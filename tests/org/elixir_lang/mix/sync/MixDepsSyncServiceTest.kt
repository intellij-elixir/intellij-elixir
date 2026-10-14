package org.elixir_lang.mix.sync

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderEntry
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PsiTestUtil
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.mix.library.CONSOLIDATED_LIBRARY_BASE_NAME
import org.elixir_lang.mix.library.Kind as MixLibraryKind
import java.io.File

// WritePlan, ModuleWriteOp, LibraryRootsPlan, DeleteAllPlan, ModuleDepsPlan, buildWritePlan,
// applyWritePlan are internal to the sync package. Tests in the same package access them directly.

/**
 * Behavioural tests for [MixDepsSyncService].
 *
 * Covers:
 * - Pending-set deduplication: repeated identical enqueues collapse to one entry.
 * - Coalescing: a [SyncRequest.DeleteAll] suppresses any pending [SyncRequest.DepRoot] for the
 *   same deps tree, preventing a re-sync from undoing a deliberate delete.
 * - Service registration: the service is reachable via `project.service<MixDepsSyncService>()`.
 * - Delete-before-sync ordering: delete and sync requests for independent targets both execute
 *   in a single drain, with deletes always running first.
 * - mix.exs resolution: a listener-produced [SyncRequest.MixFile] resolves to the owning module
 *   during drain and wires any declared library deps into the module's order entries.
 * - Multi-module scoping:
 *   - Two content roots with the same dep name produce two distinct scoped libraries.
 *   - Syncing one deps root updates only that root's scoped library.
 *   - Deleting one dep removes only that root's scoped library.
 *   - `_build/<env>/lib/<dep>/ebin` is scoped to its own content root.
 *   - A single-root DepRoot request does not wire unrelated modules.
 *   - Legacy unscoped library is removed when a scoped replacement is created.
 * - [SyncRequest.DeleteAll] removes scoped placeholder libraries (empty, no roots) as well as
 *   libraries with roots, guarded by [MixLibraryKind] so unrelated user libraries survive.
 * - A dep with an external `path:` (outside all content roots) produces a module order entry
 *   whose name matches the library created for that external path, ensuring the order entry
 *   and the project library table agree on the same library identity.
 *
 * Intentionally deferred (not covered here):
 * - Cancellation robustness: the platform WARA guarantees that a cancelled `readAction` leaves
 *   no stale `CachedValue`; verifying this requires injecting cancellation mid-PSI-scan in a way
 *   not supported by the test framework.
 * - Write-starvation acceptance: the absence of `invokeAndWait` from [MixDepsSyncService] is
 *   the static proof that the deadlock vector is gone; a timing-sensitive concurrent test would
 *   be flaky.
 * - Full service-lifecycle (scope cancelled on project close): requires creating and disposing a
 *   separate project instance; the registration smoke test covers the normal liveness path.
 *
 * The delete-coalescing and delete+sync tests use [drainDirectly] to
 * avoid VFS-event interference from directories created during fixture setup.
 */
class MixDepsSyncServiceTest : PlatformTestCase() {

    // ------------------------------------------------------------------
    // Test 6a - duplicate enqueues are deduplicated by the Set accumulator
    // ------------------------------------------------------------------

    fun testEnqueue_sameDepRootEnqueuedRepeatedly_deduplicatesToOneEntry() {
        val depRoot = myFixture.tempDirFixture.findOrCreateDir("deps/phoenix")
        val request = SyncRequest.DepRoot(depRoot)
        val service = project.service<MixDepsSyncService>()

        // Clear any VFS-triggered enqueues that may have fired during findOrCreateDir
        // (the temp dir is a content root, so dep-dir creation events are classified and enqueued).
        service.clearPendingForTesting()

        repeat(100) { service.enqueue(request) }

        // The background debounce is 250 ms; this assertion runs in < 10 ms.
        assertEquals(
            "100 identical DepRoot enqueues must collapse to 1 pending entry (Set semantics)",
            1,
            service.pendingCount
        )
    }

    fun testEnqueue_allSingletonEnqueuedRepeatedly_deduplicatesToOneEntry() {
        val service = project.service<MixDepsSyncService>()

        // SyncRequest.All is a singleton object; repeated enqueues of the same instance = 1 entry.
        repeat(50) { service.enqueue(SyncRequest.All) }

        assertEquals(
            "50 enqueues of the All singleton should deduplicate to 1",
            1,
            service.pendingCount
        )
    }

    /**
     * A burst of *distinct* dep roots drains in a single pass.
     *
     * The two dedup tests above enqueue the same request repeatedly, so they pin Set semantics and
     * nothing more. The reported freeze was ~90 different deps, each of which used to get its own
     * background task and its own blocking write action; what makes that impossible now is that the
     * whole burst is one snapshot-and-clear, so assert that count rather than leaving it structural.
     */
    fun testEnqueue_manyDistinctDepRootsInOneBurst_drainInASinglePass() {
        val depCount = 90
        val root = MixTestFixtures.createMixRoot(myFixture, "my_app")
        val depNames = (1..depCount).map { "dep_$it" }
        val depRoots = MixTestFixtures.addDeps(myFixture, "my_app", *depNames.toTypedArray())
        MixTestFixtures.addBuildArtifacts(myFixture, "my_app", "dev", *depNames.toTypedArray())

        val service = project.service<MixDepsSyncService>()
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        // Discard the enqueues that fixture setup's own directory creation triggered.
        service.clearPendingForTesting()

        depRoots.forEach { service.enqueue(SyncRequest.DepRoot(it)) }

        assertEquals(
            "$depCount distinct DepRoot enqueues must all be pending, not deduplicated",
            depCount,
            service.pendingCount
        )

        drainDirectly(service)

        assertEquals(
            "one drain must consume the whole pending set, leaving nothing for a second drain",
            0,
            service.pendingCount
        )

        val rootToken = contentRootToken(project, root.url)
        val missing = depNames.filter { libraryTable.getLibraryByName(scopedDepLibraryName(rootToken, it)) == null }
        assertEquals(
            "a single drain must create a library for every dep in the burst",
            emptyList<String>(),
            missing
        )
    }

    // ------------------------------------------------------------------
    // awaitIdle - queued work is waited for, not dropped
    // ------------------------------------------------------------------

    /** A request left to the debounced collector has been synced once [MixDepsSyncService.awaitIdle] returns. */
    fun testAwaitIdle_waitsForTheDebouncedDrain() {
        val root = MixTestFixtures.createMixRoot(myFixture, "idle_app")
        val (depRoot) = MixTestFixtures.addDeps(myFixture, "idle_app", "idle_dep")
        MixTestFixtures.addBuildArtifacts(myFixture, "idle_app", "dev", "idle_dep")
        val service = project.service<MixDepsSyncService>()
        service.clearPendingForTesting()

        service.enqueue(SyncRequest.DepRoot(depRoot))
        MixSyncTestHelpers.runSuspendOnPooledThread { service.awaitIdle() }

        val libraryName = scopedDepLibraryName(contentRootToken(project, root.url), "idle_dep")
        assertNotNull(
            "awaitIdle must return only once the queued dep has been synced",
            LibraryTablesRegistrar.getInstance().getLibraryTable(project).getLibraryByName(libraryName),
        )
    }

    /** A drain that finished earlier does not answer for a request queued after it. */
    fun testAwaitIdle_waitsForARequestQueuedAfterAnEarlierDrain() {
        val root = MixTestFixtures.createMixRoot(myFixture, "later_app")
        val (firstDep, secondDep) = MixTestFixtures.addDeps(myFixture, "later_app", "first_dep", "second_dep")
        MixTestFixtures.addBuildArtifacts(myFixture, "later_app", "dev", "first_dep", "second_dep")
        val service = project.service<MixDepsSyncService>()
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DepRoot(firstDep))
        drainDirectly(service)

        service.enqueue(SyncRequest.DepRoot(secondDep))
        MixSyncTestHelpers.runSuspendOnPooledThread { service.awaitIdle() }

