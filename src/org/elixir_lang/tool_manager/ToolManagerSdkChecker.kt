package org.elixir_lang.tool_manager

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.EditorNotifications
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.elixir_lang.isElixirModule
import org.elixir_lang.sdk.SdkRegistrar
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.sdk.choice.SdkChoice
import org.elixir_lang.sdk.elixir.ElixirSdkAssignment
import org.elixir_lang.sdk.elixir.ElixirSdkAssignment.Target
import org.elixir_lang.sdk.elixir.ElixirSdkLookup
import org.elixir_lang.sdk.elixir.knownOrNull
import org.elixir_lang.sdk.elixir.sdk
import org.elixir_lang.sdk.erlang_dependent.ErlangStep
import org.elixir_lang.sdk.erlang_dependent.elixirAdditionalData
import org.elixir_lang.sdk.wsl.wslCompat
import org.jetbrains.annotations.VisibleForTesting
import java.nio.file.Path

private val LOG = logger<ToolManagerSdkChecker>()

/**
 * Handles the tool-manager side of the SDK notification scan:
 * collecting module data, resolving versions from all enabled tool managers, detecting
 * SDKs that are not the tool manager's installs, building comparison tables, and configuring SDKs from
 * tool-manager results.
 *
 * The widget is responsible for threading (readAction / Dispatchers.IO / EDT) and for
 * combining the results here with its own non-tool-manager checks (dangling refs, classpath, etc.).
 *
 * @param project       The project this checker operates on.
 * @param toolManagers  Ordered list of all registered tool managers.  The checker queries enabled
 *                      managers in order and uses the first non-null result per content root.
 * @param settings      Project-level enable/disable flags for each tool manager.
 */
