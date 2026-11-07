package org.elixir_lang.mix.project

import com.intellij.facet.FacetManager
import com.intellij.openapi.application.ReadAction
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.PlatformProjectOpenProcessor.Companion.runDirectoryProjectConfigurators
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.runBlocking
import org.elixir_lang.Facet
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.junit.logs.GuardedLoggedErrorProcessor
import java.io.File
import java.util.concurrent.Callable

/**
 * Drives [DirectoryConfigurator] the way a small IDE does - through the platform's own
 * [runDirectoryProjectConfigurators] - so the test asserts the outcome a user sees and does not
 * depend on which branch of the dispatch loop the configurator takes.
 *
 * Needs [HeavyPlatformTestCase]: [runDirectoryProjectConfigurators] resolves its argument with
 * `refreshAndFindFileByNioFile`, and the facet lands on `ModuleManager.modules[0]`.
 */
class DirectoryConfiguratorHeavyTest : HeavyTestCase() {
    /** [runDirectoryProjectConfigurators] dispatches to `Dispatchers.EDT`, which a [runBlocking] on the EDT would never reach. */
    override fun runInDispatchThread(): Boolean = false

    fun testConfiguresTheRootModule() {
        val appDir = createTempDir("mix_app")
        FileUtil.writeToFile(File(appDir, "mix.exs"), MIX_EXS)
        val appVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(appDir)
            ?: error("$appDir not found in VFS after refresh")

        configureDirectory(appDir)

        val module = ModuleManager.getInstance(project).modules.first()

        assertNotNull(
            "Opening a directory holding a mix.exs must leave the module with an Elixir facet - " +
                    "without one Module.isElixirModule() is false in an IDE that has no ELIXIR_MODULE type",
            FacetManager.getInstance(module).findFacet(Facet.ID, "Elixir")
        )

        // The facet commits separately from the roots, so asserting only the facet would pass on a
        // half-configured module.
        val contentEntries = ReadAction.nonBlocking(Callable { ModuleRootManager.getInstance(module).contentEntries })
            .executeSynchronously()
        val contentEntry = contentEntries.singleOrNull { it.file == appVirtualFile }

        assertNotNull(
            "the configured directory must be a content root, got: ${contentEntries.map { it.url }}",
            contentEntry
        )

        val sourceUrls = contentEntry!!.sourceFolders.associate { it.url to it.isTestSource }

        assertTrue(
            "lib should be Sources, got: ${sourceUrls.keys}",
            sourceUrls["${appVirtualFile.url}/lib"] == false
        )
        assertTrue(
            "test should be Test Sources, got: ${sourceUrls.keys}",
            sourceUrls["${appVirtualFile.url}/test"] == true
        )
        assertContainsElements(
            contentEntry.excludeFolderUrls,
            "${appVirtualFile.url}/deps",
            "${appVirtualFile.url}/cover"
        )
    }

    fun testNotifiesInsteadOfConfiguringAnUmbrella() {
        val umbrellaDir = createTempDir("mix_umbrella")
        FileUtil.writeToFile(File(umbrellaDir, "mix.exs"), MIX_EXS)
        FileUtil.writeToFile(File(umbrellaDir, "apps/first/mix.exs"), MIX_EXS)
        FileUtil.writeToFile(File(umbrellaDir, "apps/second/mix.exs"), MIX_EXS)
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(umbrellaDir)
            ?: error("$umbrellaDir not found in VFS after refresh")

        val titles = mutableListOf<String>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    titles.add(notification.title)
                }
            }
        )

        configureDirectory(umbrellaDir)

        assertContainsElements(titles, "Umbrella App detected")

        val module = ModuleManager.getInstance(project).modules.first()

        assertNull(
            "An umbrella is left for the Project Wizard, so no facet is added to the root module",
            FacetManager.getInstance(module).findFacet(Facet.ID, "Elixir")
        )
    }

    /**
     * `runDirectoryProjectConfigurators` catches every [Throwable] a configurator raises and only
     * logs it, so the errors are collected and asserted on rather than left to reach the test
     * thread - they never would.
     *
     * Without `intellij.progress.task.ignoreHeadless`, `CoreProgressManager.run(Task)` takes its
     * `isSynchronousHeadless` branch and runs a `Task.Backgroundable` to completion on the calling
     * thread - the one arrangement under which reading its result on the next line works.
     */
    private fun configureDirectory(dir: File) {
        val logged = mutableListOf<String>()
        val processor = object : GuardedLoggedErrorProcessor() {
            override fun processError(
                category: String,
                message: String,
                details: Array<out String>,
                t: Throwable?
            ): Set<Action> {
                logged.add("$category: $message")

                return Action.NONE
            }
        }

        LoggedErrorProcessor.executeWith<Throwable>(processor) {
            PlatformTestUtil.withSystemProperty<Throwable>("intellij.progress.task.ignoreHeadless", "true") {
                runBlocking {
                    runDirectoryProjectConfigurators(
                        projectFile = dir.toPath(),
                        project = project,
                        newProject = false,
                        createModule = false
                    )
                }
            }
        }

        assertEmpty("configuring $dir logged errors", logged)
    }
}
