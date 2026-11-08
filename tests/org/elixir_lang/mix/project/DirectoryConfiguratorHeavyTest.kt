package org.elixir_lang.mix.project

import com.intellij.facet.FacetManager
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.PlatformProjectOpenProcessor.Companion.runDirectoryProjectConfigurators
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.testFramework.runInEdtAndWait
import kotlinx.coroutines.runBlocking
import org.elixir_lang.Facet
import org.elixir_lang.facet.FacetModelFixtures.Companion.storedName
import org.elixir_lang.facet.SmallIdeFacetHeavyTestCase
import org.elixir_lang.jps.shared.ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID
import org.elixir_lang.junit.logs.GuardedLoggedErrorProcessor
import org.elixir_lang.sdk.ProcessOutput
import org.elixir_lang.sdk.SdkFixtures.fakeHome
import org.elixir_lang.sdk.choice.RecordedChooser
import org.elixir_lang.sdk.choice.SdkChoiceEntry
import org.elixir_lang.sdk.choice.SdkChoiceState
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Callable

/**
 * Drives [DirectoryConfigurator] the way a small IDE's open does - through the platform's own
 * [runDirectoryProjectConfigurators] - so the test asserts the outcome a user sees and does not
 * depend on which branch of the dispatch loop the configurator takes.
 */
class DirectoryConfiguratorHeavyTest : SmallIdeFacetHeavyTestCase() {
    /** [runDirectoryProjectConfigurators] dispatches to `Dispatchers.EDT`, which a [runBlocking] on the EDT would never reach. */
    override fun runInDispatchThread(): Boolean = false

    private lateinit var sdk: Sdk
    private lateinit var recorded: RecordedChooser
    private var answer: (SdkChoiceState) -> SdkChoiceEntry? = { null }

    override fun setUp() {
        super.setUp()
        sdk = runInEdtAndGet { models.register("Configured Elixir", home = fakeHome("elixir/configured")) }
        recorded = RecordedChooser(testRootDisposable) { state -> answer(state) }
    }

