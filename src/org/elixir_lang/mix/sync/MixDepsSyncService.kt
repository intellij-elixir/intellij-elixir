package org.elixir_lang.mix.sync

import com.google.common.annotations.VisibleForTesting
import com.intellij.ide.SaveAndSyncHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.impl.libraries.LibraryEx
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.elixir_lang.mix.library.CONSOLIDATED_LIBRARY_BASE_NAME
import org.elixir_lang.util.awaitJpsProjectLoaded
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

/**
 * Project-level service that owns the lifecycle of the Mix dep-sync pipeline.
 *
 * All [SyncRequest]s - including deletes and per-module mix.exs re-syncs - flow through a single
 * serialized [MutableSharedFlow] / [debounce] / [Mutex] pipeline: deletes drain before syncs,
 * and only one sync operation runs at a time.
 *
 * Lifecycle:
 * - The service scope is injected by the platform and cancelled automatically on project close or plugin unload.
 * - Listeners call [enqueue] from VFS callback threads; this is always lightweight (classify + tryEmit).
 *
 * Coalescing rules (enforced in [drain]):
 * - A pending [SyncRequest.All] supersedes all pending [SyncRequest.DepsRoot] and [SyncRequest.DepRoot] requests.
 * - A pending [SyncRequest.DeleteAll] supersedes any pending [SyncRequest.DepsRoot] / [SyncRequest.DepRoot] for the same tree.
 * - Deletes are executed before syncs so that a delete followed immediately by a re-sync does not
 *   re-populate libraries that were intentionally removed.
 *
 * Write mutations use `edtWriteAction`: all model mutations dispatch to EDT + write-lock, which
 * avoids `invokeAndWait`-based deadlocks from coroutine context.
 *
 * Observability:
 * - [LOG] emits `debug`-level messages at drain-start and drain-complete with per-stage elapsed
 *   times: buildSyncPlan, buildWritePlan (read-phase snapshot + diff), and applyWritePlan
 *   (write-lock hold time - the key SLO metric).
 */
@Service(Service.Level.PROJECT)
class MixDepsSyncService(private val project: Project, cs: CoroutineScope) {

    // ------------------------------------------------------------------
    // Pending request accumulator (thread-safe, filled from VFS callbacks)
    // AtomicReference<Set> allows drain() to snapshot-and-clear atomically via getAndSet(),
    // eliminating the race between reading and clearing that exists with a plain mutable set.
    // ------------------------------------------------------------------
    private val pendingRequests: AtomicReference<Set<SyncRequest>> = AtomicReference(emptySet())

    /** How many requests have been enqueued, and how many of those a drain has finished with, for [awaitIdle]. */
    private val requested = AtomicLong()
    private val completed = MutableStateFlow(0L)

    // ------------------------------------------------------------------
    // Flow + debounce
    // replay = 1 ensures the most-recent trigger emission survives until the collector subscribes,
    // protecting against the startup-timing gap where cs.launch has not yet reached collect().
    // ------------------------------------------------------------------
    private val syncFlow = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // ------------------------------------------------------------------
    // Serialisation: only one drain executes at a time.
    // ------------------------------------------------------------------
    private val syncMutex = Mutex()

    init {
        // Nothing drains once the scope ends, on project close or plugin unload, so nothing is left to wait for.
        cs.coroutineContext.job.invokeOnCompletion { completed.value = Long.MAX_VALUE }

        @OptIn(FlowPreview::class)
        cs.launch {
            syncFlow
                .debounce(DEBOUNCE_MS.milliseconds)
                .collect {
                    // The `DelayedProjectSynchronizer` applies the on-disk `.iml` files after the
                    // workspace model is restored from cache (5-13s on WSL), and anything committed
                    // before it lands is silently overwritten - a sync that reports success and
                    // leaves nothing behind. Sync Dependency Libraries is `isDumbAware`, so a user
                    // can invoke it inside that window, which is exactly when libraries look
                    // wrong enough to reach for it. Gating the one collector covers every producer.
                    awaitJpsProjectLoaded(project)
                    if (project.isDisposed) return@collect

                    supervisorScope {
                        val drainAttempt = async { drain() }

                        try {
                            drainAttempt.await()
                        } catch (
                            @Suppress("IncorrectCancellationExceptionHandling")
                            _: CancellationException
                        ) {
                            currentCoroutineContext().ensureActive()
                            LOG.debug(
                                "MixDepsSyncService: drain cancelled; its requests are dropped, and a later change " +
                                    "under them or a full sync redoes a sync, but not a delete"
                            )
                        } catch (e: Throwable) {
                            LOG.error(
                                "MixDepsSyncService: drain failed; its requests are dropped, and a later change " +
                                    "under them or a full sync redoes a sync, but not a delete",
                                e,
                            )
                        }
                    }
                }
        }
    }

