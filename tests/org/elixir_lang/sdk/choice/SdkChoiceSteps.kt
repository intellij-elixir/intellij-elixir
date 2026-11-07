package org.elixir_lang.sdk.choice

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.vfs.LocalFileSystem
import org.elixir_lang.mix.project._import.Builder
import org.elixir_lang.mix.project._import.Provider
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install
import org.elixir_lang.tool_manager.ToolManagerSettings
import org.elixir_lang.tool_manager.ToolManagerSettingsListener

/** The Mix import's first step in [context], as `Provider` builds it over [builder]. */
internal fun mixImportStep(context: WizardContext, builder: Builder): SdkChoiceStep =
    object : Provider() {
        override fun doGetBuilder() = builder
    }.createSteps(context).first() as SdkChoiceStep

/** Picks the Elixir SDK named [name] in the step's chooser. */
internal fun SdkChoiceStep.pick(name: String) = panel.pick(panel.state.entries.single { it.sdk.name == name })

/** Shows the step, picks the Elixir SDK named [name], and presses Next. */
internal fun SdkChoiceStep.pickAndNext(name: String) {
    updateStep()
    pick(name)
    validate()
    updateDataModel()
}

/** Shows the step, takes the tool manager's offer with its first box ticked, and presses Next. */
internal fun SdkChoiceStep.takeTheOfferInStep() {
    updateStep()
    component.waitFor(OFFER).doClick()
    component.waitFor(IN_STEP).doClick()
    validate()
    updateDataModel()
}

/** What [record] reads each time [project]'s tool manager settings publish a change, until [disposable] goes. */
internal fun <T> settingsChanges(project: Project, disposable: Disposable, record: () -> T): List<T> =
    mutableListOf<T>().also { heard ->
        project.messageBus.connect(disposable)
            .subscribe(ToolManagerSettings.SETTINGS_CHANGED_TOPIC, ToolManagerSettingsListener { heard += record() })
    }

/** An Erlang SDK named [name] in the table at a new home the VFS knows, removed with [disposable]. */
internal fun erlangForNext(name: String, disposable: Disposable): Sdk {
    // Next refuses an Erlang home the VFS doesn't know, and a heavy test's new directories are unknown until refreshed.
    val home = install("27", "27.3.4").also { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }
    return SdkFixtures.register(SdkFixtures.erlangSdk(name, home), disposable)
}

/** An Elixir SDK named [elixirName] in the table, paired with an [erlangForNext] named [erlangName], both removed with [disposable]. */
internal fun pairedForNext(elixirName: String, erlangName: String, disposable: Disposable): Sdk {
    val elixir = SdkFixtures.register(SdkFixtures.elixirSdk(elixirName, elixirInstall("1.18.4", "27")), disposable)
    return SdkFixtures.commitPairing(elixir, erlangForNext(erlangName, disposable))
}
