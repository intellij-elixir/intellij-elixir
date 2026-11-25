package org.elixir_lang.code_insight.completion

import com.intellij.codeInsight.lookup.LookupElement
import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.psi.call.Call

/**
 * What basic completion offers where Elixir reads a sigil being typed: after `~`, the letter of each visible `sigil_`
 * definition and the visible operators starting with `~`; after its letter, the delimiters a sigil opens with. Both
 * settings give the same answers for the file's own definitions; only the expander finds Kernel's in this fixture.
 */
class SigilCompletionTest : ExpanderSettingTestCase() {
    fun testATildeOffersTheVisibleSigilsLetters() = assertATildeOffersTheVisibleSigilsLetters()

    fun testATildeOffersTheVisibleSigilsLettersWithTheExpanderOn() = withTheExpanderOn(::assertATildeOffersTheVisibleSigilsLetters)

    fun testATildeOnItsOwnLineOffersTheImportedSigilsLetters() = assertATildeOnItsOwnLineOffersTheImportedSigilsLetters()

    fun testATildeOnItsOwnLineOffersTheImportedSigilsLettersWithTheExpanderOn() =
        withTheExpanderOn(::assertATildeOnItsOwnLineOffersTheImportedSigilsLetters)

    fun testATildeAtATopLevelOffersTheImportedSigilsLetters() = assertATildeAtATopLevelOffersTheImportedSigilsLetters()

    fun testATildeAtATopLevelOffersTheImportedSigilsLettersWithTheExpanderOn() =
        withTheExpanderOn(::assertATildeAtATopLevelOffersTheImportedSigilsLetters)

    fun testChoosingALetterWritesTheSigil() = assertChoosingALetterWritesTheSigil()

    fun testChoosingALetterWritesTheSigilWithTheExpanderOn() = withTheExpanderOn(::assertChoosingALetterWritesTheSigil)

    fun testATildeOffersTheVisibleOperatorsStartingWithIt() = assertATildeOffersTheVisibleOperatorsStartingWithIt()

    fun testATildeOffersTheVisibleOperatorsStartingWithItWithTheExpanderOn() =
        withTheExpanderOn(::assertATildeOffersTheVisibleOperatorsStartingWithIt)

    fun testChoosingAnOperatorReplacesTheTilde() = assertChoosingAnOperatorReplacesTheTilde()

    fun testChoosingAnOperatorReplacesTheTildeWithTheExpanderOn() = withTheExpanderOn(::assertChoosingAnOperatorReplacesTheTilde)

    fun testATildeOffersKernelsSigilsLettersWithTheExpanderOn() = withTheExpanderOn {
        configure(sigils("inspect(~<caret>)"))

        assertContainsElements(myFixture.lookupStringsWithPattern(""), "s", "r", "w", "D")
    }

    fun testASigilsLetterOffersTheDelimiters() = assertASigilsLetterOffersTheDelimiters()

    fun testASigilsLetterOffersTheDelimitersWithTheExpanderOn() = withTheExpanderOn(::assertASigilsLetterOffersTheDelimiters)

    fun testChoosingADelimiterWritesIt() = assertChoosingADelimiterWritesIt()

    fun testChoosingADelimiterWritesItWithTheExpanderOn() = withTheExpanderOn(::assertChoosingADelimiterWritesIt)

    private fun assertATildeOffersTheVisibleSigilsLetters() {
        configure(sigils("inspect(~<caret>)"))

        val items = myFixture.itemsWithPattern("").filter { it.lookupString in setOf("x", "XY") }
        assertSameElements(items.map(LookupElement::getLookupString), "x", "XY")
        items.forEach { assertInstanceOf(it.psiElement, Call::class.java) }
    }

    private fun assertATildeOnItsOwnLineOffersTheImportedSigilsLetters() {
        configure(sigils("~<caret>"))

        assertContainsElements(myFixture.lookupStringsWithPattern(""), "x", "XY")
    }

    private fun assertATildeAtATopLevelOffersTheImportedSigilsLetters() {
        myFixture.configureByText(
            "sigil.exs",
            """
            defmodule Sigils do
              def sigil_x(string, _modifiers), do: string
            end

            import Sigils
            ~<caret>
            """.trimIndent(),
        )

        assertContainsElements(myFixture.lookupStringsWithPattern(""), "x")
    }

    private fun assertChoosingALetterWritesTheSigil() {
        configure(sigils("inspect(~<caret>)"))

        myFixture.chooseItem("", "x")

        myFixture.checkResult(sigils("inspect(~x<caret>)"))
    }

    private fun assertATildeOffersTheVisibleOperatorsStartingWithIt() {
        configure(sigils("x ~<caret>"))

        val items = myFixture.itemsWithPattern("~")
        assertSameElements(items.map(LookupElement::getLookupString), "~>")
        items.forEach { assertInstanceOf(it.psiElement, Call::class.java) }
    }

    private fun assertChoosingAnOperatorReplacesTheTilde() {
        configure(sigils("x ~<caret>"))

        myFixture.chooseItem("~", "~>")

        myFixture.checkResult(sigils("x ~><caret>"))
    }

    private fun assertASigilsLetterOffersTheDelimiters() {
        configure(sigils("~s<caret>"))

        val items = myFixture.itemsWithPattern("")
        assertSameElements(items.map(LookupElement::getLookupString), DELIMITERS)
        items.forEach { assertNull("`${it.lookupString}` is no definition", it.psiElement) }
    }

    private fun assertChoosingADelimiterWritesIt() {
        configure(sigils("~s<caret>"))

        myFixture.chooseItem("", "\"\"\"")

        myFixture.checkResult(sigils("~s\"\"\"<caret>"))
    }

    private fun configure(text: String) {
        myFixture.configureByText("sigil.ex", text)
    }

    private companion object {
        /** IEx's sigil delimiters. */
        val DELIMITERS = setOf("\"", "\"\"\"", "'", "'''", "(", "/", "<", "[", "{", "|")

        /** [line] in a function that imports `Sigils`, which defines `~x`, `~XY` and `~>`. */
        fun sigils(line: String): String =
            """
            defmodule Sigils do
              def sigil_x(string, _modifiers), do: string
              def sigil_XY(string, _modifiers), do: string
              def left ~> right, do: {left, right}
            end

            defmodule Outer do
              def g(x) do
                import Sigils
                $line
              end
            end
            """.trimIndent()
    }
}
