package org.elixir_lang.mix.sync

import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.elixir_lang.mix.Dep
import org.elixir_lang.mix.watcher.TransitiveResolution.transitiveDepRoots
import org.elixir_lang.mix.Project as MixProject

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Maximum depth for the ancestor-deps walk in [findAncestorDeps]. */
private const val MAX_ANCESTOR_WALK_DEPTH = 10

/**
 * Source directory names that are included as source roots in a Mix dep's [LibraryRootsPlan].
 * Defined once here as the single source of truth; [org.elixir_lang.mix.sync.SyncRequest]
 * classifyByPath uses the same set via [MIX_DEP_SOURCE_DIR_NAMES].
 */
internal val MIX_DEP_SOURCE_DIR_NAMES = setOf("c_src", "lib", "priv", "src")

// ---------------------------------------------------------------------------
// CoalescedRequests - output of coalesceRequests, input to buildSyncPlan
// ---------------------------------------------------------------------------

internal data class CoalescedRequests(
    val deleteAlls: List<SyncRequest.DeleteAll>,
    val deleteOnes: List<SyncRequest.DeleteOne>,
    val hasAll: Boolean,
    val syncRoots: List<SyncRequest.SyncRoot>,
    val depsRoots: List<SyncRequest.DepsRoot>,
    val depRoots: List<SyncRequest.DepRoot>,
    val consolidatedRoots: List<SyncRequest.Consolidated>,
    val syncModuleNames: Set<String>,
)

// ---------------------------------------------------------------------------
// resolvePathShapedRequests - first in drain(); validates under readAction
// ---------------------------------------------------------------------------

/**
 * Validates and resolves listener-produced path-shaped requests under read access.
 *
 * The listener is intentionally limited to string/path-shape checks. This function is the first
 * point where project-model APIs are consulted for candidate content roots or owning modules.
 */
internal suspend fun resolvePathShapedRequests(project: Project, requests: List<SyncRequest>): List<SyncRequest> =
    readAction {
        val contentRootUrls = ProjectRootManager
            .getInstance(project)
            .contentRoots
            .mapTo(HashSet()) { it.url }

        requests.mapNotNull { request -> resolvePathShapedRequest(project, request, contentRootUrls) }
    }

private fun resolvePathShapedRequest(
    project: Project,
    request: SyncRequest,
    contentRootUrls: Set<String>,
): SyncRequest? =
    when (request) {
        is SyncRequest.BuildPath ->
            if (isContentRootCandidate(request.contentRootCandidate, contentRootUrls)) {
                SyncRequest.SyncRoot(request.contentRootCandidate.url)
            } else {
                null
            }

        is SyncRequest.BuildDep -> {
            val contentRoot = request.contentRootCandidate
            if (!isContentRootCandidate(contentRoot, contentRootUrls)) {
                null
            } else {
                MixProject.depFolder(contentRoot, request.depName)
                    ?.let { SyncRequest.DepRoot(it) }
                    // No such dep: the project's own app, whose build holds a regular project's consolidated
                    // protocols, or an umbrella child or `path:` dep, for which the consolidated rescan is merely
                    // redundant.
                    ?: SyncRequest.Consolidated(contentRoot)
            }
        }

        is SyncRequest.DepsRoot ->
            if (request.depsRoot.isValid &&
                request.depsRoot.name == "deps" &&
                request.depsRoot.parent?.url in contentRootUrls
            ) {
                request
            } else {
                null
            }

        is SyncRequest.DepRoot ->
            if (request.depRoot.isValid &&
                request.depRoot.parent?.name == "deps" &&
                request.depRoot.parent?.parent?.url in contentRootUrls
            ) {
                request
            } else {
                null
            }

        is SyncRequest.DeleteAll ->
            if (request.contentRootUrl == null || request.contentRootUrl in contentRootUrls) request else null

        is SyncRequest.DeleteOne ->
            if (request.contentRootUrl == null || request.contentRootUrl in contentRootUrls) request else null

        is SyncRequest.MixFile ->
            resolveMixFileRequest(project, request.mixFile)

        is SyncRequest.Consolidated ->
            if (isContentRootCandidate(request.contentRootCandidate, contentRootUrls)) {
                request
            } else {
                null
            }

        SyncRequest.All,
        is SyncRequest.SyncRoot,
        is SyncRequest.SyncModule ->
            request
    }