    // ------------------------------------------------------------------
    // Public API for thin listener adapters (called from VFS callbacks)
    // ------------------------------------------------------------------

    /**
     * Enqueues a sync request and triggers the debounce timer.
     *
     * This method is designed to be called from VFS listener callbacks. It is always lightweight:
     * it atomically updates the pending set and calls [MutableSharedFlow.tryEmit], which never suspends.
     */
    fun enqueue(request: SyncRequest) {
        pendingRequests.getAndUpdate { it + request }
        // Numbered only once it is in the pending set, which is what a drain's `covers` relies on.
        requested.incrementAndGet()
        syncFlow.tryEmit(Unit)
    }

    /**
     * Returns once every request enqueued before the call has been through a drain, whether it finished, failed or was
     * cancelled, or the service has stopped.
     */
    @TestOnly
    internal suspend fun awaitIdle() {
        val target = requested.get()
        completed.first { it >= target }
    }

    /** Whether every request enqueued so far has been through a drain, as [awaitIdle] counts it. */
    @get:TestOnly
    internal val isIdle: Boolean get() = completed.value >= requested.get()

    /** The counts [isIdle] compares, and the pending set's size, for a test that gave up waiting. */
    @get:TestOnly
    internal val counts: String
        get() = "requested=${requested.get()}, completed=${completed.value}, pending=$pendingCount"

    /** Number of requests currently waiting in the pending set. Exposed for tests only. */
    @VisibleForTesting
    internal val pendingCount: Int get() = pendingRequests.get().size

    /** Clears the pending-request accumulator. Must only be called from tests to reset state between test methods. */
    @VisibleForTesting
    internal fun clearPendingForTesting() {
        pendingRequests.set(emptySet())
    }

    // ------------------------------------------------------------------
    // Drain (runs inside coroutine, serialised by mutex)
    // ------------------------------------------------------------------
    @VisibleForTesting
    internal suspend fun drain() {
        syncMutex.withLock {
            // Read before the pending set is taken: every request numbered up to it is in that set, or was taken or
            // cleared before it.
            val covers = requested.get()
            try {
                drainPending()
            } finally {
                completed.update { maxOf(it, covers) }
            }
        }
    }

    private suspend fun drainPending() {
        if (project.isDisposed) return

        val rawRequests: List<SyncRequest> = pendingRequests.getAndSet(emptySet()).toList()
        if (rawRequests.isEmpty()) return

        val requests = resolvePathShapedRequests(project, rawRequests)
        if (requests.isEmpty()) return

        val drainStart = TimeSource.Monotonic.markNow()
        LOG.debug("MixDepsSyncService: draining ${requests.size} request(s)")

        val coalescedRequests = coalesceRequests(requests)

        val (syncPlan, buildSyncPlanTime) = measureTimedValue {
            withBackgroundProgress(project, "Syncing Elixir dependencies") {
                withContext(Dispatchers.Default) {
                    buildSyncPlan(project, coalescedRequests)
                }
            }
        }

        val (writePlan, buildWritePlanTime) = measureTimedValue {
            // A full sync sweeps even with nothing to plan, or the startup check that asked for it would ask again
            // on every open.
            if (syncPlan.isEmpty && !coalescedRequests.hasAll) {
                null
            } else {
                withBackgroundProgress(project, "Computing Elixir dependency changes") {
                    withContext(Dispatchers.Default) {
                        buildWritePlan(project, syncPlan)
                    }
                }
            }
        }

        val (applyStats, applyWritePlanTime) = measureTimedValue {
            writePlan?.takeUnless { it.isEmpty }?.let { plan ->
                withBackgroundProgress(project, "Applying Elixir dependency sync") {
                    applyWritePlan(project, plan)
                }
            } ?: ApplyStats(0, 0)
        }

        // The platform only flushes project settings at its own save points - frame
        // deactivation, periodic autosave, project close - so a drain that runs in the
        // background and is never followed by one leaves the rewritten .iml and
        // .idea/libraries unwritten, and an IDE killed before the next save point loses them.
        if (applyStats.librariesChanged > 0 || applyStats.modulesChanged > 0) {
            // forceSavingAllSettings, because the default saves only components that report
            // themselves changed and the module/library edits below do not, so the rewritten
            // .iml and .idea/libraries were left on disk as they were.
            SaveAndSyncHandler.getInstance().scheduleProjectSave(project, forceSavingAllSettings = true)
        }

        LOG.debug(
            "MixDepsSyncService: drain complete - ${requests.size} request(s) in " +
                "${drainStart.elapsedNow().inWholeMilliseconds}ms " +
                "(buildSyncPlan=${buildSyncPlanTime.inWholeMilliseconds}ms, " +
                "buildWritePlan=${buildWritePlanTime.inWholeMilliseconds}ms, " +
                "applyWritePlan=${applyWritePlanTime.inWholeMilliseconds}ms [write-lock hold], " +
                "deleteAlls=${coalescedRequests.deleteAlls.size}, " +
                "deleteOnes=${coalescedRequests.deleteOnes.size}, " +
                "hasAll=${coalescedRequests.hasAll}, " +
                "syncRoots=${coalescedRequests.syncRoots.size}, " +
                "depsRoots=${coalescedRequests.depsRoots.size}, " +
                "depRoots=${coalescedRequests.depRoots.size}, " +
                "syncModules=${coalescedRequests.syncModuleNames.size}, " +
                "libraryPlans=${syncPlan.libraryPlans.size}, " +
                "modulePlans=${syncPlan.modulePlans.size}, " +
                "consolidatedPlans=${syncPlan.consolidatedPlans.size}, " +
                "librariesChanged=${applyStats.librariesChanged}, " +
                "modulesChanged=${applyStats.modulesChanged})"
        )
    }

