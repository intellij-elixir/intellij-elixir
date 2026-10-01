package org.elixir_lang.psi

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.Import.Filter
import org.elixir_lang.psi.Import.Filter.Invalid.Error.INVALID_EXCEPT
import org.elixir_lang.psi.Import.Filter.Invalid.Error.INVALID_ONLY
import org.elixir_lang.psi.Import.Filter.Invalid.Error.ONLY_AND_EXCEPT_GIVEN
import org.elixir_lang.psi.Import.Filter.Invalid.Error.UNSUPPORTED_OPTION
import org.elixir_lang.psi.Import.Term
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

/** [Filter.of] over expanded option terms, and [Import.optionsError]. */
class ImportFilterTermTest {
    @Test
    fun `no options bring in every public name`() =
        assertImports(options(), "f/1 f/2 g/1 | mac/1")

    @Test
    fun `an only list`() = assertImports(options("only" to list(pair("g", 1), pair("_h", 1))), "_h/1 g/1 |")

    @Test
    fun `an unexpanded only brings in nothing`() = assertImports(options("only" to Term.Unexpanded), "|")

    @Test
    fun `an unexpanded except subtracts nothing`() =
        assertImports(options("except" to Term.Unexpanded), "f/1 f/2 g/1 | mac/1")

    @Test
    fun `an unexpanded except is still given`() =
        assertInvalid(options("only" to list(pair("g", 1)), "except" to Term.Unexpanded), ONLY_AND_EXCEPT_GIVEN)

    @Test
    fun `an unexpanded element is skipped`() =
        assertImports(options("only" to list(pair("g", 1), Term.Unexpanded)), "g/1 |")

    @Test
    fun `a pair holding an unexpanded arity is skipped`() =
        assertImports(options("only" to list(pair("g", 1), Term.Pair(atom("f"), Term.Unexpanded))), "g/1 |")

    @Test
    fun `an only list holding an atom is invalid`() =
        assertInvalid(options("only" to list(atom("g"), pair("f", 1))), INVALID_ONLY)

    @Test
    fun `an except list holding a binary is invalid`() =
        assertInvalid(options("except" to list(Term.Binary("f".toByteArray()))), INVALID_EXCEPT)

    @Test
    fun `an arity that is not an integer is invalid`() =
        assertInvalid(options("only" to list(Term.Pair(atom("f"), Term.Binary("1".toByteArray())))), INVALID_ONLY)

    @Test
    fun `a variable is invalid`() = assertInvalid(options("except" to Term.Other), INVALID_EXCEPT)

    @Test
    fun `an unsupported option`() = assertInvalid(options("only" to list(), "as" to atom("Elixir.N")), UNSUPPORTED_OPTION)

    @Test
    fun `the lists as written`() {
        val only = Filter.of(options("only" to list(pair("g", 1), pair("g", 1))), LEVEL, null)
        val except = Filter.of(options("except" to list(pair("f", 1), pair("f", 1))), LEVEL, null)

        assertEquals(listOf(G, G), only.only)
        assertEquals(null, only.except)
        assertEquals(listOf(F, F), except.except)
        assertEquals(null, except.only)
    }

    @Test
    fun `the selector`() {
        assertEquals(Filter.Selector.MACROS, Filter.of(options("only" to atom("macros")), LEVEL, null).selector)
        assertEquals(null, Filter.of(options("only" to list(pair("g", 1))), LEVEL, null).selector)
    }

    @Test
    fun `sigils are a selector from 1_13`() {
        assertInvalid(options("only" to atom("sigils")), INVALID_ONLY, ElixirLanguageLevel.of("1.12.3"))
        assertImports(options("only" to atom("sigils")), "|", ElixirLanguageLevel.of("1.13.0-rc.0"))
    }

    @Test
    fun `except is checked before only from 1_17`() {
        val listAndBad = options("only" to list(pair("g", 1)), "except" to atom("bad"))
        val badAndBad = options("only" to atom("bad"), "except" to atom("bad"))

        assertInvalid(listAndBad, ONLY_AND_EXCEPT_GIVEN, ElixirLanguageLevel.of("1.16.3"))
        assertInvalid(listAndBad, INVALID_EXCEPT, ElixirLanguageLevel.of("1.17.0-rc.0"))
        assertInvalid(badAndBad, INVALID_ONLY, ElixirLanguageLevel.of("1.16.3"))
        assertInvalid(badAndBad, INVALID_EXCEPT, ElixirLanguageLevel.of("1.17.0-rc.0"))
    }

    @Test
    fun `except subtracts from the prior import`() =
        assertEquals(
            "g/1 | mac/1",
            render(
                Filter.of(options("except" to list(pair("f", 1))), LEVEL, Import.Imports(setOf(G), emptySet()))
                    .filter
                    .imports(EXPORTS)
            ),
        )

    @Test
    fun `options that are not a list`() =
        assertEquals("options_are_not_keyword", Import.optionsError(atom("foo"), listOf("only")))

    @Test
    fun `a pair whose key is not allowed`() {
        assertEquals("unsupported_option", Import.optionsError(options("as" to atom("Elixir.N")), listOf("only")))
        assertEquals(
            "unsupported_option",
            Import.optionsError(list(Term.Pair(Term.Integer(BigInteger.ONE), atom("x"))), listOf("only")),
        )
    }

    @Test
    fun `elements that are not pairs are not checked`() =
        assertEquals(null, Import.optionsError(list(atom("foo")), listOf("only")))

    private fun assertImports(options: Term.List, expected: String, level: ElixirLanguageLevel = LEVEL) =
        assertEquals(expected, render(Filter.of(options, level, null).filter.imports(EXPORTS)))

    private fun assertInvalid(options: Term.List, error: Filter.Invalid.Error, level: ElixirLanguageLevel = LEVEL) =
        assertEquals(error, (Filter.of(options, level, null).filter as? Filter.Invalid)?.error)

    private fun render(imports: Import.Imports): String =
        listOf(imports.functions, imports.macros).joinToString(" | ") { nameArities ->
            nameArities.sortedWith(compareBy({ it.name }, { it.arity })).joinToString(" ") { "${it.name}/${it.arity}" }
        }.trim()

    private companion object {
        val LEVEL: ElixirLanguageLevel = ElixirLanguageLevel.of("1.20.4")
        val F = NameArity("f", 1)
        val G = NameArity("g", 1)
        val EXPORTS = Import.Imports(setOf(F, NameArity("f", 2), G, NameArity("_h", 1)), setOf(NameArity("mac", 1)))

        fun atom(name: String) = Term.Atom(name)

        fun pair(name: String, arity: Int) = Term.Pair(atom(name), Term.Integer(BigInteger.valueOf(arity.toLong())))

        fun list(vararg elements: Term) = Term.List(elements.toList())

        fun options(vararg pairs: Pair<String, Term>) = Term.List(pairs.map { (key, value) -> Term.Pair(atom(key), value) })
    }
}
