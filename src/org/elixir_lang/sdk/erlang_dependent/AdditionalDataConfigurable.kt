package org.elixir_lang.sdk.erlang_dependent

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.UI
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.projectRoots.AdditionalDataConfigurable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.SdkModificator
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.elixir_lang.debug
import org.elixir_lang.sdk.SdkVersionsFiller
import org.elixir_lang.sdk.elixir.ElixirErlangClasspath.addNewCodePathsFromInternErlangSdk
import org.elixir_lang.sdk.elixir.ElixirErlangClasspath.removeCodePathsFromInternalErlangSdk
import org.elixir_lang.sdk.elixir.ElixirSdkMutation
import org.elixir_lang.sdk.elixir.ElixirSdkPathConfigurator
import org.elixir_lang.sdk.elixir.ElixirSdkValidation
import org.elixir_lang.sdk.elixir.ElixirSdkValidation.hasErlangClasspathInRoots
import org.elixir_lang.sdk.elixir.OtpMatch
import org.elixir_lang.util.ElixirAppCoroutineService
import org.elixir_lang.util.WriteActions
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ItemEvent
import javax.swing.*

class AdditionalDataConfigurable(
    private val sdkModel: SdkModel,
    private val sdkModificator: SdkModificator,
) : AdditionalDataConfigurable {
    private val internalErlangSdkLabel = JLabel("Internal Erlang SDK:")
    private val internalErlangSdksComboBoxModel = PairingComboBoxModel()
    private val internalErlangSdksComboBox = ComboBox(internalErlangSdksComboBoxModel)
    private val classpathWarningLabel = JLabel("Erlang classpath entries missing from Elixir SDK").apply {
        icon = AllIcons.General.Warning
        isVisible = false
    }
    private val otpMismatchWarningLabel = JLabel("").apply {
        icon = AllIcons.General.Warning
        isVisible = false
    }
    private val suppressOtpMismatchWarningCheckBox = JBCheckBox("Suppress OTP version mismatch warning").apply {
        isSelected = false
    }
    private val saveHintLabel =
        JLabel("After changing the Erlang SDK, save settings and reopen this dialog to see updates").apply {
            icon = AllIcons.General.Information
            isVisible = false
        }
    private val sdkModelListener: SdkModel.Listener

    /**
     * Cancelling a detection cannot interrupt a read already inside the blocking filesystem calls,
     * so they are serialised rather than merely superseded.
     *
     * Per instance, not per application: a home on a dead WSL distro can block indefinitely, and an
     * application-wide limit would park every other editor's detection behind it.
     */
    private val otpDetectionDispatcher = Dispatchers.IO.limitedParallelism(1, "Elixir OTP mismatch detection")

    private var otpMismatchJob: Job? = null

    private var otpScope: CoroutineScope? = null
    private var elixirSdk: Sdk? = null

    /** The user picked an Erlang SDK in the combo; only then does [apply] write the pairing. */
    private var pairingPicked = false

    /** The suppress checkbox as the SDK stores it, when last reset or applied. */
    private var suppressedAsStored = false
    private val suppressionChanged: Boolean get() = suppressOtpMismatchWarningCheckBox.isSelected != suppressedAsStored

    /** Set while the configurable changes the combo itself, so its item events are not taken for a pick. */
    private var freeze = false

    init {
        sdkModelListener =
            object : SdkModel.Listener {
                override fun sdkAdded(sdk: Sdk) {
                    if (Type.staticIsValidDependency(sdk)) {
                        addErlangSdk(sdk)
                    }
                }

                override fun beforeSdkRemove(sdk: Sdk) {
                    if (Type.staticIsValidDependency(sdk)) {
                        dropRemovedErlangSdks()
                    }
                }
            }
        sdkModel.addListener(sdkModelListener)
    }

    private fun updateJdkList() {
        internalErlangSdksComboBoxModel.removeAllElements()
        sdkModel.sdks.filter { Type.staticIsValidDependency(it) }.forEach(internalErlangSdksComboBoxModel::addElement)
    }

    override fun setSdk(sdk: Sdk) {
        LOG.debug("[setSdk] Setting Elixir SDK: ${sdk.name} (additionalData=${sdk.sdkAdditionalData?.javaClass?.simpleName})")
        elixirSdk = sdk
    }

    override fun createComponent(): JComponent {
        val wholePanel = JPanel(GridBagLayout())

        wholePanel.add(
            internalErlangSdkLabel,
            GridBagConstraints(
                0,
                GridBagConstraints.RELATIVE,
                1,
                1,
                0.0,
                1.0,
                GridBagConstraints.WEST,
                GridBagConstraints.NONE,
                JBUI.emptyInsets(),
                0,
                0,
            ),
        )
        wholePanel.add(
            internalErlangSdksComboBox,
            GridBagConstraints(
                1,
                GridBagConstraints.RELATIVE,
                1,
                1,
                1.0,
                1.0,
                GridBagConstraints.EAST,
                GridBagConstraints.HORIZONTAL,
                JBUI.insetsLeft(30),
                0,
                0,
            ),
        )
        wholePanel.add(
            classpathWarningLabel,
            GridBagConstraints(
                0,
                GridBagConstraints.RELATIVE,
                2,
                1,
                1.0,
                0.0,
                GridBagConstraints.WEST,
                GridBagConstraints.HORIZONTAL,
                JBUI.insetsTop(8),
                0,
                0,
            ),
        )
        wholePanel.add(
            saveHintLabel,
            GridBagConstraints(
                0,
                GridBagConstraints.RELATIVE,
                2,
                1,
                1.0,
                0.0,
                GridBagConstraints.WEST,
                GridBagConstraints.HORIZONTAL,
                JBUI.insetsTop(4),
                0,
                0,
            ),
        )
        wholePanel.add(
            otpMismatchWarningLabel,
            GridBagConstraints(
                0,
                GridBagConstraints.RELATIVE,
                2, 1, 1.0, 0.0,
                GridBagConstraints.WEST,
                GridBagConstraints.HORIZONTAL,
                JBUI.insetsTop(8),
                0, 0,
            ),
        )
        wholePanel.add(
            suppressOtpMismatchWarningCheckBox,
            GridBagConstraints(
                0,
                GridBagConstraints.RELATIVE,
                2, 1, 1.0, 0.0,
                GridBagConstraints.WEST,
                GridBagConstraints.HORIZONTAL,
                JBUI.insetsTop(4),
                0, 0,
            ),
        )
        suppressOtpMismatchWarningCheckBox.addItemListener { updateOtpMismatchWarning() }
        internalErlangSdksComboBox.setRenderer(
            object : SimpleListCellRenderer<Sdk>() {
                override fun customize(
                    list: JList<out Sdk>,
                    value: Sdk?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    text = value?.name
                }
            },
        )
        internalErlangSdksComboBox.addItemListener { itemEvent: ItemEvent ->
            if (!freeze) {
                val stateChange = itemEvent.stateChange
                val internalErlangSdk = itemEvent.item as Sdk
                val myElixirSdk = elixirSdk ?: return@addItemListener

                // Update UI path editors only. The actual SDK will be updated when the user
                // clicks Apply, via Editor.apply() which calls pathEditor.apply(sdkModificator).
                // The sdkModificator parameter is a UI-only wrapper that updates the path editors
                // displayed in the dialog tabs.
                if (stateChange == ItemEvent.DESELECTED) {
                    removeCodePathsFromInternalErlangSdk(
                        myElixirSdk,
                        internalErlangSdk,
                        sdkModificator,
                    )
                } else if (stateChange == ItemEvent.SELECTED) {
                    addNewCodePathsFromInternErlangSdk(
                        myElixirSdk,
                        internalErlangSdk,
                        sdkModificator,
                    )
                    pairingPicked = true
                    updateLabels(saveHint = true)
                }
            }
        }

        updateWarningLabel()
        updateOtpMismatchWarning()

        return wholePanel
    }

    override fun isModified(): Boolean = pairingPicked || suppressionChanged

    /** Writes the pairing only after a pick: what the combo shows otherwise is the model's selection, not the user's. */
    @Throws(ConfigurationException::class)
    override fun apply() {
        val myElixirSdk = elixirSdk ?: return
        if (pairingPicked) {
            if (writeInternalErlangSdk(myElixirSdk)) ElixirSdkPathConfigurator.followErlangHome(listOf(myElixirSdk), sdkModel)
        } else if (suppressionChanged) {
            WriteActions.runWriteAction {
                ElixirSdkMutation.setOtpMismatchWarningSuppressed(myElixirSdk, suppressOtpMismatchWarningCheckBox.isSelected)
            }
        }

        markStored()
    }

    /** What the combo and the checkbox show is now what the SDK stores. */
    private fun markStored() {
        pairingPicked = false
        suppressedAsStored = suppressOtpMismatchWarningCheckBox.isSelected
    }

    /** Returns whether the stored Erlang home changed. */
    private fun writeInternalErlangSdk(myElixirSdk: Sdk): Boolean {
        val erlangSdk = internalErlangSdksComboBox.selectedItem as? Sdk
        LOG.trace { "[${myElixirSdk.name}] Persisting Erlang SDK dependency: ${erlangSdk?.name ?: "null"}" }

        var rehomed = false
        WriteActions.runWriteAction {
            rehomed = ElixirSdkMutation.applyDependencySelection(
                elixirSdk = myElixirSdk,
                erlangSdk = erlangSdk,
                suppressOtpMismatchWarning = suppressOtpMismatchWarningCheckBox.isSelected,
            )
        }
        return rehomed
    }

    override fun reset() {
        val sdk = elixirSdk
        val sdkName = sdk?.name ?: "null"
        LOG.debug("[$sdkName] reset() called")

        frozen {
            updateJdkList()
            LOG.debug("[$sdkName] Populated combo box with ${internalErlangSdksComboBoxModel.size} Erlang SDKs")

            if (sdk == null) {
                LOG.debug("[$sdkName] No Elixir SDK set, skipping Erlang SDK selection")
                return
            }

            // Restore the suppress checkbox state from persisted additional data
            suppressOtpMismatchWarningCheckBox.isSelected = sdk.elixirAdditionalData?.isSuppressOtpMismatchWarning() ?: false

            showStoredPairing(sdk)
        }

        markStored()
        updateLabels(saveHint = false)
    }

    /** Shows or hides the hint to save and reopen, and refreshes both warnings. */
    private fun updateLabels(saveHint: Boolean) {
        saveHintLabel.isVisible = saveHint
        updateWarningLabel()
        updateOtpMismatchWarning()
    }

    /** Selects the Erlang SDK [sdk]'s pairing resolves to in [sdkModel], and adds its code paths when they are missing. */
    private fun showStoredPairing(sdk: Sdk) {
        val erlangSdk = sdk.elixirAdditionalData?.getErlangSdk(sdkModel)
        if (erlangSdk == null) {
            LOG.debug("[${sdk.name}] No Erlang SDK found to select")
            return
        }

        val index = internalErlangSdksComboBoxModel.indexOfName(erlangSdk.name)
        if (index != null) {
            LOG.debug("[${sdk.name}] Found Erlang SDK '${erlangSdk.name}' at index $index, selecting")
            internalErlangSdksComboBox.selectedIndex = index
        } else {
            LOG.debug { ("[${sdk.name}] Erlang SDK '${erlangSdk.name}' not found in combo box (size=${internalErlangSdksComboBoxModel.size})") }
        }

        if (!hasErlangClasspathInRoots(sdkModificator.getRoots(OrderRootType.CLASSES), erlangSdk)) {
            addNewCodePathsFromInternErlangSdk(sdk, erlangSdk, sdkModificator)
        }
    }

    override fun disposeUIResources() {
        otpMismatchJob = null
        otpScope?.cancel()
        otpScope = null
        sdkModel.removeListener(sdkModelListener)
    }

    private fun otpScope(): CoroutineScope =
        otpScope?.takeIf { it.isActive }
            ?: service<ElixirAppCoroutineService>()
                .supervisedChildScope("AdditionalDataConfigurable.otpMismatch")
                .also { otpScope = it }

    private fun addErlangSdk(sdk: Sdk) {
        internalErlangSdksComboBoxModel.addElement(sdk)
    }

    /**
     * `ProjectSdksModel.removeSdk` fires with the table's SDK once its copy, which the combo lists and which may carry
     * another name, has left the model. Removing the picked SDK voids the pick: the page shows the stored pairing again,
     * with its code paths in place of the pick's.
     */
    private fun dropRemovedErlangSdks() {
        val held = sdkModel.sdks
        fun gone(item: Sdk) = held.none { it === item }
        val picked = (internalErlangSdksComboBox.selectedItem as? Sdk)?.takeIf { pairingPicked && gone(it) }
        frozen {
            for (index in (0 until internalErlangSdksComboBoxModel.size).reversed()) {
                if (gone(internalErlangSdksComboBoxModel.getElementAt(index))) internalErlangSdksComboBoxModel.removeElementAt(index)
            }
        }
        val myElixirSdk = elixirSdk
        if (picked == null || myElixirSdk == null) return

        pairingPicked = false
        removeCodePathsFromInternalErlangSdk(myElixirSdk, picked, sdkModificator)
        frozen { showStoredPairing(myElixirSdk) }
        updateLabels(saveHint = false)
    }

    private inline fun frozen(block: () -> Unit) {
        freeze = true
        try {
            block()
        } finally {
            freeze = false
        }
    }

    /**
     * Computes whether the currently selected Erlang SDK's OTP major matches the OTP major that
     * the Elixir SDK was compiled against, and updates [otpMismatchWarningLabel] accordingly.
     *
     * Runs on the application coroutine scope, not [org.elixir_lang.util.runWithEdtGuard]: the
     * platform calls this from inside its own read action, and that helper would not drop it - see
     * its KDoc.
     *
     * The combo box and the suppress checkbox both re-enter here, so a superseded result must not
     * overwrite a newer one.
     *
     * Informational only - never prevents applying settings.
     */
    @RequiresEdt
    private fun updateOtpMismatchWarning() {
        ThreadingAssertions.assertEventDispatchThread()

        otpMismatchJob?.cancel()
        otpMismatchJob = null

        val myElixirSdk = elixirSdk
        val selectedErlangSdk = internalErlangSdksComboBox.selectedItem as? Sdk

        if (myElixirSdk == null || selectedErlangSdk == null || suppressOtpMismatchWarningCheckBox.isSelected) {
            hideOtpMismatchWarning()
            return
        }

        // Read on the EDT: these are the dialog's editable copies, which SdkEditor commits to
        // inside a write action on apply and reset.
        val elixirHome = myElixirSdk.homePath ?: return hideOtpMismatchWarning()
        val erlangHome = selectedErlangSdk.homePath ?: return hideOtpMismatchWarning()

        // This label's state is owned by the asynchronous detection below and is written once, when
        // the result lands. Touching it here would repaint the row twice and, because GridBagLayout
        // skips invisible components, reflow every row under it in between.
        otpMismatchJob = otpScope().launch {
            val mismatch = withContext(otpDetectionDispatcher) {
                SdkVersionsFiller.fillIfUnread(elixirHome)
                SdkVersionsFiller.fillIfUnread(erlangHome)
                ElixirSdkValidation.otpMatch(elixirHome, erlangHome) as? OtpMatch.Mismatch
            }

            // Unqualified UI dispatch defaults to nonModal, which would hold the update until this
            // modal dialog closes. any() is only legal because this block touches no PSI, VFS or
            // project model - keep it that way.
            withContext(Dispatchers.UI + ModalityState.any().asContextElement()) {
                if (mismatch != null) {
                    otpMismatchWarningLabel.text = mismatch.warning
                }
                otpMismatchWarningLabel.isVisible = mismatch != null
            }
        }
    }

    private fun hideOtpMismatchWarning() {
        otpMismatchWarningLabel.isVisible = false
    }

    private fun updateWarningLabel() {
        if (elixirSdk == null) {
            classpathWarningLabel.isVisible = false
            return
        }
        val erlangSdk = (internalErlangSdksComboBox.selectedItem as? Sdk) ?: run {
            classpathWarningLabel.isVisible = false
            return
        }
        // Check if Erlang classpath is in the UI modificator (not the persisted SDK).
        // This allows the warning to update immediately when the user changes the Erlang SDK selection,
        // even before clicking Apply. The sdkModificator reads from the UI path editors.
        val classRoots = sdkModificator.getRoots(OrderRootType.CLASSES)
        classpathWarningLabel.isVisible = !hasErlangClasspathInRoots(classRoots, erlangSdk)
    }

    /**
     * `DefaultComboBoxModel` selects the first element added to an empty model, and a neighbour of a selected element
     * removed, either of which would show a pairing nobody picked.
     */
    private class PairingComboBoxModel : DefaultComboBoxModel<Sdk>() {
        override fun addElement(anObject: Sdk) = insertElementAt(anObject, size)

        override fun removeElementAt(index: Int) {
            if (selectedItem === getElementAt(index)) selectedItem = null
            super.removeElementAt(index)
        }

        fun indexOfName(name: String): Int? = (0 until size).firstOrNull { getElementAt(it).name == name }
    }

    companion object {
        // Qualified because an explicit import outranks a declaration in the same file: the bare
        // name resolves to the imported com.intellij.openapi.projectRoots.AdditionalDataConfigurable.
        private val LOG = Logger.getInstance(org.elixir_lang.sdk.erlang_dependent.AdditionalDataConfigurable::class.java)
    }
}
