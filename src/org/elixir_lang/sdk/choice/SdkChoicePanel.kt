package org.elixir_lang.sdk.choice

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
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
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.JBUI
import com.intellij.xml.util.XmlStringUtil
import org.elixir_lang.sdk.elixir.SdkSettingsOpener
import org.elixir_lang.sdk.elixir.SettingsPage
import org.elixir_lang.sdk.erlang_dependent.rowText
import org.elixir_lang.tool_manager.ToolManagerVersions
import org.elixir_lang.tool_manager.providesEnvironment
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.nio.file.Path
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.Timer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** "Manage SDKs...", which opens the SDK pages: Project Structure > SDKs, or Settings > Elixir > SDKs on the small IDEs. */
internal fun manageSdksLink() = AnActionLink("Manage SDKs...", object : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = SdkSettingsOpener.getInstance().open(e, SettingsPage.SDKS)
})

/** The tool manager's box: "Keep the SDKs in step with mise". */
internal fun inStepText(manager: String) = "Keep the SDKs in step with $manager"

/** Its environment's box: "Use mise's environment for Mix and the build". */
internal fun environmentText(manager: String) = "Use $manager's environment for Mix and the build"

/** "mise's Elixir 1.20.4 and Erlang 29.0.6": the tool manager [manager] and the versions [pins] names. */
internal fun pinsText(manager: String, pins: ToolManagerVersions) =
    "$manager's Elixir ${pins.elixir?.version}" + (pins.erlang?.let { " and Erlang ${it.version}" } ?: "")

/**
 * The chooser's list of [state]'s Elixir SDKs, each with its pairing, with nothing selected, Add... and Manage SDKs...,
 * following the table until [parentDisposable] goes. When a tool manager pins an Elixir for [state]'s folders, a first
 * radio offers it, with boxes that would turn the manager on, and a second radio stands for the list. [onPick] hears each
 * change of the pick or its opt-ins, null included.
 */
