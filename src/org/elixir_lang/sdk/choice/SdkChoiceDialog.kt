package org.elixir_lang.sdk.choice

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import org.elixir_lang.sdk.erlang_dependent.rowText
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** Asks which of the [state]'s Elixir SDKs its modules use. OK is enabled once one is picked. */
class SdkChoiceDialog(parent: Component?, internal val state: SdkChoiceState) :
    DialogWrapper(state.project, parent, true, IdeModalityType.IDE) {
    private val list = JBList(state.listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = EntryRenderer()
        addListSelectionListener { event -> if (!event.valueIsAdjusting) pick(selectedValue) }
    }

    init {
        title = "Choose Elixir SDK"
        isOKActionEnabled = false
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        add(JBLabel("Choose the Elixir SDK for ${modulesText()}:"), BorderLayout.NORTH)
        add(JBScrollPane(list).apply { preferredSize = JBUI.size(520, 200) }, BorderLayout.CENTER)
    }

    private fun modulesText(): String =
        state.modules.singleOrNull()?.let { "module '${it.name}'" } ?: "the modules of '${state.project.name}'"

    override fun getPreferredFocusedComponent(): JComponent = list

    /** Shows the dialog, and answers once it is closed with the entry picked, or null when cancelled. */
    fun showAndPick(): SdkChoiceEntry? = if (showAndGet()) state.selected else null

    internal fun pick(entry: SdkChoiceEntry?) {
        state.select(entry)
        val selected = state.selected
        if (list.selectedValue != selected) list.setSelectedValue(selected, true)
        isOKActionEnabled = selected != null
    }

    /** The text of the list's row for [entry], as its renderer draws it. */
    internal fun rowText(entry: SdkChoiceEntry): String = list.rowText(entry)

    private class EntryRenderer : ColoredListCellRenderer<SdkChoiceEntry>() {
        override fun customizeCellRenderer(
            list: JList<out SdkChoiceEntry>,
            value: SdkChoiceEntry?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            value ?: return
            append(value.sdk.name)
            val erlang = value.erlang
            append(
                if (erlang == null) "  no Erlang SDK" else "  Erlang SDK '${erlang.name}'" + (erlang.otpVersion?.let { ", OTP $it" } ?: ""),
                SimpleTextAttributes.GRAYED_ATTRIBUTES,
            )
        }
    }
}
