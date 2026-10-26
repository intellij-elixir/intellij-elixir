@file:Suppress("UnstableApiUsage")

package org.elixir_lang.model.psi.config

import com.intellij.find.usages.api.PsiUsage
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.chosenTargetPsiUsagesAtCaret
import org.elixir_lang.code_insight.singleTargetPsiUsagesAtCaret
import org.elixir_lang.code_insight.singleTargetPsiUsagesAtCaretInScope
import org.elixir_lang.expander.LegSdk
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
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(testDataPath, "usages_library").absoluteFile)!!
        val library = WriteAction.computeAndWait<Library, Throwable> {
            LibraryTablesRegistrar.getInstance().getLibraryTable(project).createLibrary("library_app").also { library ->
                library.modifiableModel.apply {
                    addRoot(root, OrderRootType.SOURCES)
                    commit()
                }
            }
        }
        ModuleRootModificationUtil.addDependency(myFixture.module, library)

        try {
            myFixture.caret(CONFIG, "time<caret>out: 5")

            assertContainsElements(usages(), "library_app.ex:2 timeout")
        } finally {
            ModuleRootModificationUtil.removeDependency(myFixture.module, library)
            WriteAction.runAndWait<Throwable> { LibraryTablesRegistrar.getInstance().getLibraryTable(project).removeLibrary(library) }
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

        /** The reads in `worker.ex` of every key of `:my_app`. */
        val EVERY_KEY = listOf("worker.ex:7 Application.get_all_env(:my_app)", "worker.ex:9 key")
    }
}
