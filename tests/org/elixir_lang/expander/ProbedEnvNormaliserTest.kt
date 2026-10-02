package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.lowering.inspect

/** An [Env] projects to the terms Elixir's env holds, checked on fields the empty env leaves empty. */
class ProbedEnvNormaliserTest : ProbeTestCase() {
    fun testAMacroAliasCounterTakenInAModuleProjectsAsTheModuleAndCount() {
        assertEquals(
            list(tuple(atom("Elixir.Baz"), tuple(tuple(atom("Elixir.M"), OtpErlangLong(1)), atom("Elixir.Foo.Bar")))),
            macroAliases(Env.MacroAlias("Elixir.Baz", Env.Counter.InModule("Elixir.M", 1), "Elixir.Foo.Bar"))
        )
    }

    fun testAMacroAliasCounterTakenOutsideAModuleProjectsAsTheInteger() {
        assertEquals(
            list(tuple(atom("Elixir.Baz"), tuple(OtpErlangLong(-576460752303423470), atom("Elixir.Foo.Bar")))),
            macroAliases(Env.MacroAlias("Elixir.Baz", Env.Counter.Unique(-576460752303423470), "Elixir.Foo.Bar"))
        )
    }

    fun testAMacroAliasFromAMacroExpandedInAModuleProjectsAsElixirHoldsIt() {
        val batch = harness.compile(
            listOf(
                """
                defprotocol P do
                  def f(x)
                end
                """.trimIndent()
            )
        )
        val observed = ProbedEnvNormaliser.observed(batch.observations.last().env, batch.probeModule)
        val observedMacroAliases = observed.getValue("macro_aliases") as OtpErlangList
        // The expansion's place in the module's count isn't what's checked: only how the counter is held.
        val n = ((observedMacroAliases.elementAt(0) as OtpErlangTuple).elementAt(1) as OtpErlangTuple)
            .elementAt(0).let { (it as OtpErlangTuple).elementAt(1) as OtpErlangLong }.longValue()
        val caseModule = batch.caseModule(0)

        assertEquals(
            inspect(observedMacroAliases),
            inspect(macroAliases(Env.MacroAlias("Elixir.P", Env.Counter.InModule(caseModule, n), "$caseModule.P")))
        )
    }

    fun testMacroAliasCountersAreNumberedAsTheyFirstAppearAcrossProbes() {
        val first = Env.MacroAlias("Elixir.A", Env.Counter.InModule("Elixir.M", 7), "Elixir.M.A")
        val second = Env.MacroAlias("Elixir.B", Env.Counter.Unique(-3), "Elixir.B")
        val third = Env.MacroAlias("Elixir.C", Env.Counter.InModule("Elixir.M", 7), "Elixir.M.C")

        assertEquals(
            listOf(
                list(tuple(atom("Elixir.A"), tuple(atom("c0"), atom("Elixir.M.A")))),
                list(
                    tuple(atom("Elixir.B"), tuple(atom("c1"), atom("Elixir.B"))),
                    tuple(atom("Elixir.C"), tuple(atom("c0"), atom("Elixir.M.C"))),
                ),
            ).map(::inspect),
            ProbedEnvNormaliser.numberedCounters(
                listOf(
                    mapOf("macro_aliases" to macroAliases(first)),
                    mapOf("macro_aliases" to macroAliases(second, third)),
                )
            ).map { inspect(it.getValue("macro_aliases")) }
        )
    }

    private fun macroAliases(vararg macroAliases: Env.MacroAlias): OtpErlangObject =
        ProbedEnvNormaliser.projected(
            Env(
                aliases = emptyList(),
                requires = emptyList(),
                functions = emptyList(),
                macros = emptyList(),
                macroAliases = macroAliases.toList(),
                context = Env.Context.NONE,
                contextModules = emptyList(),
                module = null,
                function = null,
            ),
            false,
            false,
            legLevel(),
        ).getValue("macro_aliases")
}