private fun isContentRootCandidate(file: VirtualFile, contentRootUrls: Set<String>): Boolean =
    file.isValid && file.url in contentRootUrls

private fun resolveMixFileRequest(project: Project, mixFile: VirtualFile): SyncRequest.SyncModule? {
    if (!mixFile.isValid || mixFile.name != MixProject.MIX_EXS) return null
    val module = ModuleUtil.findModuleForFile(mixFile, project) ?: return null
    val mixRoot = mixFile.parent ?: return null
    val contentRoots = ModuleRootManager.getInstance(module).contentRoots
    val inContentRoot = contentRoots.any { contentRoot -> contentRoot == mixRoot }
    // apps/<app>/mix.exs in a single-module umbrella: the app dir is not a content root of its
    // own, but its apps/ parent sits directly under one of the module's content roots.  Resolve
    // to the owning umbrella module so that dep edits in app mix.exs files still trigger a
    // module sync.  App dirs carved out as their own content roots (multi-module umbrella
    // layout) already match the direct check above via their own module.
    val inUmbrellaApp = !inContentRoot &&
        mixRoot.parent?.name == "apps" &&
        contentRoots.any { contentRoot -> contentRoot == mixRoot.parent?.parent }
    return if (inContentRoot || inUmbrellaApp) SyncRequest.SyncModule(module) else null
}

// ---------------------------------------------------------------------------
// coalesceRequests
// ---------------------------------------------------------------------------

internal fun coalesceRequests(requests: List<SyncRequest>): CoalescedRequests {
    val deleteAlls = requests.filterIsInstance<SyncRequest.DeleteAll>()
    val deleteOnes = requests.filterIsInstance<SyncRequest.DeleteOne>()
    val hasAll = requests.any { it is SyncRequest.All }
    val deleteAllUrls = deleteAlls.map { it.depsUrl }.toSet()

    // SyncRoot requests are filtered the same way as DepsRoot: suppressed by All and by
    // DeleteAll for the same content root. DeleteAll.depsUrl is always
    // "<contentRootUrl>/deps", so we compare with exact equality rather than a prefix
    // startsWith check. A startsWith check would incorrectly suppress a pending SyncRoot
    // for file:///repo/app when a DeleteAll targets file:///repo/app2/deps, because the
    // sibling root URL is a prefix of the other's depsUrl string.
    val syncRoots = if (hasAll) emptyList() else {
        requests.filterIsInstance<SyncRequest.SyncRoot>()
            .distinctBy { it.contentRootUrl }
            .filter { syncRoot ->
                deleteAllUrls.none { depsUrl -> depsUrl == "${syncRoot.contentRootUrl}/deps" }
            }
    }

    val depsRoots = if (hasAll) emptyList() else {
        requests.filterIsInstance<SyncRequest.DepsRoot>()
            .filter { depsRoot -> deleteAllUrls.none { url -> depsRoot.depsRoot.url == url } }
    }

    val depRoots = if (hasAll) emptyList() else {
        val depsRootUrls = depsRoots.map { it.depsRoot.url }.toSet()
        val syncRootContentUrls = syncRoots.map { it.contentRootUrl }.toSet()
        requests.filterIsInstance<SyncRequest.DepRoot>()
            .filter { depRoot -> depsRootUrls.none { url -> depRoot.depRoot.parent?.url == url } }
            .filter { depRoot -> deleteAllUrls.none { url -> depRoot.depRoot.parent?.url == url } }
            // Also suppress DepRoot when a SyncRoot covers the same content root (the SyncRoot
            // will enumerate all deps under that root anyway).
            .filter { depRoot -> syncRootContentUrls.none { url -> depRoot.depRoot.parent?.parent?.url == url } }
    }

    val syncModuleNames = requests.filterIsInstance<SyncRequest.SyncModule>()
        .mapTo(LinkedHashSet()) { it.moduleName }

    // Consolidated requests are superseded by hasAll (which already triggers consolidated sync).
    val consolidatedRoots = if (hasAll) emptyList() else {
        requests.filterIsInstance<SyncRequest.Consolidated>()
    }

    return CoalescedRequests(
        deleteAlls = deleteAlls,
        deleteOnes = deleteOnes,
        hasAll = hasAll,
        syncRoots = syncRoots,
        depsRoots = depsRoots,
        depRoots = depRoots,
        consolidatedRoots = consolidatedRoots,
        syncModuleNames = syncModuleNames,
    )
}

