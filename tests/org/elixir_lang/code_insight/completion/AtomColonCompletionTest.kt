package org.elixir_lang.code_insight.completion

import com.intellij.codeInsight.lookup.Lookup
import org.elixir_lang.declaration.ExpanderSettingTestCase

/**
 * What basic completion offers in an atom: the module atoms starting with what is typed after its colon, or after its
 * opening quote, and choosing one writes its name after the colon, or between the quotes, as Elixir parses it there.
 * Both settings give the same answers, since the module atoms come from the index, not from what is visible at the caret.
 */
class AtomColonCompletionTest : ExpanderSettingTestCase() {
    fun testAfterTheColonEveryModuleAtomIsOffered() = assertAfterTheColonEveryModuleAtomIsOffered()

    fun testAfterTheColonEveryModuleAtomIsOfferedWithTheExpanderOn() = withTheExpanderOn(::assertAfterTheColonEveryModuleAtomIsOffered)

    fun testChoosingAfterTheColonWritesTheAtom() = assertChoosingAfterTheColonWritesTheAtom()

    fun testChoosingAfterTheColonWritesTheAtomWithTheExpanderOn() = withTheExpanderOn(::assertChoosingAfterTheColonWritesTheAtom)

    fun testAfterTheColonOfAnOperatorAtomEveryModuleAtomIsOffered() = assertAfterTheColonOfAnOperatorAtomEveryModuleAtomIsOffered()

