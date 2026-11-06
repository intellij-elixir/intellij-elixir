package org.elixir_lang.sdk.choice

import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.runAll
import org.elixir_lang.facet.FacetModelFixtures
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.fakeHome
import org.elixir_lang.sdk.SdkFixtures.storedErlangName
import org.elixir_lang.sdk.SdkVersionsStore

/** The chooser on IntelliJ IDEA, asked for this test's modules, with its dialog answered by the test. */
class SdkChoiceTest : HeavyTestCase() {
    private val shown = mutableListOf<SdkChoiceState>()

    override fun setUp() {
        super.setUp()
        // As a module IntelliJ IDEA creates; a test module has no SDK entry.
        ModuleRootModificationUtil.setSdkInherited(module)
    }

    override fun tearDown() = runAll(
        { SdkVersionsStore.getInstance().clearForTests() },
        { super.tearDown() },
    )

    private fun elixir(name: String): Sdk = SdkFixtures.register(SdkFixtures.elixirSdk(name, fakeHome("elixir/$name")), testRootDisposable)

    /** "Choice Erlang", at an OTP 27.3.4 home. */
    private fun erlang(): Sdk {
        val home = fakeHome("erlang/27.3.4")
        SdkVersionsStore.getInstance().setOtpVersion(home, "27.3.4")
        return SdkFixtures.register(SdkFixtures.erlangSdk("Choice Erlang", home), testRootDisposable)
    }

    private fun SdkChoiceState.names(): List<String> = entries.map { it.sdk.name }

    private fun pick(name: String): (SdkChoiceState) -> SdkChoiceEntry? = { state -> state.entries.single { it.sdk.name == name } }

    /** Asks the chooser for [modules], its dialog answered by [answer] from what it shows. */
    private fun ask(answer: (SdkChoiceState) -> SdkChoiceEntry?, modules: List<Module> = listOf(module)): Sdk? =
        SdkChoice { _, state ->
            shown += state
            answer(state)
        }.ask(project, modules)

    fun testEveryElixirSdkIsListedByNameWithItsPairing() {
        val erlang = erlang()
        SdkFixtures.commitPairing(elixir("Choice B"), erlang)
        elixir("Choice A")

        ask({ null })

        assertEquals(
            listOf("Choice A" to null, "Choice B" to ("Choice Erlang" to "27.3.4")),
            shown.single().entries.map { entry -> entry.sdk.name to entry.erlang?.let { it.name to it.otpVersion } },
        )
    }

    fun testEachRowShowsItsPairing() {
        SdkFixtures.commitPairing(elixir("Choice paired"), erlang())
        elixir("Choice unpaired")
        val dialog = dialog()

        assertEquals(
            listOf("Choice paired  Erlang SDK 'Choice Erlang', OTP 27.3.4", "Choice unpaired  no Erlang SDK"),
            dialog.state.entries.map(dialog::rowText),
        )
    }

    fun testNothingIsSelected() {
        elixir("Choice A")

        ask({ null })

        assertNull(shown.single().selected)
    }

    fun testOkIsDisabledUntilAPick() {
        elixir("Choice A")
        val dialog = dialog()

        assertFalse("nothing is picked yet", dialog.isOKActionEnabled)

        dialog.pick(dialog.state.entries.single())

        assertTrue(dialog.isOKActionEnabled)
    }

    fun testAnUnpairedSdkCanBePicked() {
        elixir("Choice unpaired")
        val dialog = dialog()

        dialog.pick(dialog.state.entries.single())

        assertEquals("Choice unpaired", dialog.state.selected?.sdk?.name)
    }

    fun testOnIntellijIdeaTheSdkTheFacetNamesGoesFirst() {
        elixir("Choice A")
        val named = elixir("Choice B")
        FacetModelFixtures.assign(module, named)

        ask({ null })

        assertEquals(listOf("Choice B", "Choice A"), shown.single().names())
    }

    fun testOkWritesThePickToEveryModule() {
        val pick = elixir("Choice pick")
        val other = createModule("Choice other module")
        ModuleRootModificationUtil.setSdkInherited(other)

        val returned = ask(pick("Choice pick"), listOf(module, other))

        assertSame(pick, returned)
        assertSame(pick, ProjectRootManager.getInstance(project).projectSdk)
        assertSame(pick, ModuleRootManager.getInstance(module).sdk)
        assertSame(pick, ModuleRootManager.getInstance(other).sdk)
    }

    fun testCancelWritesNothing() {
        elixir("Choice A")

        assertNull(ask({ null }))

        assertNull(ProjectRootManager.getInstance(project).projectSdk)
    }

    fun testThePickIsNeverPaired() {
        val unpaired = elixir("Choice unpaired")
        erlang()

        ask(pick("Choice unpaired"))

        assertNull(storedErlangName(unpaired))
    }

    private fun dialog(): SdkChoiceDialog {
        val state = SdkChoiceState(project, listOf(module))
        Disposer.register(testRootDisposable, state)
        return SdkChoiceDialog(null, state).also { Disposer.register(testRootDisposable, it.disposable) }
    }
}
