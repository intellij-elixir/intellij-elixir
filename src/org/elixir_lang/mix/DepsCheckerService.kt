package org.elixir_lang.mix
import com.intellij.openapi.application.UI
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.progress.coroutineToIndicator
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.elixir_lang.mix.sync.MixEventClassifier
import org.elixir_lang.notification.setup_sdk.Notifier
import org.elixir_lang.package_manager.DepsStatusResult
import org.elixir_lang.package_manager.virtualFile
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType
import org.elixir_lang.sdk.elixir.findElixirSdkForRoot
import org.elixir_lang.sdk.erlang.Type as ErlangSdkType
import org.elixir_lang.settings.ElixirExperimentalSettings
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Overall verdict returned by [DepsCheckerService.checkDepsStatus].
 *
 * `internal` so that tests can assert directly on the type and its fields without reflection.
 */
internal sealed interface DepsCheckResult {
    data object NoSupported : DepsCheckResult
    data class NonOk(val root: VirtualFile) : DepsCheckResult
    data object AllOk : DepsCheckResult
    data class Error(val rootName: String, val message: String) : DepsCheckResult
}

@Service(Service.Level.PROJECT)
class DepsCheckerService(private val project: Project, private val cs: CoroutineScope) {
    private val checkMutex = Mutex()
    /** Roots whose deps files changed during the current debounce window. */
    private val pendingRoots: AtomicReference<Set<VirtualFile>> = AtomicReference(emptySet())
    /** If set, an empty pending set uses cached-only recompute (plus uncached roots) instead of full re-query. */
    private val useCachedOnlyWhenNoPending: AtomicReference<Boolean> = AtomicReference(false)
    /** Last-known SDK name by root URL from the most recent checks. */
    private val rootSdkNameSnapshot: AtomicReference<Map<String, String?>> = AtomicReference(emptyMap())
    /**
     * Last-known status for each top-level Mix root (keyed by root URL).
     * Mutated only inside [checkMutex]; no additional synchronisation needed.
     */
    private val rootStatusCache = HashMap<String, CachedRootStatus>()
    private val checkFlow = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // ── Internal accessors (test seam) ────────────────────────────────────────

    /** Snapshot of the URLs currently in the pending-roots set. */
    internal val pendingRootUrls: Set<String>
        get() = pendingRoots.get().mapTo(HashSet()) { it.url }

    /** Atomically replaces the pending-roots set. */
    internal fun setPendingRoots(roots: Set<VirtualFile>) {
        pendingRoots.set(roots)
    }

    /** The "use cached-only when no pending roots" scheduling flag. */
    internal var cachedOnlyMode: Boolean
        get() = useCachedOnlyWhenNoPending.get()
        set(v) { useCachedOnlyWhenNoPending.set(v) }

    /** The last-committed SDK-name-by-root-URL snapshot. */
    internal var sdkNameSnapshot: Map<String, String?>
        get() = rootSdkNameSnapshot.get()
        set(v) { rootSdkNameSnapshot.set(v) }

    /** Snapshot of root URLs currently in the status cache. */
    internal val statusCacheKeys: Set<String>
        get() = rootStatusCache.keys.toSet()

    /** Returns the cached status for [url], or `null` if the root has no cached entry. */
    internal fun cachedStatusForUrl(url: String): CachedRootStatus? = rootStatusCache[url]

    /**
     * Resets all mutable scheduling state.
     *
     * Intended for test `setUp`/`tearDown` only.  **Not** mutex-protected - only safe when no
     * concurrent check is running (which is always the case in test tear-down).
     */
    internal fun resetState() {
        pendingRoots.set(emptySet())
        useCachedOnlyWhenNoPending.set(false)
        rootSdkNameSnapshot.set(emptyMap())
        rootStatusCache.clear()
    }

    // ── Init: listeners + debounce loop ───────────────────────────────────────

