package org.elixir_lang.mix

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SimpleJavaSdkType
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.testFramework.PlatformTestUtil
import org.elixir_lang.mix.sync.MixTestFixtures
import org.elixir_lang.notification.setup_sdk.Notifier
import org.elixir_lang.package_manager.*
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType
import org.elixir_lang.sdk.erlang.Type as ErlangSdkType

/**
 * Integration tests for [DepsCheckerService].
 *
 * After the testability refactoring, every assertion goes through the service's `internal`
 * API ([DepsCheckerService.checkDepsStatus], [DepsCheckerService.runCheck], the accessor
 * properties) and [Notifier.hasActiveMixDepsOutdatedNotification].  No reflection is used.
 *
 * Pure algorithmic tests (root selection, SDK delta) live in [DepsCheckPlannerTest].
 *
 * Shared infrastructure (fake package manager, wait helpers, etc.) lives in
 * [DepsCheckerServiceTestBase].
 */
class DepsCheckerServiceTest : DepsCheckerServiceTestBase() {
    // ── Verdict correctness ───────────────────────────────────────────────────

    fun testCheckDepsStatus_pendingOkRootDoesNotHideOutdatedSiblingRoot() {
        val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val rootB = MixTestFixtures.createMixRoot(myFixture, "project_b")

        statusByRootUrl[rootA.url] = depsStatus(DepState.OK)
        statusByRootUrl[rootB.url] = depsStatus(DepState.OUTDATED)

        val service = project.service<DepsCheckerService>()
        withMixDepsCheckEnabled(false) {
            service.setPendingRoots(linkedSetOf(rootA))

            val result = runSuspendOnPooledThread { service.checkDepsStatus("test") }

            assertTrue(
                "checkDepsStatus should report the still-outdated sibling root instead of clearing",
                result is DepsCheckResult.NonOk,
            )
            val nonOkRoot = (result as DepsCheckResult.NonOk).root
            assertTrue(
                "Expected sibling root ${rootB.path} to be reported, got ${nonOkRoot.path}",
                com.intellij.openapi.util.io.FileUtil.pathsEqual(rootB.path, nonOkRoot.path),
            )
        }
    }

    fun testCheckDepsStatus_returnsErrorWhenRootReportsError() {
        val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val rootB = MixTestFixtures.createMixRoot(myFixture, "project_b")
        statusByRootUrl[rootA.url] = depsStatus(DepState.OK)
        statusByRootUrl[rootB.url] = DepsStatusResult.Error("mix deps.get failed")

        val service = project.service<DepsCheckerService>()
        withMixDepsCheckEnabled(false) {
            val result = runSuspendOnPooledThread { service.checkDepsStatus("error") }

            assertTrue("checkDepsStatus should surface a per-root Error result", result is DepsCheckResult.Error)
            val error = result as DepsCheckResult.Error
            assertEquals("Error result should carry the failing root's name", rootB.name, error.rootName)
            assertEquals("Error result should carry the package-manager message", "mix deps.get failed", error.message)
            assertTrue(
                "failing root should be cached as Error",
                service.cachedStatusForUrl(rootB.url) is CachedRootStatus.Error,
            )
        }
    }

    fun testCheckDepsStatus_allUnsupported_returnsNoSupported() {
        val root = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val service = project.service<DepsCheckerService>()

        withMixDepsCheckEnabled(false) {
            val result = runSuspendOnPooledThread { service.checkDepsStatus("unsupported") }
            assertTrue("All-Unsupported project should resolve to NoSupported", result is DepsCheckResult.NoSupported)
            assertEquals(
                "The unsupported root should be cached as Unsupported",
                CachedRootStatus.Unsupported,
                service.cachedStatusForUrl(root.url),
            )
        }
    }

    // ── Notification pipeline ─────────────────────────────────────────────────

