package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.roots.ui.configuration.SdkComboBox
import com.intellij.openapi.roots.ui.configuration.SdkComboBoxModel
import com.intellij.openapi.roots.ui.configuration.SdkListItem
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JList

/** The field on an Elixir SDK's page that says which Erlang SDK it is paired with. Its SDKs are the dialog's copies. */
internal interface ErlangSdkField {
    val component: JComponent
    val selectedSdk: Sdk?

    /** Calls [listener] with each SDK the selection enters or leaves, and the `ItemEvent` state change. */
    fun addSelectionListener(listener: (stateChange: Int, erlangSdk: Sdk) -> Unit)

    /** Lists the Erlang SDKs the model holds now. */
    fun reload()

    /** Lists [erlangSdk], just added to the model. */
    fun added(erlangSdk: Sdk)

    /** Stops listing every SDK the model no longer holds, and selects nothing if the selected one is among them, answering that one. */
    fun dropGone(): Sdk?

    /** Shows [erlangSdk], what a stored pairing resolves to, which the model may not hold. */
    fun show(erlangSdk: Sdk?)
}

/** The platform's SDK field, with its Detected and Add rows, over the Project Structure model [sdksModel]. */
internal class PlatformErlangSdkField(project: Project, private val sdksModel: ProjectSdksModel) : ErlangSdkField {
    private val comboBox =
        SdkComboBox(SdkComboBoxModel.createSdkComboBoxModel(project, sdksModel, sdkTypeFilter = { Type.staticIsValidDependency(it) }))

    override val component: JComponent get() = comboBox

    /**
     * What the project row stands for since it was chosen. A pairing names an SDK, while the row follows the project's, which
     * can change or go. When the row names none, the selection's SDK stays as it was: no row of this field clears a pairing.
     */
    private var projectRowSdk: Sdk? = null

    /** The row's own SDK, the copy: `SdkComboBox.getSelectedSdk` answers the model's key for it. */
    override val selectedSdk: Sdk? get() = sdkOf(comboBox.selectedItem)

    private fun sdkOf(item: Any?): Sdk? =
        when (item) {
            is SdkListItem.SdkItem -> item.sdk
            is SdkListItem.ProjectSdkItem -> projectRowSdk
            else -> null
        }

    override fun addSelectionListener(listener: (stateChange: Int, erlangSdk: Sdk) -> Unit) =
        comboBox.addItemListener { event ->
            // The old row's event comes first, with the selection already moved.
            if (event.stateChange == ItemEvent.DESELECTED && comboBox.selectedItem is SdkListItem.ProjectSdkItem) {
                projectRowSdk = sdksModel.projectSdk ?: sdkOf(event.item)
            }
            sdkOf(event.item)?.let { listener(event.stateChange, it) }
        }

    override fun reload() = comboBox.reloadModel()

    override fun added(erlangSdk: Sdk) = reload()

    override fun dropGone(): Sdk? {
        reload()
        val dropped = selectedSdk?.takeIf { selected -> sdksModel.sdks.none { it === selected } } ?: return null
        comboBox.selectedItem = null
        return dropped
    }

    override fun show(erlangSdk: Sdk?) {
        comboBox.selectedItem = comboBox.model.listModel.findSdkItem(erlangSdk)
    }
}

/** A plain combo of the Erlang SDKs in [sdkModel]. */
internal class ComboErlangSdkField(private val sdkModel: SdkModel) : ErlangSdkField {
    private val comboBoxModel = PairingComboBoxModel()
    private val comboBox = ComboBox(comboBoxModel).apply {
        setRenderer(
            object : SimpleListCellRenderer<Sdk>() {
                override fun customize(list: JList<out Sdk>, value: Sdk?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    text = value?.name
                }
            },
        )
    }

    override val component: JComponent get() = comboBox
    override val selectedSdk: Sdk? get() = comboBox.selectedItem as? Sdk

    override fun addSelectionListener(listener: (stateChange: Int, erlangSdk: Sdk) -> Unit) =
        comboBox.addItemListener { listener(it.stateChange, it.item as Sdk) }

    override fun reload() {
        comboBoxModel.removeAllElements()
        sdkModel.sdks.filter { Type.staticIsValidDependency(it) }.forEach(comboBoxModel::addElement)
    }

    override fun added(erlangSdk: Sdk) = comboBoxModel.addElement(erlangSdk)

    override fun dropGone(): Sdk? {
        val held = sdkModel.sdks
        val selected = selectedSdk
        for (index in (0 until comboBoxModel.size).reversed()) {
            val listed = comboBoxModel.getElementAt(index)
            if (held.none { it === listed }) comboBoxModel.removeElementAt(index)
        }
        return selected.takeIf { selectedSdk !== it }
    }

    override fun show(erlangSdk: Sdk?) {
        comboBoxModel.indexOfName(erlangSdk?.name)?.let { comboBox.selectedIndex = it }
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

        fun indexOfName(name: String?): Int? = (0 until size).firstOrNull { getElementAt(it).name == name }
    }
}
