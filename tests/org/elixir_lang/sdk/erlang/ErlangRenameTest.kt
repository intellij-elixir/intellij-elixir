package org.elixir_lang.sdk.erlang

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.common.runAll
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.facet.SdksService
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.codePaths
import org.elixir_lang.sdk.SdkFixtures.codePathsUnder
import org.elixir_lang.sdk.SdkFixtures.register
import org.elixir_lang.sdk.SdkFixtures.settle
import org.elixir_lang.sdk.SdkFixtures.storedErlangHome
import org.elixir_lang.sdk.SdkFixtures.storedErlangName
import org.elixir_lang.sdk.elixir.ElixirSdkPathConfigurator
import org.elixir_lang.sdk.erlang_dependent.getErlangSdk

/**
 * Renaming an Erlang SDK in an SDK editor pairs the Elixir SDKs that stored its old name with its new one, and moving its
 * home pairs those that stored its old home with its new one, though the model's apply writes its copy of each Elixir
 * SDK after the Erlang SDK's change. Driven as the editors do: the copy changed and committed, then the Erlang type's
 * additional-data configurable applied, then the model.
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
        val model = ideaModel()

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
        val model = smallIdesModel()

        Editor(model, "Rename small Erlang").rename("Rename small Erlang renamed")
        SdksService.getInstance()!!.apply()
        settle()

        assertNotNull("precondition: the rename reached the table", ProjectJdkTable.getInstance().findJdk("Rename small Erlang renamed"))
        assertEquals("Rename small Erlang renamed", storedErlangName(elixir))
    }

    /** Two renames in one Project Structure dialog, through one editor: the second pairs the Elixir SDKs again. */
    fun testTwoRenamesInOneDialogRepairItsElixirSdks() {
        val elixir = pairedWithErlang("Rename twice")
        val model = ideaModel()
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

        SdkFixtures.renameInTable("Rename table Erlang", "Rename table Erlang renamed")
        settle()

        assertEquals("Rename table Erlang renamed", storedErlangName(elixir))
    }

    /**
     * A rename alone, on IntelliJ IDEA's Project Structure, of an Erlang SDK whose Elixir SDK stores its name with another
     * home that another Erlang SDK still holds: the repair pairs the Elixir SDK at the renamed SDK's home, and its code
     * paths follow.
     */
    fun testARenameThatPairsAtAnotherHomeMovesTheCodePaths() {
        val (elixir, staleHome) = pairedAtAStaleHome("Rename rehomes")
        val model = ideaModel()

        Editor(model, "Rename rehomes Erlang").rename("Rename rehomes Erlang renamed")
        model.apply()
        settle()

        assertFollowed(elixir, "Rename rehomes Erlang renamed", staleHome)
    }

    /**
     * The same rename made in the table outside any dialog's model: the table's listener repairs the pairing inside the
     * table's write action, and the code paths follow once it ends.
     */
    fun testARenameInTheTableThatPairsAtAnotherHomeMovesTheCodePaths() {
        val (elixir, staleHome) = pairedAtAStaleHome("Table rehomes")

        SdkFixtures.renameInTable("Table rehomes Erlang", "Table rehomes Erlang renamed")
        settle()

        assertFollowed(elixir, "Table rehomes Erlang renamed", staleHome)
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
        val model = ideaModel()
        val takersEditor = Editor(model, "Reuse first Erlang")
        val giversEditor = Editor(model, "Reuse second Erlang")

        takersEditor.rename("Reuse second Erlang")
        giversEditor.rename("Reuse third Erlang")
        model.apply()
        settle()

        assertPairedAt(taker, "Reuse second Erlang")
        assertPairedAt(giver, "Reuse third Erlang")
    }

    /** IntelliJ IDEA's Project Structure. */
    fun testAHomeMovedInIdeasProjectStructureIsFollowedByItsElixirSdks() =
        assertMoveFollowed("Move IDEA", ::ideaModel) { it.apply() }

    /** The small IDEs' SDKs pages. */
    fun testAHomeMovedOnTheSmallIdesPagesIsFollowedByItsElixirSdks() =
        assertMoveFollowed("Move small", ::smallIdesModel) { SdksService.getInstance()!!.apply() }

    /** A rename and a move in one Apply, on IntelliJ IDEA's Project Structure. */
    fun testAHomeMovedAndRenamedInOneApplyIsFollowedByItsElixirSdks() =
        assertMoveFollowed("Move and rename", ::ideaModel, renameTo = "Move and rename Erlang renamed") { it.apply() }

    /**
     * The Erlang SDK's home moved, and the SDK renamed [renameTo] if given, in an editor on the model [model] gives, then
     * [apply]: the Elixir SDK follows the home, so a later SDK at the old home doesn't take it, and its code paths are the
     * new home's Erlang ones and none of the old home's. After the move, another Erlang SDK is registered at the old
     * home, as the tool manager does.
     */
    private fun assertMoveFollowed(
        prefix: String,
        model: () -> ProjectSdksModel,
        renameTo: String? = null,
        apply: (ProjectSdksModel) -> Unit,
    ) {
        val elixir = pairedWithErlang(prefix)
        val erlangName = "$prefix Erlang"
        val newHome = SdkFixtures.erlangHomeWithEbin("26", "26.2.5")
        val oldHome = ProjectJdkTable.getInstance().findJdk(erlangName)!!.homePath!!
        val sdksModel = model()

        Editor(sdksModel, erlangName).change(renameTo, newHome)
        apply(sdksModel)
        settle()

        val movedName = renameTo ?: erlangName
        val moved = ProjectJdkTable.getInstance().findJdk(movedName)!!
        assertEquals(
            "precondition: the move reached the table",
            FileUtil.toSystemIndependentName(newHome),
            FileUtil.toSystemIndependentName(moved.homePath!!),
        )
        assertFollowed(elixir, movedName, oldHome)

        register(SdkFixtures.erlangSdk("$movedName at the old home", oldHome))

        assertSame(moved, ReadAction.computeBlocking<Sdk?, RuntimeException> { elixir.getErlangSdk() })
    }

    /** A Project Structure dialog's model, as IntelliJ IDEA's holds it. */
    private fun ideaModel(): ProjectSdksModel = ProjectSdksModel().apply { reset(project) }

    /** The small IDEs' SDKs pages' model, `SdksService`'s, built afresh from the table. */
    private fun smallIdesModel(): ProjectSdksModel = SdksService.getInstance()!!.run {
        resetForTests()
        getModel()
    }

    /**
     * An Erlang SDK named "<prefix> Erlang", then an Elixir SDK paired with it, so after it in the table, each with the
     * code paths its type sets up.
     */
    private fun pairedWithErlang(prefix: String): Sdk {
        val (erlang, elixir) = SdkFixtures.pairedInTable("$prefix Elixir", erlangName = "$prefix Erlang")
        assertEquals("precondition: the Erlang code paths", codePaths(erlang), codePathsUnder(elixir, erlang.homePath!!))
        return elixir
    }

    /**
     * [pairedWithErlang], then the Elixir SDK's pairing stores "<prefix> Erlang" with another home, which another Erlang
     * SDK holds, and the code paths that SDK gives it. Returns the Elixir SDK and that home.
     */
    private fun pairedAtAStaleHome(prefix: String): Pair<Sdk, String> {
        val elixir = pairedWithErlang(prefix)
        val staleHome = SdkFixtures.erlangHomeWithEbin("26", "26.2.5")
        val atStaleHome = SdkFixtures.withCodePaths(register(SdkFixtures.erlangSdk("$prefix Erlang at the stale home", staleHome)))
        SdkFixtures.commitPairing(elixir, atStaleHome)
        ElixirSdkPathConfigurator.configure(elixir)
        SdkFixtures.commitPairing(elixir, "$prefix Erlang", staleHome)
        assertEquals("precondition: the stale home's code paths", codePaths(atStaleHome), codePathsUnder(elixir, staleHome))
        return elixir to staleHome
    }

    /**
     * Erlang SDKs "<prefix> second Erlang", then "<prefix> first Erlang", at two homes, each with its own Elixir SDK
     * registered after both. Returns the first's Elixir SDK, then the second's.
     */
    private fun twoPairs(prefix: String): Pair<Sdk, Sdk> {
        val second = register(SdkFixtures.erlangSdk("$prefix second Erlang", SdkFixtures.erlangHomeWithEbin("26", "26.2.5")))
        val first = register(SdkFixtures.erlangSdk("$prefix first Erlang", SdkFixtures.erlangHomeWithEbin("27", "27.3.4")))
        return pairedElixir("$prefix first Elixir", first) to pairedElixir("$prefix second Elixir", second)
    }

    /** A registered Elixir SDK named [name], paired with [erlang]. */
    private fun pairedElixir(name: String, erlang: Sdk): Sdk =
        SdkFixtures.commitPairing(register(SdkFixtures.elixirSdk(name, SdkFixtures.elixirHome("1.18.4"))), erlang)

    /** [elixir] stores the name and the home of the Erlang SDK now named [erlangName]. Returns that SDK. */
    private fun assertPairedAt(elixir: Sdk, erlangName: String): Sdk {
        val erlang = ProjectJdkTable.getInstance().findJdk(erlangName)!!
        assertEquals("${elixir.name}'s Erlang name", erlangName, storedErlangName(elixir))
        assertEquals("${elixir.name}'s Erlang home", erlang.homePath, storedErlangHome(elixir))
        return erlang
    }

    /** [elixir] is paired with the Erlang SDK now named [erlangName], at its home, with its code paths and none of [oldHome]'s. */
    private fun assertFollowed(elixir: Sdk, erlangName: String, oldHome: String) =
        SdkFixtures.assertCodePathsFollowed(elixir, assertPairedAt(elixir, erlangName), oldHome)

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

        fun rename(newName: String) = change(newName = newName)

        /**
         * The new name [newName] and the new home [newHome], each if given, with the new home's code paths in place of the
         * old ones, as `SdkEditor` takes them from `setupSdkPaths`.
         */
        fun change(newName: String? = null, newHome: String? = null) {
            WriteAction.run<Throwable> {
                copy.sdkModificator.apply {
                    newName?.let { this.name = it }
                    if (newHome != null) {
                        homePath = newHome
                        removeAllRoots()
                    }
                    commitChanges()
                }
                if (newHome != null) SdkFixtures.withCodePaths(copy)
            }
            configurable?.apply()
        }
    }
}
