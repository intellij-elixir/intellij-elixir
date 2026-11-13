package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import org.elixir_lang.coverage.ExpanderProgress
import org.elixir_lang.coverage.macroName
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.language_level.ElixirLanguageFeature.MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.inspect
import org.elixir_lang.psi.Import.Term
import java.io.File

/**
 * The expander's view of modules that `use Phoenix.Component` as Elixir's compile of the Mix project in [FIXTURE] gives
 * it: each module's functions and macros, and through `lib/probe.ex`'s macros its `__components__/0`, `__global__?/1` and what its wrappers return;
 * and how each module of `routes/`, which Mix doesn't compile, ends on its own.
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
        val asker = corpus!!.psi(compiled.project, ASKER)

        val expected = MODULES.map { module -> "$module ${exportsIn(info, module)}" }
        val actual = MODULES.map { module -> "$module ${exported(exports(holder, asker, module))}" }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    /** `__components__/0`, `__global__?/1` and the components' wrappers as a macro's body calls them, so the expander's answer is a value. */
    fun testEachCallOfAComponentModulesFunctionsIsElixirs() {
        val compiled = compiled ?: return
        val corpus = corpus!!
        val level = legLevel()
        val calls = (etf(File(compiled.oracle, "calls.etf")) as OtpErlangList).elements().map { it as OtpErlangTuple }
        val asker = corpus.psi(compiled.project, ASKER)
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
        val result = expansion(holder, corpus!!.psi(compiled.project, "lib/with_sigil.ex")).modules.single()
        val ended = result.ended as ExpansionResult.Ended.Stopped
        val stop = (result.units.map { it.expansion } + result.opaque).filterIsInstance<Expansion.Opaque>().first { it.at === ended.at }

        assertEquals("Phoenix.Component.sigil_H/2", macroName(stop.dispatch))
        assertFalse(result.table.entries.keys.any { it.name == "__components__" })
    }

    /**
     * Each module of `routes/`, which writes `~p` its own way, ends as Elixir's compile of it does, with its `__info__`
     * and what `@phoenix_verified_routes` ends up holding. A route's `warn_location` isn't compared: its column is `nil`
     * here, and only `@after_verify`, which the IDE never runs, reads it.
     */
    fun testEachVerifiedRouteFormEndsAsElixirsCompileDoes() {
        val compiled = compiled ?: return
        val oracle = etf(File(compiled.oracle, "routes.etf")) as OtpErlangMap
        val holder = ProjectModules(project)
        val level = legLevel()
        val forms = File(compiled.project, "routes").listFiles()!!.map { it.nameWithoutExtension }.sorted()

        val expected = forms.map { form ->
            val outcome = oracle.get(OtpErlangBinary(form.toByteArray())) as OtpErlangMap
            val raised = outcome.get(OtpErlangAtom("raised"))

            "$form " + when {
                // Left unknown, not wrong, until the expander evaluates the `~H` of the module they import.
                form == "html" || form == "liveview" -> "stopped import/1"
                raised == OtpErlangAtom("nil") ->
                    "compiled ${exported(outcome)}; routes: ${routes(outcome.get(OtpErlangAtom("routes")))}"
                else -> "raised ${(raised as OtpErlangAtom).atomValue()}"
            }
        }
        val actual = forms.map { form ->
            val result = expansion(holder, corpus!!.psi(compiled.project, "routes/$form.ex")).modules.single()

            "$form " + when (val ended = result.ended) {
                ExpansionResult.Ended.Compiled -> {
                    val routes = when (val value = result.attributes.final["phoenix_verified_routes"]) {
                        null -> "absent"
                        is AttributeValue.Held -> routes(value.value)
                        is AttributeValue.Known -> if (value.term == Term.List(emptyList())) "[]" else "${value.term}"
                        AttributeValue.Unknown -> "unknown"
                    }

                    "compiled ${stored(result)}; routes: $routes"
                }
                is ExpansionResult.Ended.Raised -> when (val kind = ended.error.kind) {
                    null -> "raised an exception that isn't known"
                    else -> "raised ${if (ErrorKinds.isCompileError(kind, level)) "Elixir.CompileError" else kind}"
                }
                else -> ending(result)
            }
        }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

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
        val compiling: Result<MixFixture?> by lazy { runCatching(::compile) }
        val compiled: MixFixture? get() = compiling.getOrThrow()

        /** The fixture compiled, with its dependencies, and what Elixir's oracle script wrote. */
        fun compile(): MixFixture? {
            if (!hasLegLock(FIXTURE)) return null

            val fixture = MixFixture("phoenix-component-fixture", "phoenixComponentFixture", "phoenix_live_view", "phoenix_component_fixture")

            FileUtil.copyDir(FIXTURE, fixture.project)
            fixture.compile()
            fixture.runOracle("PHOENIX_COMPONENT_ORACLE")

            return fixture
        }

        /** The route structs of the list [term], each field but `warn_location` in name order. */
        fun routes(term: OtpErlangObject): String =
            (term as OtpErlangList).elements().joinToString(", ", "[", "]") { route ->
                val map = route as OtpErlangMap

                map.keys().filter { it != OtpErlangAtom("warn_location") }
                    .map { inspect(it) to inspect(map.get(it)) }
                    .sortedBy { it.first }
                    .joinToString(", ", "%{", "}") { "${it.first} => ${it.second}" }
            }

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
