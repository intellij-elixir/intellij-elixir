@file:Suppress("UnstableApiUsage")

package org.elixir_lang.model.psi.config

import com.intellij.find.usages.api.PsiUsage
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiManager
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.chosenTargetPsiUsagesAtCaret
import org.elixir_lang.code_insight.singleTargetPsiUsagesAtCaret
import org.elixir_lang.code_insight.singleTargetPsiUsagesAtCaretInScope
import org.elixir_lang.expander.LegSdk
import org.elixir_lang.injection.PsiLanguageInjectionHost
import org.elixir_lang.psi.call.Call
import java.io.File

/** Find Usages of an application env key: its reads, wherever they are, and the config lines that set it. */
class ConfigKeyUsagesTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/config"

    override fun setUp() {
        super.setUp()
        disableMixDepsCheck(testRootDisposable)
        LegSdk.setUp(module, testRootDisposable)
        myFixture.copyDirectoryToProject("usages", "")
    }

    override fun tearDown() {
        try {
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

    fun testUsagesOfAKeyAreItsReadsAndSettings() {
        myFixture.caret(CONFIG, "retr<caret>ies: 3")

        assertEquals(
            listOf(
                "config.exs:3 retries",
                "dev.exs:3 retries",
                "worker.ex:4 retries",
                "worker.ex:7 Application.get_all_env(:my_app)",
                "worker.ex:9 key",
            ),
            usages(),
        )
    }

    fun testUsagesIncludeAReadInAHeexFile() {
        myFixture.addFileToProject("lib/my_app_web/page.html.heex", "<p><%= Application.get_env(:my_app, :retries) %></p>\n")
        myFixture.caret(CONFIG, "retr<caret>ies: 3")

        assertContainsElements(usages(), "page.html.heex:1 retries")
    }

    fun testUsagesIncludeAnAliasKeyReadByExpansion() {
        myFixture.caret(CONFIG, "MyApp.Re<caret>po")

        assertContainsElements(configKeyUsages(), "worker.ex:5 Repo")
    }

    fun testUsagesIncludeAModuleKeyRead() {
        myFixture.caret(CONFIG, "MyApp.Re<caret>po")

        assertContainsElements(configKeyUsages(), "repo.ex:2 __MODULE__")
    }

    fun testUsagesIncludeAnAnyAppRead() {
        myFixture.caret(DEV, "lev<caret>el: :debug")

        assertContainsElements(usages(), "worker.ex:6 level")
    }

    fun testUsagesIncludeAnAnyAppModuleKeyRead() {
        myFixture.caret(CONFIG, "MyApp.Re<caret>po")

        assertContainsElements(configKeyUsages(), "repo.ex:3 __MODULE__")
    }

    fun testUsagesIncludeGetAllEnvOfTheApp() {
        myFixture.caret(DEV, ":retr<caret>ies, 5")

        assertContainsElements(usages(), "worker.ex:7 Application.get_all_env(:my_app)")
    }

    fun testUsagesIncludeANonLiteralKeyReadOfTheApp() {
        myFixture.caret(DEV, ":retr<caret>ies, 5")

        assertContainsElements(usages(), "worker.ex:9 key")
    }

    fun testUsagesIncludeALibraryRead() {
        myFixture.withSourceLibrary("library_app", File(testDataPath, "usages_library")) {
            myFixture.caret(CONFIG, "time<caret>out: 5")

            assertContainsElements(usages(), "library_app.ex:2 timeout")
        }
    }

    fun testUsagesInALocalScopeAreOnlyThoseInIt() {
        val worker = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("lib/my_app/worker.ex"))!!
        val definition = PsiTreeUtil.getParentOfType(worker.findElementAt(worker.text.indexOf("def retries")), Call::class.java)!!
        myFixture.caret(CONFIG, "retr<caret>ies: 3")

        assertEquals(
            listOf("worker.ex:4 retries"),
            labels(myFixture.singleTargetPsiUsagesAtCaretInScope(project, LocalSearchScope(definition))),
        )
    }

    fun testUsagesExcludeAnotherAppsRead() {
        myFixture.caret(CONFIG, "retr<caret>ies: 3")

        assertDoesntContain(usages(), "worker.ex:8 retries")
    }

    // the module a read calls

    fun testUsagesIncludeAReadThroughAnAliasOfApplication() {
        myFixture.caret(CONFIG, "alia<caret>sed: 1")

        assertContainsElements(usages(), "aliased.ex:4 aliased")
    }

    fun testUsagesExcludeApplicationAliasedToAnotherModule() {
        myFixture.caret(CONFIG, "aliased_aw<caret>ay: 1")

        assertEquals(listOf("config.exs:8 aliased_away") + EVERY_KEY, usages())
    }

    fun testUsagesIncludeAnElixirApplicationRead() {
        myFixture.caret(CONFIG, "elixir_appli<caret>cation: 1")

        assertContainsElements(usages(), "aliased.ex:5 elixir_application")
    }

    fun testUsagesIncludeAnErlangApplicationRead() {
        myFixture.caret(CONFIG, "erl<caret>ang: 1")

        assertContainsElements(usages(), "aliased.ex:6 erlang")
    }

    fun testUsagesExcludeAnApplicationAliasCallingGetEnv1() {
        myFixture.caret(CONFIG, "get_en<caret>v1: 1")

        assertEquals(listOf("config.exs:11 get_env1") + EVERY_KEY, usages())
    }

    fun testUsagesIncludeAnImportedApplicationRead() {
        myFixture.caret(CONFIG, "impor<caret>ted: 1")

        assertContainsElements(usages(), "imported.ex:4 imported")
    }

    fun testUsagesIncludeAnImportedCompileEnvRead() {
        myFixture.caret(CONFIG, "imported_compile_e<caret>nv: 1")

        assertContainsElements(usages(), "imported.ex:6 imported_compile_env")
    }

    fun testUsagesExcludeAnUnqualifiedGetEnvWithoutTheImport() {
        myFixture.caret(CONFIG, "loc<caret>al: 1")

        assertEquals(listOf("config.exs:14 local") + EVERY_KEY, usages())
    }

    fun testUsagesIncludeAReadThroughAnAttributeHoldingApplication() {
        myFixture.caret(CONFIG, "attri<caret>bute: 1")

        assertContainsElements(usages(), "attribute.ex:5 attribute")
    }

    fun testUsagesIncludeAReadThroughAParenthesisedAttribute() {
        myFixture.caret(CONFIG, "parenthesised_he<caret>ld: 1")

        assertContainsElements(usages(), "attribute.ex:6 parenthesised_held")
    }

    fun testUsagesIncludeAPipedKeyReadThroughAnAttribute() {
        myFixture.caret(CONFIG, "piped_he<caret>ld: 1")

        assertContainsElements(usages(), "attribute.ex:7 piped_held")
    }

    fun testUsagesExcludeAnAliasQualifiedReadWhereExpansionDidNotReach() {
        myFixture.caret(CONFIG, "unreached_ali<caret>as: 1")

        assertEquals(listOf("config.exs:16 unreached_alias") + EVERY_KEY, usages())
    }

    fun testUsagesIncludeAWrittenApplicationReadWhereExpansionDidNotReach() {
        myFixture.caret(CONFIG, "unreached_writ<caret>ten: 1")

        assertContainsElements(usages(), "unreached.ex:7 unreached_written")
    }

    fun testUsagesIncludeAnAbsoluteApplicationReadWhereExpansionDidNotReach() {
        myFixture.caret(CONFIG, "unreached_abso<caret>lute: 1")

        assertContainsElements(usages(), "unreached.ex:8 unreached_absolute", "unreached.ex:9 unreached_absolute")
    }

    fun testUsagesExcludeAHeadDefiningAnImportedReadsName() {
        myFixture.caret(CONFIG, "defined_he<caret>ad: 1")

        assertEquals(listOf("config.exs:21 defined_head") + EVERY_KEY, usages())
    }

    fun testUsagesExcludeAGuardedHeadDefiningAnImportedReadsName() {
        myFixture.caret(CONFIG, "guarded_he<caret>ad: 1")

        assertEquals(listOf("config.exs:22 guarded_head") + EVERY_KEY, usages())
    }

    fun testUsagesIncludeAHeexBraceRead() {
        myFixture.caret(TEMPLATES_CONFIG, "heex_br<caret>ace: true")

        assertEquals(
            listOf(
                "templates.ex:2 heex_brace",
                "test.exs:3 heex_brace",
                "worker.ex:7 Application.get_all_env(:my_app)",
                "worker.ex:9 key",
            ),
            templateUsages(),
        )
    }

    fun testUsagesIncludeAHeexTagRead() {
        myFixture.caret(TEMPLATES_CONFIG, "heex_t<caret>ag: true")

        assertContainsElements(templateUsages(), "templates.ex:3 heex_tag")
    }

    fun testUsagesIncludeAnESigilRead() {
        myFixture.caret(TEMPLATES_CONFIG, "e_sig<caret>il: true")

        assertContainsElements(templateUsages(), "templates.ex:4 e_sigil")
    }

    fun testUsagesIncludeAnLSigilRead() {
        myFixture.caret(TEMPLATES_CONFIG, "l_sig<caret>il: true")

        assertContainsElements(templateUsages(), "templates.ex:5 l_sigil")
    }

    fun testUsagesIncludeAHeredocTemplateRead() {
        myFixture.caret(TEMPLATES_CONFIG, "heredoc_temp<caret>late: true")

        assertContainsElements(templateUsages(), "templates.ex:10 heredoc_template")
    }

    fun testUsagesIncludeAReadInAnIfSpanningTemplateParts() {
        myFixture.caret(TEMPLATES_CONFIG, "ban<caret>ner: true")

        assertContainsElements(templateUsages(), "templates.ex:18 banner")
    }

    fun testUsagesIncludeAnAliasKeyReadInATemplate() {
        myFixture.caret(TEMPLATES_CONFIG, "MyApp.Mai<caret>ler")

        assertContainsElements(templateUsages(::configKeyUsages), "templates.ex:29 Mailer")
    }

    fun testUsagesIncludeAModuleKeyReadInATemplate() {
        myFixture.caret(TEMPLATES_CONFIG, "MyAppWeb.Templa<caret>tes")

        assertContainsElements(templateUsages(::configKeyUsages), "templates.ex:32 __MODULE__")
    }

    fun testUsagesExcludeAnAliasKeyReadAfterAnAliasInTheTemplate() {
        myFixture.caret(TEMPLATES_CONFIG, "Cryp<caret>to, enabled")

        assertDoesntContain(templateUsages(::configKeyUsages), "templates.ex:37 Crypto")
    }

    fun testUsagesIncludeATemplateReadThroughAnAliasOfApplication() {
        myFixture.caret(TEMPLATES_CONFIG, "template_alia<caret>sed: true")

        assertContainsElements(templateUsages(), "templates.ex:44 template_aliased")
    }

    fun testUsagesExcludeATemplateReadOfApplicationAliasedToAnotherModule() {
        myFixture.caret(TEMPLATES_CONFIG, "template_aliased_aw<caret>ay: true")

        assertEquals(listOf("test.exs:9 template_aliased_away") + EVERY_KEY, templateUsages())
    }

    fun testUsagesIncludeAnImportedApplicationReadInATemplate() {
        myFixture.caret(TEMPLATES_CONFIG, "template_impor<caret>ted: true")

        assertContainsElements(templateUsages(), "templates.ex:56 template_imported")
    }

    fun testUsagesExcludeAReadAfterTheTemplateAliasesApplicationAway() {
        myFixture.caret(TEMPLATES_CONFIG, "own_aliased_aw<caret>ay: true")

        assertEquals(listOf("test.exs:9 own_aliased_away") + EVERY_KEY, templateUsages())
    }

    fun testUsagesExcludeAReadAfterTheTemplateAliasesApplicationAwayInAnInjectedTemplate() {
        myFixture.caret(TEMPLATES_CONFIG, "own_aliased_aw<caret>ay: true")

        assertEquals(listOf("test.exs:9 own_aliased_away") + EVERY_KEY, myFixture.withSigilInjection(::usages))
    }

    fun testUsagesExcludeAParenthesisedReadAfterTheTemplateAliasesApplicationAway() {
        myFixture.caret(TEMPLATES_CONFIG, "own_parenthesised_aw<caret>ay: true")

        assertEquals(listOf("test.exs:9 own_parenthesised_away") + EVERY_KEY, templateUsages())
    }

    fun testUsagesExcludeAParenthesisedReadAfterTheTemplateAliasesApplicationAwayInAnInjectedTemplate() {
        myFixture.caret(TEMPLATES_CONFIG, "own_parenthesised_aw<caret>ay: true")

        assertEquals(listOf("test.exs:9 own_parenthesised_away") + EVERY_KEY, myFixture.withSigilInjection(::usages))
    }

    fun testUsagesExcludeAReadThroughTheTemplatesAliasOfApplication() {
        myFixture.caret(TEMPLATES_CONFIG, "own_alia<caret>sed: true")

        assertEquals(listOf("test.exs:9 own_aliased") + EVERY_KEY, templateUsages())
    }

    fun testUsagesExcludeAReadThroughTheTemplatesAliasOfApplicationInAnInjectedTemplate() {
        myFixture.caret(TEMPLATES_CONFIG, "own_alia<caret>sed: true")

        assertEquals(listOf("test.exs:9 own_aliased") + EVERY_KEY, myFixture.withSigilInjection(::usages))
    }

    fun testUsagesExcludeAReadThroughTheTemplatesImportOfApplication() {
        myFixture.caret(TEMPLATES_CONFIG, "own_impor<caret>ted: true")

        assertEquals(listOf("test.exs:9 own_imported") + EVERY_KEY, templateUsages())
    }

    fun testUsagesExcludeAReadThroughTheTemplatesImportOfApplicationInAnInjectedTemplate() {
        myFixture.caret(TEMPLATES_CONFIG, "own_impor<caret>ted: true")

        assertEquals(listOf("test.exs:9 own_imported") + EVERY_KEY, myFixture.withSigilInjection(::usages))
    }

    /** [usages], where sigils are not injection hosts, so a template's reads are found without injected PSI. */
    private fun templateUsages(usages: () -> List<String> = ::usages): List<String> {
        assertFalse(PsiLanguageInjectionHost.sigilsAreHosts())

        return usages()
    }

    /** Find Usages of the one search target at the caret. */
    private fun usages(): List<String> = labels(myFixture.singleTargetPsiUsagesAtCaret(project))

    /** Find Usages of the config key, where the chooser also offers the module the key names. */
    private fun configKeyUsages(): List<String> =
        labels(myFixture.chosenTargetPsiUsagesAtCaret(project) { targets -> targets.single { it is ConfigKeySymbol } })

    /** Each usage as `file:line text`, sorted. */
    private fun labels(usages: List<PsiUsage>): List<String> =
        usages.map { usage ->
            val text = usage.file.text
            val line = StringUtil.offsetToLineNumber(text, usage.range.startOffset) + 1

            "${usage.file.name}:$line ${usage.range.substring(text)}"
        }.sorted()

    private companion object {
        const val CONFIG = "config/config.exs"
        const val DEV = "config/dev.exs"
        const val TEMPLATES_CONFIG = "config/test.exs"

        /** The reads in `worker.ex` of every key of `:my_app`. */
        val EVERY_KEY = listOf("worker.ex:7 Application.get_all_env(:my_app)", "worker.ex:9 key")
    }
}
