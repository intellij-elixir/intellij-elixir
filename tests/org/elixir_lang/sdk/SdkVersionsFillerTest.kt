package org.elixir_lang.sdk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.io.FileUtil
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.testFramework.registerOrReplaceServiceInstance
import org.elixir_lang.junit.onPooledThread
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.mix.sync.MixSyncTestHelpers.runSuspendOnPooledThread
import org.elixir_lang.sdk.SdkFixtures.elixirHome
import org.elixir_lang.sdk.SdkFixtures.erlangHome
import org.elixir_lang.sdk.elixir.ElixirVersions
import org.elixir_lang.sdk.elixir.OtpMajor
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData as ElixirSdkAdditionalData
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

// Absolute on every OS: a relative home is never read. It need not exist, as the resolving service stands in for it.
private val CONFIGURED_HOME = File(FileUtil.getTempDirectory(), "fake/erlang/latest").path
private const val NOT_INSTALLED_WSL_HOME = "//wsl.localhost/IntellijElixirWSLDistribution/home/user/erlang"

class SdkVersionsFillerTest : PlatformTestCase() {
    private val store get() = SdkVersionsStore.getInstance()

    override fun setUp() {
        super.setUp()
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            MockWslCompatService(),
            testRootDisposable,
        )
    }

    override fun tearDown() {
        try {
            ModuleRootModificationUtil.setModuleSdk(module, null)
            store.clearForTests()
        } finally {
            super.tearDown()
        }
    }

    fun testFillingAnElixirHomeStoresItsVersions() {
        val home = elixirHome("1.20.0-rc.0")

        assertTrue("a home that was not known is filled", fill(home))

        assertEquals(ElixirVersions("1.20.0-rc.0", OtpMajor.None), store.elixirVersions(home))
    }

    fun testFillingAnErlangHomeStoresItsOtpVersion() {
        val home = erlangHome("27", "27.0-rc1")

        assertTrue(fill(home))

        assertEquals("27.0-rc1", store.otpVersion(home))
    }

    fun testFillingAHomeThatReportsNothingStoresNothing() {
        val home = FileUtil.createTempDirectory("empty_home", null, true).path

        assertFalse("a home with no version files has nothing to store", fill(home))

        assertNull(store.elixirVersions(home))
        assertNull(store.otpVersion(home))
    }

    fun testFillingAgainAfterTheInstallIsReplacedStoresTheNewVersion() {
        val home = erlangHome("27", "27.3.4")
        fill(home)

        // What a distribution package does: the same path, a different version.
        File(home, "releases/27/OTP_VERSION").writeText("27.3.5\n")

        assertTrue("a replaced install is a change", fill(home))
        assertEquals("27.3.5", store.otpVersion(home))
    }

    fun testAFailedReadKeepsWhatWasReadBefore() {
        val home = erlangHome("27", "27.3.4")
        fill(home)
        assertTrue(File(home, "releases/27/OTP_VERSION").delete())

        assertFalse("nothing said the installation changed, so a failed read is not news", fill(home))

        assertEquals("27.3.4", store.otpVersion(home))
    }

    fun testFillingAgainWithNoChangeReportsNoChange() {
        val home = elixirHome("1.19.5")
        fill(home)

        assertFalse("nothing changed, so nothing is written", fill(home))
    }

    fun testFillingStoresTheInstallationTheHomeResolvesTo() {
        val realHome = erlangHome("27", "27.3.4")
        val configuredHome = CONFIGURED_HOME
        // What mise does: `latest` is a symlink to the version.
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            object : WslCompatService by MockWslCompatService() {
                override fun canonicalizePath(path: String): String =
                    if (path == configuredHome) realHome else path
            },
            testRootDisposable,
        )

        assertTrue(fill(configuredHome))

        assertEquals("the installation the symlink resolves to", "27.3.4", store.otpVersion(realHome))
        assertEquals(
            "and the home the SDK is configured with, so a read never has to resolve it",
            "27.3.4",
            store.otpVersion(configuredHome),
        )
    }

    @RequiresEdt
    fun testFillingTheSdksAProjectUsesCoversThePairedErlangSdk() {
        val erlangSdk = SdkFixtures.registerAndWaitForFill(
            SdkFixtures.erlangSdk("Filler Erlang", erlangHome("26", "26.2.5.21")),
            testRootDisposable,
        )
        val elixirSdk = SdkFixtures.registerAndWaitForFill(
            SdkFixtures.elixirSdk("Filler Elixir", elixirHome("1.18.4")),
            testRootDisposable,
        )
        SdkFixtures.commit(elixirSdk, ElixirSdkAdditionalData(erlangSdk, elixirSdk))
        ModuleRootModificationUtil.setModuleSdk(module, elixirSdk)

        runSuspendOnPooledThread { SdkVersionsFiller.fillUsedBy(project) }

        assertEquals(ElixirVersions("1.18.4", OtpMajor.None), store.elixirVersions(elixirSdk.homePath))
        assertEquals(
            "the SDK the module uses is paired with this one",
            "26.2.5.21",
            store.otpVersion(erlangSdk.homePath),
        )
    }

    @RequiresEdt
    fun testFillingWhatAProjectUsesSkipsAnInstallationAlreadyRead() {
        val erlangHomePath = erlangHome("27", "27.3.4")
        val elixirHomePath = elixirHome("1.19.5")
        val erlangSdk = SdkFixtures.registerAndWaitForFill(
            SdkFixtures.erlangSdk("Filler read once Erlang", erlangHomePath),
            testRootDisposable,
        )
        val elixirSdk = SdkFixtures.registerAndWaitForFill(
            SdkFixtures.elixirSdk("Filler read once Elixir", elixirHomePath),
            testRootDisposable,
        )
        SdkFixtures.commit(elixirSdk, ElixirSdkAdditionalData(erlangSdk, elixirSdk))
        ModuleRootModificationUtil.setModuleSdk(module, elixirSdk)
        runSuspendOnPooledThread { SdkVersionsFiller.fillUsedBy(project) }
        val used = setOf(erlangHomePath, elixirHomePath).mapTo(HashSet(), FileUtil::toSystemIndependentName)
        val reads = AtomicInteger()
        // Only the second fill's reads are counted: the one `setModuleSdk` queued may still be resolving a home, and
        // the mock replaces an application-level service that a fill another test left running can land on too.
        val secondFill = ThreadLocal.withInitial { false }
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            object : WslCompatService by MockWslCompatService() {
                override fun canonicalizePath(path: String): String {
                    if (secondFill.get() && FileUtil.toSystemIndependentName(path) in used) reads.incrementAndGet()
                    return path
                }
            },
            testRootDisposable,
        )

        // A second project opening, using the same installations.
        runSuspendOnPooledThread {
            withContext(secondFill.asContextElement(true)) { SdkVersionsFiller.fillUsedBy(project) }
        }

        assertEquals("what the store already holds is the record of what was read", 0, reads.get())
    }

    fun testAFailedReadOfOneVersionKeepsThatVersion() {
        // A home that reports both kinds, so one read can fail while the other succeeds.
        val home = elixirHome("1.19.5")
        val releases = File(home, "releases/27")
        assertTrue("Failed to create $releases", releases.mkdirs())
        File(releases, "OTP_VERSION").writeText("27.3.4\n")
        fill(home)
        assertEquals("precondition: both were read", "27.3.4", store.otpVersion(home))

        assertTrue(File(home, "releases/27/OTP_VERSION").delete())

        fill(home)

        assertEquals(
            "the Elixir read succeeding does not make the failed OTP read news",
            "27.3.4",
            store.otpVersion(home),
        )
    }

    fun testAnUnreadableBeamKeepsTheOtpMajorAlreadyStored() {
        // The home has elixir.app but no Elixir.System.beam, so the Elixir version reads and the OTP major does not.
        val home = elixirHome("1.19.5")
        store.setElixirVersions(home, ElixirVersions("1.19.5", OtpMajor.Known("27")))

        fill(home)

        assertEquals(
            "a beam that could not be read is not the build saying it has no OTP major",
            ElixirVersions("1.19.5", OtpMajor.Known("27")),
            store.elixirVersions(home),
        )
    }

    fun testAWatchedChangeKeepsTheOtpMajorWhenTheBeamIsPresentButUnreadable() {
        val home = elixirHome("1.19.5")
        // Present and not parseable: a corrupt or half-written file, not a build that reports no major.
        File(home, "lib/elixir/ebin/Elixir.System.beam").writeText("not a beam")
        store.setElixirVersions(home, ElixirVersions("1.19.5", OtpMajor.Known("27")))

        fill(home, clearWhenUnreadable = true)

        assertEquals(
            "a beam that is there but unreadable is a failed read, not an answer",
            ElixirVersions("1.19.5", OtpMajor.Known("27")),
            store.elixirVersions(home),
        )
    }

    fun testAWatchedChangeClearsTheOtpMajorWhenTheBeamIsGone() {
        val home = elixirHome("1.19.5")
        store.setElixirVersions(home, ElixirVersions("1.19.5", OtpMajor.Known("27")))

        fill(home, clearWhenUnreadable = true)

        assertEquals(
            "no beam at all is the installation saying it reports no OTP major",
            ElixirVersions("1.19.5", OtpMajor.None),
            store.elixirVersions(home),
        )
    }

    fun testAFirstReadOfAnUnreadableBeamRecordsThatNothingWasLearned() {
        val home = elixirHome("1.19.5")
        // Present and not parseable, read without a file event as registration and startup read it.
        val beam = File(home, "lib/elixir/ebin/Elixir.System.beam")
        beam.writeText("not a beam")
        assertTrue("precondition: the read has to fail on a beam that is there", beam.exists())

        fill(home)

        assertEquals(
            "a read that failed is not the build saying it has no OTP major",
            OtpMajor.Unread,
            store.elixirVersions(home)?.elixirOtpMajor,
        )
    }

    @RequiresEdt
    fun testFillingTheSdksAProjectUsesReadsAgainForAMajorNothingLearned() {
        val home = elixirHome("1.19.5")
        val elixirSdk = SdkFixtures.registerAndWaitForFill(
            SdkFixtures.elixirSdk("Unread Major", home),
            testRootDisposable,
        )
        ModuleRootModificationUtil.setModuleSdk(module, elixirSdk)
        store.setElixirVersions(home, ElixirVersions("1.19.5", OtpMajor.Unread))

        runSuspendOnPooledThread { SdkVersionsFiller.fillUsedBy(project) }

        assertEquals(
            "a home recorded with nothing learned about its major has not been read yet",
            OtpMajor.None,
            store.elixirVersions(home)?.elixirOtpMajor,
        )
    }

    fun testAClearingFillOfAHomeThatLostEverythingForgetsIt() {
        val home = erlangHome("27", "27.3.4")
        fill(home)
        assertTrue("precondition: held", installationKey(home) in store.homes())

        // The whole installation, not just its version file: only a home that is gone may be forgotten.
        assertTrue(File(home).deleteRecursively())
        fill(home, clearWhenUnreadable = true)

        assertEmpty(
            "an installation that reports nothing is forgotten, not held as an empty entry that is read again",
            store.homes().filter { it == installationKey(home) },
        )
    }

    /**
     * A file event is not proof the installation went: a forced VFS refresh reports a path it cannot stat, such as a
     * home on a stopped WSL distro, as deleted.
     */
    fun testAClearingFillOfAHomeThatIsStillThereKeepsItsVersions() {
        val home = erlangHome("27", "27.3.4")
        fill(home)
        // Its version file goes, the installation directory stays, as a half-written upgrade leaves it.
        assertTrue(File(home, "releases/27/OTP_VERSION").delete())
        assertTrue("precondition: the home itself is still there", File(home).isDirectory)

        assertFalse(
            "nothing proved the installation went, so nothing is forgotten",
            fill(home, clearWhenUnreadable = true),
        )

        assertEquals("the version read before still stands", "27.3.4", store.otpVersion(home))
    }

    fun testForgettingAnInstallationNamesTheInstallationItForgot() {
        val realHome = erlangHome("27", "27.3.4")
        val configuredHome = CONFIGURED_HOME
        resolving(to = realHome)
        fill(configuredHome)
        // Taken from the notification itself: a real listener works off the publishing thread, by when the store's
        // entry is gone.
        val named = mutableListOf<String>()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(SdkVersionsListener.TOPIC, SdkVersionsListener { _, canonicalHome -> named.add(canonicalHome) })

        assertTrue(File(realHome).deleteRecursively())
        fill(configuredHome, clearWhenUnreadable = true)

        assertEquals(
            "a home configured through a symlink is forgotten under the installation it resolved to",
            listOf(installationKey(realHome)),
            named,
        )
        assertEquals(
            "what makes that worth carrying: asking afterwards answers with the home itself",
            installationKey(configuredHome),
            store.canonicalHome(configuredHome),
        )
    }

    fun testForgettingAnInstallationCarriesEverySpellingItWasHeldUnder() {
        val realHome = erlangHome("27", "27.3.4")
        val configuredHome = CONFIGURED_HOME
        resolving(to = realHome)
        fill(configuredHome)
        val spellings = mutableListOf<Set<String>>()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(
                SdkVersionsListener.TOPIC,
                SdkVersionsListener { homePaths, _ -> spellings.add(homePaths) },
            )

        assertTrue(File(realHome).deleteRecursively())
        fill(configuredHome, clearWhenUnreadable = true)

        assertEquals(
            "one notification carrying every spelling it was held under",
            listOf(setOfNotNull(installationKey(realHome), installationKey(configuredHome))),
            spellings,
        )
    }

    fun testAFirstReadOfAHomeWithNoBeamRecordsThatTheBuildHasNoMajor() {
        val home = elixirHome("1.19.5")

        fill(home)

        assertEquals(
            "no beam at all is the installation answering, so it is recorded as an answer",
            OtpMajor.None,
            store.elixirVersions(home)?.elixirOtpMajor,
        )
    }

    fun testFillingPublishesWhenTheInstallationAHomeResolvesToChanges() {
        val realHome = erlangHome("27", "27.3.4")
        val configuredHome = CONFIGURED_HOME
        resolving(to = realHome)
        fill(configuredHome)
        val published = published()

        // What a WSL outage does: resolving falls back to the configured spelling, re-keying the entry to itself.
        resolving(to = configuredHome)
        fill(configuredHome)

        assertFalse(
            "the installation a home belongs to changed, and reparsing keys off it",
            published.isEmpty(),
        )
    }

    fun testAClearingFillOfAHomeTheStoreNeverHeldPublishesNothing() {
        val home = FileUtil.createTempDirectory("empty_home", null, true).path
        val published = published()

        assertFalse(
            "nothing was read and nothing was held, so nothing changed",
            fill(home, clearWhenUnreadable = true),
        )
        assertEmpty("an entry that went from absent to empty must not reparse the project", published)
    }

    fun testFillingAnInstallationWithTwoSpellingsPublishesOnce() {
        val realHome = erlangHome("27", "27.3.4")
        val configuredHome = CONFIGURED_HOME
        resolving(to = realHome)
        val published = published()

        fill(configuredHome)

        assertEquals(
            "one installation read once is one change, however many spellings it is stored under",
            1,
            published.size,
        )
    }

    /** Registers a [WslCompatService] that resolves [CONFIGURED_HOME] to [to], as mise's `latest` symlink does. */
    private fun resolving(to: String) {
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            object : WslCompatService by MockWslCompatService() {
                override fun canonicalizePath(path: String): String = if (path == CONFIGURED_HOME) to else path

                override fun canonicalizePathNullable(path: String?): String? =
                    if (path == CONFIGURED_HOME) to else path
            },
            testRootDisposable,
        )
    }

    fun testABlockingFillReadsAnUnreadHomeOffTheEdt() {
        val home = erlangHome("27", "27.3.4")

        onPooledThread { SdkVersionsFiller.fillIfUnreadBlocking(home) }

        assertEquals("27.3.4", store.otpVersion(home))
    }

    fun testABlockingFillReadsNothingUnderAReadLock() {
        // A platform hook can be reached inside a read action, where resolving a WSL home would hold the lock
        // across a distro boot. The caller answers from the store instead.
        val home = erlangHome("27", "27.3.4")

        onPooledThread {
            ReadAction.nonBlocking(Callable { SdkVersionsFiller.fillIfUnreadBlocking(home) }).executeSynchronously()
        }

        assertNull(store.otpVersion(home))
    }

    @RequiresEdt
    fun testABlockingFillOnTheEdtReadsUnderModalProgress() {
        val home = erlangHome("27", "27.3.4")

        SdkVersionsFiller.fillIfUnreadBlocking(home)

        assertEquals("27.3.4", store.otpVersion(home))
    }

    @RequiresEdt
    fun testABlockingFillReadsNothingUnderAWriteLock() {
        // `holdsReadLock` is false under a write lock, so this refusal is separate from the read-lock one.
        val home = erlangHome("27", "27.3.4")

        WriteAction.run<Throwable> { SdkVersionsFiller.fillIfUnreadBlocking(home) }

        assertNull(store.otpVersion(home))
    }

    /**
     * On the EDT a read action holds no read permit of its own, so the refusal before the modal progress lets it
     * through. Before 2026.3 the progress is then handed one, which only the check inside it can see; from 2026.3 it
     * shares the EDT's write-intent instead (IJPL-253986), and the version is read with no read lock held.
     */
    @RequiresEdt
    fun testABlockingFillInsideAReadActionOnTheEdtReadsOnlyWithoutAReadLock() {
        val home = erlangHome("27", "27.3.4")
        val app = ApplicationManager.getApplication()
        val progressHoldsAReadLock = app.runReadAction(Computable {
            runWithModalProgressBlocking(ModalTaskOwner.guess(), "") { app.holdsReadLock() }
        })

        app.runReadAction { SdkVersionsFiller.fillIfUnreadBlocking(home) }

        if (progressHoldsAReadLock) {
            assertNull("modal progress holds a read lock here, so nothing may be read", store.otpVersion(home))
        } else {
            assertEquals("modal progress holds no read lock here, so the version is read", "27.3.4", store.otpVersion(home))
        }
    }

    private fun published(): MutableList<String> {
        val published = CopyOnWriteArrayList<String>()
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
            .subscribe(
                SdkVersionsListener.TOPIC,
                SdkVersionsListener { _, canonicalHome -> published.add(canonicalHome) },
            )
        return published
    }

    /** Anything under a distribution this machine does not have waits out a network timeout on Windows. */
    fun testAHomeInAWslDistributionThatIsNotInstalledIsNotRead() {
        val millis = measureTimeMillis { assertFalse(fill(NOT_INSTALLED_WSL_HOME)) }

        assertTrue("a home in a distribution this machine does not have was read: $millis ms", millis < 5_000)
    }

    fun testABlockingFillOfAHomeInAWslDistributionThatIsNotInstalledReadsNothing() {
        val millis = measureTimeMillis { fillTheNotInstalledWslHomeBlockingOffTheEdt() }

        assertTrue("a home in a distribution this machine does not have was read: $millis ms", millis < 5_000)
        assertNull(store.otpVersion(NOT_INSTALLED_WSL_HOME))
    }

    /** An SDK created without a home has `""`, which resolves to the JVM's working directory. */
    fun testABlankHomeIsNotRead() {
        assertEquals(emptyList<String>(), pathsTouchedFilling(""))
    }

    fun testARelativeHomeIsNotRead() {
        assertEquals(emptyList<String>(), pathsTouchedFilling("erlang"))
    }

    fun testAHomeResolvedToABlankPathIsNotRead() {
        assertEquals(emptyList<String>(), pathsTouchedBy(::fillCanonicalBlankHome))
    }

    fun testABlockingFillOfABlankHomeReadsNothing() {
        assertEquals(emptyList<String>(), pathsTouchedBy(::fillBlankHomeBlockingOffTheEdt))
    }

    // Out of the test methods: JUnit 3 runs a nested lambda's `test...$lambda$0$0` method as a test of its own.
    private fun fillCanonicalBlankHome() {
        runSuspendOnPooledThread { SdkVersionsFiller.fillCanonical("") }
    }

    private fun fillBlankHomeBlockingOffTheEdt() {
        onPooledThread(TimeUnit.SECONDS.toMillis(30)) { SdkVersionsFiller.fillIfUnreadBlocking("") }
    }

    private fun pathsTouchedFilling(homePath: String): List<String> = pathsTouchedBy { fill(homePath) }

    /** Every path [block] resolved or probed for version files. */
    private fun pathsTouchedBy(block: () -> Unit): List<String> {
        val touched = CopyOnWriteArrayList<String>()
        val mock = MockWslCompatService()
        val spy = object : WslCompatService by mock {
            override fun canonicalizePath(path: String): String = mock.canonicalizePath(path).also { touched += path }

            override fun exists(file: File): Boolean = mock.exists(file).also { touched += file.path }
        }
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(WslCompatService::class.java, spy, testRootDisposable)

        block()

        return touched.toList()
    }

    private fun fillTheNotInstalledWslHomeBlockingOffTheEdt() {
        onPooledThread(TimeUnit.SECONDS.toMillis(30)) { SdkVersionsFiller.fillIfUnreadBlocking(NOT_INSTALLED_WSL_HOME) }
    }

    private fun fill(homePath: String, clearWhenUnreadable: Boolean = false): Boolean =
        runSuspendOnPooledThread { SdkVersionsFiller.fill(homePath, clearWhenUnreadable) }

}