class SdkChoicePanel(
    internal val state: SdkChoiceState,
    parentDisposable: Disposable,
    private val onPick: (SdkChoicePick?) -> Unit,
) : JPanel(BorderLayout(0, JBUI.scale(8))) {
    private val list = JBList(state.listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = EntryRenderer()
        // A selection the state already has is the state's own change, not the user's.
        addListSelectionListener { event -> if (!event.valueIsAdjusting && selectedValue != state.selected) pick(selectedValue) }
    }

    internal val addButton = JButton("Add...").apply { addActionListener { state.add(list)?.let(::pick) } }

    internal val manageSdksLink = manageSdksLink()

    private val offerRadio = JBRadioButton().apply {
        addActionListener {
            // Cancelling the progress throws, with this radio already selected by its group.
            try {
                state.take(this@SdkChoicePanel)
            } finally {
                showSelection()
            }
        }
    }

    private val anotherRadio = JBRadioButton("Use another SDK:").apply {
        addActionListener {
            state.chooseAnother()
            showSelection()
        }
    }

    private val radioGroup = ButtonGroup().apply {
        add(offerRadio)
        add(anotherRadio)
    }

    private val inStepBox = JBCheckBox().apply { addActionListener { ticked() } }
    private val environmentBox = JBCheckBox().apply { addActionListener { ticked() } }
    @Suppress("DialogTitleCapitalization") // Names the Settings pages as they are titled.
    private val settingsPage = JBLabel("Change these in Settings > Languages & Frameworks > Elixir > Tool Managers.").apply { isVisible = !state.hostCommits }
    private val boxes = JPanel(VerticalLayout(0)).apply {
        isVisible = false
        border = JBUI.Borders.emptyLeft(20)
        add(inStepBox)
        add(environmentBox.apply { border = JBUI.Borders.emptyLeft(20) })
        add(settingsPage)
    }

    private val severalLine = JBLabel("Several SDKs use this Elixir. Pick one in the list.").apply {
        isVisible = false
        border = JBUI.Borders.emptyLeft(20)
    }

    private val radios = JPanel(VerticalLayout(JBUI.scale(4))).apply {
        isVisible = false
        add(offerRadio)
        add(boxes)
        add(severalLine)
        add(anotherRadio)
    }

    private val errorMessage = JBLabel()
    private val errorRow = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(8), 0)).apply {
        isVisible = false
        add(errorMessage)
        add(ActionLink("Check again") { check() })
    }

    // Shown only when the check takes long enough to be noticed.
    private val checkingNotice = Timer(CHECKING_NOTICE_MILLIS) { showChecking() }.apply { isRepeats = false }

    init {
        Disposer.register(parentDisposable) { checkingNotice.stop() }
        add(radios, BorderLayout.NORTH)
        add(JBScrollPane(list).apply { preferredSize = JBUI.size(520, 200) }, BorderLayout.CENTER)
        add(JPanel(VerticalLayout(JBUI.scale(8))).apply {
            add(JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(8), 0)).apply {
                add(addButton)
                add(manageSdksLink)
            })
            add(errorRow)
        }, BorderLayout.SOUTH)
        // As Settings or the create flow change them.
        followTableAt(parentDisposable, list) { reload() }
        check()
    }

    /** The component to focus first. */
    val preferredFocusedComponent get() = list

    private fun reload() {
        state.reload()
        showSelection()
    }

    /** Lists the Elixir SDKs of [target]'s environment instead, and asks the tool managers about [folders]. */
    @RequiresEdt
    @Suppress("UnstableApiUsage") // EelDescriptor is the platform's only name for an environment.
    fun retarget(target: EelDescriptor?, folders: List<Path>) {
        val refolded = state.retarget(target, folders)
        showSelection()
        if (refolded) {
            show(null)
            // A location typed in changes with each key: its check starts once the typing pauses, so one the next key
            // drops never runs a tool manager.
            check(after = 300.milliseconds)
        }
    }

    private fun check(after: Duration = Duration.ZERO) {
        checkingNotice.restart()
        state.check(list, after) { answer ->
            checkingNotice.stop()
            show(answer)
            showSelection()
        }
    }

    /** A disabled first radio says the check is still running, beside the second. */
    private fun showChecking() {
        offerRadio.text = "Checking mise..."
        offerRadio.isEnabled = false
        radios.isVisible = true
    }

    /** Shows [answer]'s offer and boxes, or its error, or neither. */
    private fun show(answer: ToolManagerCheck?) {
        val offer = answer as? ToolManagerCheck.Offer
        val error = answer as? ToolManagerCheck.Error
        offerRadio.isEnabled = true
        radios.isVisible = offer != null
        boxes.isVisible = offer != null
        errorRow.isVisible = error != null
        if (offer != null) {
            val manager = offer.manager.displayName
            offerRadio.text = "Use ${pinsText(manager, offer.versions)}"
            inStepBox.text = inStepText(manager)
            environmentBox.text = environmentText(manager)
            environmentBox.isVisible = providesEnvironment(offer.manager)
        }
        errorMessage.text = error?.let { XmlStringUtil.wrapInHtml(it.description) }
    }

    private fun ticked() {
        state.tick(inStepBox.isSelected, environmentBox.isSelected)
        showSelection()
    }

    /** The user's pick in the list, which moves the radio. */
    internal fun pick(entry: SdkChoiceEntry?) {
        state.choose(entry)
        showSelection()
    }

    /** Shows the pick, radios and boxes as [state] has them, then tells [onPick]. */
    private fun showSelection() {
        val selected = state.selected
        if (list.selectedValue != selected) list.setSelectedValue(selected, true)
        when {
            state.offerChosen -> offerRadio.isSelected = true
            state.anotherChosen -> anotherRadio.isSelected = true
            else -> radioGroup.clearSelection()
        }
        severalLine.isVisible = state.offerChosen && selected == null
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

    private companion object {
        const val CHECKING_NOTICE_MILLIS = 300
    }
}