// ---------------------------------------------------------------------------
// buildSyncPlan - main read-phase orchestrator
// ---------------------------------------------------------------------------

internal suspend fun buildSyncPlan(project: Project, requests: CoalescedRequests): SyncPlan {
    val requestedLibraryPlans = when {
        requests.hasAll -> buildLibraryRootsPlans(project, allDepRoots(project))
        requests.syncRoots.isNotEmpty() || requests.depsRoots.isNotEmpty() || requests.depRoots.isNotEmpty() -> {
            val depRoots = readAction {
                // SyncRoot: enumerate all deps under the specified content root (identified by URL).
                // SyncRoot is only produced from BuildPath after the URL is verified as a registered
                // module content root (see resolvePathShapedRequest), so the firstOrNull lookup
                // always succeeds in production.
                val fromSyncRoots = requests.syncRoots.flatMap { syncRoot ->
                    ProjectRootManager.getInstance(project).contentRoots
                        .firstOrNull { it.url == syncRoot.contentRootUrl }
                        ?.findChild("deps")?.children?.toList()
                        ?: emptyList()
                }
                val fromDepsRoots = requests.depsRoots.flatMap { depsRoot ->
                    if (depsRoot.depsRoot.isValid) depsRoot.depsRoot.children.toList() else emptyList()
                }
                val fromDepRoots = requests.depRoots.mapNotNull { depRoot ->
                    depRoot.depRoot.takeIf { it.isValid }
                }
                buildList {
                    addAll(fromSyncRoots)
                    addAll(fromDepsRoots)
                    addAll(fromDepRoots)
                }
            }
            buildLibraryRootsPlans(project, depRoots)
        }
        // When only module-scoped requests are present (e.g. from a MixFile content-change
        // event that resolved to a SyncModule), build library plans from those modules' own
        // content roots so that deps-owner scoping works correctly.
        //
        // Without this branch, buildModuleDepsPlan receives an empty requestedLibraryPlans
        // list and an umbrella child module whose deps live under the umbrella root falls
        // through to the mix.exs content-root fallback, producing a mismatched placeholder
        // name like "phoenix [parent/apps/child1]" instead of the correct "phoenix [parent]".
        //
        // The ancestor walk handles umbrella children: when a child module at
        // parent/apps/child1 has no deps/ under its own content root, we walk up the
        // directory tree until we find a mix.exs-bearing ancestor that has a deps/ directory.
        // This ensures the library plans carry the umbrella root's content-root URL.
        requests.syncModuleNames.isNotEmpty() -> {
            val depRoots = readAction {
                val moduleManager = ModuleManager.getInstance(project)
                requests.syncModuleNames.flatMap { moduleName ->
                    moduleManager.findModuleByName(moduleName)
                        ?.takeIf { !it.isDisposed }
                        ?.let { ModuleRootManager.getInstance(it).contentRoots.toList() }
                        .orEmpty()
                }.flatMap { contentRoot ->
                    // First try the module's own deps/ directory.
                    val ownDeps = contentRoot.findChild("deps")?.children?.toList()
                    if (!ownDeps.isNullOrEmpty()) {
                        ownDeps
                    } else {
                        // Walk up ancestors to find the nearest umbrella root with deps/.
                        // Stop when we find a directory containing both mix.exs and deps/.
                        findAncestorDeps(contentRoot)
                    }
                }
            }
            buildLibraryRootsPlans(project, depRoots)
        }
        else -> emptyList()
    }

    val moduleNames = LinkedHashSet(requests.syncModuleNames)
    if (requestedLibraryPlans.isNotEmpty()) {
        // For a full All-sync, fan-out to every module in the project.
        // For scoped (DepRoot / DepsRoot) syncs, only fan-out to modules that own the
        // affected content roots - avoids wiring unrelated modules.
        moduleNames += if (requests.hasAll) {
            allModuleNames(project)
        } else {
            affectedModuleNamesForLibraryPlans(project, requestedLibraryPlans)
        }
    }

    // Pass requestedLibraryPlans so that buildModuleDepsPlan can resolve the correct
    // scoped library name for umbrella child modules whose dep directory is not under
    // their own content root but IS the root of an already-computed library plan
    // (e.g. "phoenix [parent]" for a child at "parent/apps/child1").
    val modulePlans = buildModuleDepsPlans(project, moduleNames, requestedLibraryPlans)
    val externalLibraryPlans = modulePlans.flatMap { it.externalLibraryPlans }
    val libraryPlans = deduplicateLibraryPlans(requestedLibraryPlans + externalLibraryPlans)

    val consolidatedPlans = buildConsolidatedLibraryPlans(project, requests)

    val basePath = project.basePath?.let(FileUtil::toSystemIndependentName)

    return SyncPlan(
        deleteAlls = requests.deleteAlls.map { DeleteAllPlan(it.depsUrl) },
        deleteOnes = requests.deleteOnes.map {
            DeleteOnePlan(it.depName, it.contentRootUrl, it.contentRootUrl?.let { url -> contentRootToken(basePath, url) })
        },
        libraryPlans = libraryPlans,
        modulePlans = modulePlans,
        consolidatedPlans = consolidatedPlans,
    )
}

