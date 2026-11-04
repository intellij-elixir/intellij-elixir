package org.elixir_lang.sdk.erlang

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.testFramework.common.runAll
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.facet.SdksService
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.persistedElixirData
import org.elixir_lang.sdk.SdkFixtures.register
import org.elixir_lang.sdk.SdkFixtures.settle
import org.elixir_lang.sdk.SdkFixtures.storedErlangName

/**
 * Renaming an Erlang SDK in an SDK editor pairs the Elixir SDKs that stored its old name with its new one, though the
 * model's apply writes its copy of each Elixir SDK after the Erlang SDK's rename. Driven as the editors do: the copy
 * renamed and committed, then the Erlang type's additional-data configurable applied, then the model.
 */
class ErlangRenameTest : PlatformTestCase() {
    private lateinit var table: SdkFixtures.TableSnapshot

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
    }

    override fun tearDown() = runAll(
        { table.restore() },
        { SdksService.getInstance()!!.resetForTests() },
        { super.tearDown() },
    )

    /** IntelliJ IDEA's Project Structure, whose model is the platform's own and is applied again by a second Apply. */
    fun testARenameInIdeasProjectStructureRepairsItsElixirSdks() {
        val elixir = pairedWithErlang("Rename IDEA")
        val model = ProjectSdksModel()
        model.reset(project)

        Editor(model, "Rename IDEA Erlang").rename("Rename IDEA Erlang renamed")
        model.apply()
        settle()

        assertNotNull("precondition: the rename reached the table", ProjectJdkTable.getInstance().findJdk("Rename IDEA Erlang renamed"))
        assertEquals("after the Apply", "Rename IDEA Erlang renamed", storedErlangName(elixir))

        model.apply()
        settle()

        assertEquals("after a second Apply", "Rename IDEA Erlang renamed", storedErlangName(elixir))
    }

    /** The small IDEs' SDKs pages, whose model is `SdksService`'s. */
    fun testARenameOnTheSmallIdesPagesRepairsItsElixirSdks() {
        val elixir = pairedWithErlang("Rename small")
        val service = SdksService.getInstance()!!
        service.resetForTests()
        val model = service.getModel()

        Editor(model, "Rename small Erlang").rename("Rename small Erlang renamed")
        service.apply(model)
        settle()

        assertNotNull("precondition: the rename reached the table", ProjectJdkTable.getInstance().findJdk("Rename small Erlang renamed"))
        assertEquals("Rename small Erlang renamed", storedErlangName(elixir))
    }

    /** Two renames in one Project Structure dialog, through one editor: the second pairs the Elixir SDKs again. */
    fun testTwoRenamesInOneDialogRepairItsElixirSdks() {
        val elixir = pairedWithErlang("Rename twice")
        val model = ProjectSdksModel()
        model.reset(project)
        val editor = Editor(model, "Rename twice Erlang")

        editor.rename("Rename twice Erlang first")
        model.apply()
        settle()
        editor.rename("Rename twice Erlang second")
        model.apply()
        settle()

        assertEquals("Rename twice Erlang second", storedErlangName(elixir))
    }

    /** A rename made in the table outside any dialog's model, as the registrar's `updateJdk` makes one. */
    fun testARenameInTheTableRepairsItsElixirSdks() {
        val elixir = pairedWithErlang("Rename table")
        val erlang = ProjectJdkTable.getInstance().findJdk("Rename table Erlang")!!
        val clone = erlang.clone() as Sdk

        WriteAction.run<Throwable> {
            clone.sdkModificator.apply {
                name = "Rename table Erlang renamed"
                commitChanges()
            }
            ProjectJdkTable.getInstance().updateJdk(erlang, clone)
        }
        settle()

        assertEquals("Rename table Erlang renamed", storedErlangName(elixir))
    }

    /**
     * Two renames in one Project Structure Apply, where one Erlang SDK takes the other's old name and its editor applies
     * first: each Elixir SDK stays with its own Erlang SDK, though the first repair writes the name the second renames
     * away from. The giver is first in the table, so the model commits its rename first: a commit renaming an SDK to a
     * name another still holds leaves the table one of the two, so the other order, a swap, or the same renames made in
     * the table never reach the repairs with both SDKs.
     */
    fun testRenamesInOneDialogThatReuseAnOldNameKeepEachPairing() {
        val (taker, giver) = twoPairs("Reuse")
        val model = ProjectSdksModel()
        model.reset(project)
        val takersEditor = Editor(model, "Reuse first Erlang")
        val giversEditor = Editor(model, "Reuse second Erlang")

        takersEditor.rename("Reuse second Erlang")
        giversEditor.rename("Reuse third Erlang")
        model.apply()
        settle()

        assertPairedAt(taker, "Reuse second Erlang")
        assertPairedAt(giver, "Reuse third Erlang")
    }

    /** An Erlang SDK named "<prefix> Erlang", then an Elixir SDK paired with it, so after it in the table. */
    private fun pairedWithErlang(prefix: String): Sdk {
        val erlang = register(SdkFixtures.erlangSdk("$prefix Erlang", SdkFixtures.erlangHomeWithEbin("27", "27.3.4")))
        return SdkFixtures.commitPairing(register(SdkFixtures.elixirSdk("$prefix Elixir", SdkFixtures.elixirHome("1.18.4"))), erlang)
    }

    /**
     * Erlang SDKs "<prefix> second Erlang", then "<prefix> first Erlang", at two homes, each with its own Elixir SDK
     * registered after both. Returns the first's Elixir SDK, then the second's.
     */
    private fun twoPairs(prefix: String): Pair<Sdk, Sdk> {
        val second = register(SdkFixtures.erlangSdk("$prefix second Erlang", SdkFixtures.erlangHomeWithEbin("26", "26.2.5")))
        val first = register(SdkFixtures.erlangSdk("$prefix first Erlang", SdkFixtures.erlangHomeWithEbin("27", "27.3.4")))
        val pairedWith = { erlang: Sdk, name: String ->
            SdkFixtures.commitPairing(register(SdkFixtures.elixirSdk(name, SdkFixtures.elixirHome("1.18.4"))), erlang)
        }
        return pairedWith(first, "$prefix first Elixir") to pairedWith(second, "$prefix second Elixir")
    }

    /** [elixir] stores the name and the home of the Erlang SDK now named [erlangName]. */
    private fun assertPairedAt(elixir: Sdk, erlangName: String) {
        val erlang = ProjectJdkTable.getInstance().findJdk(erlangName)!!
        assertEquals("${elixir.name}'s Erlang name", erlangName, storedErlangName(elixir))
        assertEquals("${elixir.name}'s Erlang home", erlang.homePath, persistedElixirData(elixir)?.getErlangSdkHomePath())
    }

    /**
     * An open SDK editor for the copy named [name] in [model], as `SdkEditor` is: its configurables set and reset, then
     * on Apply the copy changed and committed, then theirs applied.
     */
    private class Editor(model: ProjectSdksModel, name: String) {
        private val copy = model.findSdk(name)!!
        private val configurable = (copy.sdkType as SdkType).createAdditionalDataConfigurable(model, copy.sdkModificator)?.apply {
            setSdk(copy)
            reset()
        }

        fun rename(newName: String) {
            WriteAction.run<Throwable> {
                copy.sdkModificator.apply {
                    this.name = newName
                    commitChanges()
                }
            }
            configurable?.apply()
        }
    }
}
