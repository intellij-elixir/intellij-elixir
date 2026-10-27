package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import org.elixir_lang.NameArity
import org.elixir_lang.beam.binaryToTerm
import org.elixir_lang.coverage.ExpanderProgress
import org.elixir_lang.coverage.macroName
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_MAP_IN_VM_ORDER
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.inspect
import org.elixir_lang.psi.ElixirFile
import java.io.File

/**
 * Ecto's query and Repo macros as the expander expands them and as Elixir's compile of the Mix project in [FIXTURE]
 * does: each `Macro.expand_once/2` step of every function body in `lib/queries.ex`, the functions and macros of its
 * Queries and Repo modules, `Ecto.Repo.Supervisor.compile_config/2` through `lib/probe.ex`'s macro, and the warnings the
 * compile prints. The project's dependencies are those of the leg's lock, which `getEctoFixtureDeps` fetches; a leg
 * whose Elixir has no lock asserts nothing.
 */
class EctoFixtureTest : HeavyTestCase() {
    private var corpus: PhoenixCorpus? = null

    override fun setUp() {
        super.setUp()
        compiled?.let { corpus = ExpanderProgress.setUp(project, module, testRootDisposable, it.project) }
    }

    override fun tearDown() {
        try {
            if (corpus != null) LegSdk.tearDown(module)
        } finally {
            corpus = null
            super.tearDown()
        }
    }

