package org.elixir_lang.facet.sdks

import com.intellij.openapi.options.ex.ConfigurableCardPanel
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import javax.swing.JLabel
import javax.swing.JTextField
import org.elixir_lang.facet.SdksService
import org.elixir_lang.facet.sdk.Editor

/** One SDKs page, as a Settings tab: built and reset, then closed by [cancel]. [open] holds it while it is open. */
internal class SdksPage(private val configurable: Configurable, private val open: MutableCollection<SdksPage>) {
    val root = configurable.createComponent()!!

    init {
        configurable.reset()
        open += this
    }

    private val list: List get() = UIUtil.findComponentOfType(root, List::class.java)!!

    fun listedSdks(): kotlin.collections.List<ProjectJdkImpl> = (0 until list.model.size).map(list.model::getElementAt)

    fun listed(): kotlin.collections.List<String> = listedSdks().map { it.name }

    val selected: ProjectJdkImpl? get() = list.selectedValue

    /** Selects [sdk]'s copy, which builds and shows its editor, and returns that editor. */
    fun select(sdk: Sdk): Editor = show(copy(sdk))

    /** Selects [copy], which builds and shows its editor, and returns that editor. */
    fun show(copy: ProjectJdkImpl): Editor {
        list.setSelectedValue(copy, false)
        return UIUtil.findComponentOfType(root, ConfigurableCardPanel::class.java)!!.key as Editor
    }

    /** Selects [sdk] and browses its editor to [home], as the home chooser's callback does. */
    fun browse(sdk: Sdk, home: String) = select(sdk).doSetHomePath(home, sdk.sdkType as SdkType)

    /** Selects [sdk] and types [name] into its editor's Name field. */
    fun rename(sdk: Sdk, name: String) {
        val label = UIUtil.findComponentsOfType(select(sdk).createComponent(), JLabel::class.java)
            .firstOrNull { it.text == "Name:" }
            ?: throw AssertionError("the editor has no Name field")
        (label.labelFor as JTextField).text = name
    }

    /** Clicks the shown editor's "Suppress OTP version mismatch warning" checkbox. */
    fun clickSuppressOtpWarning() =
        UIUtil.findComponentsOfType(root, JBCheckBox::class.java)
            .single { it.text == "Suppress OTP version mismatch warning" }
            .doClick()

    /** Selects the Elixir SDK [sdk] and picks [erlang]'s copy in its editor's Erlang SDK combo. */
    fun pickErlang(sdk: Sdk, erlang: Sdk) {
        select(sdk)
        UIUtil.findComponentOfType(root, ComboBox::class.java)!!.selectedItem = copy(erlang)
    }

    fun remove(sdk: Sdk) = configurable.removeSelectedSdk(copy(sdk))

    val isModified: Boolean get() = configurable.isModified

    fun apply() = configurable.apply()

    fun cancel() {
        open -= this
        configurable.disposeUIResources()
    }

    companion object {
        /** [sdk]'s copy in the shared model, found by the table SDK it is keyed by. */
        fun copy(sdk: Sdk): ProjectJdkImpl = SdksService.getInstance()!!.getModel().projectSdks[sdk] as ProjectJdkImpl
    }
}
