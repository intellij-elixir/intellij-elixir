package org.elixir_lang.mix

import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.facet.impl.FacetUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import org.elixir_lang.Facet
import org.elixir_lang.mix.sync.MixTestFixtures
import org.elixir_lang.notification.setup_sdk.Notifier
import org.elixir_lang.package_manager.*
import org.elixir_lang.facet.Type as ElixirFacetType

/**
 * End-to-end integration tests for [DepsCheckerService] that exercise the real VFS event path:
 *
 * `VFS_CHANGES` publish -> `BulkFileListener.after` -> `pendingRoots` update +
 * `checkFlow.tryEmit` -> debounce(1500 ms) -> `runCheck` -> `rootStatusCache` update + `Notifier` call.
 *
 * Assertions are outcome-based: `pendingRootUrls`, cached status, notification state.
 * No assertion couples to internal coroutine scheduling order or exact timing.
 *
 * The Elixir facet is required so that `Module.isElixirMixModule()` returns `true` and
 * `MixEventClassifier.elixirMixContentRoots` includes the test module's roots.
 * Without the facet the classifier filters every root out and nothing is enqueued.
 *
 * The real 1500 ms debounce fires in wall time; [waitUntil] (from [DepsCheckerServiceTestBase])
 * absorbs any CI jitter with a 10 s deadline while continuously pumping the IDE event queue.
 *
 * Shared infrastructure (fake package manager, [runSuspendOnPooledThread], [waitUntil],
 * [withMixDepsCheckEnabled], etc.) lives in [DepsCheckerServiceTestBase].
 */
class DepsCheckerServiceVfsIntegrationTest : DepsCheckerServiceTestBase() {