    init {
        val connection = project.messageBus.connect(cs)

        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (!depsCheckEnabled()) return
                    val affectedRoots = MixEventClassifier.findAffectedMixRoots(project, events)
                    if (affectedRoots.isNotEmpty()) {
                        pendingRoots.getAndUpdate { it + affectedRoots }
                        requestDebouncedCheck("deps change")
                    }
                }
            }
        )

        connection.subscribe(ProjectJdkTable.JDK_TABLE_TOPIC, object : ProjectJdkTable.Listener {
            override fun jdkAdded(jdk: Sdk) {
                if (!depsCheckEnabled()) return
                if (isRelevantSdk(jdk)) {
                    scheduleTopologyAwareCheck("sdk added")
                }
            }

            override fun jdkRemoved(jdk: Sdk) {
                if (!depsCheckEnabled()) return
                if (isRelevantSdk(jdk)) {
                    scheduleTopologyAwareCheck("sdk removed")
                }
            }

            override fun jdkNameChanged(jdk: Sdk, previousName: String) {
                if (!depsCheckEnabled()) return
                if (isRelevantSdk(jdk)) {
                    scheduleTopologyAwareCheck("sdk renamed")
                }
            }
        })

        connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) {
                scheduleTopologyAwareCheck("module roots changed")
            }
        })

        @OptIn(FlowPreview::class)
        cs.launch {
            checkFlow
                .debounce(DEPS_CHECK_DEBOUNCE_MS.milliseconds)
                .collect { reason ->
                    supervisorScope {
                        val checkAttempt = async { runCheck(reason) }

                        try {
                            checkAttempt.await()
                        } catch (
                            @Suppress("IncorrectCancellationExceptionHandling")
                            _: CancellationException
                        ) {
                            currentCoroutineContext().ensureActive()
                            LOG.debug("DepsCheckerService: check cancelled, will retry on next trigger")
                        } catch (e: Throwable) {
                            LOG.error("DepsCheckerService: check failed, will retry on next trigger", e)
                        }
                    }
                }
        }
    }

    fun scheduleInitialCheck() {
        cs.launch { runCheck("startup") }
    }

    // skips the debounce to run the check immediately, i.e. the user requested the check, so don't wait.
    fun scheduleCheckNow(reason: String) {
        cs.launch { runCheck(reason) }
    }

    private fun requestDebouncedCheck(reason: String, cachedOnlyWhenNoPending: Boolean = false) {
        if (cachedOnlyWhenNoPending) {
            useCachedOnlyWhenNoPending.set(true)
        }
        checkFlow.tryEmit(reason)
    }

    private fun isRelevantSdk(sdk: Sdk): Boolean = sdk.sdkType is ElixirSdkType || sdk.sdkType is ErlangSdkType

    /** Whether the experimental Mix deps check feature is enabled in settings. */
    private fun depsCheckEnabled(): Boolean =
        ElixirExperimentalSettings.instance.state.enableMixDepsCheck

    /**
     * Finds roots affected by SDK/root topology changes and schedules a targeted re-check.
     *
     * Uses [DepsCheckPlanner.computeSdkDelta] to diff the current SDK assignment against the
     * last-known snapshot, adding only the changed roots to [pendingRoots].
     */
    private fun scheduleTopologyAwareCheck(reason: String) {
        if (!depsCheckEnabled()) return
        cs.launch(Dispatchers.Default) {
            if (project.isDisposed) return@launch

            val (changedRoots, removedRootExists) = readAction {
                val allRoots = ProjectRootManager.getInstance(project).contentRoots.asList()
                val allTopLevelRoots = MixEventClassifier.selectTopLevelMixRoots(allRoots)
                val currentSdkByRootUrl = LinkedHashMap<String, String?>(allTopLevelRoots.size)
                for (root in allTopLevelRoots) {
                    currentSdkByRootUrl[root.url] = findElixirSdkForRoot(project, root)?.name
                }
                val delta = DepsCheckPlanner.computeSdkDelta(
                    allTopLevelRoots.map { it.url },
                    currentSdkByRootUrl,
                    rootSdkNameSnapshot.get(),
                )
                val changedRoots = allTopLevelRoots.filter { it.url in delta.changedRootUrls }
                Pair(changedRoots, delta.removedRootExists)
            }

            if (changedRoots.isNotEmpty()) {
                pendingRoots.getAndUpdate { it + changedRoots }
            }
            if (changedRoots.isNotEmpty() || removedRootExists) {
                requestDebouncedCheck(reason, cachedOnlyWhenNoPending = true)
            }
        }
    }

    /**
     * Evaluates settings, runs [checkDepsStatus], and dispatches the appropriate [Notifier]
     * call on the EDT.
     *
     * `internal` so tests can call it directly to assert notification side-effects without
     * going through the debounced flow.
     */
    internal suspend fun runCheck(reason: String) {
        if (!depsCheckEnabled()) {
            LOG.debug("DepsCheckerService: Mix deps check disabled in settings")
            return
        }

        val result = checkDepsStatus(reason) ?: return

        withContext(Dispatchers.UI) {
            if (project.isDisposed) return@withContext
            when (result) {
                is DepsCheckResult.Error -> Notifier.mixDepsCheckFailed(project, result.rootName, result.message)
                is DepsCheckResult.NonOk -> Notifier.mixDepsOutdated(project, result.root)
                DepsCheckResult.AllOk -> Notifier.clearMixDepsOutdated(project)
                DepsCheckResult.NoSupported -> Unit
            }
        }
    }

    /**
     * Runs the deps status check under [checkMutex], returning the result for notification.
     * Returns `null` if the project is disposed (caller should bail out).
     *
     * Only the roots in [pendingRoots] (plus any root not yet in [rootStatusCache]) are
     * actually re-queried via [depsStatusResult]; all other roots reuse their cached status.
     * This keeps event-triggered checks O(|changed roots|) rather than O(|all roots|).
     *
     * Root selection is delegated to [DepsCheckPlanner.selectRootsToCheck].
     *
     * `internal` so tests can await the verdict directly without going through the debounced
     * flow or using reflection.
     */
    internal suspend fun checkDepsStatus(reason: String): DepsCheckResult? = checkMutex.withLock {
        if (project.isDisposed) return@withLock null

        val pendingSet = pendingRoots.getAndSet(emptySet())
        val cachedOnlyWhenNoPending = useCachedOnlyWhenNoPending.getAndSet(false)
        val allTopLevelRoots = readAction {
            val allRoots = ProjectRootManager.getInstance(project).contentRoots.asList()
            MixEventClassifier.selectTopLevelMixRoots(allRoots)
        }

        // Evict cache entries for roots that no longer exist in the project.
        val currentRootUrls = allTopLevelRoots.mapTo(HashSet()) { it.url }
        rootStatusCache.keys.retainAll(currentRootUrls)

        // Determine which roots to actually call depsStatus on.
        val rootUrlsToCheck = DepsCheckPlanner.selectRootsToCheck(
            allTopLevelRoots.map { it.url },
            pendingSet.mapTo(HashSet()) { it.url },
            cachedOnlyWhenNoPending,
            rootStatusCache.keys.toHashSet(),
        )
        val rootByUrl = allTopLevelRoots.associateBy { it.url }
        val rootsToCheck = rootUrlsToCheck.mapNotNull { rootByUrl[it] }

        LOG.debug(
            "DepsCheckerService: Checking Mix deps ($reason) for " +
                "${rootsToCheck.size}/${allTopLevelRoots.size} root(s)"
        )

        val sdkNameByRootUrl = rootSdkNameSnapshot.get().toMutableMap()
        sdkNameByRootUrl.keys.retainAll(currentRootUrls)

        // Re-check and cache only the selected roots.
        for (root in rootsToCheck) {
            val sdk = readAction { findElixirSdkForRoot(project, root) }
            sdkNameByRootUrl[root.url] = sdk?.name
            rootStatusCache[root.url] = when (
                // `coroutineToIndicator`, so the blocking wait on `mix deps` ends when this coroutine is cancelled.
                val statusResult = withContext(Dispatchers.IO) {
                    coroutineToIndicator { depsStatusResult(project, root, sdk) }
                }
            ) {
                is DepsStatusResult.Available ->
                    if (statusResult.status.hasNonOk) CachedRootStatus.NonOk else CachedRootStatus.Ok
                is DepsStatusResult.Error -> CachedRootStatus.Error(statusResult.message)
                DepsStatusResult.Unsupported -> CachedRootStatus.Unsupported
            }
        }
        rootSdkNameSnapshot.set(sdkNameByRootUrl)

        // Derive the overall verdict from the full (now-updated) cache.
        var sawSupported = false
        var firstNonOkRoot: VirtualFile? = null
        for (root in allTopLevelRoots) {
            when (val entry = rootStatusCache[root.url] ?: CachedRootStatus.Unsupported) {
                CachedRootStatus.Ok -> sawSupported = true
                CachedRootStatus.NonOk -> {
                    sawSupported = true
                    if (firstNonOkRoot == null) firstNonOkRoot = root
                }
                is CachedRootStatus.Error ->
                    return@withLock DepsCheckResult.Error(root.name, entry.message)
                CachedRootStatus.Unsupported -> Unit
            }
        }

        when {
            !sawSupported -> DepsCheckResult.NoSupported
            firstNonOkRoot != null -> DepsCheckResult.NonOk(firstNonOkRoot)
            else -> DepsCheckResult.AllOk
        }
    }

    private fun depsStatusResult(project: Project, projectRoot: VirtualFile, sdk: Sdk?): DepsStatusResult {
        val packageManagerVirtualFile = virtualFile(projectRoot)
            ?: return DepsStatusResult.Unsupported
        val (packageManager, packageVirtualFile) = packageManagerVirtualFile
        return packageManager.depsStatus(project, packageVirtualFile, sdk)
    }

    companion object {
        private const val DEPS_CHECK_DEBOUNCE_MS: Long = 1500
        private val LOG = logger<DepsCheckerService>()
    }
}
