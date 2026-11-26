package org.elixir_lang.injection.markdown

import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.heex.HeexLanguage
import org.intellij.lang.regexp.RegExpLanguage
import org.intellij.plugins.markdown.lang.MarkdownLanguage
import org.elixir_lang.eex.Language as EexLanguage

/**
 * A documentation value that another injector fills gets no Markdown: the platform keeps one injection per stretch
 * of host text, and on 263.8311.30 it invalidates the injected file it replaces while annotators may still run on it.
 */
class OtherLanguageDocumentationInjectionTest : PlatformTestCase() {
    fun testACommentLanguageIsTheDocumentationsOnlyLanguage() = assertEach(
        listOf(
            Case(heredoc(), EexLanguage.INSTANCE, comment = "eex"),
            Case(heredoc(), HeexLanguage.INSTANCE, comment = "heex"),
            Case(heredoc(sigil = "~S"), EexLanguage.INSTANCE, comment = "eex"),
            Case(heredoc(sigil = "~S"), HeexLanguage.INSTANCE, comment = "heex"),
            Case("@doc \"$TEMPLATE\"", EexLanguage.INSTANCE, comment = "eex"),
            Case("@doc deprecated: \"$TEMPLATE\"", EexLanguage.INSTANCE, comment = "eex"),
            Case(heredoc("@moduledoc"), EexLanguage.INSTANCE, comment = "eex"),
            Case(heredoc("@typedoc"), EexLanguage.INSTANCE, comment = "eex"),
        )
    )

    fun testATemplateSigilIsTheDocumentationsOnlyLanguage() = assertEach(
        listOf(
            Case(heredoc(sigil = "~H"), HeexLanguage.INSTANCE),
            Case(heredoc(sigil = "~E"), EexLanguage.INSTANCE),
            Case(heredoc(sigil = "~L"), EexLanguage.INSTANCE),
            Case(heredoc(sigil = "~r"), RegExpLanguage.INSTANCE),
        )
    )

    fun testACommentInjectedTemplateStaysValidWhenTheDocIsInjected() {
        val source = source("eex", heredoc())
        myFixture.configureByText("doc.ex", source)
        val file = myFixture.file
        val manager = InjectedLanguageManager.getInstance(project)
        val template = manager.findInjectedElementAt(file, source.indexOf(TEMPLATE))?.containingFile

        assertEquals("no EEx is injected at the template", EexLanguage.INSTANCE, template?.viewProvider?.baseLanguage)

        PsiTreeUtil.collectElements(file) { true }.forEach { element -> manager.enumerateEx(element, file, false) { _, _ -> } }

        assertTrue("the template's injected EEx file was invalidated by another injection", template!!.isValid)
    }

    fun testOnlyTheProseValueOfADocumentationAttributeGetsMarkdown() {
        val failures = listOf(
            ProseCase("@doc since: \"1.0\"", "1.0", markdown = false),
            ProseCase("@doc \"since\": \"1.0\"", "1.0", markdown = false),
            ProseCase("@doc \"deprecated\": \"text\"", "deprecated", markdown = false),
            ProseCase("@doc since: \"1.0\", deprecated: \"text\"", "text", markdown = false),
            ProseCase("@doc deprecated: \"text\"", "text", markdown = true),
        ).mapNotNull { (doc, marker, markdown) ->
            val source = source(null, doc)
            myFixture.configureByText("doc.ex", source)
            val injected = injectedLanguagesByHost()
            val host = hostAt(source, marker)

            when {
                host == null -> "`$doc` has no host at `$marker`"
                (MarkdownLanguage.INSTANCE in injected[host].orEmpty()) != markdown ->
                    "`$doc` at `$marker` must ${if (markdown) "" else "not "}inject Markdown, but injected ${injected[host]}"
                else -> null
            }
        }

        assertEmpty(failures.joinToString("\n"), failures)
    }

    private data class Case(val doc: String, val language: Language, val comment: String? = null)

    private data class ProseCase(val doc: String, val marker: String, val markdown: Boolean)

    private fun assertEach(cases: List<Case>) {
        val failures = cases.mapNotNull { case ->
            val source = source(case.comment, case.doc)
            myFixture.configureByText("doc.ex", source)
            val languages = injectedLanguagesByHost()[hostAt(source, TEMPLATE)].orEmpty()

            if (languages == setOf(case.language)) null else "`${case.doc}` under `${case.comment}` injected $languages"
        }

        assertEmpty(failures.joinToString("\n"), failures)
    }

    private fun heredoc(attribute: String = "@doc", sigil: String = ""): String =
        "$attribute $sigil\"\"\"\n  $TEMPLATE\n  \"\"\""

    private fun source(comment: String?, doc: String): String =
        "defmodule Doc do\n" +
                comment?.let { "  # language=$it\n" }.orEmpty() +
                "  $doc\n" +
                "  def f, do: 1\n" +
                "end\n"

    private fun hostAt(source: String, marker: String): PsiLanguageInjectionHost? =
        PsiTreeUtil.getParentOfType(
            myFixture.file.findElementAt(source.indexOf(marker)),
            PsiLanguageInjectionHost::class.java,
            false
        )

    private fun injectedLanguagesByHost(): Map<PsiLanguageInjectionHost?, Set<Language>> {
        val manager = InjectedLanguageManager.getInstance(project)

        return enumeratedInjections(subject = "`${myFixture.file.text}`")
            .groupBy({ manager.getInjectionHost(it) }) { it.viewProvider.baseLanguage }
            .mapValues { (_, languages) -> languages.toSet() }
    }

    private companion object {
        const val TEMPLATE = "<div><%= admin %></div>"
    }
}
