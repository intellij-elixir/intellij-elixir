package org.elixir_lang.annotator

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import junit.framework.TestCase.fail
import org.elixir_lang.ElixirFileType
import org.elixir_lang.injection.ElixirSigilInjector
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.settings.ElixirExperimentalSettings

/**
 * Asserts that the errors in [source] are [expected], which come from Elixir inside a template sigil. A failure also
 * says what is injected at the first expected error and what highlighting again reports, and the test's log traces
 * the platform's highlight updates.
 */
internal fun assertTemplateErrors(
    fixture: CodeInsightTestFixture,
    disposable: Disposable,
    languageLevel: ElixirLanguageLevel,
    source: String,
    vararg expected: Pair<String, String>
) {
    require(expected.isNotEmpty()) { "expected errors from inside the template" }

    val settings = ElixirExperimentalSettings.instance
    val originalEnableHtmlInjection = settings.state.enableHtmlInjection
    settings.state.enableHtmlInjection = true

    try {
        val project = fixture.project
        InjectedLanguageManager.getInstance(project).registerMultiHostInjector(ElixirSigilInjector(), disposable)

        for (category in HIGHLIGHT_UPDATE_CATEGORIES) {
            val logger = Logger.getInstance(category)

            if (!logger.isTraceEnabled) {
                logger.setLevel(LogLevel.TRACE)
                Disposer.register(disposable) { logger.setLevel(LogLevel.INFO) }
            }
        }

        ElixirLanguageLevelResolver.overrideLanguageLevel(project, languageLevel)
        fixture.configureByText(ElixirFileType.INSTANCE, source)
        val errors = errors(fixture, source)

        if (errors != expected.toList()) {
            val word = expected.first().first
            val injected = InjectedLanguageManager.getInstance(project).findInjectedElementAt(fixture.file, source.indexOf(word))

            fail(
                "errors in ${escaped(source)} on $languageLevel: expected ${expected.toList()} but was $errors; " +
                    "injected at ${escaped(word)}: ${injected?.node?.elementType} " +
                    "in ${injected?.containingFile?.viewProvider?.baseLanguage?.id}; " +
                    "highlighting again: ${errors(fixture, source)}"
            )
        }
    } finally {
        settings.state.enableHtmlInjection = originalEnableHtmlInjection
    }
}

internal fun escaped(source: String): String =
    source.codePoints().toArray().joinToString("") { if (it in 0x20..0x7E) it.toChar().toString() else "\\u{%X}".format(it) }

private fun errors(fixture: CodeInsightTestFixture, source: String): List<Pair<String, String?>> =
    fixture
        .doHighlighting(HighlightSeverity.ERROR)
        .map { source.substring(it.startOffset, it.endOffset) to it.description }

private val HIGHLIGHT_UPDATE_CATEGORIES = listOf(
    "#com.intellij.codeInsight.daemon.impl.HighlightInfoUpdaterImpl",
    "#com.intellij.codeInsight.daemon.impl.UpdateHighlightersUtil"
)
