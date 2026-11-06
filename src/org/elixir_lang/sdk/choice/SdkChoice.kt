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
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.util.Disposer
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.provider.getEelDescriptor
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
import org.elixir_lang.tool_manager.BuiltInToolManagers
import org.elixir_lang.tool_manager.PinsModel
import org.elixir_lang.tool_manager.ToolManagerPins
import org.elixir_lang.tool_manager.ToolManagerVersions
import org.elixir_lang.tool_manager.toolManagerFolder
import org.elixir_lang.util.ElixirAppCoroutineService
import java.awt.Component
import java.nio.file.Path
import javax.swing.JComponent
import kotlin.time.Duration

/** One table Elixir SDK the chooser offers, with the Erlang SDK it is paired with, or null when it has none. */
data class SdkChoiceEntry(val sdk: Sdk, val erlang: Erlang?) {
    data class Erlang(val name: String, val otpVersion: String?)
}

/**
 * What the chooser shows and has picked: every Elixir SDK in the table that runs in [target]'s environment, by name,
 * paired as in [model], with the SDK named [firstName] first. Nothing is picked until the user picks. [folders] are
 * where the modules are, or go, which [toolManagers] are asked about.
 */
@Suppress("UnstableApiUsage") // EelDescriptor is the platform's only name for an environment.
class SdkChoiceState @RequiresEdt constructor(
    val project: Project,
    private var target: EelDescriptor?,
    private val firstName: String?,
    private var folders: List<Path>,
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
    var offer: ToolManagerCheck.Offer? = null
        private set

    /** The Elixir SDKs taking an offer found, with the pins they were found for. */
    private class Taken(val pins: ToolManagerVersions, val sdks: List<Sdk>)

    /** What taking the offer found, kept while each answer since offers the same pins. */
    private var taken: Taken? = null

    /** The Elixir SDKs taking the offer found. */
    val offered: List<Sdk> get() = taken?.sdks.orEmpty()

    init {
        reload(null)
    }

    fun select(entry: SdkChoiceEntry?) {
        selected = entry?.takeIf(listModel::contains)
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
        reload(sdk)
        return selected
    }

    /**
     * Makes [offer]'s pins into an Elixir SDK as the tool manager does, with [parent] over its Erlang step, and picks it.
     * When the install has several Elixir SDKs and no Erlang pin to tell them apart, or the step was cancelled, nothing
     * is picked. Answers the pick.
     */
    @RequiresEdt
    fun take(parent: JComponent): SdkChoiceEntry? {
        val offer = offer ?: return selected
        val model = runWithModalProgressBlocking(ModalTaskOwner.component(parent), "Configuring Elixir SDK from ${offer.manager.displayName}") {
            ToolManagerPins.model(offer.elixir.installPath, listOf(offer.versions), project, parent)
        }
        taken = Taken(
            offer.versions,
            when (model) {
                is PinsModel.Found -> listOf(model.sdk)
                is PinsModel.Several -> model.sdks
                PinsModel.None -> emptyList()
            },
        )
        reload((model as? PinsModel.Found)?.sdk)
        return selected
    }

    /** Lists the table's Elixir SDKs again, keeping the pick while the table still has that SDK, renamed or not. */
    @RequiresEdt
    fun reload() = reload(selected?.sdk)

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
                val answered = answer as? ToolManagerCheck.Offer
                val samePins = answered != null && taken?.pins?.samePins(answered.versions) == true
                if (!samePins) taken = null
                offer = answered
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
        SdkEnvironment.syncTargetSdks(model, target)
        listModel.replaceAll(load())
        select(entries.find { it.sdk === sdk })
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
    /** Shows the [SdkChoiceState] asked for [modules] and answers with the entry picked, or null on Cancel. */
    internal fun interface Dialog {
        fun show(parent: Component?, modules: List<Module>, state: SdkChoiceState): SdkChoiceEntry?
    }

    /** Asks for [modules]' Elixir SDK and writes the pick to each; returns the table's SDK picked, or null on Cancel. */
    @RequiresEdt
    @Suppress("UnstableApiUsage") // EelDescriptor is the platform's only name for an environment.
    fun ask(project: Project, modules: List<Module>, parent: Component? = null): Sdk? {
        // On IntelliJ IDEA the SDK the first module's facet names, left by a small IDE, goes first.
        val firstName = modules.firstOrNull()?.takeIf { ElixirSdkAssignment.target(it) != ElixirSdkAssignment.Target.FACET }
            ?.let { Facet.sdk(it) }?.name
        val folders = modules.mapNotNull { it.toolManagerFolder }
        val state = SdkChoiceState(project, project.getEelDescriptor(), firstName, folders)
        try {
            val pick = dialog.show(parent, modules, state)?.sdk ?: return null
            WriteAction.run<Throwable> { modules.forEach { ElixirSdkAssignment.assign(it, pick) } }
            return pick
        } finally {
            Disposer.dispose(state)
        }
    }

    companion object {
        fun getInstance(): SdkChoice = ApplicationManager.getApplication().service()
    }
}