private suspend fun allDepRoots(project: Project): List<VirtualFile> =
    readAction {
        ProjectRootManager
            .getInstance(project)
            .contentRoots
            .flatMap { contentRoot -> contentRoot.findChild("deps")?.children?.toList() ?: emptyList() }
    }

/**
 * Builds [ConsolidatedLibraryPlan]s for the affected content roots.
 *
 * Scoping rules:
 * - [CoalescedRequests.hasAll] -> scan all content roots
 * - [CoalescedRequests.syncRoots] / [CoalescedRequests.depsRoots] / [CoalescedRequests.depRoots] -> affected roots only
 * - [CoalescedRequests.consolidatedRoots] -> specified content roots only
 * - [CoalescedRequests.syncModuleNames] -> content roots of named modules
 * - Delete-only requests -> empty list (deletion is handled by [buildWritePlan])
 */
private suspend fun buildConsolidatedLibraryPlans(
    project: Project,
    requests: CoalescedRequests,
): List<ConsolidatedLibraryPlan> {
    val hasPositiveSync = requests.hasAll ||
        requests.syncRoots.isNotEmpty() ||
        requests.depsRoots.isNotEmpty() ||
        requests.depRoots.isNotEmpty() ||
        requests.consolidatedRoots.isNotEmpty() ||
        requests.syncModuleNames.isNotEmpty()

    if (!hasPositiveSync) return emptyList()

    return readAction {
        val allContentRoots = ProjectRootManager.getInstance(project).contentRoots

        val contentRoots: List<VirtualFile> = when {
            requests.hasAll -> allContentRoots.toList()
            else -> {
                val fromSyncRoots = requests.syncRoots.mapNotNull { syncRoot ->
                    allContentRoots.firstOrNull { it.url == syncRoot.contentRootUrl }
                }
                val fromDepsRoots = requests.depsRoots.mapNotNull { it.depsRoot.parent?.takeIf { p -> p.isValid } }
                val fromDepRoots = requests.depRoots.mapNotNull { it.depRoot.parent?.parent?.takeIf { p -> p.isValid } }
                val fromConsolidated = requests.consolidatedRoots.map { it.contentRootCandidate }.filter { it.isValid }
                val fromModules = requests.syncModuleNames.flatMap { moduleName ->
                    ModuleManager.getInstance(project).findModuleByName(moduleName)
                        ?.takeIf { !it.isDisposed }
                        ?.let { ModuleRootManager.getInstance(it).contentRoots.toList() }
                        .orEmpty()
                }
                buildList {
                    addAll(fromSyncRoots)
                    addAll(fromDepsRoots)
                    addAll(fromDepRoots)
                    addAll(fromConsolidated)
                    addAll(fromModules)
                }.distinctBy { it.url }
            }
        }

        val basePath = project.basePath?.let(FileUtil::toSystemIndependentName)
        contentRoots.map { contentRoot ->
            ProgressManager.checkCanceled()
            val token = contentRootToken(basePath, contentRoot.url)
            // An empty plan, not none, so a library left from before `_build` was deleted is removed.
            val build = contentRoot.findChild("_build")?.takeIf { it.isValid && it.isDirectory }
                ?: return@map ConsolidatedLibraryPlan(contentRoot.url, token, emptyList(), ownerModuleName = null)

            // An umbrella consolidates into `_build/<env>/consolidated`, any other project into its app's
            // `_build/<env>/lib/<app>/consolidated`. `lib/<app>/consolidated` is used only when
            // `_build/<env>/consolidated` is absent: an umbrella child compiled on its own leaves a copy there too.
            // Deps never consolidate.
            val consolidatedDirs = build.children
                .filter { it.isDirectory }
                .flatMap { env ->
                    env.findChild("consolidated")?.takeIf { it.isDirectory }?.let { listOf(it) }
                        ?: env.findChild("lib")?.children.orEmpty()
                            .mapNotNull { it.findChild("consolidated")?.takeIf(VirtualFile::isDirectory) }
                }

            val ownerModuleName = ModuleUtil.findModuleForFile(contentRoot, project)?.name

            ConsolidatedLibraryPlan(
                contentRootUrl = contentRoot.url,
                contentRootToken = token,
                classRootUrls = consolidatedDirs.map { it.url },
                ownerModuleName = ownerModuleName,
            )
        }
    }
}