    fun testRunCheck_transitionsOutdatedNotificationToClear() {
        val root = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val service = project.service<DepsCheckerService>()

        statusByRootUrl[root.url] = depsStatus(DepState.OUTDATED)
        service.setPendingRoots(setOf(root))
        withMixDepsCheckEnabled { runSuspendOnPooledThread { service.runCheck("outdated") } }
        assertTrue("expected outdated notification to be present", Notifier.hasActiveMixDepsOutdatedNotification(project))

        statusByRootUrl[root.url] = depsStatus(DepState.OK)
        service.setPendingRoots(setOf(root))
        withMixDepsCheckEnabled { runSuspendOnPooledThread { service.runCheck("clear") } }
        assertFalse("expected outdated notification to be cleared", Notifier.hasActiveMixDepsOutdatedNotification(project))
    }

    fun testRunCheck_rebindsOutdatedNotificationWhenFailingRootChanges() {
        val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val rootB = MixTestFixtures.createMixRoot(myFixture, "project_b")
        val service = project.service<DepsCheckerService>()

        statusByRootUrl[rootA.url] = depsStatus(DepState.OUTDATED)
        statusByRootUrl[rootB.url] = depsStatus(DepState.OK)
        service.setPendingRoots(setOf(rootA, rootB))
        withMixDepsCheckEnabled { runSuspendOnPooledThread { service.runCheck("initial outdated") } }

        assertEquals(rootA.url, Notifier.activeMixDepsOutdatedRootUrl(project))

        statusByRootUrl[rootA.url] = depsStatus(DepState.OK)
        statusByRootUrl[rootB.url] = depsStatus(DepState.OUTDATED)
        service.setPendingRoots(setOf(rootA, rootB))
        val notifications = capturedNotifications {
            withMixDepsCheckEnabled { runSuspendOnPooledThread { service.runCheck("rebind outdated") } }
        }

        assertTrue(Notifier.hasActiveMixDepsOutdatedNotification(project))
        assertEquals(rootB.url, Notifier.activeMixDepsOutdatedRootUrl(project))

        val outdated = notifications.lastOrNull { it.title.startsWith(Notifier.MIX_DEPS_OUTDATED_TITLE) }
        assertNotNull("expected refreshed outdated notification", outdated)
        assertEquals("${Notifier.MIX_DEPS_OUTDATED_TITLE} (${rootB.name})", outdated!!.title)
    }

    fun testRunCheck_firesCheckFailedNotificationOnError() {
        val root = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val service = project.service<DepsCheckerService>()

        statusByRootUrl[root.url] = DepsStatusResult.Error("boom from mix")
        service.setPendingRoots(setOf(root))

        val notifications = capturedNotifications {
            withMixDepsCheckEnabled { runSuspendOnPooledThread { service.runCheck("error") } }
        }

        val failure = notifications.firstOrNull { it.title.startsWith("Mix deps check failed") }
        assertNotNull(
            "expected a 'Mix deps check failed' notification, got ${notifications.map { it.title }}",
            failure,
        )
        assertEquals("Mix deps check failed (${root.name})", failure!!.title)
        assertEquals("boom from mix", failure.content)
        assertFalse(
            "Error path must not create the tracked outdated notification",
            Notifier.hasActiveMixDepsOutdatedNotification(project),
        )
    }

    fun testRunCheck_noSupportedRoots_doesNotNotify() {
        val root = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val service = project.service<DepsCheckerService>()

        service.setPendingRoots(setOf(root))
        val notifications = capturedNotifications {
            withMixDepsCheckEnabled { runSuspendOnPooledThread { service.runCheck("unsupported") } }
        }
        assertTrue(
            "NoSupported verdict must not raise any Mix deps notification, got ${notifications.map { it.title }}",
            notifications.none { it.title.contains("Mix deps") },
        )
        assertFalse(Notifier.hasActiveMixDepsOutdatedNotification(project))
    }

    // ── SDK topology: targeted re-check ──────────────────────────────────────

