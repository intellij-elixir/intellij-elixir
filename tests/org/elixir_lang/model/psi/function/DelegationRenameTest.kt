package org.elixir_lang.model.psi.function

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.command.WriteCommandAction
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret
import org.elixir_lang.code_insight.renameTargetsAtCaret

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

    /** A target with defaults is declared at its lowest arity, and the head without `as:` still names it. */
    fun testFindUsagesFromATargetWithDefaults() {
        myFixture.configureByText("m.ex", DEFAULTS)
        myFixture.editor.caretModel.moveToOffset(DEFAULTS.indexOf("def renamee") + 5)

        assertEquals(listOf(2, 6, 7), usageLines())
    }

    fun testRenameFromATargetWithDefaults() {
        myFixture.configureByText("m.ex", DEFAULTS)
        myFixture.editor.caretModel.moveToOffset(DEFAULTS.indexOf("def renamee") + 5)

        myFixture.renameTargetAtCaret("fresh")

        assertEquals(DEFAULTS.replace("renamee", "fresh"), myFixture.file.text)
    }

    /** From the delegation's head or a call of it, the target with defaults renames with it, and no `as:` is added. */
    fun testRenameFromADelegationToATargetWithDefaults() =
        assertRenamesFrom(DEFAULTS, DEFAULTS.replace("renamee", "cons"), "defdelegate ren|amee", "M.ren|amee")

    fun testRenameFromTheHeadWithAs() {
        myFixture.configureByText("m.ex", WITH_AS)
        myFixture.editor.caretModel.moveToOffset(WITH_AS.indexOf("renamee") + 1)

        myFixture.renameTargetAtCaret("fresh")

        assertEquals(WITH_AS.replace("renamee", "fresh"), myFixture.file.text)
    }

    /** A use names the delegation, so with `as:` the rename leaves the target and `as:` as they are. */
    fun testRenameFromTheUseWithAs() {
        myFixture.configureByText("m.ex", WITH_AS)
        myFixture.editor.caretModel.moveToOffset(WITH_AS.indexOf("M.renamee") + 3)

        myFixture.renameTargetAtCaret("fresh")

        assertEquals(WITH_AS.replace("renamee", "fresh"), myFixture.file.text)
    }

    fun testFindUsagesFromTheUseWithAs() {
        myFixture.configureByText("m.ex", WITH_AS)
        myFixture.editor.caretModel.moveToOffset(WITH_AS.indexOf("M.renamee") + 3)

        assertEquals(listOf(6, 7), usageLines())
    }

    /**
     * Without `as:`, renaming only the head and its calls would leave the delegation calling a function that is not
     * there, so a rename whose target is found at no arity the head passes on is refused, from the head and from a use.
     */
    fun testRenameIsRefusedAtAWrongArityTarget() {
        assertRefused(WRONG_ARITY, "d(a), to:", "its target T.d/1 is not found")
        assertRefused(WRONG_ARITY, "d(1)\nend\n", "its target T.d/1 is not found")
    }

    fun testRenameIsRefusedAtAnUnresolvableTarget() {
        assertRefused(UNRESOLVABLE, "d(a), to:", "its target Nowhere.d/1 is not found")
        assertRefused(UNRESOLVABLE, "d(1)\nend\n", "its target Nowhere.d/1 is not found")
    }

    /** A delegation renamed with the one the rename starts from would be left calling a function that is not there too. */
    fun testRenameIsRefusedAlongAChain() {
        val reason = "a function renamed with it delegates without `as:`, and its target Nowhere.x/1 is not found"

        assertRefused(CHAIN, "x(a), to: N", reason)
        assertRefused(CHAIN, "x(1)\nend\n", reason)
    }

    /** With options that are not a keyword list, there is no `as:` to keep the delegation calling its target by. */
    fun testRenameIsRefusedWhenTheOptionsAreNotAKeywordList() {
        val reason = "its options are not a keyword list, so `as:` cannot be added"

        assertRefused(OPTIONS, "d(a), @opts", reason)
        assertRefused(OPTIONS, "d(1)\nend\n", reason)
    }

    fun testRenameIsRefusedAlongAChainEndingInOptionsThatAreNotAKeywordList() {
        val reason =
            "a function renamed with it delegates without `as:`, and its options are not a keyword list, so `as:` cannot be added"

        assertRefused(CHAIN_TO_OPTIONS, "x(a), to: N", reason)
        assertRefused(CHAIN_TO_OPTIONS, "x(1)\nend\n", reason)
    }

    /** A delegation a `quote` injects is called in modules not known until the `quote` is used, even with a found target. */
    fun testRenameIsRefusedAtAnInjectedHeadWithAFoundTarget() {
        assertRefused(INJECTED, "d(a), to:", "declared by `quote`")
        assertRefused(INJECTED, "d(1)\nend\n", "declared by `quote`")
    }

    /** The rename with the caret at [caret] in [text] is refused with a reason containing [reason], and the file is unchanged. */
    private fun assertRefused(text: String, caret: String, reason: String) {
        myFixture.configureByText("refused.ex", text)
        myFixture.editor.caretModel.moveToOffset(text.lastIndexOf(caret))
        val targets = myFixture.renameTargetsAtCaret()

        assertNotEmpty(targets)

        for (target in targets) {
            val failure = runCatching { myFixture.renameTarget(target, "fresh") }.exceptionOrNull()

            assertTrue(
                "Renaming $target at `$caret` should be refused as $reason, but " +
                    (failure?.let { "failed with $it" } ?: "renamed it"),
                generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains(reason) }
            )
        }

        assertEquals(text, myFixture.file.text)
    }

    /**
     * Renames from each caret in a fresh copy of [text], to `y` at a caret on `x` and to `cons` otherwise, and asserts
     * the result is [after]. A caret is written as the text around it, with `|` where it goes.
     */
    private fun assertRenamesFrom(
        text: String,
        after: String,
        vararg carets: String = arrayOf("defdelegate sn|oc", "M.sn|oc")
    ) {
        carets.forEachIndexed { index, caret ->
            myFixture.configureByText("from$index.ex", text)
            val virtualFile = myFixture.file.virtualFile
            myFixture.editor.caretModel.moveToOffset(text.indexOf(caret.replace("|", "")) + caret.indexOf('|'))

            try {
                myFixture.renameTargetAtCaret(if ("x|" in caret) "y" else "cons")
                assertEquals("from `$caret`", after, myFixture.file.text)
            } finally {
                WriteCommandAction.runWriteCommandAction(project) { virtualFile.delete(this) }
            }
        }
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

        const val DEFAULTS =
            "defmodule T do\n  def renamee(a, b \\\\ nil), do: {a, b}\nend\n\n" +
                "defmodule M do\n  defdelegate renamee(a, b \\\\ nil), to: T\n  def m_use, do: M.renamee(1)\nend\n"

        const val WRONG_ARITY =
            "defmodule T do\n  def d(a, b), do: {a, b}\n  def t_use, do: T.d(1, 2)\nend\n\n" +
                "defmodule M do\n  defdelegate d(a), to: T\n  def m_use, do: M.d(1)\nend\n"

        const val INJECTED =
            "defmodule T do\n  def d(a), do: a\nend\n\n" +
                "defmodule U do\n  defmacro __using__(_) do\n    quote do\n      defdelegate d(a), to: T\n    end\n  end\nend\n\n" +
                "defmodule M do\n  use U\n  def m_use, do: d(1)\nend\n"

        const val CHAIN =
            "defmodule N do\n  defdelegate x(a), to: Nowhere\nend\n\n" +
                "defmodule M do\n  defdelegate x(a), to: N\n  def m_use, do: M.x(1)\nend\n"

        const val CHAIN_TO_OPTIONS =
            "defmodule N do\n  @opts [to: T]\n  defdelegate x(a), @opts\nend\n\n" +
                "defmodule M do\n  defdelegate x(a), to: N\n  def m_use, do: M.x(1)\nend\n"

        const val OPTIONS = "defmodule M do\n  @opts [to: T]\n  defdelegate d(a), @opts\n  def m_use, do: M.d(1)\nend\n"

        const val UNRESOLVABLE = "defmodule M do\n  defdelegate d(a), to: Nowhere\n  def m_use, do: M.d(1)\nend\n"

        const val WITH_AS =
            "defmodule T do\n  def other(x), do: x\nend\n\n" +
                "defmodule M do\n  defdelegate renamee(x), to: T, as: :other\n  def m_use, do: M.renamee(1)\nend\n"
    }
}
