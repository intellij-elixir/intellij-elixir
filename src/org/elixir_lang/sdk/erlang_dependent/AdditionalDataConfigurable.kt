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
import com.intellij.openapi.util.Comparing
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
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType

class AdditionalDataConfigurable(
    private val sdkModel: SdkModel,
    private val sdkModificator: SdkModificator,
) : AdditionalDataConfigurable {
    private val internalErlangSdkLabel = JLabel("Internal Erlang SDK:")
    private val internalErlangSdksComboBoxModel = DefaultComboBoxModel<Sdk?>()
    private val internalErlangSdksComboBox = ComboBox<Sdk>(internalErlangSdksComboBoxModel)
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
    private var modified = false
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
                        removeErlangSdk(sdk)
                    }
                }

                override fun sdkChanged(
                    sdk: Sdk,
                    previousName: String,
                ) {
                    if (Type.staticIsValidDependency(sdk)) {
                        updateErlangSdkList(sdk, previousName)
                    }
                }

                override fun sdkHomeSelected(
                    sdk: Sdk,
                    newSdkHome: String,
                ) {
                    if (Type.staticIsValidDependency(sdk)) {
                        internalErlangSdkUpdate(sdk)
                    }
                }
            }
        sdkModel.addListener(sdkModelListener)
    }

    private fun updateJdkList() {
        internalErlangSdksComboBoxModel.removeAllElements()

        val validErlangSdks = mutableListOf<String>()
        for (sdk in sdkModel.sdks) {
            if (Type.staticIsValidDependency(sdk)) {
                internalErlangSdksComboBoxModel.addElement(sdk)
                validErlangSdks.add(sdk.name)
            }
        }
        LOG.debug("[updateJdkList] Found ${validErlangSdks.size} valid Erlang SDKs: $validErlangSdks")
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
        suppressOtpMismatchWarningCheckBox.addItemListener {
            modified = true
            updateOtpMismatchWarning()
        }
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

                if (stateChange == ItemEvent.DESELECTED) {
                    // Update UI path editors only. The actual SDK will be updated when the user
                    // clicks Apply, via Editor.apply() which calls pathEditor.apply(sdkModificator).
                    // The sdkModificator parameter is a UI-only wrapper that updates the path editors
                    // displayed in the dialog tabs.
                    removeCodePathsFromInternalErlangSdk(
                        myElixirSdk,
                        internalErlangSdk,
                        sdkModificator,
                    )
                } else if (stateChange == ItemEvent.SELECTED) {
                    // Update UI path editors only. The actual SDK will be updated when the user
                    // clicks Apply, via Editor.apply() which calls pathEditor.apply(sdkModificator).
                    // The sdkModificator parameter is a UI-only wrapper that updates the path editors
                    // displayed in the dialog tabs.
                    addNewCodePathsFromInternErlangSdk(
                        myElixirSdk,
                        internalErlangSdk,
                        sdkModificator,
                    )
                    modified = true
                    // Show hint to save and reopen
                    saveHintLabel.isVisible = true
                    updateWarningLabel()
                    updateOtpMismatchWarning()
                }
            }
        }

        modified = true
        updateWarningLabel()
        updateOtpMismatchWarning()

        return wholePanel
    }

    private fun internalErlangSdkUpdate(sdk: Sdk) {
        LOG.debug("[internalErlangSdkUpdate] SDK home selected for: ${sdk.name}")
        val sdkAdditionalData = sdk.sdkAdditionalData as? SdkAdditionalData

        val erlangSdk = sdkAdditionalData?.getErlangSdk(sdkModel) ?: findFirstErlangSdkInModel()
        LOG.debug("[internalErlangSdkUpdate] Erlang SDK: ${erlangSdk?.name}")

        if (erlangSdk == null || internalErlangSdksComboBoxModel.getIndexOf(erlangSdk) == -1) {
            LOG.debug("[internalErlangSdkUpdate] Adding Erlang SDK to combo box: ${erlangSdk?.name}")
            internalErlangSdksComboBoxModel.addElement(erlangSdk)
        } else {
            LOG.debug("[internalErlangSdkUpdate] Selecting existing Erlang SDK in combo box: ${erlangSdk.name}")
            internalErlangSdksComboBoxModel.selectedItem = erlangSdk
        }
    }

    override fun isModified(): Boolean {
        if (modified) {
            return true
        }

        val sdk = elixirSdk ?: return false
        val selectedErlangSdk = internalErlangSdksComboBox.selectedItem as? Sdk ?: return false
        val additionalData = sdk.sdkAdditionalData as? SdkAdditionalData ?: return true

        return (additionalData.getErlangSdkName() != selectedErlangSdk.name) ||
                (additionalData.isSuppressOtpMismatchWarning() != suppressOtpMismatchWarningCheckBox.isSelected)
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val myElixirSdk = elixirSdk ?: return
        val erlangSdkChanged = erlangSdkChanged(myElixirSdk)

        writeInternalErlangSdk(myElixirSdk)
        if (erlangSdkChanged) reconfigureSdkPaths(myElixirSdk)

        modified = false
    }

    private fun erlangSdkChanged(myElixirSdk: Sdk): Boolean {
        val selectedErlangSdk = internalErlangSdksComboBox.selectedItem as? Sdk
        val existingAdditionalData = myElixirSdk.sdkAdditionalData as? SdkAdditionalData
        return existingAdditionalData?.getErlangSdkName() != selectedErlangSdk?.name
    }

    private fun reconfigureSdkPaths(myElixirSdk: Sdk) {
        LOG.trace { "[${myElixirSdk.name}] Reconfiguring SDK paths after Erlang SDK update" }
        ElixirSdkPathConfigurator.configure(myElixirSdk)
    }

    private fun writeInternalErlangSdk(myElixirSdk: Sdk) {
        val erlangSdk = internalErlangSdksComboBox.selectedItem as? Sdk
        LOG.trace { "[${myElixirSdk.name}] Persisting Erlang SDK dependency: ${erlangSdk?.name ?: "null"}" }

        WriteActions.runWriteAction {
            ElixirSdkMutation.applyDependencySelection(
                elixirSdk = myElixirSdk,
                erlangSdk = erlangSdk,
                suppressOtpMismatchWarning = suppressOtpMismatchWarningCheckBox.isSelected,
            )
        }
    }

    override fun reset() {
        val sdk = elixirSdk
        val sdkName = sdk?.name ?: "null"
        LOG.debug("[$sdkName] reset() called")

        freeze = true
        updateJdkList()
        LOG.debug("[$sdkName] Populated combo box with ${internalErlangSdksComboBoxModel.size} Erlang SDKs")

        if (sdk == null) {
            LOG.debug("[$sdkName] No Elixir SDK set, skipping Erlang SDK selection")
            freeze = false
            return
        }

        // Try to get the Erlang SDK from additional data, using sdkModel for lookup
        val additionalData = sdk.sdkAdditionalData as? SdkAdditionalData
        val erlangSdk = if (additionalData != null) {
            LOG.debug("[$sdkName] Has SdkAdditionalData, looking up Erlang SDK (erlangSdkName=${additionalData.getErlangSdkName()})")
            additionalData.getErlangSdk(sdkModel)
        } else {
            LOG.debug("[$sdkName] sdkAdditionalData is null or non-SdkAdditionalData type, auto-discovering Erlang SDK from model")
            findFirstErlangSdkInModel()
        }

        // Restore the suppress checkbox state from persisted additional data
        suppressOtpMismatchWarningCheckBox.isSelected = additionalData?.isSuppressOtpMismatchWarning() ?: false

        if (erlangSdk != null) {
            LOG.debug("[$sdkName] Looking for Erlang SDK '${erlangSdk.name}' in combo box")
            var found = false
            for (i in 0 until internalErlangSdksComboBoxModel.size) {
                val element = internalErlangSdksComboBoxModel.getElementAt(i)
                if (Comparing.strEqual(element?.name, erlangSdk.name)) {
                    LOG.debug("[$sdkName] Found Erlang SDK at index $i, selecting")
                    internalErlangSdksComboBox.selectedIndex = i
                    found = true
                    break
                }
            }
            if (!found) {
                LOG.debug { ("[$sdkName] Erlang SDK '${erlangSdk.name}' not found in combo box (size=${internalErlangSdksComboBoxModel.size})") }
            }

            if (!hasErlangClasspathInRoots(sdkModificator.getRoots(OrderRootType.CLASSES), erlangSdk)) {
                addNewCodePathsFromInternErlangSdk(sdk, erlangSdk, sdkModificator)
                updateWarningLabel()
            }
        } else {
            LOG.debug("[$sdkName] No Erlang SDK found to select")
        }

        // Release freeze after setting selectedIndex to avoid triggering ItemListener during reset
        freeze = false

        modified = false
        saveHintLabel.isVisible = false
        updateWarningLabel()
        updateOtpMismatchWarning()
    }

    /**
     * Finds the first valid Erlang SDK in the sdkModel.
     * Used when sdkAdditionalData is null/invalid and we need to auto-discover.
     */
    private fun findFirstErlangSdkInModel(): Sdk? {
        for (sdk in sdkModel.sdks) {
            if (Type.staticIsValidDependency(sdk)) {
                LOG.debug("[findFirstErlangSdkInModel] Found Erlang SDK: ${sdk.name}")
                return sdk
            }
        }
        LOG.debug("[findFirstErlangSdkInModel] No valid Erlang SDK found in model (${sdkModel.sdks.size} total SDKs)")
        return null
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

    private fun removeErlangSdk(sdk: Sdk) {
        if (internalErlangSdksComboBoxModel.selectedItem == sdk) {
            modified = true
        }

        internalErlangSdksComboBoxModel.removeElement(sdk)
    }

    private fun updateErlangSdkList(
        sdk: Sdk,
        previousName: String,
    ) {
        LOG.debug("[updateErlangSdkList] Erlang SDK renamed: '$previousName' -> '${sdk.name}'")
        val sdks = sdkModel.sdks

        for (currentSdk in sdks) {
            if (currentSdk.sdkType is ElixirSdkType) {
                val sdkAdditionalData = currentSdk.sdkAdditionalData as? SdkAdditionalData
                if (sdkAdditionalData == null) {
                    LOG.debug("[updateErlangSdkList] Skipping ${currentSdk.name}: no SdkAdditionalData")
                    continue
                }
                val erlangSdk = sdkAdditionalData.getErlangSdk(sdkModel)

                if (erlangSdk != null && erlangSdk.name == previousName) {
                    LOG.debug("[updateErlangSdkList] Updating ${currentSdk.name} to use renamed Erlang SDK")
                    sdkAdditionalData.setErlangSdk(sdk)
                }
            }
        }
        updateJdkList()
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

    companion object {
        // Qualified because an explicit import outranks a declaration in the same file: the bare
        // name resolves to the imported com.intellij.openapi.projectRoots.AdditionalDataConfigurable.
        private val LOG = Logger.getInstance(org.elixir_lang.sdk.erlang_dependent.AdditionalDataConfigurable::class.java)
    }
}
