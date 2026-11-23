package org.elixir_lang.model.psi.type

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.model.psi.PsiSymbolReferenceService
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationChosenAtCaret
import org.elixir_lang.code_insight.assertGotoDeclarationLandsIn
import org.elixir_lang.code_insight.enclosingCallAtCaret
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.Type as TypeElement

@Suppress("UnstableApiUsage")
class TypeGotoDeclarationTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/type"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testCtrlClickOnTypeInSpecChoosesGotoDeclaration() {
        myFixture.configureByFiles("goto_declaration.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    fun testGoToDeclarationNavigatesToTypeName() {
        myFixture.configureByFiles("goto_declaration.ex")
        myFixture.assertGotoDeclarationLandsIn(
            "user_id",
            "a @type/@typep/@opaque declaration"
        ) { TypeElement.`is`(it) }
    }

    /** `@type unquote(:t)()` defines `t/0`. */
    fun testGoToDeclarationAtAUseOfAnUnquotedTypeNameLandsOnTheType() {
        myFixture.configureByText(
            "unquoted.ex",
            "defmodule M do\n  @type unquote(:t)() :: integer\n  @spec f() :: <caret>t()\n  def f, do: 1\nend\n"
        )

        val type = generateSequence(myFixture.gotoDeclarationDestinationAtCaret()) { it.parent }
            .filterIsInstance<Call>()
            .firstOrNull { TypeElement.`is`(it) }

        assertEquals("@type unquote(:t)() :: integer", type?.text)
    }

    /** A variable in what an `unquote` unquotes is the macro's, not the type variable of the same name. */
    fun testGoToDeclarationAtAVariableAnUnquoteInATypeUnquotesLandsOnTheMacrosParameter() =
        assertUnquotedDestination("t(x) :: {x, unquote(helper(<caret>x))}", "x")

    fun testGoToDeclarationAtATypeBesideAnUnquoteInATypeLandsOnTheType() =
        assertUnquotedDestination("t :: {unquote(helper(x)), <caret>u()}", "@type u :: atom")

    /**
     * Go to Declaration at the caret in [type], written after `@type` in a macro's `quote`, lands in the first
     * [expected] of the file.
     */
    private fun assertUnquotedDestination(type: String, expected: String) {
        myFixture.configureByText(
            "unquoted.ex",
            """
            defmodule M do
              @type u :: atom

              def helper(a), do: a

              defmacro typed(x) do
                quote do
                  @type $type
                end
              end
            end
            """.trimIndent()
        )

        val destination = myFixture.gotoDeclarationDestinationAtCaret()
        val start = myFixture.file.text.indexOf(expected)

        assertEquals(
            "lands at ${destination?.textOffset}, on `${destination?.text}`",
            true,
            destination != null && destination.textOffset in start until start + expected.length
        )
    }

    fun testSpecTypeReferenceResolvesToTypeSymbol() {
        myFixture.configureByFiles("goto_declaration.ex")
        val callElement = myFixture.enclosingCallAtCaret()
        assertNotNull("Call element at caret should exist", callElement)

        val references = PsiSymbolReferenceService.getService().getReferences(callElement!!)
        assertTrue("Type usage site should have at least one symbol reference", references.isNotEmpty())

        val resolved = references.flatMap { it.resolveReference() }
        assertTrue("Reference should resolve to at least one TypeSymbol", resolved.any { it is TypeSymbol })
    }

    fun testCtrlClickOnRemoteSourceTypeChoosesGotoDeclaration() {
        myFixture.configureByFiles("remote_source_type.ex", "remote_source_other.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    fun testGoToDeclarationNavigatesToRemoteSourceType() {
        myFixture.configureByFiles("remote_source_type.ex", "remote_source_other.ex")
        val target = myFixture.gotoDeclarationDestinationAtCaret()
        assertNotNull("Go To Declaration should navigate to RemoteSourceOther's @type", target)
        assertEquals("existing", target!!.text)
        assertEquals("remote_source_other.ex", target.containingFile.name)
        val enclosing = generateSequence(target) { it.parent }
            .filterIsInstance<org.elixir_lang.psi.call.Call>()
            .firstOrNull { TypeElement.`is`(it) }
        assertNotNull("Should land inside a @type/@typep/@opaque declaration", enclosing)
    }

    fun testCtrlClickOnTypeVariableUsageChoosesGotoDeclaration() {
        myFixture.configureByFiles("type_variable_usage.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    fun testGoToDeclarationNavigatesToTypeVariableDeclaration() {
        myFixture.configureByFiles("type_variable_usage.ex")
        myFixture.assertGotoDeclarationLandsIn(
            "a",
            "the type variable's head declaration"
        ) { it.text == "box(a)" }
    }

    fun testCtrlClickOnSpecTypeVariableUsageChoosesGotoDeclaration() {
        myFixture.configureByFiles("type_variable_spec_when.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    fun testSpecTypeVariableUsageResolvesToWhenBinding() {
        myFixture.configureByFiles("type_variable_spec_when.ex")
        val target = myFixture.gotoDeclarationDestinationAtCaret()
        assertNotNull("Go To Declaration should navigate to the `when` binding", target)
        assertEquals("a", target!!.text)

        val whenOffset = myFixture.file.text.indexOf("when")
        assertTrue(
            "Should land on the `when` binding, not the parameter usage",
            target.textRange.startOffset > whenOffset
        )
    }
}