    // ------------------------------------------------------------------
    // Per-module mix.exs resolution + dependency wiring
    // ------------------------------------------------------------------

    @VisibleForTesting
    internal suspend fun syncLibrariesForModule(module: Module) {
        val modulePlan = buildModuleDepsPlan(project, module.name, emptyList()) ?: return
        val syncPlan = SyncPlan(
            libraryPlans = deduplicateLibraryPlans(modulePlan.externalLibraryPlans),
            modulePlans = listOf(modulePlan),
        )
        if (!syncPlan.isEmpty) {
            val writePlan = buildWritePlan(project, syncPlan)
            if (!writePlan.isEmpty) {
                applyWritePlan(project, writePlan)
            }
        }
    }

    companion object {
        private const val DEBOUNCE_MS: Long = 250
        internal val LOG = logger<MixDepsSyncService>()
    }
}

/**
 * [contentRootToken] of every content root, an unloaded module's included: its libraries must survive until it is
 * loaded again.
 */
@Suppress("UnstableApiUsage") // Nothing stable reaches an unloaded module's content roots.
internal fun contentRootTokens(project: Project, systemIndependentBasePath: String?): Set<String> = buildSet {
    // URLs, not files: a root whose directory cannot be read just now, such as on a stopped WSL distro, has no file.
    val urls = ProjectRootManager.getInstance(project).contentRootUrls.asSequence() +
        ModuleManager.getInstance(project).unloadedModuleDescriptions.asSequence()
            .flatMap { it.contentRoots }
            .map { it.url }
    urls.mapTo(this) { contentRootToken(systemIndependentBasePath, it) }
}

/**
 * Reduces a content-root URL to the scope token [scopedDepLibraryName] embeds in a library name.
 *
 * The token is made project-relative because the library name reaches version control three ways -
 * the `name` in `.idea/libraries`, the `name` on each referencing `<orderEntry>`, and the library
 * file's own name, which the platform derives from it - so an absolute URL stops one `.idea` being
 * shared between clones. `$PROJECT_DIR$` cannot do this: the macro only collapses a path at the
 * start of a value, and the file name is derived before serialization anyway.
 *
 * Pure string manipulation, so it is safe under a read lock.
 *
 * @return the path relative to the project base directory (`"."`, `"apps/child"`, `"../sibling"`),
 *   or [contentRootUrl] unchanged when no relative path exists - no base directory, or another
 *   drive or mount, where the dep genuinely is machine-specific.
 */
internal fun contentRootToken(project: Project, contentRootUrl: String): String =
    contentRootToken(project.basePath?.let(FileUtil::toSystemIndependentName), contentRootUrl)

/**
 * [contentRootToken] against a base path already read and normalised by the caller.
 *
 * A plan builder resolves a token per dep, nearly always for the same handful of roots, so hoisting
 * the base path keeps a `Path`-to-`String` conversion and a separator scan out of that loop.
 */
