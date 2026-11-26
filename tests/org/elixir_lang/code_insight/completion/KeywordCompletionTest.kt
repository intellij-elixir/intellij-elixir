package org.elixir_lang.code_insight.completion

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.language_level.ElixirLanguageFeature
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.psi.call.Call

/**
 * What basic completion offers after an expression and a space, where Elixir reads a block keyword or a binary
 * operator being typed: IEx's six block keywords and its binary operators, starting with what is typed. Both settings
 * give the same answers: the list is Elixir's, and only the operators' definitions come from what is visible.
 */
class KeywordCompletionTest : ExpanderSettingTestCase() {
    fun testAfterAModuleNameTheKeywordsAndBinaryOperatorsAreOffered() = assertAfterAModuleNameTheKeywordsAndBinaryOperatorsAreOffered()

    fun testAfterAModuleNameTheKeywordsAndBinaryOperatorsAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertAfterAModuleNameTheKeywordsAndBinaryOperatorsAreOffered)

    fun testATypedLetterOffersTheKeywordsStartingWithIt() = assertATypedLetterOffersTheKeywordsStartingWithIt()

    fun testATypedLetterOffersTheKeywordsStartingWithItWithTheExpanderOn() =
        withTheExpanderOn(::assertATypedLetterOffersTheKeywordsStartingWithIt)

    fun testInsideAWordOperatorItIsOffered() = assertInsideAWordOperatorItIsOffered()

    fun testInsideAWordOperatorItIsOfferedWithTheExpanderOn() = withTheExpanderOn(::assertInsideAWordOperatorItIsOffered)

    fun testAWordOperatorsStartIsCompletedToIt() = assertAWordOperatorsStartIsCompletedToIt()

    fun testAWordOperatorsStartIsCompletedToItWithTheExpanderOn() = withTheExpanderOn(::assertAWordOperatorsStartIsCompletedToIt)

    fun testInAStringNothingIsOffered() = assertInAStringNothingIsOffered()

    fun testInAStringNothingIsOfferedWithTheExpanderOn() = withTheExpanderOn(::assertInAStringNothingIsOffered)

    fun testInAHeredocAfterAnInterpolationNothingIsOffered() = assertInAHeredocAfterAnInterpolationNothingIsOffered()

    fun testInAHeredocAfterAnInterpolationNothingIsOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInAHeredocAfterAnInterpolationNothingIsOffered)

    fun testInAStringsInterpolationTheKeywordsAndBinaryOperatorsAreOffered() =
        assertInAStringsInterpolationTheKeywordsAndBinaryOperatorsAreOffered()

    fun testInAStringsInterpolationTheKeywordsAndBinaryOperatorsAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInAStringsInterpolationTheKeywordsAndBinaryOperatorsAreOffered)

    fun testInAHeredocsInterpolationTheKeywordsAndBinaryOperatorsAreOffered() =
        assertInAHeredocsInterpolationTheKeywordsAndBinaryOperatorsAreOffered()

    fun testInAHeredocsInterpolationTheKeywordsAndBinaryOperatorsAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInAHeredocsInterpolationTheKeywordsAndBinaryOperatorsAreOffered)

    fun testInASigilNothingIsOffered() = assertInASigilNothingIsOffered()

    fun testInASigilNothingIsOfferedWithTheExpanderOn() = withTheExpanderOn(::assertInASigilNothingIsOffered)

    fun testBeforeAnAtomTheKeywordsAndBinaryOperatorsAreOffered() = assertBeforeAnAtomTheKeywordsAndBinaryOperatorsAreOffered()

    fun testBeforeAnAtomTheKeywordsAndBinaryOperatorsAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertBeforeAnAtomTheKeywordsAndBinaryOperatorsAreOffered)

    fun testAnImportedOperatorIsOfferedWithItsDefinition() = assertAnImportedOperatorIsOfferedWithItsDefinition()

    fun testAnImportedOperatorIsOfferedWithItsDefinitionWithTheExpanderOn() =
        withTheExpanderOn(::assertAnImportedOperatorIsOfferedWithItsDefinition)

    fun testAfterALiteralAnImportedOperatorIsOfferedWithItsDefinition() =
        assertAfterALiteralAnImportedOperatorIsOfferedWithItsDefinition()

    fun testAfterALiteralAnImportedOperatorIsOfferedWithItsDefinitionWithTheExpanderOn() =
        withTheExpanderOn(::assertAfterALiteralAnImportedOperatorIsOfferedWithItsDefinition)

    private fun assertAfterAModuleNameTheKeywordsAndBinaryOperatorsAreOffered() {
        configure("defmodule Outer <caret>do\nend\n")

        assertSameElements(myFixture.shownLookupStrings(), keywordsAndOperators())
    }

    private fun assertBeforeAnAtomTheKeywordsAndBinaryOperatorsAreOffered() {
        configure("defmodule :ok_target do\nend\n\ndefmodule Outer do\n  def g, do: foo(1 <caret>:ok)\nend\n")

        assertSameElements(myFixture.shownLookupStrings(), keywordsAndOperators())
    }

    private fun assertATypedLetterOffersTheKeywordsStartingWithIt() {
        configure("defmodule Outer do\n  def g(x) do\n    f(x) e<caret>\n  end\nend\n")

        val shown = myFixture.shownLookupStrings()
        assertContainsElements(shown, "else", "end")
        assertDoesntContain(shown, "do", "catch")
    }

    private fun assertInsideAWordOperatorItIsOffered() {
        configure("defmodule Outer do\n  def f(x) w<caret>hen x > 1 do\n    x\n  end\nend\n")

        assertSameElements(myFixture.shownLookupStrings(), "when")
    }

    private fun assertAWordOperatorsStartIsCompletedToIt() {
        configure("defmodule Outer do\n  def f(x) wh<caret> x > 1 do\n    x\n  end\nend\n")

        myFixture.complete(CompletionType.BASIC)

        myFixture.checkResult("defmodule Outer do\n  def f(x) when<caret> x > 1 do\n    x\n  end\nend\n")
    }

    private fun assertInAStringNothingIsOffered() {
        configure("defmodule Outer do\n  def g, do: \"string #{1 + 2} <caret>interp\"\nend\n")

        assertEmpty(myFixture.shownLookupStrings())
    }

    private fun assertInAHeredocAfterAnInterpolationNothingIsOffered() {
        configure("defmodule Outer do\n  def g do\n    \"\"\"\n    text #{1 + 2} <caret>more\n    \"\"\"\n  end\nend\n")

        assertEmpty(myFixture.shownLookupStrings())
    }

    private fun assertInAStringsInterpolationTheKeywordsAndBinaryOperatorsAreOffered() {
        configure("defmodule Outer do\n  def g, do: \"text #{1 <caret>}\"\nend\n")

        assertSameElements(myFixture.shownLookupStrings(), keywordsAndOperators())
    }

    private fun assertInAHeredocsInterpolationTheKeywordsAndBinaryOperatorsAreOffered() {
        configure("defmodule Outer do\n  def g do\n    \"\"\"\n    text #{1 + 2} more #{1 <caret>}\n    \"\"\"\n  end\nend\n")

        assertSameElements(myFixture.shownLookupStrings(), keywordsAndOperators())
    }

    private fun assertInASigilNothingIsOffered() {
        configure("defmodule Outer do\n  def g, do: ~w(Foo <caret>y)\nend\n")

        assertEmpty(myFixture.shownLookupStrings())
    }

    private fun assertAnImportedOperatorIsOfferedWithItsDefinition() =
        assertAnImportedOperatorIsOfferedWithItsDefinitionAfter("f(x)")

    private fun assertAfterALiteralAnImportedOperatorIsOfferedWithItsDefinition() =
        assertAnImportedOperatorIsOfferedWithItsDefinitionAfter("1")

    /** After [left] in a function that imports `Arrows`, `<~>` is offered with its definition. */
    private fun assertAnImportedOperatorIsOfferedWithItsDefinitionAfter(left: String) {
        configure(
            """
            defmodule Arrows do
              def left <~> right, do: {left, right}
            end

            defmodule Outer do
              def g(x) do
                import Arrows
                $left <caret>
              end
            end
            """.trimIndent()
        )

        val items = myFixture.shownLookup().items.associateBy(LookupElement::getLookupString)

        assertInstanceOf(items.getValue("<~>").psiElement, Call::class.java)
        assertNull("`~>` is defined by nothing visible", items.getValue("~>").psiElement)
        assertNull("`do` is no definition", items.getValue("do").psiElement)
    }

    private fun configure(text: String) {
        myFixture.configureByText("keyword.ex", text)
    }

    /** IEx's block keywords and binary operators, `**` where the file's Elixir has it. */
    private fun keywordsAndOperators(): List<String> = buildList {
        addAll(KEYWORDS)
        addAll(OPERATORS)
        if (ElixirLanguageLevelResolver.isAvailable(ElixirLanguageFeature.POWER_OPERATOR, myFixture.file)) add("**")
    }

    private companion object {
        val KEYWORDS = listOf("do", "end", "after", "catch", "else", "rescue")

        /** IEx's binary operators but `**`, which Elixir has had since 1.13. */
        val OPERATORS = listOf(
            "*", "/", "+", "-", "++", "--", "+++", "---", "..", "<>", "in", "not in", "|>", "<<<", ">>>", "<<~", "~>>",
            "<~", "~>", "<~>", "<", ">", "<=", ">=", "==", "!=", "=~", "===", "!==", "&&", "&&&", "and", "||", "|||",
            "or", "=", "=>", "|", "::", "when", "<-", "\\\\",
        )
    }
}
