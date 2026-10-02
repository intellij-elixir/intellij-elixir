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
            listOf(y, laterInModule, atom, inModule, earlierAtom, unique).sortedWith(VARIABLE_ORDER),
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

    @Test
    fun `each module counts its own expansions from 1, and with no module the counter is a unique integer`() {
        val counters = Counters()

        assertEquals(Env.Counter.InModule(CASE, 1), counters.next(CASE))
        assertEquals(Env.Counter.InModule(CASE, 2), counters.next(CASE))
        assertEquals(Env.Counter.InModule(KERNEL, 1), counters.next(KERNEL))
        assertEquals(Env.Counter.Unique(1), counters.next(null))
        assertEquals(2L, counters.count(CASE))
        assertEquals(1L, counters.count(null))
        assertEquals(0L, counters.count("Elixir.Other"))
    }

    @Test
    fun `linify gives a quote of the receiver's context the counter, and every node with metadata the line`() {
        fun quote(context: String, vararg keys: Meta.Key) =
            call("quote", listOf(ElixirAst.ListNode(meta(), listOf(integer(1)))), *keys, entry("context", atom(context)))

        assertLinified(quote(KERNEL, line(3), entry("counter", COUNTER_VALUE)), quote(KERNEL))
        assertLinified(quote("Elixir.Other", line(3)), quote("Elixir.Other"))
    }

    @Test
    fun `linify gives a variable of the receiver's context the counter, but not _ or another context's`() {
        assertLinified(
            linifiable("x", KERNEL_CONTEXT, line(3), entry("counter", COUNTER_VALUE)),
            linifiable("x", KERNEL_CONTEXT),
        )
        assertLinified(linifiable("_", KERNEL_CONTEXT, line(3)), linifiable("_", KERNEL_CONTEXT))
        assertLinified(linifiable("x", ElixirAst.VariableContext.Nil, line(3)), linifiable("x", ElixirAst.VariableContext.Nil))
    }

    @Test
    fun `linify gives aliases and alias, import and require calls the counter, keeping a counter they have`() {
        assertLinified(alias(line(3), entry("counter", COUNTER_VALUE)), alias())
        assertLinified(alias(line(3), entry("counter", counterMeta(atom(CASE), 9))), alias(entry("counter", counterMeta(atom(CASE), 9))))

        for (lexical in listOf("alias", "import", "require")) {
            assertLinified(
                call(lexical, listOf(alias(line(3), entry("counter", COUNTER_VALUE))), line(3), entry("counter", COUNTER_VALUE)),
                call(lexical, listOf(alias())),
            )
        }

        assertLinified(call("foo", listOf(alias(line(3), entry("counter", COUNTER_VALUE))), line(3)), call("foo", listOf(alias())))
    }

    @Test
    fun `linify goes into lists and pairs, which have no metadata of their own`() {
        val pair = { variable: ElixirAst -> ElixirAst.Tuple(meta(), listOf(variable, integer(1))) }

        assertLinified(
            ElixirAst.ListNode(meta(), listOf(pair(linifiable("x", KERNEL_CONTEXT, line(3), entry("counter", COUNTER_VALUE))))),
            ElixirAst.ListNode(meta(), listOf(pair(linifiable("x", KERNEL_CONTEXT)))),
        )
    }

    @Test
    fun `linify keeps a node's own line, and adds none for line 0`() {
        val located = Meta.Key.Location(Meta.Position(7, 2))

        assertLinified(call("foo", emptyList(), located), call("foo", emptyList(), located))
        assertLinified(
            linifiable("x", KERNEL_CONTEXT, entry("counter", COUNTER_VALUE)),
            linifiable("x", KERNEL_CONTEXT),
            line = 0,
        )
    }

    @Test
    fun `linify keeps whether the expander built a node`() {
        val source = call("foo", emptyList())
        val built = ElixirAst.Call(meta(built = true), ElixirAst.Literal.Atom(meta(), "foo"), emptyList())

        assertEquals(false, linifyWithContextCounter(3, KERNEL, COUNTER, source).meta.built)
        assertEquals(true, linifyWithContextCounter(3, KERNEL, COUNTER, built).meta.built)
    }

    private fun assertLinified(expected: ElixirAst, node: ElixirAst, line: Int = 3) =
        assertEquals(expected.toOtp(), linifyWithContextCounter(line, KERNEL, COUNTER, node).toOtp())

    private fun call(name: String, arguments: List<ElixirAst>, vararg keys: Meta.Key) =
        ElixirAst.Call(meta(*keys), ElixirAst.Literal.Atom(meta(), name), arguments)

    private fun alias(vararg keys: Meta.Key) = ElixirAst.Alias(meta(*keys), listOf(ElixirAst.Literal.Atom(meta(), "Foo")))

    private fun integer(value: Long) = ElixirAst.Literal.Integer(meta(), value.toBigInteger())

    private fun linifiable(name: String, context: ElixirAst.VariableContext, vararg keys: Meta.Key) =
        ElixirAst.Call(meta(*keys), ElixirAst.Literal.Atom(meta(), name), null, context)

    private fun meta(vararg keys: Meta.Key, built: Boolean = false): Meta {
        val position = Meta.Position(1, 1)

        return Meta(TextRange(0, 1), position, position, keys.toList(), built)
    }

    private fun line(line: Long) = entry("line", Meta.Value.Integer(line))

    private fun entry(name: String, value: Meta.Value) = Meta.Key.Entry(name, value)

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

        val KERNEL_CONTEXT = ElixirAst.VariableContext.Atom(KERNEL)
        val COUNTER = Env.Counter.InModule(CASE, 1)
        val COUNTER_VALUE = Meta.Value.Tuple(listOf(Meta.Value.Atom(CASE), Meta.Value.Integer(1)))
    }
}