internal fun contentRootToken(systemIndependentBasePath: String?, contentRootUrl: String): String {
    // The "no owning root known" fallback, which must stay distinct from the project root's ".".
    if (contentRootUrl.isEmpty()) return ""
    if (systemIndependentBasePath == null) return contentRootUrl
    val rootPath = VirtualFileManager.extractPath(contentRootUrl)
    return FileUtil.getRelativePath(
        systemIndependentBasePath,
        FileUtil.toSystemIndependentName(rootPath),
        '/',
    ) ?: contentRootUrl
}

/**
 * Generates a deterministic, cross-platform, root-scoped library name for a Mix dep.
 *
 * The name is derived from [contentRootToken] and [depName]. Two content roots that each declare a
 * dep named "phoenix" produce two distinct library names, solving the cross-contamination between
 * content roots that share the same dep name.
 *
 * **This is the single canonical naming helper - all production and test call sites MUST use it
 * rather than inlining string formats.**
 *
 * @param contentRootToken  The scope token identifying the content root that owns the dep, as
 *   produced by [contentRootToken] - normally project-relative (e.g. `"apps/child"`), falling back
 *   to an absolute VirtualFile URL where no relative path exists.
 * @param depName  The Mix application/dep name (e.g. `"phoenix"`).
 * @return A scoped library name such as `"phoenix [apps/child]"`.
 */
@VisibleForTesting
internal fun scopedDepLibraryName(contentRootToken: String, depName: String): String =
    "$depName [$contentRootToken]"

/**
 * Inverse of [scopedDepLibraryName]: extracts the embedded scope token from a root-scoped library
 * name, or returns null when [libraryName] does not have the `"<dep> [<token>]"` shape.
 */
@VisibleForTesting
internal fun scopedLibraryNameToken(libraryName: String): String? {
    if (!libraryName.endsWith("]")) return null
    // First occurrence, not last: dep names are Mix app atoms and can never contain " [", but
    // the content-root token may (a directory named "work [old]" is legal on every OS).  Matching
    // the last occurrence would truncate such tokens and misclassify the entry as stale.
    val markerIndex = libraryName.indexOf(" [")
    if (markerIndex <= 0) return null
    return libraryName.substring(markerIndex + 2, libraryName.length - 1)
}

// ---------------------------------------------------------------------------
// Internal top-level plan data classes
//
// Declared at file scope (internal) so WritePlanBuilder.kt and WritePlanApplicator.kt can access
// them as inputs to their respective phases without crossing visibility boundaries.
// ---------------------------------------------------------------------------

internal data class DeleteAllPlan(val depsUrl: String)

internal data class DeleteOnePlan(
    val depName: String,
    val contentRootUrl: String?,
    /** [contentRootUrl] reduced by [contentRootToken]; null exactly when [contentRootUrl] is. */
    val contentRootToken: String?,
) {
    /**
     * The library name to delete: scoped if the owning content root is known, otherwise the legacy
     * unscoped dep name (for backwards-compatible deletion of previous version libraries).
     */
    val libraryName: String
        get() = if (contentRootToken != null) scopedDepLibraryName(contentRootToken, depName) else depName

    /** The absolute-URL-scoped name used before tokens went relative, so a delete still finds it. */
    val previousLibraryName: String?
        get() = contentRootUrl?.let { scopedDepLibraryName(it, depName) }?.takeIf { it != libraryName }
}

internal data class ExcludeFolderPlan(
    val moduleName: String,
    val folderUrl: String,
)

internal data class LibraryRootsPlan(
    /**
     * The URL of the content root under which this dep lives (e.g. `file:///project/my_app`).
     * Stays absolute because the umbrella-sharing fallback in `buildModuleDepsPlan` matches it
     * against a module's own content-root URLs; naming uses [contentRootToken] instead.
     */
    val contentRootUrl: String,
    /** [contentRootUrl] reduced by [contentRootToken] - the form that appears in [libraryName]. */
    val contentRootToken: String,
    val depName: String,
    val classRootUrls: List<String>,
    val sourceRootUrls: List<String>,
    val excludeFolders: List<ExcludeFolderPlan>,
) {
    /**
     * The scoped project-library name for this dep.
     * Two content roots with the same dep name produce distinct [libraryName] values,
     * preventing cross-contamination between unrelated projects.
     */
    val libraryName: String get() = scopedDepLibraryName(contentRootToken, depName)

    /**
     * The absolute-URL-scoped name used before tokens went relative, or null when unchanged.
     * Computed from [contentRootUrl] rather than detected from [libraryName]: old and new names
     * share the same `"<dep> [<token>]"` shape, so no format check could tell them apart.
     */
    val previousLibraryName: String?
        get() = scopedDepLibraryName(contentRootUrl, depName).takeIf { it != libraryName }
}

