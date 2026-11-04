package org.elixir_lang.status_bar_widget

import com.intellij.facet.FacetManager
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.notification.impl.NotificationFullContent
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedStatusBarPopup
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import org.elixir_lang.Elixir
import org.elixir_lang.Facet
import org.elixir_lang.Icons
import org.elixir_lang.isElixirModule
import org.elixir_lang.mix.project.ProjectModuleSetupValidator
import org.elixir_lang.mix.project.ProjectModuleSetupValidator.FolderMarkIssue
import org.elixir_lang.mixContentRoots
import org.elixir_lang.mix.Project as MixProject
import org.elixir_lang.sdk.ProcessOutput.isSmallIde
import org.elixir_lang.sdk.SdkEbinPaths
import org.elixir_lang.sdk.SdkVersionsListener
import org.elixir_lang.sdk.elixir.ElixirSdkLookup
import org.elixir_lang.sdk.elixir.ElixirSdkMutation
import org.elixir_lang.sdk.elixir.ElixirSdkValidation
import org.elixir_lang.sdk.elixir.ElixirSdkValidation.detectOtpMismatch
import org.elixir_lang.sdk.elixir.ModuleSdkStatus
import org.elixir_lang.sdk.elixir.SdkSettingsOpener
import org.elixir_lang.sdk.elixir.SettingsPage
import org.elixir_lang.sdk.elixir.Type
import org.elixir_lang.sdk.elixir.sdk
import org.elixir_lang.sdk.elixir.summaryHtml
import org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData
import org.elixir_lang.tool_manager.ModuleSdkIssue
import org.elixir_lang.tool_manager.NotInstalled
import org.elixir_lang.tool_manager.SdkVersionTable
import org.elixir_lang.tool_manager.ToolManagerAnalysisResult
import org.elixir_lang.tool_manager.ToolManagerResult
import org.elixir_lang.tool_manager.ToolManagerSdkAnalyser
import org.elixir_lang.tool_manager.ToolManagerSdkCheckerService
import org.elixir_lang.tool_manager.ToolManagerSettings
import org.elixir_lang.tool_manager.ToolManagerSettingsListener
import org.elixir_lang.tool_manager.ToolManagerVersions
import org.elixir_lang.util.WriteActions
import org.elixir_lang.util.awaitJpsProjectLoaded
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val LOG = logger<ElixirEditorBasedSdkWidget>()

/**
 * Aggregated project-level SDK health status for driving notifications.
 * NOT used for widget display - the widget uses platform [EditorBasedStatusBarPopup.WidgetState]
 * via [ElixirEditorBasedSdkWidget.getWidgetState]. This sealed interface exists solely to power
 * the background notification scan triggered by rootsChanged events.
 */
internal sealed interface SdkStatus {
    data class Configured(
        val elixirSdk: Sdk,
        val erlangSdk: Sdk,
        val elixirVersion: String
    ) : SdkStatus

    data class ClasspathIssue(
        val elixirSdk: Sdk,
        val erlangSdk: Sdk,
        val elixirVersion: String,
        val issues: List<String>
    ) : SdkStatus

    data class InvalidSdk(
        val elixirSdk: Sdk?,
        val elixirVersion: String?,
        val issue: String
    ) : SdkStatus

    data class ModuleSdkError(
        val elixirSdk: Sdk?,
        val elixirVersion: String?,
        val moduleSdkIssues: List<ModuleSdkIssue>,
        val sdkVersionTables: Map<String, SdkVersionTable> = emptyMap(),
    ) : SdkStatus

    data class FolderMarkWarning(
        val elixirSdk: Sdk,
        val elixirVersion: String,
        val folderMarkIssues: List<FolderMarkIssue>
    ) : SdkStatus

    data object NotConfigured : SdkStatus

    /**
     * No Elixir SDK is configured, but a tool manager has an installed Elixir version for the
     * module's content root. Surfaces a notification with a one-click "Configure from <manager>"
     * action.
     *
     * [toolManagerVersions] carries the full tool-manager result (used for configuration).
     */
    data class NotConfiguredToolManagerAvailable(
        val toolManagerVersions: ToolManagerVersions,
    ) : SdkStatus

    /**
     * The Elixir SDK was compiled for a different OTP major than the paired Erlang SDK.
     * This can cause `{undef,[{elixir,start_cli,[],[]}]}` at runtime.
     *
     * Only surfaced when `suppressOtpMismatchWarning` is `false` on the Elixir SDK's
     * [SdkAdditionalData].
     */
    data class OtpMismatch(
        val elixirSdk: Sdk,
        val erlangSdk: Sdk,
        val elixirVersion: String,
        /** OTP major baked into the Elixir BEAM (from `otp_release` in `Elixir.System.beam`). */
        val elixirOtpMajor: String,
        /** OTP major of the paired Erlang SDK (from `releases/<N>/OTP_VERSION`). */
        val erlangOtpMajor: String,
    ) : SdkStatus
}

