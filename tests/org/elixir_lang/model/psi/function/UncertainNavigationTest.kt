package org.elixir_lang.model.psi.function

import com.intellij.codeInsight.highlighting.actions.HighlightUsagesAction
import com.intellij.ide.impl.HeadlessDataManager
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.code_insight.renameTargetsAtCaret
import org.elixir_lang.code_insight.searchTargetsAtCaret
import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.expander.ExpanderSourceRows.lineOf
import org.elixir_lang.model.psi.config.caret

/**
 * With "Run the expander" on, a call into a module in two copies, either of which Elixir may load, offers each copy's
 * definition to Go To. Which one the call reaches isn't known, so neither rename nor Find Usages starts from the call,
 * and Highlight Usages in File marks nothing there, while each copy's own definition is still renamed from itself.
 */
@Suppress("UnstableApiUsage")
class UncertainNavigationTest : ExpanderSettingTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
        expanderOn()
        myFixture.addFileToProject("lib/b1.ex", B)
        myFixture.addFileToProject("lib/b2.ex", B)
    }

    fun testGoToOffersEachCopysDefinition() {
        atTheCall()

        assertEquals(listOf("b1.ex:2", "b2.ex:2"), goTo())
    }

    fun testNeitherRenameNorFindUsagesStartsAtTheCall() {
        atTheCall()

        assertEquals("rename [] | find usages []", renameAndFindUsages())
    }

    fun testHighlightUsagesInFileMarksNothingAtTheCall() {
        atTheCall()

        assertEquals(emptyList<String>(), highlightUsagesInFile())
    }

    /** Highlight Usages in File marks a certain find's uses: what the rows above see as nothing is the action's answer. */
    fun testHighlightUsagesInFileMarksACertainFindsUses() {
        myFixture.configureByText("a.ex", "defmodule A do\n  def f(x), do: x\n  def g, do: <caret>f(1)\nend\n")

        assertEquals(listOf("f", "f"), highlightUsagesInFile())
    }

    /** An MFA atom naming the function is a use of it as the call is: Go To offers each copy, and nothing else starts. */
    fun testAnMfaAtomOffersEachCopyAndNeitherRenameNorFindUsagesStartsAtIt() {
        myFixture.configureByText("a.ex", "defmodule A do\n  def g, do: apply(B, :<caret>f, [1])\nend\n")

        assertEquals("go to [b1.ex:2, b2.ex:2] | rename [] | find usages []", "go to ${goTo()} | ${renameAndFindUsages()}")
    }

    fun testOneCopysDefinitionIsRenamedFromItself() {
        myFixture.caret("lib/b1.ex", "def <caret>f(x)")

        assertEquals(
            listOf("FunctionSymbol(B.f/1, macro=false) in b1.ex"),
            myFixture.renameTargetsAtCaret().map { "$it in ${(it as FunctionSymbol).file.name}" },
        )
    }

    /** What Highlight Usages in File, run at the caret, marks: each highlighter's text. */
    private fun highlightUsagesInFile(): List<String> {
        assertTrue("Highlight Usages in File ran", myFixture.testAction(HighlightUsagesAction()).isEnabled)

        return myFixture.editor.markupModel.allHighlighters.map { myFixture.file.text.substring(it.startOffset, it.endOffset) }.sorted()
    }

    /** Go To's targets at the caret, each as `file:line`. */
    private fun goTo(): List<String?>? =
        myFixture.gotoDeclarationTargetsAtCaret()
            ?.map { target -> target.destination?.let { "${it.containingFile.name}:${lineOf(it)}" } }
            ?.sortedBy { it.orEmpty() }

    /** The rename and Find Usages targets at the caret. */
    private fun renameAndFindUsages(): String =
        "rename ${myFixture.renameTargetsAtCaret()} | find usages ${myFixture.searchTargetsAtCaret(project)}"

    /** The caret on `f` in `a.ex`'s call `B.f(1)`. */
    private fun atTheCall() {
        myFixture.configureByText("a.ex", "defmodule A do\n  def g, do: B.<caret>f(1)\nend\n")
    }

    private companion object {
        const val B = "defmodule B do\n  def f(x), do: x\nend\n"
    }
}
