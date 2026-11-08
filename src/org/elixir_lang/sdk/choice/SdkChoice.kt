package org.elixir_lang.sdk.choice

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.UI
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.util.Disposer
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.eel.provider.getResolvedEelMachine
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.CollectionListModel
import com.intellij.util.concurrency.annotations.RequiresEdt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.elixir_lang.Facet
import org.elixir_lang.sdk.SdkEnvironment
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.sdk.elixir.ElixirSdkAssignment
import org.elixir_lang.sdk.elixir.Type
import org.elixir_lang.sdk.erlang_dependent.ErlangSdkResolver
import org.elixir_lang.sdk.erlang_dependent.elixirAdditionalData
import org.elixir_lang.tool_manager.BuiltInToolManagers
import org.elixir_lang.tool_manager.ElixirToolManager
import org.elixir_lang.tool_manager.PinsModel
import org.elixir_lang.tool_manager.ToolManagerOptIns
import org.elixir_lang.tool_manager.ToolManagerPins
import org.elixir_lang.tool_manager.ToolManagerSettings
import org.elixir_lang.tool_manager.ToolManagerVersions
import org.elixir_lang.tool_manager.toolManagerFolder
import org.elixir_lang.util.ElixirAppCoroutineService
import java.awt.Component
import java.nio.file.Path
import javax.swing.JComponent
import kotlin.time.Duration

/** The entry picked, with the tool manager opt-ins its boxes hand over, or null when they hand over none. */
data class SdkChoicePick(val entry: SdkChoiceEntry, val optIns: ToolManagerOptIns?)

/** One table Elixir SDK the chooser offers, with the Erlang SDK it is paired with, or null when it has none. */
data class SdkChoiceEntry(val sdk: Sdk, val erlang: Erlang?) {
    data class Erlang(val name: String, val otpVersion: String?)
}

/**
 * Writes [sdk] to each of [modules] through [ElixirSdkAssignment], then the tool manager [optIns] picked with it, if any,
 * through [ToolManagerSettings.adopt].
 */
@RequiresEdt
internal fun writePick(project: Project, modules: List<Module>, sdk: Sdk, optIns: ToolManagerOptIns?) {
    WriteAction.run<Throwable> { modules.forEach { ElixirSdkAssignment.assign(it, sdk) } }
    optIns?.let { ToolManagerSettings.adopt(project, it) }
}

/**
 * What the chooser shows and has picked: every Elixir SDK in the table that runs in [target]'s environment, by name,
 * paired as in [model], with the SDK named [firstName] first. Nothing is picked until the user picks. [folders] are
 * where the modules are, or go, which [toolManagers] are asked about. [hostCommits] when whoever shows the chooser
 * writes its pick at OK, so the opt-ins can be written with it.
 */
