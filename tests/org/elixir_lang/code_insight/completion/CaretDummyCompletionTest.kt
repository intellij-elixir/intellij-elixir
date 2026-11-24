package org.elixir_lang.code_insight.completion

import com.intellij.codeInsight.CodeInsightSettings
import org.elixir_lang.code_insight.completionStringsAtCaret
import org.elixir_lang.declaration.ExpanderSettingTestCase

/**
 * What basic completion offers where the copy's dummy decides what the code around the caret parses as. Both
 * settings give the same answers: the dummy is chosen before either source is asked.
 */
class CaretDummyCompletionTest : ExpanderSettingTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "lib/unique_struct.ex",
            """
            defmodule UniqueStruct do
              defstruct [:a]
            end
            """.trimIndent()
        )
        myFixture.addFileToProject("lib/unique_other.ex", "defmodule UniqueOther do\nend\n")
    }

    fun testBeforeADefinitionsNameNoModuleNameIsOffered() = assertBeforeADefinitionsNameNoModuleNameIsOffered()

    fun testBeforeADefinitionsNameNoModuleNameIsOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertBeforeADefinitionsNameNoModuleNameIsOffered)

    fun testInAStructsBracesModuleNamesAreOffered() = assertInAStructsBracesModuleNamesAreOffered()

    fun testInAStructsBracesModuleNamesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInAStructsBracesModuleNamesAreOffered)

    fun testInAMapsBracesModuleNamesAreOffered() = assertInAMapsBracesModuleNamesAreOffered()

    fun testInAMapsBracesModuleNamesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInAMapsBracesModuleNamesAreOffered)

    fun testInAMapsBracesAfterAFormFeedModuleNamesAreOffered() = assertInAMapsBracesAfterAFormFeedModuleNamesAreOffered()

    fun testInAMapsBracesAfterAFormFeedModuleNamesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInAMapsBracesAfterAFormFeedModuleNamesAreOffered)

    fun testInAMapsBracesBeforeAFormFeedModuleNamesAreOffered() = assertInAMapsBracesBeforeAFormFeedModuleNamesAreOffered()

    fun testInAMapsBracesBeforeAFormFeedModuleNamesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInAMapsBracesBeforeAFormFeedModuleNamesAreOffered)

    fun testInAStructsBracesAfterAFormFeedModuleNamesAreOffered() = assertInAStructsBracesAfterAFormFeedModuleNamesAreOffered()

    fun testInAStructsBracesAfterAFormFeedModuleNamesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInAStructsBracesAfterAFormFeedModuleNamesAreOffered)

    fun testInAStructsBracesOnTheirOwnLineModuleNamesAreOffered() =
        assertInAStructsBracesOnTheirOwnLineModuleNamesAreOffered()

    fun testInAStructsBracesOnTheirOwnLineModuleNamesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInAStructsBracesOnTheirOwnLineModuleNamesAreOffered)

    fun testInAStructsBracesAfterAPairOnTheLineBeforeModuleNamesAreOffered() =
        assertInAStructsBracesAfterAPairOnTheLineBeforeModuleNamesAreOffered()

    fun testInAStructsBracesAfterAPairOnTheLineBeforeModuleNamesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInAStructsBracesAfterAPairOnTheLineBeforeModuleNamesAreOffered)

    fun testInAStructsBracesAfterAHeredocModuleNamesAreOffered() = assertInAStructsBracesAfterAHeredocModuleNamesAreOffered()

    fun testInAStructsBracesAfterAHeredocModuleNamesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInAStructsBracesAfterAHeredocModuleNamesAreOffered)

    fun testInAMapsBracesOnTheirOwnLineModuleNamesAreOffered() = assertInAMapsBracesOnTheirOwnLineModuleNamesAreOffered()

    fun testInAMapsBracesOnTheirOwnLineModuleNamesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInAMapsBracesOnTheirOwnLineModuleNamesAreOffered)

    fun testInAStructsBracesAfterACommentLineModuleNamesAreOffered() =
        assertInAStructsBracesAfterACommentLineModuleNamesAreOffered()

    fun testInAStructsBracesAfterACommentLineModuleNamesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInAStructsBracesAfterACommentLineModuleNamesAreOffered)

    fun testInAMapsBracesAfterACommentLineModuleNamesAreOffered() = assertInAMapsBracesAfterACommentLineModuleNamesAreOffered()

    fun testInAMapsBracesAfterACommentLineModuleNamesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInAMapsBracesAfterACommentLineModuleNamesAreOffered)

    fun testInAMapsBracesBeforeACommentLineModuleNamesAreOffered() = assertInAMapsBracesBeforeACommentLineModuleNamesAreOffered()

    fun testInAMapsBracesBeforeACommentLineModuleNamesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInAMapsBracesBeforeACommentLineModuleNamesAreOffered)

    fun testAtAMapsValueNamesAreOffered() = assertAtAMapsValueNamesAreOffered()

    fun testAtAMapsValueNamesAreOfferedWithTheExpanderOn() = withTheExpanderOn(::assertAtAMapsValueNamesAreOffered)

    fun testAfterAliasOnlyModuleNamesAreOffered() = assertAfterAliasOnlyModuleNamesAreOffered()

    fun testAfterAliasOnlyModuleNamesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertAfterAliasOnlyModuleNamesAreOffered)

    fun testInAnAliasCallAfterACommentLineOnlyModuleNamesAreOffered() =
        assertInAnAliasCallAfterACommentLineOnlyModuleNamesAreOffered()

    fun testInAnAliasCallAfterACommentLineOnlyModuleNamesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInAnAliasCallAfterACommentLineOnlyModuleNamesAreOffered)

    fun testAfterImportOnlyModuleNamesAreOffered() = assertAfterImportOnlyModuleNamesAreOffered()

    fun testAfterImportOnlyModuleNamesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertAfterImportOnlyModuleNamesAreOffered)

    fun testInsideAnAliasModuleNamesStartingWithItsTypedPartAreOffered() =
        assertInsideAnAliasModuleNamesStartingWithItsTypedPartAreOffered()

    fun testInsideAnAliasModuleNamesStartingWithItsTypedPartAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInsideAnAliasModuleNamesStartingWithItsTypedPartAreOffered)

    fun testInsideAnAttributesNameAttributesAreOffered() = assertInsideAnAttributesNameAttributesAreOffered()

    fun testInsideAnAttributesNameAttributesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInsideAnAttributesNameAttributesAreOffered)

    fun testInsideAVariablesNameAfterAnAccentVariablesAreOffered() =
        assertInsideAVariablesNameAfterAnAccentVariablesAreOffered()

    fun testInsideAVariablesNameAfterAnAccentVariablesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInsideAVariablesNameAfterAnAccentVariablesAreOffered)

    fun testInsideAKeywordsKeyNamesAreOffered() = assertInsideAKeywordsKeyNamesAreOffered()

    fun testInsideAKeywordsKeyNamesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInsideAKeywordsKeyNamesAreOffered)

    fun testInsideAKeywordsKeyBeforeACombiningMarkNamesAreOffered() = assertInsideAKeywordsKeyBeforeACombiningMarkNamesAreOffered()

    fun testInsideAKeywordsKeyBeforeACombiningMarkNamesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInsideAKeywordsKeyBeforeACombiningMarkNamesAreOffered)

    fun testBeforeAKeywordsColonNamesAreOffered() = assertBeforeAKeywordsColonNamesAreOffered()

    fun testBeforeAKeywordsColonNamesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertBeforeAKeywordsColonNamesAreOffered)

    fun testInsideANameBeforeAColonAndAFormFeedNamesAreOffered() = assertInsideANameBeforeAColonAndAFormFeedNamesAreOffered()

    fun testInsideANameBeforeAColonAndAFormFeedNamesAreOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInsideANameBeforeAColonAndAFormFeedNamesAreOffered)

    fun testBeforeAListNamesAreOffered() = assertBeforeAListNamesAreOffered()

    fun testBeforeAListNamesAreOfferedWithTheExpanderOn() = withTheExpanderOn(::assertBeforeAListNamesAreOffered)

    fun testAfterALocalNameEndingInAnExclamationMarkThatNameIsOffered() =
        assertAfterALocalNameEndingInAnExclamationMarkThatNameIsOffered()

    fun testAfterALocalNameEndingInAnExclamationMarkThatNameIsOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertAfterALocalNameEndingInAnExclamationMarkThatNameIsOffered)

    fun testAfterAnAliasExclamationMarkNothingIsOffered() = assertAfterAnAliasExclamationMarkNothingIsOffered()

    fun testAfterAnAliasExclamationMarkNothingIsOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertAfterAnAliasExclamationMarkNothingIsOffered)

    fun testAfterAStructsExclamationMarkNothingIsOffered() = assertAfterAStructsExclamationMarkNothingIsOffered()

    fun testAfterAStructsExclamationMarkNothingIsOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertAfterAStructsExclamationMarkNothingIsOffered)

    fun testAfterAnAliasQuestionMarkNothingIsOffered() = assertAfterAnAliasQuestionMarkNothingIsOffered()

    fun testAfterAnAliasQuestionMarkNothingIsOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertAfterAnAliasQuestionMarkNothingIsOffered)

    fun testAfterAnAliasExclamationMarksDotNothingIsOffered() = assertAfterAnAliasExclamationMarksDotNothingIsOffered()

    fun testAfterAnAliasExclamationMarksDotNothingIsOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertAfterAnAliasExclamationMarksDotNothingIsOffered)

    fun testInsideACaptureOfAnOperatorNothingIsOffered() = assertInsideACaptureOfAnOperatorNothingIsOffered()

    fun testInsideACaptureOfAnOperatorNothingIsOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInsideACaptureOfAnOperatorNothingIsOffered)

    fun testInsideARemoteOperatorNothingIsOffered() = assertInsideARemoteOperatorNothingIsOffered()

    fun testInsideARemoteOperatorNothingIsOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertInsideARemoteOperatorNothingIsOffered)

    fun testAfterANumbersExclamationMarkNothingIsOffered() = assertAfterANumbersExclamationMarkNothingIsOffered()

    fun testAfterANumbersExclamationMarkNothingIsOfferedWithTheExpanderOn() =
        withTheExpanderOn(::assertAfterANumbersExclamationMarkNothingIsOffered)

    private fun assertBeforeADefinitionsNameNoModuleNameIsOffered() {
        configure("  def <caret>f(x), do: x")

        assertDoesntContain(offered(), "UniqueStruct")
    }

    private fun assertInAStructsBracesModuleNamesAreOffered() =
        assertOffered("  def f, do: %UniqueStruct{<caret>}", "UniqueStruct")

    private fun assertInAMapsBracesModuleNamesAreOffered() =
        assertOffered("  def f, do: %{<caret>}", "UniqueStruct")

    private fun assertInAStructsBracesOnTheirOwnLineModuleNamesAreOffered() =
        assertOffered("  def f do\n    %UniqueStruct{\n      <caret>\n    }\n  end", "UniqueStruct")

    private fun assertInAStructsBracesAfterAPairOnTheLineBeforeModuleNamesAreOffered() =
        assertOffered("  def f do\n    %UniqueStruct{a: 1,\n      <caret>}\n  end", "UniqueStruct")

    private fun assertInAStructsBracesAfterAHeredocModuleNamesAreOffered() =
        assertOffered("  def f do\n    %UniqueStruct{\n      doc: \"\"\"\n      text\n      \"\"\",\n      <caret>\n    }\n  end", "UniqueStruct")

    private fun assertInAMapsBracesOnTheirOwnLineModuleNamesAreOffered() =
        assertOffered("  def f do\n    %{\n      <caret>\n    }\n  end", "UniqueStruct")

    /** The lexer reads a form feed as white space, so the line still ends in the braces' `{`. */
    private fun assertInAStructsBracesAfterAFormFeedModuleNamesAreOffered() =
        assertOffered("  def f do\n    %UniqueStruct{\u000C # note\n      <caret>\n    }\n  end", "UniqueStruct")

    /** The lexer reads a form feed as white space, so the caret is still all the braces hold. */
    private fun assertInAMapsBracesAfterAFormFeedModuleNamesAreOffered() =
        assertOffered("  def f do\n    %{\u000C\n      <caret>\n    }\n  end", "UniqueStruct")

    private fun assertInAMapsBracesBeforeAFormFeedModuleNamesAreOffered() =
        assertOffered("  def f do\n    %{\n      <caret>\u000C\n    }\n  end", "UniqueStruct")

    private fun assertInAStructsBracesAfterACommentLineModuleNamesAreOffered() =
        assertOffered("  def f do\n    %UniqueStruct{\n      # note\n      <caret>\n    }\n  end", "UniqueStruct")

    private fun assertInAMapsBracesAfterACommentLineModuleNamesAreOffered() =
        assertOffered("  def f do\n    %{\n      # note\n      <caret>\n    }\n  end", "UniqueStruct")

    private fun assertInAMapsBracesBeforeACommentLineModuleNamesAreOffered() =
        assertOffered("  def f do\n    %{\n      <caret>\n      # note\n    }\n  end", "UniqueStruct")

    private fun assertAtAMapsValueNamesAreOffered() =
        assertOffered("  def abc1(x), do: x\n  def f, do: %{a: <caret>1}", "abc1")

    private fun assertAfterAliasOnlyModuleNamesAreOffered() =
        assertOnlyModuleNamesOffered("  alias <caret>UniqueStruct\n  def abc1(x), do: x")

    private fun assertInAnAliasCallAfterACommentLineOnlyModuleNamesAreOffered() =
        assertOnlyModuleNamesOffered("  alias(\n    # note\n    <caret>UniqueStruct\n  )\n  def abc1(x), do: x")

    private fun assertAfterImportOnlyModuleNamesAreOffered() =
        assertOnlyModuleNamesOffered("  import <caret>UniqueStruct\n  def abc1(x), do: x")

    private fun assertOnlyModuleNamesOffered(body: String) {
        configure(body)

        val offered = offered()
        assertContainsElements(offered, "UniqueStruct")
        assertDoesntContain(offered, "abc1", "is_atom")
    }

    private fun assertOffered(body: String, vararg names: String) {
        configure(body)

        assertContainsElements(offered(), *names)
    }

    private fun assertInsideAnAliasModuleNamesStartingWithItsTypedPartAreOffered() =
        assertOffered("  def f, do: [Uniq<caret>ueStruct]", "UniqueStruct", "UniqueOther")

    private fun assertInsideAnAttributesNameAttributesAreOffered() =
        assertOffered("  @attr1 1\n  @attr2 2\n  def f, do: [@at<caret>tr1]", "attr1", "attr2")

    private fun assertInsideAVariablesNameAfterAnAccentVariablesAreOffered() =
        assertOffered("  def f(café_noir, café_au_lait), do: caf<caret>é_noir", "café_noir", "café_au_lait")

    private fun assertInsideAKeywordsKeyNamesAreOffered() =
        assertOffered("  def abc1(x), do: x\n  def abc2(x), do: x\n  def f, do: foo(ab<caret>c: 1)", "abc1", "abc2")

    // The key is `café` written decomposed, the caret before its combining acute accent.
    private fun assertInsideAKeywordsKeyBeforeACombiningMarkNamesAreOffered() =
        assertOffered("  def cafe1(x), do: x\n  def cafe2(x), do: x\n  def f, do: foo(cafe<caret>\u0301: 1)", "cafe1", "cafe2")

    private fun assertBeforeAKeywordsColonNamesAreOffered() =
        assertOffered("  def abc1(x), do: x\n  def abc2(x), do: x\n  def f, do: foo(ab<caret>: 1)", "abc1", "abc2")

    // A colon before a form feed is no keyword key's: Elixir wants a space after one.
    private fun assertInsideANameBeforeAColonAndAFormFeedNamesAreOffered() =
        assertOffered("  def abc1(x), do: x\n  def abc2(x), do: x\n  def f, do: foo(ab<caret>c:\u000c1)", "abc1", "abc2")

    private fun assertBeforeAListNamesAreOffered() =
        assertOffered("  def abc1(x), do: x\n  def f, do: x = <caret>[1]", "abc1")

    private fun assertAfterALocalNameEndingInAnExclamationMarkThatNameIsOffered() {
        configure("  def run!(x), do: x\n  def runner(x), do: x\n  def f(x), do: run!<caret>(x)")

        val offered = shown()
        assertContainsElements(offered, "run!")
        assertDoesntContain(offered, "runner")
    }

    // No module's name ends in `!` or `?`, and nothing typed after one joins it.
    private fun assertAfterAnAliasExclamationMarkNothingIsOffered() {
        configure("  def f, do: x = UniqueStruct!<caret>")

        assertEmpty(shown())
    }

    private fun assertAfterAStructsExclamationMarkNothingIsOffered() {
        configure("  def f, do: %UniqueStruct!<caret>")

        assertEmpty(shown())
    }

    private fun assertAfterAnAliasQuestionMarkNothingIsOffered() {
        configure("  def f, do: x = UniqueOther?<caret>")

        assertEmpty(shown())
    }

    private fun assertAfterAnAliasExclamationMarksDotNothingIsOffered() {
        configure("  def f, do: UniqueStruct!.<caret>")

        assertEmpty(shown())
    }

    private fun assertInsideACaptureOfAnOperatorNothingIsOffered() {
        configure("  def f, do: &+<caret>/2")

        assertEmpty(shown())
    }

    private fun assertInsideARemoteOperatorNothingIsOffered() {
        configure("  def f, do: Kernel.~<caret>~~(1)")

        assertEmpty(shown())
    }

    // Elixir reads `1!` as one name, which is no name.
    private fun assertAfterANumbersExclamationMarkNothingIsOffered() {
        configure("  def abc1(x), do: x\n  def f(n), do: 1!<caret>= n")

        assertEmpty(shown())
    }

    /** What the lookup shows, a lone item shown rather than inserted. */
    private fun shown(): List<String> =
        CodeInsightSettings.runWithTemporarySettings<List<String>, Throwable> { settings ->
            settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
            offered()
        }

    /** What completion offers at the caret. */
    private fun offered(): List<String> = myFixture.completionStringsAtCaret().orEmpty()

    private fun configure(body: String) {
        myFixture.configureByText("caret.ex", "defmodule Caret do\n$body\nend\n")
    }
}
