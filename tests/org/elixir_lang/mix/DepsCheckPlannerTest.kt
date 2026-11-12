package org.elixir_lang.mix

import org.elixir_lang.junit.UnitTestCase
import org.elixir_lang.sdk.erlang_dependent.ElixirSdkSnapshot

/**
 * Pure unit tests for [DepsCheckPlanner].
 *
 * No IntelliJ platform fixture is required - all inputs/outputs are plain strings and maps.
 * This makes the suite compile fast and run in milliseconds.
 */
class DepsCheckPlannerTest : UnitTestCase() {

    // ── selectRootsToCheck ────────────────────────────────────────────────────

    fun testSelectRootsToCheck_noPendingNoCacheNoCachedOnlyFlag_returnsAll() {
        val all = listOf("url://a", "url://b", "url://c")
        val result = DepsCheckPlanner.selectRootsToCheck(
            allTopLevelRootUrls = all,
            pendingRootUrls = emptySet(),
            cachedOnlyWhenNoPending = false,
            existingCacheKeys = emptySet(),
        )
        assertEquals(all, result)
    }

    fun testSelectRootsToCheck_noPendingFullCacheNoCachedOnlyFlag_stillReturnsAll() {
        // Without the topology flag, a full startup/explicit check always queries everything.
        val all = listOf("url://a", "url://b")
        val result = DepsCheckPlanner.selectRootsToCheck(
            allTopLevelRootUrls = all,
            pendingRootUrls = emptySet(),
            cachedOnlyWhenNoPending = false,
            existingCacheKeys = all.toHashSet(),
        )
        assertEquals(all, result)
    }

    fun testSelectRootsToCheck_cachedOnlyFlagNoPending_returnsOnlyUncachedRoots() {
        val all = listOf("url://a", "url://b", "url://c")
        val cached = setOf("url://a", "url://c")
        val result = DepsCheckPlanner.selectRootsToCheck(
            allTopLevelRootUrls = all,
            pendingRootUrls = emptySet(),
            cachedOnlyWhenNoPending = true,
            existingCacheKeys = cached,
        )
        assertEquals(listOf("url://b"), result)
    }

    fun testSelectRootsToCheck_cachedOnlyFlagNoPendingAllCached_returnsEmpty() {
        val all = listOf("url://a", "url://b")
        val result = DepsCheckPlanner.selectRootsToCheck(
            allTopLevelRootUrls = all,
            pendingRootUrls = emptySet(),
            cachedOnlyWhenNoPending = true,
            existingCacheKeys = all.toHashSet(),
        )
        assertTrue("Expected no roots to check when all are cached", result.isEmpty())
    }

    fun testSelectRootsToCheck_pendingRootsAddUncachedRoots() {
        // rootA is pending, rootB is also uncached -> both should be queried.
        val all = listOf("url://a", "url://b", "url://c")
        val pending = setOf("url://a")
        val cached = setOf("url://c")  // rootB ("url://b") is NOT cached
        val result = DepsCheckPlanner.selectRootsToCheck(
            allTopLevelRootUrls = all,
            pendingRootUrls = pending,
            cachedOnlyWhenNoPending = false,
            existingCacheKeys = cached,
        )
        // rootA (pending) + rootB (uncached); rootC is cached so NOT included.
        assertEquals(setOf("url://a", "url://b"), result.toSet())
    }

    fun testSelectRootsToCheck_pendingRootsAllAlreadyCached_stillIncludesPending() {
        // Even if a pending root is already cached, it must be re-queried (the cache is stale).
        val all = listOf("url://a", "url://b")
        val pending = setOf("url://b")
        val cached = setOf("url://a", "url://b")  // both are cached
        val result = DepsCheckPlanner.selectRootsToCheck(
            allTopLevelRootUrls = all,
            pendingRootUrls = pending,
            cachedOnlyWhenNoPending = false,
            existingCacheKeys = cached,
        )
        assertEquals(listOf("url://b"), result)
    }

