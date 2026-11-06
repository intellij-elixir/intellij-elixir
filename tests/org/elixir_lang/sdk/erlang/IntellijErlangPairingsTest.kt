package org.elixir_lang.sdk.erlang

import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.projectRoots.ui.SdkEditor
import com.intellij.openapi.roots.ui.configuration.ProjectStructureConfigurable
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.common.runAll
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.facet.SdksService
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.codePathsUnder
import org.elixir_lang.sdk.SdkFixtures.inADialog
import org.elixir_lang.sdk.SdkFixtures.register
import org.elixir_lang.sdk.SdkFixtures.settle
import org.elixir_lang.sdk.SdkFixtures.storedErlangHome
import org.elixir_lang.sdk.SdkFixtures.storedErlangName
import org.elixir_lang.sdk.erlang_dependent.Asked
import org.elixir_lang.sdk.erlang_dependent.ScriptedErlangStep
import java.io.File
import org.intellij.erlang.sdk.ErlangSdkType as IntellijErlangSdkType

/**
 * intellij-erlang's "Erlang SDK" in IntelliJ IDEA's Project Structure: its SDK editor has no configurable of the plugin's
 * own, but the dialog's model is followed through its Elixir SDKs' editors, so a rename or a move there reaches the Elixir
 * SDKs paired with it, in the table and in the dialog's copies.
 */
