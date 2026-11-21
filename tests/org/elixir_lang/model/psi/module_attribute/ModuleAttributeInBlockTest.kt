package org.elixir_lang.model.psi.module_attribute

import com.intellij.openapi.util.TextRange
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.completionStringsAtCaret
import org.elixir_lang.code_insight.gotoDeclarationTargetsAtCaret
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret

/**
 * Elixir accumulates module attributes through a module body in order, including through a block that runs in place
 * at module level, such as an `if` or a `case`, and through a block that a DSL's expansion runs there.
 */
class ModuleAttributeInBlockTest : PlatformTestCase() {
    fun testGotoDeclarationControl() =
        assertEquals(listOf("@timeout 5000"), declarationLines("@timeout 5000\n\n  def f, do: @timeout<caret>"))

    /** The walk reads a block last-first, as it reads the module body, so the `else` branch is nearest. */
    fun testGotoDeclarationThroughIfElse() =
        assertEquals(
            listOf("@timeout 5000"),
            declarationLines(
                "if Mix.env() == :test do\n    @timeout 10\n  else\n    @timeout 5000\n  end\n\n  def f, do: @timeout<caret>"
            )
        )

    fun testGotoDeclarationThroughCase() =
        assertEquals(
            listOf("_ -> @timeout 20"),
            declarationLines("case Mix.env() do\n    :test -> @timeout 10\n    _ -> @timeout 20\n  end\n\n  def f, do: @timeout<caret>")
        )

    fun testGotoDeclarationThroughKeywordIf() =
        assertEquals(
            listOf("if true, do: @timeout 10"),
            declarationLines("if true, do: @timeout 10\n\n  def f, do: @timeout<caret>")
        )

    fun testGotoDeclarationThroughKeywordElse() =
        assertEquals(
            listOf("if true, do: nil, else: @timeout 10"),
            declarationLines("if true, do: nil, else: @timeout 10\n\n  def f, do: @timeout<caret>")
        )

    fun testGotoDeclarationThroughDslBlock() =
        assertEquals(
            listOf("@timeout 10"),
            declarationLines("settings do\n    @timeout 10\n  end\n\n  def f, do: @timeout<caret>")
        )

    fun testGotoDeclarationPrefersBlockAfterEarlierDeclaration() =
        assertEquals(
            listOf("@limit 100"),
            declarationLines("@limit 1\n\n  if prod?() do\n    @limit 100\n  end\n\n  def f, do: @limit<caret>")
        )

    fun testGotoDeclarationThroughIfInMatch() =
        assertEquals(
            listOf("@timeout 10"),
            declarationLines("x =\n    if true do\n      @timeout 10\n    end\n\n  def f, do: @timeout<caret>")
        )

    fun testGotoDeclarationThroughIfInAttribute() =
        assertEquals(
            listOf("@timeout 10"),
            declarationLines("@outer (if true do\n    @timeout 10\n  end)\n\n  def f, do: @timeout<caret>")
        )

    fun testGotoDeclarationDoesNotFindLaterDeclaration() =
        assertEquals(emptyList<String>(), declarationLines("if true do\n    :ok\n  end\n\n  def f, do: @timeout<caret>\n\n  @timeout 10"))

    /** Elixir forbids setting an attribute in a function body, so one written there declares nothing for the module. */
    fun testGotoDeclarationDoesNotLookInsideDef() =
        assertEquals(emptyList<String>(), declarationLines("def g do\n    @inner 1\n  end\n\n  def f, do: @inner<caret>"))

    fun testGotoDeclarationDoesNotLookInsideNestedModule() =
        assertEquals(
            emptyList<String>(),
            declarationLines("if true do\n    defmodule Inner do\n      @inner 1\n    end\n  end\n\n  def f, do: @inner<caret>")
        )

    /** A read inside the block is walked from where it is, so a declaration after it in the block is not yet made. */
    fun testReadInsideBlockDoesNotSeeLaterDeclarationInIt() =
        assertEquals(
            listOf("@a 1"),
            declarationLines("@a 1\n\n  if true do\n    def f, do: @a<caret>\n    @a 2\n  end")
        )

    fun testReadInsideBlockSeesEarlierDeclarationInIt() =
        assertEquals(
            listOf("@a 2"),
            declarationLines("@a 1\n\n  if true do\n    @a 2\n    def f, do: @a<caret>\n  end")
        )

    fun testCompletionControl() =
        assertEquals(listOf("tally", "timeout"), completions("@timeout 10\n  @tally 1\n\n  def f do\n    @t<caret>\n  end"))

    fun testCompletionThroughIf() =
        assertEquals(
            listOf("tally", "timeout"),
            completions("if true do\n    @timeout 10\n  end\n\n  @tally 1\n\n  def f do\n    @t<caret>\n  end")
        )

    fun testCompletionThroughKeywordIf() =
        assertEquals(
            listOf("tally", "timeout"),
            completions("if true, do: @timeout 10\n\n  @tally 1\n\n  def f do\n    @t<caret>\n  end")
        )

    fun testCompletionThroughKeywordElse() =
        assertEquals(
            listOf("tally", "timeout"),
            completions("if true, do: nil, else: @timeout 10\n\n  @tally 1\n\n  def f do\n    @t<caret>\n  end")
        )

    fun testCompletionThroughDslBlock() =
        assertEquals(
            listOf("tally", "timeout"),
            completions("settings do\n    @timeout 10\n  end\n\n  @tally 1\n\n  def f do\n    @t<caret>\n  end")
        )

