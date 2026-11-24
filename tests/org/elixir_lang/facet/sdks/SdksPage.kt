package org.elixir_lang.facet.sdks

import com.intellij.openapi.options.ex.ConfigurableCardPanel
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ui.configuration.SdkComboBox
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.UsefulTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextField
import org.elixir_lang.facet.SdksService
import org.elixir_lang.facet.sdk.Editor
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.erlang_dependent.SAVE_HINT
import org.elixir_lang.sdk.erlang_dependent.addAndPick
import org.elixir_lang.sdk.erlang_dependent.assertFieldAsksForAnErlang
import org.elixir_lang.sdk.erlang_dependent.assertFieldDoesNotAskForAnErlang
import org.elixir_lang.sdk.erlang_dependent.markShown
import org.elixir_lang.sdk.erlang_dependent.pickInField
import org.elixir_lang.sdk.erlang_dependent.shownInField

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
        return shownEditor
    }

    private val shownEditor: Editor get() = UIUtil.findComponentOfType(root, ConfigurableCardPanel::class.java)!!.key as Editor

    /**
     * Selects [copy] and shows its editor's Erlang SDK section, as the dialog does once the editor is on screen. Only that
     * section: the path editors' tabs need a window to be shown in.
     */
    fun view(copy: ProjectJdkImpl) {
        markShown(labels(copy).single { it.text == "Internal Erlang SDK:" }.parent as JComponent)
    }

    /** Selects [sdk] and browses its editor to [home], as the home chooser's callback does. */
    fun browse(sdk: Sdk, home: String) = select(sdk).doSetHomePath(home, sdk.sdkType as SdkType)

    /** Selects [sdk] and types [name] into its editor's Name field. */
    fun rename(sdk: Sdk, name: String) = rename(copy(sdk), name)

    /** Selects [copy] and types [name] into its editor's Name field. */
    fun rename(copy: ProjectJdkImpl, name: String) {
        nameField(copy).text = name
    }

    /** What [copy]'s editor's Name field shows. */
    fun nameShown(copy: ProjectJdkImpl): String = nameField(copy).text

    private fun nameField(copy: ProjectJdkImpl): JTextField {
        val label = labels(copy).firstOrNull { it.text == "Name:" } ?: throw AssertionError("the editor has no Name field")
        return label.labelFor as JTextField
    }

    /** Clicks the shown editor's "Suppress OTP version mismatch warning" checkbox. */
    fun clickSuppressOtpWarning() =
        UIUtil.findComponentsOfType(shownEditor.createComponent(), JBCheckBox::class.java)
            .single { it.text == "Suppress OTP version mismatch warning" }
            .doClick()

    /** Selects the Elixir SDK [sdk] and picks [erlang]'s copy in its editor's Erlang SDK field. */
    fun pickErlang(sdk: Sdk, erlang: Sdk) = pickErlang(copy(sdk), copy(erlang))

    /** Selects the Elixir SDK copy [copy] and picks the Erlang SDK copy [erlang] in its editor's field, whichever it has. */
    fun pickErlang(copy: ProjectJdkImpl, erlang: Sdk) = pickInField(erlangField(copy), erlang)

    /** [copy]'s editor's Erlang SDK field. */
    fun erlangField(copy: ProjectJdkImpl): ComboBox<*> =
        UIUtil.findComponentOfType(show(copy).createComponent(), ComboBox::class.java)!!

    /** [copy]'s editor's Erlang SDK field, the platform's, as Project Structure's page has. */
    fun platformErlangField(copy: ProjectJdkImpl): SdkComboBox =
        UsefulTestCase.assertInstanceOf(erlangField(copy), SdkComboBox::class.java)

    /** The Erlang SDK copy [copy]'s editor's field shows. */
    fun shownErlang(copy: ProjectJdkImpl): Sdk? = shownInField(erlangField(copy))

    /**
     * The Erlang SDK [name] at [home] added to the shared model through [copy]'s platform Erlang SDK field and picked there, as its Add and
     * Detected rows do: they pick inside `doAdd`'s callback, before the model's listeners hear of the add. Returns its
     * copy.
     */
    fun addErlangInField(copy: ProjectJdkImpl, name: String, home: String): ProjectJdkImpl =
        addAndPick(SdksService.getInstance()!!.getModel(), platformErlangField(copy), SdkFixtures.erlangSdk(name, home)) as ProjectJdkImpl

    private fun labels(copy: ProjectJdkImpl): kotlin.collections.List<JLabel> =
        UIUtil.findComponentsOfType(show(copy).createComponent(), JLabel::class.java)

    /** Whether [copy]'s editor shows the hint to save and reopen after a pick, as the plugin's own combo does. */
    fun showsSaveHint(copy: ProjectJdkImpl): Boolean =
        labels(copy).any { it.isVisible && it.text.startsWith(SAVE_HINT) }

    /** [copy]'s editor asks for an Erlang SDK: the text under its field, the field's outline and the outline's popup. */
    fun assertAsksForAnErlang(copy: ProjectJdkImpl) = assertFieldAsksForAnErlang(erlangField(copy), labels(copy))

    fun assertDoesNotAskForAnErlang(copy: ProjectJdkImpl) = assertFieldDoesNotAskForAnErlang(erlangField(copy), labels(copy))

    /** Selects [sdk] and adds [path] to its editor's Classes list, as that list's Add button does. */
    fun addClassPath(sdk: Sdk, path: VirtualFile) = select(sdk).pathEditorFor(OrderRootType.CLASSES)!!.addPaths(path)

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
