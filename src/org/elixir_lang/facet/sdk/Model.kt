package org.elixir_lang.facet.sdk

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.ui.CollectionListModel
import org.elixir_lang.facet.SdksService
import org.elixir_lang.sdk.elixir.Type
import javax.swing.ComboBoxModel

class Model :
    CollectionListModel<Sdk?>(
        SdksService.getInstance()?.projectJdkImplList(Type::class.java) ?: mutableListOf(),
        false
    ),
    ComboBoxModel<Sdk?> {
    /**
     * The [ProjectSdksModel] this instance subscribed to. Kept so [detach] unsubscribes from the
     * same instance that [listener] was added to.
     */
    private val subscribedSdksModel: ProjectSdksModel? = SdksService.getInstance()?.getModel()

    private val listener = object : SdkModel.Listener {
        override fun beforeSdkRemove(sdk: Sdk) {
            // ProjectSdksModel.removeSdk fires this with the table SDK once its copy, which this model lists and which
            // may carry another name, has left the model's map.
            val copies = subscribedSdksModel?.projectSdks?.values ?: return
            for (index in internalList.indices.reversed()) {
                val item = internalList[index]
                if (item != null && copies.none { it === item }) {
                    remove(index)
                }
            }
        }

        override fun sdkAdded(sdk: Sdk) {
            if (sdk.sdkType is Type) {
                add(sdk)
            }
        }
    }

    init {
        internalList.add(0, null)
        subscribedSdksModel?.addListener(listener)
    }

    /**
     * Unsubscribes [listener] from the shared [ProjectSdksModel]. Must be called when the owning
     * UI is disposed (e.g. from a Configurable's `disposeUIResources`) - the shared model is an
     * application-lifetime singleton, so an un-removed listener accumulates on every Settings
     * open and pins this model (and its Swing combo box) forever.
     */
    fun detach() {
        subscribedSdksModel?.removeListener(listener)
    }

    private var _selectedItem: Sdk? = items[0]

    override fun getSelectedItem(): Any? {
        return _selectedItem
    }

    override fun setSelectedItem(item: Any?) {
        if (_selectedItem !== item) {
            _selectedItem = item as Sdk?
            update()
        }
    }

    private fun update() {
        fireContentsChanged(this, -1, -1)
    }
}
