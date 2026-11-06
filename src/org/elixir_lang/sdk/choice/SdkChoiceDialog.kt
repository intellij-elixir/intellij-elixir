package org.elixir_lang.sdk.choice

import com.intellij.openapi.module.Module
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JPanel

/** Asks which of the [state]'s Elixir SDKs [modules] use. OK is enabled once one is picked. */
class SdkChoiceDialog(parent: Component?, private val modules: List<Module>, private val state: SdkChoiceState) :
    DialogWrapper(state.project, parent, true, IdeModalityType.IDE) {
    internal val panel = SdkChoicePanel(state, disposable) { isOKActionEnabled = it != null }

    init {
        title = "Choose Elixir SDK"
        isOKActionEnabled = false
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        add(JBLabel("Choose the Elixir SDK for ${modulesText()}:"), BorderLayout.NORTH)
        add(panel, BorderLayout.CENTER)
    }

    private fun modulesText(): String =
        modules.singleOrNull()?.let { "module '${it.name}'" } ?: "the modules of '${state.project.name}'"

    override fun getPreferredFocusedComponent(): JComponent = panel.preferredFocusedComponent

    /** Shows the dialog, and answers once it is closed with the pick, or null when cancelled. */
    fun showAndPick(): SdkChoicePick? = if (showAndGet()) state.pick else null
}
