package org.elixir_lang.declaration

import com.intellij.codeHighlighting.Pass
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import com.intellij.util.ui.UIUtil
import org.elixir_lang.expander.ConfigHeavyTestCase
import org.elixir_lang.expander.edit
import org.elixir_lang.model.psi.config.ConfigReads
import org.elixir_lang.model.psi.config.ConfigSettings
import org.elixir_lang.settings.ElixirExperimentalSettings
import java.io.File
import java.util.concurrent.Callable

/**
 * What changing the Experimental setting does in the open projects, through the topic that the Settings page publishes
 * on and that a settings import or reload does: each project drops what it holds from the old path and has its editors
 * analysed again. What fails without the listener is the dropped caches and the restart; the answers asked here are
 * behaviour pins.
 */
class ExpanderPathToggleHeavyTest : ConfigHeavyTestCase() {
    private var savedProperty: String? = null
    private lateinit var original: ElixirExperimentalSettings.State
    private var expanderBefore = false
    private var htmlInjectionBefore = false
    private var mixDepsCheckBefore = true

    override fun setUp() {
        original = ElixirExperimentalSettings.instance.state
        expanderBefore = original.enableExpander
        htmlInjectionBefore = original.enableHtmlInjection
        mixDepsCheckBefore = original.enableMixDepsCheck
        savedProperty = System.getProperty(SOURCE_PROPERTY)
        System.clearProperty(SOURCE_PROPERTY)
        super.setUp()
        original.enableExpander = false
    }

    /** The original state object goes back, not a copy: a helper that captured it restores into the object the application holds. */
    override fun tearDown() {
        try {
            original.enableExpander = expanderBefore
            original.enableHtmlInjection = htmlInjectionBefore
            if (ElixirExperimentalSettings.instance.state !== original) ElixirExperimentalSettings.instance.loadState(original)
            super.tearDown()
        } finally {
            savedProperty?.let { System.setProperty(SOURCE_PROPERTY, it) } ?: System.clearProperty(SOURCE_PROPERTY)
        }

        assertEquals(
            "the mix deps check setting after the class",
            mixDepsCheckBefore,
            ElixirExperimentalSettings.instance.state.enableMixDepsCheck,
        )
    }

    fun testAToggleMovesAnAliasKeyReadBothWays() {
        aliasKeyProject()

        assertEquals("off", 0, aliasKeySettings())

        for (through in listOf(Through.PAGE, Through.LOAD)) {
            val count = PsiModificationTracker.getInstance(project).modificationCount
            toggle(true, through)

            assertEquals("on, $through", 1, aliasKeySettings())
            assertTrue("dropped, $through", PsiModificationTracker.getInstance(project).modificationCount != count)

            toggle(false, through)

            assertEquals("off, $through", 0, aliasKeySettings())
        }
    }

    fun testALaterLoadStateMovesAnAliasKeyRead() {
        aliasKeyProject()
        val count = PsiModificationTracker.getInstance(project).modificationCount

        toggle(true, Through.LOAD)

        assertEquals(1, aliasKeySettings())
        assertTrue(PsiModificationTracker.getInstance(project).modificationCount != count)
    }

    fun testAToggleReachesASecondOpenProject() {
        aliasKeyProject()
        val second = secondProject()
        val counts = listOf(project, second).map { PsiModificationTracker.getInstance(it).modificationCount }

        toggle(true, Through.PAGE)

        assertEquals(1, aliasKeySettings())
        assertEquals(
            listOf(true, true),
            listOf(project, second).zip(counts).map { (open, before) -> PsiModificationTracker.getInstance(open).modificationCount != before },
        )
    }

    fun testAToggleMovesTheDaemon() {
        val root = aliasKeyProject()
        val file = root.findFileByRelativePath("lib/reader.ex")!!
        val editor = FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true)!!
        val psi = ReadAction.computeBlocking<PsiFile, Throwable> { PsiManager.getInstance(project).findFile(file)!! }

