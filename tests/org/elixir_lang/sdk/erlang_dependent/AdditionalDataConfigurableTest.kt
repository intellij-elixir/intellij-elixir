package org.elixir_lang.sdk.erlang_dependent

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.ex.ConfigurableCardPanel
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.SdkModificator
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.ui.configuration.ProjectJdksConfigurable
import com.intellij.openapi.roots.ui.configuration.ProjectStructureConfigurable
import com.intellij.openapi.roots.ui.configuration.SdkComboBox
import com.intellij.openapi.roots.ui.configuration.SdkListItem
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.ComponentValidator
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.common.runAll
import com.intellij.testFramework.replaceService
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
import org.elixir_lang.sdk.elixir.ElixirSdkPathConfigurator
import org.elixir_lang.sdk.elixir.SdkPageInDialog
import javax.swing.JComponent
import javax.swing.JLabel
import com.intellij.openapi.projectRoots.SdkAdditionalData as PlatformSdkAdditionalData
import org.elixir_lang.sdk.erlang.Type as ErlangSdkType

/** The Elixir SDK page's Erlang pairing combo, on both families' pages: it writes a pairing only after the user picks one. */
class AdditionalDataConfigurableTest : PlatformTestCase() {
    private val opened = mutableListOf<AdditionalDataConfigurable>()
    private lateinit var table: SdkFixtures.TableSnapshot

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
    }

    override fun tearDown() = runAll(
        { opened.forEach(AdditionalDataConfigurable::disposeUIResources) },
        { table.restore() },
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

    fun testADeletedErlangSdkWhoseCopyWasRenamedLeavesTheList() {
        val kept = erlang("Combo renamed kept Erlang", "27", "27.3.4")
        val removed = erlang("Combo renamed removed Erlang", "26", "26.2.5")
        val model = model(kept, removed, paired("Combo renamed removal", kept.name, kept.homePath!!))
        val page = open(model, "Combo renamed removal")
        val copy = model.findSdk(removed.name)!!
        renameCopy(copy, "Combo renamed removed Erlang left by a refused Apply")

        model.removeSdk(copy)

        assertEquals(listOf(kept.name), page.listedNames())
    }

    fun testAPickWhoseCopyWasRenamedRemovedFromTheModelShowsTheStoredPairingAgain() {
        val stored = SdkFixtures.withCodePaths(erlang("Combo renamed pick stored Erlang", "27", "27.3.4"))
        val picked = SdkFixtures.withCodePaths(erlang("Combo renamed pick shown Erlang", "26", "26.2.5"))
        val model = model(stored, picked, paired("Combo renamed pick paths", stored.name, stored.homePath!!))
        val page = open(model, "Combo renamed pick paths")
        val copy = model.findSdk(picked.name)!!
        page.combo.selectedItem = copy
        renameCopy(copy, "Combo renamed pick shown Erlang left by a refused Apply")

        model.removeSdk(copy)

        assertEquals(stored.name, (page.combo.selectedItem as Sdk?)?.name)
        assertEquals("the removed pick's code paths", emptyList<String>(), page.codePathsUnder(picked.homePath!!))
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

    fun testAPickRemovedFromTheModelBeforeApplyWritesNothing() {
        val picked = erlang("Combo pick removed picked Erlang", "26", "26.2.5")
        val model = model(picked, paired("Combo pick removed", "Combo pick removed gone Erlang", "/gone/erlang"))
        val page = open(model, "Combo pick removed")

        page.combo.selectedItem = model.findSdk(picked.name)
        model.removeSdk(model.findSdk(picked.name)!!)
        page.configurable.apply()

        assertEquals("Combo pick removed gone Erlang", liveErlangName(page.elixirSdk))
    }

    fun testAPickRemovedFromTheModelShowsTheStoredPairingAgain() {
        val stored = SdkFixtures.withCodePaths(erlang("Combo pick removed stored Erlang", "27", "27.3.4"))
        val picked = SdkFixtures.withCodePaths(erlang("Combo pick removed shown Erlang", "26", "26.2.5"))
        val model = model(stored, picked, paired("Combo pick removed paths", stored.name, stored.homePath!!))
        val page = open(model, "Combo pick removed paths")
        assertEquals("precondition: the stored pairing's code paths", codePaths(stored), page.codePathsUnder(stored.homePath!!))

        page.combo.selectedItem = model.findSdk(picked.name)
        model.removeSdk(model.findSdk(picked.name)!!)

        assertEquals(stored.name, (page.combo.selectedItem as Sdk?)?.name)
        assertEquals("the stored pairing's code paths", codePaths(stored), page.codePathsUnder(stored.homePath!!))
        assertEquals("the removed pick's code paths", emptyList<String>(), page.codePathsUnder(picked.homePath!!))
        assertFalse(page.configurable.isModified)
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

    fun testANewModelIsRefusedUntilAnErlangIsChosen() {
        onProjectStructure()
        val erlang = erlang("Field refused Erlang", "27", "27.3.4")
        val model = model(erlang, unpaired("Field refused"))
        val page = open(model, "Field refused")

        assertRefusedForNoErlang(model)

        page.pick(erlang)
        assertNull("a pick answers it", refusal(model))
    }

    fun testAPickIsOnTheDialogsCopyWhenMade() {
        onProjectStructure()
        val erlang = SdkFixtures.withCodePaths(erlang("Field live Erlang", "27", "27.3.4"))
        val model = model(erlang, unpaired("Field live"))
        val page = open(model, "Field live")

        page.pick(erlang)

        assertEquals(erlang.name, liveErlangName(page.elixirSdk))
        assertEquals(erlang.homePath, page.elixirSdk.elixirAdditionalData?.getErlangSdkHomePath())
        assertEquals("the page's paths with it", codePaths(erlang), page.codePathsUnder(erlang.homePath!!))
    }

    /** The model's check and its write run before any page applies. */
    fun testApplyAfterAPickNeedsNoPageApply() {
        onProjectStructure()
        val erlang = erlang("Field no page apply Erlang", "27", "27.3.4")
        val model = model(erlang, unpaired("Field no page apply"))
        val page = open(model, "Field no page apply")
        page.pick(erlang)

        model.apply()

        assertEquals(erlang.name, SdkFixtures.storedErlangName(ProjectJdkTable.getInstance().findJdk("Field no page apply")!!))
    }

    fun testTheTextAndTheOutlineShowUntilAnErlangIsChosen() {
        onProjectStructure()
        val erlang = erlang("Field message Erlang", "27", "27.3.4")
        val model = model(erlang, unpaired("Field message"))
        val page = open(model, "Field message")
        page.assertAsksForAnErlang()

        page.pick(erlang)

        page.assertDoesNotAskForAnErlang()
    }

    fun testTheSaveHintIsGone() {
        onProjectStructure()
        val erlang = erlang("Field hint Erlang", "27", "27.3.4")
        val model = model(erlang, unpaired("Field hint"))
        val page = open(model, "Field hint")

        page.pick(erlang)

        assertFalse("the pick is already on the Model", page.saveHint.isVisible)
    }

    fun testTheFieldListsOnlyErlangTypes() {
        onProjectStructure()
        val erlang = erlang("Field only Erlang", "27", "27.3.4")
        val model = model(erlang, unpaired("Field only"))
        val field = open(model, "Field only").field

        field.model.modelBuilder.reloadActions()

        val rows = field.model.listModel.items.flatMap { if (it is SdkListItem.GroupItem) it.subItems else listOf(it) }
        assertEquals(listOf(erlang.name), rows.filterIsInstance<SdkListItem.SdkItem>().map { it.sdk.name })
        val addRows = rows.filterIsInstance<SdkListItem.ActionItem>()
        assertNotEmpty(addRows)
        addRows.forEach { assertSame(it.toString(), ErlangSdkType.instance, it.action.sdkType) }
    }

    fun testTheFieldShowsTheStoredPairing() {
        onProjectStructure()
        val erlang = erlang("Field stored Erlang", "27", "27.3.4")
        val model = model(erlang, paired("Field stored", erlang.name, erlang.homePath!!))
        val page = open(model, "Field stored")

        assertSame("the model's copy", model.findSdk(erlang.name), (page.field.selectedItem as SdkListItem.SdkItem).sdk)
        assertFalse("showing it is no pick", page.configurable.isModified)
    }

    fun testARemovedHeldPickVoidsANewModel() {
        onProjectStructure()
        val model = model(unpaired("Field void held"))
        val page = open(model, "Field void held")
        val held = page.holdAndPick(model, erlang("Field void held Erlang", "27", "27.3.4"))

        model.removeSdk(held)

        page.assertVoided()
        assertRefusedForNoErlang(model)
    }

    /** The resolver still finds a removed registered Erlang in the table, so only the void stops the check passing. */
    fun testARemovedRegisteredPickVoidsANewModel() {
        onProjectStructure()
        val erlang = saved(erlang("Field void registered Erlang", "27", "27.3.4"))
        val model = model(erlang, unpaired("Field void registered"))
        val page = open(model, "Field void registered")
        page.pick(erlang)

        model.removeSdk(model.findSdk(erlang.name)!!)

        page.assertVoided()
        assertRefusedForNoErlang(model)
    }

    /** `-` on a saved SDK, then a new one under its name: a name lookup in the table takes the new one for saved. */
    fun testANewModelNamedAsOneRemovedInTheDialogIsRefused() {
        onProjectStructure()
        val model = model(saved(unpaired("Field reused name")))
        model.removeSdk(model.findSdk("Field reused name")!!)
        model.addSdk(unpaired("Field reused name"))

        open(model, "Field reused name")

        assertRefusedForNoErlang(model)
    }

    fun testAnotherPageListsAHeldErlangWithoutSelectingIt() {
        onProjectStructure()
        val model = model(unpaired("Field holding"), unpaired("Field other"))
        val holding = open(model, "Field holding")
        val other = open(model, "Field other")

        val held = holding.holdAndPick(model, erlang("Field shared held Erlang", "27", "27.3.4"))

        assertNotNull("listed", other.field.model.listModel.findSdkItem(held))
        assertNull("not selected", other.shown)
        assertNull("not written", liveErlangName(other.elixirSdk))
    }

    fun testPickingAnInstallLeavesTheTableAlone() {
        onProjectStructure()
        val model = model(unpaired("Field table alone"))
        val page = open(model, "Field table alone")
        val before = ProjectJdkTable.getInstance().allJdks.toSet()

        page.holdAndPick(model, erlang("Field table alone Erlang", "27", "27.3.4"))

        assertInstanceOf(page.combo, SdkComboBox::class.java)
        assertEquals("Apply writes both, Cancel neither", before, ProjectJdkTable.getInstance().allJdks.toSet())
    }

    fun testApplyWritesAHeldErlangAndPairsIt() {
        onProjectStructure()
        val model = model(unpaired("Field held apply"))
        val page = open(model, "Field held apply")
        val erlang = erlang("Field held apply Erlang", "27", "27.3.4")
        page.holdAndPick(model, erlang)

        model.apply()

        val table = ProjectJdkTable.getInstance()
        assertNotNull(table.findJdk(erlang.name))
        assertEquals(erlang.name, SdkFixtures.storedErlangName(table.findJdk("Field held apply")!!))
    }

    fun testAnEarlierHeldErlangStaysWhenReplaced() {
        onProjectStructure()
        val model = model(unpaired("Field replaced"))
        val page = open(model, "Field replaced")
        val first = erlang("Field replaced first Erlang", "27", "27.3.4")
        val second = erlang("Field replaced second Erlang", "26", "26.2.5")
        page.holdAndPick(model, first)
        page.holdAndPick(model, second)

        model.apply()

        val table = ProjectJdkTable.getInstance()
        assertNotNull("OK saves it, as the platform's other SDK fields do", table.findJdk(first.name))
        assertEquals(second.name, SdkFixtures.storedErlangName(table.findJdk("Field replaced")!!))
    }

    fun testTheOtpWarningFollowsThePick() {
        val matching = SdkFixtures.erlangSdk("Field OTP matching Erlang", install("27", "27.3.4"))
        val mismatching = SdkFixtures.erlangSdk("Field OTP mismatching Erlang", install("26", "26.2.5"))
        val model = model(matching, mismatching, SdkFixtures.elixirSdk("Field OTP", elixirInstall("1.18.4", "27")))
        val page = open(model, "Field OTP")

        page.pick(mismatching)
        SdkFixtures.waitUntil("the warning shows for the mismatching pick") { page.otpWarning.isVisible }

        page.pick(matching)
        SdkFixtures.waitUntil("the warning goes for the matching pick") { !page.otpWarning.isVisible }
    }

    fun testTheLastOfTwoPicksIsStored() {
        val first = erlang("Field two picks first Erlang", "27", "27.3.4")
        val second = erlang("Field two picks second Erlang", "26", "26.2.5")
        val model = model(first, second, unpaired("Field two picks"))
        val page = open(model, "Field two picks")

        page.pick(first)
        page.pick(second)
        page.configurable.apply()

        assertEquals(second.name, liveErlangName(page.elixirSdk))
    }

    fun testAPickThenCancelLeavesTheStoredPairing() {
        val stored = saved(erlang("Field cancel stored Erlang", "27", "27.3.4"))
        val picked = saved(erlang("Field cancel picked Erlang", "26", "26.2.5"))
        val elixir = saved(paired("Field cancel", stored.name, stored.homePath!!))
        val model = model(stored, picked, elixir)
        val page = open(model, "Field cancel")

        page.pick(picked)

        assertEquals(stored.name, SdkFixtures.storedErlangName(elixir))
    }

    fun testASavedModelWithNoDataIsLeftAlone() {
        val erlang = saved(erlang("Field saved no data Erlang", "27", "27.3.4"))
        val model = model(erlang, saved(unpaired("Field saved no data")))
        val page = open(model, "Field saved no data")

        assertNull(page.elixirSdk.sdkAdditionalData)
        page.assertDoesNotAskForAnErlang()
        assertFalse(page.configurable.isModified)
        assertNull("Apply is not refused", refusal(model))
    }

    fun testASavedModelWithNoDataStoresItsFirstPickOnApply() {
        onProjectStructure()
        val erlang = saved(erlang("Field first pick Erlang", "27", "27.3.4"))
        val elixir = saved(unpaired("Field first pick"))
        val model = model(erlang, elixir)
        val page = open(model, "Field first pick")

        page.pick(erlang)
        page.configurable.apply()
        assertNull("Apply is not refused", refusal(model))

        assertEquals(erlang.name, SdkFixtures.storedErlangName(elixir))
        assertFalse(page.configurable.isModified)
    }

    fun testASavedModelWithAnEmptyPairingShowsNoMessage() {
        val elixir = unpaired("Field saved empty").also { SdkFixtures.commit(it, SdkAdditionalData(it)) }
        val model = model(saved(erlang("Field saved empty Erlang", "27", "27.3.4")), saved(elixir))
        val page = open(model, "Field saved empty")

        page.assertDoesNotAskForAnErlang()
        assertNotNull("Apply is still refused", refusal(model))
    }

    fun testASavedModelWhoseErlangIsGoneIsLeftAlone() {
        val model = model(saved(paired("Field saved gone", "Field gone Erlang", "/gone/erlang")))
        val page = open(model, "Field saved gone")

        assertNull(page.shown)
        page.assertDoesNotAskForAnErlang()
        assertFalse(page.configurable.isModified)
        assertEquals("Field gone Erlang", liveErlangName(page.elixirSdk))
    }

    /** The resolver still finds it in the table, and the field has no row for it. */
    fun testAStoredErlangRemovedWithNoPickShowsNothing() {
        val stored = saved(erlang("Field stored removed Erlang", "27", "27.3.4"))
        val model = model(stored, saved(paired("Field stored removed", stored.name, stored.homePath!!)))
        val page = open(model, "Field stored removed")

        model.removeSdk(model.findSdk(stored.name)!!)

        assertNull(page.shown)
        assertFalse(page.configurable.isModified)
    }

    fun testARemovedHeldPickRestoresASavedModel() {
        val (stored, elixir) = pairedInTable("Field restore held")
        val model = model(stored, elixir)
        val page = open(model, "Field restore held")
        val held = SdkFixtures.withCodePaths(erlang("Field restore held Erlang", "26", "26.2.5"))

        model.removeSdk(page.holdAndPick(model, held))

        page.assertRestored(stored, gone = held)
    }

    fun testARemovedRegisteredPickRestoresASavedModel() {
        val (stored, elixir) = pairedInTable("Field restore registered")
        val picked = saved(SdkFixtures.withCodePaths(erlang("Field restore registered Erlang", "26", "26.2.5")))
        val model = model(stored, picked, elixir)
        val page = open(model, "Field restore registered")
        page.pick(picked)

        model.removeSdk(model.findSdk(picked.name)!!)

        page.assertRestored(stored, gone = picked)
    }

    fun testARemovedPickLeavesASavedModelWithNoDataAlone() {
        val erlang = saved(erlang("Field restore no data Erlang", "27", "27.3.4"))
        val model = model(erlang, saved(unpaired("Field restore no data")))
        val page = open(model, "Field restore no data")
        page.pick(erlang)

        model.removeSdk(model.findSdk(erlang.name)!!)

        assertNull(page.elixirSdk.sdkAdditionalData)
        assertFalse(page.configurable.isModified)
        assertNull(refusal(model))
    }

    fun testARemovedSecondPickRestoresThePairingBeforeTheFirst() {
        onProjectStructure()
        val (stored, elixir) = pairedInTable("Field second voided")
        val first = saved(SdkFixtures.withCodePaths(erlang("Field second voided first Erlang", "26", "26.2.5")))
        val second = saved(SdkFixtures.withCodePaths(erlang("Field second voided second Erlang", "25", "25.3.2")))
        val model = model(stored, first, second, elixir)
        val page = open(model, "Field second voided")
        page.pick(first)
        page.pick(second)

        model.removeSdk(model.findSdk(second.name)!!)

        page.assertRestored(stored, gone = second)
    }

    fun testARemovedPickRestoresThePairingAsRepairedSinceTheReset() {
        onProjectStructure()
        val (stored, elixir) = pairedInTable("Field repaired")
        val picked = saved(SdkFixtures.withCodePaths(erlang("Field repaired other Erlang", "26", "26.2.5")))
        val model = model(stored, picked, elixir)
        val page = open(model, "Field repaired")
        val movedHome = FileUtil.toSystemIndependentName(SdkFixtures.erlangHomeWithEbin("27", "27.3.5"))

        SdkFixtures.commit(model.findSdk(stored.name)!!) {
            name = "Field repaired renamed Erlang"
            homePath = movedHome
        }
        model.apply()
        SdkFixtures.settle()
        assertEquals("precondition: the repair reached the copy", "Field repaired renamed Erlang", liveErlangName(page.elixirSdk))
        page.pick(picked)
        model.removeSdk(model.findSdk(picked.name)!!)

        assertEquals("Field repaired renamed Erlang", liveErlangName(page.elixirSdk))
        assertEquals(movedHome, page.elixirSdk.elixirAdditionalData?.getErlangSdkHomePath())
    }

    fun testTheProjectRowIsAPick() {
        onProjectStructure()
        val erlang = SdkFixtures.withCodePaths(erlang("Field project row Erlang", "27", "27.3.4"))
        val model = model(erlang, unpaired("Field project row"))
        model.projectSdk = model.findSdk(erlang.name)
        val page = open(model, "Field project row")

        page.pickProjectRow()

        assertEquals(erlang.name, liveErlangName(page.elixirSdk))
        assertEquals("the page's paths with it", codePaths(erlang), page.codePathsUnder(erlang.homePath!!))
        page.assertDoesNotAskForAnErlang()
        assertTrue(page.configurable.isModified)
        assertNull("a pick answers it", refusal(model))
    }

    fun testTheProjectRowAfterAPickIsTheOneSaved() {
        onProjectStructure()
        val first = erlang("Field project row second first Erlang", "27", "27.3.4")
        val row = erlang("Field project row second row Erlang", "26", "26.2.5")
        val model = model(first, row, unpaired("Field project row second"))
        model.projectSdk = model.findSdk(row.name)
        val page = open(model, "Field project row second")
        page.pick(first)

        page.pickProjectRow()
        page.configurable.apply()

        assertEquals(row.name, liveErlangName(page.elixirSdk))
    }

    fun testTheProjectRowMovesASavedModelsCodePaths() {
        onProjectStructure()
        val (stored, elixir) = pairedInTable("Field project row paths")
        val row = saved(SdkFixtures.withCodePaths(erlang("Field project row paths row Erlang", "26", "26.2.5")))
        val model = model(stored, row, elixir)
        model.projectSdk = model.findSdk(row.name)
        val page = open(model, "Field project row paths")

        page.pickProjectRow()

        assertEquals(row.name, liveErlangName(page.elixirSdk))
        assertEquals("the row's code paths", codePaths(row), page.codePathsUnder(row.homePath!!))
        assertEquals("the stored pairing's code paths", emptyList<String>(), page.codePathsUnder(stored.homePath!!))
    }

    fun testLeavingTheProjectRowTakesItsCodePaths() {
        onProjectStructure()
        val row = SdkFixtures.withCodePaths(erlang("Field project row left row Erlang", "27", "27.3.4"))
        val next = erlang("Field project row left next Erlang", "26", "26.2.5")
        val model = model(row, next, unpaired("Field project row left"))
        model.projectSdk = model.findSdk(row.name)
        val page = open(model, "Field project row left")
        page.pickProjectRow()

        page.pick(next)

        assertEquals(emptyList<String>(), page.codePathsUnder(row.homePath!!))
    }

    fun testTheOtpWarningFollowsTheProjectRow() {
        onProjectStructure()
        val mismatching = SdkFixtures.erlangSdk("Field project row OTP Erlang", install("26", "26.2.5"))
        val model = model(mismatching, SdkFixtures.elixirSdk("Field project row OTP", elixirInstall("1.18.4", "27")))
        model.projectSdk = model.findSdk(mismatching.name)
        val page = open(model, "Field project row OTP")

        page.pickProjectRow()

        SdkFixtures.waitUntil("the warning shows for the row's Erlang") { page.otpWarning.isVisible }
    }

    fun testARemovedProjectRowPickVoidsANewModel() {
        onProjectStructure()
        val erlang = saved(erlang("Field project row void Erlang", "27", "27.3.4"))
        val model = model(erlang, unpaired("Field project row void"))
        model.projectSdk = model.findSdk(erlang.name)
        val page = open(model, "Field project row void")
        page.pickProjectRow()

        model.removeSdk(model.findSdk(erlang.name)!!)

        page.assertVoided()
        assertRefusedForNoErlang(model)
    }

    /** A pairing names an SDK, while the row follows the project's. */
    fun testTheProjectRowKeepsTheSdkItWasChosenFor() {
        onProjectStructure()
        val chosen = erlang("Field project row kept chosen Erlang", "27", "27.3.4")
        val later = erlang("Field project row kept later Erlang", "26", "26.2.5")
        val model = model(chosen, later, unpaired("Field project row kept"))
        model.projectSdk = model.findSdk(chosen.name)
        val page = open(model, "Field project row kept")
        page.pickProjectRow()

        model.projectSdk = model.findSdk(later.name)
        page.configurable.apply()

        assertEquals(chosen.name, liveErlangName(page.elixirSdk))
    }

    /** The row stays after the project's SDK goes, naming none, and no row of the field clears a pairing. */
    fun testAProjectRowNamingNoSdkLeavesThePickAsItWas() {
        onProjectStructure()
        val picked = SdkFixtures.withCodePaths(erlang("Field project row none picked Erlang", "27", "27.3.4"))
        val gone = erlang("Field project row none gone Erlang", "26", "26.2.5")
        val model = model(picked, gone, unpaired("Field project row none"))
        model.projectSdk = model.findSdk(gone.name)
        val page = open(model, "Field project row none")
        page.pick(picked)
        model.removeSdk(model.findSdk(gone.name)!!)

        page.pickProjectRow()
        page.configurable.apply()

        assertEquals(picked.name, liveErlangName(page.elixirSdk))
        assertEquals(codePaths(picked), page.codePathsUnder(picked.homePath!!))
    }

    fun testAProjectRowNamingNoSdkIsNoPick() {
        onProjectStructure()
        val gone = erlang("Field project row no pick gone Erlang", "26", "26.2.5")
        val model = model(gone, unpaired("Field project row no pick"))
        model.projectSdk = model.findSdk(gone.name)
        val page = open(model, "Field project row no pick")
        model.removeSdk(model.findSdk(gone.name)!!)

        page.pickProjectRow()

        page.assertAsksForAnErlang()
        assertFalse(page.configurable.isModified)
    }

    fun testASmallIdesPageIsUnchanged() {
        onProjectStructure()
        ProcessOutput.isSmallIdeOverride = true
        try {
            assertPageIsThePlainCombo("Field small")
        } finally {
            ProcessOutput.isSmallIdeOverride = null
        }
    }

    /** `ProjectJdksConfigurable`'s one-argument constructor creates the same service and takes its model, with no UI up. */
    fun testAPageOutsideProjectStructureKeepsTodaysCombo() {
        val model = ProjectStructureConfigurable.getInstance(project).projectJdksModel
        try {
            assertPageIsThePlainCombo("Field outside", model)
        } finally {
            model.reset(project)
        }
    }

    /**
     * That host adds a node only from its own +, and `apply` removes a model SDK with no node before the model's check. The
     * Elixir SDK leaves the table once the node is built, which is where a Model made by that + stands.
     */
    fun testAModelSdkWithNoNodeIsDroppedAndThePairingRefused() {
        val dropped = erlang("Host dropped Erlang", "27", "27.3.4")
        val elixir = saved(SdkFixtures.commitPairing(unpaired("Host Elixir"), dropped))
        val model = ProjectSdksModel()
        val host = ProjectJdksConfigurable(project, model)
        Disposer.register(testRootDisposable, host::disposeUIResources)
        host.reset()
        val table = ProjectJdkTable.getInstance()
        WriteAction.run<Throwable> { table.removeJdk(elixir) }
        model.addSdk(dropped)

        assertThrows(ConfigurationException::class.java) { host.apply() }

        assertNull(table.findJdk(dropped.name))
        assertNull(table.findJdk("Host Elixir"))
        assertEquals("the pairing stored, not picked", dropped.name, liveErlangName(model.findSdk("Host Elixir")!!))
    }

    private class Page(
        private val sdkModel: SdkModel,
        val configurable: AdditionalDataConfigurable,
        val elixirSdk: Sdk,
        val combo: ComboBox<*>,
        val suppress: JBCheckBox,
        private val modificator: SdkModificator,
        private val component: JComponent,
    ) {
        fun listedNames(): List<String> = (0 until combo.itemCount).map { (combo.getItemAt(it) as Sdk).name }

        /** The code paths under [home] the page's path editors hold, before any Apply. */
        fun codePathsUnder(home: String): List<String> = SdkFixtures.pathsUnder(modificator.getRoots(OrderRootType.CLASSES), home)

        val field: SdkComboBox get() = assertInstanceOf(combo, SdkComboBox::class.java)

        /** Today's combo holds [Sdk]s; the platform's field holds rows, whose own [Sdk] is the model's copy. */
        val shown: Sdk?
            get() = when (val item = combo.selectedItem) {
                is SdkListItem.SdkItem -> item.sdk
                is Sdk -> item
                else -> null
            }

        /** The model's copy of [erlang] picked, in whichever field the page has. */
        fun pick(erlang: Sdk) {
            val copy = sdkModel.findSdk(erlang.name)!!
            val platformField = combo as? SdkComboBox
            if (platformField == null) {
                combo.selectedItem = copy
            } else {
                platformField.selectedItem = platformField.model.listModel.findSdkItem(copy)!!
            }
        }

        /** The row for the project's SDK, which the field lists when the model has one as it opens. */
        fun pickProjectRow() {
            field.selectedItem = field.model.listModel.findProjectSdkItem()!!
        }

        /** [erlang] added to [model] and picked, returning its copy. The platform's add rows pick inside `doAdd`'s callback. */
        fun holdAndPick(model: ProjectSdksModel, erlang: Sdk): Sdk {
            val platformField = combo as? SdkComboBox
            if (platformField == null) {
                model.doAdd(erlang, null)
                return model.findSdk(erlang.name)!!.also { combo.selectedItem = it }
            }
            lateinit var copy: Sdk
            model.doAdd(erlang) { added ->
                copy = added
                platformField.reloadModel()
                platformField.selectedItem = platformField.model.listModel.findSdkItem(added)!!
            }
            return copy
        }

        private val labels: List<JLabel> get() = UIUtil.findComponentsOfType(component, JLabel::class.java)
        val saveHint: JLabel get() = labels.single { it.text.startsWith("After changing the Erlang SDK") }
        val otpWarning: JLabel get() = labels.single { it.icon === AllIcons.General.Warning && !it.text.startsWith("Erlang classpath") }

        private val textShown: Boolean get() = labels.any { it.text == NO_ERLANG && it.isVisible }
        private val outline: Any? get() = combo.getClientProperty("JComponent.outline")
        private val popupMessage: String? get() = ComponentValidator.getInstance(combo).orElse(null)?.validationInfo?.message

        fun assertAsksForAnErlang() {
            assertTrue("the text under the field", textShown)
            assertEquals("the field's outline", "error", outline)
            assertEquals("the outline's popup", NO_ERLANG, popupMessage)
        }

        fun assertDoesNotAskForAnErlang() {
            assertFalse("the text under the field", textShown)
            assertNull("the field's outline", outline)
            assertNull("the outline's popup", popupMessage)
        }

        fun assertVoided() {
            assertNull("the field is empty", shown)
            assertAsksForAnErlang()
            assertNull(elixirSdk.elixirAdditionalData?.getErlangSdkName())
        }

        fun assertRestored(stored: Sdk, gone: Sdk) {
            assertSame(sdkModel.findSdk(stored.name), shown)
            assertEquals(stored.name, elixirSdk.elixirAdditionalData?.getErlangSdkName())
            assertEquals("the stored pairing's code paths", codePaths(stored), codePathsUnder(stored.homePath!!))
            assertEquals("the removed pick's code paths", emptyList<String>(), codePathsUnder(gone.homePath!!))
            assertFalse(configurable.isModified)
        }
    }

    /** As `SdkEditor` builds the page, over a modificator that refuses additional data. */
    private class EditedModificator(delegate: SdkModificator) : SdkModificator by delegate {
        override fun setSdkAdditionalData(data: PlatformSdkAdditionalData?) = throw UnsupportedOperationException()
    }

    /** The page for the model's copy of the Elixir SDK named [elixirName], reset as the SDK editor shows it. */
    private fun open(model: SdkModel, elixirName: String): Page {
        val elixirSdk = model.findSdk(elixirName)!!
        val modificator = EditedModificator(elixirSdk.sdkModificator)
        val configurable = AdditionalDataConfigurable(model, modificator)
        configurable.setSdk(elixirSdk)
        val component = configurable.createComponent()
        configurable.reset()
        opened += configurable

        return Page(
            model,
            configurable,
            elixirSdk,
            UIUtil.findComponentOfType(component, ComboBox::class.java)!!,
            UIUtil.findComponentOfType(component, JBCheckBox::class.java)!!,
            modificator,
            component,
        )
    }

    /** The page answers that it is on a Project Structure over its model, as a bare `ProjectSdksModel` otherwise isn't. */
    private fun onProjectStructure() {
        val onProject = project
        ApplicationManager.getApplication().replaceService(
            SdkPageInDialog::class.java,
            object : SdkPageInDialog {
                override fun projectOf(sdkModel: SdkModel): Project = onProject
            },
            testRootDisposable,
        )
    }

    /** A new Model with no data is left so, the plain combo stays, and a pick shows the hint and writes nothing yet. */
    private fun assertPageIsThePlainCombo(name: String, model: ProjectSdksModel = model()) {
        val erlang = erlang("$name Erlang", "27", "27.3.4")
        model.addSdk(erlang)
        model.addSdk(unpaired(name))
        val page = open(model, name)

        assertFalse(page.combo is SdkComboBox)
        assertNull(page.elixirSdk.sdkAdditionalData)

        page.pick(erlang)

        assertTrue(page.saveHint.isVisible)
        assertNull(liveErlangName(page.elixirSdk))
    }

    private fun refusal(model: ProjectSdksModel): String? =
        try {
            model.apply()
            null
        } catch (e: ConfigurationException) {
            e.message
        }

    private fun assertRefusedForNoErlang(model: ProjectSdksModel) {
        val refusal = refusal(model)
        assertTrue("refused with the sentence, not: $refusal", refusal?.endsWith(NO_ERLANG) == true)
    }

    /** [sdk] in the table, as the table holds it. */
    private fun saved(sdk: Sdk): Sdk {
        SdkFixtures.register(sdk)
        return ProjectJdkTable.getInstance().findJdk(sdk.name)!!
    }

    /** A saved Erlang SDK and a saved Elixir SDK [name] paired with it, its code paths set up from it. */
    private fun pairedInTable(name: String): Pair<Sdk, Sdk> = SdkFixtures.pairedInTable(name, add = ::saved)

    private fun unpaired(name: String): Sdk = SdkFixtures.elixirSdk(name, SdkFixtures.elixirHome("1.18.4"))

    /** Names the model's [copy] [name] while its table SDK keeps its own, as a refused Apply leaves a page's Name field. */
    private fun renameCopy(copy: Sdk, name: String) = SdkFixtures.commit(copy) { this.name = name }

    private fun erlang(name: String, major: String, otpVersion: String): Sdk =
        SdkFixtures.erlangSdk(name, SdkFixtures.erlangHomeWithEbin(major, otpVersion))

    private fun paired(name: String, erlangName: String, erlangHome: String): Sdk =
        SdkFixtures.commitPairing(SdkFixtures.elixirSdk(name, SdkFixtures.elixirHome("1.18.4")), erlangName, erlangHome)

    /** The Erlang SDK name the live additional data holds, unlike [SdkFixtures.storedErlangName]'s XML read. */
    private fun liveErlangName(sdk: Sdk): String? = sdk.elixirAdditionalData?.getErlangSdkName()
}