@Suppress("UnstableApiUsage") // EelDescriptor is the platform's only name for an environment.
class SdkChoiceState @RequiresEdt constructor(
    val project: Project,
    private var target: EelDescriptor?,
    private val firstName: String?,
    private var folders: List<Path>,
    val hostCommits: Boolean,
) : Disposable {
    private val model = ProjectSdksModel()
    private val toolManagers = BuiltInToolManagers.getInstance().all
    private val scope = service<ElixirAppCoroutineService>().supervisedChildScope(javaClass.simpleName)
    private var checking: Job? = null

    val listModel = CollectionListModel<SdkChoiceEntry>()
    val entries: List<SdkChoiceEntry> get() = listModel.items

    var selected: SdkChoiceEntry? = null
        private set

    /** The tool manager's pins the last check offered, or null when it offered none. */
    private var offer: ToolManagerCheck.Offer? = null

    private sealed interface Radio

    /** The first radio: the Elixir SDKs taking the offer found, the pins they were found for, and the SDK selected before. */
    private class Taken(private val pins: ToolManagerVersions, sdks: List<Sdk>, val before: Sdk?) : Radio {
        private val found = sdks.map { it to homes(it) }

        /** Whether [offer] still pins what these were found for. */
        fun pinnedBy(offer: ToolManagerCheck.Offer?): Boolean = offer != null && pins.samePins(offer.versions)

        /** Whether [sdk] is one of those found, on the homes it had then: on others the pins no longer describe it. */
        fun restsOn(sdk: Sdk?): Boolean = found.any { (it, homes) -> it === sdk && homes == homes(it) }

        private fun homes(sdk: Sdk) = sdk.homePath to sdk.elixirAdditionalData?.getErlangSdkHomePath()
    }

    /** The second radio: the pick is the list's selection. */
    private object Another : Radio

    /** The radio chosen, or null when neither is. */
    private var radio: Radio? = null

    val offerChosen: Boolean get() = radio is Taken
    val anotherChosen: Boolean get() = radio === Another

    private var toolManagerTicked = false
    private var environmentTicked = false

    private val settings get() = ToolManagerSettings.getInstance(project)

    /** Whether the boxes hand over opt-ins: there is an offer, its manager is off, and the host writes the pick at OK. */
    private val handsOverOptIns: Boolean
        get() = hostCommits && offer?.let { !settings.isEnabled(it.manager) } == true

    /** Whether the boxes can be ticked: they hand over opt-ins, and the first radio is chosen. */
    val optInsOpen: Boolean get() = handsOverOptIns && offerChosen

    /** Whether the environment's box can be ticked: the boxes can be, and the tool manager's is. */
    val environmentOpen: Boolean get() = optInsOpen && toolManagerBox

    /** Whether the tool manager's box shows ticked. */
    val toolManagerBox: Boolean get() = shown(optInsOpen, toolManagerTicked) { isEnabled(it) }

    /** Whether the environment's box shows ticked. */
    val environmentBox: Boolean get() = shown(environmentOpen, environmentTicked) { isEnvironmentEnabled(it) }

    /**
     * While the boxes hand over opt-ins, a box shows [ticked] only while it is [open]; otherwise it shows the offer's
     * manager's [setting].
     */
    private fun shown(open: Boolean, ticked: Boolean, setting: ToolManagerSettings.(ElixirToolManager) -> Boolean): Boolean =
        if (handsOverOptIns) open && ticked else offer?.let { settings.setting(it.manager) } == true

    /** The pick, with the opt-ins of the boxes when they can be ticked and the tool manager's is. */
    val pick: SdkChoicePick?
        get() = selected?.let { entry ->
            val optIns = offer?.takeIf { handsOverOptIns && toolManagerBox }?.let { ToolManagerOptIns(it.manager.name, environmentBox) }
            SdkChoicePick(entry, optIns)
        }

    init {
        reload(null)
    }

    /** Picks the row of [sdk], or none when it has no row. */
    private fun selectRowOf(sdk: Sdk?) {
        selected = entries.find { it.sdk === sdk }
    }

    /** The user picks [entry] in the list: the second radio, unless the first radio can rest on it. */
    fun choose(entry: SdkChoiceEntry?) {
        selected = entry?.takeIf(listModel::contains)
        if ((radio as? Taken)?.restsOn(selected?.sdk) != true) radio = Another
    }

    /** The user chooses the second radio: coming from the first, the SDK selected before the offer was taken is back. */
    fun chooseAnother() {
        (radio as? Taken)?.let { selectRowOf(it.before) }
        radio = Another
    }

    /** Puts the choice back: the row of [before] under the second radio, or with no such row neither radio. */
    private fun rollBack(before: Sdk?) {
        selectRowOf(before)
        radio = if (selected != null) Another else null
    }

    /** Ticks or unticks the boxes: [toolManager]'s, and [environment]'s. */
    fun tick(toolManager: Boolean, environment: Boolean) {
        toolManagerTicked = toolManager
        environmentTicked = environment
    }

    /**
     * Runs the SDK pages' create flow and picks the SDK it creates. That SDK goes into the table at once, so Cancel
     * keeps it. Answers the pick, or null when the flow was cancelled, which keeps the pick there was.
     */
    @RequiresEdt
    fun add(parent: JComponent): SdkChoiceEntry? {
        var created: Sdk? = null
        model.doAdd(parent, Type.instance) { created = it }
        val sdk = created?.let { model.findSdk(it) } ?: return null
        // Only the new SDK: applying the model would write its copies back over SDKs committed since its last reset.
        SdkConfigurationUtil.addSdk(sdk)
        saveSdkTable()
        reload(sdk)
        return selected
    }

    /**
     * Chooses the first radio, once: makes [offer]'s pins into an Elixir SDK as the tool manager does, with [parent] over
     * its Erlang step, and picks it. When the install has several Elixir SDKs and no Erlang pin to tell them apart, nothing
     * is picked yet; when the step was cancelled, the choice is put back.
     */
    @RequiresEdt
    fun take(parent: JComponent) {
        if (radio is Taken) return
        val offer = offer ?: return
        val before = selected?.sdk
        val model = runWithModalProgressBlocking(ModalTaskOwner.component(parent), "Configuring Elixir SDK from ${offer.manager.displayName}") {
            ToolManagerPins.model(offer.elixir.installPath, listOf(offer.versions), project, parent)
        }
        val sdks = when (model) {
            is PinsModel.Found -> listOf(model.sdk)
            is PinsModel.Several -> model.sdks
            PinsModel.None -> emptyList()
        }
        reload(sdks.singleOrNull())
        if (sdks.isEmpty()) rollBack(before) else radio = Taken(offer.versions, sdks, before)
    }

    /**
     * Lists the table's Elixir SDKs again, keeping the pick while the table still has that SDK, renamed or not. The first
     * radio stands while it can rest on the SDK picked, or with none picked on any listed.
     */
    @RequiresEdt
    fun reload() {
        val rested = selected?.sdk
        reload(rested)
        val taken = radio as? Taken ?: return
        val standing = if (rested != null) taken.restsOn(selected?.sdk) else entries.any { taken.restsOn(it.sdk) }
        if (!standing) rollBack(taken.before)
    }

    /**
     * Lists the Elixir SDKs of [target]'s environment, keeping the pick while it is one of them, with [folders] as where
     * the modules go. When the folders change, the old folders' offer goes, and this answers true: the tool managers are
     * to be asked again.
     */
    @RequiresEdt
    fun retarget(target: EelDescriptor?, folders: List<Path>): Boolean {
        if (target != this.target) {
            this.target = target
            reload()
        }
        if (folders == this.folders) return false
        this.folders = folders
        offer = null
        return true
    }

    /**
     * Asks every tool manager, enabled or not, about [folders] off the EDT once [after] has passed, and hands [onAnswer]
     * its answer on the EDT at [component]'s modality. A check still running is dropped, and so is one running when this
     * state goes.
     */
    @RequiresEdt
    fun check(component: Component, after: Duration = Duration.ZERO, onAnswer: (ToolManagerCheck?) -> Unit) {
        checking?.cancel()
        val folders = folders
        checking = scope.launch {
            delay(after)
            val answer = withContext(Dispatchers.IO) { ToolManagerCheck.of(toolManagers, folders) }
            val modality = withContext(Dispatchers.UI + ModalityState.any().asContextElement()) { ModalityState.stateForComponent(component) }
            withContext(Dispatchers.UI + modality.asContextElement()) {
                offer = answer as? ToolManagerCheck.Offer
                val taken = radio as? Taken
                if (taken != null && !taken.pinnedBy(offer)) rollBack(taken.before)
                onAnswer(answer)
            }
        }
    }

    /** Lists the table's Elixir SDKs again and picks the table's [sdk], when it is listed. */
    private fun reload(sdk: Sdk?) {
        // `reset` drops the copies it holds without disposing them, and each stays registered until the IDE exits.
        model.disposeUIResources()
        // A wizard's project is the default project, whose `reset` keeps only local SDKs. Add...'s Erlang step lists the
        // model's, so the target's go in too.
        model.reset(project)
        syncTargetSdks()
        val rows = load()
        // Before the rows go, so the list's emptying agrees with the pick and isn't taken for the user's.
        selected = null
        listModel.replaceAll(rows)
        selectRowOf(sdk)
    }

    /**
     * Adds the table's SDKs in [target]'s environment that `reset` left out, as the internal
     * `ProjectSdksModel.syncSdks(EelMachine)` would: `addSdk` copies each into [model] and fires `sdkAdded`, and `apply`
     * takes an SDK already in the table as an update, not an addition.
     */
    private fun syncTargetSdks() {
        val targetMachine = target?.getResolvedEelMachine() ?: return
        val projectSdks = model.projectSdks

        for (sdk in ProjectJdkTable.getInstance().allJdks) {
            if (projectSdks.containsKey(sdk) || projectSdks.containsValue(sdk)) continue

            if (SdkEnvironment.sdkDescriptor(sdk)?.getResolvedEelMachine() == targetMachine) {
                model.addSdk(sdk)
            }
        }
    }

    private fun load(): List<SdkChoiceEntry> {
        val visible = SdkEnvironment.visibleFor(target)
        val sdks = model.projectSdks.entries
            .filter { it.value.sdkType === Type.instance && visible(it.key) }
            .sortedWith(compareBy({ it.key.name != firstName }, { it.key.name }))
        return ReadAction.computeBlocking<List<SdkChoiceEntry>, Throwable> { sdks.map { (sdk, copy) -> entry(sdk, copy) } }
    }

    /** The table's [sdk], paired as its [copy] in [model] is. */
    private fun entry(sdk: Sdk, copy: Sdk): SdkChoiceEntry {
        val erlang = ErlangSdkResolver.getInstance().resolveErlangSdk(copy, model)
        return SdkChoiceEntry(sdk, erlang?.let { SdkChoiceEntry.Erlang(it.name, SdkVersionsStore.getInstance().otpVersion(it.homePath)) })
    }

    override fun dispose() {
        scope.cancel()
        model.disposeUIResources()
    }
}