    fun testSelectRootsToCheck_pendingRootNotInProject_isFiltered() {
        val all = listOf("url://a", "url://b")
        val pending = setOf("url://gone")  // no longer a live root
        val result = DepsCheckPlanner.selectRootsToCheck(
            allTopLevelRootUrls = all,
            pendingRootUrls = pending,
            cachedOnlyWhenNoPending = false,
            existingCacheKeys = all.toHashSet(),  // everything else is cached
        )
        assertTrue("Stale pending root should be dropped; no uncached roots to add", result.isEmpty())
    }

    fun testSelectRootsToCheck_pendingAndUncachedOverlap_noDuplicates() {
        // rootA appears both as pending AND is uncached -> should appear exactly once.
        val all = listOf("url://a", "url://b")
        val pending = setOf("url://a")
        val result = DepsCheckPlanner.selectRootsToCheck(
            allTopLevelRootUrls = all,
            pendingRootUrls = pending,
            cachedOnlyWhenNoPending = false,
            existingCacheKeys = emptySet(),  // neither is cached -> rootA is in both lists
        )
        assertEquals(1, result.count { it == "url://a" })
    }

    /**
     * Regression guard: when rootA (OK, pending) is re-queried but rootB (Outdated) is already
     * in the cache, the planner must NOT include rootB in the re-query list - rootB's cached
     * Outdated status will be read in the verdict sweep in the service.
     *
     * This is the algorithmic invariant that prevents "pending OK root hides outdated sibling".
     */
    fun testSelectRootsToCheck_pendingOkRootDoesNotAddAlreadyCachedSibling() {
        val rootA = "url://a"
        val rootB = "url://b"
        val all = listOf(rootA, rootB)
        val pending = setOf(rootA)
        val cached = setOf(rootB)  // rootB already has a (Outdated) entry - must NOT be re-queried
        val result = DepsCheckPlanner.selectRootsToCheck(
            allTopLevelRootUrls = all,
            pendingRootUrls = pending,
            cachedOnlyWhenNoPending = false,
            existingCacheKeys = cached,
        )
        assertFalse("Cached sibling must not be included in re-query list", rootB in result)
        assertTrue("Pending root must be included", rootA in result)
    }

    // ── computeSdkDelta ───────────────────────────────────────────────────────

    fun testComputeSdkDelta_noChange_emptyDelta() {
        val all = listOf("url://a", "url://b")
        val snapshotByRootUrl = mapOf("url://a" to named("Elixir 1.18"), "url://b" to null)
        val delta = DepsCheckPlanner.computeSdkDelta(all, snapshotByRootUrl, snapshotByRootUrl)
        assertTrue("Identical maps should produce no changed roots", delta.changedRootUrls.isEmpty())
        assertFalse("Identical maps should not report a removed root", delta.removedRootExists)
    }

    fun testComputeSdkDelta_sdkAssignedToRoot_thatRootIsChanged() {
        val all = listOf("url://a", "url://b")
        val previous = mapOf("url://a" to null, "url://b" to named("Elixir 1.18"))
        val current  = mapOf("url://a" to named("Elixir 1.18"), "url://b" to named("Elixir 1.18"))
        val delta = DepsCheckPlanner.computeSdkDelta(all, current, previous)
        assertEquals(setOf("url://a"), delta.changedRootUrls)
        assertFalse(delta.removedRootExists)
    }

    fun testComputeSdkDelta_sdkRemovedFromRoot_thatRootIsChanged() {
        val all = listOf("url://a")
        val previous = mapOf("url://a" to named("Elixir 1.18"))
        val current  = mapOf("url://a" to null)
        val delta = DepsCheckPlanner.computeSdkDelta(all, current, previous)
        assertEquals(setOf("url://a"), delta.changedRootUrls)
        assertFalse(delta.removedRootExists)
    }

    fun testComputeSdkDelta_erlangSdkPaired_thatRootIsChanged() {
        val all = listOf("url://a")
        val previous = mapOf("url://a" to named("Elixir 1.18"))
        val current  = mapOf("url://a" to ElixirSdkSnapshot("Elixir 1.18", null, null, "/erlang/27"))
        val delta = DepsCheckPlanner.computeSdkDelta(all, current, previous)
        assertEquals(setOf("url://a"), delta.changedRootUrls)
        assertFalse(delta.removedRootExists)
    }

