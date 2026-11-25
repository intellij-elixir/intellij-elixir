package org.elixir_lang.code_insight.completion

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.psi.call.Call

/**
 * What basic completion offers where Elixir reads an operator being typed: the visible operators starting with it,
 * after `op/` the operator itself, and in a bitstring segment's type, after `::` or a `-`, at a name's start or on the
 * line after, the modifiers not yet written. Outside a bitstring after `-`, and after a step's `//`, the expander also
 * offers Kernel's operators, which the walk does not find in these fixtures, so the `-` row asserts `---` among those
 * offered and the `//` row each name once. In a segment's value and after a `+` in its type, it also offers Kernel's
 * functions starting with the name, so those rows assert the modifiers left out and, in the value, `bits` among those
 * offered.
 */
class OperatorCompletionTest : ExpanderSettingTestCase() {
    fun testAnOperatorsStartOffersTheVisibleOperatorsStartingWithIt() = assertAnOperatorsStartOffersTheVisibleOperatorsStartingWithIt()

    fun testAnOperatorsStartOffersTheVisibleOperatorsStartingWithItWithTheExpanderOn() =
        withTheExpanderOn(::assertAnOperatorsStartOffersTheVisibleOperatorsStartingWithIt)

    fun testInsideAnOperatorTheOperatorsStartingWithWhatIsBeforeTheCaretAreOffered() =
        assertInsideAnOperatorTheOperatorsStartingWithWhatIsBeforeTheCaretAreOffered()

