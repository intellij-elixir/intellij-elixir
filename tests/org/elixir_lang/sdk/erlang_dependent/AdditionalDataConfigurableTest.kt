package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.options.ex.ConfigurableCardPanel
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.common.runAll
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.navigation.History
import com.intellij.ui.navigation.Place
import com.intellij.util.ui.UIUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.facet.sdk.Editor
import org.elixir_lang.sdk.ProcessOutput
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.codePaths
import org.elixir_lang.sdk.SdkFixtures.codePathsUnder
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.sdk.elixir.ElixirSdkPathConfigurator

/** The Elixir SDK page's Erlang pairing combo, on both families' pages: it writes a pairing only after the user picks one. */
class AdditionalDataConfigurableTest : PlatformTestCase() {
    private val opened = mutableListOf<AdditionalDataConfigurable>()

    override fun tearDown() = runAll(
        { opened.forEach(AdditionalDataConfigurable::disposeUIResources) },
        { SdkFixtures.waitForRegistrationFills() },
        { SdkVersionsStore.getInstance().clearForTests() },
        { super.tearDown() },
    )

    fun testAPairingResolvedByHomeIsNotModified() {
        val renamed = erlang("Combo renamed Erlang", "27", "27.3.4")
        val model = model(renamed, paired("Combo by home", "Combo old Erlang name", renamed.homePath!!))
        val page = open(model, "Combo by home")

        assertFalse("nothing was picked", page.configurable.isModified)
    }

    fun testApplyWithoutAPickWritesNothing() {
        val renamed = erlang("Combo unpicked Erlang", "27", "27.3.4")
        val model = model(renamed, paired("Combo unpicked", "Combo stored Erlang name", renamed.homePath!!))
        val page = open(model, "Combo unpicked")

        page.configurable.apply()

        assertEquals("Combo stored Erlang name", liveErlangName(page.elixirSdk))
    }

    fun testTheSuppressCheckboxAloneWritesOnlyTheFlag() {
        val renamed = erlang("Combo suppress Erlang", "27", "27.3.4")
        val model = model(renamed, paired("Combo suppress", "Combo suppress stored name", renamed.homePath!!))
        val page = open(model, "Combo suppress")

        page.suppress.isSelected = true
        page.configurable.apply()

        val data = page.elixirSdk.sdkAdditionalData as SdkAdditionalData
        assertTrue("the flag is written", data.isSuppressOtpMismatchWarning())
        assertEquals("the pairing is not", "Combo suppress stored name", data.getErlangSdkName())
    }

    fun testTheSuppressCheckboxIsWrittenWithNoAdditionalData() {
        val model = model(
            erlang("Combo no data suppress Erlang", "27", "27.3.4"),
            SdkFixtures.elixirSdk("Combo no data suppress", SdkFixtures.elixirHome("1.18.4")),
        )
        val page = open(model, "Combo no data suppress")

        page.suppress.isSelected = true
        page.configurable.apply()

        val data = page.elixirSdk.sdkAdditionalData as SdkAdditionalData
        assertTrue("the flag is written", data.isSuppressOtpMismatchWarning())
        assertNull("no Erlang is paired", data.getErlangSdkName())
    }

    fun testTheSuppressCheckboxModifiesThePageUntilApplied() {
        val erlang = erlang("Combo suppress cycle Erlang", "27", "27.3.4")
        val model = model(erlang, paired("Combo suppress cycle", erlang.name, erlang.homePath!!))
        val page = open(model, "Combo suppress cycle")

        page.suppress.isSelected = true
        assertTrue("a tick is a change to apply", page.configurable.isModified)
        page.configurable.apply()
        assertFalse("the tick is applied", page.configurable.isModified)

        page.suppress.isSelected = false
        assertTrue("an untick after the apply is a change to apply", page.configurable.isModified)
        page.configurable.apply()
        assertFalse((page.elixirSdk.sdkAdditionalData as SdkAdditionalData).isSuppressOtpMismatchWarning())
    }

    fun testAStoredSuppressionIsNotModified() {
        val erlang = erlang("Combo suppressed Erlang", "27", "27.3.4")
        val elixir = SdkFixtures.elixirSdk("Combo suppressed", SdkFixtures.elixirHome("1.18.4")).also { elixir ->
            val data = SdkFixtures.storedPairing(elixir, erlang.name, erlang.homePath)
            data.setSuppressOtpMismatchWarning(true)
            SdkFixtures.commit(elixir, data)
        }
        val page = open(model(erlang, elixir), "Combo suppressed")

        assertTrue("precondition: the checkbox shows the stored flag", page.suppress.isSelected)
        assertFalse(page.configurable.isModified)
    }

    fun testTickingAndUntickingTheSuppressCheckboxIsNotModified() {
        val erlang = erlang("Combo untick Erlang", "27", "27.3.4")
        val model = model(erlang, paired("Combo untick", erlang.name, erlang.homePath!!))
        val page = open(model, "Combo untick")

        page.suppress.isSelected = true
        page.suppress.isSelected = false

        assertFalse("the checkbox is back as it was", page.configurable.isModified)
    }

