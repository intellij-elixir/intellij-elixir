package org.elixir_lang.new_project_wizard

import com.intellij.ide.JavaUiBundle
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.observable.properties.ObservableProperty
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.common.runAll
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.ui.dsl.builder.panel
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.SdkDetectionContext
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.storedErlangName
import org.elixir_lang.sdk.choice.FakeToolManager
import org.elixir_lang.sdk.choice.SdkChoicePanel
import org.elixir_lang.sdk.erlang_dependent.Asked
import org.elixir_lang.sdk.erlang_dependent.ScriptedErlangStep
import org.elixir_lang.sdk.erlang_dependent.ScriptedFileChooserFactory
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService
import java.nio.file.Path

/** The New Project and New Module wizards' "Elixir SDK:" row, asked as the wizard asks it, with no project. */
class SdkChoiceRowTest : PlatformTestCase() {
    private lateinit var table: SdkFixtures.TableSnapshot
    private val propertyGraph = PropertyGraph()
    private val sdkProperty = propertyGraph.property<Sdk?>(null)
    private lateinit var wizardDisposable: com.intellij.openapi.Disposable
    private lateinit var dialogPanel: DialogPanel
    private lateinit var chooser: SdkChoicePanel

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        // The row asks the tool managers about its location, and a real mise there answers from this machine's config.
        FakeToolManager().install(testRootDisposable)
    }

    override fun tearDown() = runAll(
        { SdkDetectionContext.clear() },
        { table.restore() },
        { super.tearDown() },
    )

    fun testNothingIsSelectedWhenTheTableHasAnElixirModel() {
        paired("Row Erlang")

        row()

        assertNull(sdkProperty.get())
    }

    fun testAPairingCommittedWhileTheStepIsOpenSurvivesItsValidation() {
        val elixir = paired("Row Erlang 1")
        val repaired = erlang("Row Erlang 2")
        row()
        pick(ELIXIR)

        SdkFixtures.commitPairing(elixir, repaired)
        validate()

        assertEquals("Row Erlang 2", storedErlangName(elixir))
    }

    fun testAPickRemovedFromTheTableWhileTheStepIsOpenIsNotAddedBack() {
        val elixir = paired("Row Erlang")
        row()
        pick(ELIXIR)

        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(elixir) }
        SdkFixtures.settle()
        validate()

        assertNull(ProjectJdkTable.getInstance().findJdk(ELIXIR))
    }

    fun testCreateIsRefusedWithNoPick() {
        row()

        assertEquals(listOf(JavaUiBundle.message("title.no.jdk.specified")), validate())
    }

    fun testCreateIsRefusedForAnUnpairedPick() {
        SdkFixtures.register(SdkFixtures.elixirSdk("Row unpaired", elixirInstall("1.18.4", "27")), testRootDisposable)
        row()
        pick("Row unpaired")

        assertEquals(listOf("Internal Erlang SDK is not configured. Set it before picking this Elixir SDK."), validate())
    }

    fun testTheSdkAddMakesIsThePickCreateTakes() {
        erlang("Row Erlang")
        val asked = Asked()
        ScriptedFileChooserFactory(asked, elixirInstall("1.18.4", "27")).install(testRootDisposable)
        ScriptedErlangStep(asked, ScriptedErlangStep.pick("Row Erlang")).install(testRootDisposable)
        val before = ProjectJdkTable.getInstance().allJdks.toSet()
        row()

        chooser.addButton.doClick()

        val made = ProjectJdkTable.getInstance().allJdks.single { it !in before }
        assertSame(made, sdkProperty.get())
        assertEquals("Row Erlang", storedErlangName(made))
        assertEmpty(validate())
    }

    fun testALocationIsPublishedForDetectionUntilTheWizardCloses() {
        val location = propertyGraph.property("")
        row(location)
        assertNull("a blank location publishes nothing", SdkDetectionContext.resolve())

        val first = FileUtil.createTempDirectory("newProject", null).path
        location.set(first)
        assertEquals(Path.of(first), SdkDetectionContext.resolve())

        val second = FileUtil.createTempDirectory("newProject", null).path
        location.set(second)
        assertEquals(Path.of(second), SdkDetectionContext.resolve())

        Disposer.dispose(wizardDisposable)
        assertNull("closing the wizard clears it", SdkDetectionContext.resolve())
    }

    fun testALocationChangeLooksUpTheNewLocationsEnvironment() {
        val lookedUp = mutableListOf<String>()
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            object : WslCompatService by MockWslCompatService() {
                override fun String.canonicalizeWslPrefix(): String = also { lookedUp += it }
            },
            testRootDisposable,
        )
        val location = propertyGraph.property(FileUtil.createTempDirectory("newProject", null).path)
        row(location)

        val moved = FileUtil.createTempDirectory("newProject", null).path
        location.set(moved)

        // Off WSL every location is local, so the list cannot show the move; the lookup the list follows can.
        assertContainsElements(lookedUp.map(FileUtil::toSystemIndependentName), FileUtil.toSystemIndependentName(moved))
    }

    private fun erlang(name: String): Sdk = SdkFixtures.register(SdkFixtures.erlangSdk(name, install("27", "27.3.4")), testRootDisposable)

    /** The Elixir SDK [ELIXIR] in the table, paired with a new Erlang SDK named [erlangName] at a home that exists. */
    private fun paired(erlangName: String): Sdk {
        val elixir = SdkFixtures.register(SdkFixtures.elixirSdk(ELIXIR, elixirInstall("1.18.4", "27")), testRootDisposable)
        return SdkFixtures.commitPairing(elixir, erlang(erlangName))
    }

    private fun row(location: ObservableProperty<String> = propertyGraph.property("")) {
        wizardDisposable = Disposer.newDisposable(testRootDisposable)
        val context = WizardContext(null, wizardDisposable)
        dialogPanel = panel { row { chooser = elixirSdkChooser(context, sdkProperty, location).component } }
            .apply { registerValidators(wizardDisposable) }
    }

    private fun pick(name: String) {
        chooser.pick(chooser.state.entries.single { it.sdk.name == name })
    }

    private fun validate(): List<String> = dialogPanel.validateAll().map { it.message }

    private companion object {
        const val ELIXIR = "Row A"
    }
}
