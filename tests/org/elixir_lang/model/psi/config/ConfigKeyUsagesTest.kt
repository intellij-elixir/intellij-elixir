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
    }
}