    fun testJdkTopic_triggersTargetedRecheckForChangedRootsOnly() {
        val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val rootB = MixTestFixtures.createMixRoot(myFixture, "project_b")
        statusByRootUrl[rootA.url] = depsStatus(DepState.OK)
        statusByRootUrl[rootB.url] = depsStatus(DepState.OK)

        val service = project.service<DepsCheckerService>()
        runSuspendOnPooledThread { service.checkDepsStatus("prime") }
        resetDepsStatusCallCounts()

        service.sdkNameSnapshot = mapOf(rootA.url to null, rootB.url to "stale-sdk-name")

        val triggerSdk = ProjectJdkImpl("Elixir trigger", ElixirSdkType.instance)
        withMixDepsCheckEnabled {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(ProjectJdkTable.JDK_TABLE_TOPIC)
                .jdkAdded(triggerSdk)
        }

        waitUntil("expected only rootB to be marked pending after SDK topic event") {
            service.pendingRootUrls == setOf(rootB.url)
        }
        runSuspendOnPooledThread { service.checkDepsStatus("jdk-topic-targeted-check") }

        assertEquals("rootA should not be re-queried", 0, depsStatusCallCount(rootA))
        assertEquals("rootB should be re-queried exactly once", 1, depsStatusCallCount(rootB))
    }

    fun testJdkTopic_removedAndRenamedTriggerTargetedRecheck() {
        val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val rootB = MixTestFixtures.createMixRoot(myFixture, "project_b")
        statusByRootUrl[rootA.url] = depsStatus(DepState.OK)
        statusByRootUrl[rootB.url] = depsStatus(DepState.OK)

        val service = project.service<DepsCheckerService>()
        runSuspendOnPooledThread { service.checkDepsStatus("prime") }

        resetDepsStatusCallCounts()
        service.sdkNameSnapshot = mapOf(rootA.url to "stale-sdk-name", rootB.url to null)
        val triggerSdk = ProjectJdkImpl("Elixir trigger", ElixirSdkType.instance)
        withMixDepsCheckEnabled {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(ProjectJdkTable.JDK_TABLE_TOPIC)
                .jdkRemoved(triggerSdk)
        }

        waitUntil("expected only rootA to be marked pending after jdkRemoved") {
            service.pendingRootUrls == setOf(rootA.url)
        }
        runSuspendOnPooledThread { service.checkDepsStatus("jdk-removed-targeted-check") }
        assertEquals(1, depsStatusCallCount(rootA))
        assertEquals(0, depsStatusCallCount(rootB))

        resetDepsStatusCallCounts()
        service.sdkNameSnapshot = mapOf(rootA.url to null, rootB.url to "stale-sdk-name")
        withMixDepsCheckEnabled {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(ProjectJdkTable.JDK_TABLE_TOPIC)
                .jdkNameChanged(triggerSdk, "old-name")
        }

        waitUntil("expected only rootB to be marked pending after jdkNameChanged") {
            service.pendingRootUrls == setOf(rootB.url)
        }
        runSuspendOnPooledThread { service.checkDepsStatus("jdk-renamed-targeted-check") }
        assertEquals(0, depsStatusCallCount(rootA))
        assertEquals(1, depsStatusCallCount(rootB))
    }

    fun testJdkTopic_noSdkDeltaDoesNotScheduleRecheck() {
        val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val rootB = MixTestFixtures.createMixRoot(myFixture, "project_b")
        statusByRootUrl[rootA.url] = depsStatus(DepState.OK)
        statusByRootUrl[rootB.url] = depsStatus(DepState.OK)

        val service = project.service<DepsCheckerService>()
        runSuspendOnPooledThread { service.checkDepsStatus("prime") }
        service.sdkNameSnapshot = mapOf(rootA.url to null, rootB.url to null)
        resetDepsStatusCallCounts()

        val triggerSdk = ProjectJdkImpl("Elixir trigger", ElixirSdkType.instance)
        withMixDepsCheckEnabled {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(ProjectJdkTable.JDK_TABLE_TOPIC)
                .jdkAdded(triggerSdk)
        }

        spinEventQueue()

        assertTrue("no-op SDK event should not enqueue pending roots", service.pendingRootUrls.isEmpty())
        assertFalse("no-op SDK event should not switch to cached-only mode", service.cachedOnlyMode)
    }

