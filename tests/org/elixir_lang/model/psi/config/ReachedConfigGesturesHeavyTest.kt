package org.elixir_lang.model.psi.config

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.find.usages.api.PsiUsage
import com.intellij.openapi.module.Module
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.TestApplicationManager
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.util.ThrowableRunnable
import org.elixir_lang.code_insight.GtduNavigation
import org.elixir_lang.code_insight.gtduNavigationAtCaret
import org.elixir_lang.code_insight.singleTargetPsiUsagesAtCaret
import org.elixir_lang.declaration.SOURCE_PROPERTY
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.expander.LegSdk
import org.elixir_lang.expander.ProjectModules
import org.elixir_lang.expander.SdkInputs
import org.elixir_lang.inspection.UnreadConfigKey
import org.elixir_lang.junit.logs.UnexpectedLogs
import org.elixir_lang.settings.ElixirExperimentalSettings

/**
 * Ctrl-Click, Find Usages, completion and the unread key inspection over a setting in a subfolder that
 * `import_config` reaches. On disk, like [ReachedConfigFilesHeavyTest], in a `CodeInsightTestFixture` whose files are
 * real ones.
 */
class ReachedConfigGesturesHeavyTest : UsefulTestCase() {
    private lateinit var myFixture: CodeInsightTestFixture
    private lateinit var module: Module
    private var savedProperty: String? = null

    @Throws(Throwable::class)
    override fun runBare(testRunnable: ThrowableRunnable<Throwable>) {
        UnexpectedLogs.failOnUnexpectedLogs {
            TestApplicationManager.getInstance()
            super.runBare(testRunnable)
        }
    }

    override fun setUp() {
        savedProperty = System.getProperty(SOURCE_PROPERTY)
        System.clearProperty(SOURCE_PROPERTY)
        super.setUp()

        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val builder = factory.createFixtureBuilder("${javaClass.name}.$name")
        myFixture = factory.createCodeInsightFixture(builder.fixture)
        val moduleBuilder = builder.addModule(EmptyModuleFixtureBuilder::class.java)
        moduleBuilder.addSourceContentRoot(myFixture.tempDirPath)
        myFixture.setUp()
        module = moduleBuilder.fixture.module
        // The fixture's own disposable, which goes before the fixture's check for leaked SDKs.
        disableMixDepsCheck(myFixture.testRootDisposable)
        LegSdk.setUp(module, myFixture.testRootDisposable)
        enableExpander(myFixture.testRootDisposable)
    }

    override fun tearDown() {
        try {
            LegSdk.tearDown(module)
            myFixture.tearDown()
        } finally {
            savedProperty?.let { System.setProperty(SOURCE_PROPERTY, it) } ?: System.clearProperty(SOURCE_PROPERTY)
            super.tearDown()
        }
    }

    fun testReadGoesToASettingInAnImportedSubfolderFile() {
        project()
        myFixture.caret(READER, "get_env(:app, :pool_<caret>size)")

        assertEquals(listOf("config/prod/database.exs:3"), destinations())
    }

    fun testReadDoesNotGoToAFileNothingImports() {
        project()
        myFixture.caret(READER, "get_env(:app, :orph<caret>an)")

        assertEquals(emptyList<String>(), destinations())
    }

    fun testFindUsagesListsASettingInAnImportedSubfolderFile() {
        project()
        myFixture.caret(READER, "get_env(:app, :pool_<caret>size)")

        assertEquals(listOf("database.exs:3 pool_size", "reader.ex:2 pool_size"), labels(myFixture.singleTargetPsiUsagesAtCaret(this.project)))
    }

    fun testCompletionOffersAKeySetInAnImportedSubfolderFile() {
        project()
        val typing = myFixture.addFileToProject("lib/typing.ex", "defmodule Typing do\n  def f, do: Application.get_env(:app, :o)\nend\n")
        myFixture.configureFromExistingVirtualFile(typing.virtualFile)
        myFixture.editor.caretModel.moveToOffset(typing.text.indexOf(":o)") + 2)

        val items = myFixture.completeBasic()

        assertContainsElements(items?.map { it.lookupString }.orEmpty(), "only_set")
        myFixture.lookup.currentItem = items!!.first { it.lookupString == "only_set" }
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        assertEquals("defmodule Typing do\n  def f, do: Application.get_env(:app, :only_set)\nend\n", myFixture.editor.document.text)
    }