/**
 * Walks up the directory tree from [contentRoot] to find the nearest ancestor that contains
 * both a `mix.exs` file and a `deps/` directory.  Returns the children of that ancestor's
 * `deps/` directory.
 *
 * This handles umbrella child modules at `parent/apps/child1` whose deps physically live
 * under `parent/deps/`.  The walk stops when:
 * - An ancestor with both `mix.exs` and `deps/` is found (the umbrella root).
 * - The directory tree is exhausted (parent is null).
 * - More than [MAX_ANCESTOR_WALK_DEPTH] levels are traversed (safety bound).
 *
 * **Must be called inside [readAction].**
 */
private fun findAncestorDeps(contentRoot: VirtualFile): List<VirtualFile> {
    var current = contentRoot.parent
    var depth = 0
    while (current != null && depth < MAX_ANCESTOR_WALK_DEPTH) {
        val hasMixExs = current.findChild("mix.exs")?.isValid == true
        val deps = current.findChild("deps")
        if (hasMixExs && deps != null && deps.isDirectory) {
            return deps.children?.toList().orEmpty()
        }
        current = current.parent
        depth++
    }
    return emptyList()
}

private suspend fun allModuleNames(project: Project): List<String> =
    readAction {
        ModuleManager
            .getInstance(project)
            .modules
            .filter { !it.isDisposed }
            .map { it.name }
    }

/**
 * Returns the names of modules whose content roots overlap with the content roots referenced
 * by [libraryPlans].
 *
 * Used for scoped (non-All) syncs so that a single-root [SyncRequest.DepRoot] does not
 * trigger full module-plan computation for unrelated modules.
 *
 * **Matching rules:**
 * 1. Exact match - the module's content root URL equals the library plan's content root URL.
 * 2. Ancestor match - the module's content root URL is a descendant of the library plan's
 *    content root URL (separated by a "/"). This covers umbrella child modules at
 *    `parent/apps/child1` when a library plan targets the umbrella root `parent/`; without
 *    this rule, a scoped DepRoot event for `parent/deps/phoenix` would never plan child
 *    modules and they would remain un-wired after a deps sync.
 *
 * **Note:** this function iterates [ModuleManager.modules] to identify the affected subset.
 * That lightweight filter pass does NOT perform module-plan computation for every module -
 * only modules whose content roots overlap with [libraryPlans] proceed to full plan
 * computation. Modules outside the affected set are therefore never touched by a scoped sync,
 * even though all module names are read here for filtering purposes.
 */
private suspend fun affectedModuleNamesForLibraryPlans(
    project: Project,
    libraryPlans: List<LibraryRootsPlan>,
): Set<String> =
    readAction {
        val affectedContentRootUrls = libraryPlans.mapTo(HashSet()) { it.contentRootUrl }
        ModuleManager.getInstance(project)
            .modules
            .filter { !it.isDisposed }
            .filter { module ->
                ModuleRootManager.getInstance(module).contentRoots.any { cr ->
                    // Exact: the module's content root IS the deps-owner root.
                    cr.url in affectedContentRootUrls ||
                    // Ancestor: the module's content root is a child of a deps-owner root
                    // (e.g. parent/apps/child1 is under parent/).
                    // VfsUtilCore.isEqualOrAncestor requires the ancestor arg to end with "/"
                    // to be path-boundary-safe; it is always satisfied because we normalise
                    // with the trailing "/" here.
                    affectedContentRootUrls.any { ancestorUrl ->
                        val normalizedAncestor = if (ancestorUrl.endsWith("/")) ancestorUrl else "$ancestorUrl/"
                        VfsUtilCore.isEqualOrAncestor(normalizedAncestor, cr.url)
                    }
                }
            }
            .mapTo(LinkedHashSet()) { it.name }
    }

