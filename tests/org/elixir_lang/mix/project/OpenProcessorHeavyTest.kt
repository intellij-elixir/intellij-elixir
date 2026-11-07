package org.elixir_lang.mix.project

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.checkout.CheckoutListener
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.projectImport.ProjectImportBuilder
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.closeProjectAsync
import com.intellij.testFramework.createTestOpenProjectOptions
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.testFramework.runInEdtAndWait
import kotlinx.coroutines.runBlocking
import org.elixir_lang.jps.shared.ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.mix.project._import.Builder
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.fakeHome
import org.elixir_lang.sdk.choice.RecordedChooser
import org.elixir_lang.sdk.choice.SdkChoiceEntry
import org.elixir_lang.sdk.choice.SdkChoiceState
import org.elixir_lang.sdk.elixir.ElixirSdkLookup
import org.elixir_lang.sdk.elixir.sdk
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.io.path.writeText

/**
 * A Mix project with no `.idea`, opened as File > Open on a folder and as Get from VCS opens a checkout: the open attaches
 * no JDK and no earlier import's pick, and once the project is open the chooser asks for its modules' Elixir SDK.
 */
class OpenProcessorHeavyTest : HeavyTestCase() {
    /** The open dispatches to `Dispatchers.EDT`, which a [runBlocking] on the EDT would never reach. */
    override fun runInDispatchThread(): Boolean = false

    private lateinit var jdk: Sdk
    private lateinit var elixir: Sdk
    private lateinit var recorded: RecordedChooser
    private var answer: (SdkChoiceState) -> SdkChoiceEntry? = { null }

    override fun setUp() {
        super.setUp()
        runInEdtAndWait {
            jdk = SdkFixtures.register(ProjectJdkImpl("Open JDK", JavaSdk.getInstance()), testRootDisposable)
            elixir = SdkFixtures.register(SdkFixtures.elixirSdk("Open Elixir", fakeHome("elixir/open")), testRootDisposable)
        }
        recorded = RecordedChooser(testRootDisposable) { state -> answer(state) }
    }

    fun testAnEarlierImportPickIsNotGivenToTheOpenedModule() {
        val builder = ProjectImportBuilder.EXTENSIONS_POINT_NAME.findExtensionOrFail(Builder::class.java)
        // As the import wizard's SDK step leaves it when the wizard is cancelled.
        builder.elixirSdk = elixir
        try {
            opened(::fileOpen) { project ->
                val module = module(project)
                assertEquals("the opened module's Elixir SDK", null, read { ElixirSdkLookup.resolve(module).sdk?.name })
            }
        } finally {
            builder.elixirSdk = null
        }
    }

    fun testANewestJdkIsNotMadeTheProjectSdk() = opened(::fileOpen) { project ->
        assertNoProjectSdk(project)
    }

    fun testGetFromVcsWithoutIdeaAttachesNoJdk() = opened(::getFromVcs) { project ->
        assertNoProjectSdk(project)
    }

    fun testTheChooserIsAskedForTheOpenedModule() = opened(::fileOpen) { project ->
        recorded.assertChooserAskedFor(module(project))
    }

    fun testThePickIsTheOpenedModulesSdk() {
        answer = { state -> state.entries.single { it.sdk.name == elixir.name } }
        opened(::fileOpen) { project ->
            val module = module(project)
            assertSame(elixir, ModuleRootManager.getInstance(module).sdk)
            assertFalse("the module inherits the project SDK", ModuleRootManager.getInstance(module).isSdkInherited)
            assertNoProjectSdk(project)
        }
    }

    fun testGetFromVcsWithoutIdeaAsksForTheModule() = opened(::getFromVcs) { project ->
        recorded.assertChooserAskedFor(module(project))
    }

    fun testCancelLeavesTheModuleWithoutAnSdk() = opened(::fileOpen) { project ->
        val module = module(project)
        recorded.assertChooserAskedFor(module)
        assertEquals("the opened module's Elixir SDK", null, read { ElixirSdkLookup.resolve(module).sdk?.name })
        assertNoProjectSdk(project)
    }

    fun testAModuleWithAnElixirSdkIsNotAsked() {
        val (a, b) = runInEdtAndGet { elixirModule("a") to elixirModule("b") }
        ModuleRootModificationUtil.setModuleSdk(b, elixir)

        askAfterOpen(project)
        settle(project)

        recorded.assertChooserAskedFor(a)
    }

    /** A Mix project with no `.idea` opened by [open], given to [body], then closed. */
    private fun opened(open: (Path) -> Project, body: (Project) -> Unit) {
        assertContainsElements(ProjectJdkTable.getInstance().allJdks.toList(), jdk, elixir)
        val dir = createTempDir("mix_open").toPath()
        dir.resolve("mix.exs").writeText(MIX_EXS)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir) ?: error("$dir not found in VFS after refresh")

        val project = open(dir)
        try {
            settle(project)
            body(project)
        } finally {
            runBlocking { project.closeProjectAsync() }
        }
    }

    /** As File > Open on a folder ends. */
    private fun fileOpen(dir: Path): Project = runBlocking {
        ProjectUtil.openOrImportAsync(dir, createTestOpenProjectOptions().copy(projectRootDir = dir))
    } ?: error("File > Open on $dir opened no project")

    /** As Get from VCS hands a checkout to the checkout listeners, under the indicator of its background task. */
    private fun getFromVcs(dir: Path): Project {
        ProgressManager.getInstance().runProcess({
            CheckoutListener.EP_NAME.extensionList.firstOrNull { it.processCheckedOutDirectory(project, dir) }
                ?: error("no checkout listener opened $dir")
        }, EmptyProgressIndicator())
        return ProjectManager.getInstance().openProjects.firstOrNull { FileUtil.pathsEqual(it.basePath, dir.toString()) }
            ?: error("Get from VCS on $dir opened no project")
    }

    /** Indexing done and the EDT drained, so what the open posted for the smart project has run. */
    private fun settle(project: Project) {
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        DumbService.getInstance(project).waitForSmartMode()
        runInEdtAndWait { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
    }

    private fun module(project: Project): Module = ModuleManager.getInstance(project).modules.single()

    private fun assertNoProjectSdk(project: Project) =
        assertEquals("the project SDK", null, ProjectRootManager.getInstance(project).projectSdk?.name)

    private fun elixirModule(name: String): Module =
        doCreateRealModuleIn(name, project, ModuleTypeManager.getInstance().findByID(ELIXIR_MODULE_TYPE_ID))

    private fun <T> read(compute: () -> T): T = ReadAction.nonBlocking(Callable(compute)).executeSynchronously()

    private companion object {
        private val MIX_EXS = """
            defmodule TestApp.MixProject do
              use Mix.Project

              def project do
                [app: :test_app, version: "0.1.0"]
              end
            end
        """.trimIndent()
    }
}
