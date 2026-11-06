package org.elixir_lang.tool_manager

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.testFramework.common.runAll
import org.elixir_lang.jps.shared.ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.storedErlangHome
import org.elixir_lang.sdk.SdkFixtures.storedErlangName
import org.elixir_lang.sdk.choice.RecordedChooser
import org.elixir_lang.sdk.elixir.ElixirSdkLookup
import org.elixir_lang.sdk.elixir.sdk
import org.elixir_lang.sdk.erlang_dependent.Asked
import org.elixir_lang.sdk.erlang_dependent.ScriptedErlangStep
import org.elixir_lang.sdk.erlang_dependent.StepAnswer
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install
import org.elixir_lang.sdk.wsl.wslCompat

/**
 * "Configure from the tool manager" with no Erlang SDK to pair with (none pinned, or a pin that fails to register):
 * the Elixir install's own SDKs decide. With none, the Erlang step is asked; with one, it is used as it is; with
 * several, the chooser asks which, once for the install's modules.
 */
class ToolManagerErlangAskTest : HeavyTestCase() {
    private val asked = Asked()
    private lateinit var table: SdkFixtures.TableSnapshot
    private val checker get() = ToolManagerSdkChecker(project, emptyList(), ToolManagerSettings())
    private lateinit var elixirHome: String

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        asked.recordMessages(testRootDisposable)
        elixirHome = elixirInstall("1.18.4", "27")
    }

    override fun tearDown() = runAll({ table.restore() }, { super.tearDown() })

    fun testWithNoSdkOnTheInstallTheStepIsAsked() {
        val erlang = registeredErlang("Tool manager registered Erlang", install("27", "27.3.4"))
        script(ScriptedErlangStep.pick(erlang.name))

        configure(erlang = null)

        assertEquals(listOf("erlang"), asked.events)
        val assigned = assignedSdk() ?: throw AssertionError("the new SDK is assigned")
        assertEquals(erlang.name, storedErlangName(assigned))
    }

    fun testACancelledAskRegistersAndAssignsNothing() {
        registeredErlang("Tool manager cancelled Erlang", install("27", "27.3.4"))
        script(ScriptedErlangStep.cancel)
        val moduleSdkBefore = assignedSdk()

        configure(erlang = null)

        assertEmpty(elixirSdksOnTheInstall())
        assertEquals(moduleSdkBefore, assignedSdk())
    }

    fun testWithOneSdkOnTheInstallItIsUsed() {
        val existing = SdkFixtures.commitPairing(
            SdkFixtures.register(SdkFixtures.elixirSdk("Tool manager existing Elixir", elixirHome)),
            registeredErlang("Tool manager existing's Erlang", install("27", "27.3.4")),
        )
        registeredErlang("Tool manager other Erlang", install("26", "26.2.5"))

        configure(erlang = null)

        assertEmpty(asked.events)
        assertEquals(listOf(existing.name), elixirSdksOnTheInstall().map { it.name })
        assertEquals(existing.name, assignedSdk()?.name)
    }

    fun testWithSeveralSdksOnTheInstallACancelledChooserRegistersAndAssignsNothing() {
        val onTheInstall = twoSdksOnTheInstall(elixirHome, "Tool manager")
        val recorded = RecordedChooser(testRootDisposable)
        val moduleSdkBefore = assignedSdk()

        configure(erlang = null)

        recorded.assertChooserAskedFor(module)
        assertEmpty(asked.events)
        assertSameElements(elixirSdksOnTheInstall().map { it.name }, onTheInstall.map { it.name })
        assertEquals(moduleSdkBefore, assignedSdk())
    }

    fun testAPinnedErlangThatFailsToRegisterIsAsked() {
        script(ScriptedErlangStep.cancel)
        val moduleSdkBefore = assignedSdk()

        // Installed by the tool manager's account, but with no `erl` to run.
        configure(erlang = ToolEntry("27.3.4", SdkFixtures.fakeHome("erlang/tool-manager-no-erl"), true))

        assertEquals(listOf("erlang"), asked.events)
        assertEquals(moduleSdkBefore, assignedSdk())
    }

    fun testModulesOnOneInstallAreAskedOnceWhenTheAskIsCancelled() {
        registeredErlang("Tool manager once Erlang", install("27", "27.3.4"))
        script(ScriptedErlangStep.cancel)

        configure(null, module.name, "another module")

        assertEquals(listOf("erlang"), asked.events)
        assertEmpty(elixirSdksOnTheInstall())
    }

    fun testModulesOnOneInstallWithSeveralSdksAreAskedForOnce() {
        val pick = twoSdksOnTheInstall(elixirHome, "Tool manager")[1]
        val recorded = RecordedChooser(testRootDisposable) { state -> state.entries.single { it.sdk.name == pick.name } }
        val other = createModule("Tool manager several SDKs module")
        val decided = elixirModule()
        val decidedHome = elixirInstall("1.17.3", "26")

        checker.configureSdks(
            linkedMapOf(
                module.name to onTheInstall(erlang = null),
                other.name to onTheInstall(erlang = null),
                decided.name to versions(elixir = ToolEntry("1.17.3", decidedHome, true), erlang = pinnedAt(install("26", "26.2.5"))),
            )
        )

        assertEquals(listOf(listOf(module, other)), recorded.chooserAsked)
        assertEquals(pick.name, assignedSdk(module)?.name)
        assertEquals(pick.name, assignedSdk(other)?.name)
        assertEquals(elixirSdksAt(decidedHome).single().name, assignedSdk(decided)?.name)
    }

    fun testBothUndecidedCasesAskOncePerGroup() {
        twoSdksOnTheInstall(elixirHome, "Tool manager")
        val recorded = RecordedChooser(testRootDisposable)
        val sharing = createModule("Tool manager sharing module")
        val onSeveral = elixirModule()
        val erlang = pinnedAt(install("26", "26.2.5"))

        checker.configureSdks(
            linkedMapOf(
                module.name to versions(elixir = ToolEntry("1.17.3", elixirInstall("1.17.3", "26"), true), erlang = erlang),
                sharing.name to versions(elixir = ToolEntry("1.16.3", elixirInstall("1.16.3", "26"), true), erlang = erlang),
                onSeveral.name to onTheInstall(erlang = null),
            )
        )

        assertSameElements(recorded.chooserAsked, listOf(module, sharing), listOf(onSeveral))
    }

    fun testALaterModulesPinIsUsedWhenAnEarlierModuleOnTheInstallHasNone() {
        script(ScriptedErlangStep.cancel)
        val pin = install("26", "26.2.5")
        val pinned = createModule("Tool manager pinned module")

        checker.configureSdks(linkedMapOf(module.name to onTheInstall(erlang = null), pinned.name to onTheInstall(pinnedAt(pin))))

        assertEmpty(asked.events)
        assertPairedWith(pin, module, pinned)
    }

    fun testALaterModulesPinIsUsedWhenAnEarlierModulesPinFailsToRegister() {
        script(ScriptedErlangStep.cancel)
        val pin = install("26", "26.2.5")
        val pinned = createModule("Tool manager second pin module")
        // Installed by the tool manager's account, but with no `erl` to run.
        val failing = pinnedAt(SdkFixtures.fakeHome("erlang/tool-manager-first-pin-no-erl"))

        checker.configureSdks(linkedMapOf(module.name to onTheInstall(failing), pinned.name to onTheInstall(pinnedAt(pin))))

        assertEmpty(asked.events)
        assertPairedWith(pin, module, pinned)
    }

    private fun onTheInstall(erlang: ToolEntry?): ToolManagerVersions = versions(elixir = ToolEntry("1.18.4", elixirHome, true), erlang = erlang)

    private fun pinnedAt(home: String): ToolEntry = ToolEntry("26.2.5", home, true)

    /** Each of [modules] is assigned the install's one Elixir SDK, paired with the Erlang SDK at [erlangHome]. */
    private fun assertPairedWith(erlangHome: String, vararg modules: Module) {
        val sdk = elixirSdksOnTheInstall().singleOrNull() ?: throw AssertionError("one SDK on the install: ${elixirSdksOnTheInstall()}")
        assertTrue("paired with the pin: ${storedErlangHome(sdk)}", wslCompat.pathsEqualWslAware(storedErlangHome(sdk), erlangHome))
        modules.forEach { assertEquals(it.name, sdk.name, assignedSdk(it)?.name) }
    }

    /** [moduleNames] share one tool manager config, so all name one Elixir install. */
    private fun configure(erlang: ToolEntry?, vararg moduleNames: String = arrayOf(module.name)) {
        val shared = onTheInstall(erlang)
        checker.configureSdks(moduleNames.associateWith { shared })
    }

    private fun elixirModule(): Module =
        doCreateRealModuleIn("Tool manager Elixir module", project, ModuleTypeManager.getInstance().findByID(ELIXIR_MODULE_TYPE_ID))

    private fun script(vararg answers: StepAnswer): ScriptedErlangStep =
        ScriptedErlangStep(asked, *answers).install(testRootDisposable)

    private fun elixirSdksOnTheInstall(): List<Sdk> = elixirSdksAt(elixirHome)

    private fun assignedSdk(module: Module = this.module): Sdk? =
        ReadAction.computeBlocking<Sdk?, Throwable> { ElixirSdkLookup.resolve(module).sdk }
}