        val libraryName = scopedDepLibraryName(contentRootToken(project, root.url), "second_dep")
        assertNotNull(
            "awaitIdle must wait for a request queued after an earlier drain finished",
            LibraryTablesRegistrar.getInstance().getLibraryTable(project).getLibraryByName(libraryName),
        )
    }

    // ------------------------------------------------------------------
    // Test 6b - DeleteAll suppresses DepRoot for the same tree
    // (also covers Test 8 - delete-before-sync ordering for the same tree)
    // ------------------------------------------------------------------

    fun testDrain_deleteAllSuppressesDepRootForSameTree_libraryAbsent() {
        val myApp = MixTestFixtures.createMixRoot(myFixture, "my_app")
        val depsDir = myFixture.tempDirFixture.findOrCreateDir("my_app/deps")
        val depRoot = myFixture.tempDirFixture.findOrCreateDir("my_app/deps/phoenix")
        myFixture.tempDirFixture.findOrCreateDir("my_app/deps/phoenix/lib")
        MixTestFixtures.addBuildArtifacts(myFixture, "my_app", "dev", "phoenix")
        val phoenixLibName = scopedDepLibraryName(contentRootToken(project, myApp.url), "phoenix")

        val service = project.service<MixDepsSyncService>()
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        // Seed: create the phoenix library via the production drain path.
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DepRoot(depRoot))
        drainDirectly(service)
        assertNotNull("Phoenix library must exist before drain", libraryTable.getLibraryByName(phoenixLibName))

        // Clear any VFS-triggered enqueues from directory creation above, so the drain only
        // sees the two requests we explicitly enqueue next.  VFS create events are delivered on
        // EDT; `clearPendingForTesting()` called here (before the next EDT pump) is safe because
        // the test thread IS the EDT, so no VFS event can fire between this call and the enqueues.
        service.clearPendingForTesting()

        // Enqueue DeleteAll (delete the whole deps/ tree) AND DepRoot (re-sync phoenix).
        // The dep files still exist on disk - WITHOUT coalescing the DepRoot sync would
        // re-add the library after the delete. WITH coalescing the DepRoot is suppressed
        // and phoenix must remain absent after the drain.
        service.enqueue(SyncRequest.DeleteAll(depsDir.url))
        service.enqueue(SyncRequest.DepRoot(depRoot))

        // Run drain() directly (bypasses the 250 ms debounce) and assert immediately after it
        // returns - before the background debounce can fire a second time for any VFS events
        // that were re-enqueued during the drain's EDT-pump cycle.
        drainDirectly(service)

        assertNull(
            "Phoenix library must be absent: DeleteAll suppressed the DepRoot",
            libraryTable.getLibraryByName(phoenixLibName)
        )
    }

    // ------------------------------------------------------------------
    // Test 7 (minimal) - service is registered and accepts events without throwing
    // ------------------------------------------------------------------

    fun testService_isRegisteredAndEnqueueDoesNotThrow() {
        val service = project.service<MixDepsSyncService>()
        assertNotNull("MixDepsSyncService must be registered as a project service", service)
        service.enqueue(SyncRequest.All)
    }

    // ------------------------------------------------------------------
    // Test 8 - delete and sync for independent targets execute in the same drain
    // ------------------------------------------------------------------

    fun testDrain_deleteOneAndDepRootForDifferentTargets_bothEffectsApplied() {
        val myApp = MixTestFixtures.createMixRoot(myFixture, "my_app")
        val phoenixRoot = myFixture.tempDirFixture.findOrCreateDir("my_app/deps/phoenix")
        myFixture.tempDirFixture.findOrCreateDir("my_app/deps/phoenix/lib")
        val ectoRoot = myFixture.tempDirFixture.findOrCreateDir("my_app/deps/ecto")
        myFixture.tempDirFixture.findOrCreateDir("my_app/deps/ecto/lib")
        MixTestFixtures.addBuildArtifacts(myFixture, "my_app", "dev", "phoenix", "ecto")
        val phoenixLibName = scopedDepLibraryName(contentRootToken(project, myApp.url), "phoenix")
        val ectoLibName = scopedDepLibraryName(contentRootToken(project, myApp.url), "ecto")

        val service = project.service<MixDepsSyncService>()
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        // Seed: create both libraries via the production drain path.
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DepRoot(phoenixRoot))
        service.enqueue(SyncRequest.DepRoot(ectoRoot))
        drainDirectly(service)
        assertNotNull("phoenix library must exist before drain", libraryTable.getLibraryByName(phoenixLibName))
        assertNotNull("ecto library must exist before drain", libraryTable.getLibraryByName(ectoLibName))

        // Clear VFS-triggered setup events so this test drains exactly the two explicit
        // requests below. Otherwise fixture-created _build events can add SyncRequest.All,
        // which legitimately rebuilds phoenix from the still-present test fixture dep root.
        service.clearPendingForTesting()

        // Delete phoenix only (DeleteOne); re-sync ecto (DepRoot).
        // DeleteOne does NOT suppress DepRoot syncs - the two requests are independent.
        // Expected after drain: phoenix absent (deleted), ecto present (re-synced from files).
        service.enqueue(SyncRequest.DeleteOne("phoenix", phoenixRoot.parent?.url, myApp.url))
        service.enqueue(SyncRequest.DepRoot(ectoRoot))

        drainDirectly(service)

        assertNull("Phoenix must be absent - it was targeted by DeleteOne", libraryTable.getLibraryByName(phoenixLibName))
        assertNotNull(
            "Ecto must be present - its DepRoot sync ran in the same drain " +
                "(delete-before-sync ordering preserved)",
            libraryTable.getLibraryByName(ectoLibName)
        )
    }

    // ------------------------------------------------------------------
    // Test 9 - unresolved mix.exs request resolves to module and wires library order entries
    // ------------------------------------------------------------------

    @RequiresEdt
    fun testMixFileRequest_resolvesOwningModuleAndWiresLibraryIntoModuleOrderEntries() {
        val root = MixTestFixtures.createMixRootWithDeps(myFixture, "my_app", "phoenix")
        val mixFile = root.findChild("mix.exs")!!
        val depRoot = myFixture.tempDirFixture.findOrCreateDir("my_app/deps/phoenix")
        myFixture.tempDirFixture.findOrCreateDir("my_app/deps/phoenix/lib")
        MixTestFixtures.addBuildArtifacts(myFixture, "my_app", "dev", "phoenix")
        val phoenixLibName = scopedDepLibraryName(contentRootToken(project, root.url), "phoenix")

        val service = project.service<MixDepsSyncService>()
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        // Seed: create the phoenix library via the production drain path.
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DepRoot(depRoot))
        drainDirectly(service)
        assertNotNull("phoenix project library must exist before fan-out", libraryTable.getLibraryByName(phoenixLibName))

        service.clearPendingForTesting()
        service.enqueue(SyncRequest.MixFile(mixFile))
        drainDirectly(service)

        val orderEntries = ReadAction.computeBlocking<Array<OrderEntry>, RuntimeException> { ModuleRootManager.getInstance(myFixture.module).orderEntries }
        val hasPhoenixLibEntry = orderEntries.any { it is LibraryOrderEntry && it.libraryName == phoenixLibName }
        assertTrue(
            "After fan-out, myFixture.module should have a LibraryOrderEntry for '$phoenixLibName'. " +
                "Order entries: ${orderEntries.map { it.presentableName }}",
            hasPhoenixLibEntry
        )
    }

    // ------------------------------------------------------------------
    // Multi-module scoping tests
    // ------------------------------------------------------------------

    /**
     * Two content roots with the same dep name produce two distinct scoped libraries.
     *
     * Without root-scoped naming, both roots would share a single `"phoenix"` library, causing
     * cross-contamination. With scoped naming, each root gets its own `"phoenix [<contentRootUrl>]"`
     * library.
     */
    fun testSameDepNameInTwoContentRootsCreatesTwoDistinctLibraries() {
        val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val rootB = MixTestFixtures.createMixRoot(myFixture, "project_b")
        val depRootA = myFixture.tempDirFixture.findOrCreateDir("project_a/deps/phoenix")
        myFixture.tempDirFixture.findOrCreateDir("project_a/deps/phoenix/lib")
        val depRootB = myFixture.tempDirFixture.findOrCreateDir("project_b/deps/phoenix")
        myFixture.tempDirFixture.findOrCreateDir("project_b/deps/phoenix/lib")
        MixTestFixtures.addBuildArtifacts(myFixture, "project_a", "dev", "phoenix")
        MixTestFixtures.addBuildArtifacts(myFixture, "project_b", "dev", "phoenix")

        val libNameA = scopedDepLibraryName(contentRootToken(project, rootA.url), "phoenix")
        val libNameB = scopedDepLibraryName(contentRootToken(project, rootB.url), "phoenix")

        // The two scoped names must be distinct.
        assertFalse(
            "Two different content roots must produce different scoped library names",
            libNameA == libNameB
        )

        val service = project.service<MixDepsSyncService>()
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DepRoot(depRootA))
        service.enqueue(SyncRequest.DepRoot(depRootB))
        drainDirectly(service)

        assertNotNull("Library for project_a/phoenix must exist", libraryTable.getLibraryByName(libNameA))
        assertNotNull("Library for project_b/phoenix must exist", libraryTable.getLibraryByName(libNameB))
        assertNull(
            "Unscoped legacy library 'phoenix' must NOT be created",
            libraryTable.getLibraryByName("phoenix")
        )
    }

    /**
     * Syncing a single deps root updates only that root's scoped library, leaving the other
     * root's library untouched.
     */
    fun testSyncingOneDepsRootUpdatesOnlyThatRootsLibrary() {
        val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val rootB = MixTestFixtures.createMixRoot(myFixture, "project_b")
        val depRootA = myFixture.tempDirFixture.findOrCreateDir("project_a/deps/phoenix")
        myFixture.tempDirFixture.findOrCreateDir("project_a/deps/phoenix/lib")
        val depRootB = myFixture.tempDirFixture.findOrCreateDir("project_b/deps/phoenix")
        myFixture.tempDirFixture.findOrCreateDir("project_b/deps/phoenix/lib")
        MixTestFixtures.addBuildArtifacts(myFixture, "project_a", "dev", "phoenix")
        MixTestFixtures.addBuildArtifacts(myFixture, "project_b", "dev", "phoenix")

        val libNameA = scopedDepLibraryName(contentRootToken(project, rootA.url), "phoenix")
        val libNameB = scopedDepLibraryName(contentRootToken(project, rootB.url), "phoenix")

        val service = project.service<MixDepsSyncService>()
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        // Seed both libraries via the production drain path.
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DepRoot(depRootA))
        service.enqueue(SyncRequest.DepRoot(depRootB))
        drainDirectly(service)
        val initialSourceUrlsB = libraryTable.getLibraryByName(libNameB)!!.getUrls(OrderRootType.SOURCES).toSet()

        // Enqueue only a DepRoot for project_a and drain.
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DepRoot(depRootA))
        drainDirectly(service)

        // project_a's library still present.
        assertNotNull("project_a phoenix library must still exist", libraryTable.getLibraryByName(libNameA))
        // project_b's library source roots must be unchanged (sync did not touch it).
        val finalSourceUrlsB = libraryTable.getLibraryByName(libNameB)?.getUrls(OrderRootType.SOURCES)?.toSet()
        assertEquals(
            "project_b phoenix library source roots must be unchanged after syncing only project_a",
            initialSourceUrlsB,
            finalSourceUrlsB
        )
    }

    /**
     * Deleting `deps/phoenix` in one content root removes only that root's scoped library,
     * leaving the other root's scoped library intact.
     */
    fun testDeletingOneDepRemovesOnlyThatRootsScopedLibrary() {
        val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val rootB = MixTestFixtures.createMixRoot(myFixture, "project_b")
        val depRootA = myFixture.tempDirFixture.findOrCreateDir("project_a/deps/phoenix")
        myFixture.tempDirFixture.findOrCreateDir("project_a/deps/phoenix/lib")
        val depRootB = myFixture.tempDirFixture.findOrCreateDir("project_b/deps/phoenix")
        myFixture.tempDirFixture.findOrCreateDir("project_b/deps/phoenix/lib")
        MixTestFixtures.addBuildArtifacts(myFixture, "project_a", "dev", "phoenix")
        MixTestFixtures.addBuildArtifacts(myFixture, "project_b", "dev", "phoenix")

        val libNameA = scopedDepLibraryName(contentRootToken(project, rootA.url), "phoenix")
        val libNameB = scopedDepLibraryName(contentRootToken(project, rootB.url), "phoenix")

        val service = project.service<MixDepsSyncService>()
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        // Seed both libraries via the production drain path.
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DepRoot(depRootA))
        service.enqueue(SyncRequest.DepRoot(depRootB))
        drainDirectly(service)
        assertNotNull(libraryTable.getLibraryByName(libNameA))
        assertNotNull(libraryTable.getLibraryByName(libNameB))

        // Delete only the scoped library for project_a.
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DeleteOne("phoenix", depRootA.parent?.url, rootA.url))
        drainDirectly(service)

        assertNull(
            "project_a phoenix library must be removed after DeleteOne targeting project_a",
            libraryTable.getLibraryByName(libNameA)
        )
        assertNotNull(
            "project_b phoenix library must remain after DeleteOne targeting only project_a",
            libraryTable.getLibraryByName(libNameB)
        )
    }

    /**
     * `_build/<env>/lib/<dep>/ebin` in project_a is mapped to project_a's phoenix library and
     * NOT included in project_b's phoenix library. Scoped _build scanning prevents
     * cross-contamination between content roots.
     */
    fun testBuildEbinMapsToDepUnderSameContentRoot() {
        val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val rootB = MixTestFixtures.createMixRoot(myFixture, "project_b")
        val depRootA = myFixture.tempDirFixture.findOrCreateDir("project_a/deps/phoenix")
        myFixture.tempDirFixture.findOrCreateDir("project_a/deps/phoenix/lib")
        val depRootB = myFixture.tempDirFixture.findOrCreateDir("project_b/deps/phoenix")
        myFixture.tempDirFixture.findOrCreateDir("project_b/deps/phoenix/lib")
        // Only project_a has _build artifacts for phoenix.
        MixTestFixtures.addBuildArtifacts(myFixture, "project_a", "dev", "phoenix")
        // project_b has no phoenix ebin artifact.
        myFixture.tempDirFixture.findOrCreateDir("project_b/_build/dev/consolidated")

        val libNameA = scopedDepLibraryName(contentRootToken(project, rootA.url), "phoenix")
        val libNameB = scopedDepLibraryName(contentRootToken(project, rootB.url), "phoenix")

        val service = project.service<MixDepsSyncService>()
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DepRoot(depRootA))
        service.enqueue(SyncRequest.DepRoot(depRootB))
        drainDirectly(service)

        val classUrlsA = libraryTable.getLibraryByName(libNameA)?.getUrls(OrderRootType.CLASSES)?.toList().orEmpty()
        val classUrlsB = libraryTable.getLibraryByName(libNameB)?.getUrls(OrderRootType.CLASSES)?.toList().orEmpty()

        assertTrue(
            "project_a phoenix library must include its own ebin",
            classUrlsA.any { it.contains("project_a/_build") }
        )
        assertFalse(
            "project_b phoenix library must NOT include project_a's ebin (scoping prevents cross-contamination)",
            classUrlsB.any { it.contains("project_a/_build") }
        )
    }

    /**
     * A single-root DepRoot request does NOT wire dependencies into unrelated modules.
     *
     * Verifies that the affected-module-only fan-out (which replaced the old all-modules fan-out)
     * prevents unrelated-module dependency entries from being modified.
     */
    @RequiresEdt
    fun testSingleRootDepRootDoesNotWireUnrelatedModules() {
        // This test relies on PSI resolution of mix.exs, so we create a proper mix root with deps.
        val myApp = MixTestFixtures.createMixRootWithDeps(myFixture, "my_app", "phoenix")
        val depRoot = myFixture.tempDirFixture.findOrCreateDir("my_app/deps/phoenix")
        myFixture.tempDirFixture.findOrCreateDir("my_app/deps/phoenix/lib")
        MixTestFixtures.addBuildArtifacts(myFixture, "my_app", "dev", "phoenix")
        val phoenixLibName = scopedDepLibraryName(contentRootToken(project, myApp.url), "phoenix")

        val service = project.service<MixDepsSyncService>()
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        // Pre-populate the library via the production drain path.
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DepRoot(depRoot))
        drainDirectly(service)
        assertNotNull(libraryTable.getLibraryByName(phoenixLibName))

        // Capture the current order entries before the scoped drain.
        val entriesBefore = ReadAction.computeBlocking<Array<OrderEntry>, RuntimeException> { ModuleRootManager.getInstance(myFixture.module).orderEntries }
            .filterIsInstance<LibraryOrderEntry>()
            .map { it.libraryName }
            .toSet()

        // Drain only the DepRoot for my_app - affected-module fan-out must only touch the module
        // owning my_app's content root (myFixture.module in this single-module test).
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DepRoot(depRoot))
        drainDirectly(service)

        // The library for my_app/phoenix must still be present (DepRoot re-sync ran).
        assertNotNull(libraryTable.getLibraryByName(phoenixLibName))
        // No spurious extra library entries added to the module from unrelated content roots.
        val entriesAfter = ReadAction.computeBlocking<Array<OrderEntry>, RuntimeException> { ModuleRootManager.getInstance(myFixture.module).orderEntries }
            .filterIsInstance<LibraryOrderEntry>()
            .map { it.libraryName }
            .toSet()
        val unexpectedNewEntries = entriesAfter.filterNot { it in entriesBefore || it == phoenixLibName }
        assertTrue(
            "No unexpected library entries must be wired into the module from an unrelated content root. " +
                "Unexpected: $unexpectedNewEntries",
            unexpectedNewEntries.isEmpty()
        )
    }

    /**
     * A legacy unscoped library with [MixLibraryKind] (e.g. `"phoenix"`) is removed when a scoped
     * replacement is created (e.g. `"phoenix [file:///my_app]"`).
     *
     * A user-created library that happens to share the dep name but does NOT carry [MixLibraryKind]
     * must NOT be removed - the guard verifies library.kind before deletion.
     */
    @RequiresEdt
    fun testLegacyUnscopedLibraryRemovedWhenScopedReplacementCreated() {
        val myApp = MixTestFixtures.createMixRoot(myFixture, "my_app")
        val depRoot = myFixture.tempDirFixture.findOrCreateDir("my_app/deps/phoenix")
        myFixture.tempDirFixture.findOrCreateDir("my_app/deps/phoenix/lib")
        MixTestFixtures.addBuildArtifacts(myFixture, "my_app", "dev", "phoenix")
        val scopedLibName = scopedDepLibraryName(contentRootToken(project, myApp.url), "phoenix")

        val service = project.service<MixDepsSyncService>()
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        // Pre-plant a legacy unscoped Mix dep library - has Kind, so should be cleaned up.
        // Also plant an unrelated user library with the same name but WITHOUT Kind - must survive.
        WriteAction.run<Throwable> {
            val model = libraryTable.modifiableModel
            model.createLibrary("phoenix", MixLibraryKind)           // legacy Mix dep library
            model.createLibrary("phoenix-unrelated", null)           // user library, no kind
            model.commit()
        }
        assertNotNull("Legacy Mix dep library must exist before sync", libraryTable.getLibraryByName("phoenix"))
        assertNotNull("Unrelated library must exist before sync", libraryTable.getLibraryByName("phoenix-unrelated"))

        // Run the production drain path - scoped library is created and the legacy Mix dep library is cleaned up.
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DepRoot(depRoot))
        drainDirectly(service)

        assertNotNull("Scoped library must be created", libraryTable.getLibraryByName(scopedLibName))
        assertNull(
            "Legacy unscoped Mix dep library must be removed when the scoped replacement is created",
            libraryTable.getLibraryByName("phoenix")
        )
        assertNotNull(
            "Unrelated user library (no Kind) must NOT be removed by the legacy cleanup guard",
            libraryTable.getLibraryByName("phoenix-unrelated")
        )
    }

    /**
     * Orphaned unscoped Mix-Kind libraries (name has no " [" content-root marker) are removed by
     * [buildWritePlan] on every drain, even when no delete event targets them.
     *
     * This covers projects configured with an older plugin version before root-scoped library
     * naming was introduced: the first sync event after upgrade sweeps the stale entry.
     * A user-created library without [MixLibraryKind] must NOT be removed.
     */
    @RequiresEdt
    fun testOrphanedUnscopedLibraryRemovedOnNextDrain() {
        MixTestFixtures.createMixRoot(myFixture, "orphan_sweep_app")
        myFixture.tempDirFixture.findOrCreateDir("orphan_sweep_app/deps/phoenix")
        myFixture.tempDirFixture.findOrCreateDir("orphan_sweep_app/deps/phoenix/lib")
        MixTestFixtures.addBuildArtifacts(myFixture, "orphan_sweep_app", "dev", "phoenix")

        val service = project.service<MixDepsSyncService>()
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        // Pre-plant an orphaned unscoped Mix-Kind library (pre-C3 naming)
        // and a user library without Kind that must survive.
        WriteAction.run<Throwable> {
            val model = libraryTable.modifiableModel
            model.createLibrary("phoenix", MixLibraryKind)      // orphaned pre-C3 library
            model.createLibrary("user-phoenix", null)           // user library, no Kind - must survive
            model.commit()
        }
        assertNotNull("Orphaned library must exist before drain", libraryTable.getLibraryByName("phoenix"))
        assertNotNull("User library must exist before drain", libraryTable.getLibraryByName("user-phoenix"))

        // Trigger any drain - the orphaned library is removed by the sweep in buildWritePlan
        // regardless of whether a scoped replacement is requested in the same batch.
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DepRoot(myFixture.tempDirFixture.getFile("orphan_sweep_app/deps/phoenix")!!))
        drainDirectly(service)

        assertNull(
            "Orphaned unscoped Mix-Kind library must be removed by the drain-time sweep",
            libraryTable.getLibraryByName("phoenix")
        )
        assertNotNull(
            "User library without Kind must NOT be removed by the sweep",
            libraryTable.getLibraryByName("user-phoenix")
        )
    }

    // ------------------------------------------------------------------
    // C3 remediation tests
    // ------------------------------------------------------------------

    /**
     * Unfetched dep declared in mix.exs but not yet on disk still appears in the module's
     * library deps as an empty placeholder (missingLibraryDeps code path).
     *
     * Before the fix, mapNotNullTo skipped deps whose virtualFile was null, losing them entirely.
     * After the fix, resolution falls back to the first content root containing mix.exs so the
     * scoped name is always produced and the placeholder library is created.
     */
    fun testUnfetchedDepProducesPlaceholderLibraryEntry() {
        // Create a mix root with a dep declared in mix.exs, but deliberately DO NOT create
        // the deps/<dep> directory - simulating a dep that has not been fetched yet.
        val myApp = MixTestFixtures.createMixRootWithDeps(myFixture, "my_app", "unfetched_dep")
        // Verify the dep directory really does not exist.
        assertNull("deps/unfetched_dep must not exist for this test", myApp.findChild("deps")?.findChild("unfetched_dep"))

        val service = project.service<MixDepsSyncService>()
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        service.clearPendingForTesting()
        service.enqueue(SyncRequest.MixFile(myApp.findChild("mix.exs")!!))
        drainDirectly(service)

        // The library must exist (even if empty) so that the module order entry may be wired.
        // We search by dep-name prefix rather than exact URL since the exact content-root URL
        // depends on the fallback heuristic (first mix.exs-bearing root in contentRoots).
        val createdLibrary = libraryTable.libraries.firstOrNull { lib ->
            val name = lib.name ?: return@firstOrNull false
            name.startsWith("unfetched_dep [") && name.endsWith("]")
        }
        assertNotNull(
            "A placeholder library for unfetched_dep must be created in the project library table " +
                "so that the module order entry is wired correctly. Libraries: ${libraryTable.libraries.mapNotNull { it.name }}",
            createdLibrary
        )
    }

    /**
     * A dep that only a *dependency's* `mix.exs` declares, under options Mix honours by not fetching
     * it, must not become a library.
     *
     * This is the whole point of the filter, seen from where the user sees it: such a library can
     * never gain roots, because no `mix deps.get` in any environment will create the directory. The
     * declaration is `cachex`'s own, which is what put `benchee` in a real project's
     * `.idea/libraries`.
     */
    fun testDepDeclaredOnlyByADependencyUnderOnlyProducesNoLibrary() {
        val myApp = MixTestFixtures.createMixRootWithDeps(myFixture, "filtered_app", "cachex")
        MixTestFixtures.addDeps(myFixture, "filtered_app", "cachex")
        MixTestFixtures.createDepMixExs(
            myFixture,
            "filtered_app/deps/cachex",
            "{:benchee, \"~> 1.0\", optional: true, only: [:bench]}",
        )

        val service = project.service<MixDepsSyncService>()
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        service.clearPendingForTesting()
        service.enqueue(SyncRequest.MixFile(myApp.findChild("mix.exs")!!))
        drainDirectly(service)

        assertNull(
            "A dep Mix will never fetch must not become a library. " +
                "Libraries: ${libraryTable.libraries.mapNotNull { it.name }}",
            libraryTable.libraries.firstOrNull { it.name?.startsWith("benchee") == true },
        )
    }

    /**
     * A `SyncRequest.SyncRoot` (produced by resolving a `BuildPath` event for a registered content
     * root) scopes the sync to that single content root's deps, not all content roots.
     *
     * Before the fix, `BuildPath` resolved to `SyncRequest.All`, syncing all roots. After the fix it
     * resolves to `SyncRequest.SyncRoot` and only the affected root's deps are re-synced.
     */
    fun testSyncRootRequestScopesDepSyncToSingleContentRoot() {
        val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val rootB = MixTestFixtures.createMixRoot(myFixture, "project_b")
        val depRootA = myFixture.tempDirFixture.findOrCreateDir("project_a/deps/phoenix")
        myFixture.tempDirFixture.findOrCreateDir("project_a/deps/phoenix/lib")
        val depRootB = myFixture.tempDirFixture.findOrCreateDir("project_b/deps/phoenix")
        myFixture.tempDirFixture.findOrCreateDir("project_b/deps/phoenix/lib")
        MixTestFixtures.addBuildArtifacts(myFixture, "project_a", "dev", "phoenix")
        MixTestFixtures.addBuildArtifacts(myFixture, "project_b", "dev", "phoenix")

        val libNameA = scopedDepLibraryName(contentRootToken(project, rootA.url), "phoenix")
        val libNameB = scopedDepLibraryName(contentRootToken(project, rootB.url), "phoenix")

        val service = project.service<MixDepsSyncService>()
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        // Seed both libraries via the production drain path.
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DepRoot(depRootA))
        service.enqueue(SyncRequest.DepRoot(depRootB))
        drainDirectly(service)
        val initialClassUrlsB = libraryTable.getLibraryByName(libNameB)!!.getUrls(OrderRootType.CLASSES).toSet()

        // Enqueue a SyncRoot for project_a only and drain.
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.SyncRoot(rootA.url))
        drainDirectly(service)

        // project_a's library must still be present and project_b's class roots must be unchanged.
        assertNotNull("project_a phoenix library must still exist", libraryTable.getLibraryByName(libNameA))
        val finalClassUrlsB = libraryTable.getLibraryByName(libNameB)?.getUrls(OrderRootType.CLASSES)?.toSet()
        assertEquals(
            "project_b phoenix library class roots must be unchanged after SyncRoot targeting project_a only",
            initialClassUrlsB,
            finalClassUrlsB
        )
    }

    // ------------------------------------------------------------------
    // DeleteAll and external-path dep tests
    // ------------------------------------------------------------------

    /**
     * [SyncRequest.DeleteAll] must remove scoped placeholder libraries that have no source or class
     * roots, in addition to the normally-populated scoped libraries it already removed.
     *
     * Placeholder libraries are created for deps declared in `mix.exs` but not yet fetched (no
     * `deps/<name>` directory exists). They have [MixLibraryKind] set but carry zero roots. The
     * source-root URI strategy used previously skipped them because their source-root list was
     * empty (`urls.isNotEmpty()` was false). The scoped-name + Kind strategy now catches them.
     *
     * Also verifies that an unrelated user library without [MixLibraryKind] is NOT removed, so
     * that user-created libraries that happen to share a dep name are never accidentally deleted.
     */
    @RequiresEdt
    fun testDeleteAllRemovesEmptyPlaceholderLibraryWithKind() {
        val root = MixTestFixtures.createMixRoot(myFixture, "my_app")
        val contentRootUrl = root.url
        val depsUrl = "$contentRootUrl/deps"

        val service = project.service<MixDepsSyncService>()
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        // Seed the library table:
        //   (a) scoped placeholder with Kind but NO roots (simulates an unfetched dep)
        //   (b) user library without Kind (must survive delete)
        val placeholderName = scopedDepLibraryName(contentRootToken(project, contentRootUrl), "placeholder_dep")
        WriteAction.run<Throwable> {
            val model = libraryTable.modifiableModel
            model.createLibrary(placeholderName, MixLibraryKind)  // placeholder - no roots added
            model.createLibrary("user_lib_no_kind", null)         // unrelated user library
            model.commit()
        }
        assertNotNull("Placeholder library must exist before DeleteAll", libraryTable.getLibraryByName(placeholderName))
        assertNotNull("User library must exist before DeleteAll", libraryTable.getLibraryByName("user_lib_no_kind"))

        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DeleteAll(depsUrl))
        drainDirectly(service)

        assertNull(
            "Scoped placeholder library (no roots) must be deleted by DeleteAll",
            libraryTable.getLibraryByName(placeholderName)
        )
        assertNotNull(
            "User library without Kind must NOT be deleted by DeleteAll (Kind guard)",
            libraryTable.getLibraryByName("user_lib_no_kind")
        )
    }

    /**
     * For a dep whose `path:` option points outside all registered content roots (an external
     * dep), the module order entry name must match the library that [MixDepsSyncService] creates
     * for that external path so that the IDE can actually resolve the entry to a real library.
     *
     * When `buildExternalLibraryPlans` finds an external dep directory it derives the library
     * name from the physical grandparent of that directory. Without a matching lookup in
     * `buildModuleDepsPlan`, the module order entry falls back to a name scoped to the mix.exs
     * content root instead - a different string - so the order entry points at a library that
     * does not exist in the project library table.
     *
     * This test verifies that both sides agree on the library name so the module order entry is
     * correctly resolved.
     */
    @RequiresEdt
    fun testExternalPathDepModuleOrderEntryMatchesExternalLibraryPlan() {
        // Create external_lib at the fixture temp dir root - NOT under any registered content root.
        val externalLib = myFixture.tempDirFixture.findOrCreateDir("external_lib")
        myFixture.tempDirFixture.findOrCreateDir("external_lib/lib")

        // Allow VFS root access to the external dir (it lives outside any module content root).
        VfsRootAccess.allowRootAccess(myFixture.testRootDisposable, externalLib.path)

        // Create my_app as the content root whose mix.exs declares the external path dep.
        // Use externalLib.path (VirtualFile.path, always forward-slash) as the Elixir string so
        // that Dep.virtualFile() can later find the directory via VfsUtil.findFile(Paths.get(path)).
        val myApp = myFixture.tempDirFixture.findOrCreateDir("my_app")
        val externalPath = externalLib.path  // absolute, forward-slash, no "file://" prefix
        myFixture.tempDirFixture.createFile(
            "my_app/mix.exs",
            "defmodule MyApp.MixProject do\n" +
                "  use Mix.Project\n" +
                "\n" +
                "  def project do\n" +
                "    [app: :my_app, version: \"0.1.0\", deps: deps()]\n" +
                "  end\n" +
                "\n" +
                "  def deps do\n" +
                "    [{:external_lib, path: \"$externalPath\"}]\n" +
                "  end\n" +
                "end\n"
        )
        PsiTestUtil.addContentRoot(myFixture.module, myApp)

        val service = project.service<MixDepsSyncService>()
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.MixFile(myApp.findChild("mix.exs")!!))
        drainDirectly(service)

        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        // Find whichever library was created for external_lib (name is runtime-determined by the
        // VirtualFile grandparent path, which is platform-dependent).
        val externalLibrary = libraryTable.libraries.firstOrNull { lib ->
            val name = lib.name ?: return@firstOrNull false
            name.startsWith("external_lib [") && name.endsWith("]")
        }
        assertNotNull(
            "A library for external_lib must have been created. Libraries: " +
                "${libraryTable.libraries.mapNotNull { it.name }}",
            externalLibrary
        )

        // The module order entry must reference the SAME library name that was created.
        val orderEntries = ReadAction.computeBlocking<Array<OrderEntry>, RuntimeException> { ModuleRootManager.getInstance(myFixture.module).orderEntries }
        val externalLibEntry = orderEntries
            .filterIsInstance<LibraryOrderEntry>()
            .firstOrNull { it.libraryName?.startsWith("external_lib [") == true }
        assertNotNull(
            "A module order entry for external_lib must have been wired. Order entries: " +
                "${orderEntries.map { it.presentableName }}",
            externalLibEntry
        )
        assertEquals(
            "Module order entry name must match the library created for the external dep " +
                "(both must use the same scoped name so the order entry resolves to a real library)",
            externalLibrary!!.name,
            externalLibEntry!!.libraryName
        )
    }

    // ------------------------------------------------------------------
    // DeleteAll for a sibling content root must NOT suppress SyncRoot for an
    // unrelated content root whose URL is a string prefix of the depsUrl
    // (e.g. "app" vs "app2").
    // ------------------------------------------------------------------

    /**
     * Regression test: `coalesceRequests` previously used `startsWith` to check whether
     * a [SyncRequest.DeleteAll].depsUrl "belongs to" a [SyncRequest.SyncRoot].contentRootUrl.
     *
     * Given two content roots whose URLs share a string prefix:
     *   - `file:///tmp/app`  (the target of SyncRoot)
     *   - `file:///tmp/app2` (the target of DeleteAll - depsUrl = `file:///tmp/app2/deps`)
     *
     * The old `startsWith` check incorrectly matched because `"file:///tmp/app2/deps".startsWith("file:///tmp/app")`
     * is `true`. The fixed code uses exact equality: `depsUrl == "${contentRootUrl}/deps"`, which
     * correctly keeps the SyncRoot for `app` when the DeleteAll targets `app2`.
     */
    @RequiresEdt
    fun testCoalesceRequests_deleteAllForSiblingRoot_doesNotSuppressSyncRootForUnrelatedRoot() {
        // Two content roots that share a string prefix: "app" and "app2".
        val app = myFixture.tempDirFixture.findOrCreateDir("app")
        val app2 = myFixture.tempDirFixture.findOrCreateDir("app2")
        myFixture.tempDirFixture.findOrCreateDir("app/deps")
        myFixture.tempDirFixture.findOrCreateDir("app2/deps")
        myFixture.tempDirFixture.findOrCreateDir("app/deps/phoenix")
        myFixture.tempDirFixture.findOrCreateDir("app/deps/phoenix/lib")
        MixTestFixtures.addBuildArtifacts(myFixture, "app", "dev", "phoenix")
        PsiTestUtil.addContentRoot(myFixture.module, app)
        PsiTestUtil.addContentRoot(myFixture.module, app2)

        val service = project.service<MixDepsSyncService>()
        service.clearPendingForTesting()

        // Seed: create the phoenix library so we can verify it is re-created by the SyncRoot.
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
        val phoenixLibName = scopedDepLibraryName(contentRootToken(project, app.url), "phoenix")
        service.enqueue(SyncRequest.DepRoot(myFixture.tempDirFixture.getFile("app/deps/phoenix")!!))
        drainDirectly(service)
        assertNotNull("phoenix library must exist after seed", libraryTable.getLibraryByName(phoenixLibName))

        // Delete the phoenix library so SyncRoot has a visible effect.
        WriteAction.run<Throwable> { libraryTable.getLibraryByName(phoenixLibName)?.let { libraryTable.removeLibrary(it) } }
        assertNull("phoenix library must be absent before drain", libraryTable.getLibraryByName(phoenixLibName))

        // Enqueue DeleteAll for app2 (the *sibling* root) and SyncRoot for app.
        // With the old startsWith bug the SyncRoot for "app" would be suppressed because
        //   "file:///.../app2/deps".startsWith("file:///.../app") == true.
        // With the fix the SyncRoot survives and phoenix is re-created.
        service.enqueue(SyncRequest.DeleteAll("${app2.url}/deps"))
        service.enqueue(SyncRequest.SyncRoot(app.url))

        drainDirectly(service)

        assertNotNull(
            "phoenix library must be present: DeleteAll for app2 must NOT suppress SyncRoot for app",
            libraryTable.getLibraryByName(phoenixLibName)
        )
    }

    // ------------------------------------------------------------------
    // Diff-correctness - existing library with identical roots produces no write op;
    //  existing library with changed roots produces a minimal diff write op.
    // ------------------------------------------------------------------

    /**
     * Seeds a phoenix library with a specific set of source roots, then builds a [WritePlan] via
     * [buildWritePlan] requesting a different set of roots for that library.  Asserts that:
     * - A [LibraryWriteOp] is emitted only for the library that changed.
     * - The op carries the exact add/remove diff (not a clear+rebuild).
     * - No write op is emitted for a library whose roots are already up to date.
     */
    @RequiresEdt
    fun testBuildWritePlan_diffCorrectnessForExistingLibrary() {
        val myApp = myFixture.tempDirFixture.findOrCreateDir("my_app")
        myFixture.tempDirFixture.findOrCreateDir("my_app/deps")
        myFixture.tempDirFixture.findOrCreateDir("my_app/deps/phoenix")
        val libOld = myFixture.tempDirFixture.findOrCreateDir("my_app/deps/phoenix/lib_old")
        val libNew = myFixture.tempDirFixture.findOrCreateDir("my_app/deps/phoenix/lib_new")
        PsiTestUtil.addContentRoot(myFixture.module, myApp)
        MixSyncTestHelpers.awaitMixDepsSyncs(project)

        val contentRootUrl = myApp.url
        val rootToken = contentRootToken(project, contentRootUrl)
        val libName = scopedDepLibraryName(rootToken, "phoenix")

        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        // Seed: phoenix library with lib_old as source root.
        createMixLibrary(libName, sourceRootUrl = libOld.url)

        // Build a SyncPlan requesting lib_new (not lib_old) as the source root.
        val plan = SyncPlan(
            libraryPlans = listOf(
                LibraryRootsPlan(
                    contentRootUrl = contentRootUrl,
                    contentRootToken = rootToken,
                    depName = "phoenix",
                    classRootUrls = emptyList(),
                    sourceRootUrls = listOf(libNew.url),
                    excludeFolders = emptyList(),
                )
            ),
            modulePlans = emptyList(),
        )

        val writePlan = runSuspendOnPooledThread { buildWritePlan(project, plan) }

        // Exactly one LibraryWriteOp for phoenix.
        val op = writePlan.libraryWriteOps.singleOrNull { it.libraryName == libName }
        assertNotNull("Expected a LibraryWriteOp for $libName", op)
        requireNotNull(op)

        assertFalse("createWithKind must be false for an existing library", op.createWithKind)
        assertEquals("addSourceUrls must contain only lib_new", listOf(libNew.url), op.addSourceUrls)
        assertEquals("removeSourceUrls must contain only lib_old", listOf(libOld.url), op.removeSourceUrls)
        assertTrue("addClassUrls must be empty", op.addClassUrls.isEmpty())
        assertTrue("removeClassUrls must be empty", op.removeClassUrls.isEmpty())

        // Cleanup
        WriteAction.run<Throwable> { libraryTable.getLibraryByName(libName)?.let { libraryTable.removeLibrary(it) } }
    }

    /**
     * Seeds a phoenix library with the exact roots that the sync plan requests.  Asserts that
     * [buildWritePlan] emits NO [LibraryWriteOp] for that library (nothing to change -> no write).
     */
    @RequiresEdt
    fun testBuildWritePlan_noOpForAlreadyUpToDateLibrary() {
        val myApp = myFixture.tempDirFixture.findOrCreateDir("my_app_noop")
        val libDir = myFixture.tempDirFixture.findOrCreateDir("my_app_noop/deps/phoenix/lib")
        PsiTestUtil.addContentRoot(myFixture.module, myApp)
        MixSyncTestHelpers.awaitMixDepsSyncs(project)

        val contentRootUrl = myApp.url
        val rootToken = contentRootToken(project, contentRootUrl)
        val libName = scopedDepLibraryName(rootToken, "phoenix")
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        // Seed with the exact same source roots as the plan.
        createMixLibrary(libName, sourceRootUrl = libDir.url)

        val plan = SyncPlan(
            libraryPlans = listOf(
                LibraryRootsPlan(
                    contentRootUrl = contentRootUrl,
                    contentRootToken = rootToken,
                    depName = "phoenix",
                    classRootUrls = emptyList(),
                    sourceRootUrls = listOf(libDir.url),
                    excludeFolders = emptyList(),
                )
            ),
        )

        val writePlan = runSuspendOnPooledThread { buildWritePlan(project, plan) }

        assertNull(
            "No LibraryWriteOp expected when roots are already up to date",
            writePlan.libraryWriteOps.firstOrNull { it.libraryName == libName }
        )

        // Cleanup
        WriteAction.run<Throwable> { libraryTable.getLibraryByName(libName)?.let { libraryTable.removeLibrary(it) } }
    }

    // ------------------------------------------------------------------
    // Stale-plan resilience - applyWritePlan skips gracefully when a
    //  referenced module does not exist.
    // ------------------------------------------------------------------

    /**
     * Constructs a [WritePlan] that references a non-existent module name and verifies that
     * [applyWritePlan] completes without throwing, applying any library mutations that don't
     * depend on the missing module.
     */
    @RequiresEdt
    fun testApplyWritePlan_nonExistentModuleSkippedGracefully() {
        val contentRootUrl = myFixture.tempDirFixture.findOrCreateDir("stale_test").url
        val libName = scopedDepLibraryName(contentRootToken(project, contentRootUrl), "phoenix")

        val writePlan = WritePlan(
            librariesToRemove = emptyList(),
            // Create a placeholder library to verify library work still proceeds.
            placeholderLibraries = setOf(libName),
            libraryWriteOps = emptyList(),
            legacyLibrariesToRemove = emptyList(),
            // ModuleWriteOp with a module name that doesn't exist.
            moduleWriteOps = listOf(
                ModuleWriteOp(
                    moduleName = "nonexistent_module_write_plan_test",
                    addModuleDeps = emptySet(),
                    addInvalidModuleDeps = emptySet(),
                    addLibraryDeps = setOf(libName),
                    addInvalidLibraryDeps = emptySet(),
                    addExcludeFolderUrls = emptyList(),
                )
            ),
        )

        // Must complete without throwing even though the module doesn't exist.
        val stats = runSuspendOnPooledThread { applyWritePlan(project, writePlan) }

        // Library was created (placeholder).
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
        assertNotNull(
            "Placeholder library must be created even when a referenced module is absent",
            libraryTable.getLibraryByName(libName)
        )
        // Library counts: only placeholder creation.
        assertTrue("librariesChanged must be >= 1", stats.librariesChanged >= 1)
        // Module count: 'nonexistent' is skipped -> 0 modules changed.
        assertEquals("modulesChanged must be 0 when the only module doesn't exist", 0, stats.modulesChanged)

        // Cleanup.
        WriteAction.run<Throwable> { libraryTable.getLibraryByName(libName)?.let { libraryTable.removeLibrary(it) } }
    }

    // ------------------------------------------------------------------
    // Write-phase parity - drain through the new pipeline produces the
    //  same library-table state as the old wide-write path.
    // ------------------------------------------------------------------

    /** Through the production path, [MixDepsSyncService.drain] rather than [buildWritePlan] and [applyWritePlan] alone. */
    @RequiresEdt
    fun testDrain_buildWritePlanApplyWritePlanParity_libraryRootsPopulated() {
        val myApp = myFixture.tempDirFixture.findOrCreateDir("parity_test_app")
        myFixture.tempDirFixture.findOrCreateDir("parity_test_app/deps/phoenix")
        myFixture.tempDirFixture.findOrCreateDir("parity_test_app/deps/phoenix/lib")
        MixTestFixtures.addBuildArtifacts(myFixture, "parity_test_app", "dev", "phoenix")
        PsiTestUtil.addContentRoot(myFixture.module, myApp)

        val depsPhoenix = myFixture.tempDirFixture.getFile("parity_test_app/deps/phoenix")!!
        val service = project.service<MixDepsSyncService>()
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DepRoot(depsPhoenix))
        drainDirectly(service)

        val libName = scopedDepLibraryName(contentRootToken(project, myApp.url), "phoenix")
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
        val lib = libraryTable.getLibraryByName(libName)

        assertNotNull("phoenix library must be created by drain pipeline", lib)
        requireNotNull(lib)
        assertTrue(
            "phoenix library must have at least one source root (lib dir)",
            lib.getUrls(OrderRootType.SOURCES).isNotEmpty()
        )
        assertTrue(
            "phoenix library must have at least one class root (ebin dir)",
            lib.getUrls(OrderRootType.CLASSES).isNotEmpty()
        )

        // Cleanup
        WriteAction.run<Throwable> { libraryTable.getLibraryByName(libName)?.let { libraryTable.removeLibrary(it) } }
    }

    // ------------------------------------------------------------------
    // buildWritePlan placeholder computation accounts for libraries
    //  that will be removed by delete operations in the same plan.
    // ------------------------------------------------------------------

    /**
     * A library a [DeleteAllPlan] removes while a module still depends on it must be left as an empty placeholder
     * for the module's order entry to reference.
     */
    @RequiresEdt
    fun testBuildWritePlan_placeholderForLibraryScheduledForDeletion() {
        val myApp = myFixture.tempDirFixture.findOrCreateDir("placeholder_del_test")
        myFixture.tempDirFixture.findOrCreateDir("placeholder_del_test/deps")
        val libDir = myFixture.tempDirFixture.findOrCreateDir("placeholder_del_test/deps/placeholder_dep/lib")
        PsiTestUtil.addContentRoot(myFixture.module, myApp)
        MixSyncTestHelpers.awaitMixDepsSyncs(project)

        val contentRootUrl = myApp.url
        val libName = scopedDepLibraryName(contentRootToken(project, contentRootUrl), "placeholder_dep")
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        // Seed the library so it exists in the snapshot.
        createMixLibrary(libName, sourceRootUrl = libDir.url)

        // Build a SyncPlan with a DeleteAll AND a ModuleDepsPlan that needs the library.
        val syncPlan = SyncPlan(
            deleteAlls = listOf(DeleteAllPlan("${contentRootUrl}/deps")),
            libraryPlans = emptyList(),
            modulePlans = listOf(
                ModuleDepsPlan(
                    moduleName = myFixture.module.name,
                    moduleDeps = emptySet(),
                    libraryDeps = setOf(libName),
                    externalLibraryPlans = emptyList(),
                )
            ),
        )

        val writePlan = runSuspendOnPooledThread { buildWritePlan(project, syncPlan) }
        assertNoNameRemovedAndCreated(writePlan)
        runSuspendOnPooledThread { applyWritePlan(project, writePlan) }

        val library = assertLibraryExists(libName)
        assertEmpty("The deleted dep's roots must be gone", library.getUrls(OrderRootType.SOURCES).toList())

        // Cleanup
        WriteAction.run<Throwable> { libraryTable.getLibraryByName(libName)?.let { libraryTable.removeLibrary(it) } }
    }

    /** A DeleteOne plus a re-sync of the same dep in one drain must leave the library in place. */
    @RequiresEdt
    fun testBuildWritePlan_deleteAndResyncSameDepRecreatesLibrary() {
        val myApp = myFixture.tempDirFixture.findOrCreateDir("delete_resync_test")
        myFixture.tempDirFixture.findOrCreateDir("delete_resync_test/deps")
        val libDir = myFixture.tempDirFixture.findOrCreateDir("delete_resync_test/deps/phoenix/lib")
        PsiTestUtil.addContentRoot(myFixture.module, myApp)
        MixSyncTestHelpers.awaitMixDepsSyncs(project)

        val contentRootUrl = myApp.url
        val rootToken = contentRootToken(project, contentRootUrl)
        val libName = scopedDepLibraryName(rootToken, "phoenix")
        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        // Seed the library with the same roots the re-sync will request - this is the critical
        // scenario: identical roots means a naive diff sees no changes and emits no write op.
        createMixLibrary(libName, sourceRootUrl = libDir.url)

        // Build a SyncPlan with a DeleteOne for phoenix AND a LibraryRootsPlan requesting the
        // same roots (simulating a delete event immediately followed by a re-sync event in the
        // same coalesced drain).
        val syncPlan = SyncPlan(
            deleteOnes = listOf(DeleteOnePlan("phoenix", contentRootUrl, rootToken)),
            libraryPlans = listOf(
                LibraryRootsPlan(
                    contentRootUrl = contentRootUrl,
                    contentRootToken = rootToken,
                    depName = "phoenix",
                    classRootUrls = emptyList(),
                    sourceRootUrls = listOf(libDir.url),
                    excludeFolders = emptyList(),
                )
            ),
            modulePlans = emptyList(),
        )

        val writePlan = runSuspendOnPooledThread { buildWritePlan(project, syncPlan) }
        assertNoNameRemovedAndCreated(writePlan)
        runSuspendOnPooledThread { applyWritePlan(project, writePlan) }

        val library = assertLibraryExists(libName)
        assertEquals(listOf(libDir.url), library.getUrls(OrderRootType.SOURCES).toList())

        // Cleanup
        WriteAction.run<Throwable> { libraryTable.getLibraryByName(libName)?.let { libraryTable.removeLibrary(it) } }
    }

    /**
     * Deleting a deps directory must remove that root's libraries under either scope scheme. The
     * match is by name suffix, so a project last synced by an older version - whose names carry the
     * absolute URL - would otherwise keep every library it had.
     */
    @RequiresEdt
    fun testBuildWritePlan_deleteAllRemovesBothScopeSchemes() {
        val myApp = myFixture.tempDirFixture.findOrCreateDir("delete_all_schemes")
        myFixture.tempDirFixture.findOrCreateDir("delete_all_schemes/deps")
        PsiTestUtil.addContentRoot(myFixture.module, myApp)
        MixSyncTestHelpers.awaitMixDepsSyncs(project)

        val contentRootUrl = myApp.url
        val currentName = scopedDepLibraryName(contentRootToken(project, contentRootUrl), "phoenix")
        val olderSchemeName = scopedDepLibraryName(contentRootUrl, "ecto")

        createMixLibrary(currentName)
        if (olderSchemeName != currentName) createMixLibrary(olderSchemeName)

        val plan = SyncPlan(deleteAlls = listOf(DeleteAllPlan("$contentRootUrl/deps")))
        val writePlan = runSuspendOnPooledThread { buildWritePlan(project, plan) }

        assertTrue(
            "The current scheme's library must be removed. Removing: ${writePlan.librariesToRemove}",
            currentName in writePlan.librariesToRemove
        )
        assertTrue(
            "A library named by the older scheme must be removed too. " +
                "Removing: ${writePlan.librariesToRemove}",
            olderSchemeName in writePlan.librariesToRemove
        )
    }

    // ------------------------------------------------------------------
    // One dep, one library entry per module
    // ------------------------------------------------------------------

    /**
     * An entry the plugin wrote under an older scope scheme must be removed once the dep is wired
     * under the current one, or a module collects an entry per scheme the plugin has shipped.
     * Recognised by the `"<dep> [<token>]"` shape, so a bare name - possibly a user's own library -
     * is left strictly alone.
     */
    @RequiresEdt
    fun testBuildWritePlan_removesEntriesScopedByAnOlderScheme() = withRelativeTokenRoot("supersede_app") { myApp ->
        val ebinDir = File(myApp.path, "_build/dev/lib/phoenix/ebin").apply { mkdirs() }
        val ebin = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ebinDir)!!
        MixSyncTestHelpers.awaitMixDepsSyncs(project)

        val contentRootUrl = myApp.url
        val rootToken = contentRootToken(project, contentRootUrl)
        val newName = scopedDepLibraryName(rootToken, "phoenix")
        val legacyUnscoped = "phoenix"
        // The absolute-URL scheme the plugin wrote before tokens went relative. Deliberately a *valid* library.
        val supersededName = scopedDepLibraryName(contentRootUrl, "phoenix")
        assertFalse("Fixture needs the two names to differ", newName == supersededName)

        WriteAction.run<Throwable> {
            val model = LibraryTablesRegistrar.getInstance().getLibraryTable(project).modifiableModel
            // Not Mix-Kind: a user's library, which the sync neither owns nor removes.
            model.createLibrary(legacyUnscoped)
            model.createLibrary(supersededName, MixLibraryKind)
            model.commit()
            ModuleRootModificationUtil.updateModel(myFixture.module) { rootModel ->
                val table = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
                rootModel.addLibraryEntry(table.getLibraryByName(legacyUnscoped)!!)
                rootModel.addLibraryEntry(table.getLibraryByName(supersededName)!!)
            }
        }

        val plan = SyncPlan(
            libraryPlans = listOf(
                LibraryRootsPlan(
                    contentRootUrl = contentRootUrl,
                    contentRootToken = rootToken,
                    depName = "phoenix",
                    classRootUrls = listOf(ebin.url),
                    sourceRootUrls = emptyList(),
                    excludeFolders = emptyList(),
                )
            ),
            modulePlans = listOf(
                ModuleDepsPlan(
                    moduleName = myFixture.module.name,
                    moduleDeps = emptySet(),
                    libraryDeps = setOf(newName),
                    externalLibraryPlans = emptyList(),
                )
            ),
        )

        val writePlan = runSuspendOnPooledThread { buildWritePlan(project, plan) }
        val op = writePlan.moduleWriteOps.single { it.moduleName == myFixture.module.name }

        assertTrue("The current scoped name must be wired", newName in op.addLibraryDeps)
        assertFalse(
            "A bare name may be a user library and must be left alone. Removed: ${op.removeStaleLibraryDeps}",
            legacyUnscoped in op.removeStaleLibraryDeps
        )
        assertTrue(
            "The entry scoped by the older scheme must be removed even though its library is valid. " +
                "Removed: ${op.removeStaleLibraryDeps}",
            supersededName in op.removeStaleLibraryDeps
        )
        assertFalse(
            "The entry being wired must never be removed",
            newName in op.removeStaleLibraryDeps
        )
    }

    // ------------------------------------------------------------------
    // Sync Dependency Libraries action
    // ------------------------------------------------------------------

    /**
     * The action is the only way a user can force a full rescan: nothing else enqueues
     * [SyncRequest.All] outside first-time project configuration, and `mix deps.get` on already
     * fetched deps changes nothing on disk, so it produces no VFS events either.
     */
    fun testSyncDependencyLibrariesAction_enqueuesAll() {
        val service = project.service<MixDepsSyncService>()
        service.clearPendingForTesting()
        assertEquals(0, service.pendingCount)

        org.elixir_lang.action.SyncDependencyLibrariesAction().actionPerformed(
            com.intellij.testFramework.TestActionEvent.createTestEvent(
                com.intellij.openapi.actionSystem.impl.SimpleDataContext
                    .getProjectContext(project)
            )
        )

        assertEquals("The action must enqueue exactly one request", 1, service.pendingCount)
        service.clearPendingForTesting()
    }

    // ------------------------------------------------------------------
    // contentRootToken
    // ------------------------------------------------------------------

    /**
     * A content root under the project base directory scopes to its relative path, matching the
     * `$PROJECT_DIR$/apps/child` the platform already writes for that library's roots.
     */
    fun testContentRootToken_belowProjectBaseIsRelative() {
        val basePath = projectBasePath()
        assertEquals("apps/child", contentRootToken(project, "file://$basePath/apps/child"))
    }

    /** The base directory itself is ".", never "" - "" is the separate "no owning root" fallback. */
    fun testContentRootToken_projectBaseIsDot() {
        val basePath = projectBasePath()
        assertEquals(".", contentRootToken(project, "file://$basePath"))
    }

    /**
     * A Mix module that is part of the project but sits outside its base directory scopes to a
     * `../`-prefixed path.  `PathMacroManager.addFileHierarchyReplacements` walks to the filesystem
     * root, so the platform writes such a library's roots as `$PROJECT_DIR$/../...` - the name has to
     * relocate on that same boundary or it would be less shareable than the roots it names.
     */
    fun testContentRootToken_outsideProjectBaseIsParentRelative() {
        val basePath = projectBasePath()
        val parent = basePath.substringBeforeLast('/', "")
        assertTrue("Test needs a project base with a parent directory, got '$basePath'", parent.contains('/'))
        val token = contentRootToken(project, "file://$parent/sibling_mix_app")
        assertTrue("Expected a ../-prefixed token, got '$token'", token.startsWith("../"))
        assertTrue("Expected the module directory in the token, got '$token'", token.endsWith("sibling_mix_app"))
    }

    /** No relative path can span two drives or mounts, so the absolute URL is kept unchanged. */
    fun testContentRootToken_unrelatedRootKeepsAbsoluteUrl() {
        val url = "file://nonexistent-mount-0/elsewhere/mix_app"
        assertEquals(url, contentRootToken(project, url))
    }

    // ------------------------------------------------------------------
    // Libraries a drain means to keep survive it
    // ------------------------------------------------------------------

    fun testConsolidatedLibrarySurvivesASecondSyncOfItsRoot() {
        val app = createAppWithDep("keep_app", "phoenix")
        val service = project.service<MixDepsSyncService>()
        syncDep(service, "keep_app", "phoenix")
        assertLibraryExists(consolidatedName(app))

        syncDep(service, "keep_app", "phoenix")

        assertLibraryExists(consolidatedName(app))
    }

    /** Only an umbrella consolidates into `_build/<env>/consolidated`; a regular project uses its app's build. */
    @RequiresEdt
    fun testARegularProjectsConsolidatedProtocolsBecomeItsConsolidatedLibrary() {
        val app = MixTestFixtures.createMixRoot(myFixture, "regular_app")
        myFixture.tempDirFixture.findOrCreateDir("regular_app/deps/phoenix/lib")
        myFixture.tempDirFixture.findOrCreateDir("regular_app/_build/dev/lib/phoenix/ebin")
        val consolidated = myFixture.tempDirFixture
            .findOrCreateDir("regular_app/_build/dev/lib/regular_app/consolidated")
        val service = project.service<MixDepsSyncService>()

        syncDep(service, "regular_app", "phoenix")

        val library = assertLibraryExists(consolidatedName(app))
        assertEquals(listOf(consolidated.url), library.getUrls(OrderRootType.CLASSES).toList())
        val orderEntries = ReadAction.computeBlocking<Array<OrderEntry>, RuntimeException> {
            ModuleRootManager.getInstance(myFixture.module).orderEntries
        }
        assertTrue(
            "the module must depend on it; has ${orderEntries.map { it.presentableName }}",
            orderEntries.any { it is LibraryOrderEntry && it.libraryName == consolidatedName(app) },
        )
    }

    /** An umbrella's child app compiled on its own consolidates into its own app build: a copy the umbrella's is not. */
    @RequiresEdt
    fun testAnUmbrellasConsolidatedLibraryLeavesOutAChildAppsOwnConsolidation() {
        val umbrella = createAppWithDep("umbrella_app", "phoenix")
        myFixture.tempDirFixture.findOrCreateDir("umbrella_app/_build/dev/lib/child_app/consolidated")
        val service = project.service<MixDepsSyncService>()

        syncDep(service, "umbrella_app", "phoenix")

        assertEquals(
            listOf(myFixture.tempDirFixture.getFile("umbrella_app/_build/dev/consolidated")!!.url),
            assertLibraryExists(consolidatedName(umbrella)).getUrls(OrderRootType.CLASSES).toList(),
        )
    }

    /** Of two roots with the same directory name, syncing the one without a `_build` must not remove the other's. */
    @RequiresEdt
    fun testConsolidatedLibrarySurvivesASyncOfARootOfTheSameNameWithoutABuild() {
        val built = createAppWithDep("consolidated_a/api", "phoenix")
        MixTestFixtures.createMixRoot(myFixture, "consolidated_b/api")
        val service = project.service<MixDepsSyncService>()
        syncDep(service, "consolidated_a/api", "phoenix")
        assertLibraryExists(consolidatedName(built))

        service.clearPendingForTesting()
        service.enqueue(SyncRequest.SyncRoot(myFixture.tempDirFixture.getFile("consolidated_b/api")!!.url))
        drainDirectly(service)

        assertNotEmpty(assertLibraryExists(consolidatedName(built)).getUrls(OrderRootType.CLASSES).toList())
    }

    /** A project last synced before consolidated libraries were scoped has one named after its root's directory. */
    @RequiresEdt
    fun testAConsolidatedLibraryNamedAfterItsDirectoryIsReplacedByTheScopedOne() {
        val app = createAppWithDep("unscoped_app", "phoenix")
        val libraryTable = libraryTable()
        WriteAction.run<Throwable> {
            val model = libraryTable.modifiableModel
            model.createLibrary("unscoped_app (consolidated)", MixLibraryKind)
            model.commit()
            ModuleRootModificationUtil.updateModel(myFixture.module) { rootModel ->
                rootModel.addLibraryEntry(libraryTable.getLibraryByName("unscoped_app (consolidated)")!!)
            }
        }
        val service = project.service<MixDepsSyncService>()

        syncDep(service, "unscoped_app", "phoenix")

        assertNull(libraryTable.getLibraryByName("unscoped_app (consolidated)"))
        assertLibraryExists(consolidatedName(app))
        assertFalse(
            "a module must not keep depending on the library the sync removed",
            "unscoped_app (consolidated)" in moduleLibraryEntryNames(),
        )
    }

    /** A directory name holding ` [` does not make the name from before scoping look scoped. */
    @RequiresEdt
    fun testAConsolidatedLibraryNamedAfterABracketedDirectoryIsReplacedByTheScopedOne() {
        val app = createAppWithDep("work [old]", "phoenix")
        val legacyName = "work [old] (consolidated)"
        val libraryTable = libraryTable()
        WriteAction.run<Throwable> {
            val model = libraryTable.modifiableModel
            model.createLibrary(legacyName, MixLibraryKind)
            model.commit()
            ModuleRootModificationUtil.updateModel(myFixture.module) { rootModel ->
                rootModel.addLibraryEntry(libraryTable.getLibraryByName(legacyName)!!)
            }
        }
        val service = project.service<MixDepsSyncService>()

        syncDep(service, "work [old]", "phoenix")

        assertNull(libraryTable.getLibraryByName(legacyName))
        assertLibraryExists(consolidatedName(app))
        assertFalse("the removed library's entry must go with it", legacyName in moduleLibraryEntryNames())
    }

    /** Of two roots with the same directory name, only one built, a sync of both keeps the built one's, in either order. */
    @RequiresEdt
    fun testConsolidatedLibrarySurvivesASyncOfEveryRootWhenOnlyOneOfTheSameNameIsBuilt() {
        MixTestFixtures.createMixRoot(myFixture, "consolidated_b/api")
        val built = createAppWithDep("consolidated_a/api", "phoenix")
        val service = project.service<MixDepsSyncService>()
        syncDep(service, "consolidated_a/api", "phoenix")
        assertLibraryExists(consolidatedName(built))

        service.clearPendingForTesting()
        service.enqueue(SyncRequest.All)
        drainDirectly(service)

        assertNotEmpty(assertLibraryExists(consolidatedName(built)).getUrls(OrderRootType.CLASSES).toList())
    }

    fun testConsolidatedLibrarySurvivesASyncOfAnotherRoot() {
        val first = createAppWithDep("first_app", "phoenix")
        createAppWithDep("second_app", "ecto")
        val service = project.service<MixDepsSyncService>()
        syncDep(service, "first_app", "phoenix")
        assertLibraryExists(consolidatedName(first))

        syncDep(service, "second_app", "ecto")

        assertLibraryExists(consolidatedName(first))
    }

    fun testSteadyStateDrainWritesNothing() {
        createAppWithDep("steady_app", "phoenix")
        val service = project.service<MixDepsSyncService>()
        syncDep(service, "steady_app", "phoenix")

        val depRoot = myFixture.tempDirFixture.getFile("steady_app/deps/phoenix")!!
        val writePlan = runSuspendOnPooledThread {
            val requests = resolvePathShapedRequests(project, listOf(SyncRequest.DepRoot(depRoot)))
            buildWritePlan(project, buildSyncPlan(project, coalesceRequests(requests)))
        }

        assertTrue("A drain over unchanged files must write nothing, but planned $writePlan", writePlan.isEmpty)
    }

    /** A dep still declared keeps its entry while its library is gone, as the placeholder a re-fetch fills. */
    fun testADeletedDepKeepsItsModuleEntry() {
        val app = MixTestFixtures.createMixRootWithDeps(myFixture, "deleted_dep_app", "phoenix")
        myFixture.tempDirFixture.findOrCreateDir("deleted_dep_app/deps/phoenix/lib")
        val service = project.service<MixDepsSyncService>()
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.MixFile(app.findChild("mix.exs")!!))
        drainDirectly(service)
        val libName = scopedDepLibraryName(contentRootToken(project, app.url), "phoenix")
        assertTrue("precondition: the module depends on the dep", libName in moduleLibraryEntryNames())

        val depRoot = myFixture.tempDirFixture.getFile("deleted_dep_app/deps/phoenix")!!
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DeleteOne("phoenix", depRoot.parent.url, app.url))
        drainDirectly(service)

        assertNull("precondition: the delete removed the library", libraryTable().getLibraryByName(libName))
        assertTrue("a deleted dep's entry must stay", libName in moduleLibraryEntryNames())
    }

    /** Only the plugin creates a Mix library, so its module entries are the sync's to remove with it. */
    @RequiresEdt
    fun testAnUnscopedMixLibrarysModuleEntryGoesWithIt() {
        createAppWithDep("unscoped_dep_app", "phoenix")
        val libraryTable = libraryTable()
        WriteAction.run<Throwable> {
            val model = libraryTable.modifiableModel
            model.createLibrary("phoenix", MixLibraryKind)
            model.commit()
            ModuleRootModificationUtil.updateModel(myFixture.module) { rootModel ->
                rootModel.addLibraryEntry(libraryTable.getLibraryByName("phoenix")!!)
            }
        }
        val service = project.service<MixDepsSyncService>()

        syncDep(service, "unscoped_dep_app", "phoenix")

        assertNull(libraryTable.getLibraryByName("phoenix"))
        assertFalse("the removed library's entry must go with it", "phoenix" in moduleLibraryEntryNames())
    }

    /** A library that is not a Mix library is the user's, wherever its roots are. */
    @RequiresEdt
    fun testAUserLibraryWithSourcesUnderDepsSurvivesTheirDeletion() {
        val app = createAppWithDep("user_lib_app", "phoenix")
        val libraryTable = libraryTable()
        WriteAction.run<Throwable> {
            val model = libraryTable.modifiableModel
            model.createLibrary("mylib").modifiableModel.apply {
                addRoot("${app.url}/deps/phoenix/lib", OrderRootType.SOURCES)
                commit()
            }
            model.commit()
        }
        val service = project.service<MixDepsSyncService>()

        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DeleteAll("${app.url}/deps"))
        drainDirectly(service)

        assertNotNull("a user's library must survive the deletion of deps", libraryTable.getLibraryByName("mylib"))
    }

    /** A library that is not a Mix library is the user's, whatever its name, so a root without `_build` keeps it. */
    @RequiresEdt
    fun testAUserLibraryNamedLikeAConsolidatedOneSurvivesARootWithoutABuild() {
        val app = MixTestFixtures.createMixRoot(myFixture, "no_build_app")
        val libraryTable = libraryTable()
        WriteAction.run<Throwable> {
            val model = libraryTable.modifiableModel
            model.createLibrary(consolidatedName(app))
            model.commit()
        }
        val service = project.service<MixDepsSyncService>()

        service.clearPendingForTesting()
        service.enqueue(SyncRequest.SyncRoot(app.url))
        drainDirectly(service)

        assertNotNull("a user's library must survive", libraryTable.getLibraryByName(consolidatedName(app)))
    }

    /** The same for a deleted dep: only a Mix library of that name is the sync's to remove. */
    @RequiresEdt
    fun testAUserLibraryNamedLikeADepSurvivesItsDeletion() {
        val app = createAppWithDep("user_named_app", "phoenix")
        val libName = scopedDepLibraryName(contentRootToken(project, app.url), "phoenix")
        val libraryTable = libraryTable()
        WriteAction.run<Throwable> {
            val model = libraryTable.modifiableModel
            model.createLibrary(libName)
            model.commit()
        }
        val service = project.service<MixDepsSyncService>()

        val depRoot = myFixture.tempDirFixture.getFile("user_named_app/deps/phoenix")!!
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DeleteOne("phoenix", depRoot.parent.url, app.url))
        drainDirectly(service)

        assertNotNull("a user's library must survive", libraryTable.getLibraryByName(libName))
    }

    /** An unfetched dep's placeholder has no library plan, so only the sweep removes it from the older scheme. */
    @RequiresEdt
    fun testAnOlderSchemePlaceholderWithNoPlanIsSwept() {
        withRelativeTokenRoot("older_scheme_root") { root ->
            val olderSchemeName = scopedDepLibraryName(root.url, "phoenix")
            ModuleRootModificationUtil.addDependency(myFixture.module, createMixLibrary(olderSchemeName))

            val writePlan = runSuspendOnPooledThread { buildWritePlan(project, SyncPlan()) }

            assertTrue("the older-scheme placeholder must go", olderSchemeName in writePlan.librariesToRemove)
            assertTrue(
                "its module entry must go with it",
                writePlan.moduleWriteOps.any { olderSchemeName in it.removeStaleLibraryDeps },
            )
        }
    }

    /** A dep whose `deps` and `_build` were deleted and never re-fetched has no plan, so the sync empties it. */
    @RequiresEdt
    fun testOneFullSyncClearsADanglingRoot() {
        val root = myFixture.tempDirFixture.findOrCreateDir("dangling_app")
        PsiTestUtil.addContentRoot(myFixture.module, root)
        val name = scopedDepLibraryName(contentRootToken(project, root.url), "phoenix")
        createMixLibrary(name, "${root.url}/_build/dev/lib/phoenix/ebin")

        assertOneFullSyncClears("a dangling root")
        assertEquals(
            "the dep stays as an empty placeholder",
            emptyList<String>(),
            assertLibraryExists(name).getUrls(OrderRootType.CLASSES).toList(),
        )
    }

    /** A library removed after the plan was built, such as by the user, is not recreated to drop its dangling roots. */
    @RequiresEdt
    fun testAnOpNoPlanWantsCreatesNothingForAGoneLibrary() {
        runSuspendOnPooledThread { applyWritePlan(project, removeOnlyWritePlan(recreateWith = null)) }

        assertNull("a gone library must not be recreated", libraryTable().getLibraryByName("phoenix [.]"))
    }

    /** The op is only a diff, so a library a plan wants comes back with every root the plan gives it. */
    @RequiresEdt
    fun testAnOpAPlanWantsRecreatesAGoneLibraryWithItsFullRoots() {
        val roots = LibraryRoots(listOf("temp:///src/kept/ebin"), listOf("temp:///src/kept/lib"))

        runSuspendOnPooledThread { applyWritePlan(project, removeOnlyWritePlan(recreateWith = roots)) }

        val library = assertLibraryExists("phoenix [.]")
        assertEquals(
            roots,
            LibraryRoots(
                library.getUrls(OrderRootType.CLASSES).toList(),
                library.getUrls(OrderRootType.SOURCES).toList(),
            ),
        )
    }

    /** Only an op a plan asked for recreates a library removed before it is applied. */
    @RequiresEdt
    fun testOnlyOpsAPlanAskedForRecreateTheirLibrary() {
        val app = myFixture.tempDirFixture.findOrCreateDir("recreate_app")
        val ebin = myFixture.tempDirFixture.findOrCreateDir("recreate_app/_build/dev/lib/phoenix/ebin")
        val keptRoot = myFixture.tempDirFixture.findOrCreateDir("recreate_app/wanted_kept/ebin")
        val consolidatedDir = myFixture.tempDirFixture.findOrCreateDir("recreate_app/_build/dev/consolidated")
        val keptSources = myFixture.tempDirFixture.findOrCreateDir("recreate_app/consolidated_sources")
        PsiTestUtil.addContentRoot(myFixture.module, app)
        MixSyncTestHelpers.awaitMixDepsSyncs(project)
        val token = contentRootToken(project, app.url)
        val planned = LibraryRootsPlan(
            contentRootUrl = app.url,
            contentRootToken = token,
            depName = "phoenix",
            classRootUrls = listOf(ebin.url),
            sourceRootUrls = emptyList(),
            excludeFolders = emptyList(),
        )
        createMixLibrary(planned.libraryName, "${app.url}/old/ebin")
        val emptied = scopedDepLibraryName(token, "ecto")
        createMixLibrary(emptied, "${app.url}/ecto/ebin")
        val dangling = scopedDepLibraryName(token, "gone")
        createMixLibrary(dangling, "${app.url}/gone/ebin")
        // Its deps directory went, but its mix.exs still declares it: a plan wants it, with no roots to give it.
        val wantedDangling = scopedDepLibraryName(token, "wanted")
        createMixLibrary(wantedDangling, "${app.url}/wanted_gone/ebin")
        WriteAction.run<Throwable> {
            assertLibraryExists(wantedDangling).modifiableModel.let { libraryModel ->
                libraryModel.addRoot(keptRoot.url, OrderRootType.CLASSES)
                libraryModel.commit()
            }
        }
        val created = planned.copy(depName = "jason")
        val consolidated = ConsolidatedLibraryPlan(app.url, token, listOf(consolidatedDir.url), null)
        createMixLibrary(consolidated.libraryName, "${app.url}/old_consolidated")
        WriteAction.run<Throwable> {
            assertLibraryExists(consolidated.libraryName).modifiableModel.let { libraryModel ->
                libraryModel.addRoot(keptSources.url, OrderRootType.SOURCES)
                libraryModel.addRoot("${app.url}/gone_sources", OrderRootType.SOURCES)
                libraryModel.commit()
            }
        }
        val syncPlan = SyncPlan(
            deleteOnes = listOf(DeleteOnePlan("ecto", app.url, token)),
            libraryPlans = listOf(planned, created),
            modulePlans = listOf(
                ModuleDepsPlan(myFixture.module.name, emptySet(), setOf(emptied, wantedDangling), emptyList()),
            ),
            consolidatedPlans = listOf(consolidated),
        )

        val writePlan = runSuspendOnPooledThread { buildWritePlan(project, syncPlan) }

        assertEquals(
            mapOf(
                planned.libraryName to LibraryRoots(listOf(ebin.url), emptyList()),
                created.libraryName to null,
                consolidated.libraryName to LibraryRoots(listOf(consolidatedDir.url), listOf(keptSources.url)),
                emptied to LibraryRoots(emptyList(), emptyList()),
                dangling to null,
                wantedDangling to LibraryRoots(listOf(keptRoot.url), emptyList()),
            ),
            writePlan.libraryWriteOps.associate { it.libraryName to it.recreateWith },
        )
    }

    /** A real root has a project-relative token on every OS, which the fixture's `temp://` roots may not. */
    @RequiresEdt
    fun testOneFullSyncClearsADanglingRootUnderARelativeToken() {
        withRelativeTokenRoot("dangling_relative") { root ->
            createMixLibrary(
                scopedDepLibraryName(contentRootToken(project, root.url), "phoenix"),
                "${root.url}/_build/dev/lib/phoenix/ebin",
            )

            assertOneFullSyncClears("a dangling root under a relative token")
        }
    }

    /** An external `path:` dep is scoped to a directory that is never a content root, so its roots are left alone. */
    @RequiresEdt
    fun testAnExternalDepsDanglingRootIsLeftAlone() {
        PsiTestUtil.addContentRoot(myFixture.module, myFixture.tempDirFixture.findOrCreateDir("external_dangling_app"))
        val name = scopedDepLibraryName("../shared_parent", "shared")
        val root = "file:///shared_parent/shared/ebin"
        createMixLibrary(name, root)

        assertFalse(
            "an external dep's dangling root must not trigger a full sync",
            runSuspendOnPooledThread { MixLibraryReconciler.needsResync(project) },
        )
        val service = project.service<MixDepsSyncService>()
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.All)
        drainDirectly(service)

        assertEquals(listOf(root), assertLibraryExists(name).getUrls(OrderRootType.CLASSES).toList())
    }

    /** A consolidated plan names only class roots, so a source root that went must still be cleared. */
    @RequiresEdt
    fun testOneFullSyncClearsAPlannedConsolidatedLibrarysDanglingSourceRoot() {
        val app = MixTestFixtures.createMixRoot(myFixture, "consolidated_sources_app")
        val consolidated = myFixture.tempDirFixture.findOrCreateDir("consolidated_sources_app/_build/dev/consolidated")
        MixSyncTestHelpers.awaitMixDepsSyncs(project)
        val name = consolidatedName(app)
        createMixLibrary(name, consolidated.url)
        WriteAction.run<Throwable> {
            assertLibraryExists(name).modifiableModel.let { libraryModel ->
                libraryModel.addRoot("${app.url}/gone_sources", OrderRootType.SOURCES)
                libraryModel.commit()
            }
        }

        assertOneFullSyncClears("a planned consolidated library's dangling source root")
    }

    @RequiresEdt
    fun testOneFullSyncClearsAnOlderSchemeName() {
        withRelativeTokenRoot("older_scheme_round_trip") { root ->
            createMixLibrary(scopedDepLibraryName(root.url, "phoenix"))

            assertOneFullSyncClears("an older-scheme name")
        }
    }

    @RequiresEdt
    fun testOneFullSyncClearsAnUnscopedName() {
        PsiTestUtil.addContentRoot(myFixture.module, myFixture.tempDirFixture.findOrCreateDir("unscoped_round_trip"))
        createMixLibrary("phoenix")

        assertOneFullSyncClears("an unscoped name")
    }

    @RequiresEdt
    fun testOneFullSyncClearsAConsolidatedLibraryWhoseRootLeft() {
        PsiTestUtil.addContentRoot(myFixture.module, myFixture.tempDirFixture.findOrCreateDir("consolidated_round_trip"))
        createMixLibrary(scopedDepLibraryName("apps/gone", CONSOLIDATED_LIBRARY_BASE_NAME))

        assertOneFullSyncClears("a consolidated library whose root left")
    }

    /** An external `path:` dep is scoped to its directory's grandparent, never a content root, so only a plan may remove it. */
    @RequiresEdt
    fun testAnExternalPathDepSurvivesADrainThatDoesNotPlanIt() {
        // Outside the fixture, whose own directory is a content root.
        val externalDir = File(FileUtil.createTempDirectory("external", null, true), "kept_external_lib")
        File(externalDir, "lib").mkdirs()
        val externalLib = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(externalDir)!!
        VfsRootAccess.allowRootAccess(myFixture.testRootDisposable, externalLib.path)
        val myApp = myFixture.tempDirFixture.findOrCreateDir("path_dep_app")
        myFixture.tempDirFixture.createFile(
            "path_dep_app/mix.exs",
            """
            defmodule PathDepApp.MixProject do
              use Mix.Project

              def project do
                [app: :path_dep_app, version: "0.1.0", deps: deps()]
              end

              def deps do
                [{:kept_external_lib, path: "${externalLib.path}"}]
              end
            end
            """.trimIndent(),
        )
        PsiTestUtil.addContentRoot(myFixture.module, myApp)
        val service = project.service<MixDepsSyncService>()
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.MixFile(myApp.findChild("mix.exs")!!))
        drainDirectly(service)
        val externalName = libraryTable().libraries.mapNotNull { it.name }
            .firstOrNull { it.startsWith("kept_external_lib [") }
        assertNotNull("precondition: the external dep's library", externalName)

        myFixture.tempDirFixture.findOrCreateDir("path_dep_app/_build/dev/consolidated")
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.Consolidated(myApp))
        drainDirectly(service)

        assertNotNull("an external dep's library must survive", libraryTable().getLibraryByName(externalName!!))
    }

    /** Mix resolves a dep's relative `path:` against the `mix.exs` that declares it, not the project that reached it. */
    @RequiresEdt
    fun testATransitivePathDepResolvesBesideTheDepThatDeclaresIt() {
        val external = File(FileUtil.createTempDirectory("transitive", null, true), "libs")
        File(external, "middle_lib/lib").mkdirs()
        // Named apart from its app, as a `path:` dep's directory may be.
        File(external, "sibling_dir/lib").mkdirs()
        File(external, "middle_lib/mix.exs").writeText(
            """
            defmodule MiddleLib.MixProject do
              use Mix.Project

              def project do
                [app: :middle_lib, version: "0.1.0", deps: deps()]
              end

              def deps do
                [{:sibling_lib, path: "../sibling_dir"}]
              end
            end
            """.trimIndent(),
        )
        val externalDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(external)!!
        VfsRootAccess.allowRootAccess(myFixture.testRootDisposable, externalDir.path)
        val myApp = myFixture.tempDirFixture.findOrCreateDir("transitive_app")
        // Where the path would point were it read against the project instead of the dep that declares it.
        myFixture.tempDirFixture.findOrCreateDir("sibling_dir/lib")
        myFixture.tempDirFixture.createFile(
            "transitive_app/mix.exs",
            """
            defmodule TransitiveApp.MixProject do
              use Mix.Project

              def project do
                [app: :transitive_app, version: "0.1.0", deps: deps()]
              end

              def deps do
                [{:middle_lib, path: "${externalDir.path}/middle_lib"}]
              end
            end
            """.trimIndent(),
        )
        PsiTestUtil.addContentRoot(myFixture.module, myApp)
        val service = project.service<MixDepsSyncService>()
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.MixFile(myApp.findChild("mix.exs")!!))
        drainDirectly(service)

        val siblingDir = externalDir.findChild("sibling_dir")!!.url
        val sibling = libraryTable().libraries.firstOrNull { library ->
            library.getUrls(OrderRootType.SOURCES).any { it.startsWith(siblingDir) }
        }
        assertNotNull(
            "the directory beside the dep that declares it must get a library; " +
                "libraries: ${libraryTable().libraries.map { it.name }}",
            sibling,
        )
        assertTrue(
            "the module must reference that library; references: ${moduleLibraryEntryNames()}",
            sibling!!.name in moduleLibraryEntryNames(),
        )
    }

    /** A reference whose token is not a content root is stale only once its library is gone. */
    @RequiresEdt
    fun testAnExternalDepsEntryOutlivesAModuleVisit() {
        val name = scopedDepLibraryName("../shared_parent", "shared")
        ModuleRootModificationUtil.addDependency(myFixture.module, createMixLibrary(name))
        val syncPlan = SyncPlan(modulePlans = listOf(ModuleDepsPlan(myFixture.module.name, emptySet(), emptySet(), emptyList())))

        val writePlan = runSuspendOnPooledThread { buildWritePlan(project, syncPlan) }

        assertEquals(
            "an existing external dep library's entry must stay",
            emptyList<String>(),
            writePlan.moduleWriteOps.flatMap { it.removeStaleLibraryDeps }.filter { it == name },
        )
    }

    /** A library that is not a Mix library is the user's, even with a scoped-looking name its root cannot match. */
    @RequiresEdt
    fun testAUserLibrarysEntryWithAScopedLookingNameSurvives() {
        val app = MixTestFixtures.createMixRootWithDeps(myFixture, "user_entry_app", "phoenix")
        val libraryTable = libraryTable()
        WriteAction.run<Throwable> {
            val model = libraryTable.modifiableModel
            model.createLibrary("commons [shared]")
            model.commit()
            ModuleRootModificationUtil.updateModel(myFixture.module) { rootModel ->
                rootModel.addLibraryEntry(libraryTable.getLibraryByName("commons [shared]")!!)
            }
        }
        val service = project.service<MixDepsSyncService>()

        service.clearPendingForTesting()
        service.enqueue(SyncRequest.MixFile(app.findChild("mix.exs")!!))
        drainDirectly(service)

        assertTrue("a user's library entry must stay", "commons [shared]" in moduleLibraryEntryNames())
    }

    /** A plan never rewrites a library that is not a Mix library, even one with the name it wants. */
    @RequiresEdt
    fun testAUserLibraryNamedLikeAPlannedOneKeepsItsRoots() {
        val app = MixTestFixtures.createMixRootWithDeps(myFixture, "planned_names_app", "phoenix")
        myFixture.tempDirFixture.findOrCreateDir("planned_names_app/deps/phoenix/lib")
        MixTestFixtures.addBuildArtifacts(myFixture, "planned_names_app", "dev", "phoenix")
        val depName = scopedDepLibraryName(contentRootToken(project, app.url), "phoenix")
        val userRoot = myFixture.tempDirFixture.findOrCreateDir("user_roots").url
        val libraryTable = libraryTable()
        WriteAction.run<Throwable> {
            val model = libraryTable.modifiableModel
            for (name in listOf(depName, consolidatedName(app))) {
                model.createLibrary(name).modifiableModel.apply {
                    addRoot(userRoot, OrderRootType.CLASSES)
                    commit()
                }
            }
            model.commit()
        }
        val service = project.service<MixDepsSyncService>()

        service.clearPendingForTesting()
        service.enqueue(SyncRequest.MixFile(app.findChild("mix.exs")!!))
        drainDirectly(service)

        for (name in listOf(depName, consolidatedName(app))) {
            assertEquals(
                "$name must keep its user's roots",
                listOf(userRoot),
                libraryTable.getLibraryByName(name)!!.getUrls(OrderRootType.CLASSES).toList(),
            )
        }
        assertEquals("no module may be wired to a user's library", emptyList<String>(),
            moduleLibraryEntryNames().filter { it == depName || it == consolidatedName(app) })
    }

    /** A user library under the planned name does not keep the plugin's own library from an older scheme alive. */
    @RequiresEdt
    fun testAnOlderSchemeLibraryGoesEvenWhenAUserLibraryHasThePlannedName() {
        val app = myFixture.tempDirFixture.findOrCreateDir("older_scheme_app")
        val ebin = myFixture.tempDirFixture.findOrCreateDir("older_scheme_app/_build/dev/lib/phoenix/ebin")
        PsiTestUtil.addContentRoot(myFixture.module, app)
        MixSyncTestHelpers.awaitMixDepsSyncs(project)
        // A project-relative token, as a real project has; the fixture's own root has none.
        val rootsPlan = LibraryRootsPlan(
            contentRootUrl = app.url,
            contentRootToken = ".",
            depName = "phoenix",
            classRootUrls = listOf(ebin.url),
            sourceRootUrls = emptyList(),
            excludeFolders = emptyList(),
        )
        val olderSchemeName = rootsPlan.previousLibraryName!!
        val userLibrary = WriteAction.computeAndWait<Library, Throwable> {
            val model = libraryTable().modifiableModel
            val library = model.createLibrary(rootsPlan.libraryName)
            model.createLibrary(olderSchemeName, MixLibraryKind)
            model.commit()
            library
        }
        ModuleRootModificationUtil.addDependency(myFixture.module, userLibrary)
        // A module plan that does not want the name makes Step 4 judge the entry; a delete of the name reaches Step 1.
        val syncPlan = SyncPlan(
            deleteOnes = listOf(DeleteOnePlan(rootsPlan.depName, app.url, rootsPlan.contentRootToken)),
            libraryPlans = listOf(rootsPlan),
            modulePlans = listOf(ModuleDepsPlan(myFixture.module.name, emptySet(), emptySet(), emptyList())),
        )

        val writePlan = runSuspendOnPooledThread { buildWritePlan(project, syncPlan) }

        assertTrue("the plugin's older-scheme library must go", olderSchemeName in writePlan.legacyLibrariesToRemove)
        assertEquals(
            "the user's library must be neither rewritten nor removed",
            emptyList<String>(),
            buildList {
                writePlan.libraryWriteOps.mapTo(this) { it.libraryName }
                addAll(writePlan.legacyLibrariesToRemove)
                addAll(writePlan.librariesToRemove)
                addAll(writePlan.placeholderLibraries)
                writePlan.moduleWriteOps.flatMapTo(this) { it.removeStaleLibraryDeps }
            }.filter { it == rootsPlan.libraryName },
        )
    }

    /** `mix compile` after `mix clean` recreates the project's own app build, which is where it consolidates. */
    @RequiresEdt
    fun testTheOwnAppBuildAppearingSyncsTheConsolidatedLibrary() {
        val app = MixTestFixtures.createMixRoot(myFixture, "own_build_app")
        val consolidated = myFixture.tempDirFixture.findOrCreateDir("own_build_app/_build/dev/lib/own_build_app/consolidated")
        val service = project.service<MixDepsSyncService>()

        service.clearPendingForTesting()
        service.enqueue(SyncRequest.BuildDep(app, "own_build_app"))
        drainDirectly(service)

        assertEquals(
            listOf(consolidated.url),
            assertLibraryExists(consolidatedName(app)).getUrls(OrderRootType.CLASSES).toList(),
        )
    }

    /** A root whose directory cannot be read just now, such as one on a stopped WSL distro, has not left. */
    @RequiresEdt
    fun testAnUnreadableRootKeepsItsConsolidatedLibrary() {
        createAppWithDep("readable_app", "phoenix")
        val unreadableUrl = "${myFixture.tempDirFixture.findOrCreateDir("").url}/unreadable_app"
        ModuleRootModificationUtil.updateModel(myFixture.module) { it.addContentEntry(unreadableUrl) }
        val unreadableName = consolidatedLibraryName(project, unreadableUrl)
        val libraryTable = libraryTable()
        WriteAction.run<Throwable> {
            val model = libraryTable.modifiableModel
            model.createLibrary(unreadableName, MixLibraryKind)
            model.commit()
        }
        val service = project.service<MixDepsSyncService>()

        syncDep(service, "readable_app", "phoenix")

        assertNotNull("a root that is only unreadable keeps its library", libraryTable.getLibraryByName(unreadableName))
    }

    /** A content root the VFS has no directory for, deleted or unreadable just now, keeps its libraries' roots. */
    @RequiresEdt
    fun testAContentRootWithNoDirectoryKeepsItsLibrariesRoots() {
        val unreadableUrl = "${myFixture.tempDirFixture.findOrCreateDir("").url}/unreadable_roots_app"
        ModuleRootModificationUtil.updateModel(myFixture.module) { it.addContentEntry(unreadableUrl) }
        val token = contentRootToken(project, unreadableUrl)
        val depRoot = "$unreadableUrl/_build/dev/lib/phoenix/ebin"
        val consolidatedRoot = "$unreadableUrl/_build/dev/consolidated"
        createMixLibrary(scopedDepLibraryName(token, "phoenix"), depRoot)
        createMixLibrary(consolidatedLibraryName(project, unreadableUrl), consolidatedRoot)

        assertFalse(
            "a content root with no directory must not trigger a full sync",
            runSuspendOnPooledThread { MixLibraryReconciler.needsResync(project) },
        )
        val service = project.service<MixDepsSyncService>()
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.All)
        drainDirectly(service)

        assertEquals(
            "a full sync must keep the roots of the libraries of a content root with no directory",
            listOf(listOf(depRoot), listOf(consolidatedRoot)),
            listOf(scopedDepLibraryName(token, "phoenix"), consolidatedLibraryName(project, unreadableUrl))
                .map { assertLibraryExists(it).getUrls(OrderRootType.CLASSES).toList() },
        )
    }

    fun testDeleteOneAndResyncOfSameDepInOneDrainKeepsLibrary() {
        val app = createAppWithDep("refetch_app", "phoenix")
        val service = project.service<MixDepsSyncService>()
        syncDep(service, "refetch_app", "phoenix")
        val libName = scopedDepLibraryName(contentRootToken(project, app.url), "phoenix")
        assertLibraryExists(libName)

        val depRoot = myFixture.tempDirFixture.getFile("refetch_app/deps/phoenix")!!
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DeleteOne("phoenix", depRoot.parent.url, app.url))
        service.enqueue(SyncRequest.DepRoot(depRoot))
        drainDirectly(service)

        val library = assertLibraryExists(libName)
        assertTrue(
            "The re-fetched dep's sources must be attached",
            library.getUrls(OrderRootType.SOURCES).any { it.endsWith("/refetch_app/deps/phoenix/lib") }
        )
    }

    fun testDeleteAllAndAllInOneDrainKeepLibraries() {
        val app = createAppWithDep("reset_app", "phoenix")
        val service = project.service<MixDepsSyncService>()
        syncDep(service, "reset_app", "phoenix")
        val libName = scopedDepLibraryName(contentRootToken(project, app.url), "phoenix")
        assertLibraryExists(libName)
        assertLibraryExists(consolidatedName(app))

        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DeleteAll(app.findChild("deps")!!.url, app.url))
        service.enqueue(SyncRequest.All)
        drainDirectly(service)

        assertLibraryExists(libName)
        assertLibraryExists(consolidatedName(app))
    }

    @RequiresEdt
    fun testPlaceholderForARemovedLibraryIsKept() {
        val app = MixTestFixtures.createMixRootWithDeps(myFixture, "unfetch_app", "gone_dep")
        val depRoot = myFixture.tempDirFixture.findOrCreateDir("unfetch_app/deps/gone_dep")
        myFixture.tempDirFixture.findOrCreateDir("unfetch_app/deps/gone_dep/lib")
        val service = project.service<MixDepsSyncService>()
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.MixFile(app.findChild("mix.exs")!!))
        drainDirectly(service)
        val libName = scopedDepLibraryName(contentRootToken(project, app.url), "gone_dep")
        assertLibraryExists(libName)

        val depsUrl = depRoot.parent.url
        WriteAction.run<Throwable> { depRoot.delete(this) }
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DeleteOne("gone_dep", depsUrl, app.url))
        service.enqueue(SyncRequest.MixFile(app.findChild("mix.exs")!!))
        drainDirectly(service)

        val library = assertLibraryExists(libName)
        assertEmpty("A placeholder has no roots", library.getUrls(OrderRootType.SOURCES).toList())
    }

    /** Deleting deps leaves `_build`, the consolidated protocols' own evidence, so their library stays. */
    @RequiresEdt
    fun testConsolidatedLibrarySurvivesDeletingDeps() {
        val app = createAppWithDep("deps_deleted_app", "phoenix")
        val service = project.service<MixDepsSyncService>()
        syncDep(service, "deps_deleted_app", "phoenix")
        assertLibraryExists(consolidatedName(app))
        assertTrue("precondition: the module depends on it", consolidatedName(app) in moduleLibraryEntryNames())

        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DeleteAll("${app.url}/deps"))
        drainDirectly(service)

        assertLibraryExists(consolidatedName(app))
        assertTrue("its module entry must stay", consolidatedName(app) in moduleLibraryEntryNames())
    }

    @RequiresEdt
    fun testConsolidatedLibraryRemovedWhenBuildDirDeleted() {
        val app = createAppWithDep("clean_app", "phoenix")
        val service = project.service<MixDepsSyncService>()
        syncDep(service, "clean_app", "phoenix")
        assertLibraryExists(consolidatedName(app))

        WriteAction.run<Throwable> { app.findChild("_build")!!.delete(this) }
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.SyncRoot(app.url))
        drainDirectly(service)

        assertNull(
            "The consolidated library must go with its _build directory",
            libraryTable().getLibraryByName(consolidatedName(app))
        )
        assertFalse(
            "a module must not keep depending on the library the sync removed",
            consolidatedName(app) in moduleLibraryEntryNames(),
        )
    }

    fun testConsolidatedLibraryRemovedWhenContentRootRemoved() {
        val gone = createAppWithDep("gone_app", "phoenix")
        createAppWithDep("stay_app", "ecto")
        val service = project.service<MixDepsSyncService>()
        syncDep(service, "gone_app", "phoenix")
        assertLibraryExists(consolidatedName(gone))

        ModuleRootModificationUtil.updateModel(myFixture.module) { model ->
            model.contentEntries.first { it.url == gone.url }.let(model::removeContentEntry)
        }
        syncDep(service, "stay_app", "ecto")

        assertNull(
            "The consolidated library of a root that left the project must be removed",
            libraryTable().getLibraryByName(consolidatedName(gone))
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun moduleLibraryEntryNames(): List<String?> =
        ModuleRootManager.getInstance(myFixture.module).orderEntries
            .filterIsInstance<LibraryOrderEntry>()
            .map { it.libraryName }

    private fun createAppWithDep(rootPath: String, depName: String): VirtualFile {
        val app = MixTestFixtures.createMixRoot(myFixture, rootPath)
        myFixture.tempDirFixture.findOrCreateDir("$rootPath/deps/$depName/lib")
        MixTestFixtures.addBuildArtifacts(myFixture, rootPath, "dev", depName)
        return app
    }

    private fun syncDep(service: MixDepsSyncService, rootPath: String, depName: String) {
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.DepRoot(myFixture.tempDirFixture.getFile("$rootPath/deps/$depName")!!))
        drainDirectly(service)
    }

    private fun consolidatedName(root: VirtualFile) = consolidatedLibraryName(project, root.url)

    /** Adds the roots to the Mix library [name], which a sync that already ran may have made. */
    private fun createMixLibrary(name: String, classRootUrl: String? = null, sourceRootUrl: String? = null): Library =
        WriteAction.computeAndWait<Library, Throwable> {
            val model = libraryTable().modifiableModel
            val library = model.getLibraryByName(name) ?: model.createLibrary(name, MixLibraryKind)
            val roots = listOfNotNull(
                classRootUrl?.let { it to OrderRootType.CLASSES },
                sourceRootUrl?.let { it to OrderRootType.SOURCES },
            ).filter { (url, type) -> url !in library.getUrls(type) }
            if (roots.isNotEmpty()) {
                library.modifiableModel.let { libraryModel ->
                    roots.forEach { (url, type) -> libraryModel.addRoot(url, type) }
                    libraryModel.commit()
                }
            }
            model.commit()
            library
        }

    /** A content root outside the fixture, whose `temp://` roots have no project-relative token. */
    private fun withRelativeTokenRoot(prefix: String, block: (VirtualFile) -> Unit) {
        val root = LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(FileUtil.createTempDirectory(prefix, null, true))!!
        VfsRootAccess.allowRootAccess(myFixture.testRootDisposable, root.path)
        PsiTestUtil.addContentRoot(myFixture.module, root)
        MixSyncTestHelpers.awaitMixDepsSyncs(project)
        try {
            assertFalse("the fixture needs a root with a relative token", contentRootToken(project, root.url) == root.url)
            block(root)
        } finally {
            PsiTestUtil.removeContentEntry(myFixture.module, root)
        }
    }

    /** Removes a root from `phoenix [.]`. */
    private fun removeOnlyWritePlan(recreateWith: LibraryRoots?) = WritePlan(
        librariesToRemove = emptyList(),
        libraryWriteOps = listOf(
            LibraryWriteOp(
                libraryName = "phoenix [.]",
                createWithKind = false,
                addClassUrls = emptyList(),
                removeClassUrls = listOf("temp:///src/gone/ebin"),
                addSourceUrls = emptyList(),
                removeSourceUrls = emptyList(),
                recreateWith = recreateWith,
            ),
        ),
        placeholderLibraries = emptySet(),
        moduleWriteOps = emptyList(),
        legacyLibrariesToRemove = emptyList(),
    )

    private fun assertOneFullSyncClears(what: String) {
        val needsResync = { runSuspendOnPooledThread { MixLibraryReconciler.needsResync(project) } }
        assertTrue("$what must trigger a full sync", needsResync())
        val service = project.service<MixDepsSyncService>()
        service.clearPendingForTesting()
        service.enqueue(SyncRequest.All)
        drainDirectly(service)
        assertFalse("one full sync must clear $what", needsResync())
    }

    private fun libraryTable() = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

    /** The platform drops a library created in the same table model that removed its name. */
    private fun assertNoNameRemovedAndCreated(writePlan: WritePlan) {
        val removed = (writePlan.librariesToRemove + writePlan.legacyLibrariesToRemove).toSet()
        val created = writePlan.libraryWriteOps.map { it.libraryName } + writePlan.placeholderLibraries
        assertEmpty("Removed and created in one drain", created.filter { it in removed })
    }

    private fun assertLibraryExists(name: String): Library {
        val library = libraryTable().getLibraryByName(name)
        assertNotNull("Expected library '$name'; have ${libraryTable().libraries.map { it.name }}", library)
        return library!!
    }

    /** Asserted rather than skipped: a silent return would pass without testing anything. */
    private fun projectBasePath(): String {
        val basePath = project.basePath
        assertNotNull("Test fixture must provide a project base path", basePath)
        return basePath!!
    }

    /**
     * Runs an arbitrary [suspend] block on a pooled thread while the EDT (test thread) continues
     * pumping its event queue.  Delegates to [MixSyncTestHelpers.runSuspendOnPooledThread].
     */
    private fun <T> runSuspendOnPooledThread(block: suspend () -> T): T =
        MixSyncTestHelpers.runSuspendOnPooledThread(block = block)

    /**
     * Calls [MixDepsSyncService.drain] directly on a pooled thread (bypassing the 250 ms debounce),
     * while pumping the IDE event queue from the EDT test thread so that
     * [com.intellij.openapi.application.edtWriteAction] blocks dispatched by the drain coroutine
     * can execute.  Delegates to [MixSyncTestHelpers.drainDirectly].
     */
    private fun drainDirectly(service: MixDepsSyncService) = MixSyncTestHelpers.drainDirectly(service)
}
