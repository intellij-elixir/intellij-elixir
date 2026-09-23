package org.elixir_lang.model.psi.type

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.model.psi.PsiSymbolReferenceService
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationChosenAtCaret
import org.elixir_lang.code_insight.assertGotoDeclarationLandsIn
import org.elixir_lang.code_insight.enclosingCallAtCaret
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret
import org.elixir_lang.code_insight.gotoDeclarationLineAtCaret
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

    /** A type named in a `@spec` is the `@type`, wherever the spec and the type stand in the module. */
    fun testATypeUsedInASpecGoesToItsTypeWhicheverComesFirst() {
        val shapes = listOf(
            "argument, spec first" to "@spec check(conf<caret>ig) :: :ok\n  def check(_), do: :ok\n  @type config :: map()",
            "return, spec first" to "@spec check(term) :: conf<caret>ig\n  def check(_), do: :ok\n  @type config :: map()",
            "argument with parentheses, spec first" to
                "@spec check(conf<caret>ig()) :: :ok\n  def check(_), do: :ok\n  @type config :: map()",
            "return with parentheses, spec first" to
                "@spec check(term) :: conf<caret>ig()\n  def check(_), do: :ok\n  @type config :: map()",
            "argument, type first" to "@type config :: map()\n  @spec check(conf<caret>ig) :: :ok\n  def check(_), do: :ok",
            "return, type first" to "@type config :: map()\n  @spec check(term) :: conf<caret>ig\n  def check(_), do: :ok",
            // A `use`d module's `quote` names `config` in a `@spec` of its own, which is no declaration of it.
            "return, after a use whose quote names it" to
                "use Uses\n  @spec other(term) :: conf<caret>ig\n  def other(_), do: :ok\n  @type config :: map()"
        )
        val uses = "defmodule Uses do\n  defmacro __using__(_) do\n    quote do\n      @spec check(config) :: :ok\n" +
            "      def check(_), do: :ok\n    end\n  end\nend\n\n"

        val misses = shapes.mapNotNull { (label, body) ->
            myFixture.configureByText("spec_type.ex", "${uses}defmodule M do\n  $body\nend\n")
            val line = try {
                myFixture.gotoDeclarationLineAtCaret()
            } catch (nowhere: AssertionError) {
                "nowhere"
            }

            if (line == "@type config :: map()") null else "$label went to $line"
        }

        assertEquals("a type usage that did not go to its `@type`", emptyList<String>(), misses)
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