@OptIn(FlowPreview::class)
class ElixirEditorBasedSdkWidget(
    project: Project,
    scope: CoroutineScope,
) : EditorBasedStatusBarPopup(project, isWriteableFileRequired = false, scope = scope) {

    companion object {
        internal fun versionTableTitle(table: SdkVersionTable): String =
            if (table.rows.any { !it.isInstalled }) {
                "Elixir SDK: '${table.moduleName}' - ${table.toolManagerName} version not installed"
            } else {
                "Elixir SDK: '${table.moduleName}' Version Mismatch"
            }

        /** Not for a module SDK error: it resets the module to the project SDK, undoing what a tool manager chose. */
        internal fun offersReconfigure(status: SdkStatus): Boolean = status is SdkStatus.FolderMarkWarning

        internal fun otpMismatchMessage(sdkName: String, elixirOtpMajor: String, erlangOtpMajor: String): String =
            StringUtil.escapeXmlEntities(
                "Elixir SDK '$sdkName' was compiled for OTP $elixirOtpMajor but is paired with OTP $erlangOtpMajor. " +
                    "This may cause runtime errors (e.g. {undef,[{elixir,start_cli,...}]})."
            )

        /** A description is its tool manager's own HTML; see [ToolManagerResult.Error]. */
        internal fun toolManagerErrorLines(errors: List<ToolManagerResult.Error>): String =
            errors.joinToString("<br>") { "&#8226; [${StringUtil.escapeXmlEntities(it.toolManagerName)}] ${it.description}" }

        /** For a notice with no version table, which a module with no SDK has none of. */
        internal fun notInstalledNote(moduleName: String, pins: List<NotInstalled>): String {
            val toolManagerName = StringUtil.escapeXmlEntities(pins.first().toolManagerName)
            val names = StringUtil.escapeXmlEntities(pins.joinToString(" and ") { "${it.tool} ${it.version}" })
            val verb = if (pins.size == 1) "is" else "are"

            return "<p style=\"margin-top: 6px\"><font color=\"${ColorUtil.toHtmlColor(ERROR_COLOR)}\">" +
                "$toolManagerName's $names $verb not installed. You need to run " +
                "<code><b>$toolManagerName install</b></code><br>in the <code>${StringUtil.escapeXmlEntities(moduleName)}</code> module directory.</font></p>"
        }

        /** An SDK's own problem is fixed on the SDKs page; a module's SDK is picked where a small IDE lists modules. */
        internal fun settingsPageFor(status: SdkStatus): SettingsPage = when (status) {
            is SdkStatus.OtpMismatch, is SdkStatus.InvalidSdk, is SdkStatus.ClasspathIssue -> SettingsPage.SDKS
            else -> SettingsPage.MODULE_SDKS
        }

        /** What a notice's Configure assigns: the modules it is about, or every module when none has an SDK. */
        internal fun configureAssignments(
            status: SdkStatus,
            assignments: Map<String, ToolManagerVersions>,
        ): Map<String, ToolManagerVersions> = when (status) {
            is SdkStatus.ModuleSdkError -> {
                val affectedNames = status.moduleSdkIssues.mapTo(HashSet()) { it.moduleName }
                assignments.filterKeys { it in affectedNames }
            }
            is SdkStatus.NotConfiguredToolManagerAvailable -> assignments
            else -> emptyMap()
        }

        /** What each module's Configure would assign, which a notice's text does not always show. */
        internal fun configures(assignments: Map<String, ToolManagerVersions>): Map<String, List<String?>> =
            assignments.mapValues { (_, versions) -> listOf(versions.elixir?.installPath, versions.erlang?.installPath) }

        internal fun isSameNotice(
            one: com.intellij.notification.Notification,
            oneConfigures: Map<String, List<String?>>,
            other: com.intellij.notification.Notification,
            otherConfigures: Map<String, List<String?>>,
        ): Boolean =
            one.title == other.title && one.content == other.content && one.type == other.type &&
                one.actions.map { it.templateText } == other.actions.map { it.templateText } &&
                oneConfigures == otherConfigures

        private val ERROR_COLOR = JBColor(0xC7222D, 0xE55765)

        internal fun danglingMessage(moduleNames: List<String>, sdkNames: List<String>, targetName: String): String {
            val moduleCount = moduleNames.size
            val moduleWord = StringUtil.pluralize("Module", moduleCount)
            val moduleList = formatNameList(moduleNames)
            val verb = if (moduleCount == 1) "references" else "reference"
            val sdkWord = StringUtil.pluralize("SDK", sdkNames.size)
            val sdkList = sdkNames.joinToString(", ") { "'$it'" }
            // A facet whose SDK was deleted names none, so there is no missing SDK to name.
            val problem = if (sdkNames.isEmpty()) {
                "$moduleWord $moduleList ${if (moduleCount == 1) "has" else "have"} no Elixir SDK"
            } else {
                "$moduleWord $moduleList $verb non-existent $sdkWord $sdkList"
            }
            val navigationHint = when {
                targetName != "Project Structure" && moduleCount == 1 ->
                    "Settings -> Languages & Frameworks -> Elixir. Pick an Elixir SDK for '${moduleNames.first()}'"
                targetName != "Project Structure" ->
                    "Settings -> Languages & Frameworks -> Elixir. Pick an Elixir SDK for each module"
                moduleCount == 1 ->
                    "Project Structure -> Modules -> ${moduleNames.first()} -> Dependencies -> Module SDK. Set it to \"Project SDK\""
                else ->
                    "Project Structure -> Modules -> <module> -> Dependencies -> Module SDK. Set each to \"Project SDK\""
            }
            return StringUtil.escapeXmlEntities("$problem. Code insight will not work until fixed in $navigationHint.")
        }

        private fun formatNameList(names: List<String>): String {
            return if (names.size <= 3) {
                names.joinToString(", ") { "'$it'" }
            } else {
                names.take(3).joinToString(", ") { "'$it'" } + " and ${names.size - 3} more"
            }
        }

        const val ID = "ElixirSdkStatus"

        /** Long enough for a scan through a WSL distro; a project whose modules have no content root gets no result. */
        private val TOOL_MANAGER_RESULT_WAIT = 10.seconds
    }

    // Latest tool-manager analysis result, updated whenever ToolManagerSdkAnalyser publishes.
    // Null before the first analysis completes (the analyser service is now registered in every
    // IDE, so getInstanceIfRegistered is only null if the service somehow failed to load).
    @Volatile
    private var latestTmAnalysis: ToolManagerAnalysisResult? = null

    @Volatile
    private var toolManagerResultWaitOver = ToolManagerSdkAnalyser.getInstanceIfRegistered(project) == null

    // Track last notified status to avoid spamming duplicate notifications
    @Volatile
    private var lastNotifiedIssueKey: String? = null

    // The currently displayed notification - expired when the issue resolves or changes
    @Volatile
    private var activeNotification: com.intellij.notification.Notification? = null

    @Volatile
    private var activeNotificationConfigures: Map<String, List<String?>> = emptyMap()

    // Cached count of Elixir modules in the project.  Updated on rootsChanged (which is the
    // only event that can add/remove modules) so that getWidgetState() remains O(1) and does
    // not iterate all modules on every editor switch.  Initialised to -1 (unknown) so the
    // first getWidgetState() call computes it lazily; subsequent rootsChanged events refresh it.
    @Volatile
    private var cachedElixirModuleCount: Int = -1

    /**
     * Debounced flow for the project-wide notification scan.  The platform already debounces
     * getWidgetState via its own MutableSharedFlow (300ms), but the notification scan is a
     * separate heavier path (O(modules × orderEntries)) that we debounce independently to
     * avoid redundant work during bulk rootsChanged events.
     *
     * Primary trigger: `mix deps.get` -> VFS detects new files under `deps/` and `_build/` ->
     * Mix dep sync updates libraries -> each per-dep `libraryModifiableModel.commit()` and
     * `ModuleRootManager.modifiableModel.commit()` fires a separate rootsChanged event.
     * A Phoenix project with 30 deps can easily generate 60–90+ events in quick succession.
     */
    private val notificationScanRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        // Trigger the initial notification scan only after the JPS project model sync completes: any SDK
        // assignment committed before the sync applies the on-disk .iml state would be overwritten, so we must
        // wait until the workspace model is stable before showing a notification or letting the user act on it.
        // See `awaitJpsProjectLoaded` for why this uses the deprecated/internal API and the migration tickets.
        scope.launch {
            awaitJpsProjectLoaded(project)
            notificationScanRequests.tryEmit(Unit)
            delay(TOOL_MANAGER_RESULT_WAIT)
            toolManagerResultWaitOver = true
            notificationScanRequests.tryEmit(Unit)
        }

        // Subscribe to tool-manager analysis results.  When the analyser publishes new results
        // (after a scan completes or module SDKs change), cache them and trigger a notification
        // scan so notifyIfNeeded() uses the latest TM data.
        // getInstanceIfRegistered is null-safe in case the service failed to load; then
        // latestTmAnalysis stays null and notifyIfNeeded() skips all TM-specific logic.
        ToolManagerSdkAnalyser.getInstanceIfRegistered(project)?.subscribeWithLatest(
            parentDisposable = this,
            onAnalysisCompleted = { result ->
                latestTmAnalysis = result
                notificationScanRequests.tryEmit(Unit)
            }
        )

        val notificationScanJob = scope.launch {
            notificationScanRequests
                .debounce(500.milliseconds)  // slightly longer than widget debounce since notifications are less urgent
                // Use `collect` (not `collectLatest`) deliberately: if events arrive while a scan
                // is running, they queue in the debounce operator and trigger one follow-up scan
                // after the current one completes + 500ms of quiet.  `collectLatest` would cancel
                // a partially-complete scan and restart, wasting work - safe but inefficient since
                // the notification outcome is idempotent (duplicate issueKeys are suppressed).
                .collect {
                    // Schedule a refresh of any umbrella sub-apps before scanning: `mix new`
                    // writes them from an external process, so VFS can hold a child list without
                    // their mix.exs and the folder-mark scan would skip them. Asynchronous, and
                    // outside the readAction below, so no I/O happens under the lock - the
                    // refreshed state is picked up by the next scan.
                    readAction {
                        ModuleManager.getInstance(project).modules.flatMap { it.mixContentRoots() }
                    }.forEach { MixProject.refreshUmbrellaSubApps(it.root, async = true) }

                    val modelData = readAction { collectNotificationScanModelData() }
                    val ioData = withContext(Dispatchers.IO) { collectNotificationScanIoData(modelData) }
                    val tmAnalysis = latestTmAnalysis
                    // A notice raised before the tool manager's result would be replaced once it arrives, and the
                    // platform keeps a replaced notice in the list, so the first one waits for it or for the timeout.
                    if (tmAnalysis == null && !toolManagerResultWaitOver) return@collect

                    notifyIfNeeded(
                        moduleSdkIssues = modelData.moduleSdkIssues + (tmAnalysis?.tmIssues ?: emptyList()),
                        folderMarkIssues = modelData.folderMarkIssues,
                        tmAnalysis = tmAnalysis,
                        projectSdkSnapshot = modelData.projectSdkSnapshot,
                        classpathIssues = ioData.classpathIssues,
                        projectSdkOtpMismatch = ioData.projectSdkOtpMismatch,
                    )
                }
        }
        // Cancel the notification scan collector when this widget instance is disposed.
        // This mirrors the semantics of the platform's own debounce collector in
        // EditorBasedStatusBarPopup (which uses @Internal cancelOnDispose).  Without this,
        // multiframe widget instances would leave behind active collectors until project close.
        val jobDisposable = Disposer.newDisposable("ElixirEditorBasedSdkWidget.notificationScanJob")
        Disposer.register(this, jobDisposable)
        notificationScanJob.invokeOnCompletion { Disposer.dispose(jobDisposable) }
        Disposer.register(jobDisposable) {
            notificationScanJob.cancel(CancellationException("ElixirEditorBasedSdkWidget disposed"))
        }
    }

    override fun ID(): String = ID

    // -------------------------------------------------------------------------
    // EditorBasedStatusBarPopup required overrides
    // -------------------------------------------------------------------------

    /**
     * Returns the widget state for the currently open file.
     *
     * This method is called by the platform inside a readAction on a background thread
     * (see EditorBasedStatusBarPopup.doUpdate). Do NOT nest another readAction here.
     * Performs only O(1) SDK lookups - no project-wide scans or notifications.
     */
    @RequiresBackgroundThread
    override fun getWidgetState(file: VirtualFile?): WidgetState {
        if (file == null) return WidgetState.HIDDEN
        val module = ModuleUtilCore.findModuleForFile(file, project)
            ?: return WidgetState.HIDDEN
        if (!module.isElixirModule()) return WidgetState.HIDDEN
        return buildModuleWidgetState(module)
    }

    override fun createPopup(context: DataContext): ListPopup {
        val actionGroup = DefaultActionGroup().apply {
            add(createAddSdkAction())
            val actionManager = ActionManager.getInstance()
            actionManager.getAction("Elixir.RefreshAllElixirSdks")?.let { add(it) }
            actionManager.getAction("Elixir.InstallMixDependencies")?.let { add(it) }
            add(Separator.getInstance())
            actionManager.getAction("Elixir.ReconfigureModuleSetup")?.let { add(it) }
        }
        return JBPopupFactory.getInstance().createActionGroupPopup(
            "Elixir SDK",
            actionGroup,
            context,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            false
        )
    }

    override fun createInstance(project: Project): StatusBarWidget =
            ElixirEditorBasedSdkWidget(project, scope)

    override fun registerCustomListeners(connection: MessageBusConnection) {
        // JDK table changes (SDK added/removed/renamed) - affects both widget display and
        // project-wide notifications (e.g. SDK removal creates dangling references).
        connection.subscribe(ProjectJdkTable.JDK_TABLE_TOPIC, object : ProjectJdkTable.Listener {
            override fun jdkAdded(jdk: Sdk) {
                if (jdk.sdkType is Type) {
                    update()
                    notificationScanRequests.tryEmit(Unit)
                }
            }

            override fun jdkRemoved(jdk: Sdk) {
                if (jdk.sdkType is Type) {
                    update()
                    notificationScanRequests.tryEmit(Unit)
                }
            }

            override fun jdkNameChanged(jdk: Sdk, previousName: String) {
                if (jdk.sdkType is Type) {
                    update()
                    notificationScanRequests.tryEmit(Unit)
                }
            }
        })

        // Module root changes - update the widget (platform debounces at 300ms) and
        // request a project-wide notification scan via the debounced flow.
        connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: com.intellij.openapi.roots.ModuleRootEvent) {
                cachedElixirModuleCount = -1  // invalidate; recomputed lazily in getWidgetState()
                update()  // triggers getWidgetState for per-file widget display
                notificationScanRequests.tryEmit(Unit)  // debounced notification scan
            }
        })

        // SDK refresh (from RefreshAllElixirSdksAction) - re-scan notifications since SDK
        // validity may have changed (e.g. Erlang SDK path restored).
        // Clear lastNotifiedIssueKey so the scan re-notifies even if the same mismatch persists -
        // the user explicitly requested a refresh and must see the current state.
        connection.subscribe(ElixirSdkRefreshListener.TOPIC, ElixirSdkRefreshListener {
            lastNotifiedIssueKey = null
            update()
            notificationScanRequests.tryEmit(Unit)
        })

        // Registration fills the version store after the SDK table's add is published, so scans made from that add
        // can predate the versions. Both calls only emit to flows, so they are safe from the filling coroutine.
        connection.subscribe(
            SdkVersionsListener.TOPIC,
            SdkVersionsListener { _, _ ->
                update()
                notificationScanRequests.tryEmit(Unit)
            },
        )

        // Tool manager settings change (enable/disable a manager in Settings -> Elixir -> Tool Managers).
        // The user explicitly changed which managers are active, so they must see the current state
        // even if the overall SdkStatus category (e.g. NotConfigured) has not changed.  Without
        // this reset the notification deduplication would suppress the updated message that includes
        // tool-manager errors (e.g. "mise config not trusted") because the issue key is unchanged.
        connection.subscribe(
            ToolManagerSettings.SETTINGS_CHANGED_TOPIC,
            ToolManagerSettingsListener { lastNotifiedIssueKey = null }
        )
    }

    // -------------------------------------------------------------------------
    // Per-module widget state
    // -------------------------------------------------------------------------

    private fun buildModuleWidgetState(module: com.intellij.openapi.module.Module): WidgetState {
        LOG.trace("buildModuleWidgetState: ${module.name}")
        val elixirSdk = ElixirSdkLookup.resolve(module).sdk

        // Determine whether to show module name in tooltip (only in multi-module projects).
        // Uses the cached count (refreshed on rootsChanged) to keep getWidgetState() O(1).
        var count = cachedElixirModuleCount
        if (count < 0) {
            // First call before any rootsChanged - compute lazily and cache.
            count = ModuleManager.getInstance(project).modules.count { it.isElixirModule() }
            cachedElixirModuleCount = count
        }
        val moduleName = if (count > 1) module.name else null

        // Shared classification + tooltip text (see ModuleSdkStatus), also used by the
        // Settings -> Elixir per-module SDK page so the status text lives in one place.
        val status = ModuleSdkStatus.of(elixirSdk)
        val toolTip = status.summaryHtml(moduleName)
        return when (status) {
            is ModuleSdkStatus.NoSdk ->
                WidgetState(toolTip, "No Elixir SDK", true)

            is ModuleSdkStatus.Invalid, is ModuleSdkStatus.MissingErlang ->
                WidgetState(toolTip, "Elixir: SDK Error", true).apply { icon = AllIcons.General.Error }

            is ModuleSdkStatus.Ready ->
                WidgetState(toolTip, status.elixirSdk.name, true).apply { icon = Icons.LANGUAGE }
        }
    }

    // -------------------------------------------------------------------------
    // Project-wide notification scan (triggered from rootsChanged background job)
    // -------------------------------------------------------------------------

    private data class ProjectSdkSnapshot(
        val elixirSdk: Sdk?,
        val elixirVersion: String?,
        val erlangSdk: Sdk?,
    )

    private data class NotificationScanModelData(
        val moduleSdkIssues: List<ModuleSdkIssue>,
        val folderMarkIssues: List<FolderMarkIssue>,
        val projectSdkSnapshot: ProjectSdkSnapshot,
    )

    private data class NotificationScanIoData(
        val projectSdkOtpMismatch: Pair<String, String>?,
        val classpathIssues: List<String>,
    )

    @RequiresReadLock
    private fun collectNotificationScanModelData(): NotificationScanModelData {
        val moduleSdkIssues = detectModuleSdkIssues()
        val folderMarkIssues = ProjectModuleSetupValidator.detectFolderMarkIssues(project)
        val projectSdkSnapshot = collectProjectSdkSnapshot()

        return NotificationScanModelData(
            moduleSdkIssues = moduleSdkIssues,
            folderMarkIssues = folderMarkIssues,
            projectSdkSnapshot = projectSdkSnapshot,
        )
    }

    @RequiresReadLock
    private fun collectProjectSdkSnapshot(): ProjectSdkSnapshot {
        val elixirSdk = findModuleLevelElixirSdk()
        val elixirVersion = elixirSdk?.versionString?.let {
            org.elixir_lang.sdk.Type.appendWslSuffix(it, elixirSdk.homePath)
        }
        val erlangSdk = elixirSdk?.let(Elixir::elixirSdkToErlangSdk)

        return ProjectSdkSnapshot(
            elixirSdk = elixirSdk,
            elixirVersion = elixirVersion,
            erlangSdk = erlangSdk,
        )
    }

    private suspend fun collectNotificationScanIoData(modelData: NotificationScanModelData): NotificationScanIoData {
        val snapshot = modelData.projectSdkSnapshot
        // The single-arg overload is used deliberately to keep honouring isSuppressOtpMismatchWarning.
        val projectSdkOtpMismatch = snapshot.elixirSdk?.let { elixirSdk -> detectOtpMismatch(elixirSdk) }
        return NotificationScanIoData(
            projectSdkOtpMismatch = projectSdkOtpMismatch,
            classpathIssues = detectClasspathIssues(snapshot),
        )
    }

    private fun notifyIfNeeded(
        moduleSdkIssues: List<ModuleSdkIssue>,
        folderMarkIssues: List<FolderMarkIssue>,
        /** Tool-manager analysis result from [ToolManagerSdkAnalyser], or null if unavailable. */
        tmAnalysis: ToolManagerAnalysisResult? = null,
        projectSdkSnapshot: ProjectSdkSnapshot,
        classpathIssues: List<String>,
        /** Pre-computed OTP mismatch for the project's primary Elixir SDK, or `null` when there
         *  is no mismatch, when detection failed, or when the user suppressed warnings. */
        projectSdkOtpMismatch: Pair<String, String>? = null,
    ) {
        val elixirSdk = projectSdkSnapshot.elixirSdk
        val erlangSdk = projectSdkSnapshot.erlangSdk
        val elixirVersion = projectSdkSnapshot.elixirVersion ?: "Unknown"

        val tmAssignments = tmAnalysis?.tmAssignments ?: emptyMap()
        val sdkVersionTables = tmAnalysis?.sdkVersionTables ?: emptyMap()
        val toolManagerErrors = tmAnalysis?.toolManagerErrors ?: emptyList()

        val sdkStatus: SdkStatus = when {
            moduleSdkIssues.isNotEmpty() ->
                SdkStatus.ModuleSdkError(elixirSdk, projectSdkSnapshot.elixirVersion, moduleSdkIssues, sdkVersionTables)

            folderMarkIssues.isNotEmpty() && elixirSdk != null ->
                SdkStatus.FolderMarkWarning(elixirSdk, elixirVersion, folderMarkIssues)

            elixirSdk == null && tmAssignments.isNotEmpty() -> {
                SdkStatus.NotConfiguredToolManagerAvailable(tmAssignments.values.first())
            }

            // No SDK and no tool-manager assignments.  If tool manager errors are present they
            // likely prevented version resolution - treat as NotConfigured; the error descriptions
            // are appended to the notification message below.
            elixirSdk == null ->
                SdkStatus.NotConfigured

            erlangSdk == null ->
                SdkStatus.InvalidSdk(elixirSdk, elixirVersion, "Missing internal Erlang SDK dependency")

            classpathIssues.isNotEmpty() ->
                SdkStatus.ClasspathIssue(elixirSdk, erlangSdk, elixirVersion, classpathIssues)

            // OTP mismatch pre-computed by ElixirSdkValidation.detectOtpMismatch in the IO phase.
            // Suppress is handled inside the check itself - null here means "match or suppressed".
            projectSdkOtpMismatch != null ->
                SdkStatus.OtpMismatch(
                    elixirSdk, erlangSdk, elixirVersion,
                    projectSdkOtpMismatch.first, projectSdkOtpMismatch.second
                )

            else ->
                SdkStatus.Configured(elixirSdk, erlangSdk, elixirVersion)
        }

        val issueKey = computeIssueKey(sdkStatus)

        // No issue - expire any active notification and reset
        if (issueKey == null) {
            activeNotification?.expire()
            activeNotification = null
            lastNotifiedIssueKey = null
            return
        }
        // Same issue already notified - keep the existing notification
        if (issueKey == lastNotifiedIssueKey) return

        lastNotifiedIssueKey = issueKey

        val (title, message, type) = buildNotificationContent(sdkStatus) ?: return

        // Append tool-manager error descriptions when they prevented version resolution.
        // Each concrete ElixirToolManager implementation provides a human-readable description
        // and fix hint; the widget renders them verbatim without needing to know the error type.
        val fullMessage = if (toolManagerErrors.isNotEmpty()) {
            "$message<br><br><b>Tool manager errors:</b><br>${toolManagerErrorLines(toolManagerErrors)}"
        } else {
            message
        }

        val notification = object : com.intellij.notification.Notification("Elixir SDK", StringUtil.escapeXmlEntities(title), fullMessage, type),
            NotificationFullContent {}

        // "Configure from <tool manager>" - for module SDK mismatches with a tool manager assignment.
        // When any module SDK issue has a corresponding tool manager configured erlang or elixir SDK (covering both dangling
        // references and version mismatches), offer it.  Each affected module is assigned the
        // SDK that the tool manager resolves for *its own* content root - umbrella apps with different
        // configurations get different SDKs rather than a shared one.
        if (sdkStatus is SdkStatus.ModuleSdkError) {
            val relevantAssignments = configureAssignments(sdkStatus, tmAssignments)
            if (relevantAssignments.isNotEmpty()) {
                val toolName = sdkStatus.sdkVersionTables.values.firstOrNull()?.toolManagerName
                    ?: relevantAssignments.values.firstOrNull()?.toolManagerName
                    ?: "tool manager"
                notification.addAction(object : AnAction("Configure from $toolName") {
                    override fun actionPerformed(e: AnActionEvent) {
                        notification.expire()
                        lastNotifiedIssueKey = null
                        activeNotification = null
                        project.getServiceIfCreated(ToolManagerSdkCheckerService::class.java)
                            ?.configureSdks(relevantAssignments)
                    }
                })
            }
        }

        // For OTP mismatch, offer "Don't warn for this SDK" (suppress flag) and "Configure...".
        if (sdkStatus is SdkStatus.OtpMismatch) {
            val affectedElixirSdk = sdkStatus.elixirSdk
            notification.addAction(object : AnAction("Don't Warn for This SDK") {
                override fun actionPerformed(e: AnActionEvent) {
                    notification.expire()
                    lastNotifiedIssueKey = null
                    activeNotification = null
                    WriteActions.runWriteAction {
                        ElixirSdkMutation.setOtpMismatchWarningSuppressed(affectedElixirSdk, true)
                    }
                    // Re-scan so the notification stays dismissed.
                    notificationScanRequests.tryEmit(Unit)
                }
            })
        }

        // "Configure from <tool manager>" - for unconfigured project with tool manager available.
        if (sdkStatus is SdkStatus.NotConfiguredToolManagerAvailable) {
            val toolName = sdkStatus.toolManagerVersions.toolManagerName
            notification.addAction(object : AnAction("Configure from $toolName") {
                override fun actionPerformed(e: AnActionEvent) {
                    notification.expire()
                    project.getServiceIfCreated(ToolManagerSdkCheckerService::class.java)
                        ?.configureSdks(tmAssignments)
                }
            })
        }

        // "Reconfigure Now" - for folder mark warnings.
        if (offersReconfigure(sdkStatus)) {
            val reconfigureAction = ActionManager.getInstance().getAction("Elixir.ReconfigureModuleSetup")
            if (reconfigureAction != null) {
                notification.addAction(object : AnAction("Reconfigure Now") {
                    override fun actionPerformed(e: AnActionEvent) {
                        reconfigureAction.actionPerformed(e)
                        // Allow the same issue to be shown again if reconfigure did not resolve it.
                        lastNotifiedIssueKey = null
                        activeNotification = null
                        notificationScanRequests.tryEmit(Unit)
                        notification.expire()
                    }
                })
            }
        }

        // Left live, as the settings may be closed without a fix; a scan that finds the issue gone expires it.
        notification.addAction(object : AnAction("Open ${SdkSettingsOpener.getInstance().targetName()}") {
            override fun actionPerformed(e: AnActionEvent) {
                SdkSettingsOpener.getInstance().open(e, settingsPageFor(sdkStatus))
            }
        })

        // An issue can change without changing what the notice says, such as a tool-manager result arriving after
        // the first scan; publishing again would show the same notice twice.
        val live = activeNotification
        val notificationConfigures = configures(configureAssignments(sdkStatus, tmAssignments))
        if (live != null && !live.isExpired &&
            isSameNotice(live, activeNotificationConfigures, notification, notificationConfigures)
        ) return
        live?.expire()

        notification.notify(project)
        activeNotification = notification
        activeNotificationConfigures = notificationConfigures
        LOG.info("SDK discrepancy in project '${project.name}': $message")

        if (sdkStatus is SdkStatus.FolderMarkWarning) {
            for (issue in sdkStatus.folderMarkIssues) {
                LOG.info("  Folder mark issue: module '${issue.moduleName}' -- ${issue.folderRelativePath}/ should be ${issue.folderMark.displayName} but is ${issue.currentState}")
            }
        }
        if (sdkStatus is SdkStatus.ModuleSdkError) {
            for (issue in sdkStatus.moduleSdkIssues) {
                LOG.info("  Module SDK issue: module '${issue.moduleName}' -- ${issue.issue}")
            }
        }
    }

    private fun computeIssueKey(status: SdkStatus): String? {
        return when (status) {
            is SdkStatus.Configured -> null
            is SdkStatus.NotConfigured -> "not-configured"
            is SdkStatus.NotConfiguredToolManagerAvailable ->
                "not-configured-tm:${status.toolManagerVersions.toolManagerName}:${status.toolManagerVersions.elixir?.version}"
            is SdkStatus.InvalidSdk -> "partial:${status.issue}"
            is SdkStatus.ClasspathIssue -> "warning:${status.issues.sorted().joinToString(",")}"
            is SdkStatus.OtpMismatch ->
                "otp-mismatch:${status.elixirSdk.name}:${status.elixirOtpMajor}:${status.erlangOtpMajor}"

            is SdkStatus.ModuleSdkError -> "module-error:${
                status.moduleSdkIssues.map { "${it.moduleName}:${it.isDangling}:${it.issue}" }
                    .sorted()
                    .joinToString(",")
            }"

            is SdkStatus.FolderMarkWarning -> "folder-marks:${
                status.folderMarkIssues.map { "${it.moduleName}:${it.folderRelativePath}:${it.folderMark}" }
                    .sorted()
                    .joinToString(",")
            }"
        }
    }

    private data class NotificationContent(val title: String, val message: String, val type: NotificationType)

    private fun buildNotificationContent(status: SdkStatus): NotificationContent? = when (status) {
            is SdkStatus.Configured -> null

            is SdkStatus.NotConfigured -> NotificationContent(
            "Elixir SDK Not Configured",
            "No Elixir SDK is configured for this project. Code insight will not work.",
            NotificationType.WARNING
        )

        is SdkStatus.NotConfiguredToolManagerAvailable -> {
            val toolName = status.toolManagerVersions.toolManagerName
            val displayVersion = status.toolManagerVersions.elixir?.version ?: "unknown"
            NotificationContent(
                "Elixir SDK Not Configured",
                StringUtil.escapeXmlEntities("No Elixir SDK configured, but Elixir $displayVersion is available via $toolName."),
                NotificationType.WARNING
            )
        }

        is SdkStatus.InvalidSdk -> NotificationContent(
            "Elixir SDK Issue",
            StringUtil.escapeXmlEntities("Elixir SDK: ${status.elixirVersion ?: "Unknown"} - ${status.issue}."),
            NotificationType.WARNING
        )

        is SdkStatus.ClasspathIssue -> NotificationContent(
            "Elixir SDK Warning",
            StringUtil.escapeXmlEntities("Elixir SDK ${status.elixirVersion}: ${status.issues.joinToString("; ")}."),
            NotificationType.WARNING
        )

        is SdkStatus.OtpMismatch -> NotificationContent(
            "Elixir SDK OTP Version Mismatch",
            otpMismatchMessage(status.elixirSdk.name, status.elixirOtpMajor, status.erlangOtpMajor),
            NotificationType.WARNING
        )

        is SdkStatus.ModuleSdkError -> {
            val hasDangling = status.moduleSdkIssues.any { it.isDangling }
            if (hasDangling) {
                val danglingIssues = status.moduleSdkIssues.filter { it.isDangling }
                val moduleNames = danglingIssues.map { it.moduleName }.distinct()
                val sdkNames = danglingIssues.mapNotNull { it.missingSdkName }.distinct()
                // Outranked by the missing SDK, a pin not installed would otherwise go unmentioned.
                val notInstalledNotes = moduleNames.mapNotNull { moduleName ->
                    status.moduleSdkIssues
                        .filter { it.moduleName == moduleName }
                        .mapNotNull { it.notInstalled }
                        .takeIf { it.isNotEmpty() }
                        ?.let { notInstalledNote(moduleName, it) }
                }
                NotificationContent(
                    "Elixir SDK Error: Module SDK ${StringUtil.pluralize("reference", moduleNames.size)} broken",
                    danglingMessage(moduleNames, sdkNames, SdkSettingsOpener.getInstance().targetName()) +
                        notInstalledNotes.joinToString(""),
                    NotificationType.ERROR
                )
            } else {
                val distinctModules = status.moduleSdkIssues.map { it.moduleName }.distinct()
                if (distinctModules.size == 1) {
                    val moduleName = distinctModules.first()
                    val table = status.sdkVersionTables[moduleName]
                    if (table != null) {
                        NotificationContent(
                            versionTableTitle(table),
                            buildSdkVersionTableHtml(table),
                            NotificationType.WARNING
                        )
                    } else {
                        val issueDescriptions = status.moduleSdkIssues.joinToString("; ") { it.issue }
                        NotificationContent(
                            "Elixir SDK: '$moduleName' Version Mismatch",
                            StringUtil.escapeXmlEntities("Mismatch detected: $issueDescriptions."),
                            NotificationType.WARNING
                        )
                    }
                } else {
                    val summary = status.moduleSdkIssues.joinToString("; ") { "'${it.moduleName}': ${it.issue}" }
                    NotificationContent(
                        "Elixir SDK: Module Version Mismatches",
                        StringUtil.escapeXmlEntities("Tool manager reports version mismatches. $summary."),
                        NotificationType.WARNING
                    )
                }
            }
        }

        is SdkStatus.FolderMarkWarning -> {
            val issueCount = status.folderMarkIssues.size
            val moduleCount = status.folderMarkIssues.map { it.moduleName }.distinct().size
            val summary = if (issueCount == 1) {
                val issue = status.folderMarkIssues.first()
                "${issue.folderRelativePath}/ is not marked as ${issue.folderMark.displayName} in module '${issue.moduleName}'"
            } else {
                    val issuesPlural=StringUtil.pluralize("issue", issueCount)
                    val modulesPlural=StringUtil.pluralize("module", moduleCount)
                    "$issueCount folder mark $issuesPlural across $moduleCount $modulesPlural"
                }
            NotificationContent(
                "Elixir: Project Structure Suboptimal",
                StringUtil.escapeXmlEntities("$summary. Some code insight features may not work correctly."),
                NotificationType.WARNING
            )
        }
    }

    // -------------------------------------------------------------------------
    // SDK detection helpers (shared between getWidgetState and notification scan)
    // -------------------------------------------------------------------------

    /**
     * Finds the active Elixir SDK by scanning all Elixir modules.
     *
     * Uses [ElixirSdkLookup.resolve] (module overload), which reads the module SDK in a rich IDE and the Facet SDK
     * in a small one, so neither needs branching here.
     *
     * When both the project SDK and a module SDK are Elixir, this returns the **module** SDK - the one
     * that actually drives code insight - rather than the project-level one. The mismatch between them
     * is reported separately by [detectModuleSdkIssues].
     */
    @RequiresReadLock
    internal fun findModuleLevelElixirSdk(): Sdk? {
        for (module in ModuleManager.getInstance(project).modules) {
            ProgressManager.checkCanceled()
            if (!module.isElixirModule()) continue
            val moduleSdk = ElixirSdkLookup.resolve(module).sdk
            if (moduleSdk != null) return moduleSdk
        }
        return null
    }

    /**
     * Detects project-wide module SDK issues (dangling references and mismatches).
     * O(modules × orderEntries) - only called from the rootsChanged background job,
     * never from [getWidgetState].
     *
     * **Threading**: must be called off the EDT (background thread).  Accesses
     * [ModuleRootManager] which requires read access; the caller must either hold a read lock
     * or call this inside a `readAction { }` block.
     */
    @RequiresReadLock
    @RequiresBackgroundThread
    internal fun detectModuleSdkIssues(): List<ModuleSdkIssue> {
        ThreadingAssertions.assertBackgroundThread()
        val issues = mutableListOf<ModuleSdkIssue>()
        val projectSdk = ProjectRootManager.getInstance(project).projectSdk

        for (module in ModuleManager.getInstance(project).modules) {
            ProgressManager.checkCanceled()
            if (!module.isElixirModule()) continue
            val rootManager = ModuleRootManager.getInstance(module)

            // --- Rich IDE path: detect dangling/mismatch JdkOrderEntry ---
            // The lookup never reads a module SDK in a small IDE, which has no settings to change one.
            for (entry in if (isSmallIde) emptyArray() else rootManager.orderEntries) {
                ProgressManager.checkCanceled()
                if (entry !is JdkOrderEntry) continue

                val jdkName = entry.jdkName ?: continue
                // Only check Elixir SDK entries (when SDK is resolvable, check its type)
                val resolvedSdk = entry.jdk
                if (resolvedSdk != null && resolvedSdk.sdkType !is Type) continue

                if (resolvedSdk == null) {
                    // DANGLING: SDK name exists in .iml but not in JDK table
                    issues.add(
                        ModuleSdkIssue(module.name, "references non-existent SDK '$jdkName'", isDangling = true, missingSdkName = jdkName)
                    )
                } else if (projectSdk != null && projectSdk.sdkType is Type && resolvedSdk != projectSdk) {
                    // MISMATCH: Module uses different Elixir SDK than project in a single-version project.
                    // Only meaningful when the project SDK is itself Elixir; skip when it is Java/Python/etc.
                    issues.add(
                        ModuleSdkIssue(
                            module.name,
                            "uses '$jdkName' but project SDK is '${projectSdk.name}'",
                            isDangling = false
                        )
                    )
                }
            }

            // --- Small IDE path: detect dangling Facet SDK ---
            if (isSmallIde) {
                val facet = FacetManager.getInstance(module).getFacetByType(Facet.ID)
                if (facet != null && Facet.sdk(module) == null && Facet.sdks().isNotEmpty()) {
                    // The module has an Elixir Facet and Elixir SDKs exist in the table, but the
                    // Facet library entry doesn't match any current SDK name - stale/dangling reference.
                    issues.add(
                        ModuleSdkIssue(
                            module.name,
                            "Elixir Facet SDK is not configured (stale reference). Reconfigure in Settings -> Languages -> Elixir.",
                            isDangling = true
                        )
                    )
                }
            }
        }
        return issues
    }

    private fun detectClasspathIssues(snapshot: ProjectSdkSnapshot): List<String> {
        val elixirSdk = snapshot.elixirSdk ?: return emptyList()
        val erlangSdk = snapshot.erlangSdk ?: return emptyList()
        val issues = mutableListOf<String>()
        if (hasEbinPathIssues(elixirSdk)) issues.add("Missing Elixir ebin paths or classpath entries")
        if (hasErlangSdkIssues(erlangSdk)) issues.add("Erlang SDK configuration issues")
        if (!ElixirSdkValidation.hasErlangClasspathInElixirSdk(elixirSdk, erlangSdk))
            issues.add("Erlang SDK classpath entries missing - reopen SDK settings to fix")
        return issues
    }

    private fun hasEbinPathIssues(elixirSdk: Sdk): Boolean {
        val homePath = elixirSdk.homePath ?: return true
        // Check if Elixir SDK has proper ebin paths in lib directory
        if (!SdkEbinPaths.hasEbinPath(homePath)) return true
        // Check if SDK has proper classpath roots configured
        return elixirSdk.rootProvider.getFiles(com.intellij.openapi.roots.OrderRootType.CLASSES).isEmpty()
    }

    private fun hasErlangSdkIssues(erlangSdk: Sdk): Boolean {
        val homePath = erlangSdk.homePath ?: return true
        // Check if Erlang SDK has proper ebin paths
        if (!SdkEbinPaths.hasEbinPath(homePath)) return true
        // Validate that this is a proper Erlang SDK type
        return !org.elixir_lang.sdk.erlang_dependent.Type.staticIsValidDependency(erlangSdk)
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private fun createAddSdkAction(): AnAction =
        object : AnAction("Configure Elixir SDKs...", "", AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) { SdkSettingsOpener.getInstance().open(e) }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun update(e: AnActionEvent) {
                val isConfigured = findModuleLevelElixirSdk() != null
                val targetName = SdkSettingsOpener.getInstance().targetName()
                e.presentation.text = if (isConfigured) "Configure Elixir SDKs..." else "Add Elixir SDK..."
                e.presentation.description = if (isConfigured)
                    "Configure Elixir SDKs in $targetName"
                else
                    "Open $targetName to add an Elixir SDK"
            }
        }

    internal fun buildSdkVersionTableHtml(table: SdkVersionTable): String {
        val okColor = ColorUtil.toHtmlColor(JBColor(0x388A34, 0x499C54))
        val warnColor = ColorUtil.toHtmlColor(JBColor(0xBB8522, 0xC89B3C))
        val errorColor = ColorUtil.toHtmlColor(ERROR_COLOR)
        val quietColor = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
        // Swing gives an empty cell no width, so the gap between columns is spaces - few, as the balloon's width is
        // fixed and a wider table scrolls.
        val gap = "<td nowrap>&nbsp;&nbsp;</td>"
        val toolManagerName = StringUtil.escapeXmlEntities(table.toolManagerName)
        val moduleName = StringUtil.escapeXmlEntities(table.moduleName)
        return buildString {
            append("<table cellspacing=\"0\" cellpadding=\"1\">")
            append("<tr><td nowrap></td>$gap")
            append("<td nowrap><font color=\"$quietColor\">Configured SDK</font></td>$gap")
            append("<td nowrap><font color=\"$quietColor\">$toolManagerName</font></td></tr>")
            for (row in table.rows) {
                val configured = StringUtil.escapeXmlEntities(row.configuredVersion ?: "-")
                val toolManager = StringUtil.escapeXmlEntities(row.toolManagerVersion ?: "-")
                val mark = if (row.isMismatch) "<font color=\"$warnColor\">&#x26A0;</font>" else "<font color=\"$okColor\">&#x2713;</font>"
                // A single-module notification omits the issue text, so this cell must say it is not installed.
                val toolManagerCell = when {
                    // Swing drops a trailing <br>, so the blank line that separates the next row needs content.
                    !row.isInstalled ->
                        "<font color=\"$errorColor\"><b>$toolManager</b><br><font size=\"-1\">not installed</font></font><br>&nbsp;"
                    row.isMismatch -> "<b>$toolManager</b>"
                    else -> toolManager
                }
                append("<tr valign=\"top\"><td nowrap>$mark&nbsp;${StringUtil.escapeXmlEntities(row.label)}</td>$gap")
                // The one cell left to wrap: an SDK name can outgrow the balloon, which has a fixed width.
                append("<td>$configured</td>$gap<td nowrap>$toolManagerCell</td></tr>")
            }
            append("</table>")
            if (table.rows.any { !it.isInstalled }) {
                append(
                    "<p style=\"margin-top: 6px\"><font color=\"$errorColor\">You need to run " +
                        "<code><b>$toolManagerName install</b></code><br>" +
                        "in the <code>$moduleName</code> module directory.</font></p>"
                )
            }
        }
    }

}