class IntellijErlangPairingsTest : PlatformTestCase() {
    private val asked = Asked()
    private val dialog = Disposer.newDisposable("Project Structure")
    private lateinit var editors: Map<Sdk, SdkEditor>
    private lateinit var table: SdkFixtures.TableSnapshot
    private lateinit var type: IntellijErlangSdkType
    private lateinit var model: ProjectSdksModel

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        asked.recordMessages(testRootDisposable)
        type = IntellijErlangSdkType()
        SdkType.EP_NAME.point.registerExtension(type, testRootDisposable)
        model = ProjectStructureConfigurable.getInstance(project).projectJdksModel
    }

    override fun tearDown() = runAll(
        { Disposer.dispose(dialog) },
        { model.disposeUIResources() },
        { table.restore() },
        { SdksService.getInstance()!!.resetForTests() },
        { super.tearDown() },
    )

    fun testARenameIsFollowedInTheTableAndTheModel() {
        val (erlang, elixir) = paired("Dialog renames")

        inADialog {
            rename(erlang, "Dialog renames Erlang renamed")
            model.apply()
            settle()

            assertEquals("the table's Elixir SDK", "Dialog renames Erlang renamed", storedErlangName(elixir))
            assertEquals("the model's copy", "Dialog renames Erlang renamed", storedErlangName(model.projectSdks[elixir]!!))
        }
    }

    /** Each Elixir SDK's editor holds the model open, so closing one of two leaves it followed. */
    fun testTheModelIsFollowedWhileAnyOfItsElixirSdkEditorsIsOpen() {
        val (erlang, elixir) = paired("Dialog closes one") { erlang ->
            SdkFixtures.commitPairing(register(SdkFixtures.elixirSdk("Dialog closes one other Elixir", SdkFixtures.elixirHome("1.18.4"))), erlang)
        }
        val other = ProjectJdkTable.getInstance().findJdk("Dialog closes one other Elixir")!!
        editors.getValue(model.projectSdks[other]!!).disposeUIResources()

        inADialog {
            rename(erlang, "Dialog closes one Erlang renamed")
            model.apply()
            settle()

            assertEquals("Dialog closes one Erlang renamed", storedErlangName(model.projectSdks[elixir]!!))
        }
    }

    fun testAMoveIsFollowed() {
        val (erlang, elixir) = paired("Dialog moves")
        val oldHome = erlang.homePath!!
        val newHome = SdkFixtures.erlangHomeWithEbin("26", "26.2.5")

        inADialog {
            move(erlang, newHome)
            model.apply()
            settle()

            assertFollowed(elixir, newHome, oldHome, "the table's Elixir SDK")
            assertFollowed(model.projectSdks[elixir]!!, newHome, oldHome, "the model's copy")
        }
    }

    fun testASecondApplyInTheDialogKeepsTheMove() {
        val (erlang, elixir) = paired("Dialog applies twice")
        val oldHome = erlang.homePath!!
        val newHome = SdkFixtures.erlangHomeWithEbin("26", "26.2.5")

        inADialog {
            move(erlang, newHome)
            model.apply()
            settle()
            model.apply()
            settle()

            assertFollowed(elixir, newHome, oldHome, "the table's Elixir SDK after the second Apply")
        }
    }

    fun testARenameAndAMoveThenADeleteInTheDialogAsks() {
        val (erlang, elixir) = paired("Dialog rehomes and deletes")
        ScriptedErlangStep(asked, ScriptedErlangStep.cancel).install(testRootDisposable)

        inADialog {
            rename(erlang, "Dialog rehomes and deletes Erlang renamed")
            move(erlang, SdkFixtures.erlangHomeWithEbin("26", "26.2.5"))
            model.apply()
            settle()

            model.removeSdk(model.projectSdks[erlang]!!)
            model.apply()
            settle()

            assertEmpty("nothing is asked while the dialog is open", asked.events)
        }
        settle()

        assertEquals("the Elixir SDK paired with the deleted one is asked once", listOf("erlang"), asked.events)
        assertEquals("Dialog rehomes and deletes Erlang renamed", storedErlangName(elixir))
    }

    fun testADialogOpenedAboveHoldsTheRenameOff() {
        val (erlang, elixir) = paired("Dialog nested")

        inADialog {
            rename(erlang, "Dialog nested Erlang renamed")
            model.apply()

            inADialog {
                settle()

                assertEquals("not followed while a dialog above is open", "Dialog nested Erlang", storedErlangName(elixir))
            }
            settle()

            assertEquals("followed once it closes", "Dialog nested Erlang renamed", storedErlangName(elixir))
        }
    }

    fun testARenameThenADeleteAsks() {
        val (erlang, elixir) = paired("Dialog deletes")
        rename(erlang, "Dialog deletes Erlang renamed")
        model.apply()
        settle()
        ScriptedErlangStep(asked, ScriptedErlangStep.cancel).install(testRootDisposable)

        SdkConfigurationUtil.removeSdk(ProjectJdkTable.getInstance().findJdk("Dialog deletes Erlang renamed")!!)
        settle()

        assertEquals("the Elixir SDK paired with the deleted one is asked", listOf("erlang"), asked.events)
        assertEquals("Dialog deletes Erlang renamed", storedErlangName(elixir))
    }

    /** [elixir] is paired at [newHome] and has its stdlib ebin, and none under [oldHome]. */
    private fun assertFollowed(elixir: Sdk, newHome: String, oldHome: String, message: String) {
        val home = FileUtil.toSystemIndependentName(newHome)
        assertEquals("$message: the stored Erlang home", home, storedErlangHome(elixir)?.let(FileUtil::toSystemIndependentName))
        assertEquals("$message: the new home's code paths", listOf("$home/lib/stdlib-26.2.5/ebin"), codePathsUnder(elixir, newHome))
        assertEmpty("$message: the old home's code paths", codePathsUnder(elixir, oldHome))
    }

    /** [erlang]'s copy in [model] renamed to [newName] in its Project Structure editor. */
    private fun rename(erlang: Sdk, newName: String) {
        editors.getValue(model.projectSdks[erlang]!!).apply {
            setNewSdkName(newName)
            apply()
        }
    }

    /** [erlang]'s copy in [model] moved to [newHome], with that home's class root, as its editor's home field does. */
    private fun move(erlang: Sdk, newHome: String) = SdkFixtures.moveHome(model.projectSdks[erlang]!!, newHome)

    /**
     * An intellij-erlang Erlang SDK "<name> Erlang" with its class root, then an Elixir SDK "<name> Elixir" paired with it
     * and set up, both in the table, and [more] given the Erlang SDK; then [model] reset to hold them, and Project
     * Structure open over it.
     */
    private fun paired(name: String, more: (Sdk) -> Unit = {}): Pair<Sdk, Sdk> {
        val (erlang, elixir) = SdkFixtures.pairedInTable("$name Elixir", erlangName = "$name Erlang", erlangType = type)
        more(erlang)
        assertEquals(
            "precondition: set up with the Erlang SDK's stdlib ebin",
            listOf(File(erlang.homePath!!, "lib/stdlib-27.3.4/ebin").path.let(FileUtil::toSystemIndependentName)),
            codePathsUnder(elixir, erlang.homePath!!),
        )
        model.reset(project)
        editors = SdkFixtures.openEditors(project, model, dialog)
        return erlang to elixir
    }
}
