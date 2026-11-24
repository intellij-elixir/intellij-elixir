package org.elixir_lang.model.psi.module_attribute

import com.intellij.model.psi.PsiSymbolReferenceService
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.CompletionAttempt
import org.elixir_lang.code_insight.assertGotoDeclarationLandsIn
import org.elixir_lang.code_insight.assertNoNavigationAtCaret
import org.elixir_lang.code_insight.completeSoleCandidateAtCaret
import org.elixir_lang.code_insight.completionAttemptAtCaret
import org.elixir_lang.code_insight.completionStringsAtCaret
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.model.psi.module.ModuleSymbol
import org.elixir_lang.psi.Module
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.underAnAliasOfNsFoo

/** Inside `defimpl P, for: X`, Elixir defines `@for` as `X` and `@protocol` as `P`. */
class ImplementationAttributeTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/module_attribute"

    fun testGotoDeclarationOnForWithExplicitForLandsOnForModule() {
        assertGotoDeclarationOnForLandsOnModule("goto_declaration_implicit_for.ex", "ImplicitForTarget")
    }

    fun testGotoDeclarationOnForWithAnAtomForLandsOnTheAtomNamedModule() {
        myFixture.configureByText(
            "for_atom.ex",
            "defprotocol P do\n  def describe(term)\nend\n\ndefmodule :foo do\nend\n\n" +
                "defimpl P, for: :foo do\n  def describe(_), do: @f<caret>or\nend\n"
        )
        val destination = myFixture.gotoDeclarationDestinationAtCaret()

        assertEquals(myFixture.file.text.indexOf("defmodule :foo") + "defmodule ".length, destination?.textOffset)
    }

    /** `for: Elixir.Foo` names the top-level `Foo`, whatever `alias NS.Foo` is in scope: `@for` lands on `defmodule Foo`. */
    fun testGotoDeclarationOnForWithAnElixirRootedForAfterAnAliasLandsOnTheModuleItSays() {
        myFixture.configureByText(
            "for_rooted.ex",
            implementationOfPForUnderAnAliasOfNsFoo("Elixir.Foo")
        )

        val declaration = myFixture.assertGotoDeclarationLandsIn("Foo", "a defmodule declaration") { Module.`is`(it) }

        assertEquals("Foo", ModuleSymbol.moduleNameText(declaration))
    }

    /** `for: :"Elixir.Foo"` names the top-level `Foo` too, whatever `alias NS.Foo` is in scope. */
    fun testGotoDeclarationOnForWithAnElixirRootedAtomAfterAnAliasLandsOnTheModuleItSays() {
        myFixture.configureByText(
            "for_rooted_atom.ex",
            implementationOfPForUnderAnAliasOfNsFoo(":\"Elixir.Foo\"")
        )

        val declaration = myFixture.assertGotoDeclarationLandsIn("Foo", "a defmodule declaration") { Module.`is`(it) }

        assertEquals("Foo", ModuleSymbol.moduleNameText(declaration))
    }

    /** In `for: [Elixir.Foo, Foo]` only the first is rooted: `@for` reaches `Foo` and, through the alias, `NS.Foo`. */
    fun testGotoDeclarationOnForWithARootedAndAnUnrootedSpellingReachesEachModule() {
        myFixture.configureByText(
            "for_rooted_and_unrooted.ex",
            implementationOfPForUnderAnAliasOfNsFoo("[Elixir.Foo, Foo]")
        )

        val names = generateSequence(myFixture.file.findElementAt(myFixture.caretOffset)) { it.parent }
            .map { PsiSymbolReferenceService.getService().getReferences(it) }
            .first { it.isNotEmpty() }
            .flatMap { it.resolveReference() }
            .filterIsInstance<ModuleSymbol>()
            .map { it.moduleName }

        assertEquals(listOf("Foo", "NS.Foo"), names.sorted())
    }

    /** `defimpl Elixir.P` names the top-level `P`, whatever `alias NS.P` is in scope: `@protocol` lands on `defprotocol P`. */
    fun testGotoDeclarationOnProtocolWithAnElixirRootedProtocolAfterAnAliasLandsOnTheProtocolItSays() {
        myFixture.configureByText(
            "protocol_rooted.ex",
            "defprotocol P do\n  def f(x)\nend\n\ndefmodule NS.P do\nend\n\ndefmodule T do\nend\n\n" +
                "defmodule User do\n  alias NS.P\n  defimpl Elixir.P, for: T do\n    def f(x), do: @pro<caret>tocol\n  end\nend"
        )

        val declaration = myFixture.assertGotoDeclarationLandsIn("P", "a defprotocol declaration") { Protocol.`is`(it) }

        assertEquals("P", ModuleSymbol.moduleNameText(declaration))
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

    fun testGotoDeclarationOnForUnquotedInQuoteInImplementationLandsOnForModule() {
        assertGotoDeclarationOnForLandsOnModule("goto_declaration_implicit_for_unquote.ex", "ImplicitForTarget")
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

/** `defimpl P, for: [forValue]` under `alias NS.Foo`, the caret on its `@for`. */
private fun implementationOfPForUnderAnAliasOfNsFoo(forValue: String): String =
    underAnAliasOfNsFoo("\ndefprotocol P do\n  def f(x)\nend\n\ndefimpl P, for: $forValue do\n  def f(x), do: @f<caret>or\nend")
