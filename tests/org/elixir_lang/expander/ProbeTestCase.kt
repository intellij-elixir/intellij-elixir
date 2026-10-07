package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.lowering.inspect
import org.elixir_lang.parser_definition.ParsingTestCase
import org.elixir_lang.psi.ElixirFile

/** A test that compiles case bodies through a [ProbeHarness] parsing them with this fixture. */
abstract class ProbeTestCase : ParsingTestCase() {
    protected val harness = ProbeHarness { createPsiFile(getTestName(false), it) as ElixirFile }

    /** The invariant each compile keeps: none of [layout]'s modules is loaded or open, and the same names compile. */
    protected fun assertLeftNothingBehind(layout: ProbeHarness.Layout) {
        val modules = listOf(layout.probeModule, layout.hookModule) + layout.cases.indices.map(layout::caseModule)

        assertEquals("left behind", emptyList<String>(), loadedOrOpen(modules))

        val again = harness.attempt(
            ProbeHarness.Layout(layout.token, layout.cases.map { ProbeHarness.Case(":ok") }, hook = layout.hook)
        )

        assertEquals("the same names again", OtpErlangAtom("ok"), again.compiled.status)
        assertEquals("left behind again", emptyList<String>(), loadedOrOpen(modules))
    }

    /** `Code.compiler_options/0` as a compile sees it. */
    protected fun compilerOptions(): OtpErlangObject {
        val compiled = harness.compileSource("IntellijElixir.Quoter.Probe.send(__ENV__, Code.compiler_options())")

        assertEquals("compiler options compile status", OtpErlangAtom("ok"), compiled.status)

        return compiled.messages.single()
    }

    private fun loadedOrOpen(modules: List<String>): List<String> {
        val compiled = harness.compileSource(
            modules.joinToString("\n") {
                val module = it.removePrefix("Elixir.")

                "IntellijElixir.Quoter.Probe.send(__ENV__, {$module, :code.is_loaded($module), Module.open?($module)})"
            }
        )

        assertEquals("invariant compile status", OtpErlangAtom("ok"), compiled.status)

        return compiled.messages.map { it as OtpErlangTuple }
            .filterNot { it.elementAt(1) == FALSE && it.elementAt(2) == FALSE }
            .map(::inspect)
    }

    private companion object {
        val FALSE = OtpErlangAtom("false")
    }
}
