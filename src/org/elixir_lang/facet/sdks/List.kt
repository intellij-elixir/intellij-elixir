package org.elixir_lang.facet.sdks

import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBList
import org.elixir_lang.facet.sdk.Name
import javax.swing.ListSelectionModel

class List(private val configurable: Configurable): JBList<ProjectJdkImpl>() {
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
}
