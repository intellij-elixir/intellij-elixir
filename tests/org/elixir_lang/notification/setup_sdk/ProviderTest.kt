package org.elixir_lang.notification.setup_sdk

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.runAll
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.storedErlangHome
import org.elixir_lang.sdk.SdkFixtures.storedErlangName
import org.elixir_lang.sdk.elixir.ElixirInternalErlangSdkSetup
import org.elixir_lang.sdk.erlang_dependent.Asked
import org.elixir_lang.sdk.erlang_dependent.ErlangSdkResolver
import org.elixir_lang.sdk.erlang_dependent.ScriptedErlangStep
import org.elixir_lang.sdk.erlang_dependent.StepAnswer
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install
import org.elixir_lang.sdk.wsl.wslCompat
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType

/**
 * The banner's project popup: a pick of a paired SDK sets the project SDK; a pick of an unpaired one asks for its
 * Erlang SDK first, and Cancel writes nothing. Each row has a broken neighbour in the table beside it.
 */
class ProviderTest : PlatformTestCase() {
    private val asked = Asked()
    private lateinit var table: SdkFixtures.TableSnapshot
    private var projectSdkBefore: Sdk? = null

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        asked.recordMessages(testRootDisposable)
        projectSdkBefore = ProjectRootManager.getInstance(project).projectSdk
        // Its pairing never resolves, so a plain `apply()` of a model holding it throws.
        unpairedTableSdk(
            "Popup broken neighbour Elixir",
            SdkFixtures.fakeHome("erlang/broken-neighbour"),
            elixirInstall("1.17.3", "26"),
        )
    }

    override fun tearDown() = runAll(
        { WriteAction.run<Throwable> { ProjectRootManager.getInstance(project).projectSdk = projectSdkBefore } },
        { table.restore() },
        { super.tearDown() },
    )

    fun testAPairedTableSdksPickSetsTheProjectSdk() {
        val erlang = registeredErlang("Popup paired Erlang", install("27", "27.3.4"))
        val elixir = SdkFixtures.commitPairing(
            SdkFixtures.register(SdkFixtures.elixirSdk("Popup paired Elixir", elixirInstall("1.18.4", "27"))),
            erlang,
        )
        val model = popupModel()

        selectProjectSdk(project, model, model.findSdk(elixir.name)!!)

        assertEmpty(asked.events)
        assertEquals(elixir.name, projectSdkName())
    }

    fun testANewUnpairedSdksPickAsksAndIsCommittedPaired() {
        val erlang = registeredErlang("Popup new Erlang", install("27", "27.3.4"))
        val model = popupModel()
        script(ScriptedErlangStep.pick(erlang.name))
        var added: Sdk? = null
        model.addSdk(ElixirSdkType.instance, elixirInstall("1.18.4", "27")) { added = it }
        val picked = added ?: throw AssertionError("precondition: the SDK is added")

        selectProjectSdk(project, model, picked)

        assertEquals(listOf("erlang"), asked.events)
        val committed = ProjectJdkTable.getInstance().findJdk(picked.name) ?: throw AssertionError("the pick is committed")
        assertEquals(erlang.name, storedErlangName(committed))
        assertEquals(committed.name, projectSdkName())
    }

    fun testAnUnpairedTableSdkIsAskedWithItsOldInstallOfferedAndRepairedToThePick() {
        val oldInstall = install("27", "27.3.4")
        val elixir = unpairedTableSdk("Popup repaired Elixir", oldInstall)
        val pick = registeredErlang("Popup repaired pick Erlang", install("26", "26.2.5"))
        val step = script(ScriptedErlangStep.pick(pick.name), installs = listOf(oldInstall))
        val model = popupModel()

        selectProjectSdk(project, model, model.findSdk(elixir.name)!!)

        val offeredOld = step.offered.single().single { wslCompat.pathsEqualWslAware(it.home, oldInstall) }
        assertNull("the deleted Erlang SDK's install is pickable", offeredOld.unpickable)
        val inTable = ProjectJdkTable.getInstance().findJdk(elixir.name)!!
        assertTrue(wslCompat.pathsEqualWslAware(pick.homePath, storedErlangHome(inTable)))
        assertEquals(elixir.name, projectSdkName())
    }

    fun testCancelForAnUnpairedTableSdkLeavesItInTheTableUnpaired() {
        val oldInstall = install("27", "27.3.4")
        val elixir = unpairedTableSdk("Popup cancelled Elixir", oldInstall)
        script(ScriptedErlangStep.cancel, installs = listOf(oldInstall))
        val model = popupModel()

        selectProjectSdk(project, model, model.findSdk(elixir.name)!!)

        val inTable = ProjectJdkTable.getInstance().findJdk(elixir.name) ?: throw AssertionError("still in the table")
        assertNull("still unpaired", ErlangSdkResolver.getInstance().resolveErlangSdk(inTable))
        assertEquals(projectSdkBefore?.name, projectSdkName())
    }

    /** A Cancel for a table SDK's copy writes nothing, even to a model that is applied afterwards. */
    fun testCancelForATableSdksCopyLeavesNothingForAnApplyToDelete() {
        val oldInstall = install("27", "27.3.4")
        val elixir = unpairedTableSdk("Popup cancelled applied Elixir", oldInstall)
        script(ScriptedErlangStep.cancel, installs = listOf(oldInstall))
        val model = popupModel()

        assertNull(ElixirInternalErlangSdkSetup.erlangSdkOrAsk(model, model.findSdk(elixir.name)!!))
        model.apply(null, true)

        val inTable = ProjectJdkTable.getInstance().findJdk(elixir.name) ?: throw AssertionError("still in the table")
        assertNull("still unpaired", ErlangSdkResolver.getInstance().resolveErlangSdk(inTable))
    }

    /**
     * A table Elixir SDK left as deleting its Erlang SDK leaves it: its pairing stores that SDK's name and
     * [erlangInstall], which no Erlang SDK now has.
     */
    private fun unpairedTableSdk(name: String, erlangInstall: String, elixirHome: String = elixirInstall("1.18.4", "27")): Sdk =
        SdkFixtures.commitPairing(
            SdkFixtures.register(SdkFixtures.elixirSdk(name, elixirHome)),
            "$name's deleted Erlang",
            erlangInstall,
        )

    private fun registeredErlang(name: String, home: String): Sdk = SdkFixtures.register(SdkFixtures.erlangSdk(name, home))

    private fun script(vararg answers: StepAnswer, installs: List<String> = emptyList()): ScriptedErlangStep =
        ScriptedErlangStep(asked, *answers, installs = installs).install(testRootDisposable)

    /** The popup's own model, reset as the popup resets it when shown. */
    private fun popupModel(): ProjectSdksModel =
        ProjectSdksModel().apply {
            reset(project)
            Disposer.register(testRootDisposable) { disposeUIResources() }
        }

    private fun projectSdkName(): String? = ProjectRootManager.getInstance(project).projectSdk?.name
}