    fun testInspectionReportsAnUnreadKeyInAnImportedSubfolderFile() {
        project()

        assertContainsElements(unreadIn(DATABASE), "only_set")
    }

    fun testInspectionClearsAReadKeyInAnImportedSubfolderFile() {
        project()

        assertDoesntContain(unreadIn(DATABASE), "pool_size")
    }

    fun testFlagOffStartsNoExpanderService() {
        ElixirExperimentalSettings.instance.state.enableExpander = false
        project()

        myFixture.caret(READER, "get_env(:app, :pool_<caret>size)")
        myFixture.gtduNavigationAtCaret()
        val typing = myFixture.addFileToProject("lib/typing.ex", "defmodule Typing do\n  def f, do: Application.get_env(:app, :o)\nend\n")
        myFixture.configureFromExistingVirtualFile(typing.virtualFile)
        myFixture.editor.caretModel.moveToOffset(typing.text.indexOf(":o)") + 2)
        myFixture.completeBasic()
        unreadIn(DATABASE)

        assertNull(this.project.getServiceIfCreated(ProjectModules::class.java))
        assertNull(this.project.getServiceIfCreated(SdkInputs::class.java))
    }

    /** `prod.exs` alone imports `prod/database.exs`; nothing imports `staging/unimported.exs`, which sets a key a read names. */
    private fun project() {
        for ((path, text) in listOf(
            "mix.exs" to "defmodule App.MixProject do\n  use Mix.Project\n\n  def project, do: [app: :app, version: \"0.1.0\"]\nend\n",
            "config/config.exs" to "import Config\n\nimport_config \"#{config_env()}.exs\"\n",
            "config/dev.exs" to "import Config\n",
            "config/test.exs" to "import Config\n",
            "config/prod.exs" to "import Config\n\nimport_config \"prod/database.exs\"\n",
            DATABASE to "import Config\n\nconfig :app, :pool_size, 10\nconfig :app, :only_set, true\n",
            "config/staging/unimported.exs" to "import Config\n\nconfig :app, :orphan, 1\n",
            READER to
                "defmodule Reader do\n  def pool, do: Application.get_env(:app, :pool_size)\n  def orphan, do: Application.get_env(:app, :orphan)\nend\n",
        )) {
            myFixture.addFileToProject(path, text)
        }
        IndexingTestUtil.waitUntilIndexesAreReady(this.project)
    }

    private val project get() = myFixture.project

    private fun destinations(): List<String> =
        when (val navigation = myFixture.gtduNavigationAtCaret()) {
            is GtduNavigation.GotoDeclaration -> navigation.targets.map { target ->
                val destination = target.destination!!
                val file = destination.containingFile
                val line = StringUtil.offsetToLineNumber(file.text, destination.textRange.startOffset) + 1

                "${FileUtil.getRelativePath(myFixture.tempDirPath, file.virtualFile.path, '/')}:$line"
            }.sorted()
            GtduNavigation.None, is GtduNavigation.ShowUsages -> emptyList()
        }

    /** Each usage as `file:line text`, sorted. */
    private fun labels(usages: List<PsiUsage>): List<String> =
        usages.map { usage ->
            val text = usage.file.text
            val line = StringUtil.offsetToLineNumber(text, usage.range.startOffset) + 1

            "${usage.file.name}:$line ${usage.range.substring(text)}"
        }.sorted()

    /** What the unread key inspection highlights in [path]. */
    private fun unreadIn(path: String): List<String> {
        myFixture.enableInspections(UnreadConfigKey::class.java)
        myFixture.configureFromTempProjectFile(path)

        return myFixture.doHighlighting().filter { it.inspectionToolId == "UnreadConfigKey" }.map { it.text }.sorted()
    }

    private companion object {
        const val READER = "lib/reader.ex"
        const val DATABASE = "config/prod/database.exs"
    }
}
