package org.elixir_lang.psi.impl.qualifiable_alias

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.caretAlias

/**
 * Tests for [ItemPresentation].
 */
class ItemPresentationTest : PlatformTestCase() {

    /**
     * Regression test for an infinite loop in the climb from an alias to its call when a
     * [org.elixir_lang.psi.QualifiableAlias] is used as the qualifier in a
     * [org.elixir_lang.psi.QualifiedMultipleAliases] expression (e.g. `alias Prefix.{SubModule}`).
     *
     * The qualifier `Prefix` has parent chain:
     *   `ElixirAlias` -> `ElixirAccessExpression` -> `QualifiedMultipleAliases` -> `alias` Call
     *
     * Before the fix, the climb called itself with the same `QualifiedMultipleAliases`, an infinite
     * tail-recursive loop that froze IntelliJ.
     */
    fun testGetPresentableTextForQualifierInQualifiedMultipleAliases() {
        myFixture.configureByFile("qualifier_in_qualified_multiple_aliases.ex")

        val elixirAlias = myFixture.caretAlias()

        // getPresentableText() must terminate (not infinite-loop) and return the alias presentation
        val presentation = elixirAlias.presentation
        assertNotNull("Expected a non-null ItemPresentation for ElixirAlias", presentation)
        val presentableText = presentation.presentableText
        assertEquals("alias Prefix", presentableText)
    }

    /**
     * `As` in `alias "Foo", as: As` has no module to be unaliased to. Its presentation falls back to the
     * alias's own name rather than printing a placeholder, and asking for it is not an error.
     */
    fun testGetPresentableTextForAliasOfSomethingThatIsNotAModule() {
        myFixture.configureByText("string_alias.ex", "alias \"Foo\", as: A<caret>s\n")
        val elixirAlias = myFixture.caretAlias()

        val (presentableText, errors) = captureLoggedErrors { elixirAlias.presentation.presentableText }

        assertEmpty("presenting an alias of a non-module is not an error", errors)
        assertEquals("As", presentableText)
    }

    /** The name an `as:` gives shows the whole call it is in, whichever way the aliased module is written. */
    fun testGetPresentableTextForAsOfAnAlias() = assertAsPresentation("alias Target, as: <caret>First", "alias Target, as: First")

    fun testGetPresentableTextForAsOfAQualifiedAlias() = assertAsPresentation("alias Foo.Bar, as: <caret>B", "alias Foo.Bar, as: B")

    fun testGetPresentableTextForAsOfAnAtom() = assertAsPresentation("alias :erlang, as: <caret>E", "alias :erlang, as: E")

    fun testGetPresentableTextForAsOfAParenthesizedAlias() =
        assertAsPresentation("alias (Target), as: <caret>First", "alias Target, as: First")

    fun testGetPresentableTextForAsOfADoublyParenthesizedQualifiedAlias() =
        assertAsPresentation("alias ((Foo.Bar)), as: <caret>B", "alias Foo.Bar, as: B")

    fun testGetPresentableTextForAsOfAParenthesizedName() =
        assertAsPresentation("alias Foo.Bar, as: (<caret>B)", "alias Foo.Bar, as: B")

    /** Elixir quotes `alias (Target)` as `alias Target`, so the module's alias is presented as it is there. */
    fun testGetPresentableTextForAParenthesizedAliasedModule() =
        assertAsPresentation("alias (<caret>Target)", "alias Target")

    fun testGetPresentableTextForAsOfAnEnclosingModule() = assertAsPresentation(
        "defmodule Outer do\n  alias __MODULE__, as: <caret>Me\nend",
        "alias Outer, as: Me",
    )

    private fun assertAsPresentation(code: String, expected: String) {
        myFixture.configureByText("as_presentation.ex", "$code\n")

        assertEquals(expected, myFixture.caretAlias().presentation.presentableText)
    }

    override fun getTestDataPath(): String =
        "testData/org/elixir_lang/psi/impl/qualifiable_alias/item_presentation"
}
