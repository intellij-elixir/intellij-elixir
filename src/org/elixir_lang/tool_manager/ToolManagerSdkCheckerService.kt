package org.elixir_lang.tool_manager

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.elixir_lang.util.ElixirCoroutineService
import org.elixir_lang.util.awaitJpsProjectLoaded
import com.intellij.util.messages.Topic
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

private val LOG = logger<ToolManagerSdkCheckerService>()

/**
 * Project service (registered in the base `plugin.xml`, so available in every IDE including
 * small IDEs like RubyMine) that owns the tool-manager scan lifecycle.
 *
 * Responsibilities:
 * - Waits for the JPS project model to be fully loaded before the first scan.
 * - Triggers a (debounced) scan when [ToolManagerSettings] change or module roots change.
 * - Runs [ToolManagerSdkChecker.resolveVersions] on a background IO thread.
 * - Publishes raw scan results on [SCAN_TOPIC] for downstream consumers (e.g. [ToolManagerSdkAnalyser]).
 * - Exposes [configureSdks] for action buttons created by UI consumers.
 *
 * This service has **no knowledge of the UI** - it only produces scan results.
 */
@Suppress("LightServiceMigrationCode")
internal class ToolManagerSdkCheckerService(private val project: Project) : Disposable {

    private val scope = project.service<ElixirCoroutineService>().supervisedChildScope(javaClass.simpleName)

    internal val checker = ToolManagerSdkChecker(
        project = project,
        toolManagers = BuiltInToolManagers.getInstance().all,
        settings = ToolManagerSettings.getInstance(project),
    )

    // replay = 1 so a request emitted before the collector below subscribes is still delivered:
    // MutableSharedFlow discards tryEmit when it has no subscribers.
    private val scanRequests = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Disposable container for the currently active [ToolManagerRefreshTrigger] installations.
     * Replaced (disposed + recreated) after every scan cycle (IO-dispatcher scan collector) and
     * immediately when settings change (message-bus listener thread).
     *
     * Because it is touched from more than one thread, the dispose+recreate+reassign swap is
     * confined to [triggerLock] (see [swapTriggerLifetime]); the lock guards only the cheap swap,
     * never the trigger installation I/O.  [installRefreshTriggers] registers onto the lifetime
     * reference returned by the swap rather than re-reading the field, so a concurrent reset cannot
     * leave it registering onto an already-replaced container.
     */
    private val triggerLock = Any()

    @Volatile
    private var triggerLifetime: Disposable = Disposer.newDisposable("ToolManagerSdkCheckerService.triggers")