    fun testNonRelevantSdk_eventDoesNotScheduleRecheck() {
        val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val rootB = MixTestFixtures.createMixRoot(myFixture, "project_b")
        statusByRootUrl[rootA.url] = depsStatus(DepState.OK)
        statusByRootUrl[rootB.url] = depsStatus(DepState.OK)

        val service = project.service<DepsCheckerService>()
        runSuspendOnPooledThread { service.checkDepsStatus("prime") }
        service.sdkNameSnapshot = mapOf(rootA.url to "stale-a", rootB.url to "stale-b")
        resetDepsStatusCallCounts()

        val javaSdk = ProjectJdkImpl("Java Mock", SimpleJavaSdkType())
        withMixDepsCheckEnabled {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(ProjectJdkTable.JDK_TABLE_TOPIC)
                .jdkAdded(javaSdk)
        }

        spinEventQueue()

        assertTrue("non-Elixir/Erlang SDK event must be ignored (no roots queued)", service.pendingRootUrls.isEmpty())
        assertFalse("non-Elixir/Erlang SDK event must not switch to cached-only mode", service.cachedOnlyMode)
    }

    fun testErlangSdk_eventIsTreatedAsRelevant() {
        val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val rootB = MixTestFixtures.createMixRoot(myFixture, "project_b")
        statusByRootUrl[rootA.url] = depsStatus(DepState.OK)
        statusByRootUrl[rootB.url] = depsStatus(DepState.OK)

        val service = project.service<DepsCheckerService>()
        runSuspendOnPooledThread { service.checkDepsStatus("prime") }
        service.sdkNameSnapshot = mapOf(rootA.url to null, rootB.url to "stale-sdk-name")
        resetDepsStatusCallCounts()

        val erlangSdk = ProjectJdkImpl("Erlang trigger", ErlangSdkType.instance)
        withMixDepsCheckEnabled {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(ProjectJdkTable.JDK_TABLE_TOPIC)
                .jdkAdded(erlangSdk)
        }

        waitUntil("expected an Erlang SDK event to queue the changed root") {
            service.pendingRootUrls == setOf(rootB.url)
        }
        runSuspendOnPooledThread { service.checkDepsStatus("erlang-targeted-check") }
        assertEquals("unchanged root must not be re-queried", 0, depsStatusCallCount(rootA))
        assertEquals("changed root must be re-queried exactly once", 1, depsStatusCallCount(rootB))
    }

    // ── Module-root topology changes ──────────────────────────────────────────

    fun testRootsChanged_addedRootChecksOnlyNewRoot() {
        val suffix = System.nanoTime().toString()
        val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a_added_$suffix")
        statusByRootUrl[rootA.url] = depsStatus(DepState.OK)
        val rootB = createMixRootWithoutContentRoot("project_b_added_$suffix")
        statusByRootUrl[rootB.url] = depsStatus(DepState.OK)

        val service = project.service<DepsCheckerService>()
        runSuspendOnPooledThread { service.checkDepsStatus("prime") }
        service.sdkNameSnapshot = mapOf(rootA.url to null, rootB.url to "stale-sdk-name")
        resetDepsStatusCallCounts()

        withMixDepsCheckEnabled { addContentRoot(rootB) }

        waitUntil("expected newly-added root to be queued for targeted check") {
            service.pendingRootUrls.contains(rootB.url)
        }
        service.setPendingRoots(setOf(rootB))

        runSuspendOnPooledThread { service.checkDepsStatus("root-added-targeted-check") }

        assertEquals(0, depsStatusCallCount(rootA))
        assertEquals(1, depsStatusCallCount(rootB))
    }

    fun testRootsChanged_mixedChangedAndRemovedRoots() {
        val sdk = createAndRegisterElixirSdk()
        ModuleRootModificationUtil.setModuleSdk(module, sdk)

        val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val rootB = MixTestFixtures.createMixRoot(myFixture, "project_b")
        val rootC = MixTestFixtures.createMixRoot(myFixture, "project_c")
        statusByRootUrl[rootA.url] = depsStatus(DepState.OK)
        statusByRootUrl[rootB.url] = depsStatus(DepState.OK)
        statusByRootUrl[rootC.url] = depsStatus(DepState.OK)

        val service = project.service<DepsCheckerService>()
        runSuspendOnPooledThread { service.checkDepsStatus("prime") }
        resetDepsStatusCallCounts()
        service.sdkNameSnapshot = mapOf(
            rootA.url to sdk.name,
            rootB.url to "stale-sdk-name",
            rootC.url to sdk.name,
        )

        withMixDepsCheckEnabled { removeContentRoot(rootC) }

        waitUntil("expected changed root pending and cached-only mode after mixed topology change") {
            service.pendingRootUrls == setOf(rootB.url) && service.cachedOnlyMode
        }
        runSuspendOnPooledThread { service.checkDepsStatus("mixed-topology-check") }

        assertEquals(0, depsStatusCallCount(rootA))
        assertEquals(1, depsStatusCallCount(rootB))
        assertFalse("removed root should be evicted from the cache", service.statusCacheKeys.contains(rootC.url))
    }

