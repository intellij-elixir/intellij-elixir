package org.elixir_lang.tool_manager

import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.testFramework.common.runAll
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.storedErlangName
import org.elixir_lang.sdk.erlang_dependent.Asked
import org.elixir_lang.sdk.erlang_dependent.ScriptedErlangStep
import org.elixir_lang.sdk.erlang_dependent.StepAnswer
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install

/** What a tool manager's pins for one Elixir install make of it, with each module's pins on that install. */
class ToolManagerPinsTest : HeavyTestCase() {
    private val asked = Asked()
    private lateinit var table: SdkFixtures.TableSnapshot
    private lateinit var elixirHome: String

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        asked.recordMessages(testRootDisposable)
        elixirHome = elixirInstall("1.18.4", "27")
    }

    override fun tearDown() = runAll({ table.restore() }, { super.tearDown() })

    fun testAnErlangPinThatRegistersFindsTheModelPairedWithIt() {
        script(ScriptedErlangStep.cancel)
        val pin = install("26", "26.2.5")

        val found = model(ToolEntry("26.2.5", pin, true)) as? PinsModel.Found ?: throw AssertionError("found")

        assertEmpty(asked.events)
        assertPairedWithErlangAt(found.sdk, pin)
        assertEquals(listOf(found.sdk.name), elixirSdksAt(elixirHome).map { it.name })
    }

    fun testWithNoErlangPinTheInstallsOneModelIsFound() {
        val existing = pairedElixir("Pins existing Elixir", elixirHome, registeredErlang("Pins existing's Erlang", install("27", "27.3.4")))

        val found = model(erlang = null)

        assertEmpty(asked.events)
        assertEquals(PinsModel.Found(existing), found)
    }

    fun testWithNoErlangPinAndNoModelTheErlangStepPairsANewOne() {
        val erlang = registeredErlang("Pins step Erlang", install("27", "27.3.4"))
        script(ScriptedErlangStep.pick(erlang.name))

        val found = model(erlang = null) as? PinsModel.Found ?: throw AssertionError("found")

        assertEquals(listOf("erlang"), asked.events)
        assertEquals(erlang.name, storedErlangName(found.sdk))
        assertEquals(listOf(found.sdk.name), elixirSdksAt(elixirHome).map { it.name })
    }

    fun testWithNoErlangPinAndSeveralModelsTheyAreAllAnswered() {
        val onTheInstall = twoSdksOnTheInstall(elixirHome, "Pins")

        val several = model(erlang = null) as? PinsModel.Several ?: throw AssertionError("several")

        assertEmpty(asked.events)
        assertSameElements(several.sdks, onTheInstall)
    }

    fun testACancelledErlangStepFindsNothingAndRegistersNothing() {
        registeredErlang("Pins cancelled Erlang", install("27", "27.3.4"))
        script(ScriptedErlangStep.cancel)

        val none = model(erlang = null)

        assertEquals(listOf("erlang"), asked.events)
        assertEquals(PinsModel.None, none)
        assertEmpty(elixirSdksAt(elixirHome))
    }

    private fun model(erlang: ToolEntry?): PinsModel {
        val sharing = listOf(versions(elixir = ToolEntry("1.18.4", elixirHome, true), erlang = erlang))
        return runWithModalProgressBlocking(ModalTaskOwner.project(project), "Pins") {
            ToolManagerPins.model(elixirHome, sharing, project, null)
        }
    }

    private fun script(vararg answers: StepAnswer): ScriptedErlangStep =
        ScriptedErlangStep(asked, *answers).install(testRootDisposable)
}
