package org.elixir_lang.injection.markdown

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.HeredocLiteral

/**
 * A `~S"""` doc's code block holding `\"""` gets `"""` in its injected Elixir, as the docstring Elixir builds drops the
 * `\` before an escaped heredoc terminator.
 */
class EscapedHeredocTerminatorInjectionTest : PlatformTestCase() {
    fun testAnEscapedTerminatorInADocCodeBlockIsInjectedUnescaped() {
        myFixture.configureByText(
            "doc.ex",
            "defmodule Doc do\n" +
                "  @doc ~S\"\"\"\n" +
                "  Builds a heredoc:\n" +
                "\n" +
                "      text = \\\"\"\"\n" +
                "      body\n" +
                "      \\\"\"\"\n" +
                "  \"\"\"\n" +
                "  def f, do: :ok\n" +
                "end\n",
        )
        val heredoc = PsiTreeUtil.findChildrenOfType(myFixture.file, HeredocLiteral::class.java).single()
        val injectedLanguageManager = InjectedLanguageManager.getInstance(project)
        val elixir = StringBuilder()

        // The injected file's own text is the host's, escapes and all: the platform parses the decoded text, then patches
        // the host's text back into the leaves. The unescaped text is what the Elixir was parsed from.
        injectedLanguageManager.enumerateEx(heredoc, myFixture.file, true) { injected, _ ->
            if (injected.language == ElixirLanguage) elixir.append(injectedLanguageManager.getUnescapedText(injected))
        }

        assertTrue("no Elixir injected", elixir.isNotEmpty())
        assertTrue(elixir.toString(), elixir.contains("text = \"\"\""))
        assertFalse(elixir.toString(), elixir.contains("\\\"\"\""))
    }
}
