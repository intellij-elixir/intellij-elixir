package org.elixir_lang.sdk.choice

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.platform.eel.EelDescriptor
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.AnActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.JBUI
import com.intellij.xml.util.XmlStringUtil
import org.elixir_lang.sdk.SdkTableChanges
import org.elixir_lang.sdk.elixir.SdkSettingsOpener
import org.elixir_lang.sdk.elixir.SettingsPage
import org.elixir_lang.sdk.elixir.Type
import org.elixir_lang.sdk.erlang_dependent.Type.Companion.staticIsValidDependency
import org.elixir_lang.sdk.erlang_dependent.rowText
import org.elixir_lang.tool_manager.providesEnvironment
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The chooser's list of [state]'s Elixir SDKs, each with its pairing, with nothing selected, Add... and Manage SDKs...,
 * following the table until [parentDisposable] goes. When a tool manager pins an Elixir for [state]'s folders, a button
 * offers it, with boxes that would turn the manager on. [onPick] hears each change of the pick or its opt-ins, null
 * included.
 */
class SdkChoicePanel(
    internal val state: SdkChoiceState,
    parentDisposable: Disposable,
    private val onPick: (SdkChoicePick?) -> Unit,
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

    private val offerButton = JButton().apply {
        isVisible = false
        addActionListener { pick(state.take(this@SdkChoicePanel)) }
    }

    private val inStepBox = JBCheckBox().apply { addActionListener { ticked() } }
    private val environmentBox = JBCheckBox().apply { addActionListener { ticked() } }
    @Suppress("DialogTitleCapitalization") // Names the Settings pages as they are titled.
    private val settingsPage = JBLabel("Change these in Settings > Languages & Frameworks > Elixir > Tool Managers.").apply { isVisible = !state.hostCommits }
    private val boxes = JPanel(VerticalLayout(0)).apply {
        isVisible = false
        add(inStepBox)
        add(environmentBox.apply { border = JBUI.Borders.emptyLeft(20) })
        add(settingsPage)
    }

    private val errorMessage = JBLabel()
    private val errorRow = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(8), 0)).apply {
        isVisible = false
        add(errorMessage)
        add(ActionLink("Check again") { check() })
    }

    private val reloadQueued = AtomicBoolean()
    private val alive = Disposer.newCheckedDisposable(parentDisposable)

    init {
        add(JBScrollPane(list).apply { preferredSize = JBUI.size(520, 200) }, BorderLayout.CENTER)
        add(JPanel(VerticalLayout(JBUI.scale(8))).apply {
            add(JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(8), 0)).apply {
                add(addButton)
                add(offerButton)
                add(manageSdksLink)
            })
            add(errorRow)
            add(boxes)
        }, BorderLayout.SOUTH)
        // The Elixir SDKs it lists, and the Erlang SDKs their rows name.
        SdkTableChanges.follow(alive, { it.sdkType === Type.instance || staticIsValidDependency(it) }) { _, _ ->
            reloadLater()
        }
        check()
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

    /** Lists the Elixir SDKs of [target]'s environment instead, and asks the tool managers about [folders]. */
    @RequiresEdt
    @Suppress("UnstableApiUsage") // EelDescriptor is the platform's only name for an environment.
    fun retarget(target: EelDescriptor?, folders: List<Path>) {
        val refolded = state.retarget(target, folders)
        pick(state.selected)
        if (refolded) {
            show(null)
            // A location typed in changes with each key: its check starts once the typing pauses, so one the next key
            // drops never runs a tool manager.
            check(after = 300.milliseconds)
        }
    }

    private fun check(after: Duration = Duration.ZERO): Unit = state.check(list, after) { answer ->
        show(answer)
        changed()
    }

    /** Shows [answer]'s offer and boxes, or its error, or neither. */
    private fun show(answer: ToolManagerCheck?) {
        val offer = answer as? ToolManagerCheck.Offer
        val error = answer as? ToolManagerCheck.Error
        offerButton.isVisible = offer != null
        boxes.isVisible = offer != null
        errorRow.isVisible = error != null
        if (offer != null) {
            val manager = offer.manager.displayName
            offerButton.text = "Use $manager's Elixir ${offer.elixir.version}" + (offer.versions.erlang?.let { " and Erlang ${it.version}" } ?: "")
            inStepBox.text = "Keep the SDKs in step with $manager"
            environmentBox.text = "Use $manager's environment for Mix and the build"
            environmentBox.isVisible = providesEnvironment(offer.manager)
        }
        errorMessage.text = error?.let { XmlStringUtil.wrapInHtml(it.description) }
    }

    private fun ticked() {
        state.tick(inStepBox.isSelected, environmentBox.isSelected)
        changed()
    }

    internal fun pick(entry: SdkChoiceEntry?) {
        state.select(entry)
        val selected = state.selected
        if (list.selectedValue != selected) list.setSelectedValue(selected, true)
        changed()
    }

    /** Shows the boxes as [state] has them, then tells [onPick]. */
    private fun changed() {
        inStepBox.isSelected = state.toolManagerBox
        environmentBox.isSelected = state.environmentBox
        inStepBox.isEnabled = state.optInsOpen
        environmentBox.isEnabled = state.environmentOpen
        onPick(state.pick)
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
