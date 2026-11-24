package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDialog
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileChooser.FileSaverDialog
import com.intellij.openapi.fileChooser.FileTextField
import com.intellij.openapi.fileChooser.PathChooserDialog
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.roots.ui.configuration.SdkComboBox
import com.intellij.openapi.roots.ui.configuration.SdkListItem
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.ComponentValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.replaceService
import com.intellij.ui.ComponentUtil
import com.intellij.util.EventDispatcher
import com.intellij.util.ui.update.UiNotifyConnector
import junit.framework.TestCase
import org.elixir_lang.cli.getExecutableFilepathWslSafe
import org.elixir_lang.jps.shared.cli.CliTool
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.sdk.elixir.ElixirVersions
import org.elixir_lang.sdk.elixir.OtpMajor
import org.elixir_lang.sdk.wsl.wslCompat
import java.awt.Component
import java.io.File
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTextField

/** An Erlang install at a new home, with an `erl` to run, its OTP version already read. */
internal fun install(major: String, otpVersion: String): String =
    SdkFixtures.erlangHomeWithEbin(major, otpVersion).also { home ->
        File(CliTool.ERL.getExecutableFilepathWslSafe(home)).let { erl ->
            check(erl.parentFile.mkdirs() && erl.createNewFile() && erl.setExecutable(true)) { "Failed to create $erl" }
        }
        SdkVersionsStore.getInstance().setOtpVersion(home, otpVersion)
    }

/** An Elixir install at a new home, built for OTP [otpMajor], its versions already read. */
internal fun elixirInstall(vsn: String, otpMajor: String): String =
    SdkFixtures.elixirHome(vsn).also {
        SdkVersionsStore.getInstance().setElixirVersions(it, ElixirVersions(vsn, OtpMajor.Known(otpMajor)))
    }

internal const val NO_ERLANG = "An Erlang SDK must be chosen."

/** The start of the hint an Elixir SDK page shows, with the plugin's own combo, to save and reopen after a pick. */
internal const val SAVE_HINT = "After changing the Erlang SDK"

/** The Erlang SDK [name] at a new OTP [otpVersion] install, registered in the table. */
internal fun registeredErlang(name: String, otpVersion: String): Sdk =
    SdkFixtures.register(SdkFixtures.erlangSdk(name, install(otpVersion.substringBefore('.'), otpVersion)))

/** The table's SDKs at [home]. */
internal fun tableSdksAt(home: String): List<Sdk> =
    ProjectJdkTable.getInstance().allJdks.filter { wslCompat.pathsEqualWslAware(home, it.homePath) }

/** [field] asks for an Erlang SDK: [NO_ERLANG] among [labels], the field's outline and the outline's popup. */
internal fun assertFieldAsksForAnErlang(field: JComponent, labels: List<JLabel>) {
    TestCase.assertTrue("the text under the field", noErlangShown(labels))
    TestCase.assertEquals("the field's outline", "error", field.getClientProperty("JComponent.outline"))
    TestCase.assertEquals("the outline's popup", NO_ERLANG, popupMessage(field))
}

/** None of [assertFieldAsksForAnErlang]'s asking shows. */
internal fun assertFieldDoesNotAskForAnErlang(field: JComponent, labels: List<JLabel>) {
    TestCase.assertFalse("the text under the field", noErlangShown(labels))
    TestCase.assertNull("the field's outline", field.getClientProperty("JComponent.outline"))
    TestCase.assertNull("the outline's popup", popupMessage(field))
}

private fun noErlangShown(labels: List<JLabel>): Boolean = labels.any { it.isVisible && it.text == NO_ERLANG }

private fun popupMessage(field: JComponent): String? = ComponentValidator.getInstance(field).orElse(null)?.validationInfo?.message

/** The model's copy [erlang] picked in [field], whichever field a page has: the platform's or the plugin's combo. */
internal fun pickInField(field: ComboBox<*>, erlang: Sdk) {
    field.selectedItem = if (field is SdkComboBox) field.model.listModel.findSdkItem(erlang)!! else erlang
}

/** The model's copy [field] shows, whichever field a page has: the platform's or the plugin's combo. */
internal fun shownInField(field: ComboBox<*>): Sdk? =
    when (val item = field.selectedItem) {
        is SdkListItem.SdkItem -> item.sdk
        is Sdk -> item
        else -> null
    }

/** [erlang] added to [model] and picked in [field], returning its copy. The platform's add rows pick inside `doAdd`'s callback. */
internal fun addAndPick(model: ProjectSdksModel, field: SdkComboBox, erlang: Sdk): Sdk {
    lateinit var copy: Sdk
    model.doAdd(erlang) { added ->
        copy = added
        field.reloadModel()
        pickInField(field, added)
    }
    return copy
}

/** As a dialog shows [component], a turn after building it. */
internal fun markShown(component: JComponent) {
    ComponentUtil.forceMarkAsShowing(component, true)
    UiNotifyConnector.forceNotifyIsShown(component)
    SdkFixtures.settle()
}

/** A dialog's model holding [sdks], as `reset` would from a table holding only them. */
internal fun model(vararg sdks: Sdk): ProjectSdksModel = ProjectSdksModel().apply { sdks.forEach(::addSdk) }