// ---------------------------------------------------------------------------
// Module dep plan building
// ---------------------------------------------------------------------------

private suspend fun buildModuleDepsPlans(
    project: Project,
    moduleNames: Set<String>,
    requestedLibraryPlans: List<LibraryRootsPlan>,
): List<ModuleDepsPlan> {
    if (moduleNames.isEmpty()) return emptyList()

    return moduleNames.mapNotNull { moduleName ->
        currentCoroutineContext().ensureActive()
        ProgressManager.checkCanceled()
        buildModuleDepsPlan(project, moduleName, requestedLibraryPlans)
    }
}

/**
 * Builds the dependency plan for one module.
 *
 * [requestedLibraryPlans] is used as a tertiary fallback when resolving the scoped library
 * name for a dep whose physical directory is not under the module's own content roots and not
 * reachable via the external-path path.  This handles umbrella child modules whose deps are
 * owned by the umbrella root: the library plan for "parent/deps/phoenix" has
 * [LibraryRootsPlan.contentRootUrl] = "parent", so both child modules reference the same
 * `"phoenix \[parent\]"` library rather than each creating a placeholder scoped to their own root.
 */
internal suspend fun buildModuleDepsPlan(
    project: Project,
    moduleName: String,
    requestedLibraryPlans: List<LibraryRootsPlan>,
): ModuleDepsPlan? {
    val contentRoots = readAction {
        ModuleManager
            .getInstance(project)
            .findModuleByName(moduleName)
            ?.takeIf { !it.isDisposed }
            ?.let { ModuleRootManager.getInstance(it).contentRoots }
            ?: return@readAction null
    } ?: return null

    // Single-module umbrella: the module's content root is the umbrella root and the apps under
    // apps/ are plain sub-directories, so the only mix.exs directly at a root is the umbrella's
    // own (usually dep-less) one.  transitiveDepRoots parses just the mix.exs at each root it
    // is given, so without expansion every app-declared dep would stay invisible and never be
    // wired.  Expand the root set with each apps/<app> dir that carries its own mix.exs.  App
    // dirs that are content roots in their own right belong to a child module (multi-module
    // umbrella layout) whose own module plan wires them - they are skipped here so the umbrella
    // module does not claim a child module's deps.
    val resolutionRoots = readAction {
        val projectFileIndex = ProjectRootManager.getInstance(project).fileIndex
        contentRoots.flatMap { contentRoot ->
            val appRoots = contentRoot.findChild("apps")?.children.orEmpty().filter { appDir ->
                appDir.isDirectory &&
                    appDir.findChild(MixProject.MIX_EXS)?.isValid == true &&
                    projectFileIndex.getContentRootForFile(appDir) == contentRoot
            }
            listOf(contentRoot) + appRoots
        }
    }

    val psiManager = PsiManager.getInstance(project)
    val indicator = EmptyProgressIndicator()
    val depRoots = transitiveDepRoots(psiManager, indicator, *resolutionRoots.toTypedArray())
    val deps = depRoots.keys.toList()
    if (deps.isEmpty()) return null

    val externalPlansByDir = buildExternalLibraryPlans(project, depRoots.values.filterNotNull())

    // Compute scoped library names for the module's library deps.
    // Resolution is restricted to this module's own content roots (not global) to prevent
    // cross-module contamination when multiple modules declare the same dep name.
    // dep.path is "deps/<name>" (relative). We search only this module's content roots so
    // that module B never resolves against module A's deps/phoenix directory.
    // A dep whose directory resolved outside every content root takes its external plan's name. Otherwise, if the dep
    // directory is not yet fetched, fall back in order:
    //   1. the library name from the already-computed requestedLibraryPlans for the same dep
    //      name - handles umbrella child modules where the dep physically lives under the
    //      umbrella root's deps/ directory, which is NOT a content root of the child module.
    //      Uses the plan whose contentRootUrl is an ancestor of one of this module's content
    //      roots, choosing the nearest (longest URL = most specific) match. This prevents a
    //      child module under umbrella_b from being wired to umbrella_a's scoped library when
    //      both umbrellas contain a dep with the same name (e.g. both have deps/phoenix).
    //   2. the first content root that has a mix.exs (the actual Mix project root)
    //   3. the first content root overall
    // This ensures every declared dep stays in libraryDeps even before `mix deps.get` has
    // run, so that the missingLibraryDeps path creates a placeholder library for it.
    val libraryDeps: Set<String> = readAction {
        val basePath = project.basePath?.let { FileUtil.toSystemIndependentName(it) }
        fun token(url: String) = contentRootToken(basePath, url)
        val mixExsRoots by lazy { contentRoots.filter { it.findFileByRelativePath("mix.exs") != null } }
        deps.filter { it.type == Dep.Type.LIBRARY }.mapTo(LinkedHashSet()) { dep ->
            // Before the path is re-read against this module's roots, which is not where Mix looks for a dep's own
            // `path:` dep.
            depRoots[dep]?.let(externalPlansByDir::get)?.let { return@mapTo it.libraryName }
            val owningRoot = contentRoots.firstOrNull { root ->
                root.findFileByRelativePath(dep.path)?.isValid == true
            }
            if (owningRoot != null) {
                scopedDepLibraryName(token(owningRoot.url), dep.application)
            } else {
                requestedLibraryPlans
                    .filter { it.depName == dep.application }
                    .filter { plan ->
                        // Only consider library plans whose content root is an ancestor of
                        // one of this module's own content roots. This prevents a child
                        // module under umbrella_b from being wired to umbrella_a's plan
                        // when both umbrellas declare the same dep name. Without the "/"
                        // suffix, "file:///parent/app" would match "file:///parent/app2".
                        val normalizedPlanRoot = if (plan.contentRootUrl.endsWith("/"))
                            plan.contentRootUrl
                        else
                            "${plan.contentRootUrl}/"
                        contentRoots.any { cr ->
                            VfsUtilCore.isEqualOrAncestor(normalizedPlanRoot, cr.url)
                        }
                    }
                    // Prefer the nearest ancestor (longest contentRootUrl = most specific).
                    // "file:///parent/apps/child" is more specific than "file:///parent"
                    // should one ever be a registered content root and a deps owner.
                    .maxByOrNull { it.contentRootUrl.length }
                    ?.libraryName
                    ?: scopedDepLibraryName(
                        (mixExsRoots.firstOrNull()?.url ?: contentRoots.firstOrNull()?.url)
                            ?.let { url -> token(url) }
                            ?: "",
                        dep.application
                    )
            }
        }
    }

    // An in_umbrella dep whose apps/<name> dir sits under one of this module's own content
    // roots is a dep between two apps of the same single-module umbrella - the "dep module" is
    // this module itself, so wiring it would only ever create an invalid module order entry.
    val moduleDeps: Set<String> = readAction {
        deps.filter { it.type == Dep.Type.MODULE }
            .filterNot { dep -> contentRoots.any { root -> root.findFileByRelativePath(dep.path)?.isValid == true } }
            .mapTo(LinkedHashSet()) { it.application }
    }

    return ModuleDepsPlan(
        moduleName = moduleName,
        moduleDeps = moduleDeps,
        libraryDeps = libraryDeps,
        externalLibraryPlans = externalPlansByDir.values.toList(),
    )
}

