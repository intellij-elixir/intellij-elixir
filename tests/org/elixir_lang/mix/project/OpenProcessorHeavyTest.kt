package org.elixir_lang.mix.project

import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.projectImport.ProjectImportBuilder
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.closeProjectAsync
import com.intellij.testFramework.createTestOpenProjectOptions
import com.intellij.testFramework.runInEdtAndWait
import kotlinx.coroutines.runBlocking
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.mix.project._import.Builder
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.fakeHome
import org.elixir_lang.sdk.elixir.ElixirSdkLookup
import org.elixir_lang.sdk.elixir.sdk
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.io.path.writeText

/**
 * A Mix project with no `.idea`, opened as File > Open on a folder: the open gives its module no earlier import's pick.
 */
class OpenProcessorHeavyTest : HeavyTestCase() {
    /** The open dispatches to `Dispatchers.EDT`, which a [runBlocking] on the EDT would never reach. */
    override fun runInDispatchThread(): Boolean = false

    private lateinit var jdk: Sdk
    private lateinit var elixir: Sdk

    override fun setUp() {
        super.setUp()
        runInEdtAndWait {
            jdk = SdkFixtures.register(ProjectJdkImpl("Open JDK", JavaSdk.getInstance()), testRootDisposable)
            elixir = SdkFixtures.register(SdkFixtures.elixirSdk("Open Elixir", fakeHome("elixir/open")), testRootDisposable)
        }
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

    /** Indexing done and the EDT drained, so what the open posted for the smart project has run. */
    private fun settle(project: Project) {
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        DumbService.getInstance(project).waitForSmartMode()
        runInEdtAndWait { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
    }

    private fun module(project: Project): Module = ModuleManager.getInstance(project).modules.single()

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
