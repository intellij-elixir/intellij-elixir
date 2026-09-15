package org.elixir_lang.sdk

import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.facet.impl.FacetUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.LightVirtualFile
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.mix.sync.MixSyncTestHelpers.runSuspendOnPooledThread
import org.elixir_lang.Facet
import org.elixir_lang.facet.Type
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.sdk.elixir.ElixirVersions
import org.elixir_lang.sdk.elixir.OtpMajor
import java.io.File
import java.util.concurrent.Callable

class ElixirLanguageLevelPusherTest : HeavyPlatformTestCase() {
    private val pusher = ElixirLanguageLevelPusher()

    override fun setUp() {
        super.setUp()
        elixirFacet(module)
    }

    override fun tearDown() {
        try {
            ModuleRootModificationUtil.setModuleSdk(module, null)
            SdkVersionsStore.getInstance().clearForTests()
        } finally {
            super.tearDown()
        }
    }

    fun testAModulesLevelComesFromTheStore() {
        val home = "/fake/elixir/level-store"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("Level store Elixir", home)))
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.12.3", OtpMajor.Known("24")))

        assertEquals(ElixirLanguageLevel.V1_12.name, read { pusher.getImmediateValue(module) })
    }

    fun testAModuleWithNoElixirSdkIsPushedTheFallback() {
        assertEquals(ElixirLanguageLevel.FALLBACK.name, read { pusher.getImmediateValue(module) })
    }

    /** A scan can reach the pusher before the store has read the module's home. */
    fun testAHomeNotReadYetKeepsTheLevelAlreadyOnTheDirectory() {
        ModuleRootModificationUtil.setModuleSdk(
            module,
            register(SdkFixtures.elixirSdk("Level unread Elixir", "/fake/elixir/level-unread")),
        )
        val directory = contentDirectory(module, "unread")
        ElixirLanguageLevelPusher.KEY.setPersistentValue(directory, ElixirLanguageLevel.V1_12.name)

        assertNull("an unread home has no level of its own", read { pusher.getImmediateValue(module) })
        assertEquals(ElixirLanguageLevel.V1_12.name, read { pusher.getImmediateValue(project, directory) })
    }

    fun testAHomeThatWasReadLeavesTheDirectoryToTheModule() {
        val home = "/fake/elixir/level-read"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("Level read Elixir", home)))
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.18.4", OtpMajor.Known("27")))
        val directory = contentDirectory(module, "read")
        ElixirLanguageLevelPusher.KEY.setPersistentValue(directory, ElixirLanguageLevel.V1_12.name)

        assertNull(read { pusher.getImmediateValue(project, directory) })
    }

    fun testAChangedLevelReparsesTheFilesUnderTheDirectory() {
        val file = elixirFile(module, "changed")
        val directory = file.parent
        ElixirLanguageLevelPusher.KEY.setPersistentValue(directory, ElixirLanguageLevel.V1_20.name)
        val before = parsed(file)

        read { pusher.persistAttribute(project, directory, ElixirLanguageLevel.V1_12.name) }

        assertEquals(ElixirLanguageLevel.V1_12.name, ElixirLanguageLevelPusher.KEY.getPersistentValue(directory))
        SdkFixtures.waitUntil("a file whose level changed must be parsed again") {
            PsiManager.getInstance(project).findFile(file) !== before
        }
    }

    fun testIsParsedAsElixirAcceptsScriptsAndTemplatesButNotBeam() {
        val directory = contentDirectory(module, "types")
        val parsed = listOf("ex", "exs", "eex", "leex", "heex").associateWith { extension ->
            ElixirLanguageLevelPusher.isParsedAsElixir(child(directory, "types.$extension"))
        }
        val notParsed = listOf("beam", "txt").associateWith { extension ->
            ElixirLanguageLevelPusher.isParsedAsElixir(child(directory, "types.$extension"))
        }

        assertEquals(parsed.keys.associateWith { true }, parsed)
        assertEquals("a .beam's stubs are built from the binary", notParsed.keys.associateWith { false }, notParsed)
    }

    fun testAnUnchangedLevelKeepsTheTree() {
        val file = elixirFile(module, "unchanged")
        val directory = file.parent
        ElixirLanguageLevelPusher.KEY.setPersistentValue(directory, ElixirLanguageLevel.V1_12.name)
        val before = parsed(file)

        read { pusher.persistAttribute(project, directory, ElixirLanguageLevel.V1_12.name) }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertSame(before, PsiManager.getInstance(project).findFile(file))
    }

    fun testThePushedLevelWinsOverTheStore() {
        val home = "/fake/elixir/level-pushed-wins"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("Level pushed Elixir", home)))
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.18.4", OtpMajor.Known("27")))
        val file = elixirFile(module, "pushed-wins")
        ElixirLanguageLevelPusher.KEY.setPersistentValue(file.parent, ElixirLanguageLevel.V1_12.name)

        assertEquals(ElixirLanguageLevel.V1_12, ElixirLanguageLevelResolver.languageLevelFor(parsed(file)))
    }

    fun testWithNothingPushedTheStoreAnswers() {
        val home = "/fake/elixir/level-store-fallback"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("Level fallback Elixir", home)))
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.18.4", OtpMajor.Known("27")))
        val file = elixirFile(module, "store-fallback")
        ElixirLanguageLevelPusher.KEY.setPersistentValue(file.parent, null)

        assertEquals(ElixirLanguageLevel.V1_18, ElixirLanguageLevelResolver.languageLevelFor(parsed(file)))
    }

    /** Indexing parses a copy of the file in a `LightVirtualFile`, which is in no directory of its own. */
    fun testACopyMadeForIndexingResolvesThroughItsOriginal() {
        val file = elixirFile(module, "indexing-copy")
        ElixirLanguageLevelPusher.KEY.setPersistentValue(file.parent, ElixirLanguageLevel.V1_12.name)
        val copy = LightVirtualFile(file.name, ElixirLanguage, "defmodule Copy do\nend\n").apply {
            originalFile = file
        }

        assertEquals(ElixirLanguageLevel.V1_12, ElixirLanguageLevelResolver.languageLevelFor(PsiManager.getInstance(project).findFile(copy)!!))
    }

    fun testAStoreChangePushesTheNewLevelAndReparses() {
        val home = "/fake/elixir/level-repush"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("Level repush Elixir", home)))
        val file = elixirFile(module, "repush")
        ElixirLanguageLevelPusher.KEY.setPersistentValue(file.parent, ElixirLanguageLevel.V1_20.name)
        val before = parsed(file)

        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.12.3", OtpMajor.Known("24")))

        SdkFixtures.waitUntil("a store change that moves the module's level must push it", timeoutMillis = 30_000) {
            ElixirLanguageLevelPusher.KEY.getPersistentValue(file.parent) == ElixirLanguageLevel.V1_12.name &&
                PsiManager.getInstance(project).findFile(file) !== before
        }
    }

    fun testALevelMatchingWhatIsPushedNeedsNoPush() {
        val home = "/fake/elixir/level-matching"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("Level matching Elixir", home)))
        val directory = contentDirectory(module, "matching")
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.12.3", OtpMajor.Known("24")))
        ElixirLanguageLevelPusher.KEY.setPersistentValue(directory, ElixirLanguageLevel.V1_12.name)

        assertFalse(
            "pushing walks the whole project in dumb mode, so a restart that changed nothing must not do it",
            read { project.service<ElixirLanguageLevelPushes>().isStale() },
        )
        ElixirLanguageLevelPusher.KEY.setPersistentValue(directory, ElixirLanguageLevel.V1_20.name)
        assertTrue("precondition: a differing level is stale", read { project.service<ElixirLanguageLevelPushes>().isStale() })
    }

    /** Every directory of every module is offered, including those of a Java module in a project that is not Elixir. */
    fun testADirectoryOfAModuleThatIsNotElixirIsNotPushed() {
        val directory = contentDirectory(createModule("not-elixir"), "not-elixir")

        assertFalse(read { pusher.acceptsDirectory(directory, project) })
        assertTrue("precondition: an Elixir module's directory is", read { pusher.acceptsDirectory(contentDirectory(module, "elixir"), project) })
    }

    fun testAModuleThatIsNotElixirNeverNeedsAPush() {
        val notElixir = createModule("never-pushed")
        contentDirectory(notElixir, "never-pushed")
        // A level of its own that nothing pushes, so only skipping the module keeps it from reading as stale.
        val home = "/fake/elixir/level-not-elixir"
        ModuleRootModificationUtil.setModuleSdk(notElixir, register(SdkFixtures.elixirSdk("Level not Elixir", home)))
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.12.3", OtpMajor.Known("24")))

        assertFalse(
            "a directory nothing pushes has no level, and would otherwise read as stale on every change",
            read { project.service<ElixirLanguageLevelPushes>().isStale() },
        )
    }

    /** A home an earlier project already read publishes nothing when this one opens, so opening has to compare. */
    fun testOpeningAProjectPushesALevelThatDiffersFromTheOneOnItsDirectories() {
        val home = "/fake/elixir/level-open"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("Level open Elixir", home)))
        val directory = contentDirectory(module, "open")
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.12.3", OtpMajor.Known("24")))
        SdkFixtures.waitUntil("precondition: the store and roots changes above push their own level", 30_000) {
            ElixirLanguageLevelPusher.KEY.getPersistentValue(directory) == ElixirLanguageLevel.V1_12.name
        }
        // What an earlier session left: nothing publishes, and nothing changes the roots.
        ElixirLanguageLevelPusher.KEY.setPersistentValue(directory, ElixirLanguageLevel.V1_20.name)

        runSuspendOnPooledThread { pusher.initExtra(project) }

        SdkFixtures.waitUntil("opening a project must push a level that differs", timeoutMillis = 30_000) {
            ElixirLanguageLevelPusher.KEY.getPersistentValue(directory) == ElixirLanguageLevel.V1_12.name
        }
    }

    /**
     * A push writes a content root before the directories under it, so one cut short - the project closed mid-push -
     * leaves a root that matches its level over directories that do not, and comparing the roots finds nothing to do.
     */
    fun testAPushThatDidNotFinishIsPushedAgainAtOpen() {
        val home = "/fake/elixir/level-unfinished"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("Level unfinished Elixir", home)))
        val root = contentDirectory(module, "unfinished")
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.12.3", OtpMajor.Known("24")))
        SdkFixtures.waitUntil("precondition: the store and roots changes above push their own level", 30_000) {
            ElixirLanguageLevelPusher.KEY.getPersistentValue(root) == ElixirLanguageLevel.V1_12.name
        }
        val subdirectory = WriteAction.computeAndWait<VirtualFile, Throwable> { root.createChildDirectory(this, "lib") }
        ElixirLanguageLevelPusher.KEY.setPersistentValue(subdirectory, ElixirLanguageLevel.V1_20.name)
        pushesSettle()
        PropertiesComponent.getInstance(project).setValue(ElixirLanguageLevelPushes.PUSH_PENDING, true)

        runSuspendOnPooledThread { pusher.initExtra(project) }

        SdkFixtures.waitUntil("a push the last session did not finish must run again", timeoutMillis = 30_000) {
            ElixirLanguageLevelPusher.KEY.getPersistentValue(subdirectory) == ElixirLanguageLevel.V1_12.name
        }
    }

    fun testAFinishedPushIsNoLongerPending() {
        val home = "/fake/elixir/level-finished"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("Level finished Elixir", home)))
        val root = contentDirectory(module, "finished")
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.12.3", OtpMajor.Known("24")))
        SdkFixtures.waitUntil("precondition: the store and roots changes above push their own level", 30_000) {
            ElixirLanguageLevelPusher.KEY.getPersistentValue(root) == ElixirLanguageLevel.V1_12.name
        }
        pushesSettle()
        PropertiesComponent.getInstance(project).setValue(ElixirLanguageLevelPushes.PUSH_PENDING, true)

        runSuspendOnPooledThread { pusher.initExtra(project) }

        SdkFixtures.waitUntil("a push that ran to the end must not be pushed again at the next open", timeoutMillis = 30_000) {
            !PropertiesComponent.getInstance(project).getBoolean(ElixirLanguageLevelPushes.PUSH_PENDING)
        }
    }

    /** A push merged or re-queued behind the clearing task keeps the project dumb until it has run. */
    fun testThePendingFlagWaitsForTheProjectToBeSmart() {
        val properties = PropertiesComponent.getInstance(project)
        properties.setValue(ElixirLanguageLevelPushes.PUSH_PENDING, true)

        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            val pushes = project.service<ElixirLanguageLevelPushes>()
            pushes.clearPendingOnceSmart(pushes.lastPush)
            assertTrue(
                "a push still queued keeps the project dumb, so it must stay pending",
                properties.getBoolean(ElixirLanguageLevelPushes.PUSH_PENDING),
            )
        }

        SdkFixtures.waitUntil("once the project is smart, every push queued before has run") {
            !properties.getBoolean(ElixirLanguageLevelPushes.PUSH_PENDING)
        }
    }

    fun testAnEarlierPushLeavesTheFlagOfALaterOne() {
        val properties = PropertiesComponent.getInstance(project)
        val pushes = project.service<ElixirLanguageLevelPushes>()
        properties.setValue(ElixirLanguageLevelPushes.PUSH_PENDING, true)

        pushes.clearPendingOnceSmart(pushes.lastPush - 1)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertTrue(
            "a later push set the flag after this one finished, and has not run yet",
            properties.getBoolean(ElixirLanguageLevelPushes.PUSH_PENDING),
        )
    }

    /** Were it read as unfinished, a change landing mid-push would walk the whole project again for nothing. */
    fun testThisSessionsOwnRunningPushIsNotAnUnfinishedOne() {
        pushesSettle()
        val pushes = project.service<ElixirLanguageLevelPushes>()

        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            pushes.push()
            assertFalse(
                "the flag this push set only says it has not settled yet",
                pushes.unfinishedPushPending(),
            )
        }
    }

    /** Loading the plugin again, without a restart, runs the attribute's initialiser a second time. */
    fun testTheAttributeIsCreatedOnce() {
        assertSame(ElixirLanguageLevelPusher.attribute(), ElixirLanguageLevelPusher.attribute())
    }

    fun testInitReadsTheHomesTheProjectUses() {
        val home = SdkFixtures.elixirHome("1.12.3")
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("Level init Elixir", home)))
        // Assigning the SDK reads it on its own; that read has to land before the store is emptied, or it refills it.
        SdkFixtures.waitUntil("precondition: assigning the SDK reads its home") {
            SdkVersionsStore.getInstance().elixirVersions(home) != null
        }
        SdkVersionsStore.getInstance().clearForTests()

        runSuspendOnPooledThread { pusher.initExtra(project) }

        assertEquals("1.12.3", SdkVersionsStore.getInstance().elixirVersions(home)?.elixirVersion)
    }

    private fun elixirFacet(module: Module) {
        if (FacetManager.getInstance(module).getFacetByType(Facet.ID) == null) {
            FacetUtil.addFacet(module, FacetType.findInstance(Type::class.java))
        }
    }

    private fun <T> read(block: () -> T): T =
        ReadAction.nonBlocking(Callable { block() }).executeSynchronously()

    /** Waits out this session's own pushes, so a flag the test sets afterwards stands only for an earlier session. */
    private fun pushesSettle() =
        SdkFixtures.waitUntil("precondition: the pushes above clear their own flag", 30_000) {
            !PropertiesComponent.getInstance(project).getBoolean(ElixirLanguageLevelPushes.PUSH_PENDING)
        }

    private fun register(sdk: Sdk): Sdk {
        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().addJdk(sdk, testRootDisposable) }
        return sdk
    }

    private fun contentDirectory(module: Module, name: String): VirtualFile {
        val directory = createTempDir(name)
        val virtualDirectory = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)
            ?: error("$directory not found in VFS after refresh")
        PsiTestUtil.addContentRoot(module, virtualDirectory)
        return virtualDirectory
    }

    private fun elixirFile(module: Module, name: String): VirtualFile =
        child(contentDirectory(module, name), "$name.ex", "defmodule Level do\n  def value, do: 1\nend\n")

    private fun child(directory: VirtualFile, name: String, text: String = ""): VirtualFile {
        File(directory.path, name).writeText(text)
        directory.refresh(false, false)
        return directory.findChild(name) ?: error("$name not found under $directory")
    }

    private fun parsed(file: VirtualFile): PsiFile =
        PsiManager.getInstance(project).findFile(file)!!.also { it.node }
}