    fun testCompletionDoesNotLookInsideDef() =
        assertEquals(listOf("tally", "timeout"), completions("@timeout 10\n  @tally 1\n\n  def g do\n    @tick 1\n  end\n\n  def f do\n    @t<caret>\n  end"))

    fun testRenameControl() =
        assertEquals(
            "@renamed 1\n\n  @renamed 100\n\n  def f, do: @renamed",
            renamed("@limit<caret> 1\n\n  @limit 100\n\n  def f, do: @limit")
        )

    fun testRenameRenamesAStructName() =
        assertEquals(
            "@renamed Foo\n\n  def f(%@renamed{}), do: :ok",
            renamed("@m<caret> Foo\n\n  def f(%@m{}), do: :ok")
        )

    fun testRenameRenamesAKeyRead() =
        assertEquals(
            "@renamed [key: 1]\n\n  def f, do: @renamed[:key]",
            renamed("@config<caret> [key: 1]\n\n  def f, do: @config[:key]")
        )

    fun testRenameFromDeclarationBeforeIf() =
        assertEquals(
            "@renamed 1\n\n  if prod?() do\n    @renamed 100\n  end\n\n  def f, do: @renamed",
            renamed("@limit<caret> 1\n\n  if prod?() do\n    @limit 100\n  end\n\n  def f, do: @limit")
        )

    fun testRenameFromDeclarationInsideIf() =
        assertEquals(
            "@renamed 1\n\n  if prod?() do\n    @renamed 100\n  end\n\n  def f, do: @renamed",
            renamed("@limit 1\n\n  if prod?() do\n    @limit<caret> 100\n  end\n\n  def f, do: @limit")
        )

    fun testRenameFromReadThroughIfElse() =
        assertEquals(
            "if true do\n    @renamed 10\n  else\n    @renamed 5000\n  end\n\n  def f, do: @renamed",
            renamed("if true do\n    @timeout 10\n  else\n    @timeout 5000\n  end\n\n  def f, do: @timeout<caret>")
        )

    fun testRenameFromReadThroughKeywordIf() =
        assertEquals(
            "if true, do: @renamed 10\n\n  def f, do: @renamed",
            renamed("if true, do: @timeout 10\n\n  def f, do: @timeout<caret>")
        )

    fun testRenameFromReadThroughKeywordElse() =
        assertEquals(
            "if true, do: nil, else: @renamed 10\n\n  def f, do: @renamed",
            renamed("if true, do: nil, else: @timeout 10\n\n  def f, do: @timeout<caret>")
        )

    fun testRenameThroughCaseAndDslBlock() =
        assertEquals(
            "case :a do\n    :a -> @renamed 1\n  end\n\n  settings do\n    @renamed 2\n  end\n\n  def f, do: @renamed",
            renamed("case :a do\n    :a -> @limit<caret> 1\n  end\n\n  settings do\n    @limit 2\n  end\n\n  def f, do: @limit")
        )

    fun testRenameLeavesNestedModuleAlone() =
        assertEquals(
            "@renamed 1\n\n  if true do\n    defmodule Inner do\n      @limit 2\n    end\n  end",
            renamed("@limit<caret> 1\n\n  if true do\n    defmodule Inner do\n      @limit 2\n    end\n  end")
        )

    fun testWriteUsagesControl() =
        assertEquals(
            listOf("@limit 100", "def f, do: @limit"),
            usageLines("@limit<caret> 1\n\n  @limit 100\n\n  def f, do: @limit")
        )

    fun testWriteUsagesFindDeclarationInKeywordIf() =
        assertEquals(
            listOf("def f, do: @limit", "if prod?(), do: @limit 100"),
            usageLines("@limit<caret> 1\n\n  if prod?(), do: @limit 100\n\n  def f, do: @limit")
        )

    fun testWriteUsagesFindDeclarationInKeywordElse() =
        assertEquals(
            listOf("def f, do: @limit", "if prod?(), do: nil, else: @limit 100"),
            usageLines("@limit<caret> 1\n\n  if prod?(), do: nil, else: @limit 100\n\n  def f, do: @limit")
        )

    fun testWriteUsagesFindDeclarationInsideIf() =
        assertEquals(
            listOf("@limit 100", "def f, do: @limit"),
            usageLines("@limit<caret> 1\n\n  if prod?() do\n    @limit 100\n  end\n\n  def f, do: @limit")
        )

    private fun configure(body: String) {
        myFixture.configureByText("m.ex", "defmodule M do\n  $body\nend\n")
    }

    private fun declarationLines(body: String): List<String> {
        configure(body)

        return myFixture.gotoDeclarationTargetsAtCaret().orEmpty().map { lineAt(it.destination!!.textOffset) }
    }

    private fun completions(body: String): List<String>? {
        configure(body)

        return myFixture.completionStringsAtCaret()?.sorted()
    }

    private fun usageLines(body: String): List<String> {
        configure(body)

        return myFixture.psiUsagesAtCaret(project).filterNot { it.declaration }.map { lineAt(it.range.startOffset) }.sorted()
    }

    private fun renamed(body: String): String {
        configure(body)
        myFixture.renameTargetAtCaret("renamed")

        return myFixture.editor.document.text.removePrefix("defmodule M do\n  ").removeSuffix("\nend\n")
    }

    private fun lineAt(offset: Int): String {
        val document = myFixture.editor.document
        val line = document.getLineNumber(offset)

        return document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).trim()
    }
}
