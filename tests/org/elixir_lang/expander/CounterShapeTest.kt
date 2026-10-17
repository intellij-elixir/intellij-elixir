package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.util.TextRange
import junit.framework.TestCase
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.lowering.ParserOptions

/**
 * A variable's `counter` is read where a macro's result converts to AST and again where the expander reads its context.
 * Both readings agree on every shape: the conversion keeps the metadata exactly where the expander reads a counter.
 */
class CounterShapeTest : TestCase() {
    fun testAnInteger() = assertReadAlike(Meta.Value.Integer(7), Env.Counter.Unique(7))

    fun testAModuleAndAnInteger() =
        assertReadAlike(
            Meta.Value.Tuple(listOf(Meta.Value.Atom("Elixir.A"), Meta.Value.Integer(3))),
            Env.Counter.InModule("Elixir.A", 3),
        )

    fun testAnEmptyTuple() = assertReadAlike(Meta.Value.Tuple(emptyList()), null)

    fun testAPairWithNoModule() =
        assertReadAlike(Meta.Value.Tuple(listOf(Meta.Value.Integer(1), Meta.Value.Integer(2))), null)

    fun testAnAtom() = assertReadAlike(Meta.Value.Atom("c"), null)

    fun testAList() = assertReadAlike(Meta.Value.List(listOf(Meta.Value.Integer(1))), null)

    private fun assertReadAlike(counter: Meta.Value, expected: Env.Counter?) {
        val meta = Meta(ORIGIN, POSITION, POSITION, listOf(Meta.Key.Entry("counter", counter)))
        val variable = OtpErlangTuple(arrayOf(OtpErlangAtom("x"), meta.toOtp(ParserOptions.DEFAULT), OtpErlangAtom("nil")))
        val converted = ElixirAst.fromOtp(variable, ORIGIN)

        assertEquals(expected, counterOf(meta))
        assertEquals(expected, converted?.meta?.let(::counterOf))
        assertEquals(expected != null, converted != null)
    }

    private companion object {
        val ORIGIN = TextRange(0, 1)
        val POSITION = Meta.Position(1, 1)
    }
}