    fun testInsideAnOperatorTheOperatorsStartingWithWhatIsBeforeTheCaretAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInsideAnOperatorTheOperatorsStartingWithWhatIsBeforeTheCaretAreOffered)

    fun testAfterALiteralTheImportedOperatorsAreOffered() = assertAfterALiteralTheImportedOperatorsAreOffered()

    fun testAfterALiteralTheImportedOperatorsAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertAfterALiteralTheImportedOperatorsAreOffered)

    fun testChoosingAnOperatorReplacesTheTypedOperator() = assertChoosingAnOperatorReplacesTheTypedOperator()

    fun testChoosingAnOperatorReplacesTheTypedOperatorWithTheExpanderOn() =
        withTheExpanderOn(::assertChoosingAnOperatorReplacesTheTypedOperator)

    fun testAfterAnOperatorsSlashTheOperatorIsOfferedAndChoosingItChangesNothing() =
        assertAfterAnOperatorsSlashTheOperatorIsOfferedAndChoosingItChangesNothing()

    fun testAfterAnOperatorsSlashTheOperatorIsOfferedAndChoosingItChangesNothingWithTheExpanderOn() =
        withTheExpanderOn(::assertAfterAnOperatorsSlashTheOperatorIsOfferedAndChoosingItChangesNothing)

    fun testAfterAnOperatorsSlashAtTwoCaretsChoosingItChangesNothing() =
        assertAfterAnOperatorsSlashAtTwoCaretsChoosingItChangesNothing()

    fun testAfterAnOperatorsSlashAtTwoCaretsChoosingItChangesNothingWithTheExpanderOn() =
        withTheExpanderOn(::assertAfterAnOperatorsSlashAtTwoCaretsChoosingItChangesNothing)

    fun testAfterAStepsSlashTabbingTheOperatorChangesNothing() = assertAfterAStepsSlashTabbingTheOperatorChangesNothing()

    fun testAfterAStepsSlashTabbingTheOperatorChangesNothingWithTheExpanderOn() =
        withTheExpanderOn(::assertAfterAStepsSlashTabbingTheOperatorChangesNothing)

    fun testInABitstringSegmentsTypeTheModifiersAreOffered() = assertInABitstringSegmentsTypeTheModifiersAreOffered()

    fun testInABitstringSegmentsTypeTheModifiersAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInABitstringSegmentsTypeTheModifiersAreOffered)

    fun testAfterAModifiersDashTheModifiersNotWrittenAreOffered() = assertAfterAModifiersDashTheModifiersNotWrittenAreOffered()

    fun testAfterAModifiersDashTheModifiersNotWrittenAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertAfterAModifiersDashTheModifiersNotWrittenAreOffered)

    fun testBeforeAWrittenModifierTheModifiersAreOffered() = assertBeforeAWrittenModifierTheModifiersAreOffered()

    fun testBeforeAWrittenModifierTheModifiersAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertBeforeAWrittenModifierTheModifiersAreOffered)

    fun testBeforeAWrittenSizeTheModifiersAreOffered() = assertBeforeAWrittenSizeTheModifiersAreOffered()

    fun testBeforeAWrittenSizeTheModifiersAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertBeforeAWrittenSizeTheModifiersAreOffered)

    fun testAfterADashBeforeAWrittenModifierTheModifiersNotWrittenBeforeAreOffered() =
        assertAfterADashBeforeAWrittenModifierTheModifiersNotWrittenBeforeAreOffered()

    fun testAfterADashBeforeAWrittenModifierTheModifiersNotWrittenBeforeAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertAfterADashBeforeAWrittenModifierTheModifiersNotWrittenBeforeAreOffered)

    fun testOutsideABitstringADashOffersTheOperatorsStartingWithIt() = assertOutsideABitstringADashOffersTheOperatorsStartingWithIt()

    fun testOutsideABitstringADashOffersTheOperatorsStartingWithItWithTheExpanderOn() =
        withTheExpanderOn(::assertOutsideABitstringADashOffersTheOperatorsStartingWithIt)

    fun testOnTheLineAfterASegmentsTypeOperatorTheModifiersAreOffered() = assertOnTheLineAfterASegmentsTypeOperatorTheModifiersAreOffered()

    fun testOnTheLineAfterASegmentsTypeOperatorTheModifiersAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertOnTheLineAfterASegmentsTypeOperatorTheModifiersAreOffered)

    fun testAModifiersStartOffersTheModifiersStartingWithIt() = assertAModifiersStartOffersTheModifiersStartingWithIt()

    fun testAModifiersStartOffersTheModifiersStartingWithItWithTheExpanderOn() =
        withTheExpanderOn(::assertAModifiersStartOffersTheModifiersStartingWithIt)

    fun testAModifiersStartAfterADashLeavesOutTheModifiersWritten() = assertAModifiersStartAfterADashLeavesOutTheModifiersWritten()

    fun testAModifiersStartAfterADashLeavesOutTheModifiersWrittenWithTheExpanderOn() =
        withTheExpanderOn(::assertAModifiersStartAfterADashLeavesOutTheModifiersWritten)

    fun testAModifierWrittenInFullIsNotOfferedAgain() = assertAModifierWrittenInFullIsNotOfferedAgain()

    fun testAModifierWrittenInFullIsNotOfferedAgainWithTheExpanderOn() = withTheExpanderOn(::assertAModifierWrittenInFullIsNotOfferedAgain)

    fun testAModifiersStartBeforeADashOffersTheModifiersStartingWithIt() =
        assertAModifiersStartBeforeADashOffersTheModifiersStartingWithIt()

    fun testAModifiersStartBeforeADashOffersTheModifiersStartingWithItWithTheExpanderOn() =
        withTheExpanderOn(::assertAModifiersStartBeforeADashOffersTheModifiersStartingWithIt)

    fun testAfterAPlusInASegmentsTypeNoModifierIsOffered() = assertAfterAPlusInASegmentsTypeNoModifierIsOffered()

    fun testAfterAPlusInASegmentsTypeNoModifierIsOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertAfterAPlusInASegmentsTypeNoModifierIsOffered)

    fun testASegmentsValueOffersNamesNotModifiers() = assertASegmentsValueOffersNamesNotModifiers()

    fun testASegmentsValueOffersNamesNotModifiersWithTheExpanderOn() = withTheExpanderOn(::assertASegmentsValueOffersNamesNotModifiers)

    private fun assertAnOperatorsStartOffersTheVisibleOperatorsStartingWithIt() {
        configure(arrows("x <~<caret>"))

        val items = myFixture.itemsWithPattern("<~")
        assertSameElements(items.map(LookupElement::getLookupString), "<~", "<~>")
        items.forEach { assertInstanceOf(it.psiElement, Call::class.java) }
    }

    private fun assertInsideAnOperatorTheOperatorsStartingWithWhatIsBeforeTheCaretAreOffered() {
        configure(arrows("x <~<caret>> y"))

        assertSameElements(myFixture.lookupStringsWithPattern("<~"), "<~", "<~>")
    }

    private fun assertAfterALiteralTheImportedOperatorsAreOffered() {
        configure(arrows("1 <~<caret>"))

        assertSameElements(myFixture.lookupStringsWithPattern("<~"), "<~", "<~>")
    }

    private fun assertChoosingAnOperatorReplacesTheTypedOperator() {
        configure(arrows("x <~<caret> y"))

        myFixture.chooseItem("<~", "<~>", Lookup.NORMAL_SELECT_CHAR)

        myFixture.checkResult(arrows("x <~><caret> y"))
    }

    private fun assertAfterAnOperatorsSlashTheOperatorIsOfferedAndChoosingItChangesNothing() {
        configure(arrows("x <~/<caret>"))

        assertSameElements(myFixture.lookupStringsWithPattern("<~"), "<~")

        myFixture.chooseItem("<~", "<~", Lookup.NORMAL_SELECT_CHAR)

        myFixture.checkResult(arrows("x <~/<caret>"))
    }

    private fun assertAfterAnOperatorsSlashAtTwoCaretsChoosingItChangesNothing() {
        val text = arrows("x <~/<caret>\n                y <~/<caret>")
        configure(text)

        myFixture.chooseItem("<~", "<~", Lookup.NORMAL_SELECT_CHAR)

        myFixture.checkResult(text)
    }

    private fun assertAfterAStepsSlashTabbingTheOperatorChangesNothing() {
        configure(
            """
            defmodule Slash do
              def left / right, do: {left, right}
            end

            defmodule Outer do
              def g(x) do
                import Slash
                1..x//<caret>2
              end
            end
            """.trimIndent()
        )
        val expected = myFixture.editor.document.text

        assertSameElements(myFixture.lookupStringsWithPattern("/").distinct(), "/")

        myFixture.chooseItem("/", "/", Lookup.REPLACE_SELECT_CHAR)

        assertEquals(expected, myFixture.editor.document.text)
        assertEquals(expected.indexOf("//2") + 2, myFixture.caretOffset)
    }

    private fun assertInABitstringSegmentsTypeTheModifiersAreOffered() {
        configure(bitstring("x ::<caret>"))

        val items = myFixture.itemsWithPattern("")
        assertSameElements(items.map(LookupElement::getLookupString), MODIFIERS)
        items.forEach { assertNull("`${it.lookupString}` is no definition", it.psiElement) }
    }

    private fun assertAfterAModifiersDashTheModifiersNotWrittenAreOffered() {
        configure(bitstring("x::big-signed-<caret>"))

        assertSameElements(myFixture.lookupStringsWithPattern(""), MODIFIERS - setOf("big", "signed"))
    }

    private fun assertBeforeAWrittenModifierTheModifiersAreOffered() {
        configure(bitstring("x::<caret>big"))

        assertSameElements(myFixture.shownLookupStrings(), MODIFIERS)
    }

    private fun assertBeforeAWrittenSizeTheModifiersAreOffered() {
        configure(bitstring("x::<caret>size(8)"))

        assertSameElements(myFixture.shownLookupStrings(), MODIFIERS)
    }

    private fun assertAfterADashBeforeAWrittenModifierTheModifiersNotWrittenBeforeAreOffered() {
        configure(bitstring("x::big-<caret>signed"))

        assertSameElements(myFixture.shownLookupStrings(), MODIFIERS - "big")
    }

    private fun assertOnTheLineAfterASegmentsTypeOperatorTheModifiersAreOffered() {
        configure(bitstring("x ::\n          <caret>"))

        assertSameElements(myFixture.shownLookupStrings(), MODIFIERS)
    }

    private fun assertAModifiersStartOffersTheModifiersStartingWithIt() {
        configure(bitstring("x :: bi<caret>"))

        assertSameElements(myFixture.shownLookupStrings(), "big", "binary", "bitstring")
    }

    private fun assertAModifiersStartAfterADashLeavesOutTheModifiersWritten() {
        configure(bitstring("x::big-bi<caret>"))

        assertSameElements(myFixture.shownLookupStrings(), "binary", "bitstring")
    }

    private fun assertAModifierWrittenInFullIsNotOfferedAgain() {
        configure(bitstring("x::big<caret>"))

        assertEmpty(myFixture.shownLookupStrings())
    }

    private fun assertAModifiersStartBeforeADashOffersTheModifiersStartingWithIt() {
        configure(bitstring("x :: bi<caret>-integer"))

        assertSameElements(myFixture.shownLookupStrings(), "big", "binary", "bitstring")
    }

    private fun assertAfterAPlusInASegmentsTypeNoModifierIsOffered() {
        configure(bitstring("x :: big+bi<caret>"))

        assertDoesntContain(myFixture.shownLookupStrings(), "binary", "bitstring")
    }

    private fun assertASegmentsValueOffersNamesNotModifiers() {
        configure("defmodule Outer do\n  def f(bits), do: <<bi<caret>>>\nend\n")

        val shown = myFixture.shownLookupStrings()
        assertContainsElements(shown, "bits")
        assertDoesntContain(shown, "binary", "bitstring")
    }

    private fun assertOutsideABitstringADashOffersTheOperatorsStartingWithIt() {
        configure(
            """
            defmodule Dashes do
              def left --- right, do: {left, right}
            end

            defmodule Outer do
              def g(x) do
                import Dashes
                x -<caret>
              end
            end
            """.trimIndent()
        )

        assertContainsElements(myFixture.lookupStringsWithPattern("-"), "---")
        assertDoesntContain(myFixture.shownLookup().items.map(LookupElement::getLookupString), "big")
    }

    private fun configure(text: String) {
        myFixture.configureByText("operator.ex", text)
    }

    private companion object {
        /** IEx's bitstring modifiers. */
        val MODIFIERS = setOf(
            "big", "binary", "bitstring", "integer", "float", "little", "native", "signed", "size", "unit", "unsigned", "utf8",
            "utf16", "utf32",
        )

        /** [segment] in a function head's bitstring. */
        fun bitstring(segment: String): String = "defmodule Outer do\n  def f(<<$segment>>), do: x\nend\n"

        /** [line] in a function that imports `Arrows`, which defines `<~>`, `<~` and `~>`. */
        fun arrows(line: String): String =
            """
            defmodule Arrows do
              def left <~> right, do: {left, right}
              def left <~ right, do: {left, right}
              def left ~> right, do: {left, right}
            end

            defmodule Outer do
              def g(x, y) do
                import Arrows
                $line
              end
            end
            """.trimIndent()
    }
}
