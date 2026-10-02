package org.elixir_lang.injection.markdown

import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.settings.ElixirExperimentalSettings
import org.intellij.plugins.markdown.lang.MarkdownLanguage

/**
 * A documentation attribute whose value is an expression starting with a string must not inject Markdown into
 * that string.
 *
 * Injection is driven through `enumerateEx` on every element because `getInjectedPsiFiles` returns `null` for
 * an invalid host instead of throwing.
 */
class ExpressionDocInjectorTest : PlatformTestCase() {
    fun testLineConcatenated() = assertNoMarkdown("@doc \"text\" <> \"more\"")
    fun testLineAccessed() = assertNoMarkdown("@doc \"text\"[0]")
    fun testLinePiped() = assertNoMarkdown("@doc \"text\" |> String.trim()")
    fun testHeredocConcatenated() = assertNoMarkdown("@doc \"\"\"\n  text\n  \"\"\" <> \"more\"")
    fun testSigilHeredocConcatenated() = assertNoMarkdown("@doc ~S\"\"\"\n  text\n  \"\"\" <> \"more\"")
    fun testModuledocPiped() =
        assertNoMarkdown("@moduledoc \"README.md\" |> File.read!() |> String.split(\"<!-- MDOC !-->\") |> Enum.fetch!(1)")
    fun testTypedocConcatenated() = assertNoMarkdown("@typedoc \"text\" <> \"more\"")

    fun testEveryOperandShape() = assertEveryOperandShape()

    // With HTML injection on, `isValidHost` accepts any sigil, so only the documentation check keeps these out
    fun testEveryOperandShapeWithHtmlInjection() = withHtmlInjection { assertEveryOperandShape() }

    fun testLine() = assertMarkdown("@doc \"text\"")
    fun testHeredoc() = assertMarkdown("@doc \"\"\"\n  text\n  \"\"\"")
    fun testSigilHeredoc() = assertMarkdown("@doc ~S\"\"\"\n  text\n  \"\"\"")
    fun testSigilHeredocWithHtmlInjection() = withHtmlInjection { assertMarkdown("@doc ~S\"\"\"\n  text\n  \"\"\"") }
    fun testModuledoc() = assertMarkdown("@moduledoc \"text\"")
    fun testTypedoc() = assertMarkdown("@typedoc \"text\"")
    fun testDeprecated() = assertMarkdown("@doc deprecated: \"text\"")
    fun testQuotedDeprecated() = assertMarkdown("@doc \"deprecated\": \"text\"")

    private fun assertEveryOperandShape() {
        val failures = OPERANDS.flatMap { operand ->
            SHAPES.mapNotNull { shape ->
                val doc = "@doc " + shape.replace("OPERAND", operand)

                try {
                    assertNoMarkdown(doc)
                    null
                } catch (e: AssertionError) {
                    // `null` here would count as a passing shape
                    e.message ?: e.toString()
                }
            }
        }

        assertEmpty(failures.joinToString("\n"), failures)
    }

    private fun assertNoMarkdown(doc: String) {
        val languages = injectedLanguages(doc)

        assertFalse("`$doc` must not inject Markdown, but injected $languages", MarkdownLanguage.INSTANCE in languages)
    }

    private fun assertMarkdown(doc: String) {
        val languages = injectedLanguages(doc)

        assertTrue("`$doc` must inject Markdown, but injected $languages", MarkdownLanguage.INSTANCE in languages)
    }

    private fun injectedLanguages(doc: String): Set<Language> {
        myFixture.configureByText("doc.ex", "defmodule Doc do\n  $doc\n  def f, do: 1\nend\n")
        val file = myFixture.file
        val manager = InjectedLanguageManager.getInstance(project)
        val languages = mutableSetOf<Language>()

        val (_, loggedErrors) = captureLoggedErrors {
            PsiTreeUtil.collectElements(file) { true }.forEach { element ->
                manager.enumerateEx(element, file, true) { injectedPsi, _ -> languages.add(injectedPsi.language) }
            }
            myFixture.doHighlighting()
        }

        assertEmpty("`$doc` logged errors: ${loggedErrors.map { it.message }}", loggedErrors)

        return languages
    }

    private fun withHtmlInjection(block: () -> Unit) {
        val state = ElixirExperimentalSettings.instance.state
        val original = state.enableHtmlInjection
        state.enableHtmlInjection = true

        try {
            block()
        } finally {
            state.enableHtmlInjection = original
        }
    }

    private companion object {
        val OPERANDS = listOf(
            "\"text\"",
            "\"\"\"\n  text\n  \"\"\"",
            "~S\"text\"",
            "~S\"\"\"\n  text\n  \"\"\"",
            "'text'",
            "\"#{1} text\"",
        )

        val SHAPES = listOf(
            "OPERAND <> \"more\"",
            "OPERAND <> \"more\" <> \"most\"",
            "OPERAND ++ []",
            "OPERAND -- []",
            "OPERAND .. \"z\"",
            "OPERAND + 1",
            "OPERAND * 2",
            "OPERAND ** 2",
            "OPERAND == \"more\"",
            "OPERAND =~ \"more\"",
            "OPERAND < \"more\"",
            "OPERAND && \"more\"",
            "OPERAND || \"more\"",
            "OPERAND and true",
            "OPERAND or true",
            "OPERAND in []",
            "OPERAND not in []",
            "OPERAND |> String.trim()",
            "OPERAND |> String.trim() |> String.upcase()",
            "OPERAND <<< 1",
            "OPERAND ~> 1",
            "OPERAND <|> 1",
            "OPERAND = more",
            "OPERAND :: String.t()",
            "OPERAND when true",
            "OPERAND | \"more\"",
            "OPERAND \\\\ \"more\"",
            "OPERAND[0]",
            "OPERAND.length",
            "OPERAND.()",
            "(OPERAND <> \"more\")",
        )
    }
}