    fun testRootsChanged_cacheOnlyPathWhenRootRemoved() {
        val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val rootB = MixTestFixtures.createMixRoot(myFixture, "project_b")
        statusByRootUrl[rootA.url] = depsStatus(DepState.OK)
        statusByRootUrl[rootB.url] = depsStatus(DepState.OK)

        val service = project.service<DepsCheckerService>()
        runSuspendOnPooledThread { service.checkDepsStatus("prime") }
        resetDepsStatusCallCounts()

        withMixDepsCheckEnabled { removeContentRoot(rootB) }

        waitUntil("expected cache-only mode after rootsChanged with removed root") {
            service.cachedOnlyMode
        }
        assertTrue("no roots should be pending when only a removal was detected", service.pendingRootUrls.isEmpty())

        runSuspendOnPooledThread { service.checkDepsStatus("roots-changed-cache-only-check") }

        assertEquals("cache-only check should not re-run deps status for cached roots", 0, depsStatusCallCount(rootA))
        assertFalse("removed root should be evicted from the cache", service.statusCacheKeys.contains(rootB.url))
    }

    fun testProjectWideSdkChange_queuesAllRoots() {
        val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a")
        val rootB = MixTestFixtures.createMixRoot(myFixture, "project_b")
        statusByRootUrl[rootA.url] = depsStatus(DepState.OK)
        statusByRootUrl[rootB.url] = depsStatus(DepState.OK)

        val service = project.service<DepsCheckerService>()
        runSuspendOnPooledThread { service.checkDepsStatus("prime") }
        resetDepsStatusCallCounts()
        service.sdkNameSnapshot = mapOf(rootA.url to "stale-sdk-name-a", rootB.url to "stale-sdk-name-b")

        val triggerSdk = ProjectJdkImpl("Elixir trigger", ElixirSdkType.instance)
        withMixDepsCheckEnabled {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(ProjectJdkTable.JDK_TABLE_TOPIC)
                .jdkAdded(triggerSdk)
        }

        waitUntil("expected all roots to be queued for broad SDK-impact recheck") {
            service.pendingRootUrls == setOf(rootA.url, rootB.url)
        }
        runSuspendOnPooledThread { service.checkDepsStatus("project-wide-targeted-check") }

        assertEquals(1, depsStatusCallCount(rootA))
        assertEquals(1, depsStatusCallCount(rootB))
    }

    // ── Disabled-setting gating: listeners do no work when the feature is off ──

    fun testJdkTopic_disabledSettingSkipsListenerWork() {
        val service = project.service<DepsCheckerService>()
        withMixDepsCheckEnabled(false) {
            val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a")
            val rootB = MixTestFixtures.createMixRoot(myFixture, "project_b")
            statusByRootUrl[rootA.url] = depsStatus(DepState.OK)
            statusByRootUrl[rootB.url] = depsStatus(DepState.OK)

            // Sentinel snapshot whose stale value for rootB would normally make the SDK-delta
            // listener queue rootB. With the feature disabled the listener must not run at all.
            val sentinelSnapshot = mapOf(rootA.url to null, rootB.url to "stale-sdk-name")
            service.setPendingRoots(emptySet())
            service.cachedOnlyMode = false
            service.sdkNameSnapshot = sentinelSnapshot

            val triggerSdk = ProjectJdkImpl("Elixir trigger", ElixirSdkType.instance)
            ApplicationManager.getApplication().messageBus
                .syncPublisher(ProjectJdkTable.JDK_TABLE_TOPIC)
                .jdkAdded(triggerSdk)

            spinEventQueue()

            assertTrue(
                "disabled deps check must not queue any pending roots from an SDK-table event. ${service.pendingRootUrls}",
                service.pendingRootUrls.isEmpty(),
            )
            assertFalse(
                "disabled deps check must not switch to cached-only mode from an SDK-table event",
                service.cachedOnlyMode,
            )
            assertEquals(
                "disabled deps check must not perform SDK-delta diff work (snapshot unchanged)",
                sentinelSnapshot,
                service.sdkNameSnapshot,
            )
        }
    }

