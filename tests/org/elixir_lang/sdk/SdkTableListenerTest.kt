package org.elixir_lang.sdk

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.common.runAll
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.mix.sync.MixSyncTestHelpers.runSuspendOnPooledThread
import org.elixir_lang.sdk.SdkFixtures.codePaths
import org.elixir_lang.sdk.SdkFixtures.codePathsUnder
import org.elixir_lang.sdk.SdkFixtures.settle
import org.elixir_lang.sdk.elixir.ElixirSdkPathConfigurator
import org.elixir_lang.sdk.erlang_dependent.install

/**
 * An Erlang SDK added at the stored Erlang home of an Elixir SDK set up while its pairing resolved to nothing gives that
 * Elixir SDK the Erlang code paths its set-up left out.
 */
class SdkTableListenerTest : PlatformTestCase() {
    private lateinit var table: SdkFixtures.TableSnapshot

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
    }

    override fun tearDown() = runAll({ table.restore() }, { super.tearDown() })

    fun testAnErlangSdkAddedAtAnUnpairedElixirSdksHomeSetsItUp() {
        val home = install("27", "27.3.4")
        val elixirSdk = unpaired("Listener added Elixir", home)
        val added = SdkFixtures.withCodePaths(SdkFixtures.erlangSdk("Listener added Erlang", home))

        SdkFixtures.register(added)
        settle()

        assertEquals(codePaths(added), codePathsUnder(elixirSdk, home))
    }

    fun testAnErlangSdkTheRegistrarRegistersAtAnUnpairedElixirSdksHomeSetsItUp() {
        val home = install("26", "26.2.5")
        val elixirSdk = unpaired("Listener registered Elixir", home)

        val registered = runSuspendOnPooledThread(60_000) { SdkRegistrar.registerOrUpdateErlangSdk(home) }
            ?: throw AssertionError("the registrar registers the home")
        settle()

        assertEquals(codePaths(registered), codePathsUnder(elixirSdk, home))
    }

    /** An Elixir SDK paired with an Erlang SDK still there is left as it is, with the roots the user added. */
    fun testAnErlangSdkAddedAtAPairedElixirSdksHomeLeavesIt() {
        val home = install("25", "25.3.2")
        val paired = SdkFixtures.register(SdkFixtures.withCodePaths(SdkFixtures.erlangSdk("Listener paired Erlang", home)))
        val elixirSdk = SdkFixtures.commitPairing(
            SdkFixtures.register(SdkFixtures.elixirSdk("Listener paired Elixir", SdkFixtures.elixirHome("1.18.4"))),
            paired,
        )
        ElixirSdkPathConfigurator.configure(elixirSdk)
        val userRoot = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(FileUtil.createTempDirectory("user", "root"))!!
        WriteAction.run<Throwable> { elixirSdk.sdkModificator.apply { addRoot(userRoot, OrderRootType.CLASSES) }.commitChanges() }

        SdkFixtures.register(SdkFixtures.withCodePaths(SdkFixtures.erlangSdk("Listener second Erlang", home)))
        settle()

        assertContainsElements(elixirSdk.rootProvider.getFiles(OrderRootType.CLASSES).toList(), userRoot)
    }

    /** An Elixir SDK whose stored pairing names a gone Erlang SDK at [erlangHome], set up with no Erlang code paths. */
    private fun unpaired(name: String, erlangHome: String): Sdk {
        val elixirSdk = SdkFixtures.commitPairing(
            SdkFixtures.register(SdkFixtures.elixirSdk(name, SdkFixtures.elixirHome("1.18.4"))),
            "$name's gone Erlang",
            erlangHome,
        )
        ElixirSdkPathConfigurator.configure(elixirSdk)
        assertEmpty("precondition: set up with no Erlang code paths", codePathsUnder(elixirSdk, erlangHome))
        return elixirSdk
    }
}
