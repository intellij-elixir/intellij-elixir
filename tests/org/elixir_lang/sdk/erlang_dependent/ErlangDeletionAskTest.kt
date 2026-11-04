package org.elixir_lang.sdk.erlang_dependent

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.impl.LaterInvocator
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.testFramework.common.runAll
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.facet.SdksService
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.persistedElixirData
import org.elixir_lang.sdk.SdkFixtures.register
import org.elixir_lang.sdk.SdkFixtures.settle
import org.elixir_lang.sdk.SdkFixtures.storedErlangName
import org.elixir_lang.sdk.elixir.ElixirSdkMutation
import org.elixir_lang.sdk.elixir.ElixirSdkPathConfigurator
import org.elixir_lang.facet.sdks.erlang.Configurable as ErlangSdksConfigurable
import org.elixir_lang.sdk.erlang.Type as ErlangSdkType

/**
 * Deleting an Erlang SDK that Elixir SDKs are paired with asks, for each one left without its Erlang, which Erlang it
 * runs on, once no modal dialog is open: after the SDK pages apply the removal and Settings or Project Structure
 * closes, or after another caller removes it from the table.
 */
class ErlangDeletionAskTest : PlatformTestCase() {
    private val asked = Asked()
    private val notified = mutableListOf<String>()
    private lateinit var table: SdkFixtures.TableSnapshot

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        asked.recordMessages(testRootDisposable)
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    notified += notification.title
                }
            },
        )
    }

    override fun tearDown() = runAll(
        { table.restore() },
        { SdksService.getInstance()!!.resetForTests() },
        { super.tearDown() },
    )

    fun testIdeasApplyAsksForEachElixirSdkItLeaves() = assertAsksForEachElixirSdkItLeaves("Ask IDEA", ::removeInIdea)

    fun testTheSmallIdesRemoveAsksForEachElixirSdkItLeaves() =
        assertAsksForEachElixirSdkItLeaves("Ask small", SdkConfigurationUtil::removeSdk)

    /** Two Elixir SDKs paired with an Erlang SDK that [remove] deletes are each asked, and each pick is written. */
    private fun assertAsksForEachElixirSdkItLeaves(prefix: String, remove: (Sdk) -> Unit) {
        val deleted = erlang("$prefix deleted Erlang", install("27", "27.3.4"))
        val other = erlang("$prefix other Erlang", install("26", "26.2.5"))
        val first = paired("$prefix first Elixir", SdkFixtures.elixirHome("1.18.4"), deleted)
        val second = paired("$prefix second Elixir", SdkFixtures.elixirHome("1.17.3"), deleted)
        script(ScriptedErlangStep.pick(other.name), ScriptedErlangStep.pick(other.name))

        remove(deleted)
        settle()

        assertEquals(listOf("erlang", "erlang"), asked.events)
        assertEquals(other.name, storedErlangName(first))
        assertEquals(other.name, storedErlangName(second))
    }

    /** The small IDEs' SDKs page: Remove, then OK. */
    fun testTheSmallIdesPageAppliesARemoveThenAsks() {
        val deleted = erlang("Ask page deleted Erlang", install("27", "27.3.4"))
        val other = erlang("Ask page other Erlang", install("26", "26.2.5"))
        val elixir = paired("Ask page Elixir", SdkFixtures.elixirHome("1.18.4"), deleted)
        script(ScriptedErlangStep.pick(other.name))
        SdksService.getInstance()!!.resetForTests()
        val page = ErlangSdksConfigurable()
        try {
            page.createComponent()
            page.reset()
            page.removeSelectedSdk(SdksService.getInstance()!!.getModel().findSdk(deleted.name) as ProjectJdkImpl)

            page.apply()
        } finally {
            page.disposeUIResources()
        }
        settle()

        assertEquals(listOf("erlang"), asked.events)
        assertEquals(other.name, storedErlangName(elixir))
    }

    fun testNothingIsAskedWhileAModalIsOpen() {
        val deleted = erlang("Ask modal deleted Erlang", install("27", "27.3.4"))
        paired("Ask modal Elixir", SdkFixtures.elixirHome("1.18.4"), deleted)
        script(ScriptedErlangStep.cancel)
        val settings = Any()

        LaterInvocator.enterModal(settings)
        try {
            SdkConfigurationUtil.removeSdk(deleted)
            settle()

            assertEmpty("nothing is asked while Settings is open", asked.events)
        } finally {
            LaterInvocator.leaveModal(settings)
        }
        settle()

        assertEquals(listOf("erlang"), asked.events)
    }

    fun testAPickIsWritten() {
        val deleted = erlang("Ask pick deleted Erlang", install("27", "27.3.4"))
        val other = erlang("Ask pick other Erlang", install("26", "26.2.5"))
        val elixirSdk = paired("Ask pick Elixir", SdkFixtures.elixirHome("1.18.4"), deleted)
        script(ScriptedErlangStep.pick(other.name))

        SdkConfigurationUtil.removeSdk(deleted)
        settle()

        assertEquals(other.name, storedErlangName(elixirSdk))
        assertEquals(other.homePath, persistedElixirData(elixirSdk)?.getErlangSdkHomePath())
    }

    fun testAPickSetsUpTheElixirSdksCodePathsFromIt() {
        val deleted = withEbinRoot(erlang("Ask roots deleted Erlang", install("27", "27.3.4")))
        val other = withEbinRoot(erlang("Ask roots other Erlang", install("26", "26.2.5")))
        val elixirSdk = paired("Ask roots Elixir", SdkFixtures.elixirHome("1.18.4"), deleted)
        ElixirSdkPathConfigurator.configure(elixirSdk)
        assertTrue("precondition: the deleted Erlang's ebin is a root", ebinUrl(deleted) in classes(elixirSdk))
        script(ScriptedErlangStep.pick(other.name))

        SdkConfigurationUtil.removeSdk(deleted)
        settle()

        val roots = classes(elixirSdk)
        assertTrue("the picked Erlang's ebin is a root: $roots", ebinUrl(other) in roots)
        assertFalse("the deleted Erlang's is not: $roots", ebinUrl(deleted) in roots)
    }

    fun testCancelWritesNothing() {
        val deleted = erlang("Ask cancel deleted Erlang", install("27", "27.3.4"))
        erlang("Ask cancel other Erlang", install("26", "26.2.5"))
        val elixirSdk = paired("Ask cancel Elixir", SdkFixtures.elixirHome("1.18.4"), deleted)
        script(ScriptedErlangStep.cancel)

        SdkConfigurationUtil.removeSdk(deleted)
        settle()

        assertEquals(listOf("erlang"), asked.events)
        assertEquals(deleted.name, storedErlangName(elixirSdk))
    }

    fun testAnElixirSdkRepairedBeforeTheAskIsNotAsked() {
        val deleted = erlang("Ask repaired deleted Erlang", install("27", "27.3.4"))
        val other = erlang("Ask repaired other Erlang", install("26", "26.2.5"))
        val elixirSdk = paired("Ask repaired Elixir", SdkFixtures.elixirHome("1.18.4"), deleted)
        script(ScriptedErlangStep.cancel)

        SdkConfigurationUtil.removeSdk(deleted)
        WriteAction.run<Throwable> { ElixirSdkMutation.applyDependencySelection(elixirSdk, other) }
        settle()

        assertEmpty(asked.events)
    }

    fun testNoBalloonIsPosted() {
        val deleted = erlang("Ask balloon deleted Erlang", install("27", "27.3.4"))
        paired("Ask balloon Elixir", SdkFixtures.elixirHome("1.18.4"), deleted)
        script(ScriptedErlangStep.cancel)

        SdkConfigurationUtil.removeSdk(deleted)
        settle()

        assertDoesntContain(notified, "Erlang SDK Deleted")
    }

    fun testAnotherErlangAtTheDeletedHomeIsAsked() {
        val home = install("27", "27.3.4")
        val deleted = erlang("Ask shared home deleted Erlang", home)
        val twin = erlang("Ask shared home twin Erlang", home)
        val elixirSdk = paired("Ask shared home Elixir", SdkFixtures.elixirHome("1.18.4"), deleted)
        script(ScriptedErlangStep.pick(twin.name))

        removeInIdea(deleted)
        settle()

        assertEquals("the pairing resolves to another SDK than the one it names", listOf("erlang"), asked.events)
        assertEquals(twin.name, storedErlangName(elixirSdk))
    }

    fun testAStaleNamedPairingAtTheDeletedHomeIsAsked() {
        val home = install("27", "27.3.4")
        val deleted = erlang("Ask stale deleted Erlang", home)
        val elixirSdk = paired("Ask stale Elixir", SdkFixtures.elixirHome("1.18.4"), erlangName = "Ask stale name", erlangHome = home)
        script(ScriptedErlangStep.cancel)

        SdkConfigurationUtil.removeSdk(deleted)
        settle()

        assertEquals(listOf("erlang"), asked.events)
        assertEquals("Ask stale name", storedErlangName(elixirSdk))
    }

    fun testDeleteAllAsksNothing() {
        val deleted = erlang("Ask delete all Erlang", install("27", "27.3.4"))
        val elixirSdk = paired("Ask delete all Elixir", SdkFixtures.elixirHome("1.18.4"), deleted)
        script(ScriptedErlangStep.cancel)

        // As `DeleteAllSdksAction` removes them: the Erlang SDKs first, then the Elixir SDKs, in one write action.
        WriteAction.run<Throwable> {
            ProjectJdkTable.getInstance().removeJdk(deleted)
            ProjectJdkTable.getInstance().removeJdk(elixirSdk)
        }
        settle()

        assertEmpty(asked.events)
        assertDoesntContain(notified, "Erlang SDK Deleted")
    }

    fun testTheAskedSdksOwnHomeCanBePicked() {
        // IDEA: the paired SDK removed through Project Structure, another SDK left at its home.
        val ideaHome = install("27", "27.3.4")
        val ideaDeleted = erlang("Ask own home IDEA deleted Erlang", ideaHome)
        val ideaTwin = erlang("Ask own home IDEA twin Erlang", ideaHome)
        paired("Ask own home IDEA Elixir", SdkFixtures.elixirHome("1.18.4"), ideaDeleted)
        val ideaStep = script(ScriptedErlangStep.cancel)

        removeInIdea(ideaDeleted)
        settle()

        assertTrue("IDEA's step", ideaStep.offered.single().single { it.sdk?.name == ideaTwin.name }.pickable)

        val smallHome = install("26", "26.2.5")
        val smallDeleted = erlang("Ask own home small deleted Erlang", smallHome)
        val smallTwin = erlang("Ask own home small twin Erlang", smallHome)
        paired("Ask own home small Elixir", SdkFixtures.elixirHome("1.17.3"), smallDeleted)
        val smallStep = script(ScriptedErlangStep.cancel)

        SdkConfigurationUtil.removeSdk(smallDeleted)
        settle()

        assertTrue("the small IDEs' step", smallStep.offered.single().single { it.sdk?.name == smallTwin.name }.pickable)
    }

    /** As IDEA's Project Structure does: removed from the dialog's model, then the model applied on OK. */
    private fun removeInIdea(sdk: Sdk) {
        val model = ProjectSdksModel()
        model.reset(project)
        model.removeSdk(model.findSdk(sdk.name)!!)
        model.apply()
    }

    /** Replaces the IDE's Erlang step, for this test, with one that gives [answers] in turn. */
    private fun script(vararg answers: StepAnswer): ScriptedErlangStep =
        ScriptedErlangStep(asked, *answers).install(testRootDisposable)

    private fun erlang(name: String, home: String): Sdk = register(SdkFixtures.erlangSdk(name, home))

    private fun paired(name: String, elixirHome: String, erlangSdk: Sdk): Sdk =
        paired(name, elixirHome, erlangSdk.name, erlangSdk.homePath)

    private fun paired(name: String, elixirHome: String, erlangName: String, erlangHome: String?): Sdk =
        SdkFixtures.commitPairing(register(SdkFixtures.elixirSdk(name, elixirHome)), erlangName, erlangHome)

    /** [sdk] with its paths set up, so its home's stdlib ebin is its code path. */
    private fun withEbinRoot(sdk: Sdk): Sdk = sdk.also { ErlangSdkType.instance.setupSdkPaths(it) }

    private fun ebinUrl(erlangSdk: Sdk): String = erlangSdk.rootProvider.getUrls(OrderRootType.CLASSES).single()

    private fun classes(sdk: Sdk): List<String> = sdk.rootProvider.getUrls(OrderRootType.CLASSES).toList()
}