/** A model listing [sdks] in the order given, which `ProjectSdksModel`'s hash map does not. */
internal class ListedSdkModel(vararg sdks: Sdk) : SdkModel {
    private val sdks = sdks.toList()
    private val listeners = EventDispatcher.create(SdkModel.Listener::class.java)

    override fun getSdks(): Array<Sdk> = sdks.toTypedArray()
    override fun findSdk(sdkName: String?): Sdk? = sdks.find { it.name == sdkName }
    override fun addSdk(sdk: Sdk) = throw UnsupportedOperationException()
    override fun addListener(listener: SdkModel.Listener) = listeners.addListener(listener)
    override fun removeListener(listener: SdkModel.Listener) = listeners.removeListener(listener)
    override fun getMulticaster(): SdkModel.Listener = listeners.multicaster
}

/**
 * What the add and the deletion ask asked, in order: `"home"` for a home chooser, `"erlang"` for the Erlang step,
 * `"messages"` for a `Messages` question.
 */
internal class Asked {
    val events = mutableListOf<String>()

    /** Until [disposable] is disposed, a question asked through `Messages` is recorded and cancelled, so it fails rather than hangs. */
    fun recordMessages(disposable: Disposable) {
        TestDialogManager.setTestDialog({ events += "messages"; Messages.CANCEL }, disposable)
        val inputBefore = TestDialogManager.setTestInputDialog { events += "messages"; null }
        Disposer.register(disposable) { TestDialogManager.setTestInputDialog(inputBefore) }
    }
}

/** One answer of the step's dialog, from the entries it offers and where it can go back to another Elixir install. */
internal typealias StepAnswer = (entries: List<ErlangStepEntry>, chooseAnotherInstall: ChooseAnotherInstall?) -> ErlangStepAnswer

/**
 * The Erlang step with its dialog and its install scan replaced: [answers] answer the dialog in turn, from the entries it
 * was offered.
 */
internal class ScriptedErlangStep(
    private val asked: Asked,
    vararg answers: StepAnswer,
    private val installs: List<String> = emptyList(),
) {
    private val answers = ArrayDeque(answers.toList())
    val offered = mutableListOf<List<ErlangStepEntry>>()

    /** Replaces the IDE's step, until [disposable] is disposed, with one built on this script. */
    fun install(disposable: Disposable): ScriptedErlangStep = also {
        val step = ErlangStep({ installs }) { _, state, _, _, chooseAnotherInstall ->
            asked.events += "erlang"
            offered += state.entries
            answers.removeFirst()(state.entries, chooseAnotherInstall)
        }
        ApplicationManager.getApplication().replaceService(ErlangStep::class.java, step, disposable)
    }

    companion object {
        fun pick(sdkName: String): StepAnswer =
            { entries, _ -> ErlangStepAnswer.Picked(entries.single { it.sdk?.name == sdkName }) }

        fun pickInstall(home: String): StepAnswer =
            { entries, _ -> ErlangStepAnswer.Picked(entries.single { it.sdk == null && it.home == home }) }

        /** The link to choose another Elixir install, which the dialog shows only when it offers one. */
        fun back(otpMajor: Int?): StepAnswer = { _, chooseAnotherInstall ->
            checkNotNull(chooseAnotherInstall) { "the step offers no other Elixir install" }(otpMajor)
            ErlangStepAnswer.Cancelled
        }

        val cancel: StepAnswer = { _, _ -> ErlangStepAnswer.Cancelled }
    }
}

/** Every home chooser picks the next of [homes]; with none left it is cancelled. */
internal class ScriptedFileChooserFactory(private val asked: Asked, vararg homes: String) : FileChooserFactory() {
    private val homes = ArrayDeque(homes.toList())

    override fun createPathChooser(descriptor: FileChooserDescriptor, project: Project?, parent: Component?): PathChooserDialog =
        PathChooserDialog { _, callback ->
            asked.events += "home"
            homes.removeFirstOrNull()?.let { home ->
                callback.accept(listOf(LocalFileSystem.getInstance().refreshAndFindFileByPath(home)!!))
            }
        }

    override fun createFileChooser(descriptor: FileChooserDescriptor, project: Project?, parent: Component?): FileChooserDialog =
        throw UnsupportedOperationException()

    override fun createSaveFileDialog(descriptor: FileSaverDescriptor, project: Project?): FileSaverDialog =
        throw UnsupportedOperationException()

    override fun createSaveFileDialog(descriptor: FileSaverDescriptor, parent: Component): FileSaverDialog =
        throw UnsupportedOperationException()

    override fun createFileTextField(descriptor: FileChooserDescriptor, showHidden: Boolean, parent: Disposable?): FileTextField =
        throw UnsupportedOperationException()

    override fun installFileCompletion(field: JTextField, descriptor: FileChooserDescriptor, showHidden: Boolean, parent: Disposable?) =
        throw UnsupportedOperationException()

    fun install(disposable: Disposable): ScriptedFileChooserFactory =
        also { ApplicationManager.getApplication().replaceService(FileChooserFactory::class.java, it, disposable) }
}