    fun testComputeSdkDelta_sdkRenamed_thatRootIsChanged() {
        val all = listOf("url://a")
        val previous = mapOf("url://a" to named("Elixir 1.17"))
        val current  = mapOf("url://a" to named("Elixir 1.18"))
        val delta = DepsCheckPlanner.computeSdkDelta(all, current, previous)
        assertEquals(setOf("url://a"), delta.changedRootUrls)
        assertFalse(delta.removedRootExists)
    }

    fun testComputeSdkDelta_rootRemovedFromProject_removedRootExistsIsTrue() {
        // The removed root is no longer in allTopLevelRootUrls but WAS in the previous map.
        val allNow = listOf("url://a")
        val previous = mapOf("url://a" to named("Elixir 1.18"), "url://gone" to named("Elixir 1.18"))
        val current  = mapOf("url://a" to named("Elixir 1.18"))
        val delta = DepsCheckPlanner.computeSdkDelta(allNow, current, previous)
        assertTrue("Removed root should set the flag", delta.removedRootExists)
        assertTrue(
            "No changed roots - the surviving root is unchanged",
            delta.changedRootUrls.isEmpty(),
        )
    }

    fun testComputeSdkDelta_mixedChangedAndRemoved() {
        val allNow = listOf("url://a", "url://b")
        val previous = mapOf(
            "url://a" to named("stale-sdk"),
            "url://b" to null,
            "url://gone" to named("some-sdk"),
        )
        val current = mapOf(
            "url://a" to null,         // changed (sdk cleared)
            "url://b" to null,         // unchanged
        )
        val delta = DepsCheckPlanner.computeSdkDelta(allNow, current, previous)
        assertEquals(setOf("url://a"), delta.changedRootUrls)
        assertTrue("'url://gone' was in previous but not current", delta.removedRootExists)
    }

    fun testComputeSdkDelta_newRootWithNoMatchInPrevious_isReportedAsChanged() {
        // A new root has no entry in the previous map - null (absent) vs current value.
        val allNow = listOf("url://a", "url://new")
        val previous = mapOf("url://a" to named("Elixir 1.18"))  // "url://new" absent -> treated as null
        val current  = mapOf("url://a" to named("Elixir 1.18"), "url://new" to named("Elixir 1.18"))
        val delta = DepsCheckPlanner.computeSdkDelta(allNow, current, previous)
        assertEquals(setOf("url://new"), delta.changedRootUrls)
        assertFalse(delta.removedRootExists)
    }

    fun testComputeSdkDelta_rootNotInAllTopLevel_notReportedAsChanged_butCanStillBeMarkedRemoved() {
        // Roots that are NOT in allTopLevelRootUrls are ignored by computeSdkDelta.
        val allNow = listOf("url://a")
        val previous = mapOf("url://a" to null, "url://other" to named("some-sdk"))
        // Keep "url://other" out of the current map so it is treated as removed.
        val current  = mapOf("url://a" to null)
        val delta = DepsCheckPlanner.computeSdkDelta(allNow, current, previous)
        assertTrue(
            "Root not in allTopLevelRootUrls must not be reported as changed",
            delta.changedRootUrls.isEmpty(),
        )
        // removedRootExists depends on previous/current map keys, not allTopLevelRootUrls.
        // So a root excluded from changedRootUrls can still contribute to removedRootExists.
        assertTrue(delta.removedRootExists)
    }

    fun testComputeSdkDelta_rootNotInAllTopLevel_changedValue_isIgnoredAndNotMarkedRemoved() {
        // A root outside allTopLevelRootUrls is ignored for changedRootUrls even if its SDK value changes.
        val allNow = listOf("url://a")
        val previous = mapOf("url://a" to null, "url://other" to named("some-sdk"))
        val current = mapOf("url://a" to null, "url://other" to named("different-sdk"))

        val delta = DepsCheckPlanner.computeSdkDelta(allNow, current, previous)

        assertTrue(
            "Root not in allTopLevelRootUrls must not be reported as changed",
            delta.changedRootUrls.isEmpty(),
        )
        assertFalse(
            "Root present in both maps is not removed, even if its SDK value changed",
            delta.removedRootExists,
        )
    }

    private fun named(elixirSdkName: String) = ElixirSdkSnapshot(elixirSdkName, null, null, null)
}