    fun testApplyAfterAPickWritesIt() {
        val first = erlang("Combo first Erlang", "27", "27.3.4")
        val second = erlang("Combo second Erlang", "26", "26.2.5")
        val model = model(first, second, paired("Combo picked", first.name, first.homePath!!))
        val page = open(model, "Combo picked")

        page.combo.selectedItem = model.findSdk(second.name)
        page.configurable.apply()

        assertEquals(second.name, liveErlangName(page.elixirSdk))
    }

    /**
     * A pick of the Erlang SDK the pairing names, when another Erlang SDK holds the stored home and so is shown: the pick
     * writes the named SDK's home, and the code paths follow it.
     */
    fun testPickingTheNamedErlangAtAnotherHomeMovesTheCodePaths() {
        val named = SdkFixtures.withCodePaths(erlang("Combo named Erlang", "27", "27.3.4"))
        val atStoredHome = SdkFixtures.withCodePaths(erlang("Combo stored home Erlang", "26", "26.2.5"))
        val elixir = paired("Combo named pick", named.name, atStoredHome.homePath!!)
        val model = model(named, atStoredHome, elixir)
        ElixirSdkPathConfigurator.configure(elixir, model)
        val page = open(model, elixir.name)
        assertEquals("precondition: the stored home's SDK is shown", atStoredHome.name, (page.combo.selectedItem as Sdk?)?.name)
        assertEquals("precondition: the stored home's code paths", codePaths(atStoredHome), codePathsUnder(elixir, atStoredHome.homePath!!))

        page.combo.selectedItem = model.findSdk(named.name)
        page.configurable.apply()

        assertEquals(named.homePath, page.elixirSdk.elixirAdditionalData?.getErlangSdkHomePath())
        assertEquals("the named SDK's code paths", codePaths(named), codePathsUnder(page.elixirSdk, named.homePath!!))
        assertEquals("the stored home's code paths", emptyList<String>(), codePathsUnder(page.elixirSdk, atStoredHome.homePath!!))
    }

    /** A pick at another home of an Erlang SDK only the dialog's model holds keeps the suppress flag applied with it. */
    fun testAPickOfAModelOnlyErlangAtAnotherHomeKeepsTheSuppressFlag() {
        val picked = erlang("Combo model-only Erlang", "27", "27.3.4")
        val stored = erlang("Combo model-only stored Erlang", "26", "26.2.5")
        val model = model(picked, stored, paired("Combo model-only pick", stored.name, stored.homePath!!))
        val page = open(model, "Combo model-only pick")

        page.suppress.isSelected = true
        page.combo.selectedItem = model.findSdk(picked.name)
        page.configurable.apply()

        val data = page.elixirSdk.sdkAdditionalData as SdkAdditionalData
        assertEquals("precondition: the pick is written", picked.homePath, data.getErlangSdkHomePath())
        assertTrue("the flag applied with the pick", data.isSuppressOtpMismatchWarning())
    }

    fun testTheSmallIdesEditorApplyWritesNoPairingUnasked() {
        val renamed = erlang("Combo editor Erlang", "27", "27.3.4")
        val model = model(renamed, paired("Combo editor", "Combo editor stored name", renamed.homePath!!))
        val elixirSdk = model.findSdk("Combo editor") as ProjectJdkImpl
        ProcessOutput.isSmallIdeOverride = true
        try {
            val editor = Editor(model, History(object : Place.Navigator {}), elixirSdk)
            ConfigurableCardPanel.createConfigurableComponent(editor)

            editor.apply()
            editor.disposeUIResources()
        } finally {
            ProcessOutput.isSmallIdeOverride = null
        }

        assertEquals("Combo editor stored name", liveErlangName(elixirSdk))
    }

    fun testADeletedPairingsFirstErlangIsNotWritten() {
        // IDEA: the paired SDK removed from the dialog's model, whose Apply resets the page.
        val deleted = erlang("Combo deleted Erlang", "25", "25.3.2")
        val ideaModel = model(
            deleted,
            erlang("Combo IDEA first Erlang", "27", "27.3.4"),
            erlang("Combo IDEA second Erlang", "26", "26.2.5"),
            paired("Combo IDEA paired", deleted.name, deleted.homePath!!),
        )
        val ideaPage = open(ideaModel, "Combo IDEA paired")
        ideaModel.removeSdk(ideaModel.findSdk(deleted.name)!!)
        ideaPage.configurable.reset()

        assertNull("IDEA's combo shows nothing for a pairing that doesn't resolve", ideaPage.combo.selectedItem)
        ideaPage.configurable.apply()

        assertEquals("IDEA's page", deleted.name, liveErlangName(ideaPage.elixirSdk))

        // The small IDEs: the paired SDK left the table at the click, before the page is shown.
        val smallModel = model(
            erlang("Combo small first Erlang", "27", "27.3.4"),
            erlang("Combo small second Erlang", "26", "26.2.5"),
            paired("Combo small paired", deleted.name, deleted.homePath!!),
        )
        val smallPage = open(smallModel, "Combo small paired")

        assertNull("the combo shows nothing for a pairing that doesn't resolve", smallPage.combo.selectedItem)
        smallPage.configurable.apply()

        assertEquals("the small IDEs' page", deleted.name, liveErlangName(smallPage.elixirSdk))
    }

