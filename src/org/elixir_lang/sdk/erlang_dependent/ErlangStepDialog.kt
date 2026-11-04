package org.elixir_lang.sdk.erlang_dependent

import com.intellij.icons.AllIcons
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import org.elixir_lang.sdk.elixir.OtpMatch
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Asks which Erlang an Elixir install runs on, from the [state]'s entries, for a new Elixir SDK or for the Elixir SDK
 * [asking] being re-paired. OK is enabled once an entry is picked; a pick built for another OTP is warned about. With
 * [chooseAnotherInstall], the warning also offers to choose another Elixir install, which closes the dialog and goes
 * there.
 */
class ErlangStepDialog(
    parent: Component?,
    internal val state: ErlangStepState,
    asking: Sdk?,
    private val chooseAnotherInstall: ChooseAnotherInstall?,
    private val browse: () -> String?,
) : DialogWrapper(null, parent, true, IdeModalityType.IDE) {
    private val list = JBList(state.listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = EntryRenderer()
        addListSelectionListener { event -> if (!event.valueIsAdjusting) pick(selectedValue) }
    }
    private val otpMismatchWarning = JBLabel().apply {
        icon = AllIcons.General.Warning
        isVisible = false
    }
    private val anotherInstallLink = ActionLink("Choose another Elixir install...") { close(ANOTHER_INSTALL_EXIT_CODE) }.apply {
        isVisible = false
    }

    init {
        if (asking == null) {
            title = "Erlang for Elixir SDK"
            setOKButtonText("Create")
        } else {
            title = "Erlang for Elixir SDK '${asking.name}'"
        }
        isOKActionEnabled = false
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        add(JBLabel("Choose the Erlang this Elixir install runs on:"), BorderLayout.NORTH)
        add(JBScrollPane(list).apply { preferredSize = JBUI.size(520, 200) }, BorderLayout.CENTER)
        add(
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(JButton("Browse...").apply { addActionListener { browsed() } }) })
                add(otpMismatchWarning)
                add(anotherInstallLink)
            },
            BorderLayout.SOUTH,
        )
    }

    override fun getPreferredFocusedComponent(): JComponent = list

    /** Shows the dialog, and answers once it is closed. */
    fun showAndPick(): ErlangStepAnswer {
        show()
        val selected = state.selected
        return when (exitCode) {
            OK_EXIT_CODE -> selected?.let(ErlangStepAnswer::Picked) ?: ErlangStepAnswer.Cancelled
            ANOTHER_INSTALL_EXIT_CODE -> {
                chooseAnotherInstall?.invoke((selected?.otpMatch as? OtpMatch.Mismatch)?.erlangOtpMajor?.toIntOrNull())
                ErlangStepAnswer.Cancelled
            }
            else -> ErlangStepAnswer.Cancelled
        }
    }

    internal fun pick(entry: ErlangStepEntry?) {
        state.select(entry)
        val selected = state.selected
        if (list.selectedValue != selected) list.setSelectedValue(selected, true)
        isOKActionEnabled = selected != null

        val mismatch = selected?.otpMatch as? OtpMatch.Mismatch
        otpMismatchWarning.text = mismatch?.warning
        otpMismatchWarning.isVisible = mismatch != null
        anotherInstallLink.isVisible = mismatch != null && chooseAnotherInstall != null
    }

    internal fun browsed() {
        val home = browse() ?: return
        pick(state.browsed(home))
    }

    internal val isOtpMismatchWarningShown: Boolean get() = otpMismatchWarning.isVisible
    internal val otpMismatchWarningText: String? get() = otpMismatchWarning.text
    internal val isAnotherInstallLinkShown: Boolean get() = anotherInstallLink.isVisible

    /** The text of the list's row for [entry], as its renderer draws it. */
    internal fun rowText(entry: ErlangStepEntry): String {
        val row = list.cellRenderer.getListCellRendererComponent(list, entry, 0, false, false) as SimpleColoredComponent
        return row.getCharSequence(false).toString()
    }

    private class EntryRenderer : ColoredListCellRenderer<ErlangStepEntry>() {
        override fun customizeCellRenderer(
            list: JList<out ErlangStepEntry>,
            value: ErlangStepEntry?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            value ?: return
            val main = if (value.pickable) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES
            append(value.sdk?.name ?: value.home, main)
            if (value.sdk == null) append("  install", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("  ")
            append(
                when (value.otpMatch) {
                    OtpMatch.Match -> "OTP ${value.otpVersion}, matches the build"
                    is OtpMatch.Mismatch -> "OTP ${value.otpVersion}, not the build's OTP"
                    OtpMatch.Unknown -> value.otpVersion?.let { "OTP $it" } ?: "OTP not read"
                },
                if (value.otpMatch is OtpMatch.Mismatch) SimpleTextAttributes.ERROR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES,
            )
            when (val unpickable = value.unpickable) {
                is Unpickable.PairedWith -> "already Elixir SDK '${unpickable.elixirSdkName}'"
                Unpickable.OnAnotherMachine -> "on another machine than the Elixir install"
                null -> null
            }?.let { append("  $it", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES) }
        }
    }

    private companion object {
        const val ANOTHER_INSTALL_EXIT_CODE = NEXT_USER_EXIT_CODE
    }
}
