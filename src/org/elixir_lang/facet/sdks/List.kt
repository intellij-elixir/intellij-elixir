package org.elixir_lang.facet.sdks

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBList
import org.elixir_lang.facet.sdk.Name
import org.elixir_lang.sdk.elixir.ListsSdks
import org.elixir_lang.sdk.elixir.SdkList
import javax.swing.ListSelectionModel

class List(private val configurable: Configurable): JBList<ProjectJdkImpl>(), ListsSdks {
    init {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = Name()
    }

    /** Replacing the model clears the selection, so the selected SDK is selected again if it is still listed. */
    fun refresh() {
        val selected = selectedValue
        model = CollectionListModel<ProjectJdkImpl>(configurable.listedSdks())
        selected?.let { setSelectedValue(it, false) }
    }

    override fun sdkListFor(sdk: Sdk): SdkList? = editorNames.takeIf { (0 until model.size).any { model.getElementAt(it) === sdk } }

    /** Each SDK's Name is its editor's Name field until Apply, as a typed name is. */
    private val editorNames = object : SdkList {
        override fun nameOf(sdk: Sdk): String = configurable.nameOf(sdk)

        override fun rename(sdk: Sdk, name: String) {
            configurable.editorOf(sdk)?.name = name
        }

        override fun select(sdk: Sdk) = setSelectedValue(sdk, true)
    }
}