/**
 * Syncs libraries for dep paths that live outside every content root, keyed by the dep's directory.
 *
 * @param depFiles each dep's directory, as dep resolution found it: resolved outside any read lock, since a dep outside
 *   the VFS is found by a synchronous refresh.
 */
private suspend fun buildExternalLibraryPlans(
    project: Project,
    depFiles: Collection<VirtualFile>,
): Map<VirtualFile, LibraryRootsPlan> = readAction {
    val projectFileIndex = ProjectRootManager.getInstance(project).fileIndex
    val externalPaths = depFiles.filter { virtualFile ->
        ProgressManager.checkCanceled()
        virtualFile.isValid &&
            virtualFile.isDirectory &&
            projectFileIndex.getContentRootForFile(virtualFile) == null &&
            !projectFileIndex.isInLibrary(virtualFile) &&
            !projectFileIndex.isExcluded(virtualFile)
    }

    externalPaths.associateWith(LibraryRootsPlanner(project)::plan)
}

// ---------------------------------------------------------------------------
// Library roots plan building
// ---------------------------------------------------------------------------

private suspend fun buildLibraryRootsPlans(project: Project, deps: Collection<VirtualFile>): List<LibraryRootsPlan> =
    readAction {
        buildLibraryRootsPlansInCurrentContext(project, deps)
    }

