package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.junit.Assert.assertEquals
import org.junit.Test

/** Erlang orders atoms by code point, so U+F900 comes before U+20000, though its UTF-16 unit is higher. */
class TermOrderTest {
    private val bmp = "豈"
    private val supplementary = String(Character.toChars(0x20000))

    @Test
    fun atomsCompareByCodePoint() =
        assertEquals(listOf(bmp, supplementary), listOf(supplementary, bmp).sortedWith(ATOM_ORDER))

    @Test
    fun variablesCompareNamesThenContextsByCodePoint() =
        assertEquals(
            listOf(Variable("a", atom(bmp)), Variable("a", atom(supplementary)), Variable(bmp, Variable.NIL)),
            listOf(Variable(bmp, Variable.NIL), Variable("a", atom(supplementary)), Variable("a", atom(bmp)))
                .sortedWith(VARIABLE_ORDER),
        )

    @Test
    fun counterModulesCompareByCodePoint() =
        assertEquals(
            listOf(Variable("a", inModule(bmp)), Variable("a", inModule(supplementary))),
            listOf(Variable("a", inModule(supplementary)), Variable("a", inModule(bmp))).sortedWith(VARIABLE_ORDER),
        )

    @Test
    fun nameAritiesCompareNamesByCodePointThenArities() =
        assertEquals(
            listOf(NameArity(bmp, 1), NameArity(supplementary, 0), NameArity(supplementary, 1)),
            listOf(NameArity(supplementary, 1), NameArity(bmp, 1), NameArity(supplementary, 0))
                .sortedWith(NAME_ARITY_ORDER),
        )

    private fun atom(text: String) = Variable.Context.Atom(text)

    private fun inModule(module: String) = Variable.Context.Counter(Env.Counter.InModule(module, 1))
}