    override fun setUp() {
        super.setUp()
        ensureElixirFacet()
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Publishes a real `VFS_CHANGES` event under `rootA/deps/`, drives the full debounced
     * `checkFlow` pipeline, and asserts externally visible outcomes:
     * - `rootStatusCache` reflects OUTDATED for `rootA` and still-OK for `rootB` (sibling root),
     * - `Notifier` shows an active outdated notification,
     * - `rootB` was **not** re-queried (its cached status was reused).
     */
    fun testVfsDepsChange_drivesDebouncedCheck_updatesVerdictNotificationAndCache() {
        // ── Arrange: two roots, both initially OK ─────────────────────────────
        val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val rootB = MixTestFixtures.createMixRoot(myFixture, "project_b")
        statusByRootUrl[rootA.url] = depsStatus(DepState.OK)
        statusByRootUrl[rootB.url] = depsStatus(DepState.OK)

        val service = project.service<DepsCheckerService>()

        // Prime both roots into the cache so rootB has a cached entry.
        runSuspendOnPooledThread { service.checkDepsStatus("prime") }
        resetDepsStatusCallCounts()

        // Flip rootA to OUTDATED before the VFS event arrives.
        statusByRootUrl[rootA.url] = depsStatus(DepState.OUTDATED)

        withMixDepsCheckEnabled {
            // ── Act: fire a real VFS event under rootA/deps/ ──────────────────
            // Create a file under rootA/deps/ - the VFS write action fires VFS_CHANGES
            // synchronously, which BulkFileListener.after processes while still under
            // the write lock (which subsumes the read lock, satisfying @RequiresReadLock).
            fireVfsDepsEvent("project_a", "deps/phoenix/lib/x.ex")

            // ── Assert 1: debounce pipeline fires and produces an outdated notification ──
            waitUntil("debounced checkFlow should produce a NonOk verdict and fire the outdated notification") {
                Notifier.hasActiveMixDepsOutdatedNotification(project)
            }

            // ── Assert 2: post-run state is consistent ────────────────────────
            assertTrue(
                "pendingRootUrls should be empty after checkDepsStatus drains the pending set",
                service.pendingRootUrls.isEmpty(),
            )
            assertEquals(
                "rootA should be cached as NonOk after the debounced check",
                CachedRootStatus.NonOk,
                service.cachedStatusForUrl(rootA.url),
            )
            assertEquals(
                "rootB should still be cached as Ok (not re-queried by a rootA-only event)",
                CachedRootStatus.Ok,
                service.cachedStatusForUrl(rootB.url),
            )
            assertEquals(
                "rootB must not be re-queried when only rootA is in pendingRoots",
                0,
                depsStatusCallCountByRootUrl[rootB.url] ?: 0,
            )
        }
    }

    /**
     * After `root` becomes OUTDATED via a real VFS event and the notification fires,
     * a second VFS event under `root/deps/` (after the fake status reverts to OK)
     * drives the debounced pipeline again and clears the notification.
     */
    fun testVfsDepsChange_recoveryClearsOutdatedNotification() {
        val root = MixTestFixtures.createMixRoot(myFixture, "project_recover")
        statusByRootUrl[root.url] = depsStatus(DepState.OK)

        val service = project.service<DepsCheckerService>()

        runSuspendOnPooledThread { service.checkDepsStatus("prime") }
        resetDepsStatusCallCounts()

        withMixDepsCheckEnabled {
            // ── Phase 1: drive the root to OUTDATED via VFS event ────────────
            statusByRootUrl[root.url] = depsStatus(DepState.OUTDATED)
            fireVfsDepsEvent("project_recover", "deps/ecto/lib/ecto.ex")

            waitUntil("outdated notification should be active after first VFS event reaches debounce") {
                Notifier.hasActiveMixDepsOutdatedNotification(project)
            }

            // ── Phase 2: recover - flip to OK, fire another VFS event ────────
            statusByRootUrl[root.url] = depsStatus(DepState.OK)
            fireVfsDepsEvent("project_recover", "deps/ecto/lib/ecto.ex")

            waitUntil("outdated notification should be cleared after second VFS event + debounce") {
                !Notifier.hasActiveMixDepsOutdatedNotification(project)
            }

            assertEquals(
                "root should be cached as Ok after the recovery debounced check",
                CachedRootStatus.Ok,
                service.cachedStatusForUrl(root.url),
            )
        }
    }

    // ── Helpers unique to this subclass ───────────────────────────────────────

    /**
     * Creates or modifies a file at `<rootRelPath>/<fileRelPath>` under the fixture temp dir
     * via the VFS, which fires `VFS_CHANGES` and causes `BulkFileListener.after` to run.
     *
     * - If the file does not yet exist it is created (fires `VFileCreateEvent`).
     * - If the file already exists its content is updated (fires `VFileContentChangeEvent`).
     *
     * Both event types carry the file path and are matched by `MixEventClassifier.isDepsPathForRoot`,
     * so either reliably enqueues the affected root into `pendingRoots`.
     *
     * Parent directories are created as needed first. After the VFS write the EDT event queue
     * is drained once so any synchronously-posted invocations are processed before the caller
     * inspects state.
     */
    private fun fireVfsDepsEvent(rootRelPath: String, fileRelPath: String) {
        val slashIdx = fileRelPath.lastIndexOf('/')
        val parentRelPath = if (slashIdx > 0) "$rootRelPath/${fileRelPath.substring(0, slashIdx)}" else rootRelPath
        val parentDir = myFixture.tempDirFixture.findOrCreateDir(parentRelPath)
        val fileName = if (slashIdx > 0) fileRelPath.substring(slashIdx + 1) else fileRelPath
        val existingFile = parentDir.findChild(fileName)
        if (existingFile != null) {
            // Modify the existing file so a VFileContentChangeEvent is emitted, which also
            // satisfies isDepsPathForRoot and triggers the BulkFileListener.
            ApplicationManager.getApplication().runWriteAction {
                existingFile.setBinaryContent("# modified ${System.nanoTime()}".toByteArray())
            }
        } else {
            myFixture.tempDirFixture.createFile("$rootRelPath/$fileRelPath", "# generated by test")
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    /**
     * Adds an Elixir facet to the test module so that `Module.isElixirMixModule()` returns `true`.
     *
     * Without this, `MixEventClassifier.elixirMixContentRoots` filters the module out and
     * `BulkFileListener.after` never enqueues any pending roots regardless of file changes.
     */
    private fun ensureElixirFacet() {
        val facetManager = FacetManager.getInstance(module)
        if (facetManager.getFacetByType(Facet.ID) == null) {
            FacetUtil.addFacet(module, FacetType.findInstance(ElixirFacetType::class.java))
        }
    }
}