@RequiresReadLock
internal fun buildLibraryRootsPlansInCurrentContext(project: Project, deps: Collection<VirtualFile>): List<LibraryRootsPlan> {
    ThreadingAssertions.assertReadAccess()
    if (deps.isEmpty()) return emptyList()
    val planner = LibraryRootsPlanner(project)

    return deps.filter { it.isValid && it.isDirectory }.map(planner::plan)
}

/** Builds each dep directory's [LibraryRootsPlan], reading what every plan shares once. */
private class LibraryRootsPlanner(private val project: Project) {
    // Fall-back _build roots for external deps that don't sit under a project content root.
    private val allBuildRoots by lazy {
        ProjectRootManager
            .getInstance(project)
            .contentRoots
            .mapNotNull { it.findChild("_build") }
    }

    private val basePath = project.basePath?.let { FileUtil.toSystemIndependentName(it) }

    /** [dep] must be a valid directory. */
    @RequiresReadLock
    fun plan(dep: VirtualFile): LibraryRootsPlan {
        ProgressManager.checkCanceled()
        val depName = dep.name
        // The content root is the grandparent of the dep: <contentRoot>/deps/<depName>.
        // Scope the _build scan to this dep's own content root to prevent cross-contamination
        // between content roots that share a dep name.
        val contentRoot = dep.parent?.parent
        val contentRootUrl = contentRoot?.url ?: ""
        val buildRootsToScan = if (contentRoot != null)
            listOfNotNull(contentRoot.findChild("_build"))
        else
            allBuildRoots

        val classRoots = ArrayList<VirtualFile>()
        val excludeFolders = ArrayList<ExcludeFolderPlan>()

        for (build in buildRootsToScan) {
            ProgressManager.checkCanceled()
            for (environment in build.children.filter { it.isDirectory }) {
                ProgressManager.checkCanceled()
                for (environmentChild in environment.children.filter { it.isDirectory }) {
                    ProgressManager.checkCanceled()
                    // Consolidated roots are NOT added to per-dep libraries;
                    // they belong in a separate shared library managed by
                    // syncConsolidatedLibrary().
                    if (environmentChild.name == "lib") {
                        environmentChild.findChild(depName)?.let { depEnvLib ->
                            depEnvLib.findChild("ebin")?.let { ebin ->
                                if (ebin.isDirectory) {
                                    ModuleUtil.findModuleForFile(depEnvLib, project)?.let { module ->
                                        excludeFolders += ExcludeFolderPlan(module.name, depEnvLib.url)
                                    }
                                    // Resolve symlinks via VFS before registering: Erlang deps
                                    // have _build/{env}/lib/{dep}/ebin -> deps/{dep}/ebin, so
                                    // without canonicalization the same physical directory is
                                    // added once per build environment.
                                    val canonicalEbin = ebin.canonicalFile ?: ebin
                                    classRoots += canonicalEbin
                                }
                            }
                        }
                    }
                }
            }
        }

        return LibraryRootsPlan(
            contentRootUrl = contentRootUrl,
            contentRootToken = contentRootToken(basePath, contentRootUrl),
            depName = depName,
            classRootUrls = classRoots.map { it.url }.distinct(),
            sourceRootUrls = dep.children
                .filter { child -> child.isDirectory && child.name in MIX_DEP_SOURCE_DIR_NAMES }
                .map { child -> child.url }
                .distinct(),
            excludeFolders = excludeFolders.distinctBy { it.moduleName to it.folderUrl },
        )
    }
}

internal fun deduplicateLibraryPlans(plans: List<LibraryRootsPlan>): List<LibraryRootsPlan> =
    plans.associateBy { it.libraryName }.values.toList()
