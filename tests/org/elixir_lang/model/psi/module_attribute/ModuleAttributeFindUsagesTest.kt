package org.elixir_lang.model.psi.module_attribute

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationChosenAtCaret
import org.elixir_lang.code_insight.assertNoNavigationAtCaret
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
import org.elixir_lang.code_insight.nonDeclarationUsageCountAtCaret
import org.elixir_lang.code_insight.searchTargetCountAtCaret

class ModuleAttributeFindUsagesTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/module_attribute"

    fun testCtrlClickOnDeclarationChoosesShowUsages() {
        myFixture.configureByFiles("usages_module_attribute_declaration.ex")
        myFixture.assertShowUsagesChosenAtCaret()
        assertEquals(1, myFixture.searchTargetCountAtCaret())
    }

    fun testFindUsagesOnDeclarationFindsWritesAndReads() {
        // The other write (`@module_attribute 2`) plus BOTH reads: a re-declared attribute is one
        // logical attribute, so the read that resolves to the second declaration is a usage of the
        // first declaration's symbol too.
        assertEquals(3, nonDeclarationUsageCount("usages_module_attribute_declaration.ex"))
    }

    fun testCtrlClickOnUsageChoosesGotoDeclaration() {
        myFixture.configureByFiles("usages_module_attribute_usage.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    fun testFindUsagesFromUsageFindsDeclarationWrite() {
        assertEquals(1, nonDeclarationUsageCount("usages_module_attribute_usage.ex"))
    }

    fun testFindUsagesIgnoresSameNameInOtherModule() {
        assertEquals(1, nonDeclarationUsageCount("usages_module_attribute_cross_module.ex"))
    }

    /** An attribute set under an `if` is the module's, so a read after the `if` is a usage of it. */
    fun testFindUsagesOfAnAttributeUnderAnIfFindsAReadAfterIt() {
        myFixture.configureByText(
            "attribute_under_if.ex",
            """
            defmodule M do
              if true do
                @li<caret>mit 3
              end

              def f, do: @limit
            end
            """.trimIndent()
        )

        assertEquals(1, myFixture.nonDeclarationUsageCountAtCaret(project))
    }

    fun testCtrlClickOnDocDeclarationDoesNothing() {
        myFixture.configureByFiles("goto_declaration_nonreferencing_doc.ex")
        myFixture.assertNoNavigationAtCaret()
    }

    private fun nonDeclarationUsageCount(vararg files: String): Int {
        myFixture.configureByFiles(*files)
        return myFixture.nonDeclarationUsageCountAtCaret(project)
    }
}
