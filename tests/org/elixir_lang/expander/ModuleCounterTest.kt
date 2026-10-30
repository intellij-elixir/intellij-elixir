package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.util.TextRange
import org.elixir_lang.expander.interpreter.Evaluation
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.junit.Assert.assertEquals
import org.junit.Test

/** `elixir_module:next_counter/1`, which `Macro.unique_var/2` calls, drawn from the counters the run's expansions take. */
class ModuleCounterTest {
    private val level = ElixirLanguageLevel.of("1.20.4")
    private val run = Run(level, ExpansionObserver.NONE, { ModuleExports.Absent }, { ModuleStruct.Absent }, NO_APP_ENV)
    private val evaluation = Evaluation(NODE, ExState.empty(level), Env.empty(level, KernelImports(emptyList(), emptyList())), run)

    @Test
    fun `a module being compiled counts from 1, sharing its count with its expansions`() {
        run.compiling[MODULE] = Compiling(NODE, level)
        run.counters.next(MODULE)

        assertEquals(OtpErlangTuple(arrayOf(OtpErlangAtom(MODULE), OtpErlangLong(2))), nextCounter(OtpErlangAtom(MODULE)))
        assertEquals(OtpErlangTuple(arrayOf(OtpErlangAtom(MODULE), OtpErlangLong(3))), nextCounter(OtpErlangAtom(MODULE)))
        assertEquals(3L, run.counters.count(MODULE))
    }

    @Test
    fun `nil gives a unique integer`() {
        assertEquals(OtpErlangLong(1), nextCounter(OtpErlangAtom("nil")))
        assertEquals(OtpErlangLong(2), nextCounter(OtpErlangAtom("nil")))
    }

    @Test
    fun `a module not being compiled gives a unique integer, as Elixir does when it has no table for it`() {
        assertEquals(OtpErlangLong(1), nextCounter(OtpErlangAtom(MODULE)))
        assertEquals(0L, run.counters.count(MODULE))
    }

    @Test
    fun `a term that is no atom gives a unique integer`() {
        assertEquals(OtpErlangLong(1), nextCounter(OtpErlangLong(1)))
    }

    private fun nextCounter(module: OtpErlangObject): OtpErlangObject? =
        evaluation.natives.call("elixir_module", "next_counter", listOf(module))

    private companion object {
        const val MODULE = "Elixir.Case"
        val NODE: ElixirAst = ElixirAst.fromOtp(OtpErlangAtom("nil"), TextRange.EMPTY_RANGE)!!
    }
}
