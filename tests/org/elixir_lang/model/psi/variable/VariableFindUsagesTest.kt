package org.elixir_lang.model.psi.variable

import com.intellij.ide.impl.HeadlessDataManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationChosenAtCaret
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret
import org.elixir_lang.code_insight.nonDeclarationUsageCountAtCaret
import org.elixir_lang.code_insight.searchTargetCountAtCaret

class VariableFindUsagesTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/variable"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testCtrlClickOnParameterDeclarationChoosesShowUsages() {
        myFixture.configureByFiles("usages_parameter_declaration.ex")
        myFixture.assertShowUsagesChosenAtCaret()
        assertEquals(1, myFixture.searchTargetCountAtCaret())
    }

    fun testFindUsagesOnParameterDeclarationFindsBodyRead() {
        assertEquals(1, nonDeclarationUsageCount("usages_parameter_declaration.ex"))
    }

    fun testCtrlClickOnVariableDeclarationChoosesShowUsages() {
        myFixture.configureByFiles("usages_variable_declaration.ex")
        myFixture.assertShowUsagesChosenAtCaret()
        assertEquals(1, myFixture.searchTargetCountAtCaret())
    }

    fun testFindUsagesOnVariableDeclarationFindsBodyRead() {
        assertEquals(1, nonDeclarationUsageCount("usages_variable_declaration.ex"))
    }

    fun testFindUsagesOnVariableDeclaredInInterpolationFindsLaterRead() {
        // `_ = "#{variable = 1}"` binds `variable` for the code after the string, as a match anywhere else does.
        assertEquals(1, nonDeclarationUsageCount("usages_variable_declared_in_interpolation.ex"))
    }

    fun testFindUsagesOnVariableDeclarationFindsReadInsideInterpolationArgument() {
        // `IO.puts("#{variable}")` is a read like `IO.puts(variable)`; neither is "a variable" to the syntactic walk,
        // and both resolve to the declaration.
        assertEquals(1, nonDeclarationUsageCount("usages_variable_read_in_interpolation_argument.ex"))
    }

    fun testFindUsagesOnComprehensionGeneratorFindsReadInGeneratedTestName() {
        // `test "handles #{variable}"` reads the generator; the string is a macro argument, not a pattern.
        assertEquals(2, nonDeclarationUsageCount("usages_comprehension_generator_read_in_test_name.ex"))
    }

    fun testFindUsagesOnVariableDeclaredInNestedMatchFindsLaterRead() {
        // `_ = (variable = 1)` declares `variable` on the left of the inner match, although the inner match sits on
        // the right of the outer one.
        assertEquals(1, nonDeclarationUsageCount("usages_variable_declared_in_nested_match.ex"))
    }

    fun testFindUsagesOnVariableDeclarationIgnoresSameNameInOtherModule() {
        assertEquals(1, nonDeclarationUsageCount("usages_variable_same_name_other_module.ex"))
    }

    fun testFindUsagesOnVariableDeclarationIncludesRebindingAndPinReads() {
        assertEquals(5, nonDeclarationUsageCount("usages_variable_rebinding_and_pin.ex"))
    }

    fun testFindUsagesOnRebindingDeclarationCoversWholeChain() {
        // A rebinding chain (`value = parameter; ^value = value; value = value + 1; value`) is one
        // user-facing variable, so Find Usages from ANY of its bindings covers the whole chain:
        // the first binding (write), the pin read, both same-line rhs reads, and the final read.
        // (Previously the same-line rhs read and everything before the caret's binding were
        // excluded - renaming from a later binding then corrupted the chain.)
        assertEquals(5, nonDeclarationUsageCount("usages_variable_rebinding_declaration_same_line_rhs.ex"))
    }

    fun testFindUsagesOnParameterDeclarationIncludesPinRead() {
        assertEquals(2, nonDeclarationUsageCount("usages_parameter_pin_read.ex"))
    }

    fun testFindUsagesOnParameterDeclarationIgnoresSiblingClauseSameName() {
        assertEquals(1, nonDeclarationUsageCount("usages_parameter_sibling_clause_same_name.ex"))
    }

    fun testFindUsagesOnVariableDeclarationIgnoresShadowingAnonymousFunctionBinding() {
        // An `fn` parameter that reuses an outer name is an independent binding, so only the
        // trailing read belongs to the outer variable - the `fn`'s own parameter and body read
        // do not. Rename shares this search, so over-reporting here silently rewrites them.
        assertEquals(1, nonDeclarationUsageCount("usages_variable_shadowed_in_anonymous_function.ex"))
    }

    fun testFindUsagesOnAnonymousFunctionParameterIgnoresShadowedOuterBinding() {
        // The mirror direction: from the `fn` parameter, only its own body read is a usage.
        assertEquals(1, nonDeclarationUsageCount("usages_variable_shadowing_anonymous_function_parameter.ex"))
    }

    fun testFindUsagesOnVariableDeclarationIgnoresShadowingCaseClauseBinding() {
        assertEquals(1, nonDeclarationUsageCount("usages_variable_shadowed_in_case_clause.ex"))
    }

    fun testFindUsagesOnVariableDeclarationIgnoresShadowingComprehensionBinding() {
        assertEquals(1, nonDeclarationUsageCount("usages_variable_shadowed_in_comprehension.ex"))
    }

    fun testFindUsagesOnVariableDeclarationIgnoresShadowingComprehensionDoBlockBinding() {
        assertEquals(1, nonDeclarationUsageCount("usages_variable_shadowed_in_comprehension_do_block.ex"))
    }

    fun testFindUsagesOnComprehensionKeywordDoGeneratorFindsBodyRead() {
        assertEquals(1, nonDeclarationUsageCount("usages_comprehension_keyword_do_generator.ex"))
    }

    fun testFindUsagesOnNestedComprehensionKeywordDoGeneratorFindsBodyRead() {
        // The inner generator sits in the OUTER comprehension's `do:` value, so it must still read
        // as a binding of its own rather than as a body read of the outer one.
        assertEquals(1, nonDeclarationUsageCount("usages_nested_comprehension_keyword_do_generator.ex"))
    }

    fun testCtrlClickOnIgnoredParameterDeclarationChoosesShowUsages() {
        myFixture.configureByFiles("usages_ignored_parameter_declaration.ex")
        myFixture.assertShowUsagesChosenAtCaret()
        assertEquals(1, myFixture.searchTargetCountAtCaret())
    }

    fun testCtrlClickOnIgnoredVariableDeclarationChoosesShowUsages() {
        myFixture.configureByFiles("usages_ignored_variable_declaration.ex")
        myFixture.assertShowUsagesChosenAtCaret()
        assertEquals(1, myFixture.searchTargetCountAtCaret())
    }

    fun testCtrlClickOnVariableUsageChoosesGotoDeclaration() {
        assertCtrlClickChoosesGotoDeclaration("goto_declaration_variable_usage.ex")
    }

    fun testCtrlClickOnParameterUsageChoosesGotoDeclaration() {
        assertCtrlClickChoosesGotoDeclaration("goto_declaration_parameter_usage.ex")
    }

    fun testGoToDeclarationFromVariableUsageNavigatesToDeclaration() {
        myFixture.configureByFiles("goto_declaration_variable_usage.ex")
        val target = myFixture.gotoDeclarationDestinationAtCaret()
        assertNotNull("Go To Declaration should navigate to variable declaration", target)
        assertEquals("variable", target!!.text)
    }

    fun testGoToDeclarationFromLaterUseNavigatesIntoInterpolation() {
        myFixture.configureByFiles("goto_declaration_variable_declared_in_interpolation.ex")
        val target = myFixture.gotoDeclarationDestinationAtCaret()
        assertNotNull("Go To Declaration should navigate to the declaration inside the interpolation", target)
        assertEquals("variable", target!!.text)
    }

    fun testGoToDeclarationFromLaterUseNavigatesIntoQuotedAtom() =
        assertGotoDeclarationNavigatesToVariable("goto_declaration_variable_declared_in_quoted_atom.ex")

    fun testGoToDeclarationFromLaterUseNavigatesIntoQuotedKeywordKey() =
        assertGotoDeclarationNavigatesToVariable("goto_declaration_variable_declared_in_quoted_keyword_key.ex")

    fun testGoToDeclarationFromLaterUseNavigatesIntoMapUpdate() =
        assertGotoDeclarationNavigatesToVariable("goto_declaration_variable_declared_in_map_update.ex")

    fun testGoToDeclarationFromLaterUseNavigatesIntoBareList() =
        assertGotoDeclarationNavigatesToVariable("goto_declaration_variable_declared_in_bare_list.ex")

    fun testGoToDeclarationFromLaterUseNavigatesIntoBareString() =
        assertGotoDeclarationNavigatesToVariable("goto_declaration_variable_declared_in_bare_string.ex")

    fun testGoToDeclarationFromLaterUseNavigatesIntoCallArgument() =
        assertGotoDeclarationNavigatesToVariable("goto_declaration_variable_declared_in_call_argument.ex")

    fun testGoToDeclarationFromUseInsideInterpolationNavigatesToDeclaration() {
        myFixture.configureByFiles("goto_declaration_variable_used_in_interpolation.ex")
        val target = myFixture.gotoDeclarationDestinationAtCaret()
        assertNotNull("Go To Declaration should navigate to the declaration before the string", target)
        assertEquals("variable", target!!.text)
    }

    fun testCtrlClickOnMatchRhsVariableUsageChoosesGotoDeclaration() {
        // `y = variable` reads `variable`; the gesture must navigate to its declaration, not show the read's own usages.
        assertCtrlClickChoosesGotoDeclaration("goto_declaration_variable_usage_match_rhs.ex")
    }

    fun testCtrlClickOnMatchRhsVariableUsageInListChoosesGotoDeclaration() {
        assertCtrlClickChoosesGotoDeclaration("goto_declaration_variable_usage_match_rhs_list.ex")
    }

    fun testCtrlClickOnMatchRhsVariableUsageInInterpolationChoosesGotoDeclaration() {
        assertCtrlClickChoosesGotoDeclaration("goto_declaration_variable_usage_match_rhs_interpolation.ex")
    }

    fun testCtrlClickOnPinnedMatchRhsVariableChoosesGotoDeclaration() {
        assertCtrlClickChoosesGotoDeclaration("goto_declaration_pin_match_rhs.ex")
    }

    fun testCtrlClickOnPinnedMatchLhsVariableChoosesGotoDeclaration() {
        assertCtrlClickChoosesGotoDeclaration("goto_declaration_pin_match_lhs.ex")
    }

    fun testCtrlClickOnPinnedMatchRhsVariableInMapPatternChoosesGotoDeclaration() {
        assertCtrlClickChoosesGotoDeclaration("goto_declaration_pin_match_rhs_map.ex")
    }

    fun testCtrlClickOnPinnedMatchRhsVariableInListPatternChoosesGotoDeclaration() {
        assertCtrlClickChoosesGotoDeclaration("goto_declaration_pin_match_rhs_list.ex")
    }

    fun testCtrlClickOnPinnedMatchRhsVariableInSingleElementListPatternChoosesGotoDeclaration() {
        assertCtrlClickChoosesGotoDeclaration("goto_declaration_pin_match_rhs_list_many.ex")
    }

    fun testCtrlClickOnPinnedMatchLhsVariableInMapPatternChoosesGotoDeclaration() {
        assertCtrlClickChoosesGotoDeclaration("goto_declaration_pin_match_lhs_map.ex")
    }

    fun testCtrlClickOnPinnedMatchLhsVariableInListPatternChoosesGotoDeclaration() {
        assertCtrlClickChoosesGotoDeclaration("goto_declaration_pin_match_lhs_list.ex")
    }

    fun testCtrlClickOnPinnedMatchLhsVariableInListPatternWithSiblingChoosesGotoDeclaration() {
        assertCtrlClickChoosesGotoDeclaration("goto_declaration_pin_match_lhs_list_many.ex")
    }

    fun testCtrlClickOnPinnedMatchRhsVariableInTuplePatternChoosesGotoDeclaration() {
        assertCtrlClickChoosesGotoDeclaration("goto_declaration_pin_match_rhs_tuple.ex")
    }

    fun testCtrlClickOnPinnedMatchLhsVariableInTuplePatternChoosesGotoDeclaration() {
        assertCtrlClickChoosesGotoDeclaration("goto_declaration_pin_match_lhs_tuple.ex")
    }

    fun testCtrlClickOnPinnedMatchRhsVariableInListHeadTailPatternChoosesGotoDeclaration() {
        assertCtrlClickChoosesGotoDeclaration("goto_declaration_pin_match_rhs_list_head_tail.ex")
    }

    fun testCtrlClickOnPinnedMatchLhsVariableInListHeadTailPatternChoosesGotoDeclaration() {
        assertCtrlClickChoosesGotoDeclaration("goto_declaration_pin_match_lhs_list_head_tail.ex")
    }

    /** Kernel's own `defmacro defdelegate(funs, opts)` is a macro clause, whose parameters its body reads. */
    fun testParameterOfAMacroHeadedAsADelegation() {
        myFixture.configureByText(
            "kernel.ex",
            "defmodule Kernel do\n  defmacro defdelegate(funs, opts) do\n    {<caret>funs, opts}\n  end\nend\n"
        )

        assertEquals(1, myFixture.nonDeclarationUsageCountAtCaret(project))
    }

    private fun nonDeclarationUsageCount(vararg files: String): Int {
        myFixture.configureByFiles(*files)
        return myFixture.nonDeclarationUsageCountAtCaret(project)
    }

    private fun assertGotoDeclarationNavigatesToVariable(fileName: String) {
        myFixture.configureByFiles(fileName)
        val target = myFixture.gotoDeclarationDestinationAtCaret()
        assertNotNull("Go To Declaration should navigate to the declaration", target)
        assertEquals("variable", target!!.text)
    }

    private fun assertCtrlClickChoosesGotoDeclaration(fileName: String) {
        myFixture.configureByFiles(fileName)
        myFixture.assertGotoDeclarationChosenAtCaret()
    }
}
