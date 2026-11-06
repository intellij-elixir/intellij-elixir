package org.elixir_lang.sdk

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.runAll
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.SdkFixtures.fakeHome
import org.elixir_lang.sdk.SdkTableChanges.Kind

/** Which SDKs a write to the table touched, reported to a follower inside the write. */
class SdkTableChangesTest : PlatformTestCase() {
    private data class Report(val name: String, val kind: Kind, val inWriteAction: Boolean)

    private val reports = mutableListOf<Report>()
    private lateinit var table: SdkFixtures.TableSnapshot
    private lateinit var following: Disposable

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        following = Disposer.newDisposable(testRootDisposable, "SdkTableChangesTest")
    }

    override fun tearDown() = runAll(
        { table.restore() },
        { super.tearDown() },
    )

    /** Follows the SDKs whose names start with "Table", as [reports]. */
    private fun follow() =
        SdkTableChanges.follow(following, { it.name.startsWith("Table") }) { sdk, kind ->
            reports += Report(sdk.name, kind, ApplicationManager.getApplication().isWriteAccessAllowed)
        }

    private fun elixir(name: String): Sdk = SdkFixtures.register(SdkFixtures.elixirSdk(name, fakeHome("elixir/$name")))

    private fun kindsOf(name: String): List<Kind> = reports.filter { it.name == name }.map { it.kind }

    fun testAnSdkAddedIsReported() {
        follow()

        elixir("Table added")

        assertEquals(listOf(Kind.ADDED), kindsOf("Table added"))
    }

    fun testAnSdkRemovedIsReported() {
        val sdk = elixir("Table removed")
        follow()

        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(sdk) }

        assertEquals(listOf(Kind.REMOVED), kindsOf("Table removed"))
    }

    fun testAnSdkRenamedIsReported() {
        elixir("Table old name")
        follow()

        SdkFixtures.renameInTable("Table old name", "Table new name")

        assertTrue(kindsOf("Table new name").toString(), Kind.RENAMED in kindsOf("Table new name"))
    }

    fun testACommitToAnSdkIsReported() {
        val sdk = elixir("Table committed")
        follow()

        SdkFixtures.commitPairing(sdk, "Table Erlang")

        assertEquals(listOf(Kind.ROOTS_COMMITTED), kindsOf("Table committed"))
    }

    fun testACommitToAnSdkAddedLaterIsReported() {
        follow()
        val sdk = elixir("Table added later")

        SdkFixtures.commitPairing(sdk, "Table Erlang")

        assertEquals(listOf(Kind.ADDED, Kind.ROOTS_COMMITTED), kindsOf("Table added later"))
    }

    fun testAnSdkTheFilterRefusesIsNotReported() {
        val refused = elixir("Other committed")
        follow()

        elixir("Other added")
        SdkFixtures.commitPairing(refused, "Table Erlang")
        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(refused) }

        assertEquals(emptyList<Report>(), reports)
    }

    fun testTheFollowerIsCalledInsideTheWrite() {
        val sdk = elixir("Table in write")
        follow()

        SdkFixtures.commitPairing(sdk, "Table Erlang")
        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(sdk) }

        assertEquals(listOf(true, true), reports.map { it.inWriteAction })
    }

    fun testAnSdkRemovedIsNoLongerFollowed() {
        val sdk = elixir("Table removed, then added back")
        follow()
        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(sdk) }
        SdkFixtures.register(sdk)
        reports.clear()

        SdkFixtures.commitPairing(sdk, "Table Erlang")

        assertEquals(listOf(Kind.ROOTS_COMMITTED), kindsOf(sdk.name))
    }

    fun testNothingIsReportedOnceDisposed() {
        val sdk = elixir("Table disposed")
        follow()

        Disposer.dispose(following)
        SdkFixtures.commitPairing(sdk, "Table Erlang")
        elixir("Table after")

        assertEquals(emptyList<Report>(), reports)
    }
}