    fun testADeletedErlangSdkLeavesTheList() {
        val kept = erlang("Combo kept Erlang", "27", "27.3.4")
        val removed = erlang("Combo removed Erlang", "26", "26.2.5")
        val model = model(kept, removed, paired("Combo removal", kept.name, kept.homePath!!))
        val page = open(model, "Combo removal")

        model.removeSdk(model.findSdk(removed.name)!!)

        assertEquals(listOf(kept.name), page.listedNames())
        assertEquals(kept.name, (page.combo.selectedItem as Sdk?)?.name)
        assertFalse(page.configurable.isModified)
    }

    fun testDeletingThePairedErlangSdkShowsNothingAndWritesNothing() {
        val deleted = erlang("Combo paired deleted Erlang", "27", "27.3.4")
        val neighbour = erlang("Combo neighbour Erlang", "26", "26.2.5")
        val model = model(neighbour, deleted, paired("Combo paired removal", deleted.name, deleted.homePath!!))
        val page = open(model, "Combo paired removal")

        model.removeSdk(model.findSdk(deleted.name)!!)

        assertEquals(listOf(neighbour.name), page.listedNames())
        assertNull("the combo shows nothing for a pairing that no longer resolves", page.combo.selectedItem)
        assertFalse(page.configurable.isModified)
        page.configurable.apply()
        assertEquals(deleted.name, liveErlangName(page.elixirSdk))
    }

    fun testAnErlangAddedToTheModelIsNotWritten() {
        val gone = erlang("Combo gone Erlang", "25", "25.3.2")
        val model = model(paired("Combo empty combo", gone.name, gone.homePath!!))
        val page = open(model, "Combo empty combo")

        model.addSdk(erlang("Combo added Erlang", "27", "27.3.4"))
        page.configurable.apply()

        assertNull(page.combo.selectedItem)
        assertEquals(gone.name, liveErlangName(page.elixirSdk))
    }

    fun testNoAdditionalDataSelectsNothingAndWritesNothing() {
        val model = model(erlang("Combo only Erlang", "27", "27.3.4"), SdkFixtures.elixirSdk("Combo no data", SdkFixtures.elixirHome("1.18.4")))
        val page = open(model, "Combo no data")

        assertNull(page.combo.selectedItem)
        assertFalse(page.configurable.isModified)
        page.configurable.apply()

        assertNull(liveErlangName(page.elixirSdk))
    }

    fun testAHomeReselectedWritesNoPairing() {
        val first = erlang("Combo home first Erlang", "27", "27.3.4")
        val second = erlang("Combo home second Erlang", "26", "26.2.5")
        // Listed in order, so the first Erlang SDK in the model is not the paired one.
        val model = ListedSdkModel(first, second, paired("Combo home reselected", second.name, second.homePath!!))
        val page = open(model, "Combo home reselected")

        model.multicaster.sdkHomeSelected(first, first.homePath!!)

        assertFalse(page.configurable.isModified)
        page.configurable.apply()
        assertEquals(second.name, liveErlangName(page.elixirSdk))
    }

    private class Page(
        val configurable: AdditionalDataConfigurable,
        val elixirSdk: Sdk,
        val combo: ComboBox<*>,
        val suppress: JBCheckBox,
    ) {
        fun listedNames(): List<String> = (0 until combo.itemCount).map { (combo.getItemAt(it) as Sdk).name }
    }

    /** The page for the model's copy of the Elixir SDK named [elixirName], reset as the SDK editor shows it. */
    private fun open(model: SdkModel, elixirName: String): Page {
        val elixirSdk = model.findSdk(elixirName)!!
        val configurable = AdditionalDataConfigurable(model, elixirSdk.sdkModificator)
        configurable.setSdk(elixirSdk)
        val component = configurable.createComponent()
        configurable.reset()
        opened += configurable

        return Page(
            configurable,
            elixirSdk,
            UIUtil.findComponentOfType(component, ComboBox::class.java)!!,
            UIUtil.findComponentOfType(component, JBCheckBox::class.java)!!,
        )
    }

    private fun erlang(name: String, major: String, otpVersion: String): Sdk =
        SdkFixtures.erlangSdk(name, SdkFixtures.erlangHomeWithEbin(major, otpVersion))

    private fun paired(name: String, erlangName: String, erlangHome: String): Sdk =
        SdkFixtures.commitPairing(SdkFixtures.elixirSdk(name, SdkFixtures.elixirHome("1.18.4")), erlangName, erlangHome)

    /** The Erlang SDK name the live additional data holds, unlike [SdkFixtures.storedErlangName]'s XML read. */
    private fun liveErlangName(sdk: Sdk): String? = sdk.elixirAdditionalData?.getErlangSdkName()
}
