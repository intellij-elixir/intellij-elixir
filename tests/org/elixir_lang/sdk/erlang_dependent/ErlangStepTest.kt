package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.common.runAll
import com.intellij.testFramework.registerOrReplaceServiceInstance
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.fakeHome
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.sdk.elixir.ElixirVersions
import org.elixir_lang.sdk.elixir.OtpMajor
import org.elixir_lang.sdk.elixir.OtpMatch
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService

/** The Erlang step's rows for an Elixir install, as the step's dialog lists them. */
class ErlangStepTest : PlatformTestCase() {
    private val elixirHome = fakeHome("elixir/1.18.4-otp-27")

    override fun setUp() {
        super.setUp()
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            MockWslCompatService(),
            testRootDisposable,
        )
        SdkVersionsStore.getInstance().setElixirVersions(elixirHome, ElixirVersions("1.18.4", OtpMajor.Known("27")))
    }

    override fun tearDown() = runAll(
        { SdkVersionsStore.getInstance().clearForTests() },
        { super.tearDown() },
    )

    /** As the IDE builds it, not replaced: the platform refuses a light service whose class isn't final. */
    fun testTheIdeBuildsTheRealStep() {
        assertNotNull(ErlangStep.getInstance())
    }

    fun testRegisteredErlangSdksInTheInstallsEnvironmentAreListed() {
        val local = SdkFixtures.erlangSdk("Step local Erlang", fakeHome("erlang/27.3.4"))
        val elsewhere = SdkFixtures.erlangSdk("Step elsewhere Erlang", fakeHome("erlang/elsewhere"))
        val elixir = SdkFixtures.elixirSdk("Step other Elixir", fakeHome("elixir/other"))

        val entries = ErlangStep.state(
            elixirHome,
            listOf(local, elsewhere, elixir),
            installHomes = emptyList(),
            inEnvironment = { home -> home != elsewhere.homePath },
        ).entries

        assertEquals(listOf("Step local Erlang"), entries.map { it.sdk?.name })
    }

    fun testDetectedInstallsNoListedSdkHasAreListed() {
        val registeredHome = fakeHome("erlang/27.3.4")
        val newHome = fakeHome("erlang/28.1")
        val registered = SdkFixtures.erlangSdk("Step registered Erlang", registeredHome)

        val entries = ErlangStep.state(elixirHome, listOf(registered), installHomes = listOf(registeredHome, newHome)).entries

        assertEquals(
            "the registered SDK first, then only the install no SDK has",
            listOf("Step registered Erlang" to path(registeredHome), null to path(newHome)),
            entries.map { it.sdk?.name to path(it.home) },
        )
    }

    fun testNothingIsSelected() {
        val one = SdkFixtures.erlangSdk("Step only Erlang", fakeHome("erlang/27.3.4"))

        val state = state(listOf(one))

        assertNull("one Erlang SDK is still a question, never a default", state.selected)
    }

    fun testEachEntryShowsItsOtpAgainstTheBuild() {
        val matchHome = fakeHome("erlang/27.3.4")
        val mismatchHome = fakeHome("erlang/26.2.5")
        val unreadHome = fakeHome("erlang/unread")
        SdkVersionsStore.getInstance().setOtpVersion(matchHome, "27.3.4")
        SdkVersionsStore.getInstance().setOtpVersion(mismatchHome, "26.2.5")

        val entries = ErlangStep.state(
            elixirHome,
            emptyList(),
            installHomes = listOf(matchHome, mismatchHome, unreadHome),
        ).entries.associateBy { it.home }

        assertEquals("27.3.4", entries.getValue(matchHome).otpVersion)
        assertEquals(OtpMatch.Match, entries.getValue(matchHome).otpMatch)
        assertEquals("26.2.5", entries.getValue(mismatchHome).otpVersion)
        assertEquals(OtpMatch.Mismatch("27", "26"), entries.getValue(mismatchHome).otpMatch)
        assertNull(entries.getValue(unreadHome).otpVersion)
        assertEquals(OtpMatch.Unknown, entries.getValue(unreadHome).otpMatch)
    }

    fun testAnErlangAnExistingSdkOnThisInstallPairsWithCanBePicked() {
        val paired = SdkFixtures.erlangSdk("Step paired Erlang", fakeHome("erlang/27.3.4"))
        val existing = pairedElixirSdk("Step existing Elixir", elixirHome, paired)

        val state = state(listOf(paired, existing))
        val entries = state.entries.associateBy { it.sdk?.name }

        assertNull(entries.getValue("Step paired Erlang").unpickable)
        state.select(entries.getValue("Step paired Erlang"))
        assertSame(entries.getValue("Step paired Erlang"), state.selected)
    }

    fun testAnInstallAnExistingSdkOnThisInstallStoresCanBePicked() {
        val home = fakeHome("erlang/27.3.4")
        val existing = pairedElixirSdk("Step existing Elixir", elixirHome, SdkFixtures.erlangSdk("Step deleted Erlang", home))

        val entry = ErlangStep.state(elixirHome, listOf(existing), installHomes = listOf(home)).entries.single()

        assertTrue(entry.pickable)
    }

    fun testBrowseToAHomeAnExistingSdkOnThisInstallStoresCanBePicked() {
        val home = fakeHome("erlang/27.3.4")
        val existing = pairedElixirSdk("Step existing Elixir", elixirHome, SdkFixtures.erlangSdk("Step deleted Erlang", home))
        val state = state(listOf(existing))

        state.browsed(home)

        assertTrue(state.entries.single().pickable)
        assertSame(state.entries.single(), state.selected)
    }

    fun testBrowseToAHomeOnAnotherMachineCannotBePicked() {
        val elsewhere = fakeHome("erlang/elsewhere")
        val state = state(emptyList(), inEnvironment = { home -> home != elsewhere })

        state.browsed(elsewhere)

        assertEquals(Unpickable.OnAnotherMachine, state.entries.single().unpickable)
        assertNull("an Erlang on another machine can't run this Elixir install", state.selected)
    }

    fun testBothErlangSdksAtAPairedHomeCanBePicked() {
        val sharedHome = fakeHome("erlang/27.3.4")
        val named = SdkFixtures.erlangSdk("Step named Erlang", sharedHome)
        val twin = SdkFixtures.erlangSdk("Step named Erlang (2)", sharedHome)
        val existing = pairedElixirSdk("Step existing Elixir", elixirHome, named)

        val entries = ErlangStep.state(elixirHome, listOf(twin, named, existing), installHomes = emptyList()).entries

        assertEquals(listOf(true, true), entries.map { it.pickable })
    }

    fun testBrowseToAListedHomeSelectsThatEntry() {
        val home = fakeHome("erlang/27.3.4")
        val listed = SdkFixtures.erlangSdk("Step listed Erlang", home)
        val state = state(listOf(listed))

        state.browsed(home)

        assertSame(listed, state.selected?.sdk)
        assertEquals("browsing to a listed home adds no row", 1, state.entries.size)
    }

    fun testBrowseOpensAtTheNewestInstallNoListedSdkHas() {
        val newest = install("28", "28.1")
        val middle = install("27", "27.3.4")
        val oldest = install("26", "26.2.5")
        val listed = SdkFixtures.erlangSdk("Step browse Erlang", newest)
        var browseFrom: String? = null
        val step = ErlangStep({ listOf(newest, middle, oldest) }) { _, _, from, _, _ -> browseFrom = from; ErlangStepAnswer.Cancelled }

        step.ask(null, elixirHome, model(listed))

        assertEquals(middle, browseFrom)
    }

    fun testADetectedInstallWithNoErlIsNotListed() {
        val complete = install("28", "28.1")
        val incomplete = SdkFixtures.erlangHomeWithEbin("27", "27.3.4")
        var listed: List<String>? = null
        val step = ErlangStep({ listOf(complete, incomplete) }) { _, state, _, _, _ ->
            listed = state.entries.map { it.home }
            ErlangStepAnswer.Cancelled
        }

        step.ask(null, elixirHome, model())

        assertEquals(listOf(complete), listed)
    }

    /** The step's dialog state for [sdks], as the step builds it. */
    private fun state(sdks: List<Sdk>, inEnvironment: (home: String) -> Boolean = { true }): ErlangStepState =
        ErlangStep.state(elixirHome, sdks, installHomes = emptyList(), inEnvironment = inEnvironment)

    private fun path(path: String): String = FileUtil.toSystemIndependentName(path)

    private fun pairedElixirSdk(name: String, home: String, erlangSdk: Sdk): Sdk =
        SdkFixtures.commitPairing(SdkFixtures.elixirSdk(name, home), erlangSdk)
}