/**
 * The chooser: picks an existing Elixir SDK for modules and writes it through [ElixirSdkAssignment]. It never pairs an
 * SDK with an Erlang SDK; that is the SDK pages'. Its dialog is the internal constructor's parameter, so a test builds
 * the chooser with its own. A light service's class must be final.
 */
@Service
class SdkChoice internal constructor(
    private val dialog: Dialog = Dialog { parent, modules, state -> SdkChoiceDialog(parent, modules, state).showAndPick() },
) {
    /** Shows the [SdkChoiceState] asked for [modules] and answers with the pick, or null on Cancel. */
    internal fun interface Dialog {
        fun show(parent: Component?, modules: List<Module>, state: SdkChoiceState): SdkChoicePick?
    }

    /**
     * Asks for [modules]' Elixir SDK and writes the pick to each, then the tool manager opt-ins picked with it; returns
     * the table's SDK picked, or null on Cancel.
     */
    @RequiresEdt
    @Suppress("UnstableApiUsage") // EelDescriptor is the platform's only name for an environment.
    fun ask(project: Project, modules: List<Module>, parent: Component? = null): Sdk? {
        // On IntelliJ IDEA the SDK the first module's facet names, left by a small IDE, goes first.
        val firstName = modules.firstOrNull()?.takeIf { ElixirSdkAssignment.target(it) != ElixirSdkAssignment.Target.FACET }
            ?.let { Facet.sdk(it) }?.name
        val folders = modules.mapNotNull { it.toolManagerFolder }
        val state = SdkChoiceState(project, project.getEelDescriptor(), firstName, folders, hostCommits = true)
        try {
            val pick = dialog.show(parent, modules, state) ?: return null
            writePick(project, modules, pick.entry.sdk, pick.optIns)
            return pick.entry.sdk
        } finally {
            Disposer.dispose(state)
        }
    }

    companion object {
        fun getInstance(): SdkChoice = ApplicationManager.getApplication().service()
    }
}
