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
import com.intellij.openapi.roots.impl.PushedFilePropertiesUpdater
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
import org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData
import java.io.File
import java.util.concurrent.Callable

class SdkVersionsPusherTest : HeavyPlatformTestCase() {
    private val pusher = SdkVersionsPusher()

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

    fun testAModulesVersionsComeFromTheStore() {
        val home = "/fake/elixir/level-store"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("Level store Elixir", home)))
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.12.3", OtpMajor.Known("24")))

        assertEquals("1.12.3|24", read { pusher.getImmediateValue(module) })
    }

    fun testAModuleWithNoElixirSdkIsPushedTheFallback() {
        assertEquals(PushedVersions.encode(ElixirLanguageLevel.FALLBACK), read { pusher.getImmediateValue(module) })
    }

    /** A scan can reach the pusher before the store has read the module's home. */
    fun testAHomeNotReadYetKeepsTheVersionAlreadyOnTheDirectory() {
        ModuleRootModificationUtil.setModuleSdk(
            module,
            register(SdkFixtures.elixirSdk("Level unread Elixir", "/fake/elixir/level-unread")),
        )
        val directory = contentDirectory(module, "unread")
        PushedVersions.KEY.setPersistentValue(directory, "1.12.3")

        assertNull("an unread home has no version of its own", read { pusher.getImmediateValue(module) })
        assertEquals("1.12.3", read { pusher.getImmediateValue(project, directory) })
    }

    fun testAHomeThatWasReadGivesTheDirectoryTheModulesVersions() {
        val home = "/fake/elixir/level-read"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("Level read Elixir", home)))
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.18.4", OtpMajor.Known("27")))
        val directory = contentDirectory(module, "read")
        PushedVersions.KEY.setPersistentValue(directory, "1.12.3")

        assertEquals("1.18.4|27", read { pusher.getImmediateValue(project, directory) })
    }

    fun testAChangedVersionReparsesTheFilesUnderTheDirectory() {
        val file = elixirFile(module, "changed")
        val directory = file.parent
        PushedVersions.KEY.setPersistentValue(directory, "1.20.4")
        val before = parsed(file)

        read { pusher.persistAttribute(project, directory, "1.12.3") }

        assertEquals("1.12.3", PushedVersions.KEY.getPersistentValue(directory))
        SdkFixtures.waitUntil("a file whose version changed must be parsed again") {
            PsiManager.getInstance(project).findFile(file) !== before
        }
    }

    fun testIsParsedAsElixirAcceptsScriptsAndTemplatesButNotBeam() {
        val directory = contentDirectory(module, "types")
        val parsed = listOf("ex", "exs", "eex", "leex", "heex").associateWith { extension ->
            isParsedAsElixir(child(directory, "types.$extension"))
        }
        val notParsed = listOf("beam", "txt").associateWith { extension ->
            isParsedAsElixir(child(directory, "types.$extension"))
        }

        assertEquals(parsed.keys.associateWith { true }, parsed)
        assertEquals("a .beam's stubs are built from the binary", notParsed.keys.associateWith { false }, notParsed)
    }

    fun testAnUnchangedVersionKeepsTheTree() {
        val file = elixirFile(module, "unchanged")
        val directory = file.parent
        PushedVersions.KEY.setPersistentValue(directory, "1.12.3")
        val before = parsed(file)

        read { pusher.persistAttribute(project, directory, "1.12.3") }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertSame(before, PsiManager.getInstance(project).findFile(file))
    }

    fun testThePushedVersionWinsOverTheStore() {
        val home = "/fake/elixir/level-pushed-wins"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("Level pushed Elixir", home)))
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.18.4", OtpMajor.Known("27")))
        val file = elixirFile(module, "pushed-wins")
        PushedVersions.KEY.setPersistentValue(file.parent, "1.12.3")

        assertEquals(ElixirLanguageLevel.of("1.12.3"), ElixirLanguageLevelResolver.languageLevelFor(parsed(file)))
    }

    fun testWithNothingPushedTheStoreAnswers() {
        val home = "/fake/elixir/level-store-fallback"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("Level fallback Elixir", home)))
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.18.4", OtpMajor.Known("27")))
        val file = elixirFile(module, "store-fallback")
        PushedVersions.KEY.setPersistentValue(file.parent, null)

        assertEquals(ElixirLanguageLevel.of("1.18.4", "27"), ElixirLanguageLevelResolver.languageLevelFor(parsed(file)))
    }

    /** A value an older plugin pushed, such as a level name, is not a version, so the store answers instead. */
    fun testAPushedValueThatIsNotAVersionLeavesItToTheStore() {
        val home = "/fake/elixir/level-unreadable"
        ModuleRootModificationUtil.setModuleSdk(
            module,
            register(SdkFixtures.elixirSdk("Level unreadable Elixir", home))
        )
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.18.4", OtpMajor.Known("27")))
        val file = elixirFile(module, "unreadable")
        PushedVersions.KEY.setPersistentValue(file.parent, "V1_12")

        assertEquals(ElixirLanguageLevel.of("1.18.4", "27"), ElixirLanguageLevelResolver.languageLevelFor(parsed(file)))
    }

    /** Indexing parses a copy of the file in a `LightVirtualFile`, which is in no directory of its own. */
    fun testACopyMadeForIndexingResolvesThroughItsOriginal() {
        val file = elixirFile(module, "indexing-copy")
        PushedVersions.KEY.setPersistentValue(file.parent, "1.12.3")
        val copy = LightVirtualFile(file.name, ElixirLanguage, "defmodule Copy do\nend\n").apply {
            originalFile = file
        }

        assertEquals(
            ElixirLanguageLevel.of("1.12.3"),
            ElixirLanguageLevelResolver.languageLevelFor(PsiManager.getInstance(project).findFile(copy)!!)
        )
    }

    fun testAStoreChangePushesTheNewVersionAndReparses() {
        val home = "/fake/elixir/level-repush"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("Level repush Elixir", home)))
        val file = elixirFile(module, "repush")
        PushedVersions.KEY.setPersistentValue(file.parent, "1.20.4")
        val before = parsed(file)

        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.12.3", OtpMajor.Known("24")))

        SdkFixtures.waitUntil("a store change that moves the module's version must push it", timeoutMillis = 30_000) {
            PushedVersions.KEY.getPersistentValue(file.parent) == "1.12.3|24" &&
                PsiManager.getInstance(project).findFile(file) !== before
        }
    }

    fun testAVersionMatchingWhatIsPushedNeedsNoPush() {
        val home = "/fake/elixir/level-matching"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("Level matching Elixir", home)))
        val directory = contentDirectory(module, "matching")
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.12.3", OtpMajor.Known("24")))
        PushedVersions.KEY.setPersistentValue(directory, "1.12.3|24")

        assertFalse(
            "pushing walks the whole project in dumb mode, so a restart that changed nothing must not do it",
            read { project.service<ElixirLanguageLevelPushes>().isStale() },
        )
        PushedVersions.KEY.setPersistentValue(directory, "1.20.4")
        assertTrue(
            "precondition: a differing version is stale",
            read { project.service<ElixirLanguageLevelPushes>().isStale() }
        )
    }

    /** Every directory of every module is offered, including those of a Java module in a project that is not Elixir. */
    fun testADirectoryOfAModuleThatIsNotElixirIsNotPushed() {
        val directory = contentDirectory(createModule("not-elixir"), "not-elixir")

        assertFalse(read { pusher.acceptsDirectory(directory, project) })
        assertTrue(
            "precondition: an Elixir module's directory is",
            read { pusher.acceptsDirectory(contentDirectory(module, "elixir"), project) },
        )
    }

    fun testAModuleThatIsNotElixirNeverNeedsAPush() {
        val notElixir = createModule("never-pushed")
        contentDirectory(notElixir, "never-pushed")
        // A version of its own that nothing pushes, so only skipping the module keeps it from reading as stale.
        val home = "/fake/elixir/level-not-elixir"
        ModuleRootModificationUtil.setModuleSdk(notElixir, register(SdkFixtures.elixirSdk("Level not Elixir", home)))
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.12.3", OtpMajor.Known("24")))

        assertFalse(
            "a directory nothing pushes has no version, and would otherwise read as stale on every change",
            read { project.service<ElixirLanguageLevelPushes>().isStale() },
        )
    }

    /** A home an earlier project already read publishes nothing when this one opens, so opening has to compare. */
    fun testOpeningAProjectPushesAVersionThatDiffersFromTheOneOnItsDirectories() {
        val home = "/fake/elixir/level-open"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("Level open Elixir", home)))
        val directory = contentDirectory(module, "open")
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.12.3", OtpMajor.Known("24")))
        SdkFixtures.waitUntil("precondition: the store and roots changes above push their own version", 30_000) {
            PushedVersions.KEY.getPersistentValue(directory) == "1.12.3|24"
        }
        // What an earlier session left: nothing publishes, and nothing changes the roots.
        PushedVersions.KEY.setPersistentValue(directory, "1.20.4")

        runSuspendOnPooledThread { pusher.initExtra(project) }

        SdkFixtures.waitUntil("opening a project must push a version that differs", timeoutMillis = 30_000) {
            PushedVersions.KEY.getPersistentValue(directory) == "1.12.3|24"
        }
    }

    /**
     * A push writes a content root before the directories under it, so one cut short - the project closed mid-push -
     * leaves a root that matches its version over directories that do not, and comparing the roots finds nothing to do.
     */
    fun testAPushThatDidNotFinishIsPushedAgainAtOpen() {
        val home = "/fake/elixir/level-unfinished"
        ModuleRootModificationUtil.setModuleSdk(
            module,
            register(SdkFixtures.elixirSdk("Level unfinished Elixir", home)),
        )
        val root = contentDirectory(module, "unfinished")
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.12.3", OtpMajor.Known("24")))
        SdkFixtures.waitUntil("precondition: the store and roots changes above push their own version", 30_000) {
            PushedVersions.KEY.getPersistentValue(root) == "1.12.3|24"
        }
        val subdirectory = WriteAction.computeAndWait<VirtualFile, Throwable> { root.createChildDirectory(this, "lib") }
        PushedVersions.KEY.setPersistentValue(subdirectory, "1.20.4")
        pushesSettle()
        PropertiesComponent.getInstance(project).setValue(ElixirLanguageLevelPushes.PUSH_PENDING, true)

        runSuspendOnPooledThread { pusher.initExtra(project) }

        SdkFixtures.waitUntil("a push the last session did not finish must run again", timeoutMillis = 30_000) {
            PushedVersions.KEY.getPersistentValue(subdirectory) == "1.12.3|24"
        }
    }

    fun testAFinishedPushIsNoLongerPending() {
        val home = "/fake/elixir/level-finished"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("Level finished Elixir", home)))
        val root = contentDirectory(module, "finished")
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.12.3", OtpMajor.Known("24")))
        SdkFixtures.waitUntil("precondition: the store and roots changes above push their own version", 30_000) {
            PushedVersions.KEY.getPersistentValue(root) == "1.12.3|24"
        }
        pushesSettle()
        PropertiesComponent.getInstance(project).setValue(ElixirLanguageLevelPushes.PUSH_PENDING, true)

        runSuspendOnPooledThread { pusher.initExtra(project) }

        SdkFixtures.waitUntil(
            "a push that ran to the end must not be pushed again at the next open",
            timeoutMillis = 30_000,
        ) {
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
        assertSame(PushedVersions.attribute(), PushedVersions.attribute())
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

    fun testThePairedErlangSdksOtpVersionIsPushed() {
        val elixirSdk = pairedElixirSdk("OTP paired", "/fake/elixir/otp-paired", "/fake/erlang/otp-paired")
        ModuleRootModificationUtil.setModuleSdk(module, elixirSdk)
        SdkVersionsStore.getInstance().setElixirVersions(
            "/fake/elixir/otp-paired",
            ElixirVersions("1.18.4", OtpMajor.Known("27"))
        )
        SdkVersionsStore.getInstance().setOtpVersion("/fake/erlang/otp-paired", "26.2.5.21")

        assertEquals(
            "the OTP running Elixir decides, not the one its build targeted",
            "1.18.4|26.2.5.21",
            read { pusher.getImmediateValue(module) },
        )
    }

    fun testWithNoPairedErlangSdkTheBuildsOtpMajorStandsIn() {
        val home = "/fake/elixir/otp-unpaired"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("OTP unpaired Elixir", home)))
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.18.4", OtpMajor.Known("27")))

        assertEquals("1.18.4|27", read { pusher.getImmediateValue(module) })
    }

    fun testWithNeitherTheOtpVersionIsLeftUnknown() {
        val home = "/fake/elixir/otp-unknown"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("OTP unknown Elixir", home)))
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.5.3", OtpMajor.None))

        assertEquals("1.5.3", read { pusher.getImmediateValue(module) })
    }

    /** Pushing the build's major meanwhile would reindex everything under it now, and again once the home is read. */
    fun testAPairedErlangSdkNotReadYetKeepsWhatIsOnTheDirectory() {
        val elixirSdk = pairedElixirSdk("OTP unread", "/fake/elixir/otp-unread", "/fake/erlang/otp-unread")
        ModuleRootModificationUtil.setModuleSdk(module, elixirSdk)
        SdkVersionsStore.getInstance().setElixirVersions(
            "/fake/elixir/otp-unread",
            ElixirVersions("1.18.4", OtpMajor.Known("27"))
        )
        val directory = contentDirectory(module, "otp-unread")
        PushedVersions.KEY.setPersistentValue(directory, "1.18.4|26.2.5.21")

        assertNull(read { pusher.getImmediateValue(module) })
        assertEquals("1.18.4|26.2.5.21", read { pusher.getImmediateValue(project, directory) })
    }

    /** A paired Erlang SDK that was removed is never read, and waiting would leave the content root at the default. */
    fun testAContentRootWithNoVersionsWhileThePairedErlangSdkIsUnreadGetsTheBuildsOtpMajor() {
        val directory = unreadPairingDirectory("otp-never-read")
        PushedVersions.KEY.setPersistentValue(directory, null)

        assertEquals("1.13.4|24", read { pusher.getImmediateValue(project, directory) })
    }

    /** The staleness check compares content roots only, so a subdirectory given its own value would keep it. */
    fun testASubdirectoryWithNoVersionsWhileThePairedErlangSdkIsUnreadInheritsItsParents() {
        val root = unreadPairingDirectory("otp-unread-subdirectory")
        PushedVersions.KEY.setPersistentValue(root, "1.13.4|24.3.4.6")
        val subdirectory = WriteAction.computeAndWait<VirtualFile, Throwable> { root.createChildDirectory(this, "lib") }
        PushedVersions.KEY.setPersistentValue(subdirectory, null)

        read { PushedFilePropertiesUpdater.getInstance(project).findAndUpdateValue(subdirectory, pusher, null) }

        assertEquals("1.13.4|24.3.4.6", PushedVersions.KEY.getPersistentValue(subdirectory))
    }

    /** An umbrella app is a module of its own, so its content root follows its own SDK rather than the outer module. */
    fun testANestedModulesContentRootWithNoVersionsWhileItsPairedErlangSdkIsUnreadGetsItsBuildsOtpMajor() {
        val outer = contentDirectory(module, "otp-unread-outer")
        val inner = WriteAction.computeAndWait<VirtualFile, Throwable> { outer.createChildDirectory(this, "inner") }
        val innerModule = createModule("otp-unread-inner")
        elixirFacet(innerModule)
        PsiTestUtil.addContentRoot(innerModule, inner)
        val elixirSdk = pairedElixirSdk(
            "otp-unread-inner",
            "/fake/elixir/otp-unread-inner",
            "/fake/erlang/otp-unread-inner"
        )
        ModuleRootModificationUtil.setModuleSdk(innerModule, elixirSdk)
        SdkVersionsStore.getInstance()
            .setElixirVersions("/fake/elixir/otp-unread-inner", ElixirVersions("1.13.4", OtpMajor.Known("24")))
        PushedVersions.KEY.setPersistentValue(inner, null)

        read { PushedFilePropertiesUpdater.getInstance(project).findAndUpdateValue(inner, pusher, null) }

        assertEquals("1.13.4|24", PushedVersions.KEY.getPersistentValue(inner))
    }

    fun testANestedModulesContentRootWhoseElixirHomeIsUnreadDoesNotTakeTheOuterModulesVersions() {
        val inner = nestedModuleRoot("unread-home-inner", "1.12.3")
        ModuleRootModificationUtil.setModuleSdk(
            inner.first,
            pairedElixirSdk("unread-home-inner", "/fake/elixir/unread-home-inner", "/fake/erlang/unread-home-inner"),
        )

        read { PushedFilePropertiesUpdater.getInstance(project).findAndUpdateValue(inner.second, pusher, null) }

        assertEquals(
            PushedVersions.encode(ElixirLanguageLevel.FALLBACK),
            PushedVersions.KEY.getPersistentValue(inner.second),
        )
    }

    /** A directory created later is pushed with no module value, so the directory itself must answer its module's. */
    fun testANestedModulesNewContentRootGetsItsOwnModulesVersions() {
        val inner = nestedModuleRoot("read-home-inner", "1.12.3")
        val home = "/fake/elixir/read-home-inner"
        ModuleRootModificationUtil.setModuleSdk(inner.first, register(SdkFixtures.elixirSdk("read-home-inner", home)))
        SdkVersionsStore.getInstance().setElixirVersions(home, ElixirVersions("1.18.4", OtpMajor.Known("27")))

        read { PushedFilePropertiesUpdater.getInstance(project).findAndUpdateValue(inner.second, pusher, null) }

        assertEquals("1.18.4|27", PushedVersions.KEY.getPersistentValue(inner.second))
    }

    fun testAContentRootInsideItsOwnModulesExcludedFolderGetsTheBuildsOtpMajor() {
        val root = unreadPairingDirectory("otp-unread-excluded")
        val excluded =
            WriteAction.computeAndWait<VirtualFile, Throwable> { root.createChildDirectory(this, "excluded") }
        val inner = WriteAction.computeAndWait<VirtualFile, Throwable> { excluded.createChildDirectory(this, "inner") }
        PsiTestUtil.addExcludedRoot(module, excluded)
        PsiTestUtil.addContentRoot(module, inner)
        PushedVersions.KEY.setPersistentValue(inner, null)

        read { PushedFilePropertiesUpdater.getInstance(project).findAndUpdateValue(inner, pusher, null) }

        assertEquals("1.13.4|24", PushedVersions.KEY.getPersistentValue(inner))
    }

    /** A module whose content root sits in the content of [module], whose SDK is Elixir [outerVersion]. */
    private fun nestedModuleRoot(name: String, outerVersion: String): Pair<Module, VirtualFile> {
        val outerHome = "/fake/elixir/$name-outer"
        ModuleRootModificationUtil.setModuleSdk(module, register(SdkFixtures.elixirSdk("$name outer", outerHome)))
        SdkVersionsStore.getInstance().setElixirVersions(outerHome, ElixirVersions(outerVersion, OtpMajor.None))
        val outer = contentDirectory(module, "$name-outer")
        PushedVersions.KEY.setPersistentValue(outer, outerVersion)
        val inner = WriteAction.computeAndWait<VirtualFile, Throwable> { outer.createChildDirectory(this, "inner") }
        val innerModule = createModule(name)
        elixirFacet(innerModule)
        PsiTestUtil.addContentRoot(innerModule, inner)
        PushedVersions.KEY.setPersistentValue(inner, null)
        return innerModule to inner
    }

    private fun unreadPairingDirectory(name: String): VirtualFile {
        val elixirSdk = pairedElixirSdk(name, "/fake/elixir/$name", "/fake/erlang/$name")
        ModuleRootModificationUtil.setModuleSdk(module, elixirSdk)
        SdkVersionsStore.getInstance()
            .setElixirVersions("/fake/elixir/$name", ElixirVersions("1.13.4", OtpMajor.Known("24")))
        return contentDirectory(module, name)
    }

    fun testThePushedOtpVersionReachesTheLanguageLevel() {
        val file = elixirFile(module, "otp-pushed")
        PushedVersions.KEY.setPersistentValue(file.parent, "1.18.4|26.2.5.21")

        assertEquals(
            ElixirLanguageLevel.of("1.18.4", "26.2.5.21"),
            ElixirLanguageLevelResolver.languageLevelFor(parsed(file))
        )
    }

    fun testADifferentOtpVersionIsStale() {
        val elixirSdk = pairedElixirSdk("OTP stale", "/fake/elixir/otp-stale", "/fake/erlang/otp-stale")
        ModuleRootModificationUtil.setModuleSdk(module, elixirSdk)
        val directory = contentDirectory(module, "otp-stale")
        SdkVersionsStore.getInstance().setElixirVersions(
            "/fake/elixir/otp-stale",
            ElixirVersions("1.18.4", OtpMajor.Known("27"))
        )
        SdkVersionsStore.getInstance().setOtpVersion("/fake/erlang/otp-stale", "27.3.4")
        PushedVersions.KEY.setPersistentValue(directory, "1.18.4|27.3.4")
        assertFalse(
            "precondition: the same versions are not stale",
            read { project.service<ElixirLanguageLevelPushes>().isStale() }
        )

        PushedVersions.KEY.setPersistentValue(directory, "1.18.4|26.2.5.21")

        assertTrue(read { project.service<ElixirLanguageLevelPushes>().isStale() })
    }

    fun testAChangedOtpVersionOfThePairedErlangSdkIsPushedAndReparses() {
        val elixirSdk = pairedElixirSdk("OTP repush", "/fake/elixir/otp-repush", "/fake/erlang/otp-repush")
        ModuleRootModificationUtil.setModuleSdk(module, elixirSdk)
        SdkVersionsStore.getInstance().setElixirVersions(
            "/fake/elixir/otp-repush",
            ElixirVersions("1.18.4", OtpMajor.Known("27"))
        )
        SdkVersionsStore.getInstance().setOtpVersion("/fake/erlang/otp-repush", "27.3.4")
        val file = elixirFile(module, "otp-repush")
        SdkFixtures.waitUntil("precondition: the store and roots changes above push their own versions", 30_000) {
            PushedVersions.KEY.getPersistentValue(file.parent) == "1.18.4|27.3.4"
        }
        val before = parsed(file)

        SdkVersionsStore.getInstance().setOtpVersion("/fake/erlang/otp-repush", "26.2.5.21")

        SdkFixtures.waitUntil("a new OTP version for the paired Erlang SDK must be pushed", timeoutMillis = 30_000) {
            PushedVersions.KEY.getPersistentValue(file.parent) == "1.18.4|26.2.5.21" &&
                PsiManager.getInstance(project).findFile(file) !== before
        }
    }

    private fun pairedElixirSdk(name: String, elixirHome: String, erlangHome: String): Sdk {
        val erlangSdk = register(SdkFixtures.erlangSdk("$name Erlang", erlangHome))
        val elixirSdk = register(SdkFixtures.elixirSdk("$name Elixir", elixirHome))
        SdkFixtures.commit(elixirSdk, SdkAdditionalData(erlangSdk, elixirSdk))
        return elixirSdk
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