    fun testConfiguresTheRootModule() {
        val appDir = createTempDir("mix_app")
        stage(appDir, "mix.exs" to "test_app")
        val appVirtualFile = getVirtualFile(appDir)

        configureDirectory(appDir)

        assertNotNull(
            "Opening a directory holding a mix.exs must leave the module with an Elixir facet - " +
                    "without one Module.isElixirModule() is false in an IDE that has no ELIXIR_MODULE type",
            elixirFacet(module)
        )

        // The facet commits separately from the roots, so asserting only the facet would pass on a
        // half-configured module.
        val contentEntries = read { ModuleRootManager.getInstance(module).contentEntries }
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

    fun testEachUmbrellaAppGetsItsModuleWithTheElixirFacet() {
        val umbrellaDir = umbrella()
        val titles = notificationTitles()

        configureDirectory(umbrellaDir)

        for (app in listOf("alpha", "beta")) {
            val appDir = File(umbrellaDir, "apps/$app")
            val appModule = moduleNamed(app)
            assertEquals("$app's module file", File(appDir, "$app.iml").toPath(), Path.of(appModule.moduleFilePath))
            assertEquals("$app's module type", ModuleTypeManager.getInstance().defaultModuleType.id, ModuleType.get(appModule).id)
            assertMixFoldersAreMarkedInItsOnlyContentRoot(appModule, getVirtualFile(appDir))
            assertFalse("$app's folder holds a .idea", File(appDir, ".idea").exists())
        }
        for (each in umbrellaModules()) {
            assertNotNull("${each.name} has the Elixir facet", elixirFacet(each))
        }
        assertEmpty("notifications posted", titles)
    }

    fun testASingleAppBelowTheFolderGetsItsModule() {
        val dir = createTempDir("mix_nested")
        stage(dir, "backend/mix.exs" to "backend")

        configureDirectory(dir)

        val backend = moduleNamed("backend")
        assertEquals("backend's module file", File(dir, "backend/backend.iml").toPath(), Path.of(backend.moduleFilePath))
        assertNotNull("backend has the Elixir facet", elixirFacet(backend))
        assertFalse("backend's folder holds a .idea", File(dir, "backend/.idea").exists())
    }

    fun testAProjectsFetchedDependenciesBelowTheFolderGetNoModule() {
        val dir = createTempDir("mix_nested_deps")
        stage(dir, "backend/mix.exs" to "backend")
        FileUtil.writeToFile(File(dir, "backend/deps/jason/mix.exs"), mixExs("jason"))

        configureDirectory(dir)
        PlatformTestUtil.saveProject(project, true)

        assertEquals(setOf(module, moduleNamed("backend")), modules())
        assertEmpty("module files under backend/deps", filesUnder(File(dir, "backend/deps")).filter { it.endsWith(".iml") })
    }

    fun testOnIntelliJIdeaEachAppBelowTheFolderGetsAnElixirModuleWithoutTheFacet() {
        ProcessOutput.isSmallIdeOverride = false

        configureDirectory(appsBelow())

        for (app in appsBelowModules()) {
            assertEquals("${app.name}'s module type", ELIXIR_MODULE_TYPE_ID, ModuleType.get(app).id)
            assertNull("${app.name}'s Elixir facet", elixirFacet(app))
        }
    }

    fun testAnAppsModuleAlreadyInTheProjectIsReused() {
        val dir = createTempDir("mix_attached")
        stage(dir, "mix.exs" to "hub", "apps/alpha/mix.exs" to "alpha")
        val alphaIdea = File(dir, "apps/alpha/.idea")
        val alphaFile = File(alphaIdea, "alpha.iml").toPath()
        val alpha = createModuleAt("alpha", project, ModuleTypeManager.getInstance().defaultModuleType, alphaIdea.toPath())
        val alphaRoot = getVirtualFile(File(dir, "apps/alpha"))
        runInEdtAndWait { ModuleRootModificationUtil.addContentRoot(alpha, alphaRoot) }

        configureDirectory(dir)

        assertEquals(setOf(module, alpha), modules())
        assertEquals("alpha's module file", alphaFile, Path.of(alpha.moduleFilePath))
        assertNotNull("alpha has the Elixir facet", elixirFacet(alpha))
        assertMixFoldersAreMarkedInItsOnlyContentRoot(alpha, alphaRoot)
    }

    fun testAnAppNamedLikeTheFirstModuleGetsItsPathName() {
        val name = module.name
        val dir = createTempDir("mix_same_name")
        stage(dir, "apps/$name/mix.exs" to name)
        fun roots() = read { ModuleRootManager.getInstance(module).contentRootUrls.toList() }
        val roots = roots()

        configureDirectory(dir)

        assertEquals(setOf(name, "$name-apps-$name"), modules().map(Module::getName).toSet())
        assertEquals("${module.name}'s content roots", roots, roots())
    }

    fun testAnAttachsThrowawayRunWritesNothingUnderAnAppFolder() {
        val umbrellaDir = umbrella()
        val staged = filesUnder(File(umbrellaDir, "apps"))

        configureDirectory(umbrellaDir, newProject = true)
        PlatformTestUtil.saveProject(project, true)

        assertEquals(setOf(module), modules())
        assertEquals(staged, filesUnder(File(umbrellaDir, "apps")))
    }

    fun testTheOpenAsksOnceForEveryModule() {
        configureDirectory(umbrella())
        recorded.awaitAsk()

        assertEquals(umbrellaModules(), recorded.chooserAsked.single().toSet())
    }

    fun testThePickIsNamedInEachFacet() {
        answer = { state -> state.entries.single { it.sdk.name == sdk.name } }

        configureDirectory(umbrella())
        recorded.awaitAsk()

        for (each in umbrellaModules()) {
            assertEquals("${each.name}'s facet SDK", sdk.name, storedName(each))
        }
    }

    fun testAnAttachsThrowawayRunDoesNotAsk() {
        configureDirectory(umbrella(), newProject = true)

        assertEmpty("the chooser was asked", recorded.chooserAsked)
    }

    fun testOnIntelliJIdeaTheOpenAsksOnceForTheAppsModules() {
        ProcessOutput.isSmallIdeOverride = false

        configureDirectory(appsBelow())
        recorded.awaitAsk()

        assertEquals(appsBelowModules(), recorded.chooserAsked.single().toSet())
    }

    fun testCancelLeavesEachFacetWithoutAnSdk() {
        configureDirectory(umbrella())
        recorded.awaitAsk()

        for (each in modules()) {
            assertNull("${each.name}'s facet SDK", storedName(each))
        }
    }

    /** An umbrella `hub` with the apps `alpha` and `beta` under `apps/`, no `.idea` in it. */
    private fun umbrella(): File {
        val dir = createTempDir("mix_umbrella")
        stage(dir, "mix.exs" to "hub", "apps/alpha/mix.exs" to "alpha", "apps/beta/mix.exs" to "beta")
        return dir
    }

    /** The modules of [umbrella]: the first module and one per app. */
    private fun umbrellaModules(): Set<Module> = appsBelowModules() + module

    /** The apps `alpha` and `beta` under `apps/`, with no `mix.exs` above them. */
    private fun appsBelow(): File {
        val dir = createTempDir("mix_apps_below")
        stage(dir, "apps/alpha/mix.exs" to "alpha", "apps/beta/mix.exs" to "beta")
        return dir
    }

    /** The modules of [appsBelow]'s apps. */
    private fun appsBelowModules(): Set<Module> = setOf(moduleNamed("alpha"), moduleNamed("beta"))

    /** Writes each `mix.exs` at its path under [dir] naming its app, and checks the scan finds exactly those apps. */
    private fun stage(dir: File, vararg mixExsToApp: Pair<String, String>) {
        for ((path, app) in mixExsToApp) {
            FileUtil.writeToFile(File(dir, path), mixExs(app))
        }
        val found = org.elixir_lang.mix.Project.findOtpApps(getVirtualFile(dir), EmptyProgressIndicator())
        assertEquals("the apps the scan finds", mixExsToApp.map { it.second }.toSet(), found.map(OtpApp::name).toSet())
    }

    private fun notificationTitles(): List<String> {
        val titles = mutableListOf<String>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    titles.add(notification.title)
                }
            }
        )
        return titles
    }

    private fun modules(): Set<Module> = ModuleManager.getInstance(project).modules.toSet()

    private fun moduleNamed(name: String): Module =
        ModuleManager.getInstance(project).findModuleByName(name) ?: error("no module $name among ${modules().map(Module::getName)}")

    private fun elixirFacet(module: Module): Facet? = FacetManager.getInstance(module).getFacetByType(Facet.ID)

    /** [module]'s only content root is [root], marking `lib` as sources and `test` as test sources. */
    private fun assertMixFoldersAreMarkedInItsOnlyContentRoot(module: Module, root: VirtualFile) {
        val contentEntry = read { ModuleRootManager.getInstance(module).contentEntries }.single()
        assertEquals("${module.name}'s content root", root, contentEntry.file)
        val isTestSource = contentEntry.sourceFolders.associate { it.url to it.isTestSource }
        assertEquals("${module.name}'s lib", false, isTestSource["${root.url}/lib"])
        assertEquals("${module.name}'s test", true, isTestSource["${root.url}/test"])
    }

    private fun filesUnder(dir: File): Set<String> =
        dir.walkTopDown().filter(File::isFile).map { FileUtil.getRelativePath(dir, it)!! }.toSet()

    /**
     * `runDirectoryProjectConfigurators` catches every [Throwable] a configurator raises and only
     * logs it, so the errors are collected and asserted on rather than left to reach the test
     * thread - they never would.
     */
    private fun configureDirectory(dir: File, newProject: Boolean = false) {
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
                        newProject = newProject,
                        createModule = false
                    )
                }
            }
        }

        assertEmpty("configuring $dir logged errors", logged)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        runInEdtAndWait { PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue() }
    }

    private fun <T> read(compute: () -> T): T = ReadAction.nonBlocking(Callable(compute)).executeSynchronously()
}
