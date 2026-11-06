package org.elixir_lang.tool_manager

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.ui.EditorNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.elixir_lang.util.ElixirCoroutineService
import com.intellij.util.messages.Topic
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

private val LOG = logger<ToolManagerSdkAnalyser>()

/**
 * Project service (registered in the base `plugin.xml`, so available in every IDE including
 * small IDEs like RubyMine) that analyses raw tool-manager scan results against the currently
 * configured module SDKs.
 *
 * Responsibilities:
 * - Subscribes to [ToolManagerSdkCheckerService.SCAN_TOPIC] and caches the latest scan results.
 * - Re-runs analysis whenever new scan results arrive **or** when module SDKs change
 *   ([ModuleRootListener], [ProjectJdkTable.JDK_TABLE_TOPIC]).
 * - Produces a [ToolManagerAnalysisResult] and publishes it on [ANALYSIS_TOPIC].
 *
 * This service has **no knowledge of the UI** - it only produces analysis results.
 * UI consumers (e.g. the status-bar widget) subscribe to [ANALYSIS_TOPIC] independently.
 */
@Suppress("LightServiceMigrationCode")
@OptIn(FlowPreview::class)
internal class ToolManagerSdkAnalyser(private val project: Project) : Disposable {

    private val scope = project.service<ElixirCoroutineService>().supervisedChildScope(javaClass.simpleName)

    private val checker = ToolManagerSdkChecker(
        project = project,
        toolManagers = BuiltInToolManagers.getInstance().all,
        settings = ToolManagerSettings.getInstance(project),
    )

    @Volatile
    private var latestScanResults: Map<Path, ToolManagerResult?> = emptyMap()

    @Volatile
    private var latestAnalysis: ToolManagerAnalysisResult? = null

    /**
     * The most recent completed analysis snapshot, or null before the first scan completes.
     * Safe to read from any thread (the result is immutable). Used by synchronous UI consumers
     * such as the setup-SDK editor banner ([org.elixir_lang.notification.setup_sdk.Provider]),
     * which reads it under the platform read lock while building the panel.
     */
    val latestAnalysisResult: ToolManagerAnalysisResult?
        get() = latestAnalysis

    // replay = 1 so a request emitted before the collector below subscribes is still delivered:
    // MutableSharedFlow discards tryEmit when it has no subscribers.
    private val analysisRequests = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        val connection = project.messageBus.connect(this)

        // Trigger analysis when the scan service produces new results.
        connection.subscribe(
            ToolManagerSdkCheckerService.SCAN_TOPIC,
            ToolManagerScanListener { results ->
                LOG.debug("Received scan results for '${project.name}', scheduling analysis")
                latestScanResults = results
                analysisRequests.tryEmit(Unit)
            }
        )

        // Re-analyse when module root changes alter SDK assignments.
        connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: com.intellij.openapi.roots.ModuleRootEvent) {
                analysisRequests.tryEmit(Unit)
            }
        })

        // Re-analyse when SDKs are added/removed/renamed - changes the comparison baseline.
        connection.subscribe(ProjectJdkTable.JDK_TABLE_TOPIC, object : ProjectJdkTable.Listener {
            override fun jdkAdded(jdk: Sdk) = analysisRequests.tryEmit(Unit).let {}
            override fun jdkRemoved(jdk: Sdk) = analysisRequests.tryEmit(Unit).let {}
            override fun jdkNameChanged(jdk: Sdk, previousName: String) = analysisRequests.tryEmit(Unit).let {}
        })

        // Analysis collector - debounced so rapid SDK/module changes produce one analysis pass.
        scope.launch {
            analysisRequests
                .debounce(200.milliseconds)
                .collect {
                    val scanResults = latestScanResults
                    if (scanResults.isEmpty()) {
                        LOG.trace("No scan results cached, skipping analysis for '${project.name}'")
                        return@collect
                    }
                    LOG.debug("Running tool manager analysis for '${project.name}'")

                    val moduleCheckData = readAction { checker.collectModuleCheckData() }
                    val canonicalPathByPath =
                        withContext(Dispatchers.IO) { checker.canonicalPaths(moduleCheckData, scanResults) }

                    val (tmIssues, sdkVersionTables) = checker.detectMismatchIssues(
                        moduleCheckData = moduleCheckData,
                        toolManagerResultsByRoot = scanResults,
                        canonicalPathByPath = canonicalPathByPath,
                    )
                    val tmAssignments = checker.buildAssignments(moduleCheckData, scanResults)
                    val toolManagerErrors = checker.collectErrors(scanResults)

                    val result = ToolManagerAnalysisResult(
                        tmIssues = tmIssues,
                        tmAssignments = tmAssignments,
                        sdkVersionTables = sdkVersionTables,
                        toolManagerErrors = toolManagerErrors,
                    )
                    latestAnalysis = result
                    LOG.debug(
                        "Analysis complete for '${project.name}': " +
                            "${tmIssues.size} issue(s), ${tmAssignments.size} assignment(s)"
                    )
                    project.messageBus.syncPublisher(ANALYSIS_TOPIC).analysisCompleted(result)

                    // Refresh editor banners so the setup-SDK notification (Provider) can offer a
                    // "Configure from <tool manager>" action once assignments become available.
                    // The banner is first built before this async scan completes, so it must be
                    // re-rendered here.
                    EditorNotifications.getInstance(project).updateAllNotifications()
                }
        }
    }

    /**
     * Subscribes [onAnalysisCompleted] to [ANALYSIS_TOPIC] and immediately replays the latest
     * completed analysis snapshot (if any) to avoid missing state when subscribers register late.
     */
    fun subscribeWithLatest(
        parentDisposable: Disposable,
        onAnalysisCompleted: (ToolManagerAnalysisResult) -> Unit,
    ) {
        project.messageBus.connect(parentDisposable).subscribe(
            ANALYSIS_TOPIC,
            ToolManagerAnalysisListener { result -> onAnalysisCompleted(result) }
        )
        latestAnalysis?.let(onAnalysisCompleted)
    }

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        @JvmField
        val ANALYSIS_TOPIC: Topic<ToolManagerAnalysisListener> = Topic.create(
            "ToolManagerSdkAnalyser.analysisCompleted",
            ToolManagerAnalysisListener::class.java,
        )

        fun getInstance(project: Project): ToolManagerSdkAnalyser =
            project.getService(ToolManagerSdkAnalyser::class.java)
                ?: error("ToolManagerSdkAnalyser not registered")

        fun getInstanceIfRegistered(project: Project): ToolManagerSdkAnalyser? =
            project.getService(ToolManagerSdkAnalyser::class.java)
    }
}
