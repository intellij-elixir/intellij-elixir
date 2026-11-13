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
import org.elixir_lang.coverage.ExpanderProgress
import org.elixir_lang.coverage.macroName
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

                    expected += "$step: " + (chain.getOrNull(index + 1)?.let { compared(it, compiled) } ?: UNEXPANDED)
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
            // Where Ecto sets `@after_compile Ecto.Schema` the schemas' exports are unknown, so Queries' calls stop
            // earlier and leave its exports unknown. [testQueriesStoresWhatElixirGives] reads what the module stored.
            if (module == QUERIES && compiled.afterCompile) return@map "$module ${ModuleExports.Unreadable}"

            "$module ${exportsIn(info, module)}"
        }
        val actual = modules.map { module -> "$module ${exported(exports(holder, probe, module))}" }

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
        val actual = SCHEMAS.map { module -> "$module ${exported(exports(holder, asker, module))}" }

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

    /**
     * An inline `embeds_one` creates its schema with `Module.create/3`: both modules compile, storing what Elixir's do,
     * and each exports what Elixir's does to another file where Ecto sets no `@after_compile`. Where it sets one, the
     * created module's callback is code the expander doesn't follow, so what the created module defines isn't known to
     * another file, and it runs while the schema is open, so the schema's tables aren't known after it and its
     * `defstruct` stops.
     */
    fun testAnInlineEmbedCreatesItsModule() {
        val compiled = compiled ?: return
        val corpus = corpus!!
        val info = etf(File(compiled.oracle, "info.etf")) as OtpErlangMap
        val holder = ProjectModules(project)
        val results = expansion(holder, file(corpus, compiled, "lib/inline.ex")).modules.withNested().associateBy { it.module }
        val asker = file(corpus, compiled, ASKER)

        val expected = INLINES.map { module ->
            val stops = module == INLINE && compiled.afterCompile

            "$module " + if (stops) "stopped defstruct/1" else "compiled ${exportsIn(info, module)}"
        } + INLINES.map { module ->
            "$module " + if (compiled.afterCompile) ModuleExports.Unreadable else exportsIn(info, module)
        }
        val actual = INLINES.map { module ->
            val result = results[module]

            "$module " + when {
                result == null -> "absent"
                result.ended == ExpansionResult.Ended.Compiled -> "compiled ${stored(result)}"
                else -> ending(result)
            }
        } + INLINES.map { "$it ${exported(exports(holder, asker, it))}" }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    /**
     * What an inline embed's module defines is declared, for another file's calls, where its code is written: a `def`
     * the `embeds_one` block holds on its own line, as Elixir's compiled module declares it, and what Ecto's macros
     * inject into the module in Ecto's code, as for any macro's output. Elixir's compiled module declares those at the
     * `embeds_one` instead, since a quote without `location: :keep` takes its caller's line.
     */
    fun testAnInlineEmbedsDefinitionsAreDeclaredWhereTheirCodeIsWritten() {
        val compiled = compiled ?: return
        val corpus = corpus!!
        val declared = (etf(File(compiled.oracle, "declared.etf")) as OtpErlangMap).get(OtpErlangAtom("$INLINE.Meta"))
        val asker = corpus.psi(compiled.project, "lib/inline_asker.ex")
        val written = declaredAt(declared, "shout")
        val calls = listOf("Meta.shout(meta)", "Meta.__schema__(:fields)", "Meta.__changeset__()", "Meta.__struct__()")

        val expected = listOf("shout $written") +
            listOf("__schema__", "__changeset__", "__struct__").map { "$it $ECTO_SCHEMA" }
        val actual = calls.map { call ->
            val lines = ExpanderSourceRows.read {
                val found = ExpanderSource.candidates(ExpanderSourceRows.use(asker, call, call), false)

                // A line of Ecto's decompiled code is its version's.
                ExpanderSourceRows.declaredAt(found, compiled.project) { it != ECTO_SCHEMA }
            }

            "${call.substringAfter('.').substringBefore('(')} $lines"
        }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
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

        val expected = compiled.stderr.replace("\r\n", "\n").split(Regex("\n\\s*\n")).asSequence()
            .map { block -> block.lines().map(String::trim).filter(String::isNotEmpty) }
            .filter { it.firstOrNull()?.startsWith(WARNING) == true }
            .map { it.joinToString("\n").removePrefix(WARNING) }
            // It names the schema through `inspect/1`, whose text isn't known, so `IO.warn/2` declines.
            .filterNot { it.startsWith(MISSING_ON) }
            .sorted()
        val actual = File(compiled.project, "lib").listFiles()!!.sortedBy { it.name }.asSequence()
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
        val INLINES = listOf(INLINE, "$INLINE.Meta")
        const val ECTO_SCHEMA = "_build/dev/lib/ecto/ebin/Elixir.Ecto.Schema.beam"
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
            if (!hasLegLock(FIXTURE)) return null

            val fixture = MixFixture("ecto-fixture", "ectoFixture", "ecto", "ecto_fixture")

            FileUtil.copyDir(FIXTURE, fixture.project)
            val stderr = fixture.compile().stderr
            for (script in listOf("oracle.exs", "compile_config.exs")) fixture.runOracle("ECTO_FIXTURE_ORACLE", script)

            return Compiled(fixture.project, stderr, fixture.oracle, etf(File(fixture.oracle, "after_compile.etf")) == OtpErlangAtom(true))
        }

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
