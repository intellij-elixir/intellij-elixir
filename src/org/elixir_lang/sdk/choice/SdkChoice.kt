package org.elixir_lang.sdk.choice

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
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
import com.intellij.ui.CollectionListModel
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.elixir_lang.Facet
import org.elixir_lang.sdk.SdkEnvironment
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.sdk.elixir.ElixirSdkAssignment
import org.elixir_lang.sdk.elixir.Type
import org.elixir_lang.sdk.erlang_dependent.ErlangSdkResolver
import java.awt.Component
import javax.swing.JComponent

/** One table Elixir SDK the chooser offers, with the Erlang SDK it is paired with, or null when it has none. */
data class SdkChoiceEntry(val sdk: Sdk, val erlang: Erlang?) {
    data class Erlang(val name: String, val otpVersion: String?)
}

/**
 * What the chooser shows and has picked: every Elixir SDK in the table that runs in [target]'s environment, by name,
 * paired as in [model], with the SDK named [firstName] first. Nothing is picked until the user picks.
 */
@Suppress("UnstableApiUsage") // EelDescriptor is the platform's only name for an environment.
class SdkChoiceState @RequiresEdt constructor(
    val project: Project,
    private val target: EelDescriptor?,
    private val firstName: String?,
) : Disposable {
    private val model = ProjectSdksModel()

    val listModel = CollectionListModel<SdkChoiceEntry>()
    val entries: List<SdkChoiceEntry> get() = listModel.items

    var selected: SdkChoiceEntry? = null
        private set

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

    /** Lists the table's Elixir SDKs again, keeping the pick while the table still has that SDK, renamed or not. */
    @RequiresEdt
    fun reload() = reload(selected?.sdk)

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
        val state = SdkChoiceState(project, project.getEelDescriptor(), firstName)
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
