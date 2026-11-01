package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
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
 * The expander's view of modules that `use Phoenix.Component` as Elixir's compile of the Mix project in [FIXTURE] gives
 * it: each module's functions and macros, and through `lib/probe.ex`'s macros its `__components__/0`, `__global__?/1` and what its wrappers return.
 * The project's dependencies are those of the leg's lock, which `getPhoenixComponentFixtureDeps` fetches; a leg whose
 * Elixir has no lock asserts nothing.
 */
class PhoenixComponentFixtureTest : HeavyTestCase() {
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

    fun testEachModuleExportsWhatElixirGives() {
        val compiled = compiled ?: return
        val info = etf(File(compiled.oracle, "info.etf")) as OtpErlangMap
        val holder = ProjectModules(project)
        val asker = file(corpus!!, compiled, ASKER)

        val expected = MODULES.map { module ->
            val exports = info.get(OtpErlangAtom(module)) as OtpErlangMap

            "$module functions: ${names(exports.get(OtpErlangAtom("functions")))}; macros: ${names(exports.get(OtpErlangAtom("macros")))}"
        }
        val actual = MODULES.map { module ->
            when (val exports = exports(holder, asker, module)) {
                is ModuleExports.Present -> "$module functions: ${names(exports.functions)}; macros: ${names(exports.macros)}"
                else -> "$module $exports"
            }
        }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    /** `__components__/0`, `__global__?/1` and the components' wrappers as a macro's body calls them, so the expander's answer is a value. */
    fun testEachCallOfAComponentModulesFunctionsIsElixirs() {
        val compiled = compiled ?: return
        val corpus = corpus!!
        val level = legLevel()
        val calls = (etf(File(compiled.oracle, "calls.etf")) as OtpErlangList).elements().map { it as OtpErlangTuple }
        val asker = file(corpus, compiled, ASKER)
        val holder = ProjectModules(project)
        val empty = Env.empty(level, legKernel)
        val env = empty.copy(requires = (empty.requires + PROBE).distinct().sorted())

        val expected = calls.map { "${inspect(it.elementAt(0))} ${compared(it.elementAt(1))}" }
        val actual = corpus.read {
            val run = Run(
                level,
                ExpansionObserver.NONE,
                { holder.exports(asker, it, legInputs) },
                { holder.struct(asker, it, legInputs) },
                legAppEnv(),
                clauses = { holder.clauses(asker, it, legInputs) },
            )

            calls.map {
                val call = ElixirAst.fromOtp(it.elementAt(0), TextRange.EMPTY_RANGE)!!

                "${inspect(it.elementAt(0))} ${described(macroExpandOnce(call, ExState.empty(level), env, run))}"
            }
        }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    /** It pins that such a module is left unknown, not wrong, until the expander evaluates `~H`. */
    fun testAModuleWithASigilBeforeItsBeforeCompileStopsAtTheSigil() {
        val compiled = compiled ?: return
        val holder = ProjectModules(project)
        val result = expansion(holder, file(corpus!!, compiled, "lib/with_sigil.ex")).modules.single()
        val ended = result.ended as ExpansionResult.Ended.Stopped
        val stop = (result.units.map { it.expansion } + result.opaque).filterIsInstance<Expansion.Opaque>().first { it.at === ended.at }

        assertEquals("Phoenix.Component.sigil_H/2", macroName(stop.dispatch))
        assertFalse(result.table.entries.keys.any { it.name == "__components__" })
    }

    private fun file(corpus: PhoenixCorpus, compiled: Compiled, path: String): ElixirFile =
        corpus.psi(File(compiled.project, path)) ?: error("no Elixir file at $path")

    /** The fixture compiled in [project], with its dependencies, and what Elixir's oracle script wrote in [oracle]. */
    private class Compiled(val project: File, val oracle: File)

    private companion object {
        val FIXTURE: File = File("testData/org/elixir_lang/expander/phoenix_component").absoluteFile
        val MODULES = listOf("Remote", "Forms", "Globals").map { "Elixir.PhoenixComponentFixture.$it" }
        const val PROBE = "Elixir.PhoenixComponentFixture.Probe"

        /** Not lib/probe.ex or lib/forms.ex, whose own modules are compiling, so not loaded, while it asks. */
        const val ASKER = "lib/with_sigil.ex"

        /**
         * Compiled once for the class, since compiling LiveView takes most of a minute, and a failure kept, not compiled
         * again for each test; `null` where the leg has no lock.
         */
        val compiling: Result<Compiled?> by lazy { runCatching(::compile) }
        val compiled: Compiled? get() = compiling.getOrThrow()

        fun compile(): Compiled? {
            if (!File(FIXTURE, "locks/${LegManifest.environment("ELIXIR_VERSION")}.lock").isFile) return null

            val deps = File(property("elixir.phoenixComponentFixture.deps"))

            if (!File(deps, "phoenix_live_view").isDirectory) {
                throw AssertionError("This test needs the fixture's dependencies in $deps, which `getPhoenixComponentFixtureDeps` fetches.")
            }

            // Not under the test framework's temporary directory, which it empties after each test.
            val root = FileUtil.createTempDirectory(File("build/tmp").absoluteFile.apply { mkdirs() }, "phoenix-component-fixture", null, true)
            val project = File(root, "phoenix_component_fixture")
            val oracle = File(root, "oracle").apply { mkdirs() }

            FileUtil.copyDir(FIXTURE, project)
            FileUtil.copyDir(deps, File(project, "deps"))
            File(project, "test").mkdirs()

            // Mix reads any MIX_ variable the test JVM inherits; these are the fixture's own.
            val environment = System.getenv().filterKeys { !it.startsWith("MIX_") } + mapOf(
                "MIX_HOME" to property("elixir.phoenixComponentFixture.mixHome"),
                "MIX_ARCHIVES" to property("elixir.phoenixComponentFixture.mixArchives"),
                "MIX_ENV" to "dev",
            )

            MixProcess.run(project, environment, listOf("deps.compile"), timeoutMinutes = 15)
            MixProcess.run(project, environment, listOf("compile"))
            MixProcess.run(project, environment + ("PHOENIX_COMPONENT_ORACLE" to oracle.path), listOf("run", "--no-start", "--no-compile", "oracle/oracle.exs"))

            return Compiled(project, oracle)
        }

        fun property(name: String): String =
            System.getProperty(name) ?: throw AssertionError("This test needs the system property $name, which Gradle's `test` sets.")

        fun etf(file: File): OtpErlangObject = binaryToTerm(file.readBytes(), 0).first

        fun names(term: OtpErlangObject): String =
            names((term as OtpErlangList).elements().map { NameArity.from(it)!! })

        fun names(nameArities: List<NameArity>): String =
            nameArities.sortedWith(compareBy({ it.name }, { it.arity })).joinToString(", ") { "${it.name}/${it.arity}" }

        fun described(expanded: MacroExpanded): String =
            when (expanded) {
                is MacroExpanded.Node -> if (expanded.expanded) compared(expanded.node.toOtp()) else "unexpanded"
                MacroExpanded.Dir -> "dir"
                is MacroExpanded.Stopped -> when (val stop = expanded.expansion) {
                    is Expansion.Opaque -> "opaque ${macroName(stop.dispatch)}"
                    is Expansion.Unported -> "unported"
                    is Expansion.Error -> "raise ${stop.kind}"
                    is Expansion.Expanded -> "expanded"
                }
            }

        /** Where [MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER] holds, Elixir's map order is the atoms' index in the VM, where the expander's is term order. */
        fun compared(term: OtpErlangObject): String =
            inspect(if (MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER.isSufficient(legLevel())) sorted(term) else term)

        fun sorted(term: OtpErlangObject): OtpErlangObject =
            when (term) {
                is OtpErlangTuple -> {
                    val elements = term.elements().map(::sorted)
                    val pairs = (elements.getOrNull(2) as? OtpErlangList)?.takeIf { term.arity() == 3 && elements[0] == MAP }

                    if (pairs != null) {
                        OtpErlangTuple(arrayOf(elements[0], elements[1], OtpErlangList(pairs.elements().sortedBy(::inspect).toTypedArray())))
                    } else {
                        OtpErlangTuple(elements.toTypedArray())
                    }
                }
                is OtpErlangList -> OtpErlangList(term.elements().map(::sorted).toTypedArray())
                else -> term
            }

        val MAP = OtpErlangAtom("%{}")
    }
}
