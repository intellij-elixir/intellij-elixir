package org.elixir_lang.mix

import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.common.runAll
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.mix.sync.MixSyncTestHelpers
import org.elixir_lang.notification.setup_sdk.Notifier
import org.elixir_lang.package_manager.*
import org.elixir_lang.package_manager.DepGatherer
import org.elixir_lang.settings.ElixirExperimentalSettings
import org.elixir_lang.PackageManager as RootPackageManager

/**
 * Shared test infrastructure for [DepsCheckerService] test classes.
 *
 * Provides:
 * - a fake `packageManager` EP that delegates to [statusByRootUrl] and records call counts,
 * - [setUp] that turns `enableMixDepsCheck` off for the test, masks the EP, waits for a check
 *   already running, clears state, and resets the service,
 * - [tearDown] that clears the outdated notification and resets the service before delegating
 *   to the platform teardown, then restores the setting,
 * - [depsStatus], [depsStatusCallCount], [resetDepsStatusCallCounts],
 * - [withMixDepsCheckEnabled] with an `enabled` parameter (defaults `true`),
 * - [runSuspendOnPooledThread] and [waitUntil] wait helpers.
 *
 * Subclasses add their own fixture topology and any helpers specific to the scenario
 * under test (e.g. facet management, VFS event firing, SDK registration).
 */
abstract class DepsCheckerServiceTestBase : PlatformTestCase() {

    private val packageManagerEp =
        ExtensionPointName.create<RootPackageManager>("org.elixir_lang.packageManager")

    /** Keyed by root URL. Set by each test before invoking the service. */
    protected val statusByRootUrl = mutableMapOf<String, DepsStatusResult>()

    /** Keyed by root URL. Accumulates across `checkDepsStatus` calls within one test. */
    protected val depsStatusCallCountByRootUrl = mutableMapOf<String, Int>()

    private val fakePackageManager = object : RootPackageManager {
        override val fileName: String = "mix.exs"

        override fun depGatherer(): DepGatherer = object : DepGatherer() {}

        override fun depsStatus(
            project: com.intellij.openapi.project.Project,
            packageVirtualFile: com.intellij.openapi.vfs.VirtualFile,
            sdk: Sdk?,
        ): DepsStatusResult {
            val rootUrl = packageVirtualFile.parent?.url ?: return DepsStatusResult.Unsupported
            depsStatusCallCountByRootUrl[rootUrl] = (depsStatusCallCountByRootUrl[rootUrl] ?: 0) + 1
            return statusByRootUrl[rootUrl] ?: DepsStatusResult.Unsupported
        }
    }

    private var originalMixDepsCheckEnabled: Boolean? = null

    override fun setUp() {
        super.setUp()
        // The service outlives the test in the shared light project, so a debounced check an
        // earlier test scheduled can still run; with the setting off it returns before touching
        // the pending roots, the cache or the call counts.
        val state = ElixirExperimentalSettings.instance.state
        originalMixDepsCheckEnabled = state.enableMixDepsCheck
        state.enableMixDepsCheck = false
        ExtensionTestUtil.maskExtensions(packageManagerEp, listOf(fakePackageManager), testRootDisposable)
        val service = project.service<DepsCheckerService>()
        // Queues behind a check that passed the setting gate before it went off: `resetState`
        // must not race one.
        runSuspendOnPooledThread { service.checkDepsStatus("settle") }
        statusByRootUrl.clear()
        depsStatusCallCountByRootUrl.clear()
        service.resetState()
    }

    /**
     * Clears the outdated notification and resets the service before delegating to the
     * platform teardown. Subclasses should call `super.tearDown()` as the last item in
     * their own `runAll` block so that this cleanup runs after subclass-specific teardown.
     */
    override fun tearDown() {
        runAll(
            { Notifier.clearMixDepsOutdated(project) },
            { project.service<DepsCheckerService>().resetState() },
            { super.tearDown() },
            // Last, so the shared module's restore and the SDK removals run with the listeners off.
            { originalMixDepsCheckEnabled?.let { ElixirExperimentalSettings.instance.state.enableMixDepsCheck = it } },
        )
    }

    // ── Shared test helpers ───────────────────────────────────────────────────

    protected fun depsStatus(depState: DepState): DepsStatusResult.Available =
        DepsStatusResult.Available(DepsStatus(listOf(DepStatus("phoenix", depState))))

    protected fun depsStatusCallCount(root: com.intellij.openapi.vfs.VirtualFile): Int =
        depsStatusCallCountByRootUrl[root.url] ?: 0

    protected fun resetDepsStatusCallCounts() {
        depsStatusCallCountByRootUrl.clear()
    }

    /**
     * Temporarily overrides the `enableMixDepsCheck` setting, which [setUp] turns off, for the
     * duration of [block].
     *
     * A test that checks the pending roots itself wraps only the event it publishes: the
     * listener schedules its work before the publish returns, and the debounced check it
     * schedules then finds the setting off, so the test's own
     * [DepsCheckerService.checkDepsStatus] is the only check that takes the pending roots.
     */
    protected fun withMixDepsCheckEnabled(enabled: Boolean = true, block: () -> Unit) {
        val state = ElixirExperimentalSettings.instance.state
        val original = state.enableMixDepsCheck
        state.enableMixDepsCheck = enabled
        try {
            block()
        } finally {
            state.enableMixDepsCheck = original
        }
    }

    /**
     * Runs a suspending [block] on a pooled thread while the EDT (test thread) pumps
     * its event queue. Delegates to [MixSyncTestHelpers.runSuspendOnPooledThread].
     *
     * Required whenever the block dispatches work back to the EDT (via
     * [com.intellij.openapi.application.readAction] or
     * [com.intellij.openapi.application.edtWriteAction]), which would otherwise deadlock
     * if [kotlinx.coroutines.runBlocking] were called directly on the EDT test thread.
     */
    protected fun <T> runSuspendOnPooledThread(block: suspend () -> T): T =
        MixSyncTestHelpers.runSuspendOnPooledThread(block = block)

    /**
     * Polls [condition] in a tight loop, pumping the IDE event queue on each iteration
     * and yielding briefly between polls.
     *
     * Pumping lets EDT-dispatched work from the `checkFlow` collector (e.g. the
     * `withContext(Dispatchers.EDT)` notification dispatch inside [DepsCheckerService.runCheck])
     * execute while the test thread waits. The short sleep reduces CPU spin while still
     * reacting quickly enough for both topology-change (sub-second) and debounced (1.5 s+)
     * scenarios.
     *
     * Fails with [message] if [condition] does not become `true` within [timeoutMs].
     */
    protected fun waitUntil(message: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(message)
            }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(20)
        }
    }
}
