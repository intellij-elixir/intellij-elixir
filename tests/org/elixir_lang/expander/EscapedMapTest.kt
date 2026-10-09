package org.elixir_lang.expander

import com.intellij.openapi.util.TextRange
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.junit.Assert.assertEquals
import org.junit.Test

/** `elixir_quote:escape/3` of a map lists its keys in term order, whatever order the port collected them in. */
class EscapedMapTest {
    private val s = Synthetic(Meta(TextRange(0, 0), Meta.Position(1, 1), Meta.Position(1, 1), emptyList()))

    @Test
    fun `an escaped map lists its keys by code point`() {
        val map = s.escapedMap(listOf("z", "é", "a", "__struct__", "B").map { it to s.atom(it) })

        assertEquals(listOf("B", "__struct__", "a", "z", "é"), keys(map))
    }

    @Test
    fun `an escaped map keeps each key's value`() {
        val map = s.escapedMap(listOf("b" to s.atom("one"), "a" to s.atom("two")))

        assertEquals(listOf("a" to "two", "b" to "one"), pairs(map))
    }

    private fun keys(map: ElixirAst.Call) = pairs(map).map { it.first }

    private fun pairs(map: ElixirAst.Call) = map.arguments!!.map { entry ->
        val (key, value) = (entry as ElixirAst.Tuple).elements

        (key as ElixirAst.Literal.Atom).name to (value as ElixirAst.Literal.Atom).name
    }
}