    /** Each step's input is Elixir's, so a difference is in that step alone, not in one before it. */
    fun testEveryStepOfEveryQueryIsElixirs() {
        val compiled = compiled ?: return
        val corpus = corpus!!
        val level = legLevel()
        val steps = File(compiled.oracle, "steps").listFiles()!!.sortedBy { it.name }.associate {
            it.nameWithoutExtension to (etf(it) as OtpErlangList).elements().toList()
        }
        val queries = file(corpus, compiled, "lib/queries.ex")

        // A `def` the oracle's prewalk doesn't match has no steps, and would drop out of the comparison unseen.
        assertEquals(DEF.findAll(queries.text).map { it.groupValues[1] }.sorted().toList(), steps.keys.toList())

        val holder = ProjectModules(project)
        val entered = HashMap<String, Pair<ExState, Env>>()
        val stops = stops(LegManifest.environment("ELIXIR_VERSION"))
        val expected = mutableListOf<String>()
        val actual = mutableListOf<String>()

        corpus.read {
            holder.observe(queries, legInputs) { node, state, env ->
                val name = env.function?.name

                if (env.module == QUERIES && name != null && name !in entered && node is ElixirAst.Call && node.arguments != null) {
                    val body = steps[name]?.first()

                    if (body != null && compared(node.toOtp(), compiled) == compared(body, compiled)) entered[name] = state to env
                }
            }

            val run = Run(
                level,
                ExpansionObserver.NONE,
                { holder.exports(queries, it, legInputs) },
                { holder.struct(queries, it, legInputs) },
                file = queries.virtualFile.path,
                // The project's root as the file system gives it, as it gives `file`.
                directories = { queries.virtualFile.parent.parent.path },
                clauses = { holder.clauses(queries, it, legInputs) },
            )

            for ((form, chain) in steps) {
                val reached = entered[form]
                val nodes = chain.map { ElixirAst.fromOtp(it, TextRange.EMPTY_RANGE, values = true)!! }

                run.compiling[QUERIES] = Compiling(nodes.first(), level)
                for (index in chain.indices) {
                    val step = "$form ${index + 1}"

                    expected += "$step: " + (stops[step] ?: chain.getOrNull(index + 1)?.let { compared(it, compiled) } ?: UNEXPANDED)
                    actual += "$step: " +
                        if (reached == null) "not reached" else described(macroExpandOnce(nodes[index], reached.first, reached.second, run), compiled)
                }
            }
        }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    fun testTheQueriesAndRepoModulesExportWhatElixirGives() {
        val compiled = compiled ?: return
        val corpus = corpus!!
        val info = etf(File(compiled.oracle, "info.etf")) as OtpErlangMap
        val probe = file(corpus, compiled, "lib/probe.ex")
        val holder = ProjectModules(project)
        val modules = listOf(QUERIES, REPO)

        val expected = modules.map { module ->
            val exports = info.get(OtpErlangAtom(module)) as OtpErlangMap

            // The schema module's expansion declines, so the `__schema__(:source)` call Queries' functions make can't be
            // answered.
            if (module == QUERIES) return@map "$module ${ModuleExports.Unreadable}"

            "$module functions: ${names(exports.get(OtpErlangAtom("functions")))}; macros: ${names(exports.get(OtpErlangAtom("macros")))}"
        }
        val actual = modules.map { module ->
            when (val exports = exports(holder, probe, module)) {
                is ModuleExports.Present ->
                    "$module functions: ${names(exports.functions)}; macros: ${names(exports.macros)}"
                else -> "$module $exports"
            }
        }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    fun testCompileConfigIsElixirs() {
        val compiled = compiled ?: return
        val corpus = corpus!!
        val level = legLevel()
        val (call, expanded) = (etf(File(compiled.oracle, "compile_config.etf")) as OtpErlangTuple).elements()
        // Not from lib/probe.ex, whose own modules are compiling, so not loaded, while it asks.
        val asker = file(corpus, compiled, "lib/queries.ex")
        val holder = ProjectModules(project)
        val empty = Env.empty(level, legKernel)
        val env = empty.copy(requires = (empty.requires + PROBE).distinct().sorted())

        val actual = corpus.read {
            val run = Run(
                level,
                ExpansionObserver.NONE,
                { holder.exports(asker, it, legInputs) },
                { holder.struct(asker, it, legInputs) },
                clauses = { holder.clauses(asker, it, legInputs) },
            )

            described(macroExpandOnce(ElixirAst.fromOtp(call, TextRange.EMPTY_RANGE)!!, ExState.empty(level), env, run), compiled)
        }

        assertEquals(compared(expanded, compiled), actual)
    }

    /** Each warning as its message and where it is reported, as Elixir prints a warning given a stacktrace. */
    fun testTheWarningsAreTheCompilesInTextAndLocation() {
        val compiled = compiled ?: return
        val corpus = corpus!!
        val holder = ProjectModules(project)

        val expected = compiled.stderr.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
            .map { block -> block.lines().map(String::trim).filter(String::isNotEmpty) }
            .filter { it.firstOrNull()?.startsWith(WARNING) == true }
            .map { it.joinToString("\n").removePrefix(WARNING) }
            // It names the schema through `inspect/1`, whose text isn't known, so `IO.warn/2` declines.
            .filterNot { it.startsWith(MISSING_ON) }
            .sorted()
        val actual = File(compiled.project, "lib").listFiles()!!.sortedBy { it.name }
            .flatMap { ProjectModules.results(expansion(holder, file(corpus, compiled, "lib/${it.name}"))).toList() }
            .flatMap { it.warnings }
            .map(::rendered)
            .sorted()

        assertEquals(expected.joinToString("\n\n"), actual.joinToString("\n\n"))
    }

    private fun file(corpus: PhoenixCorpus, compiled: Compiled, path: String): ElixirFile =
        corpus.psi(File(compiled.project, path)) ?: error("no Elixir file at $path")

    /** The fixture compiled in [project], with its dependencies, and what Elixir's oracle scripts wrote in [oracle]. */
    private class Compiled(val project: File, val stderr: String, val oracle: File)

    private companion object {
        val FIXTURE: File = File("testData/org/elixir_lang/expander/ecto").absoluteFile
        const val QUERIES = "Elixir.EctoFixture.Queries"
        const val REPO = "Elixir.EctoFixture.Repo"
        const val PROBE = "Elixir.EctoFixture.Probe"
        const val UNEXPANDED = "unexpanded"
        const val MISSING_ON = "missing `:on` in join"
        const val WARNING = "warning: "
        const val FILE = "{file}"

        /**
         * Compiled once for the class, since compiling Ecto takes most of a minute, and a failure kept, not compiled
         * again for each test; `null` where the leg has no lock.
         */
        val compiling: Result<Compiled?> by lazy { runCatching(::compile) }
        val compiled: Compiled? get() = compiling.getOrThrow()

        fun compile(): Compiled? {
            if (!File(FIXTURE, "locks/${LegManifest.environment("ELIXIR_VERSION")}.lock").isFile) return null

            val deps = File(property("elixir.ectoFixture.deps"))

            if (!File(deps, "ecto").isDirectory) {
                throw AssertionError("This test needs the Ecto fixture's dependencies in $deps, which `getEctoFixtureDeps` fetches.")
            }

            // Not under the test framework's temporary directory, which it empties after each test.
            val root = FileUtil.createTempDirectory(File("build/tmp").absoluteFile.apply { mkdirs() }, "ecto-fixture", null, true)
            val project = File(root, "ecto_fixture")
            val oracle = File(root, "oracle").apply { mkdirs() }

            FileUtil.copyDir(FIXTURE, project)
            FileUtil.copyDir(deps, File(project, "deps"))
            File(project, "test").mkdirs()

            // Mix reads any MIX_ variable the test JVM inherits; these are the fixture's own.
            val environment = System.getenv().filterKeys { !it.startsWith("MIX_") } + mapOf(
                "MIX_HOME" to property("elixir.ectoFixture.mixHome"),
                "MIX_ARCHIVES" to property("elixir.ectoFixture.mixArchives"),
                "MIX_ENV" to "dev",
            )

            // Apart, so the stderr kept is the project's own.
            MixProcess.run(project, environment, listOf("deps.compile"), timeoutMinutes = 15)
            val stderr = MixProcess.run(project, environment, listOf("compile")).stderr

            for (script in listOf("oracle.exs", "compile_config.exs")) {
                MixProcess.run(project, environment + ("ECTO_FIXTURE_ORACLE" to oracle.path), listOf("run", "--no-start", "--no-compile", "oracle/$script"))
            }

            return Compiled(project, stderr, oracle)
        }

        fun property(name: String): String =
            System.getProperty(name) ?: throw AssertionError("This test needs the system property $name, which Gradle's `test` sets.")

        fun etf(file: File): OtpErlangObject = binaryToTerm(file.readBytes(), 0).first

        fun names(term: OtpErlangObject): String =
            names((term as OtpErlangList).elements().map { NameArity.from(it)!! })

        fun names(nameArities: List<NameArity>): String =
            nameArities.sortedWith(compareBy({ it.name }, { it.arity })).joinToString(", ") { "${it.name}/${it.arity}" }

        fun described(expanded: MacroExpanded, compiled: Compiled): String =
            when (expanded) {
                is MacroExpanded.Node -> if (expanded.expanded) compared(expanded.node.toOtp(), compiled) else UNEXPANDED
                MacroExpanded.Dir -> "dir"
                is MacroExpanded.Stopped -> when (val stop = expanded.expansion) {
                    is Expansion.Opaque -> "opaque ${macroName(stop.dispatch)}"
                    is Expansion.Unported -> "unported ${compared(stop.at.toOtp(), compiled).take(200)}"
                    is Expansion.Error -> "raise ${stop.kind}"
                    is Expansion.Expanded -> "expanded"
                }
            }

        fun rendered(warning: Warning): String {
            val line = lineOf(warning.at.meta)

            return when (warning) {
                is Warning.Deprecated -> "deprecated ${warning.module}.${warning.name}/${warning.arity} line $line"
                is Warning.CompileEnv -> "compile_env ${warning.name}/${warning.arity} line $line"
                is Warning.Emitted ->
                    (listOf(warning.message) + (warning.stacktrace as? OtpErlangList)?.elements().orEmpty().map(::entry))
                        .joinToString("\n")
            }
        }

        /** A stacktrace entry as `Exception.format_stacktrace_entry/1` prints it: `file:line: Module.function/arity`. */
        fun entry(entry: OtpErlangObject): String {
            val (module, function, arity, location) = (entry as OtpErlangTuple).elements()
            val keyword = (location as OtpErlangList).elements().associate {
                ((it as OtpErlangTuple).elementAt(0) as OtpErlangAtom).atomValue() to it.elementAt(1)
            }
            val file = when (val charlist = keyword.getValue("file")) {
                is OtpErlangString -> charlist.stringValue()
                else -> (charlist as OtpErlangList).elements().joinToString("") { Character.toString((it as OtpErlangLong).intValue()) }
            }
            val name = (module as OtpErlangAtom).atomValue().let { if (it.startsWith("Elixir.")) it.removePrefix("Elixir.") else ":$it" }

            return "$file:${keyword.getValue("line")}: $name.${(function as OtpErlangAtom).atomValue()}/$arity"
        }

        /**
         * [term] as Elixir's `inspect` prints it once each side's differences that aren't the expansion's are gone: a
         * node's line, column and file, the path of the file the steps are in, and the hygiene counters' values, numbered
         * in the order they first appear with their modules kept. Where [ESCAPED_MAP_IN_VM_ORDER] holds, a map's pairs
         * are sorted, as Elixir's order is the atoms' index in the VM.
         */
        fun compared(term: OtpErlangObject, compiled: Compiled): String {
            val sorts = ESCAPED_MAP_IN_VM_ORDER.isSufficient(legLevel())
            val path = File(compiled.project, "lib/queries.ex").path

            return inspect(CounterNumbers().renumbered(withoutPlace(term, path, sorts)))
        }

        fun withoutPlace(term: OtpErlangObject, path: String, sorts: Boolean): OtpErlangObject =
            when (term) {
                is OtpErlangTuple -> {
                    val elements = term.elements().map { withoutPlace(it, path, sorts) }
                    val meta = elements.getOrNull(1) as? OtpErlangList

                    if (term.arity() == 3 && meta != null) {
                        val kept = meta.elements().filterNot { (it as? OtpErlangTuple)?.elementAt(0) in PLACE }
                        val pairs = (elements[2] as? OtpErlangList)?.takeIf {
                            sorts && elements[0] == OtpErlangAtom("%{}") && it.lastTail == null
                        }
                        val last = pairs?.let { OtpErlangList(it.elements().sortedBy(::inspect).toTypedArray()) } ?: elements[2]

                        OtpErlangTuple(arrayOf(elements[0], OtpErlangList(kept.toTypedArray()), last))
                    } else {
                        OtpErlangTuple(elements.toTypedArray())
                    }
                }
                is OtpErlangList -> {
                    val elements = term.elements().map { withoutPlace(it, path, sorts) }.toTypedArray()

                    term.lastTail?.let { OtpErlangList(elements, withoutPlace(it, path, sorts)) } ?: OtpErlangList(elements)
                }
                // Elixir writes the path with `/` and, on Windows, a lower-case drive.
                is OtpErlangBinary -> if (FileUtil.pathsEqual(String(term.binaryValue()), path)) OtpErlangBinary(FILE.toByteArray()) else term
                else -> term
            }

        /**
         * The steps the expander stops at where Elixir expands them, on the leg with [elixir]. `join/4` without `:on`
         * names its schema in a warning through `inspect/1`, whose text isn't known. On 1.20.4, `Macro.to_string/1`
         * classifies an atom through `atom_to_list/1` and `elixir_config:identifier_tokenizer/0`, which aren't answered.
         */
        fun stops(elixir: String): Map<String, String> =
            mapOf("q_no_on 1" to "opaque Ecto.Query.join/4") +
                if (elixir == "1.20.4") {
                    mapOf(
                        "q_from 2" to "opaque Ecto.Query.select/3",
                        "q_parens 2" to "opaque Ecto.Query.where/3",
                        "q_pipe 2" to "opaque Ecto.Query.where/3",
                    )
                } else {
                    emptyMap()
                }

        val PLACE = listOf("line", "column", "file").map(::OtpErlangAtom)
        val COUNTER = OtpErlangAtom("counter")
        val DEF = Regex("^\\s*def (\\w+)", RegexOption.MULTILINE)
    }

    /** Each hygiene counter numbered in the order it first appears, its module kept. */
    private class CounterNumbers {
        private val numbers = HashMap<OtpErlangObject, Long>()

        fun renumbered(term: OtpErlangObject): OtpErlangObject =
            when (term) {
                is OtpErlangTuple ->
                    if (term.arity() == 2 && term.elementAt(0) == COUNTER) {
                        OtpErlangTuple(arrayOf(COUNTER, counter(term.elementAt(1))))
                    } else {
                        OtpErlangTuple(term.elements().map(::renumbered).toTypedArray())
                    }
                is OtpErlangList -> {
                    val elements = term.elements().map(::renumbered).toTypedArray()

                    term.lastTail?.let { OtpErlangList(elements, renumbered(it)) } ?: OtpErlangList(elements)
                }
                else -> term
            }

        private fun counter(value: OtpErlangObject): OtpErlangObject {
            val number = OtpErlangLong(numbers.getOrPut(value) { numbers.size.toLong() })

            return when {
                value is OtpErlangTuple && value.arity() == 2 -> OtpErlangTuple(arrayOf(value.elementAt(0), number))
                else -> number
            }
        }
    }
}
