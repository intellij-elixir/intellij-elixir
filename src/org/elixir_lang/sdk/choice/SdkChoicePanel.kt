package org.elixir_lang.sdk.choice

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.AnActionLink
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
import java.awt.FlowLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * The chooser's list of [state]'s Elixir SDKs, each with its pairing, with nothing selected, Add... and Manage SDKs...,
 * following the table until [parentDisposable] goes. [onPick] hears each change of the pick, null included.
 */
class SdkChoicePanel(
    internal val state: SdkChoiceState,
    parentDisposable: Disposable,
    private val onPick: (SdkChoiceEntry?) -> Unit,
) : JPanel(BorderLayout(0, JBUI.scale(8))) {
    private val list = JBList(state.listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = EntryRenderer()
        addListSelectionListener { event -> if (!event.valueIsAdjusting) pick(selectedValue) }
    }

    internal val addButton = JButton("Add...").apply { addActionListener { state.add(list)?.let(::pick) } }

    internal val manageSdksLink = AnActionLink("Manage SDKs...", object : DumbAwareAction() {
        override fun actionPerformed(e: AnActionEvent) = SdkSettingsOpener.getInstance().open(e, SettingsPage.SDKS)
    })

    private val reloadQueued = AtomicBoolean()
    private val alive = Disposer.newCheckedDisposable(parentDisposable)

    init {
        add(JBScrollPane(list).apply { preferredSize = JBUI.size(520, 200) }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(8), 0)).apply {
            add(addButton)
            add(manageSdksLink)
        }, BorderLayout.SOUTH)
        // The Elixir SDKs it lists, and the Erlang SDKs their rows name.
        SdkTableChanges.follow(alive, { it.sdkType === Type.instance || staticIsValidDependency(it) }) { _, _ ->
            reloadLater()
        }
    }

    /** The component to focus first. */
    val preferredFocusedComponent get() = list

    // Later, at this panel's modality, so not while Settings or the create flow is open over it: the table publishes
    // inside its write action. One reload for a burst, as an Apply on the SDKs page commits every SDK in the table.
    private fun reloadLater() {
        if (!reloadQueued.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater(
            { reloadQueued.set(false); reload() },
            ModalityState.stateForComponent(list),
        ) { alive.isDisposed }
    }

    private fun reload() {
        state.reload()
        pick(state.selected)
    }

    internal fun pick(entry: SdkChoiceEntry?) {
        state.select(entry)
        val selected = state.selected
        if (list.selectedValue != selected) list.setSelectedValue(selected, true)
        onPick(selected)
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