    init {
        // Dispose trigger lifetime when the service itself is disposed.
        Disposer.register(this, triggerLifetime)

        val connection = project.messageBus.connect(this)

        // Re-scan when tool manager settings are toggled.  Also immediately dispose any active
        // refresh triggers so we stop watching files for managers that were just disabled.
        connection.subscribe(
            ToolManagerSettings.SETTINGS_CHANGED_TOPIC,
            ToolManagerSettingsListener {
                LOG.debug("Tool manager settings changed, disposing triggers and scheduling scan for '${project.name}'")
                disposeAndResetTriggers()
                scanRequests.tryEmit(Unit)
            }
        )

        // Re-scan when module roots change - new modules may introduce new content roots.
        connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: com.intellij.openapi.roots.ModuleRootEvent) {
                scanRequests.tryEmit(Unit)
            }
        })

        // Wait for JPS project model sync before the initial scan so that module content roots and SDK
        // assignments reflect the on-disk state rather than the workspace-model cache.
        // See [awaitJpsProjectLoaded] for why this uses the deprecated/internal API and the migration tickets.
        scope.launch {
            awaitJpsProjectLoaded(project)
            LOG.debug("JPS model loaded, emitting initial scan request for '${project.name}'")
            scanRequests.tryEmit(Unit)
        }

        // Scan collector - debounced to avoid redundant work during bulk rootsChanged events.
        @OptIn(FlowPreview::class)
        scope.launch {
            scanRequests
                .debounce(500.milliseconds)
                .collect {
                    LOG.debug("Running tool manager scan for '${project.name}'")
                    val contentRoots: List<Path> = readAction { checker.collectContentRoots() }
                    if (contentRoots.isEmpty()) {
                        LOG.trace("No Elixir content roots found, skipping scan")
                        return@collect
                    }
                    val results = withContext(Dispatchers.IO) {
                        checker.resolveVersions(contentRoots)
                    }
                    LOG.debug("Scan complete for '${project.name}', publishing ${results.size} result(s)")
                    project.messageBus.syncPublisher(SCAN_TOPIC).scanCompleted(results)

                    // Reinstall refresh triggers after each scan so dynamically discovered
                    // file lists (e.g. from `mise config ls`) stay up to date.
                    withContext(Dispatchers.IO) {
                        installRefreshTriggers(contentRoots, results)
                    }
                }
        }
    }

    /**
     * Installs [ToolManagerRefreshTrigger]s for all currently enabled tool managers.
     *
     * Disposes any previously installed triggers first.  Must be called on a background IO thread
     * (trigger implementations may run CLI commands to discover watched files).
     */
    private fun installRefreshTriggers(contentRoots: List<Path>, results: Map<Path, ToolManagerResult?>) {
        // Swap is atomic and returns the fresh lifetime; register onto that reference (not the
        // field) so a concurrent settings-change reset cannot redirect our registrations.
        val newLifetime = swapTriggerLifetime()

        checker.enabledToolManagers
            .forEach { manager ->
                val trigger = manager.createRefreshTrigger() ?: return@forEach
                LOG.debug("Installing refresh trigger for '${manager.name}' in '${project.name}'")
                val triggerDisposable = trigger.install(project, contentRoots, results) {
                    LOG.debug("Refresh trigger fired for '${manager.name}', requesting scan of '${project.name}'")
                    requestScan()
                }
                // tryRegister returns false (instead of throwing) if the lifetime was disposed by a
                // concurrent settings change; in that case drop the just-installed trigger.
                if (!Disposer.tryRegister(newLifetime, triggerDisposable)) {
                    LOG.debug("Trigger lifetime disposed during install for '${manager.name}', disposing trigger")
                    Disposer.dispose(triggerDisposable)
                }
            }
    }

    /** Disposes the current trigger lifetime and installs a fresh empty one, used by callers that
     *  only need to stop watching (e.g. the settings-changed listener). */
    private fun disposeAndResetTriggers() {
        swapTriggerLifetime()
    }

    /**
     * Atomically disposes the current trigger lifetime, creates a fresh empty one registered as a
     * child of this service, stores it in [triggerLifetime], and returns it.
     *
     * The lock spans only this cheap swap (never trigger-install I/O), so the EDT settings listener
     * is never blocked on background work.
     */
    private fun swapTriggerLifetime(): Disposable = synchronized(triggerLock) {
        Disposer.dispose(triggerLifetime)
        val newLifetime = Disposer.newDisposable("ToolManagerSdkCheckerService.triggers")
        Disposer.register(this, newLifetime)
        triggerLifetime = newLifetime
        newLifetime
    }

    /**
     * Requests an immediate (debounced) scan.  Safe to call from any thread.
     * Exposed for [ToolManagerRefreshTrigger] implementations to call back into the service.
     */
    fun requestScan() {
        scanRequests.tryEmit(Unit)
    }

    /**
     * Configures module SDKs from the given [assignments].
     *
     * Delegates to [ToolManagerSdkChecker.configureSdks].  Called from notification action buttons
     * created by UI consumers (e.g. [org.elixir_lang.status_bar_widget.ElixirEditorBasedSdkWidget]).
     *
     * Must be called from a blocking context on the EDT (e.g. `AnAction.actionPerformed`).
     */
    fun configureSdks(assignments: Map<String, ToolManagerVersions>) =
        checker.configureSdks(assignments)

    override fun dispose() {
        scope.cancel()
        // triggerLifetime is registered as a child of `this`, so Disposer handles it.
    }

    companion object {
        @JvmField
        val SCAN_TOPIC: Topic<ToolManagerScanListener> = Topic.create(
            "ToolManagerSdkCheckerService.scanCompleted",
            ToolManagerScanListener::class.java,
        )

        fun getInstance(project: Project): ToolManagerSdkCheckerService =
            project.getService(ToolManagerSdkCheckerService::class.java)
                ?: error("ToolManagerSdkCheckerService not registered")
    }
}