internal data class ModuleDepsPlan(
    val moduleName: String,
    val moduleDeps: Set<String>,
    val libraryDeps: Set<String>,
    val externalLibraryPlans: List<LibraryRootsPlan>,
)

internal data class SyncPlan(
    val deleteAlls: List<DeleteAllPlan> = emptyList(),
    val deleteOnes: List<DeleteOnePlan> = emptyList(),
    val libraryPlans: List<LibraryRootsPlan> = emptyList(),
    val modulePlans: List<ModuleDepsPlan> = emptyList(),
    val consolidatedPlans: List<ConsolidatedLibraryPlan> = emptyList(),
) {
    val isEmpty: Boolean
        get() = deleteAlls.isEmpty() &&
            deleteOnes.isEmpty() &&
            libraryPlans.isEmpty() &&
            modulePlans.isEmpty() &&
            consolidatedPlans.isEmpty()
}

/**
 * Desired state for a consolidated protocol library at one content root.
 *
 * Built during the read phase by scanning `_build/{env}/consolidated/` and `_build/{env}/lib/{app}/consolidated/`.
 * Diffed in `buildWritePlan` against the existing library table state.
 */
internal data class ConsolidatedLibraryPlan(
    /** The content root URL that owns this `_build` directory. */
    val contentRootUrl: String,
    /** [contentRootUrl] reduced by [contentRootToken] - the form that appears in [libraryName]. */
    val contentRootToken: String,
    /** Desired class root URLs (the `consolidated/` directories). */
    val classRootUrls: List<String>,
    /** The module name of the owner module (for wiring the library as a module dependency). */
    val ownerModuleName: String?,
) {
    /** Scoped to its root: named after the directory alone, two roots of the same name would share one library. */
    val libraryName: String get() = scopedDepLibraryName(contentRootToken, CONSOLIDATED_LIBRARY_BASE_NAME)
}

/** [ConsolidatedLibraryPlan.libraryName]'s shape, or `<dir> (consolidated)`, the name from before it was scoped. */
internal fun isConsolidatedLibraryName(name: String): Boolean =
    name.startsWith("$CONSOLIDATED_LIBRARY_BASE_NAME [") || isUnscopedConsolidatedLibraryName(name)

/**
 * Whether a sync's sweep removes the Mix library [name]: named without a root, consolidated for a root not in
 * [contentRootTokens], or named after a current root's absolute URL, as before tokens went relative. An external `path:`
 * dep is scoped to its directory's grandparent, never a content root, so other names are not swept by root.
 */
internal fun isSweptMixLibraryName(
    name: String,
    contentRootTokens: Set<String>,
    systemIndependentBasePath: String?,
): Boolean {
    if (" [" !in name || isUnscopedConsolidatedLibraryName(name)) return true
    val token = scopedLibraryNameToken(name) ?: return false

    return token !in contentRootTokens && (
        isConsolidatedLibraryName(name) ||
            "://" in token && contentRootToken(systemIndependentBasePath, token) in contentRootTokens
        )
}

/**
 * The tokens of the content roots the VFS holds now. A library scoped to any other token, such as an external `path:`
 * dep's, an umbrella's whose root was left out at import, or one under a content root that is deleted or cannot be
 * read, keeps its roots.
 */
internal fun liveContentRootTokens(project: Project, systemIndependentBasePath: String?): Set<String> =
    ProjectRootManager.getInstance(project).contentRoots.mapTo(HashSet()) {
        contentRootToken(systemIndependentBasePath, it.url)
    }

/** [library]'s [rootType] roots the VFS cannot resolve, when its name is scoped to one of [liveTokens]. */
internal fun danglingRootUrls(library: LibraryEx, rootType: OrderRootType, liveTokens: Set<String>): List<String> =
    if (scopedLibraryNameToken(library.name.orEmpty()) in liveTokens) {
        library.getInvalidRootUrls(rootType)
    } else {
        emptyList()
    }

/** Checked apart from other unscoped names: the directory in `<dir> (consolidated)` may itself hold ` [`. */
internal fun isUnscopedConsolidatedLibraryName(name: String): Boolean =
    name.endsWith(" $CONSOLIDATED_LIBRARY_BASE_NAME")