        try {
            CodeInsightTestFixtureImpl.instantiateAndRun(psi, editor, intArrayOf(), false)
            assertNull("analysed", dirtyScope(editor.document, psi))

            toggle(true, Through.PAGE)

            assertNotNull("analysed again", dirtyScope(editor.document, psi))
        } finally {
            FileEditorManager.getInstance(project).closeFile(file)
        }
    }

    fun testAChangeToAnotherSettingDropsNothing() {
        aliasKeyProject()
        val settings = ElixirExperimentalSettings.instance
        val count = PsiModificationTracker.getInstance(project).modificationCount
        val old = settings.state.copy()

        settings.state.enableHtmlInjection = !settings.state.enableHtmlInjection
        publish(old, settings.state.copy())
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(count, PsiModificationTracker.getInstance(project).modificationCount)
    }

    // Edits made while the setting is off

    fun testAReadEditWhileOffIsSeenOn() {
        val root = aliasKeyProject(reader = "defmodule Reader do\n  def read, do: Application.get_env(:app, Other.Repo)\nend\n")
        toggle(true, Through.PAGE)

        assertEquals("before", 0, reads())

        toggle(false, Through.PAGE)
        edit(project, psi(root, "lib/reader.ex"), "defmodule Reader do\n  def read, do: {Application.get_env(:app, Other.Repo), Application.get_env(:app, App.Repo)}\nend\n")
        toggle(true, Through.PAGE)

        assertEquals("after", 1, reads())
    }

    fun testAnSdkChangeWhileOffIsSeenOn() {
        // Held, since a file's PSI that nothing holds is let go when the roots change, and its caches with it.
        val config = psi(aliasKeyProject(), CONFIG)
        toggle(true, Through.PAGE)

        assertEquals("before", 1, aliasKeySettings())

        toggle(false, Through.PAGE)
        ModuleRootModificationUtil.setModuleSdk(module, null)
        toggle(true, Through.PAGE)

        assertEquals("after", 0, aliasKeySettings())
        assertTrue(config.isValid)
    }

    fun testADepCopyAddedWhileOffIsSeenOn() {
        val root = project(
            "mix.exs" to MIX_EXS,
            CONFIG to "import Config\n\nuse Dep.Helper\nconfig :app, C, a: 1\n",
        )
        toggle(true, Through.PAGE)

        assertEquals("before", 0, settings("Elixir.Dep.Target"))

        toggle(false, Through.PAGE)
        addFiles(
            root,
            "deps/dep/mix.exs" to "defmodule Dep.MixProject do\n  use Mix.Project\n\n  def project, do: [app: :dep, version: \"0.1.0\"]\nend\n",
            "deps/dep/lib/helper.ex" to
                "defmodule Dep.Helper do\n  defmacro __using__(_) do\n    quote do\n      alias Dep.Target, as: C\n    end\n  end\nend\n",
        )
        ModuleRootModificationUtil.addModuleLibrary(module, "dep", listOf(root.findFileByRelativePath("deps/dep/lib")!!.url), emptyList())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        toggle(true, Through.PAGE)

        assertEquals("after", 1, settings("Elixir.Dep.Target"))
    }

    // Fixtures

    private enum class Through { PAGE, LOAD }

    /** A project whose config sets `App.Repo` as an alias key and whose reader reads it. */
    private fun aliasKeyProject(reader: String = READER): VirtualFile =
        project(
            "mix.exs" to MIX_EXS,
            CONFIG to "import Config\n\nconfig :app, App.Repo, a: 1\n",
            "lib/reader.ex" to reader,
        )

    private fun settings(key: String): Int =
        ReadAction.nonBlocking(Callable { ConfigSettings.of(project, "app", key).size }).executeSynchronously()

    private fun aliasKeySettings(): Int = settings("Elixir.App.Repo")

    private fun reads(): Int =
        ReadAction.nonBlocking(Callable {
            ConfigReads.of(project, "app", "Elixir.App.Repo", GlobalSearchScope.allScope(project)).count()
        }).executeSynchronously()

    /** The setting at [on], published as the Settings page or a settings load does, then the EDT's work run. */
    private fun toggle(on: Boolean, through: Through) {
        val settings = ElixirExperimentalSettings.instance

        when (through) {
            Through.PAGE -> {
                val old = settings.state.copy()
                settings.state.enableExpander = on
                publish(old, settings.state.copy())
            }
            Through.LOAD -> settings.loadState(settings.state.copy(enableExpander = on))
        }

        UIUtil.dispatchAllInvocationEvents()
    }

    private fun publish(old: ElixirExperimentalSettings.State, new: ElixirExperimentalSettings.State) {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(ElixirExperimentalSettings.SETTINGS_CHANGED_TOPIC)
            .settingsChanged(old, new)
    }

    private fun dirtyScope(document: Document, file: PsiFile) =
        (DaemonCodeAnalyzer.getInstance(project) as DaemonCodeAnalyzerImpl).fileStatusMap.getFileDirtyScope(document, file, Pass.UPDATE_ALL)

    private fun addFiles(root: VirtualFile, vararg sources: Pair<String, String>) {
        sources.forEach { (path, text) -> FileUtil.writeToFile(File(root.path, path), text) }
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun secondProject(): Project {
        val directory = FileUtil.createTempDirectory("second", null).toPath()
        File(directory.toFile(), "mix.exs").writeText(MIX_EXS)
        val second = PlatformTestUtil.loadAndOpenProject(directory, testRootDisposable)
        assertTrue(ProjectManager.getInstance().openProjects.contains(second))

        return second
    }

    private companion object {
        const val READER = "defmodule Reader do\n  def read, do: Application.get_env(:app, App.Repo)\nend\n"
    }
}
