package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.testFramework.common.runAll
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.wsl.wslCompat
import java.util.concurrent.Callable
import java.util.function.Consumer
import javax.swing.JPanel
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType
import org.elixir_lang.sdk.erlang.Type as ErlangSdkType

/** Adding an Elixir SDK through `ProjectSdksModel.doAdd`, as both families' SDK pages and every SDK combo's Add do. */
class ElixirSdkAddTest : PlatformTestCase() {
    private val asked = Asked()
    private lateinit var table: SdkFixtures.TableSnapshot
    private lateinit var elixirHome: String

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        elixirHome = elixirInstall("1.18.4", "27")
        asked.recordMessages(testRootDisposable)
    }

    override fun tearDown() = runAll(
        { table.restore() },
        { super.tearDown() },
    )

    fun testTheElixirInstallIsAskedBeforeTheErlang() {
        val model = model(registeredErlang("Add first Erlang", "27", "27.3.4"), registeredErlang("Add second Erlang", "26", "26.2.5"))
        script(ScriptedErlangStep.pick("Add second Erlang"))

        add(model)

        assertEquals(listOf("home", "erlang"), asked.events)
    }

    fun testOneRegisteredErlangIsStillAsked() {
        val model = model(registeredErlang("Add only Erlang", "27", "27.3.4"))
        val step = script(ScriptedErlangStep.pick("Add only Erlang"))

        add(model)

        assertEquals("one Erlang SDK is offered, not used silently", listOf("home", "erlang"), asked.events)
        assertEquals(listOf("Add only Erlang"), step.offered.single().mapNotNull { it.sdk?.name })
    }

    fun testNoErlangSdkOffersInstallsInTheStep() {
        val install = install("28", "28.1")
        val step = script(ScriptedErlangStep.pickInstall(install), installs = listOf(install))

        add(model())

        assertEquals(listOf("home", "erlang"), asked.events)
        assertEquals(listOf(install), step.offered.single().map { it.home })
    }

    fun testAnInstallPickedIsInTheTableAndTheModel() {
        val install = install("28", "28.1")
        script(ScriptedErlangStep.pickInstall(install), installs = listOf(install))
        val model = model()

        add(model)

        val registered = tableErlangSdksAt(install).single()
        assertTrue("the model holds the registered SDK, so its Apply updates rather than adds it", registered in model.projectSdks.keys)
    }

    fun testTheNewSdkResolvesItsErlangWithNoModel() {
        val install = install("28", "28.1")
        script(ScriptedErlangStep.pickInstall(install), installs = listOf(install))

        val elixirSdk = addedSdk(model())

        // The wizard's, the import step's and the banner's combos look the pairing up with no model.
        val resolved = ReadAction.nonBlocking(Callable { ErlangSdkResolver.getInstance().resolveErlangSdk(elixirSdk, null) })
            .executeSynchronously()
        assertSame(tableErlangSdksAt(install).single(), resolved)
    }

    fun testApplyingTheModelKeepsOneInstallPickedErlang() {
        val install = install("28", "28.1")
        script(ScriptedErlangStep.pickInstall(install), installs = listOf(install))
        val model = model()
        add(model)

        model.apply()

        assertEquals("applying updates the Erlang SDK already in the table", 1, tableErlangSdksAt(install).size)
    }

    fun testASecondAddInOneDialogCannotPickTheFirstsErlang() {
        val model = model(registeredErlang("Add shared Erlang", "27", "27.3.4"))
        val step = script(ScriptedErlangStep.pick("Add shared Erlang"), ScriptedErlangStep.cancel, homes = listOf(elixirHome, elixirHome))
        val first = addedSdk(model)

        add(model)

        assertEquals(
            "the first add is only in the dialog's model, not yet in the table",
            Unpickable.PairedWith(first.name),
            step.offered.last().single { it.sdk?.name == "Add shared Erlang" }.unpickable,
        )
    }

    fun testThePairingIsThePick() {
        val picked = registeredErlang("Add picked Erlang", "26", "26.2.5")
        val model = model(registeredErlang("Add unpicked Erlang", "27", "27.3.4"), picked)
        script(ScriptedErlangStep.pick("Add picked Erlang"))

        val data = addedSdk(model).sdkAdditionalData as SdkAdditionalData

        assertEquals("Add picked Erlang", data.getErlangSdkName())
        assertTrue(wslCompat.pathsEqualWslAware(picked.homePath, data.getErlangSdkHomePath()))
    }

    fun testCancelAtTheErlangStepCreatesNothing() {
        val install = install("28", "28.1")
        script(ScriptedErlangStep.cancel, installs = listOf(install))
        val model = model(registeredErlang("Add cancelled Erlang", "27", "27.3.4"))
        val modelBefore = model.projectSdks.keys.toSet()

        assertNull("the callback is not called", add(model))

        assertEquals(listOf("home", "erlang"), asked.events)
        assertEquals(modelBefore, model.projectSdks.keys.toSet())
        assertEmpty(tableErlangSdksAt(install))
    }

    fun testAMismatchedPickOffersAnotherElixirInstall() {
        val otp26Elixir = elixirInstall("1.17.3", "26")
        val model = model(registeredErlang("Add OTP 26 Erlang", "26", "26.2.5"))
        script(ScriptedErlangStep.back(26), ScriptedErlangStep.pick("Add OTP 26 Erlang"), homes = listOf(elixirHome, otp26Elixir))

        val elixirSdk = addedSdk(model)

        assertEquals("the link goes back to the Elixir install", listOf("home", "erlang", "home", "erlang"), asked.events)
        assertTrue(wslCompat.pathsEqualWslAware(otp26Elixir, elixirSdk.homePath))
        assertEquals("Add OTP 26 Erlang", (elixirSdk.sdkAdditionalData as SdkAdditionalData).getErlangSdkName())
    }

    fun testCancellingTheElixirSetupProgressLeavesTheErlangRegisteredAndUnpaired() {
        val install = install("28", "28.1")
        script(ScriptedErlangStep.pickInstall(install), installs = listOf(install))
        val model = model()

        // What the platform's callback does when its "Configuring SDK" progress is cancelled: adds nothing.
        ElixirSdkType.instance.showCustomCreateUI(model, JPanel(), null, Consumer {})

        val registered = tableErlangSdksAt(install).single()
        assertTrue(registered in model.projectSdks.keys)
        assertTrue("no Elixir SDK was added", model.sdks.none { it.sdkType is ElixirSdkType })
    }

    /** The step answering [answers] with [installs] found, and every home chooser picking the next of [homes]. */
    private fun script(
        vararg answers: StepAnswer,
        installs: List<String> = emptyList(),
        homes: List<String> = listOf(elixirHome),
    ): ScriptedErlangStep {
        ScriptedFileChooserFactory(asked, *homes.toTypedArray()).install(testRootDisposable)
        return ScriptedErlangStep(asked, *answers, installs = installs).install(testRootDisposable)
    }

    /** The SDK the platform's callback was handed, or null when it was not called. */
    private fun add(model: ProjectSdksModel): Sdk? {
        var added: Sdk? = null
        model.doAdd(JPanel(), ElixirSdkType.instance) { added = it }
        return added
    }

    private fun addedSdk(model: ProjectSdksModel): Sdk = add(model) ?: throw AssertionError("the callback was handed no Elixir SDK")

    private fun registeredErlang(name: String, major: String, otpVersion: String): Sdk {
        val home = install(major, otpVersion)
        return SdkFixtures.register(SdkFixtures.erlangSdk(name, home))
    }

    private fun tableErlangSdksAt(home: String): List<Sdk> =
        ProjectJdkTable.getInstance().getSdksOfType(ErlangSdkType.instance).filter { wslCompat.pathsEqualWslAware(it.homePath, home) }
}
