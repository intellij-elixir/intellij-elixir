package org.elixir_lang.sdk.choice

import com.intellij.ide.util.projectWizard.ModuleWizardStep
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.toNioPathOrNull
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.elixir_lang.sdk.SdkDetectionContext
import org.elixir_lang.sdk.SdkEnvironment
import java.util.function.Consumer
import java.util.function.Supplier
import javax.swing.Icon
import javax.swing.JComponent

/**
 * A wizard step that asks with the chooser for the Elixir SDK of the modules [context]'s wizard makes in its
 * [wizardFolder], given the folder [imported] says its builder imports. Next is refused until the pick is paired with an
 * Erlang SDK, then hands [onNext] the pick, null included. The step writes nothing: the modules don't exist yet, so
 * whoever [onNext] hands the pick to writes it once they do.
 */
class SdkChoiceStep @RequiresEdt constructor(
    private val context: WizardContext,
    private val onNext: Consumer<SdkChoicePick?>,
    private val imported: Supplier<String?>,
) : ModuleWizardStep() {
    private val disposable = Disposer.newDisposable(context.disposable, javaClass.simpleName)

    internal val panel: SdkChoicePanel = wizardChooserPanel(context, disposable, directory()) {}

    private fun directory(): String? = wizardFolder(context, imported.get())

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel.preferredFocusedComponent
    override fun getIcon(): Icon? = context.stepIcon

    /**
     * An earlier step may have moved the wizard's directory, and Project Structure's Import Module hands the folder chosen
     * to the builder only once the steps are built: Add...'s home chooser opens there, and the list follows.
     */
    override fun updateStep() = panel.follow(directory())

    override fun validate(): Boolean {
        pickRefusal(panel.state.pick?.entry?.sdk)?.let { throw ConfigurationException(it) }
        return true
    }

    override fun updateDataModel() = onNext.accept(panel.state.pick)

    override fun disposeUIResources() {
        Disposer.dispose(disposable)
        super.disposeUIResources()
    }
}

/**
 * The folder [context]'s wizard works in: [imported], the folder its builder imports, when there is one, as Project
 * Structure's Import Module hands it only to the builder; else the wizard's project file directory.
 */
fun wizardFolder(context: WizardContext, imported: String?): String? =
    imported?.takeUnless(String::isBlank) ?: context.projectFileDirectory.takeUnless(String::isBlank)

/**
 * The chooser's panel in [context]'s wizard, over the table's Elixir SDKs that run where [directory] is, telling [onPick]
 * each pick. It goes with [parentDisposable], and so does the [SdkDetectionContext] [follow] publishes.
 */
@RequiresEdt
internal fun wizardChooserPanel(
    context: WizardContext,
    parentDisposable: Disposable,
    directory: String?,
    onPick: (SdkChoicePick?) -> Unit,
): SdkChoicePanel {
    val project = context.project ?: ProjectManager.getInstance().defaultProject
    val state = SdkChoiceState(project, targetOf(directory), null, foldersOf(directory), takesOptIns(context))
    Disposer.register(parentDisposable, state)
    Disposer.register(parentDisposable) { SdkDetectionContext.clear() }
    return SdkChoicePanel(state, parentDisposable, onPick)
}

/** Publishes [directory], where Add...'s home chooser opens, and lists the Elixir SDKs that run there. */
@RequiresEdt
internal fun SdkChoicePanel.follow(directory: String?) {
    SdkDetectionContext.set(directory)
    retarget(targetOf(directory), foldersOf(directory))
}

private fun targetOf(directory: String?) = directory?.let { SdkEnvironment.eelDescriptor(it) }

private fun foldersOf(directory: String?) = listOfNotNull(directory?.toNioPathOrNull())
