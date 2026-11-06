package org.elixir_lang.action

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleJdkOrderEntry
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.VfsTestUtil
import org.elixir_lang.jps.shared.ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.fakeHome
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.SimpleJavaSdkType
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.facet.impl.FacetUtil
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.application.ReadAction
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Facet
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.facet.Type
import org.elixir_lang.junit.onPooledThread
import org.elixir_lang.mix.project.ProjectModuleSetupValidator
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType

/** Runs Reconfigure Elixir Module Setup on [project], as the Tools menu does. */
private fun runReconfigure(project: Project) {
    ReconfigureModuleSetupAction().actionPerformed(TestActionEvent.createTestEvent(SimpleDataContext.getProjectContext(project)))
}

class ReconfigureModuleSetupActionTest : PlatformTestCase() {

    override fun setUp() {
        super.setUp()

        val facetManager = FacetManager.getInstance(module)
        if (facetManager.getFacetByType(Facet.ID) == null) {
            FacetUtil.addFacet(module, FacetType.findInstance(Type::class.java))
        }
    }

    override fun tearDown() {
        try {
            WriteAction.run<Throwable> {
                ProjectRootManager.getInstance(project).projectSdk = null
            }
        } finally {
            super.tearDown()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a Mix project directory with `mix.exs` and the given subdirectories,
     * adds a content entry to the test module, and configures it via [block].
     */
    private fun addMixContentEntry(
        dirName: String,
        subdirs: List<String> = emptyList(),
        block: (
            content: com.intellij.openapi.roots.ContentEntry,
            rootUrl: String
        ) -> Unit = { _, _ -> }
    ): com.intellij.openapi.vfs.VirtualFile {
        val appRoot = myFixture.tempDirFixture.findOrCreateDir(dirName)
        myFixture.tempDirFixture.createFile("$dirName/mix.exs", "")
        for (subdir in subdirs) {
            myFixture.tempDirFixture.findOrCreateDir("$dirName/$subdir")
        }

        ModuleRootModificationUtil.updateModel(module) { model ->
            val content = model.addContentEntry(appRoot)
            block(content, appRoot.url)
        }

        return appRoot
    }

    private fun runAction() {
        val action = ReconfigureModuleSetupAction()
        val dataContext = DataContext { dataId ->
            when {
                CommonDataKeys.PROJECT.`is`(dataId) -> project
                else -> null
            }
        }
        val event = AnActionEvent.createEvent(dataContext, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
        action.actionPerformed(event)
    }

    private fun detectIssuesOnBgThread(): List<ProjectModuleSetupValidator.FolderMarkIssue> {
        return onPooledThread<List<ProjectModuleSetupValidator.FolderMarkIssue>> {
            ApplicationManager.getApplication().runReadAction(
                ThrowableComputable { ProjectModuleSetupValidator.detectFolderMarkIssues(project) }
            )
        }
    }

    private fun removeElixirFacetIfPresent() {
        val facetManager = FacetManager.getInstance(module)
        val facet = facetManager.getFacetByType(Facet.ID) ?: return

        ApplicationManager.getApplication().runWriteAction {
            val model = facetManager.createModifiableModel()
            model.removeFacet(facet)
            model.commit()
        }
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * When a module has `lib/` and `test/` on disk but no source/exclude marks, the action
     * should additively apply all canonical marks for the directories that exist.
     */
    @RequiresReadLock
    fun testAddsMissingSourceAndExcludeMarks() {
        addMixContentEntry(
            "unconfigured_app",
            subdirs = listOf("lib", "test", "deps", ".elixir_ls", "cover", "doc", "logs")
        )
        // Verify issues exist before reconfigure
        val issuesBefore = detectIssuesOnBgThread()
        assertTrue("Expected issues before reconfigure", issuesBefore.isNotEmpty())

        runAction()

        // After reconfigure, all issues should be resolved
        val issuesAfter = detectIssuesOnBgThread()
        assertEquals(
            "Expected zero issues after reconfigure, but found: $issuesAfter",
            0,
            issuesAfter.size
        )

        // Verify the marks are actually set
        val rootManager = ModuleRootManager.getInstance(module)
        val contentEntry = rootManager.contentEntries.find {
            it.file?.findChild("mix.exs") != null && it.file?.name == "unconfigured_app"
        }
        assertNotNull("Content entry for unconfigured_app not found", contentEntry)

        val sourceUrls = contentEntry!!.sourceFolders.associate { it.url to it.isTestSource }
        assertTrue("lib/ should be Sources", sourceUrls.any { it.key.endsWith("/lib") && !it.value })
        assertTrue("web/ should be Sources", sourceUrls.any { it.key.endsWith("/web") && !it.value })
        assertTrue("spec/ should be Test Sources", sourceUrls.any { it.key.endsWith("/spec") && it.value })
        assertTrue("test/ should be Test Sources", sourceUrls.any { it.key.endsWith("/test") && it.value })

        val excludeUrls = contentEntry.excludeFolderUrls
        assertTrue("deps/ should be Excluded", excludeUrls.any { it.endsWith("/deps") })
        assertTrue(".elixir_ls/ should be Excluded", excludeUrls.any { it.endsWith("/.elixir_ls") })
        assertTrue("cover/ should be Excluded", excludeUrls.any { it.endsWith("/cover") })
        assertTrue("doc/ should be Excluded", excludeUrls.any { it.endsWith("/doc") })
        assertTrue("logs/ should be Excluded", excludeUrls.any { it.endsWith("/logs") })
        assertTrue("assets/node_modules/phoenix should be Excluded", excludeUrls.any { it.endsWith("/assets/node_modules/phoenix") })
        assertTrue("assets/node_modules/phoenix_html should be Excluded", excludeUrls.any { it.endsWith("/assets/node_modules/phoenix_html") })
    }

    /**
     * When a module is already fully configured, the action should be idempotent -
     * no duplicate marks should be added.
     */
    @RequiresReadLock
    fun testIdempotentWhenAlreadyConfigured() {
        addMixContentEntry(
            "configured_app",
            subdirs = listOf("lib", "test", "deps", ".elixir_ls", "cover", "doc", "logs")
        ) { content, rootUrl ->
            content.addSourceFolder("$rootUrl/lib", false)
            content.addSourceFolder("$rootUrl/web", false)
            content.addSourceFolder("$rootUrl/spec", true)
            content.addSourceFolder("$rootUrl/test", true)
            content.addExcludeFolder("$rootUrl/deps")
            content.addExcludeFolder("$rootUrl/.elixir_ls")
            content.addExcludeFolder("$rootUrl/assets/node_modules/phoenix")
            content.addExcludeFolder("$rootUrl/assets/node_modules/phoenix_html")
            content.addExcludeFolder("$rootUrl/cover")
            content.addExcludeFolder("$rootUrl/doc")
            content.addExcludeFolder("$rootUrl/logs")
        }

        // Snapshot the current state
        val rootManager = ModuleRootManager.getInstance(module)
        val entryBefore = rootManager.contentEntries.find {
            it.file?.name == "configured_app"
        }!!
        val sourceCountBefore = entryBefore.sourceFolders.size
        val excludeCountBefore = entryBefore.excludeFolderUrls.size

        runAction()

        // Counts should be identical - no duplicates added
        val entryAfter = ModuleRootManager.getInstance(module).contentEntries.find {
            it.file?.name == "configured_app"
        }!!
        assertEquals("Source folder count should not change", sourceCountBefore, entryAfter.sourceFolders.size)
        assertEquals("Exclude folder count should not change", excludeCountBefore, entryAfter.excludeFolderUrls.size)
    }

    /**
     * The action should preserve user-customized source roots that are not part of the
     * canonical set (e.g. a custom `scripts/` Sources root).
     */
    @RequiresReadLock
    fun testPreservesCustomSourceRoots() {
        addMixContentEntry(
            "custom_roots_app",
            subdirs = listOf("lib", "test", "scripts")
        ) { content, rootUrl ->
            // User has manually added scripts/ as Sources
            content.addSourceFolder("$rootUrl/scripts", false)
            // But is missing canonical lib/ and test/ marks
        }

        runAction()

        val rootManager = ModuleRootManager.getInstance(module)
        val entry = rootManager.contentEntries.find { it.file?.name == "custom_roots_app" }!!
        val sourceUrls = entry.sourceFolders.map { it.url }

        assertTrue("Custom scripts/ root should be preserved", sourceUrls.any { it.endsWith("/scripts") })
        assertTrue("Canonical lib/ should be added", sourceUrls.any { it.endsWith("/lib") })
        assertTrue("Canonical test/ should be added", sourceUrls.any { it.endsWith("/test") })
    }

    /**
     * When `lib/` is incorrectly marked as Test Sources, the action should fix it to Sources.
     */
    @RequiresReadLock
    fun testFixesIncorrectSourceMark() {
        addMixContentEntry(
            "wrong_mark_app",
            subdirs = listOf("lib", "test")
        ) { content, rootUrl ->
            // lib/ incorrectly marked as Test Sources
            content.addSourceFolder("$rootUrl/lib", true)
            content.addSourceFolder("$rootUrl/test", true)
        }

        runAction()

        val rootManager = ModuleRootManager.getInstance(module)
        val entry = rootManager.contentEntries.find { it.file?.name == "wrong_mark_app" }!!
        val libFolder = entry.sourceFolders.find { it.url.endsWith("/lib") }

        assertNotNull("lib/ should still be in source folders", libFolder)
        assertFalse("lib/ should be Sources (not Test Sources)", libFolder!!.isTestSource)
    }

    /**
     * Canonical marks are added by URL even when directories are not present on disk yet.
     */
    @RequiresReadLock
    fun testAddsMarksForNonExistentDirectories() {
        // Only create lib/ and test/, NOT spec/, .elixir_ls/, etc.
        addMixContentEntry(
            "minimal_app",
            subdirs = listOf("lib", "test")
        )

        runAction()

        val rootManager = ModuleRootManager.getInstance(module)
        val entry = rootManager.contentEntries.find { it.file?.name == "minimal_app" }!!

        assertTrue("web/ should be marked even when missing on disk", entry.sourceFolders.any { it.url.endsWith("/web") })
        assertTrue("spec/ should be marked even when missing on disk", entry.sourceFolders.any { it.url.endsWith("/spec") })
        assertTrue(".elixir_ls/ should be excluded even when missing on disk", entry.excludeFolderUrls.any { it.endsWith("/.elixir_ls") })
        assertTrue("doc/ should be excluded even when missing on disk", entry.excludeFolderUrls.any { it.endsWith("/doc") })
    }

    /**
     * Non-Mix modules (no `mix.exs`) should be completely ignored by the action.
     */
    @RequiresReadLock
    fun testNonMixModuleIsSkipped() {
        val appRoot = myFixture.tempDirFixture.findOrCreateDir("non_mix_app")
        myFixture.tempDirFixture.findOrCreateDir("non_mix_app/lib")
        myFixture.tempDirFixture.findOrCreateDir("non_mix_app/test")
        // Deliberately do NOT create mix.exs

        ModuleRootModificationUtil.updateModel(module) { model ->
            model.addContentEntry(appRoot)
        }

        runAction()

        val rootManager = ModuleRootManager.getInstance(module)
        val entry = rootManager.contentEntries.find { it.file?.name == "non_mix_app" }!!

        // No marks should be applied
        assertTrue("No source folders in non-Mix module", entry.sourceFolders.isEmpty())
        assertTrue("No exclusions in non-Mix module", entry.excludeFolderUrls.isEmpty())
    }

    /**
     * Strict filtering: even if a content root is a Mix app, it should be ignored when the module is not
     * identified as Elixir (no ELIXIR_MODULE type and no Elixir facet).
     */
    @RequiresReadLock
    fun testMixModuleWithoutElixirIdentityIsSkipped() {
        removeElixirFacetIfPresent()

        addMixContentEntry(
            "plain_mix_app",
            subdirs = listOf("lib", "test", "deps")
        )

        runAction()

        val rootManager = ModuleRootManager.getInstance(module)
        val entry = rootManager.contentEntries.find { it.file?.name == "plain_mix_app" }!!

        assertTrue("No source folders should be added when module is not Elixir", entry.sourceFolders.isEmpty())
        assertTrue("No exclusions should be added when module is not Elixir", entry.excludeFolderUrls.isEmpty())
    }

    /**
     * web/ is a canonical source root and is added even when absent.
     */
    @RequiresReadLock
    fun testWebDirectoryMarkedUnconditionally() {
        addMixContentEntry(
            "phoenix_old_app",
            subdirs = listOf("lib", "test")
        )

        runAction()

        val rootManager = ModuleRootManager.getInstance(module)
        val entry = rootManager.contentEntries.find { it.file?.name == "phoenix_old_app" }!!
        val webFolder = entry.sourceFolders.find { it.url.endsWith("/web") }

        assertNotNull("web/ should be marked as Source even when it does not exist", webFolder)
        assertFalse("web/ should be Sources (not Test Sources)", webFolder!!.isTestSource)
    }

    /**
     * Reproduces a single-module umbrella whose child app was added after the module was
     * configured: the umbrella root already carries every canonical mark, and `apps/c1` has a
     * `mix.exs`, `lib/` and `test/` but no content entry of its own.
     *
     * The action must mark `apps/c1/lib` and `apps/c1/test`, because the umbrella root's own marks
     * say nothing about its children.
     */
    @RequiresReadLock
    fun testAddsMarksForAnUmbrellaSubAppAddedAfterTheRootWasConfigured() {
        myFixture.tempDirFixture.findOrCreateDir("umbrella_root/apps/c1/lib")
        myFixture.tempDirFixture.findOrCreateDir("umbrella_root/apps/c1/test")
        myFixture.tempDirFixture.createFile("umbrella_root/apps/c1/mix.exs", "")

        // The umbrella root as the New Project wizard leaves it: every canonical mark applied by
        // URL, whether or not the directory exists.
        addMixContentEntry("umbrella_root") { content, rootUrl ->
            content.addSourceFolder("$rootUrl/lib", false)
            content.addSourceFolder("$rootUrl/web", false)
            content.addSourceFolder("$rootUrl/spec", true)
            content.addSourceFolder("$rootUrl/test", true)
            content.addExcludeFolder("$rootUrl/deps")
            content.addExcludeFolder("$rootUrl/.elixir_ls")
            content.addExcludeFolder("$rootUrl/assets/node_modules/phoenix")
            content.addExcludeFolder("$rootUrl/assets/node_modules/phoenix_html")
            content.addExcludeFolder("$rootUrl/cover")
            content.addExcludeFolder("$rootUrl/doc")
            content.addExcludeFolder("$rootUrl/logs")
        }

        val issuesBefore = detectIssuesOnBgThread().filter { it.folderRelativePath.startsWith("apps/c1") }
        assertTrue(
            "The validator should report the unmarked sub-app before the action runs",
            issuesBefore.isNotEmpty()
        )

        runAction()

        val entry = ModuleRootManager.getInstance(module).contentEntries
            .find { it.file?.name == "umbrella_root" }!!
        val sourceUrls = entry.sourceFolders.associate { it.url to it.isTestSource }

        assertTrue(
            "apps/c1/lib should be Sources, got: ${sourceUrls.keys}",
            sourceUrls.any { it.key.endsWith("/apps/c1/lib") && !it.value }
        )
        assertTrue(
            "apps/c1/test should be Test Sources, got: ${sourceUrls.keys}",
            sourceUrls.any { it.key.endsWith("/apps/c1/test") && it.value }
        )

        val issuesAfter = detectIssuesOnBgThread().filter { it.folderRelativePath.startsWith("apps/c1") }
        assertEquals(
            "The sub-app should have no remaining folder mark issues, got: $issuesAfter",
            0,
            issuesAfter.size
        )
    }

    // -------------------------------------------------------------------------
    // Scenario 4: Project SDK = Java, Reconfigure action invoked
    // -> folder marks applied; module SDK left untouched
    // -------------------------------------------------------------------------

    /**
     * When the project SDK is non-Elixir (e.g. Java), the Reconfigure action must NOT
     * touch the module SDK.
     */
    @RequiresEdt
    fun testReconfigureWithNonElixirProjectSdkDoesNotTouchModuleSdk() {
        val javaHome = System.getProperty("java.home") ?: "/usr/lib/jvm/java"
        val javaSdk = SimpleJavaSdkType().createJdk("Java Mock", javaHome)

        val elixirSdk = ProjectJdkImpl("Elixir Mock", ElixirSdkType.instance)
        // Removed with testRootDisposable, after the module stops naming it.
        WriteAction.run<Throwable> {
            ProjectJdkTable.getInstance().addJdk(elixirSdk, testRootDisposable)
        }

        // Set project SDK to Java and module SDK to Elixir
        WriteAction.run<Throwable> {
            ProjectRootManager.getInstance(project).projectSdk = javaSdk
        }
        ModuleRootModificationUtil.setModuleSdk(module, elixirSdk)

        // Add a Mix content entry so the action processes the module
        addMixContentEntry("sdk_guard_app", subdirs = listOf("lib", "test"))

        runAction()

        // The module SDK should still be the Elixir SDK, not the Java project SDK
        val moduleSdkAfter = ReadAction.nonBlocking(java.util.concurrent.Callable {
            ModuleRootManager.getInstance(module).sdk
        }).executeSynchronously()
        assertEquals(
            "Module SDK must not be replaced with Java project SDK when project SDK is non-Elixir",
            elixirSdk,
            moduleSdkAfter
        )
    }
}

/**
 * Reconfigure leaves a module's SDK alone, on a project with a `mix.exs` at its root and an Elixir project SDK: the
 * chooser is where a module's SDK is picked.
 */
class ReconfigureModuleSetupActionSdkTest : HeavyTestCase() {
    private lateinit var app: Module

    override fun setUp() {
        super.setUp()
        val root = getOrCreateProjectBaseDir()
        VfsTestUtil.createFile(root, "mix.exs", "")
        app = doCreateRealModuleIn("app", project, ModuleTypeManager.getInstance().findByID(ELIXIR_MODULE_TYPE_ID))
        PsiTestUtil.addContentRoot(app, root)
        val projectSdk = SdkFixtures.register(SdkFixtures.elixirSdk("Reconfigure project", fakeHome("elixir/reconfigure-project")), testRootDisposable)
        WriteAction.runAndWait<Throwable> { ProjectRootManager.getInstance(project).projectSdk = projectSdk }
    }

    fun testADanglingModuleSdkIsLeft() {
        ModuleRootModificationUtil.updateModel(app) { it.setInvalidSdk("Reconfigure gone", ElixirSdkType.instance.name) }

        runReconfigure(project)

        val rootManager = ModuleRootManager.getInstance(app)
        assertFalse("the module does not inherit", rootManager.isSdkInherited)
        assertEquals("Reconfigure gone", rootManager.orderEntries.filterIsInstance<ModuleJdkOrderEntry>().single().jdkName)
    }

    fun testAModuleSdkOtherThanTheProjectSdkIsLeft() {
        val own = SdkFixtures.register(SdkFixtures.elixirSdk("Reconfigure own", fakeHome("elixir/reconfigure-own")), testRootDisposable)
        ModuleRootModificationUtil.setModuleSdk(app, own)

        runReconfigure(project)

        assertSame(own, ModuleRootManager.getInstance(app).sdk)
    }
}
