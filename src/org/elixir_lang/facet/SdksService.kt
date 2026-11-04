package org.elixir_lang.facet

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.roots.RootProvider
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.jetbrains.annotations.TestOnly

@Service
class SdksService : Disposable {
    // Written from root-set-changed listeners, which run inside whatever write action committed an SDK, and read by
    // getModel() on the EDT: without this a page can be handed a cached model whose commit listeners are disposed.
    @Volatile
    private var model: ProjectSdksModel? = null

    @Volatile
    private var modelCommitListeners: Disposable? = null

    @Volatile
    private var applying: ProjectSdksModel? = null

    @Volatile
    private var tableChangedWhileApplying = false

    /** The SDK [register] is adding to the table for this service's own model, whose `jdkAdded` must keep the model. */
    @Volatile
    private var registering: Sdk? = null

    init {
        // Invalidate the cached model whenever the JDK table, or an SDK in it, changes outside of it - e.g. the
        // tool-manager "Configure from mise" action registers SDKs directly via SdkRegistrar. The
        // next getModel() then rebuilds from the current table. Settings views that are already
        // open keep their own model reference (via `by lazy`), so this does not disturb them; it
        // only ensures the *next* dialog reflects the change (previously it took an IDE restart).
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(ProjectJdkTable.JDK_TABLE_TOPIC, object : ProjectJdkTable.Listener {
                override fun jdkAdded(jdk: Sdk) = tableChanged(jdk, Change.ADDED)
                override fun jdkRemoved(jdk: Sdk) = tableChanged(jdk, Change.REMOVED)
                override fun jdkNameChanged(jdk: Sdk, previousName: String) = tableChanged(jdk, Change.RENAMED)
            })
    }

    /** Drops the cached model (without disposing it, so already-open views keep working). */
    private fun invalidate() {
        if (applying != null && applying === model) return
        model = null
        modelCommitListeners?.let(Disposer::dispose)
        modelCommitListeners = null
    }

    /**
     * A commit to an SDK fires no JDK table event, only `rootSetChanged` on the SDK, and applying a stale copy of it
     * reverts the commit.
     */
    private fun invalidateOnCommit(model: ProjectSdksModel) {
        val listeners = Disposer.newDisposable(this, "SdksService model commits")
        val onCommit = RootProvider.RootSetChangedListener { invalidate() }
        for (sdk in model.projectSdks.keys) {
            sdk.rootProvider.addRootSetChangedListener(onCommit, listeners)
        }
        // An SDK created on the SDKs page goes through `ProjectSdksModel.doAdd`, which publishes it while it is still
        // absent from the table, so no `jdkAdded` follows and the loop above cannot have seen it.
        val added = object : SdkModel.Listener {
            override fun sdkAdded(sdk: Sdk) {
                // `doAdd` keys the map by the original and publishes the editable clone, so the event carries the
                // copy while the commit that has to drop the cache fires on the original's root provider. `findSdk`
                // maps the copy back to the key the loop above would have registered.
                val original = model.findSdk(sdk) ?: sdk
                original.rootProvider.addRootSetChangedListener(onCommit, listeners)
            }

            override fun beforeSdkRemove(sdk: Sdk) {}
            override fun sdkChanged(sdk: Sdk, previousName: String) {}
            override fun sdkHomeSelected(sdk: Sdk, newSdkHome: String) {}
        }
        model.addListener(added)
        Disposer.register(listeners) { model.removeListener(added) }
        modelCommitListeners = listeners
    }

    private enum class Change { ADDED, REMOVED, RENAMED }

    /**
     * A model being applied adds and removes its own SDKs in the table; it holds an added one as a key and has already
     * dropped a removed one. Another writer's add or remove has to reach the next model, and [invalidate] is suppressed
     * while applying, so it is remembered until the apply finishes.
     */
    private fun tableChanged(jdk: Sdk, change: Change) {
        if (change == Change.ADDED && jdk === registering) return
        val applying = this.applying
        if (applying != null && applying === model) {
            val copy = applying.projectSdks[jdk]
            val ownChange = when (change) {
                // `ProjectSdksModel.doApply` adds the key and copies the editable clone's state in on the next line,
                // so an SDK it has just added still carries the name it was cloned with.
                Change.ADDED -> copy != null
                Change.REMOVED -> copy == null
                Change.RENAMED -> copy != null && copy.name == jdk.name
            }
            if (!ownChange) tableChangedWhileApplying = true
            return
        }
        invalidate()
    }

    override fun dispose() {}

    companion object {
        fun getInstance(): SdksService? = ApplicationManager.getApplication().getService(SdksService::class.java)
    }

    /**
     * Discards the cached model so the next [getModel] rebuilds it from the current JDK table.
     * The model is an application-level singleton that lives for the whole test JVM; teardown that
     * mutates the JDK table directly (bypassing the model) would otherwise leak SDK clones into the
     * next test. Not used in production - the SDK settings UI keeps the model in sync via listeners.
     */
    @TestOnly
    fun resetForTests() {
        model?.disposeUIResources()
        invalidate()
    }

    /**
     * Applying commits, adds and removes SDKs in the table, which must not drop the model being applied: pages opened
     * later in the same dialog would get a second model, and applying both reverts one's edits with the other's copies.
     *
     * Validating the model reads the SDK table. A modal Settings dialog's Apply holds write-intent, a Settings tab's
     * Save Changes holds no lock, so the lock is taken here.
     */
    @Suppress("UnstableApiUsage")
    fun apply(model: ProjectSdksModel) = WriteIntentReadAction.run {
        applying = model
        try {
            model.apply()
        } finally {
            applying = null
            if (model === this.model) {
                if (tableChangedWhileApplying) {
                    invalidate()
                } else {
                    modelCommitListeners?.let(Disposer::dispose)
                    invalidateOnCommit(model)
                }
            }
            tableChangedWhileApplying = false
        }
    }

    /**
     * Adds [sdk], set up, to the table and then to [sdkModel], keyed by [sdk] itself so applying the model updates it
     * rather than adding a second. When [sdkModel] is this service's cached model, the page holding it keeps it.
     */
    @RequiresEdt
    internal fun register(sdk: Sdk, sdkModel: SdkModel?) {
        registering = sdk.takeIf { sdkModel != null && sdkModel === model }
        try {
            SdkConfigurationUtil.addSdk(sdk)
        } finally {
            registering = null
        }
        sdkModel?.addSdk(sdk)
    }

    /** Drops [model] when its pages close with changes not applied, so Cancel leaves nothing for the next Apply. */
    fun discardUnapplied(model: ProjectSdksModel) {
        if (model === this.model && model.isModified) invalidate()
    }

    fun <T> projectJdkImplList(clazz: Class<T>) =
        getModel().sdks.filter { clazz.isInstance(it.sdkType) }.map { it as ProjectJdkImpl }

    fun getModel(): ProjectSdksModel {
        val model = this.model ?: initModel().also(::invalidateOnCommit)
        this.model = model

        return model
    }

    private fun initModel(): ProjectSdksModel {
        // ProjectSdksModel.reset requires a NON-NULL project to load SDKs from the JDK table -
        // reset(null) loads nothing. The JDK table is application-wide, so any open project triggers
        // a full load. The model is initialised once and cached; the combo/list SdkModel listeners
        // keep it in sync with edits, so it must NOT be reset again per view (re-cloning would
        // invalidate SDK references other open views hold and break removal - see ProjectSdksModel).
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        var model: ProjectSdksModel?

        do {
            model = try {
                ProjectSdksModel().apply { reset(project) }
            } catch (_: AssertionError) {
                null
            }
        } while (model == null)

        return model
    }
}