    fun testAfterTheColonOfAnOperatorAtomEveryModuleAtomIsOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertAfterTheColonOfAnOperatorAtomEveryModuleAtomIsOffered)

    fun testInsideANameTheModuleAtomsStartingWithWhatIsTypedAreOffered() =
        assertInsideANameTheModuleAtomsStartingWithWhatIsTypedAreOffered()

    fun testInsideANameTheModuleAtomsStartingWithWhatIsTypedAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInsideANameTheModuleAtomsStartingWithWhatIsTypedAreOffered)

    fun testInsideAQuotedAtomTheModuleAtomsStartingWithWhatIsTypedAreOffered() =
        assertInsideAQuotedAtomTheModuleAtomsStartingWithWhatIsTypedAreOffered()

    fun testInsideAQuotedAtomTheModuleAtomsStartingWithWhatIsTypedAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInsideAQuotedAtomTheModuleAtomsStartingWithWhatIsTypedAreOffered)

    fun testReplacingInsideAQuotedAtomWritesTheAtom() = assertReplacingInsideAQuotedAtomWritesTheAtom()

    fun testReplacingInsideAQuotedAtomWritesTheAtomWithTheExpanderOn() = withTheExpanderOn(::assertReplacingInsideAQuotedAtomWritesTheAtom)

    fun testAtAQuotedAtomsEndTheModuleAtomsStartingWithItAreOffered() = assertAtAQuotedAtomsEndTheModuleAtomsStartingWithItAreOffered()

    fun testAtAQuotedAtomsEndTheModuleAtomsStartingWithItAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertAtAQuotedAtomsEndTheModuleAtomsStartingWithItAreOffered)

    fun testChoosingAtAQuotedAtomsEndWritesTheAtom() = assertChoosingAtAQuotedAtomsEndWritesTheAtom()

    fun testChoosingAtAQuotedAtomsEndWritesTheAtomWithTheExpanderOn() = withTheExpanderOn(::assertChoosingAtAQuotedAtomsEndWritesTheAtom)

    fun testReplacingAtAQuotedAtomsEndKeepsItsClosingQuote() = assertReplacingAtAQuotedAtomsEndKeepsItsClosingQuote()

    fun testReplacingAtAQuotedAtomsEndKeepsItsClosingQuoteWithTheExpanderOn() =
        withTheExpanderOn(::assertReplacingAtAQuotedAtomsEndKeepsItsClosingQuote)

    fun testReplacingInsideAQuotedAtomBeforeADashReplacesItsContent() =
        assertReplacingInsideAQuotedAtomBeforeADashReplacesItsContent()

    fun testReplacingInsideAQuotedAtomBeforeADashReplacesItsContentWithTheExpanderOn() =
        withTheExpanderOn(::assertReplacingInsideAQuotedAtomBeforeADashReplacesItsContent)

    fun testReplacingInsideAQuotedAtomBeforeAnInterpolationKeepsIt() = assertReplacingInsideAQuotedAtomBeforeAnInterpolationKeepsIt()

    fun testReplacingInsideAQuotedAtomBeforeAnInterpolationKeepsItWithTheExpanderOn() =
        withTheExpanderOn(::assertReplacingInsideAQuotedAtomBeforeAnInterpolationKeepsIt)

    fun testReplacingAQuotedAtomFromItsColonWritesTheAtomQuotedAsItNeeds() =
        assertReplacingAQuotedAtomFromItsColonWritesTheAtomQuotedAsItNeeds()

    fun testReplacingAQuotedAtomFromItsColonWritesTheAtomQuotedAsItNeedsWithTheExpanderOn() =
        withTheExpanderOn(::assertReplacingAQuotedAtomFromItsColonWritesTheAtomQuotedAsItNeeds)

    fun testReplacingAQuotedAtomFromItsColonWritesABareAtomBare() = assertReplacingAQuotedAtomFromItsColonWritesABareAtomBare()

    fun testReplacingAQuotedAtomFromItsColonWritesABareAtomBareWithTheExpanderOn() =
        withTheExpanderOn(::assertReplacingAQuotedAtomFromItsColonWritesABareAtomBare)

    fun testReplacingANameInAQuotedAtomsInterpolationReplacesTheName() =
        assertReplacingANameInAQuotedAtomsInterpolationReplacesTheName()

    fun testReplacingANameInAQuotedAtomsInterpolationReplacesTheNameWithTheExpanderOn() =
        withTheExpanderOn(::assertReplacingANameInAQuotedAtomsInterpolationReplacesTheName)

    fun testChoosingAnAtomThatNeedsQuotesAfterTheColonWritesItQuoted() =
        assertChoosingAnAtomThatNeedsQuotesAfterTheColonWritesItQuoted()

    fun testChoosingAnAtomThatNeedsQuotesAfterTheColonWritesItQuotedWithTheExpanderOn() =
        withTheExpanderOn(::assertChoosingAnAtomThatNeedsQuotesAfterTheColonWritesItQuoted)

    fun testInAnEmptyQuotedAtomEveryModuleAtomIsOffered() = assertInAnEmptyQuotedAtomEveryModuleAtomIsOffered()

    fun testInAnEmptyQuotedAtomEveryModuleAtomIsOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInAnEmptyQuotedAtomEveryModuleAtomIsOffered)

    fun testChoosingInAnEmptyQuotedAtomWritesTheAtom() = assertChoosingInAnEmptyQuotedAtomWritesTheAtom()

    fun testChoosingInAnEmptyQuotedAtomWritesTheAtomWithTheExpanderOn() = withTheExpanderOn(::assertChoosingInAnEmptyQuotedAtomWritesTheAtom)

    fun testReplacingInAnEmptyQuotedAtomKeepsItsQuotes() = assertReplacingInAnEmptyQuotedAtomKeepsItsQuotes()

    fun testReplacingInAnEmptyQuotedAtomKeepsItsQuotesWithTheExpanderOn() =
        withTheExpanderOn(::assertReplacingInAnEmptyQuotedAtomKeepsItsQuotes)

    fun testAfterAColonInAQuotedAtomsInterpolationsCallEveryModuleAtomIsOffered() =
        assertAfterAColonInAQuotedAtomsInterpolationsCallEveryModuleAtomIsOffered()

    fun testAfterAColonInAQuotedAtomsInterpolationsCallEveryModuleAtomIsOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertAfterAColonInAQuotedAtomsInterpolationsCallEveryModuleAtomIsOffered)

    private fun assertAfterTheColonEveryModuleAtomIsOffered() {
        configure(atoms(":<caret>ok"))

        assertSameElements(ours(), OK to "", QUOTED to "", RELEASE to "")
    }

    private fun assertChoosingAfterTheColonWritesTheAtom() =
        assertChoosing(":<caret>ok", "", OK, Lookup.REPLACE_SELECT_CHAR, ":ok_target<caret>")

    private fun assertAfterTheColonOfAnOperatorAtomEveryModuleAtomIsOffered() {
        configure(atoms(":<caret>+"))

        assertSameElements(ours(), OK to "", QUOTED to "", RELEASE to "")
    }

    private fun assertInsideANameTheModuleAtomsStartingWithWhatIsTypedAreOffered() {
        configure(atoms(":o<caret>k"))

        assertSameElements(ours(), OK to "o")
    }

    private fun assertInsideAQuotedAtomTheModuleAtomsStartingWithWhatIsTypedAreOffered() {
        configure(atoms(":\"q<caret>uoted\""))

        assertSameElements(ours(), QUOTED to "q")
    }

    private fun assertReplacingInsideAQuotedAtomWritesTheAtom() =
        assertChoosing(":\"q<caret>uoted\"", "q", QUOTED, Lookup.REPLACE_SELECT_CHAR, ":\"quoted_target<caret>\"")

    private fun assertAtAQuotedAtomsEndTheModuleAtomsStartingWithItAreOffered() {
        configure(atoms(":\"release-12<caret>\""))

        assertSameElements(ours(), RELEASE to "release-12")
    }

    private fun assertChoosingAtAQuotedAtomsEndWritesTheAtom() =
        assertChoosing(":\"release-12<caret>\"", "release-12", RELEASE, Lookup.NORMAL_SELECT_CHAR, ":\"release-12.x<caret>\"")

    private fun assertReplacingAtAQuotedAtomsEndKeepsItsClosingQuote() =
        assertChoosing(":\"release-12<caret>\"", "release-12", RELEASE, Lookup.REPLACE_SELECT_CHAR, ":\"release-12.x<caret>\"")

    private fun assertReplacingInsideAQuotedAtomBeforeADashReplacesItsContent() =
        assertChoosing(":\"rel<caret>ease-12.x\"", "rel", RELEASE, Lookup.REPLACE_SELECT_CHAR, ":\"release-12.x<caret>\"")

    private fun assertReplacingInsideAQuotedAtomBeforeAnInterpolationKeepsIt() =
        assertChoosing(":\"rel<caret>#{1}\"", "rel", RELEASE, Lookup.REPLACE_SELECT_CHAR, ":\"release-12.x<caret>#{1}\"")

    private fun assertReplacingAQuotedAtomFromItsColonWritesTheAtomQuotedAsItNeeds() =
        assertChoosing(":<caret>\"quoted\"", "", RELEASE, Lookup.REPLACE_SELECT_CHAR, ":\"release-12.x\"<caret>")

    private fun assertReplacingAQuotedAtomFromItsColonWritesABareAtomBare() =
        assertChoosing(":<caret>\"quoted\"", "", OK, Lookup.REPLACE_SELECT_CHAR, ":ok_target<caret>")

    private fun assertReplacingANameInAQuotedAtomsInterpolationReplacesTheName() {
        configure("defmodule Outer do\n  def g(foo_bar), do: :\"a#{fo<caret>o}b\"\nend\n")

        myFixture.chooseItem("fo", "foo_bar", Lookup.REPLACE_SELECT_CHAR)

        myFixture.checkResult("defmodule Outer do\n  def g(foo_bar), do: :\"a#{foo_bar<caret>}b\"\nend\n")
    }

    private fun assertChoosingAnAtomThatNeedsQuotesAfterTheColonWritesItQuoted() =
        assertChoosing(":rel<caret>", "rel", RELEASE, Lookup.NORMAL_SELECT_CHAR, ":\"release-12.x\"<caret>")

    private fun assertInAnEmptyQuotedAtomEveryModuleAtomIsOffered() {
        configure(atoms(":\"<caret>\""))

        assertSameElements(ours(), OK to "", QUOTED to "", RELEASE to "")
    }

    private fun assertChoosingInAnEmptyQuotedAtomWritesTheAtom() =
        assertChoosing(":\"<caret>\"", "", OK, Lookup.NORMAL_SELECT_CHAR, ":\"ok_target<caret>\"")

    private fun assertReplacingInAnEmptyQuotedAtomKeepsItsQuotes() =
        assertChoosing(":\"<caret>\"", "", OK, Lookup.REPLACE_SELECT_CHAR, ":\"ok_target<caret>\"")

    private fun assertAfterAColonInAQuotedAtomsInterpolationsCallEveryModuleAtomIsOffered() {
        configure(atoms(":\"a#{f(:<caret>)}\""))

        assertSameElements(ours(), OK to "", QUOTED to "", RELEASE to "")
    }

    /** Chooses [item], matched by [pattern], with [char] at [before]'s caret in [atoms], which then reads [after]. */
    private fun assertChoosing(before: String, pattern: String, item: String, char: Char, after: String) {
        configure(atoms(before))

        myFixture.chooseItem(pattern, item, char)

        myFixture.checkResult(atoms(after))
    }

    /** Each of the fixture's own module atoms the lookup shows, with the text it matched. */
    private fun ours(): List<Pair<String, String>> {
        val lookup = myFixture.shownLookup()

        return lookup.items.filter { it.lookupString in setOf(OK, QUOTED, RELEASE) }.map { it.lookupString to lookup.itemPattern(it) }
    }

    private fun configure(text: String) {
        myFixture.configureByText("atom.ex", text)
    }

    private companion object {
        const val OK = "ok_target"
        const val QUOTED = "quoted_target"
        const val RELEASE = "release-12.x"

        /** [atom] in a function, beside three modules named by atoms. */
        fun atoms(atom: String): String =
            """
            defmodule :ok_target do
            end

            defmodule :quoted_target do
            end

            defmodule :"release-12.x" do
            end

            defmodule Outer do
              def g, do: $atom
            end
            """.trimIndent()
    }
}
