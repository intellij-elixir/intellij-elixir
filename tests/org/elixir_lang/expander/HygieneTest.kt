package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.util.TextRange
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.junit.Assert.assertEquals
import org.junit.Test

/** The contexts macro output gives variables, without Elixir. */
class HygieneTest {
    @Test
    fun `var_context reads a counter ahead of the context a variable is written with`() {
        assertEquals(
            Variable("x", Variable.Context.Counter(Env.Counter.InModule(CASE, 2))),
            variable(variableNode("x", ElixirAst.VariableContext.Atom(KERNEL), counterMeta(atom(CASE), 2))),
        )
        assertEquals(
            Variable("x", Variable.Context.Counter(Env.Counter.Unique(5))),
            variable(variableNode("x", ElixirAst.VariableContext.Nil, Meta.Value.Integer(5))),
        )
    }

    @Test
    fun `var_context without a counter is the context a variable is written with`() {
        assertEquals(Variable("x", Variable.Context.Atom(KERNEL)), variable(variableNode("x", ElixirAst.VariableContext.Atom(KERNEL))))
        assertEquals(Variable("x", Variable.NIL), variable(variableNode("x", ElixirAst.VariableContext.Nil)))
    }

    @Test
    fun `a probe's variables decode with a counter, nil, an atom and an integer context`() {
        val env = OtpErlangMap(
            arrayOf<OtpErlangObject>(OtpErlangAtom("versioned_vars")),
            arrayOf<OtpErlangObject>(
                OtpErlangMap(
                    arrayOf<OtpErlangObject>(
                        tuple(OtpErlangAtom("x"), tuple(OtpErlangAtom(CASE), OtpErlangLong(3))),
                        tuple(OtpErlangAtom("x"), OtpErlangAtom("nil")),
                        tuple(OtpErlangAtom("x"), OtpErlangAtom("Elixir.Ctx")),
                        tuple(OtpErlangAtom("y"), OtpErlangLong(-576460752303423)),
                    ),
                    arrayOf<OtpErlangObject>(OtpErlangLong(0), OtpErlangLong(1), OtpErlangLong(2), OtpErlangLong(3)),
                )
            ),
        )

        assertEquals(
            mapOf(
                Variable("x", Variable.Context.Counter(Env.Counter.InModule(CASE, 3))) to 0,
                Variable("x", Variable.NIL) to 1,
                Variable("x", Variable.Context.Atom("Elixir.Ctx")) to 2,
                Variable("y", Variable.Context.Counter(Env.Counter.Unique(-576460752303423))) to 3,
            ),
            VariableClasses.observed(env),
        )
    }

    @Test
    fun `counter contexts are classes numbered by first appearance, whatever the module`() {
        fun x(module: String, n: Long) = Variable("x", Variable.Context.Counter(Env.Counter.InModule(module, n)))

        val elixir = listOf(mapOf(x("Elixir.T.Case0", 2) to 0), mapOf(x("Elixir.T.Case0", 4) to 1, x("Elixir.T.Case0", 2) to 0))
        val expander = listOf(mapOf(x(CASE, 1) to 3), mapOf(x(CASE, 1) to 3, x(CASE, 2) to 5))

        assertEquals(listOf("x/c0=0", "x/c0=0 x/c1=1"), VariableClasses.canonical(elixir))
        assertEquals(VariableClasses.canonical(elixir), VariableClasses.canonical(expander))
    }

    @Test
    fun `variables sort in Erlang's term order, an integer counter before an atom before a tuple counter`() {
        val inModule = Variable("x", Variable.Context.Counter(Env.Counter.InModule(CASE, 1)))
        val laterInModule = Variable("x", Variable.Context.Counter(Env.Counter.InModule(CASE, 2)))
        val unique = Variable("x", Variable.Context.Counter(Env.Counter.Unique(9)))
        val atom = Variable("x", Variable.NIL)
        val earlierAtom = Variable("x", Variable.Context.Atom("Elixir.Kernel"))
        val y = Variable("y", Variable.Context.Counter(Env.Counter.Unique(0)))

        assertEquals(
            listOf(unique, earlierAtom, atom, inModule, laterInModule, y),
            listOf(y, laterInModule, atom, inModule, earlierAtom, unique).sortedWith(TERM_ORDER),
        )
    }

    @Test
    fun `a variable with an atom context and a counter quotes as Elixir prints it`() {
        val node = variableNode("line", ElixirAst.VariableContext.Atom("elixir_quote"), counterMeta(atom(CASE), 7))

        assertEquals(
            tuple(
                OtpErlangAtom("line"),
                OtpErlangList(arrayOf<OtpErlangObject>(tuple(OtpErlangAtom("counter"), tuple(OtpErlangAtom(CASE), OtpErlangLong(7))))),
                OtpErlangAtom("elixir_quote"),
            ),
            node.toOtp(),
        )
    }

    private fun counterMeta(module: Meta.Value, n: Long): Meta.Value = Meta.Value.Tuple(listOf(module, Meta.Value.Integer(n)))

    private fun atom(name: String) = Meta.Value.Atom(name)

    private fun variableNode(name: String, context: ElixirAst.VariableContext, counter: Meta.Value? = null): ElixirAst.Call {
        val position = Meta.Position(1, 1)
        val keys = listOfNotNull(counter?.let { Meta.Key.Entry("counter", it) })
        val meta = Meta(TextRange(0, name.length), position, position, keys)

        return ElixirAst.Call(meta, ElixirAst.Literal.Atom(meta, name), null, context)
    }

    private fun tuple(vararg elements: OtpErlangObject) = OtpErlangTuple(elements)

    private companion object {
        const val CASE = "Elixir.Case"
        const val KERNEL = "Elixir.Kernel"
    }
}
