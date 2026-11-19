package org.elixir_lang.model.psi.module_attribute

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.CompletionAttempt
import org.elixir_lang.code_insight.assertGotoDeclarationLandsIn
import org.elixir_lang.code_insight.assertNoNavigationAtCaret
import org.elixir_lang.code_insight.completeSoleCandidateAtCaret
import org.elixir_lang.code_insight.completionAttemptAtCaret
import org.elixir_lang.code_insight.completionStringsAtCaret
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.psi.Module

/** Inside `defimpl P, for: X`, Elixir defines `@for` as `X` and `@protocol` as `P`. */
class ImplementationAttributeTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/module_attribute"

    fun testGotoDeclarationOnForWithExplicitForLandsOnForModule() {
        assertGotoDeclarationOnForLandsOnModule("goto_declaration_implicit_for.ex", "ImplicitForTarget")
    }

    fun testGotoDeclarationOnForWithoutForLandsOnEnclosingModule() {
        assertGotoDeclarationOnForLandsOnModule("goto_declaration_implicit_for_omitted.ex", "ImplicitForOuter")
    }

    fun testGotoDeclarationOnForWithForModuleLandsOnEnclosingModule() {
        assertGotoDeclarationOnForLandsOnModule("goto_declaration_implicit_for_module.ex", "ImplicitForOuter")
    }

    fun testGotoDeclarationOnForWithoutForLandsOnNestedEnclosingModule() {
        assertGotoDeclarationOnForLandsOnModule("goto_declaration_implicit_for_nested.ex", "Inner")
    }

    fun testGotoDeclarationOnForInNestedArgumentLandsOnForModule() {
        assertGotoDeclarationOnForLandsOnModule("goto_declaration_implicit_for_nested_argument.ex", "ImplicitForTarget")
    }

    fun testGotoDeclarationOnForAsOperandLandsOnForModule() {
        assertGotoDeclarationOnForLandsOnModule("goto_declaration_implicit_for_operand.ex", "ImplicitForTarget")
    }

    fun testGotoDeclarationOnForAsAStructNameLandsOnForModule() {
        myFixture.configureByText(
            "implicit_for_struct_name.ex",
            """
            defprotocol ImplicitForProtocol do
              def make(term)
            end

            defmodule ImplicitForTarget do
              defstruct []
            end

            defimpl ImplicitForProtocol, for: ImplicitForTarget do
              def make(_), do: %@f<caret>or{}
            end
            """.trimIndent()
        )
        myFixture.assertGotoDeclarationLandsIn("ImplicitForTarget", "a defmodule declaration") { Module.`is`(it) }
    }

    fun testGotoDeclarationOnForInQuoteOptionsLandsOnForModule() {
        assertGotoDeclarationOnForLandsOnModule("goto_declaration_implicit_for_bind_quoted.ex", "ImplicitForTarget")
    }

    fun testGotoDeclarationOnForWithForListLandsOnEachModule() {
        myFixture.configureByFiles("goto_declaration_implicit_for_list.ex")

        assertEquals(
            listOf("ImplicitForFirst", "ImplicitForSecond"),
            myFixture.gotoDeclarationTargetsAtCaret()?.map { it.destination?.text }?.sortedBy { it }
        )
    }

    fun testGotoDeclarationOnForInKeywordDoImplementationLandsOnForModule() {
        assertGotoDeclarationOnForLandsOnModule("goto_declaration_implicit_for_keyword_do.ex", "ImplicitForTarget")
    }

    fun testGotoDeclarationOnForInKeywordDoModuleNestedInImplementationDoesNothing() {
        myFixture.configureByFiles("goto_declaration_implicit_for_inner_module_keyword_do.ex")
        myFixture.assertNoNavigationAtCaret()
    }

    fun testGotoDeclarationOnForInModuleNestedInImplementationDoesNothing() {
        myFixture.configureByFiles("goto_declaration_implicit_for_inner_module.ex")
        myFixture.assertNoNavigationAtCaret()
    }

    fun testGotoDeclarationOnForInQuoteInImplementationDoesNothing() {
        myFixture.configureByFiles("goto_declaration_implicit_for_quote.ex")
        myFixture.assertNoNavigationAtCaret()
    }

    fun testGotoDeclarationOnForOutsideImplementationDoesNothing() {
        myFixture.configureByFiles("goto_declaration_implicit_for_outside_defimpl.ex")
        myFixture.assertNoNavigationAtCaret()
    }

    fun testCompletionInImplementationOffersFor() {
        myFixture.configureByText(
            "test.ex",
            """
                defimpl Inspect, for: Tuple do
                  @format "{}"

                  def inspect(_, _), do: @f<caret>
                end
            """.trimIndent()
        )

        assertEquals(listOf("for", "format"), myFixture.completionStringsAtCaret()?.sorted())
    }

    fun testCompletionInNestedArgumentInImplementationOffersFor() {
        myFixture.configureByText(
            "test.ex",
            """
                defimpl Inspect, for: Tuple do
                  @format "{}"

                  def inspect(_, _), do: IO.puts(to_string(@f<caret>))
                end
            """.trimIndent()
        )

        assertEquals(listOf("for", "format"), myFixture.completionStringsAtCaret()?.sorted())
    }

    fun testCompletionInImplementationOffersProtocol() {
        myFixture.configureByText(
            "test.ex",
            """
                defimpl Inspect, for: Tuple do
                  def inspect(_, _), do: @pro<caret>
                end
            """.trimIndent()
        )

        assertEquals(
            CompletionAttempt(
                null,
                """
                    defimpl Inspect, for: Tuple do
                      def inspect(_, _), do: @protocol
                    end
                """.trimIndent()
            ),
            myFixture.completionAttemptAtCaret()
        )
    }

    fun testCompletionInModuleNestedInImplementationDoesNotOfferFor() {
        myFixture.configureByText(
            "test.ex",
            """
                defimpl Inspect, for: Tuple do
                  defmodule Inner do
                    @format "{}"

                    def format, do: @f<caret>
                  end
                end
            """.trimIndent()
        )

        myFixture.completeSoleCandidateAtCaret()

        myFixture.checkResult(
            """
                defimpl Inspect, for: Tuple do
                  defmodule Inner do
                    @format "{}"

                    def format, do: @format
                  end
                end
            """.trimIndent()
        )
    }

    fun testCompletionOutsideImplementationDoesNotOfferFor() {
        myFixture.configureByText(
            "test.ex",
            """
                defmodule Tuple.Formatter do
                  @format "{}"

                  def format, do: @f<caret>
                end
            """.trimIndent()
        )

        myFixture.completeSoleCandidateAtCaret()

        myFixture.checkResult(
            """
                defmodule Tuple.Formatter do
                  @format "{}"

                  def format, do: @format
                end
            """.trimIndent()
        )
    }

    private fun assertGotoDeclarationOnForLandsOnModule(path: String, moduleNameText: String) {
        myFixture.configureByFiles(path)
        myFixture.assertGotoDeclarationLandsIn(moduleNameText, "a defmodule declaration") { Module.`is`(it) }
    }
}
