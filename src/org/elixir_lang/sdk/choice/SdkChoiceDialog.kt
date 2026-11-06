package org.elixir_lang.sdk.choice

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.AnActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.JBUI
import org.elixir_lang.sdk.SdkTableChanges
import org.elixir_lang.sdk.elixir.SdkSettingsOpener
import org.elixir_lang.sdk.elixir.SettingsPage
import org.elixir_lang.sdk.elixir.Type
import org.elixir_lang.sdk.erlang_dependent.Type.Companion.staticIsValidDependency
import org.elixir_lang.sdk.erlang_dependent.rowText
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.ActionEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/** Asks which of the [state]'s Elixir SDKs [modules] use. OK is enabled once one is picked. */
class SdkChoiceDialog(parent: Component?, private val modules: List<Module>, internal val state: SdkChoiceState) :
    DialogWrapper(state.project, parent, true, IdeModalityType.IDE) {
    private val list = JBList(state.listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = EntryRenderer()
        addListSelectionListener { event -> if (!event.valueIsAdjusting) pick(selectedValue) }
    }

    internal val manageSdksLink = AnActionLink("Manage SDKs...", object : DumbAwareAction() {
        override fun actionPerformed(e: AnActionEvent) = SdkSettingsOpener.getInstance().open(e, SettingsPage.SDKS)
    })

    private val reloadQueued = AtomicBoolean()

    init {
        title = "Choose Elixir SDK"
        isOKActionEnabled = false
        init()
        // The Elixir SDKs it lists, and the Erlang SDKs their rows name.
        SdkTableChanges.follow(disposable, { it.sdkType === Type.instance || staticIsValidDependency(it) }) { _, _ ->
            reloadLater()
        }
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        add(JBLabel("Choose the Elixir SDK for ${modulesText()}:"), BorderLayout.NORTH)
        add(JBScrollPane(list).apply { preferredSize = JBUI.size(520, 200) }, BorderLayout.CENTER)
        add(manageSdksLink, BorderLayout.SOUTH)
    }

    override fun createLeftSideActions(): Array<Action> = arrayOf(
        object : DialogWrapperAction("Add...") {
            override fun doAction(e: ActionEvent) = add()
        },
    )

    @RequiresEdt
    internal fun add() {
        state.add(list)?.let(::pick)
    }

    // Later, at this dialog's modality, so not while Settings or the create flow is open over it: the table publishes
    // inside its write action. One reload for a burst, as an Apply on the SDKs page commits every SDK in the table.
    private fun reloadLater() {
        if (!reloadQueued.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater(
            { reloadQueued.set(false); reload() },
            ModalityState.stateForComponent(list),
        ) { isDisposed }
    }

    private fun reload() {
        state.reload()
        pick(state.selected)
    }

    private fun modulesText(): String =
        modules.singleOrNull()?.let { "module '${it.name}'" } ?: "the modules of '${state.project.name}'"

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
