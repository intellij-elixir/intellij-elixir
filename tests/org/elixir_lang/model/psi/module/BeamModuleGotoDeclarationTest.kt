package org.elixir_lang.model.psi.module

import com.intellij.ide.impl.HeadlessDataManager
import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationChosenAtCaret
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret

/**
 * Behavioural Go To Declaration coverage for module aliases that resolve only to a compiled `.beam`
 * (e.g. `Code` from the Elixir stdlib). These resolve to the coarse `beam.psi.impl.ModuleImpl` stub, so
 * they exercise the [ModuleReference] branch that routes through `navigationElement` into the decompiled
 * mirror's `defmodule` - without it the beam result is silently dropped and Ctrl+Click does nothing.
 */
class BeamModuleGotoDeclarationTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/module"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testCtrlClickOnBeamModuleQualifierChoosesGotoDeclaration() {
        myFixture.configureByFiles("beam_module_goto.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    fun testGoToDeclarationFromBeamModuleQualifierNavigatesToDecompiledDefmodule() {
        myFixture.configureByFiles("beam_module_goto.ex")
        assertNavigatesToDecompiledCode()
    }

    fun testCtrlClickOnBareBeamModuleNameChoosesGotoDeclaration() {
        myFixture.configureByFiles("beam_module_bare_goto.ex")
        myFixture.assertGotoDeclarationChosenAtCaret()
    }

    fun testGoToDeclarationFromBareBeamModuleNameNavigatesToDecompiledDefmodule() {
        myFixture.configureByFiles("beam_module_bare_goto.ex")
        assertNavigatesToDecompiledCode()
    }

    /** The `alias` a use goes through is how it was reached, not what it names, even though it is source. */
    fun testAnAliasedCompiledModuleResolvesToTheModuleNotTheAlias() {
        myFixture.configureByText(
            "aliased_beam_module.ex",
            "defmodule Aliased do\n  alias Code, as: C\n  def f, do: <caret>C.eval_string(\"1\")\nend\n"
        )

        val resolved = myFixture.file.findReferenceAt(myFixture.caretOffset)?.resolve()

        assertEquals("Elixir.Code.beam", resolved?.containingFile?.name)
    }

    private fun assertNavigatesToDecompiledCode() {
        val target = myFixture.gotoDeclarationDestinationAtCaret()
        assertNotNull("Go To Declaration should navigate into the decompiled Code module", target)
        assertTrue(
            "Should land in the decompiled Elixir.Code.beam, not the source file (was ${target!!.containingFile.name})",
            target.containingFile.name.startsWith("Elixir.Code")
        )
    }
}
