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
import com.intellij.util.system.OS
import org.elixir_lang.NameArity
import org.elixir_lang.beam.binaryToTerm
import org.elixir_lang.coverage.ExpanderProgress
import org.elixir_lang.coverage.macroName
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.language_level.ElixirLanguageFeature.MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.inspect
import org.elixir_lang.psi.ElixirFile
import java.io.File

/**
 * Ecto's query, Repo and schema macros as the expander expands them and as Elixir's compile of the Mix project in
 * [FIXTURE] does: each `Macro.expand_once/2` step of every function body in `lib/queries.ex`, the functions and macros
 * of its Queries, Repo and schema modules, what a schema's functions return and
 * `Ecto.Repo.Supervisor.compile_config/2` through `lib/probe.ex`'s macros, and the warnings the compile prints. The
 * project's dependencies are those of the leg's lock, which `getEctoFixtureDeps` fetches; a leg whose Elixir has no lock
 * asserts nothing.
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
                legAppEnv(),
                file = { queries.virtualFile.path },
                // The project's root as the file system gives it, as it gives `file`.
                directories = { queries.virtualFile.parent.parent.path },
                clauses = { holder.clauses(queries, it, legInputs) },
                os = OS.CURRENT,
            )

            for ((form, chain) in steps) {
                val reached = entered[form]
                val nodes = chain.map { ElixirAst.fromOtp(it, TextRange.EMPTY_RANGE, values = true)!! }

                run.compiling[QUERIES] = Compiling(nodes.first(), level)
                for (index in chain.indices) {
                    val step = "$form ${index + 1}"

                    expected += "$step: " + (STOPS[step] ?: chain.getOrNull(index + 1)?.let { compared(it, compiled) } ?: UNEXPANDED)
                    actual += "$step: " +
                        if (reached == null) {
                            "not reached"
                        } else {
                            // The oracle's env is the one-line `def`'s, at the body's line.
                            val env = givenAt(reached.second, lineIn(chain.first()), level)

                            described(macroExpandOnce(nodes[index], reached.first, env, run), compiled)
                        }
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

            // `q_no_on`'s `join/4` stops at the `inspect/1` of its `:on` warning, and where Ecto sets `@after_compile
            // Ecto.Schema` the schemas' exports are unknown, so its calls stop earlier: each leaves the exports unknown.
            // [testQueriesStoresWhatElixirGives] reads what the module stored.
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

    /** On a leg whose Ecto sets `@after_compile Ecto.Schema`, the schemas' exports are unknown, so Queries' calls stop earlier. */
    fun testQueriesStoresWhatElixirGives() {
        val compiled = compiled ?: return

        if (compiled.afterCompile) return

        val info = etf(File(compiled.oracle, "info.etf")) as OtpErlangMap
        val result = expansion(ProjectModules(project), file(corpus!!, compiled, ASKER)).modules.single()

        assertEquals(exportsIn(info, QUERIES), stored(result))
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
                legAppEnv(),
                clauses = { holder.clauses(asker, it, legInputs) },
            )

            described(macroExpandOnce(ElixirAst.fromOtp(call, TextRange.EMPTY_RANGE)!!, ExState.empty(level), env, run), compiled)
        }

        assertEquals(compared(expanded, compiled), actual)
    }

    /**
     * Ecto up to 3.12 sets `@after_compile Ecto.Schema`, whose callback the expander doesn't follow, so another file can't
     * read a schema's exports there; [testEachSchemaStoresWhatElixirGives] reads what its module stored.
     */
    fun testEachSchemaExportsWhatElixirGives() {
        val compiled = compiled ?: return
        val info = etf(File(compiled.oracle, "info.etf")) as OtpErlangMap
        val holder = ProjectModules(project)
        val asker = file(corpus!!, compiled, ASKER)

        val expected = SCHEMAS.map { module ->
            if (compiled.afterCompile) "$module ${ModuleExports.Unreadable}" else "$module ${exportsIn(info, module)}"
        }
        val actual = SCHEMAS.map { module ->
            when (val exports = exports(holder, asker, module)) {
                is ModuleExports.Present -> "$module functions: ${names(exports.functions)}; macros: ${names(exports.macros)}"
                else -> "$module $exports"
            }
        }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    fun testEachSchemaStoresWhatElixirGives() {
        val compiled = compiled ?: return
        val info = etf(File(compiled.oracle, "info.etf")) as OtpErlangMap
        val structs = etf(File(compiled.oracle, "structs.etf")) as OtpErlangMap
        val results = expansion(ProjectModules(project), file(corpus!!, compiled, "lib/schema.ex")).modules.associateBy { it.module }

        val expected = SCHEMAS.map { module ->
            val keys = (structs.get(OtpErlangAtom(module)) as OtpErlangList).elements().map { (it as OtpErlangAtom).atomValue() }

            "$module ${exportsIn(info, module)}; struct: ${keys.joinToString(", ")}"
        }
        val actual = SCHEMAS.map { module ->
            val result = results.getValue(module)
            val struct = (result.struct as? ModuleStruct.Present)?.fields?.sorted()?.joinToString(", ") ?: "${result.struct}"

            "$module ${stored(result)}; struct: $struct"
        }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    /** `__struct__/0`, `__changeset__/0` and `__schema__/1,2` as a macro's body calls them, so the expander's answer is a value. */
    fun testEachCallOfASchemasFunctionsIsElixirs() {
        val compiled = compiled ?: return

        // Their exports can't be read, as testEachSchemaExportsWhatElixirGives says.
        if (compiled.afterCompile) return

        val calls = (etf(File(compiled.oracle, "calls.etf")) as OtpErlangList).elements().map { it as OtpErlangTuple }
        val expected = calls.map {
            // `:erlang.phash2/1` of the schema's fields, whose value the expander can't match.
            val answer = if (HASH in inspect(it.elementAt(0))) "opaque $CALLED" else compared(it.elementAt(1), compiled)

            "${inspect(it.elementAt(0))} $answer"
        }
        val actual = called(compiled, calls.map { it.elementAt(0) }).zip(calls) { answer, call -> "${inspect(call.elementAt(0))} $answer" }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    fun testCanAwaitModuleCompilationIsTheCompiles() {
        val compiled = compiled ?: return
        val (call, expanded) = (etf(File(compiled.oracle, "can_await.etf")) as OtpErlangTuple).elements()

        assertEquals("true", inspect(expanded))
        assertEquals(compared(expanded, compiled), called(compiled, listOf(call)).single())
    }

    /** It pins that such a module is left unknown, not wrong, until `Module.create/3` is answered. */
    fun testAnInlineEmbedStopsItsModule() {
        val compiled = compiled ?: return
        val corpus = corpus!!
        val holder = ProjectModules(project)
        val inline = file(corpus, compiled, "lib/inline.ex")
        val result = expansion(holder, inline).modules.single()
        val ended = result.ended as ExpansionResult.Ended.Stopped

        assertEquals("embedded_schema do", inline.text.substring(ended.at.meta.origin.startOffset).substringBefore("\n"))
        assertEquals(ModuleExports.Unreadable, exports(holder, file(corpus, compiled, ASKER), INLINE))
    }

    /** It pins that nothing tells the expander of a module a `__deriving__` macro defines, where Elixir has loaded it. */
    fun testADerivedImplementationIsAbsent() {
        val compiled = compiled ?: return
        val holder = ProjectModules(project)
        val asker = file(corpus!!, compiled, ASKER)
        val derived = etf(File(compiled.oracle, "derived.etf")) as OtpErlangMap

        for (module in DERIVED) {
            assertEquals(module, OtpErlangAtom(true), derived.get(OtpErlangAtom(module)))
            assertEquals(module, ModuleExports.Absent, exports(holder, asker, module))
        }
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

    /** What expanding each of [calls], `lib/probe.ex`'s macros, once gives. */
    private fun called(compiled: Compiled, calls: List<OtpErlangObject>): List<String> {
        val corpus = corpus!!
        val level = legLevel()
        val asker = file(corpus, compiled, ASKER)
        val holder = ProjectModules(project)
        val empty = Env.empty(level, legKernel)
        val env = empty.copy(requires = (empty.requires + PROBE).distinct().sorted())

        return corpus.read {
            val run = Run(
                level,
                ExpansionObserver.NONE,
                { holder.exports(asker, it, legInputs) },
                { holder.struct(asker, it, legInputs) },
                legAppEnv(),
                clauses = { holder.clauses(asker, it, legInputs) },
            )

            calls.map { described(macroExpandOnce(ElixirAst.fromOtp(it, TextRange.EMPTY_RANGE)!!, ExState.empty(level), env, run), compiled) }
        }
    }

    /**
     * The fixture compiled in [project], with its dependencies, and what Elixir's oracle scripts wrote in [oracle].
     * [afterCompile] is whether the leg's Ecto sets `@after_compile Ecto.Schema`.
     */
    private class Compiled(val project: File, val stderr: String, val oracle: File, val afterCompile: Boolean)

    private companion object {
        val FIXTURE: File = File("testData/org/elixir_lang/expander/ecto").absoluteFile
        const val QUERIES = "Elixir.EctoFixture.Queries"
        const val REPO = "Elixir.EctoFixture.Repo"
        const val PROBE = "Elixir.EctoFixture.Probe"
        const val INLINE = "Elixir.EctoFixture.Inline"
        const val CALLED = "EctoFixture.Probe.called/3"
        const val HASH = ":hash"

        /** Not lib/probe.ex or lib/schema.ex, whose own modules are compiling, so not loaded, while it asks. */
        const val ASKER = "lib/queries.ex"
        val SCHEMAS = listOf("Comment", "Post", "Tag", "Author", "Book").map { "Elixir.EctoFixture.$it" }
        val DERIVED = listOf("Tag", "Author", "Book").map { "Elixir.Inspect.EctoFixture.$it" }
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

            return Compiled(project, stderr, oracle, etf(File(oracle, "after_compile.etf")) == OtpErlangAtom(true))
        }

        fun property(name: String): String =
            System.getProperty(name) ?: throw AssertionError("This test needs the system property $name, which Gradle's `test` sets.")

        fun etf(file: File): OtpErlangObject = binaryToTerm(file.readBytes(), 0).first

        /** The functions and macros [result] stored, as [exportsIn] gives Elixir's. */
        fun stored(result: ExpansionResult): String {
            val named = { kind: DefinitionTable.Kind -> names(result.table.entries.filterValues { it.kind == kind }.keys.toList()) }

            return "functions: ${named(DefinitionTable.Kind.DEF)}; macros: ${named(DefinitionTable.Kind.DEFMACRO)}"
        }

        fun exportsIn(info: OtpErlangMap, module: String): String {
            val exports = info.get(OtpErlangAtom(module)) as OtpErlangMap

            return "functions: ${names(exports.get(OtpErlangAtom("functions")))}; macros: ${names(exports.get(OtpErlangAtom("macros")))}"
        }

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
         * in the order they first appear with their modules kept. Where [MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER] holds, a map's
         * pairs are sorted, as Elixir's order is the atoms' index in the VM, where the expander's is term order.
         */
        fun compared(term: OtpErlangObject, compiled: Compiled): String {
            val sorts = MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER.isSufficient(legLevel())
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
         * The steps the expander stops at where Elixir expands them. `join/4` without `:on` names its schema in a warning
         * through `inspect/1`, whose text isn't known.
         */
        val STOPS = mapOf("q_no_on 1" to "opaque Ecto.Query.join/4")

        /** The `line` in [term]'s own metadata, a node's that has one. */
        fun lineIn(term: OtpErlangObject): Int =
            ((term as OtpErlangTuple).elementAt(1) as OtpErlangList).elements()
                .firstNotNullOf { (it as? OtpErlangTuple)?.takeIf { pair -> pair.elementAt(0) == LINE }?.elementAt(1) as? OtpErlangLong }
                .intValue()

        val PLACE = listOf("line", "column", "file").map(::OtpErlangAtom)
        val LINE = OtpErlangAtom("line")
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
