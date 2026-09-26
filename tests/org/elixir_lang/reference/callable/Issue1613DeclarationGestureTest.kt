package org.elixir_lang.reference.callable

import com.intellij.ide.impl.HeadlessDataManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.GtduNavigation
import org.elixir_lang.code_insight.gtduNavigationAtCaret
import org.elixir_lang.documentation.quickDocumentationAtCaret

/**
 * Ctrl+Click on the name in a `def` head offers Show Usages, because the name is a declaration; so does the name in a
 * `defdelegate` head (#4043), whose quick documentation is its own `@doc`, as a `def`'s is.
 */
class Issue1613DeclarationGestureTest : PlatformTestCase() {
    private val delegation = """
        defmodule Delegator do
          @doc "Merges twice."
          defdelegate own_me<caret>rge(m1, m2), to: Target, as: :merge
        end
    """.trimIndent()

    fun testCtrlClickOnDefdelegateNameShowsUsages() {
        myFixture.configureByText("delegation_gesture.ex", delegation)

        val navigation = myFixture.gtduNavigationAtCaret()

        assertTrue(
            "Ctrl+Click on a defdelegate's own name should offer Show Usages, got: $navigation",
            navigation is GtduNavigation.ShowUsages
        )
    }

    fun testQuickDocumentationOnDefdelegateNameShowsItsDoc() {
        myFixture.configureByText("delegation_doc.ex", delegation)

        val documentation = myFixture.quickDocumentationAtCaret(project)

        assertTrue("Expected the delegation's @doc, got: $documentation", documentation?.contains("Merges twice.") == true)
    }

    override fun getTestDataPath(): String =
        "testData/org/elixir_lang/reference/callable/issue_1613_declaration_gesture"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testCtrlClickOnDefNameShowsUsages() {
        myFixture.configureByFile("def_control.ex")

        val navigation = myFixture.gtduNavigationAtCaret()

        assertTrue(
            "Ctrl+Click on a def's own name should offer Show Usages, got: $navigation",
            navigation is GtduNavigation.ShowUsages
        )
    }
}
