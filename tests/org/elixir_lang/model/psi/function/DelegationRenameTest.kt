package org.elixir_lang.model.psi.function

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.command.WriteCommandAction
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret

/**
 * Without `as:`, a `defdelegate`'s head also names the function it delegates to, so the two are one function to rename
 * and to find: from the head, the target's declaration or a use of either. With `as:` the head's name is the
 * delegation's own.
 */
@Suppress("UnstableApiUsage")
class DelegationRenameTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testRenameWithoutAs() =
        assertEveryOccurrence { caret ->
            myFixture.renameTargetAtCaret("fresh")
            assertEquals(caret, WITHOUT_AS.replace("renamee", "fresh"), myFixture.file.text)
        }

    fun testFindUsagesWithoutAs() =
        assertEveryOccurrence { caret -> assertEquals(caret, listOf(2, 3, 7, 8), usageLines()) }

    fun testRenameFromTheHeadWithAs() {
        myFixture.configureByText("m.ex", WITH_AS)
        myFixture.editor.caretModel.moveToOffset(WITH_AS.indexOf("renamee") + 1)

        myFixture.renameTargetAtCaret("fresh")

        assertEquals(WITH_AS.replace("renamee", "fresh"), myFixture.file.text)
    }

    /** Runs [check] with the caret on each occurrence of `renamee` in a fresh copy of [WITHOUT_AS]. */
    private fun assertEveryOccurrence(check: (caret: String) -> Unit) {
        val text = WITHOUT_AS
        Regex("""\brenamee\b""").findAll(text).forEachIndexed { index, occurrence ->
            myFixture.configureByText("caret$index.ex", text)
            val virtualFile = myFixture.file.virtualFile
            myFixture.editor.caretModel.moveToOffset(occurrence.range.first + 1)

            try {
                check("caret on line ${text.take(occurrence.range.first).count { it == '\n' } + 1}")
            } finally {
                WriteCommandAction.runWriteCommandAction(project) { virtualFile.delete(this) }
            }
        }
    }

    private fun usageLines(): List<Int> =
        myFixture.psiUsagesAtCaret(project)
            .map { usage -> usage.file.text.take(usage.range.startOffset).count { it == '\n' } + 1 }
            .sorted()

    private companion object {
        const val WITHOUT_AS =
            "defmodule T do\n  def renamee(x), do: x\n  def t_use, do: T.renamee(1)\nend\n\n" +
                "defmodule M do\n  defdelegate renamee(x), to: T\n  def m_use, do: M.renamee(1)\nend\n"

        const val WITH_AS =
            "defmodule T do\n  def other(x), do: x\nend\n\n" +
                "defmodule M do\n  defdelegate renamee(x), to: T, as: :other\n  def m_use, do: M.renamee(1)\nend\n"
    }
}
