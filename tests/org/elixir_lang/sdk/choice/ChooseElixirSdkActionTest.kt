package org.elixir_lang.sdk.choice

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import org.elixir_lang.jps.shared.ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.fakeHome

/** Choose Elixir SDK... asks the chooser for the context's Elixir module, else for every Elixir module. */
class ChooseElixirSdkActionTest : HeavyTestCase() {
    private lateinit var pick: Sdk
    private lateinit var recorded: RecordedChooser

    override fun setUp() {
        super.setUp()
        pick = SdkFixtures.register(SdkFixtures.elixirSdk("Choose pick", fakeHome("elixir/choose-pick")), testRootDisposable)
        recorded = RecordedChooser(testRootDisposable) { state -> state.entries.single { it.sdk.name == pick.name } }
    }

    private val action: AnAction get() = ActionManager.getInstance().getAction("Elixir.ChooseElixirSdk")

    private fun elixirModule(name: String): Module =
        doCreateRealModuleIn(name, project, ModuleTypeManager.getInstance().findByID(ELIXIR_MODULE_TYPE_ID))

    private fun event(contextModule: Module?): AnActionEvent = moduleEvent(action, project, contextModule)

    private fun moduleSdk(module: Module): Sdk? = ModuleRootManager.getInstance(module).sdk

    fun testTheContextModuleIsWritten() {
        val context = elixirModule("context")
        val other = elixirModule("other")

        ActionUtil.performAction(action, event(context))

        assertEquals(listOf(listOf(context)), recorded.chooserAsked)
        assertSame(pick, moduleSdk(context))
        assertNull(moduleSdk(other))
    }

    fun testWithNoContextModuleEveryElixirModuleIsWritten() {
        val first = elixirModule("first")
        val second = elixirModule("second")

        ActionUtil.performAction(action, event(null))

        assertEquals(setOf(first, second), recorded.chooserAsked.single().toSet())
        assertSame(pick, moduleSdk(first))
        assertSame(pick, moduleSdk(second))
    }

    fun testAContextModuleThatIsNotElixirsAsksForEveryElixirModule() {
        val elixir = elixirModule("elixir")

        ActionUtil.performAction(action, event(module))

        assertEquals(listOf(listOf(elixir)), recorded.chooserAsked)
    }

    fun testVisibleWithAnElixirModule() {
        elixirModule("elixir")
        val event = event(null)

        ActionUtil.updateAction(action, event)

        assertTrue(event.presentation.isEnabledAndVisible)
    }

    fun testHiddenWithoutAnElixirModule() {
        val event = event(module)

        ActionUtil.updateAction(action, event)

        assertFalse(event.presentation.isEnabledAndVisible)
    }
}