    fun testRootsChanged_disabledSettingSkipsListenerWork() {
        val service = project.service<DepsCheckerService>()
        withMixDepsCheckEnabled(false) {
            val rootA = MixTestFixtures.createMixRoot(myFixture, "project_a")
            val rootB = MixTestFixtures.createMixRoot(myFixture, "project_b")
            statusByRootUrl[rootA.url] = depsStatus(DepState.OK)
            statusByRootUrl[rootB.url] = depsStatus(DepState.OK)

            val sentinelSnapshot = mapOf(rootA.url to null, rootB.url to "stale-sdk-name")
            service.setPendingRoots(emptySet())
            service.cachedOnlyMode = false
            service.sdkNameSnapshot = sentinelSnapshot

            // Removing a content root fires ModuleRootListener.rootsChanged on the bus.
            removeContentRoot(rootB)

            spinEventQueue()

            assertTrue(
                "disabled deps check must not queue any pending roots from a rootsChanged event. ${service.pendingRootUrls}",
                service.pendingRootUrls.isEmpty(),
            )
            assertFalse(
                "disabled deps check must not switch to cached-only mode from a rootsChanged event",
                service.cachedOnlyMode,
            )
            assertEquals(
                "disabled deps check must not perform topology diff work (snapshot unchanged)",
                sentinelSnapshot,
                service.sdkNameSnapshot,
            )
        }
    }

    // ── Helpers unique to this subclass ───────────────────────────────────────

    private fun removeContentRoot(root: com.intellij.openapi.vfs.VirtualFile) {
        ModuleRootModificationUtil.updateModel(module) { model ->
            val entry = model.contentEntries.firstOrNull { it.url == root.url }
                ?: error("Missing content entry for root ${root.url}")
            model.removeContentEntry(entry)
        }
    }

    private fun addContentRoot(root: com.intellij.openapi.vfs.VirtualFile) {
        ModuleRootModificationUtil.updateModel(module) { model ->
            if (model.contentEntries.none { it.url == root.url }) {
                model.addContentEntry(root)
            }
        }
    }

    private fun createMixRootWithoutContentRoot(rootPath: String): com.intellij.openapi.vfs.VirtualFile {
        val root = myFixture.tempDirFixture.findOrCreateDir(rootPath)
        myFixture.tempDirFixture.createFile(
            "$rootPath/mix.exs",
            "defmodule ${rootPath.replaceFirstChar(Char::uppercase)}.MixProject do\n  use Mix.Project\nend\n",
        )
        return root
    }

    private fun createAndRegisterElixirSdk(): Sdk {
        val sdk = ProjectJdkImpl("Elixir Test SDK", ElixirSdkType.instance)
        // Removed with testRootDisposable, after the module stops naming it.
        WriteAction.run<Throwable> {
            ProjectJdkTable.getInstance().addJdk(sdk, testRootDisposable)
        }
        return sdk
    }

    /**
     * Subscribes to [Notifications.TOPIC] on the project bus for the duration of [block] and
     * returns every [Notification] that was published. In unit-test mode notifications are
     * delivered synchronously on the publishing thread, so all notifications fired while [block]
     * runs (including those from the service's `Dispatchers.EDT` section) are captured.
     */
    private fun capturedNotifications(block: () -> Unit): List<Notification> {
        val captured = java.util.Collections.synchronizedList(mutableListOf<Notification>())
        val connection = project.messageBus.connect()
        try {
            connection.subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    captured.add(notification)
                }
            })
            block()
        } finally {
            connection.disconnect()
        }
        return ArrayList(captured)
    }

    /**
     * Spins the IDE event queue for 500 milliseconds without a wait condition.
     *
     * Used by "no-op" tests that need to assert that *nothing* was enqueued after an
     * irrelevant event is published. Use [waitUntil] instead whenever a positive
     * condition is expected.
     */
    private fun spinEventQueue() {
        val deadline = System.currentTimeMillis() + 500L
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }
}
