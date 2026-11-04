package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.runAll
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.fakeHome
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.sdk.elixir.ElixirVersions
import org.elixir_lang.sdk.elixir.OtpMajor
import org.elixir_lang.sdk.elixir.OtpMatch

class ErlangStepDialogTest : PlatformTestCase() {
    private val elixirHome = fakeHome("elixir/1.18.4-otp-27")

    override fun setUp() {
        super.setUp()
        SdkVersionsStore.getInstance().setElixirVersions(elixirHome, ElixirVersions("1.18.4", OtpMajor.Known("27")))
    }

    override fun tearDown() = runAll(
        { SdkVersionsStore.getInstance().clearForTests() },
        { super.tearDown() },
    )

    fun testOkIsDisabledUntilAPick() {
        val home = fakeHome("erlang/27.3.4")
        SdkVersionsStore.getInstance().setOtpVersion(home, "27.3.4")
        val dialog = dialog(ErlangStep.state(elixirHome, listOf(SdkFixtures.erlangSdk("Dialog Erlang", home)), emptyList()))

        assertFalse("nothing is picked yet", dialog.isOKActionEnabled)

        dialog.pick(dialog.state.entries.single())

        assertTrue(dialog.isOKActionEnabled)
        assertFalse("a matching pick has no warning", dialog.isOtpMismatchWarningShown)
    }

    fun testAMismatchedPickShowsTheWarningAndTheLink() {
        val home = fakeHome("erlang/26.2.5")
        SdkVersionsStore.getInstance().setOtpVersion(home, "26.2.5")
        val dialog = dialog(ErlangStep.state(elixirHome, emptyList(), listOf(home)), chooseAnotherInstall = {})

        dialog.pick(dialog.state.entries.single())

        assertTrue(dialog.isOtpMismatchWarningShown)
        assertEquals("Elixir SDK was compiled for OTP 27 but is paired with OTP 26", dialog.otpMismatchWarningText)
        assertTrue("the link to choose another Elixir install is offered", dialog.isAnotherInstallLinkShown)
        assertTrue("a mismatch is a warning, not a refusal", dialog.isOKActionEnabled)
    }

    fun testTheWarningIsTheListedEntrysMatch() {
        val home = fakeHome("erlang/26.2.5")
        SdkVersionsStore.getInstance().setOtpVersion(home, "26.2.5")
        val dialog = dialog(ErlangStep.state(elixirHome, emptyList(), listOf(home)), chooseAnotherInstall = {})
        // The version watch reads the home again while the dialog is open.
        SdkVersionsStore.getInstance().setOtpVersion(home, "27.3.4")

        dialog.pick(dialog.state.entries.single())

        assertTrue("the row shows a mismatch, and so does the warning", dialog.isOtpMismatchWarningShown)
        assertEquals("Elixir SDK was compiled for OTP 27 but is paired with OTP 26", dialog.otpMismatchWarningText)
    }

    fun testWithNoWayBackAMismatchedPickOffersNoLink() {
        val home = fakeHome("erlang/26.2.5")
        SdkVersionsStore.getInstance().setOtpVersion(home, "26.2.5")
        val dialog = dialog(ErlangStep.state(elixirHome, emptyList(), listOf(home)))

        dialog.pick(dialog.state.entries.single())

        assertTrue(dialog.isOtpMismatchWarningShown)
        assertFalse("there is no Elixir install to go back to", dialog.isAnotherInstallLinkShown)
        assertTrue(dialog.isOKActionEnabled)
    }

    fun testBrowsingToAnErlangThatCannotBePickedLeavesNothingPicked() {
        val free = fakeHome("erlang/27.3.4")
        val paired = fakeHome("erlang/26.2.5")
        val entries = listOf(
            ErlangStepEntry(null, free, "27.3.4", OtpMatch.Match, unpickable = null),
            ErlangStepEntry(null, paired, "26.2.5", OtpMatch.Mismatch("27", "26"), Unpickable.PairedWith("Dialog paired Elixir")),
        )
        val dialog = dialog(ErlangStepState(entries) { error("a listed home makes no entry") }, browse = { paired })
        dialog.pick(dialog.state.entries.first())

        dialog.browsed()

        assertNull("an Erlang already paired on this install can't be picked", dialog.state.selected)
        assertFalse("OK is enabled only on a pick", dialog.isOKActionEnabled)
    }

    fun testBrowsingToAnErlangOnAnotherMachineShowsWhyItCannotBePicked() {
        val elsewhere = fakeHome("erlang/elsewhere")
        val state = ErlangStep.state(elixirHome, emptyList(), emptyList(), inEnvironment = { home -> home != elsewhere })
        val dialog = dialog(state, browse = { elsewhere })

        dialog.browsed()

        assertNull("an Erlang on another machine can't run this Elixir install", dialog.state.selected)
        assertFalse("OK is enabled only on a pick", dialog.isOKActionEnabled)
        val row = dialog.rowText(dialog.state.entries.single())
        assertTrue(row, row.endsWith("on another machine than the Elixir install"))
    }

    fun testAnAskNamesTheElixirSdkItRepairs() {
        val asking = SdkFixtures.elixirSdk("Dialog asked Elixir", elixirHome)
        val dialog = dialog(ErlangStep.state(elixirHome, emptyList(), emptyList()), asking = asking)

        assertEquals("Erlang for Elixir SDK 'Dialog asked Elixir'", dialog.title)
    }

    private fun dialog(
        state: ErlangStepState,
        asking: Sdk? = null,
        chooseAnotherInstall: ChooseAnotherInstall? = null,
        browse: () -> String? = { null },
    ): ErlangStepDialog =
        ErlangStepDialog(null, state, asking, chooseAnotherInstall, browse)
            .also { Disposer.register(testRootDisposable, it.disposable) }
}
