package org.elixir_lang.sdk

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.testFramework.common.runAll
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.mix.sync.MixSyncTestHelpers.runSuspendOnPooledThread
import org.elixir_lang.sdk.SdkFixtures.elixirHome
import org.elixir_lang.sdk.SdkFixtures.erlangHomeWithEbin
import org.elixir_lang.sdk.SdkFixtures.register
import org.elixir_lang.sdk.SdkFixtures.storedErlangName

/** Registering an Elixir install again, as the tool manager does, with an Elixir SDK on it already paired at that Erlang home. */
class SdkRegistrarPairingTest : PlatformTestCase() {
    private lateinit var table: SdkFixtures.TableSnapshot

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
    }

    override fun tearDown() = runAll(
        { table.restore() },
        { super.tearDown() },
    )

    fun testAPairingWhoseNameNamesAnErlangSdkIsKept() {
        val home = erlangHomeWithEbin("27", "27.3.4")
        val picked = register(SdkFixtures.erlangSdk("Registrar picked Erlang", home))
        val twin = register(SdkFixtures.erlangSdk("Registrar twin Erlang", home))
        val elixirHome = elixirHome("1.18.4")
        val elixirSdk = registerElixir(elixirHome, picked)
        assertEquals("precondition", picked.name, storedErlangName(elixirSdk))

        val again = registerElixir(elixirHome, twin)

        assertSame(elixirSdk, again)
        assertEquals("the user's pick at that home stays", picked.name, storedErlangName(elixirSdk))
    }

    fun testAPairingWhoseNameNamesNoErlangSdkTakesTheName() {
        val home = erlangHomeWithEbin("27", "27.3.4")
        val renamed = register(SdkFixtures.erlangSdk("Registrar renamed Erlang", home))
        val elixirHome = elixirHome("1.18.4")
        val elixirSdk = registerElixir(elixirHome, renamed)
        SdkFixtures.commitPairing(elixirSdk, "Registrar old Erlang name", home)

        registerElixir(elixirHome, renamed)

        assertEquals(renamed.name, storedErlangName(elixirSdk))
    }

    fun testAPairingWhoseNamedSdkMovedTakesTheNameOfTheSdkAtItsHome() {
        val storedHome = erlangHomeWithEbin("27", "27.3.4")
        val moved = register(SdkFixtures.erlangSdk("Registrar moved Erlang", erlangHomeWithEbin("26", "26.2.5")))
        val elixirHome = elixirHome("1.18.4")
        val elixirSdk = registerElixir(elixirHome, moved)
        SdkFixtures.commitPairing(elixirSdk, moved.name, storedHome)
        val atStoredHome = register(SdkFixtures.erlangSdk("Registrar Erlang at the stored home", storedHome))

        val again = registerElixir(elixirHome, atStoredHome)

        assertSame(elixirSdk, again)
        assertEquals("the home is the pairing's identity", atStoredHome.name, storedErlangName(elixirSdk))
    }

    private fun registerElixir(homePath: String, erlangSdk: Sdk): Sdk =
        runSuspendOnPooledThread(60_000) { SdkRegistrar.registerOrUpdateElixirSdk(homePath, erlangSdk) }!!
            .also { SdkFixtures.waitForRegistrationFills() }
}
