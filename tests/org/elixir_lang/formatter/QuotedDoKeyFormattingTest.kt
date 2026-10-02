package org.elixir_lang.formatter

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_style.CodeStyleSettings

/**
 * A one-liner's `do:` body stays on its line, and a quoted `"do":` is that same key. The expected layouts are what
 * `mix format` gives, with the key left as written: `mix format` also rewrites `"do":` to `do:`.
 */
class QuotedDoKeyFormattingTest : PlatformTestCase() {
    fun testDoKeyListBody() = assertFormatted("if x, do: [a: 1, b: 2]\n")

    fun testQuotedDoKeyListBody() = assertFormatted("if x, \"do\": [a: 1, b: 2]\n")

    fun testDoKeyMapUpdateBody() = assertFormatted("if x, do: %{y | a: 1, b: 2}\n")

    fun testQuotedDoKeyMapUpdateBody() = assertFormatted("if x, \"do\": %{y | a: 1, b: 2}\n")

    private fun assertFormatted(source: String) {
        CodeStyle.doWithTemporarySettings(project, CodeStyle.getSettings(project)) { settings ->
            settings.getCustomSettings(CodeStyleSettings::class.java).MIX_FORMAT = false
            myFixture.configureByText("one_liner.ex", source)

            WriteCommandAction.runWriteCommandAction(project) {
                CodeStyleManager.getInstance(project).reformatText(myFixture.file, listOf(myFixture.file.textRange))
            }

            assertEquals(source, myFixture.file.text)
        }
    }
}