internal class ToolManagerSdkChecker(
    private val project: Project,
    private val toolManagers: List<ElixirToolManager>,
    private val settings: ToolManagerSettings,
) {

    // -------------------------------------------------------------------------
    // Public data types
    // -------------------------------------------------------------------------

    /**
     * Per-module snapshot of the SDKs to compare against the tool manager's installs.  Collected inside a read
     * action.
     */
    data class ModuleCheckData(
        val moduleName: String,
        val elixirSdkHomePath: String?,
        /** The recorded Elixir version, compared with the tool manager's; null until it has been recorded. */
        val elixirSdkVersion: String?,
        /** What the SDK reports, shown beside the tool manager's version. Never compared: it is not a bare version. */
        val elixirSdkVersionString: String?,
        /** Home of the Internal Erlang SDK paired with the module's Elixir SDK (if any). */
        val erlangSdkHomePath: String?,
        /** The recorded OTP version, compared with the tool manager's; null until it has been recorded. */
        val erlangSdkVersion: String?,
        /** What the SDK reports, shown beside the tool manager's version. Never compared. */
        val erlangSdkVersionString: String?,
        /** First content root of the module; used as the working directory for tool-manager queries. */
        val contentRoot: Path?,
    )

    // -------------------------------------------------------------------------
    // Phase 1 - model data (call inside readAction)
    // -------------------------------------------------------------------------

    /**
     * Collects the first content root path for every Elixir module in the project.
     *
     * Lighter than [collectModuleCheckData] - only returns paths, no SDK lookups.
     * Used by [ToolManagerSdkCheckerService] to determine which roots to pass to tool managers.
     * Must be called inside a `readAction { }` block.
     */
    @RequiresReadLock
    fun collectContentRoots(): List<Path> =
        ModuleManager.getInstance(project).modules
            .filter { it.isElixirModule() }
            .mapNotNull { module ->
                ModuleRootManager.getInstance(module)
                    .contentRoots
                    .firstOrNull()
                    ?.toNioPathOrNull()
            }
            .distinct()

    /**
     * Collects per-module data needed by the tool-manager scan.
     * Must be called inside a `readAction { }` block.
     */
    @RequiresReadLock
    fun collectModuleCheckData(): List<ModuleCheckData> {
        val store = SdkVersionsStore.getInstance()

        return ModuleManager.getInstance(project).modules
            .filter { it.isElixirModule() }
            .map { module ->
                val elixirSdk = ElixirSdkLookup.resolve(module).sdk
                val elixirData = elixirSdk?.elixirAdditionalData
                val erlangSdk = elixirData?.getErlangSdk()
                val contentRoot = ModuleRootManager.getInstance(module)
                    .contentRoots
                    .firstOrNull()
                    ?.toNioPathOrNull()
                LOG.trace("collectModuleCheckData: module='${module.name}' elixirSdk='${elixirSdk?.name}' contentRoot=$contentRoot")
                ModuleCheckData(
                    moduleName = module.name,
                    elixirSdkHomePath = elixirSdk?.homePath,
                    elixirSdkVersion = store.elixirVersions(elixirSdk?.homePath)
                        ?.let { versions -> versions.elixirOtpMajor.knownOrNull?.let { "${versions.elixirVersion}-otp-$it" } ?: versions.elixirVersion },
                    elixirSdkVersionString = elixirSdk?.versionString,
                    erlangSdkHomePath = erlangSdk?.homePath,
                    erlangSdkVersion = store.otpVersion(erlangSdk?.homePath),
                    erlangSdkVersionString = erlangSdk?.versionString,
                    contentRoot = contentRoot,
                )
            }
    }

    // -------------------------------------------------------------------------
    // Phase 2 - IO (call inside Dispatchers.IO, outside read lock)
    // -------------------------------------------------------------------------

    /**
     * Queries all enabled tool managers for each path in [contentRoots].
     *
     * Returns the first non-null [ToolManagerResult] per content root in priority order.
     * A [ToolManagerResult.Error] result is returned as-is (not skipped) so that
     * callers can surface the error to the user rather than silently treating the manager
     * as absent.
     *
     * Must NOT be called on the EDT or under a read lock - tool managers may spawn subprocesses.
     */
    fun resolveVersions(contentRoots: List<Path>): Map<Path, ToolManagerResult?> {
        val enabledManagers = toolManagers.filter { settings.isEnabled(it) }
        return contentRoots.associateWith { contentRoot ->
            enabledManagers.firstNotNullOfOrNull { manager ->
                LOG.trace("resolveVersions: trying '${manager.name}' for $contentRoot")
                manager.resolveVersions(contentRoot).also { result ->
                    LOG.trace("resolveVersions: '${manager.name}' returned $result for $contentRoot")
                }
            }
        }
    }

    /**
     * Resolves symlinks in the module SDK homes and the install paths the results name: an SDK home picked through a
     * symlink, such as mise's `installs/elixir/1.18`, is still the install it points at.
     *
     * Must be called on a background thread outside any read lock.
     */
    @RequiresBackgroundThread
    fun canonicalPaths(
        moduleCheckData: List<ModuleCheckData>,
        toolManagerResultsByRoot: Map<Path, ToolManagerResult?>,
    ): Map<String, String> {
        ThreadingAssertions.assertBackgroundThread()
        val sdkHomes = moduleCheckData.flatMap { listOfNotNull(it.elixirSdkHomePath, it.erlangSdkHomePath) }
        val installPaths = toolManagerResultsByRoot.values
            .filterIsInstance<ToolManagerResult.Success>()
            .flatMap { listOfNotNull(it.versions.elixir?.installPath, it.versions.erlang?.installPath) }
        return (sdkHomes + installPaths).distinct().associateWith { wslCompat.canonicalizePath(it) }
    }

    /**
     * Extracts all [ToolManagerResult.Error] entries from [toolManagerResultsByRoot].
     *
     * Called from the widget's notification scan so the widget can append tool-manager error
     * descriptions to the active notification rather than silently suppressing them.
     */
    fun collectErrors(
        toolManagerResultsByRoot: Map<Path, ToolManagerResult?>,
    ): List<ToolManagerResult.Error> =
        toolManagerResultsByRoot.values
            .filterIsInstance<ToolManagerResult.Error>()
            .distinctBy { it.description }

    // -------------------------------------------------------------------------
    // Phase 3 - pure analysis (no I/O)
    // -------------------------------------------------------------------------

    /**
     * Detects module SDKs that are not the tool manager's installs.
     *
     * Compares install paths: two builds of one Elixir version for different OTP releases share an
     * `elixir.app` version but not an install path.
     *
     * Only [ToolManagerResult.Success] entries in [toolManagerResultsByRoot] are examined;
     * [ToolManagerResult.Error] entries are skipped (they carry no install paths).
     *
     * @param moduleCheckData                    Collected in Phase 1.
     * @param toolManagerResultsByRoot           Collected in Phase 2.
     * @param canonicalPathByPath                From [canonicalPaths]; an absent path is compared as is.
     *
     * @return Pair of (issues list, module-name -> SdkVersionTable map).
     */
    fun detectMismatchIssues(
        moduleCheckData: List<ModuleCheckData>,
        toolManagerResultsByRoot: Map<Path, ToolManagerResult?>,
        canonicalPathByPath: Map<String, String>,
    ): Pair<List<ModuleSdkIssue>, Map<String, SdkVersionTable>> {
        val issues = mutableListOf<ModuleSdkIssue>()
        val tables = mutableMapOf<String, SdkVersionTable>()

        fun isInstalledAt(sdkHomePath: String, entry: ToolEntry): Boolean =
            wslCompat.pathsEqualWslAware(
                canonicalPathByPath[sdkHomePath] ?: sdkHomePath,
                canonicalPathByPath[entry.installPath] ?: entry.installPath,
            )

        for (data in moduleCheckData) {
            val contentRoot = data.contentRoot ?: run {
                LOG.trace("detectMismatchIssues: '${data.moduleName}' skipped - no content root")
                continue
            }
            val tmVersions = (toolManagerResultsByRoot[contentRoot] as? ToolManagerResult.Success)?.versions ?: run {
                LOG.trace("detectMismatchIssues: '${data.moduleName}' skipped - no successful tool manager result")
                continue
            }

            val toolName = tmVersions.toolManagerName
            val rows = mutableListOf<SdkVersionRow>()
            val runInstall = "run `$toolName install` in " + FileUtil.toSystemIndependentName(contentRoot.toString())

            // --- Elixir ---
            val tmElixir = tmVersions.elixir
            val elixirHome = data.elixirSdkHomePath
            if (tmElixir != null) {
                // A pinned version that is not installed is reported even when the module has no SDK.
                val isMismatch = elixirHome != null && !isInstalledAt(elixirHome, tmElixir)
                LOG.trace(
                    "detectMismatchIssues: '${data.moduleName}' Elixir home=$elixirHome installPath=${tmElixir.installPath}"
                )
                if (!tmElixir.installed) {
                    LOG.info("'${data.moduleName}': $toolName's Elixir ${tmElixir.version} is not installed")
                    issues.add(
                        ModuleSdkIssue(
                            moduleName = data.moduleName,
                            issue = (data.elixirSdkVersion ?: elixirHome)?.let { configured ->
                                "Elixir SDK is $configured but $toolName's ${tmElixir.version} is not installed - " +
                                    runInstall
                            } ?: "$toolName resolves Elixir ${tmElixir.version}, which is not installed - $runInstall",
                            isDangling = false,
                            notInstalled = NotInstalled(toolName, "Elixir", tmElixir.version),
                        )
                    )
                } else if (isMismatch) {
                    LOG.info("'${data.moduleName}': Elixir SDK at $elixirHome is not $toolName's ${tmElixir.version}")
                    issues.add(
                        ModuleSdkIssue(
                            moduleName = data.moduleName,
                            issue =
                                if (data.elixirSdkVersion == null || data.elixirSdkVersion == tmElixir.version) {
                                    "Elixir SDK at $elixirHome is not $toolName's ${tmElixir.version} at ${tmElixir.installPath}"
                                } else {
                                    "Elixir SDK is ${data.elixirSdkVersion} but $toolName resolves ${tmElixir.version}"
                                },
                            isDangling = false,
                        )
                    )
                }
                if (elixirHome != null) {
                    rows.add(
                        SdkVersionRow(
                            "Elixir",
                            data.elixirSdkVersionString ?: data.elixirSdkVersion,
                            tmElixir.version,
                            isMismatch,
                            tmElixir.installed,
                        )
                    )
                }
            }

            // --- Erlang ---
            val tmErlang = tmVersions.erlang
            val erlangHome = data.erlangSdkHomePath
            if (tmErlang != null) {
                val isMismatch = erlangHome != null && !isInstalledAt(erlangHome, tmErlang)
                if (!tmErlang.installed) {
                    LOG.info("'${data.moduleName}': $toolName's Erlang ${tmErlang.version} is not installed")
                    issues.add(
                        ModuleSdkIssue(
                            moduleName = data.moduleName,
                            issue = (data.erlangSdkVersion ?: erlangHome)?.let { configured ->
                                "Internal Erlang SDK is $configured but $toolName's ${tmErlang.version} is not " +
                                    "installed - $runInstall"
                            } ?: "$toolName resolves Erlang ${tmErlang.version}, which is not installed - $runInstall",
                            isDangling = false,
                            notInstalled = NotInstalled(toolName, "Erlang", tmErlang.version),
                        )
                    )
                } else if (isMismatch) {
                    LOG.info("'${data.moduleName}': Erlang SDK at $erlangHome is not $toolName's ${tmErlang.version}")
                    issues.add(
                        ModuleSdkIssue(
                            moduleName = data.moduleName,
                            issue =
                                if (data.erlangSdkVersion == null || data.erlangSdkVersion == tmErlang.version) {
                                    "Internal Erlang SDK at $erlangHome is not $toolName's ${tmErlang.version} at ${tmErlang.installPath}"
                                } else {
                                    "Internal Erlang SDK ${data.erlangSdkVersion} does not match $toolName (${tmErlang.version})"
                                },
                            isDangling = false,
                        )
                    )
                }
                if (erlangHome != null) {
                    rows.add(
                        SdkVersionRow(
                            "Erlang",
                            data.erlangSdkVersionString ?: data.erlangSdkVersion,
                            tmErlang.version,
                            isMismatch,
                            tmErlang.installed,
                        )
                    )
                }
            }

            if (rows.any { it.isMismatch }) {
                tables[data.moduleName] = SdkVersionTable(data.moduleName, toolName, rows)
            }
        }

        return Pair(issues, tables)
    }

    /**
     * Builds the map of module name -> [ToolManagerVersions] for every Elixir module whose
     * content root has an *installed* tool-manager Elixir version, and its Erlang installed too if one is pinned.
     *
     * Only [ToolManagerResult.Success] entries are considered; [ToolManagerResult.Error] entries
     * are ignored here (the widget surfaces them through a separate notification path).
     *
     * Used to populate the "Configure from <tool manager>" action in notifications.
     */
    fun buildAssignments(
        moduleCheckData: List<ModuleCheckData>,
        toolManagerResultsByRoot: Map<Path, ToolManagerResult?>,
    ): Map<String, ToolManagerVersions> {
        val result = LinkedHashMap<String, ToolManagerVersions>()
        for (data in moduleCheckData) {
            val contentRoot = data.contentRoot ?: continue
            val versions = (toolManagerResultsByRoot[contentRoot] as? ToolManagerResult.Success)?.versions ?: continue
            if (versions.elixir?.installed == true && versions.erlang?.installed != false) {
                result[data.moduleName] = versions
            }
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Phase 4 - SDK configuration (call from EDT action handler)
    // -------------------------------------------------------------------------

    /**
     * Registers Elixir and Erlang SDKs from [assignments] and assigns them to their respective
     * modules.
     *
     * Performed in three ordered phases so that the global SDK table is durably persisted before
     * any module is pointed at a newly registered SDK:
     * 1. SDK registration (and classpath root setup) via [SdkRegistrar]. The VirtualFile lookups
     *    that `setupSdkPaths` needs are pre-gathered on the background thread (off EDT) to avoid
     *    VFS refresh inside the write lock.
     * 2. A `jdk.table.xml` flush on [Dispatchers.IO] (see the inline comment below) so that
     *    `DelayedProjectSynchronizer` Attempt 2 reads the newly registered SDKs from disk instead
     *    of removing them as stale - which would undo the module assignment.
     * 3. Module assignment **last**, inside a single [edtWriteAction]. A module whose SDK can't be told is not assigned
     *    there, and the chooser asks afterwards, once per group: the modules that share the project SDK but are pinned
     *    to different installs, and each install's modules when it has several Elixir SDKs and no Erlang pin that
     *    registers.
     *
     * Uses [runWithModalProgressBlocking] internally, so this must be called from a blocking
     * context on the EDT (e.g. an `AnAction.actionPerformed` handler).
     *
     * @param assignments  Module name -> [ToolManagerVersions] as built by [buildAssignments].
     */
    fun configureSdks(assignments: Map<String, ToolManagerVersions>) {
        val toolName = assignments.values.firstOrNull()?.toolManagerName ?: "tool manager"
        LOG.trace("configureSdks: assignments keys=${assignments.keys}")
        runWithModalProgressBlocking(
            ModalTaskOwner.project(project),
            "Configuring Elixir SDK from $toolName"
        ) {
            val elixirSdkByInstallPath = mutableMapOf<String, Sdk>()
            // Installs with several Elixir SDKs and no Erlang pin that registers to tell them apart: the chooser asks which.
            val severalOnInstall = mutableSetOf<String>()

            for ((installPath, sharing) in assignments.values.groupBy { it.elixir?.installPath }) {
                val elixirPath = installPath ?: continue

                // The first of the modules' pins on this install that registers: one module with none, or with one
                // that fails, doesn't hide another's. An entry the tool manager resolves but has not installed has no
                // directory to register.
                val pinnedErlangSdk = sharing.firstNotNullOfOrNull { versions ->
                    versions.erlang?.takeIf { it.installed }?.let { erlang ->
                        LOG.trace("configureSdks: registering Erlang SDK at '${erlang.installPath}'")
                        SdkRegistrar.registerOrUpdateErlangSdk(erlang.installPath)
                    }
                }
                val erlangSdk = pinnedErlangSdk ?: run {
                    val onInstall = SdkRegistrar.elixirSdksOnInstall(elixirPath)
                    when (onInstall.size) {
                        0 -> {
                            // The step registers its pick in a write action, which `Dispatchers.UI` refuses.
                            @Suppress("ObsoleteDispatchersEdt")
                            val picked = withContext(Dispatchers.EDT) { ErlangStep.getInstance().ask(null, elixirPath, null) }
                            picked ?: continue
                        }
                        1 -> {
                            elixirSdkByInstallPath[elixirPath] = onInstall.single()
                            continue
                        }
                        else -> {
                            severalOnInstall += elixirPath
                            continue
                        }
                    }
                }
                LOG.trace("configureSdks: registering Elixir SDK at '$elixirPath' erlang='${erlangSdk.name}'")
                val elixirSdk = SdkRegistrar.registerOrUpdateElixirSdk(
                    homePath = elixirPath,
                    erlangSdk = erlangSdk,
                    project = project,
                ) ?: run {
                    LOG.warn("configureSdks: registerOrUpdateElixirSdk returned null for '$elixirPath'")
                    continue
                }
                LOG.trace("configureSdks: registered Elixir SDK '${elixirSdk.name}' for '$elixirPath'")
                elixirSdkByInstallPath[elixirPath] = elixirSdk
            }

            if (elixirSdkByInstallPath.isEmpty() && severalOnInstall.isEmpty()) {
                LOG.info("configureSdks: no SDKs registered, skipping module assignment")
                return@runWithModalProgressBlocking
            }

            // Force jdk.table.xml to disk before assigning module SDKs.
            //
            // setupSdkPaths -> commitChanges() causes workspace model changes that trigger
            // DelayedProjectSynchronizer to retry (Attempt 2). That retry reads jdk.table.xml
            // to decide which SDKs to keep in the global model. Without this flush the new SDK
            // is still only in memory, so Attempt 2 removes it - undoing the module assignment.
            //
            // Application.saveSettings() synchronously writes all dirty PersistentStateComponents
            // (including ProjectJdkTable -> jdk.table.xml) before this method returns, ensuring
            // Attempt 2 finds the newly registered SDK on disk.
            withContext(Dispatchers.IO) {
                LOG.trace("configureSdks: flushing jdk.table.xml before module assignment")
                ApplicationManager.getApplication().saveSettings()
                LOG.trace("configureSdks: jdk.table.xml flush complete")
            }

            val undecided = edtWriteAction {
                val pins = assignments.mapNotNull { (moduleName, versions) ->
                    val installPath = versions.elixir?.installPath ?: run {
                        LOG.trace("configureSdks: skipping '$moduleName' - no elixir entry")
                        return@mapNotNull null
                    }
                    if (installPath !in elixirSdkByInstallPath && installPath !in severalOnInstall) {
                        LOG.warn("configureSdks: SDK not found for installPath='$installPath' (module='$moduleName')")
                        return@mapNotNull null
                    }
                    val module = ModuleManager.getInstance(project)
                        .findModuleByName(moduleName)
                        ?.takeIf { !it.isDisposed } ?: run {
                        LOG.warn("configureSdks: module '$moduleName' not found or disposed")
                        return@mapNotNull null
                    }
                    module to installPath
                }
                // Modules that share the project SDK but are pinned to different installs: which is meant can't be
                // told, so the chooser asks for them together.
                val (sharing, own) = pins.partition { (module, _) -> ElixirSdkAssignment.target(module) == Target.PROJECT_SDK }
                val sharingDisagrees = sharing.distinctBy { it.second }.size > 1
                val (onSeveral, decided) = (if (sharingDisagrees) own else pins).partition { it.second in severalOnInstall }

                for ((module, installPath) in decided) {
                    val elixirSdk = elixirSdkByInstallPath.getValue(installPath)
                    try {
                        assignElixirSdk(module, elixirSdk)
                    } catch (ex: Exception) {
                        LOG.warn("configureSdks: failed to assign '${elixirSdk.name}' -> '${module.name}'", ex)
                    }
                }

                buildList {
                    if (sharingDisagrees) add(sharing.map { it.first })
                    addAll(onSeveral.groupBy({ it.second }, { it.first }).values)
                }
            }

            if (undecided.isNotEmpty()) {
                // The chooser writes its pick in a write action, which `Dispatchers.UI` refuses.
                @Suppress("ObsoleteDispatchersEdt")
                withContext(Dispatchers.EDT) {
                    for (modules in undecided) {
                        LOG.info("configureSdks: which Elixir SDK ${modules.map { it.name }} use can't be told; asking")
                        SdkChoice.getInstance().ask(project, modules)
                    }
                }
            }

            EditorNotifications.getInstance(project).updateAllNotifications()
        }
    }

    /**
     * Assigns [elixirSdk] to [module] through [ElixirSdkAssignment.assign].
     *
     * The Elixir SDK must already be registered in the [com.intellij.openapi.projectRoots.ProjectJdkTable];
     * [configureSdks] guarantees this ordering.
     */
    @VisibleForTesting
    @RequiresWriteLock
    internal fun assignElixirSdk(module: Module, elixirSdk: Sdk) {
        ElixirSdkAssignment.assign(module, elixirSdk)
        LOG.info("configureSdks: assigned '${elixirSdk.name}' -> '${module.name}'")
    }
}
