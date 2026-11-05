package org.elixir_lang.facet

import com.intellij.facet.FacetConfiguration
import com.intellij.facet.ui.FacetEditorContext
import com.intellij.facet.ui.FacetEditorTab
import com.intellij.facet.ui.FacetValidatorsManager
import com.intellij.openapi.components.PersistentStateComponent
import org.jdom.Element

/** The name of the Elixir SDK the facet supplies on the IDEs without module SDK settings. */
class Configuration : FacetConfiguration, PersistentStateComponent<Element> {
    var sdkName: String? = null

    override fun createEditorTabs(
        editorContext: FacetEditorContext,
        validatorsManager: FacetValidatorsManager
    ): Array<FacetEditorTab> = arrayOf()

    override fun getState(): Element =
        Element(CONFIGURATION).apply { sdkName?.let { setAttribute(SDK_NAME, it) } }

    override fun loadState(state: Element) {
        sdkName = state.getAttributeValue(SDK_NAME)
    }

    private companion object {
        const val CONFIGURATION = "configuration"
        const val SDK_NAME = "sdkName"
    }
}
