// Shared setup for tests that exercise a HEEx component tag in either host - a top-level `.heex`
// file or a `~H` sigil injected into a `.ex` file. Every `~H` variant is derived from the same
// `.heex`/`.ex` fixture files via [heexSigilModuleText] rather than retyped.
package org.elixir_lang.heex.reference

import com.intellij.find.usages.api.PsiUsage
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
import org.elixir_lang.code_insight.renameTargetsAtCaret
import org.elixir_lang.code_insight.singleTargetPsiUsagesAtCaret
import org.elixir_lang.heex.HeexLanguage
import org.elixir_lang.injection.ElixirSigilInjector
import org.elixir_lang.psi.Sigil
import org.elixir_lang.settings.ElixirExperimentalSettings
import java.io.File

/** Enables `~H` injection and registers [ElixirSigilInjector] for the test's lifetime. */
abstract class HeexHostTestCase : PlatformTestCase() {
    private var originalEnableHtmlInjection = false

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
        val settings = ElixirExperimentalSettings.instance
        originalEnableHtmlInjection = settings.state.enableHtmlInjection
        settings.state.enableHtmlInjection = true
        InjectedLanguageManager
            .getInstance(project)
            .registerMultiHostInjector(ElixirSigilInjector(), testRootDisposable)
    }

    override fun tearDown() {
        try {
            ElixirExperimentalSettings.instance.state.enableHtmlInjection = originalEnableHtmlInjection
        } finally {
            super.tearDown()
        }
    }

    /**
     * Asserts a non-declaration usage of the symbol at the caret lies inside the literal
     * [expectedUsageText]. A count alone is not enough: a fixture's own `defdelegate` or second call
     * site is a legitimate usage that would satisfy it while the occurrence under test is missed.
     */
    protected fun assertUsageFound(expectedUsageText: String) {
        myFixture.assertShowUsagesChosenAtCaret()
        val usages: List<PsiUsage> = myFixture.singleTargetPsiUsagesAtCaret(project)

        val matchingUsage = usages.filterNot { it.declaration }.find { usage ->
            val text = usage.file.text
            val occurrenceStart = text.indexOf(expectedUsageText)
            occurrenceStart >= 0 && usage.range.startOffset in occurrenceStart until occurrenceStart + expectedUsageText.length
        }
        assertNotNull(
            "Expected a usage inside $expectedUsageText, got: ${usages.map { "decl=${it.declaration} file=${it.file.name} range=${it.range}" }}",
            matchingUsage
        )
    }

    /** Non-declaration usages of the symbol at the caret. */
    protected fun nonDeclarationUsagesAtCaret(): List<PsiUsage> =
        myFixture.singleTargetPsiUsagesAtCaret(project).filterNot { it.declaration }

    protected fun assertNoRenameTarget() {
        assertEquals(emptyList<Any>(), myFixture.renameTargetsAtCaret())
    }
}

/**
 * Raw text of a fixture file under this test's `getTestDataPath()`, trailing newline trimmed, for
 * reading a fixture without configuring it as the active file.
 */
fun CodeInsightTestFixture.fixtureText(relativePath: String): String =
    File(testDataPath, relativePath).readText().trimEnd('\n')

/**
 * Splices [heexBody] (a `.heex` fixture's raw text, `<caret>` and all) into a `~H'''...'''` heredoc
 * inside a new `def render(assigns) do ... end`, inserted before [entranceModuleText]'s closing
 * `end`, so the `~H` variant sees the same import/use/alias scope as the `.heex` variant's view
 * module. [otherModuleTexts] (companion `web.ex`/`core_components.ex` files) are appended as further
 * top-level `defmodule`s in the same file.
 *
 * [entranceModuleText] must end in a line that is exactly `end` at zero indentation, which every
 * fixture `.ex` file here does.
 */
fun heexSigilModuleText(entranceModuleText: String, heexBody: String, vararg otherModuleTexts: String): String {
    val lines = entranceModuleText.lines().toMutableList()
    val closingEnd = lines.indexOfLast { it == "end" }
    check(closingEnd >= 0) { "Expected a top-level `end` line in:\n$entranceModuleText" }
    lines.addAll(
        closingEnd,
        listOf("", "  def render(assigns) do", "    ~H'''") +
            heexBody.lines().map { "    $it" } +
            listOf("    '''", "  end")
    )
    return (listOf(lines.joinToString("\n")) + otherModuleTexts).joinToString("\n\n")
}

/**
 * Configures [moduleText] (typically from [heexSigilModuleText]) as `test.ex` with the caret inside
 * the sigil. With `caresAboutInjection` (the default) the fixture swaps `file`/`editor` to the
 * injected file, as the caret-driven production actions do; a fixture whose caret did not land in
 * the injection fails here rather than reporting no result for the wrong reason.
 */
fun CodeInsightTestFixture.configureHeexSigilHost(moduleText: String) {
    configureByText("test.ex", moduleText)
    check(file.viewProvider.hasLanguage(HeexLanguage.INSTANCE)) {
        "Expected the caret-in-injection swap to land on the injected HEEx file, got: ${file.language}"
    }
}

/**
 * The HEEx files injected into every `~H` sigil of the configured host file, for a fixture with no
 * caret in the sigil (so no fixture swap happens). A [heexSigilModuleText]-derived fixture can carry
 * more than one sigil, and document order does not say which holds the content under test.
 */
fun CodeInsightTestFixture.injectedHeexRoots(): List<PsiFile> {
    val hostFile = InjectedLanguageManager.getInstance(project).getTopLevelFile(file)
    val sigils = PsiTreeUtil.findChildrenOfType(hostFile, Sigil::class.java)
    check(sigils.isNotEmpty()) { "Sigil host not found in ${hostFile.name}" }
    val injectedLanguageManager = InjectedLanguageManager.getInstance(project)
    return sigils
        .flatMap { injectedLanguageManager.getInjectedPsiFiles(it).orEmpty() }
        .map { it.first.containingFile }
        .filter { it.language.isKindOf(HeexLanguage.INSTANCE) }
        .distinct()
}

/** The first [XmlTag] in the HTML root of any injected HEEx file of the configured host file. */
fun CodeInsightTestFixture.firstInjectedHeexTag(): XmlTag =
    injectedHeexRoots()
        .firstNotNullOfOrNull { PsiTreeUtil.findChildOfType(it.viewProvider.getPsi(HTMLLanguage.INSTANCE), XmlTag::class.java) }
        ?: error("Expected a tag inside a ~H sigil")
