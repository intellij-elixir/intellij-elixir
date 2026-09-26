package org.elixir_lang.sdk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.mix.sync.MixSyncTestHelpers.runSuspendOnPooledThread
import org.elixir_lang.sdk.SdkFixtures.elixirHome
import org.elixir_lang.sdk.SdkFixtures.erlangHome
import org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class SdkVersionWatchServiceTest : PlatformTestCase() {
    override fun tearDown() {
        try {
            SdkVersionWatchService.beforeWatchRebuiltForTests = null
            ModuleRootModificationUtil.setModuleSdk(module, null)
            SdkVersionsStore.getInstance().clearForTests()
        } finally {
            super.tearDown()
        }
    }

    fun testEveryHomeTheStoreHoldsIsWatched() {
        val erlang = erlangHome("27", "27.3.4")
        val elixir = elixirHome("1.20.5")
        runSuspendOnPooledThread {
            SdkVersionsFiller.fill(erlang)
            SdkVersionsFiller.fill(elixir)
        }

        val expected = setOfNotNull(installationKey(erlang), installationKey(elixir))

        assertEquals(expected, SdkVersionWatchService.homesToWatch())
    }

    @RequiresEdt
    fun testRemovingTheOnlySdkOnAHomeForgetsIt() {
        SdkVersionWatchService.install(testRootDisposable)
        val home = erlangHome("27", "27.3.4")
        val erlangSdk = SdkFixtures.registerAndWaitForFill(
            SdkFixtures.erlangSdk("Removed Erlang", home),
            testRootDisposable,
        )
        SdkFixtures.waitUntil("precondition: adding the SDK reads its home") {
            SdkVersionsStore.getInstance().otpVersion(home) != null
        }

        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(erlangSdk) }

        SdkFixtures.waitUntil("an installation no SDK points at must stop being held, and so watched") {
            SdkVersionsStore.getInstance().otpVersion(home) == null
        }
    }

    @RequiresEdt
    fun testRemovingOneOfTwoSdksOnAHomeKeepsIt() {
        val home = elixirHome("1.20.5")
        val removed = SdkFixtures.registerAndWaitForFill(
            SdkFixtures.elixirSdk("Removed Elixir", home),
            testRootDisposable,
        )
        SdkFixtures.registerAndWaitForFill(SdkFixtures.elixirSdk("Kept Elixir", home), testRootDisposable)
        runSuspendOnPooledThread { SdkVersionsFiller.fill(home) }
        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(removed) }

        // Driven directly: removal hands this to a coroutine, and asserting that nothing happened needs to know it ran.
        runSuspendOnPooledThread { SdkVersionWatchService.forgetUnlessRegistered(home) }

        assertNotNull(
            "two Elixir SDKs pairing one build with different Erlang SDKs share a home",
            SdkVersionsStore.getInstance().elixirVersions(home),
        )
    }

    fun testWatchingRefusesToRunUnderAReadAction() {
        val home = erlangHome("27", "27.3.4")

        val refusal: Throwable? = runSuspendOnPooledThread {
            runCatching {
                ReadAction.runBlocking<Throwable> {
                    SdkVersionFileWatcher.watch(setOf(home), testRootDisposable) {}
                }
            }.exceptionOrNull()
        }

        assertNotNull("a synchronous VFS refresh must not be invoked while holding a read action", refusal)
    }

    fun testTheVersionFilesOfAHomeAreTheOnesWatched() {
        val home = erlangHome("27", "27.3.4")

        val watched = runSuspendOnPooledThread {
            SdkVersionFileWatcher.watch(setOf(home), testRootDisposable) {}
        }

        assertTrue(
            "the OTP_VERSION the version was read from must be among the watched paths; got $watched",
            watched.any { it.endsWith("/releases/27/OTP_VERSION") },
        )
    }

    @RequiresEdt
    fun testAnSdkAddedAfterInstallIsWatchedOffTheWriteAction() {
        val home = erlangHome("27", "27.3.4")
        val erlangSdk = SdkFixtures.erlangSdk("Watched After Add", home)
        val elixirSdk = SdkFixtures.registerAndWaitForFill(
            SdkFixtures.elixirSdk("Watching Elixir", elixirHome("1.20.5")),
            testRootDisposable,
        )
        SdkFixtures.commit(elixirSdk, SdkAdditionalData(erlangSdk, elixirSdk))
        ModuleRootModificationUtil.setModuleSdk(module, elixirSdk)
        SdkVersionWatchService.install(testRootDisposable)

        // The table publishes its add inside its own write action. Watching there trips the refusal inside
        // SdkVersionFileWatcher.watch on the publishing thread, which surfaces as a logged error, not an exception.
        val (_, errors) = captureLoggedErrors { SdkFixtures.registerAndWaitForFill(erlangSdk, testRootDisposable) }

        assertEmpty("adding an SDK must not watch inside the table's write action; got $errors", errors)
        val otpVersionFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath("${FileUtil.toSystemIndependentName(home)}/releases/27/OTP_VERSION")
        assertNotNull("precondition: the version file of the added SDK is in the VFS", otpVersionFile)
        // Rewritten on each pass: the add hands the watch to a coroutine there is nothing here to await.
        SdkFixtures.waitUntil("an SDK added after install must end up watched") {
            WriteAction.run<Throwable> { VfsUtil.saveText(otpVersionFile!!, "27.3.5\n") }
            SdkVersionsStore.getInstance().otpVersion(home) == "27.3.5"
        }
    }

    @RequiresEdt
    fun testAVersionFileThatChangesIsReadAgain() {
        val home = erlangHome("27", "27.3.4")
        val erlangSdk = SdkFixtures.registerAndWaitForFill(
            SdkFixtures.erlangSdk("Watched For Changes", home),
            testRootDisposable,
        )
        val elixirSdk = SdkFixtures.registerAndWaitForFill(
            SdkFixtures.elixirSdk("Watching Elixir", elixirHome("1.20.5")),
            testRootDisposable,
        )
        SdkFixtures.commit(elixirSdk, SdkAdditionalData(erlangSdk, elixirSdk))
        ModuleRootModificationUtil.setModuleSdk(module, elixirSdk)
        SdkVersionWatchService.install(testRootDisposable)
        // Driven rather than awaited: `install` starts the first watch on a coroutine, and a file rewritten before
        // that registration lands is a change nothing was watching for.
        runSuspendOnPooledThread {
            SdkVersionsFiller.fill(home)
            SdkVersionWatchService.rewatch()
        }

        val otpVersionFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath("${FileUtil.toSystemIndependentName(home)}/releases/27/OTP_VERSION")
        assertNotNull("precondition: the file the watch is registered on is in the VFS", otpVersionFile)
        // Through the VFS, because that is what publishes the change; a write behind its back is only noticed by a
        // refresh nothing in production would perform.
        WriteAction.run<Throwable> { VfsUtil.saveText(otpVersionFile!!, "27.3.5\n") }

        SdkFixtures.waitUntil("a version file that changed must be read again") {
            SdkVersionsStore.getInstance().otpVersion(home) == "27.3.5"
        }
    }

    @RequiresEdt
    fun testAVersionFileChangedWhileTheWatchIsRebuiltIsReadAgain() {
        val home = erlangHome("27", "27.3.4")
        SdkVersionWatchService.install(testRootDisposable)
        runSuspendOnPooledThread {
            SdkVersionsFiller.fill(home)
            SdkVersionWatchService.rewatch()
        }
        SdkFixtures.waitUntil("precondition: the watch on the first home is settled") {
            SdkVersionWatchService.isIdleForTests()
        }
        val otpVersionFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath("${FileUtil.toSystemIndependentName(home)}/releases/27/OTP_VERSION")!!
        SdkVersionWatchService.beforeWatchRebuiltForTests = {
            SdkVersionWatchService.beforeWatchRebuiltForTests = null
            WriteAction.runAndWait<Throwable> { VfsUtil.saveText(otpVersionFile, "27.3.5\n") }
        }

        // A second home makes the watch rebuild, and the hook changes the first home's file as it does.
        runSuspendOnPooledThread {
            SdkVersionsFiller.fill(elixirHome("1.20.5"))
            SdkVersionWatchService.rewatch()
        }

        SdkFixtures.waitUntil("a version file changed while the watch was rebuilt must be read again") {
            SdkVersionsStore.getInstance().otpVersion(home) == "27.3.5"
        }
    }

    @RequiresEdt
    fun testAnInstallationStillWatchesAfterAnEarlierInstallIsDisposed() {
        val home = erlangHome("27", "27.3.4")
        val first = Disposer.newDisposable(testRootDisposable, "first install")
        SdkVersionWatchService.install(first)
        SdkVersionWatchService.install(testRootDisposable)

        Disposer.dispose(first)
        // Read only now, so only the installation still live can build the watch on it.
        runSuspendOnPooledThread {
            SdkVersionsFiller.fill(home)
            SdkVersionWatchService.rewatch()
        }

        val otpVersionFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath("${FileUtil.toSystemIndependentName(home)}/releases/27/OTP_VERSION")
        assertNotNull("precondition: the file the watch is registered on is in the VFS", otpVersionFile)
        // A value no sibling test writes, so a fill one of them left running cannot satisfy the wait.
        assertFalse(
            "precondition: the asserted version is not already held",
            SdkVersionsStore.getInstance().otpVersion(home) == "27.3.6",
        )
        WriteAction.run<Throwable> { VfsUtil.saveText(otpVersionFile!!, "27.3.6\n") }

        SdkFixtures.waitUntil("the installation that is still live must go on reading version files") {
            SdkVersionsStore.getInstance().otpVersion(home) == "27.3.6"
        }
    }

    /**
     * The installation is replaced after it was read but before it was watched. The file is already in the VFS, so the
     * load the watch performs finds a cached entry - and `findChild` compares neither timestamp nor length.
     */
    @RequiresEdt
    fun testAnInstallationReplacedWhileNothingWatchedIsReadAgain() {
        val home = erlangHome("27", "27.3.4")
        val otpVersionFile = File(home, "releases/27/OTP_VERSION")
        // Loaded first, as reading it earlier would have left it.
        val loaded = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(FileUtil.toSystemIndependentName(otpVersionFile.path))
        assertNotNull("precondition: the version file is in the VFS before anything watches it", loaded)

        // Written behind the VFS's back: through the VFS would publish the change this test is asking the watch
        // to discover for itself.
        otpVersionFile.writeText("27.3.9\n")
        // Same length and within one timestamp tick, a refresh would see it unchanged.
        assertTrue(
            "precondition: the rewrite moved the timestamp",
            otpVersionFile.setLastModified(loaded!!.timeStamp + 2_000),
        )

        val revalidated = CopyOnWriteArrayList<String>()
        runSuspendOnPooledThread {
            SdkVersionFileWatcher.watch(setOf(home), testRootDisposable) { revalidated.add(it) }
        }

        SdkFixtures.waitUntil("a home replaced while nothing watched it must be read again once it is watched") {
            revalidated.isNotEmpty()
        }
    }

    /** An in-place upgrade adds `releases/28` beside `releases/27` and rewrites nothing that was watched. */
    @RequiresEdt
    fun testAReleaseAddedBesideTheWatchedOneIsReadAgain() {
        val home = erlangHome("27", "27.3.4")
        val revalidated = CopyOnWriteArrayList<String>()
        runSuspendOnPooledThread {
            SdkVersionFileWatcher.watch(setOf(home), testRootDisposable) { revalidated.add(it) }
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        revalidated.clear()

        File(home, "releases/28").mkdirs()
        File(home, "releases/28/OTP_VERSION").writeText("28.1\n")
        // What the file watcher does with a created path the VFS does not have: marks the nearest parent it has dirty.
        val releases = LocalFileSystem.getInstance()
            .findFileByPathIfCached("${FileUtil.toSystemIndependentName(home)}/releases")
        assertNotNull("precondition: watching loaded the directory holding the releases", releases)
        VfsUtil.markDirtyAndRefresh(false, false, false, releases)

        SdkFixtures.waitUntil("a release added beside the watched one must read its home again") {
            revalidated.isNotEmpty()
        }
    }

    /** A version file read while it is being written is blank; the write that completes it must still be read. */
    @RequiresEdt
    fun testAHomeReadWhileItsVersionFileWasBlankIsReadOnceItIsWritten() {
        val home = erlangHome("27", "27.3.4")
        File(home, "releases/27/OTP_VERSION").writeText("")
        SdkVersionWatchService.install(testRootDisposable)
        SdkFixtures.registerAndWaitForFill(SdkFixtures.erlangSdk("Blank Erlang", home), testRootDisposable)
        runSuspendOnPooledThread {
            SdkVersionsFiller.fill(home)
            SdkVersionWatchService.rewatch()
        }
        assertNull("precondition: a blank version file reports nothing", SdkVersionsStore.getInstance().otpVersion(home))

        val otpVersionFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath("${FileUtil.toSystemIndependentName(home)}/releases/27/OTP_VERSION")
        assertNotNull("precondition: the version file is in the VFS", otpVersionFile)
        WriteAction.run<Throwable> { VfsUtil.saveText(otpVersionFile!!, "27.3.7\n") }

        SdkFixtures.waitUntil("a home whose version file read blank must be read again once the file is written") {
            SdkVersionsStore.getInstance().otpVersion(home) == "27.3.7"
        }
    }

    /** The write that completes a blank version file can land before the watch loads it, which then fires no event. */
    @RequiresEdt
    fun testAHomeWhoseBlankVersionFileWasWrittenBeforeItWasWatchedIsRead() {
        val home = erlangHome("27", "27.3.4")
        val otpVersionFile = File(home, "releases/27/OTP_VERSION")
        otpVersionFile.writeText("")
        SdkVersionWatchService.install(testRootDisposable)
        // Behind the VFS's back, so the watch loads the complete file as though nothing had changed.
        SdkVersionWatchService.beforeWatchRebuiltForTests = { otpVersionFile.writeText("27.3.7\n") }

        SdkFixtures.registerAndWaitForFill(SdkFixtures.erlangSdk("Written Erlang", home), testRootDisposable)

        SdkFixtures.waitUntil("a home read blank must be read once its watch starts, in case the write came first") {
            SdkVersionsStore.getInstance().otpVersion(home) == "27.3.7"
        }
    }

    /** A candidate home read while choosing an SDK answers nothing either, and no removal ever forgets it. */
    fun testAHomeThatReadBlankIsNotWatchedOnceNoSdkUsesIt() {
        val home = erlangHome("27", "27.3.4")
        File(home, "releases/27/OTP_VERSION").writeText("")
        SdkVersionWatchService.install(testRootDisposable)
        runSuspendOnPooledThread { SdkVersionsFiller.fill(home) }

        rewatch()

        assertFalse(
            "a home no SDK uses must not stay watched",
            installationKey(home) in SdkVersionWatchService.homesToWatch(),
        )
    }

    /**
     * A home configured through a path that is not its canonical one, such as a symlink, is watched by its canonical
     * path while it reads blank, and must stop being watched when its SDK goes, which names the configured path.
     */
    @RequiresEdt
    fun testAHomeThatReadBlankIsForgottenByItsConfiguredPath() {
        val home = File(erlangHome("27", "27.3.4"))
        File(home, "releases/27/OTP_VERSION").writeText("")
        File(home.parentFile, "elsewhere").mkdirs()
        val configured = "${home.parentFile.path}/elsewhere/../${home.name}"
        SdkVersionWatchService.install(testRootDisposable)
        val sdk = SdkFixtures.registerAndWaitForFill(SdkFixtures.erlangSdk("Blank Erlang", configured), testRootDisposable)
        runSuspendOnPooledThread { SdkVersionsFiller.fill(configured) }
        rewatch()
        assertTrue(
            "precondition: the home is watched while it reads blank",
            installationKey(home.path) in SdkVersionWatchService.homesToWatch(),
        )

        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(sdk) }

        SdkFixtures.waitUntil("a home no SDK uses any more must not stay watched") {
            installationKey(home.path) !in SdkVersionWatchService.homesToWatch()
        }
    }

    /**
     * The SDK is registered before the watch is installed and assigned to the module after, so the table reports
     * nothing and only the module roots say its installation is now in use.
     */
    @RequiresEdt
    fun testAnAlreadyRegisteredSdkAssignedToAModuleIsWatched() {
        val home = erlangHome("27", "27.3.4")
        // Where the watch and the store stood after each step, for a failure that is otherwise only its end state.
        val timeline = mutableListOf(snapshot("start", home))
        val erlangSdk = SdkFixtures.registerAndWaitForFill(
            SdkFixtures.erlangSdk("Assigned Later Erlang", home),
            testRootDisposable,
        )
        timeline += snapshot("Erlang SDK registered", home)
        val elixirSdk = SdkFixtures.registerAndWaitForFill(
            SdkFixtures.elixirSdk("Assigned Later Elixir", elixirHome("1.20.5")),
            testRootDisposable,
        )
        timeline += snapshot("Elixir SDK registered", home)
        SdkFixtures.commit(elixirSdk, SdkAdditionalData(erlangSdk, elixirSdk))
        // Registering reads the home once startup has installed the SDK table listeners, and `install` would then
        // watch it.
        SdkVersionsStore.getInstance().clearForTests()
        SdkVersionWatchService.stopWatchingForTests()
        SdkVersionWatchService.install(testRootDisposable)
        SdkFixtures.waitUntil("precondition: the installation's first watch settles") {
            SdkVersionWatchService.isIdleForTests()
        }
        assertFalse(
            "precondition: nothing watches the home before a module uses it",
            installationKey(home) in SdkVersionWatchService.homesToWatch(),
        )
        assertNull(
            "precondition: nothing has read the home before a module uses it",
            SdkVersionsStore.getInstance().otpVersion(home),
        )
        timeline += snapshot("watch installed", home)

        ModuleRootModificationUtil.setModuleSdk(module, elixirSdk)
        timeline += snapshot("module SDK set", home)
        SdkFixtures.waitUntil("assigning a registered SDK to a module must read its installation") {
            SdkVersionsStore.getInstance().otpVersion(home) == "27.3.4"
        }

        val otpVersionFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByPath("${FileUtil.toSystemIndependentName(home)}/releases/27/OTP_VERSION")
        assertNotNull("precondition: the version file of the assigned SDK is in the VFS", otpVersionFile)
        assertFalse(
            "precondition: the asserted version is not already held",
            SdkVersionsStore.getInstance().otpVersion(home) == "27.4.1",
        )
        timeline += snapshot("version file found", home)
        // Rewritten on each pass: the assignment hands the rewatch to a coroutine there is nothing here to await.
        SdkFixtures.waitUntil(
            "assigning a registered SDK to a module must put its installation under watch\n  " +
                timeline.joinToString("\n  ") + "\nat the deadline"
        ) {
            WriteAction.run<Throwable> { VfsUtil.saveText(otpVersionFile!!, "27.4.1\n") }
            SdkVersionsStore.getInstance().otpVersion(home) == "27.4.1"
        }
    }

    private fun snapshot(step: String, erlangHome: String): String =
        "$step: Erlang home's OTP version ${SdkVersionsStore.getInstance().otpVersion(erlangHome)}; " +
            "fills idle: ${SdkVersionWatchService.isIdleForTests()}; watch: ${SdkVersionWatchService.describeForTests()}"

    /**
     * A value re-read changes the store without changing which homes it holds, and each rebuild lists `releases/` and
     * refreshes the VFS per home, so an unchanged set must cost nothing.
     */
    @RequiresEdt
    fun testRewatchingAnUnchangedSetDoesNotRebuildTheWatch() {
        val home = elixirHome("1.20.5")
        runSuspendOnPooledThread { SdkVersionsFiller.fill(home) }
        SdkVersionWatchService.install(testRootDisposable)

        // Settles `install`'s own first watch, which runs on a coroutine and could otherwise be the one that builds it.
        rewatch()

        assertFalse("a rewatch for a set already watched must not rebuild it", rewatch())
    }

    /**
     * A home skipped because its distribution is not installed is tried again once the distribution is, and not on every
     * rewatch before then: each rebuild does I/O for every home.
     */
    @RequiresEdt
    fun testAHomeThatCouldNotBeWatchedIsTriedAgainOnceItCanBe() {
        // A local home standing in for one in a distribution, so watching it once "installed" reads nothing remote.
        val home = erlangHome("27", "27.3.4")
        var installed = false
        val mock = MockWslCompatService()
        val wsl = object : WslCompatService by mock {
            override fun isReachable(path: String): Boolean =
                if (installationKey(path) == installationKey(home)) installed else mock.isReachable(path)
        }
        ApplicationManager.getApplication()
            .registerOrReplaceServiceInstance(WslCompatService::class.java, wsl, testRootDisposable)
        SdkVersionsStore.getInstance().setOtpVersion(home, "27.3.4")
        SdkVersionWatchService.install(testRootDisposable)
        rewatch()

        assertFalse("nothing changed, so nothing is rebuilt", rewatch())

        installed = true
        assertTrue("a home the last rewatch could not watch is watched once it can be", rewatch())
    }

    private fun rewatch(): Boolean = runSuspendOnPooledThread { SdkVersionWatchService.rewatch() }

}
