package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.testFramework.registerOrReplaceServiceInstance
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.codePaths
import org.elixir_lang.sdk.SdkFixtures.codePathsUnder
import org.elixir_lang.sdk.SdkFixtures.persistedElixirData
import org.elixir_lang.sdk.SdkFixtures.storedErlangName
import org.elixir_lang.sdk.elixir.ElixirSdkMutation
import org.elixir_lang.sdk.elixir.ElixirSdkPathConfigurator
import org.elixir_lang.sdk.erlang.Type as ErlangType
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger
import com.intellij.util.concurrency.annotations.RequiresEdt

/**
 * The resolver runs under a read lock, so a pairing it repairs has to be committed later through a
 * modificator: a write to the live additional data is never saved and is replaced by the next commit.
 */
class ErlangSdkResolverHealTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            MockWslCompatService(),
            testRootDisposable,
        )
    }

    @RequiresEdt
    fun testHomePathFoundByNameIsCommitted() {
        val erlangSdk = register(SdkFixtures.erlangSdk("Heal by name Erlang", "/fake/erlang/heal-by-name"))
        val elixirSdk = register(SdkFixtures.elixirSdk("Heal by name Elixir", "/fake/elixir/heal-by-name"))
        SdkFixtures.commitPairing(elixirSdk, erlangSdk.name)

        assertSame(erlangSdk, resolve(elixirSdk))

        assertNull(
            "resolving under a read lock must not write the pairing into the live additional data",
            elixirSdk.elixirAdditionalData?.getErlangSdkHomePath(),
        )

        SdkFixtures.settle()

        assertEquals("/fake/erlang/heal-by-name", persistedElixirData(elixirSdk)?.getErlangSdkHomePath())
    }

    @RequiresEdt
    fun testAPairingFoundOnlyByHomeIsNotRewritten() {
        val erlangSdk = register(SdkFixtures.erlangSdk("Heal renamed Erlang", "/fake/erlang/heal-renamed"))
        val elixirSdk = register(SdkFixtures.elixirSdk("Heal renamed Elixir", "/fake/elixir/heal-renamed"))
        SdkFixtures.commitPairing(elixirSdk, "Name before rename", "/fake/erlang/heal-renamed")

        assertSame(erlangSdk, resolve(elixirSdk))
        SdkFixtures.settle()

        assertEquals("Name before rename", storedErlangName(elixirSdk))
    }

    @RequiresEdt
    fun testTwoErlangSdksAtOneHomeKeepTheNamedOne() {
        val other = register(SdkFixtures.erlangSdk("Heal shared home other Erlang", "/fake/erlang/heal-shared"))
        val named = register(SdkFixtures.erlangSdk("Heal shared home named Erlang", "/fake/erlang/heal-shared"))
        val elixirSdk = register(SdkFixtures.elixirSdk("Heal shared home Elixir", "/fake/elixir/heal-shared"))
        SdkFixtures.commitPairing(elixirSdk, named.name, "/fake/erlang/heal-shared")

        // Listing the other SDK first, so the home lookup finds it rather than the named one.
        resolve(elixirSdk, ListedSdkModel(other, named))
        SdkFixtures.settle()

        assertEquals(named.name, storedErlangName(elixirSdk))
    }

    @RequiresEdt
    fun testARepairQueuedBeforeAPickAtTheSameHomeIsDropped() {
        val erlangSdk = register(SdkFixtures.erlangSdk("Heal same home Erlang", "/fake/erlang/heal-same-home"))
        val chosenErlangSdk = register(SdkFixtures.erlangSdk("Heal same home chosen Erlang", "/fake/erlang/heal-same-home"))
        val elixirSdk = register(SdkFixtures.elixirSdk("Heal same home Elixir", "/fake/elixir/heal-same-home"))
        SdkFixtures.commitPairing(elixirSdk, erlangSdk.name)
        assertSame(erlangSdk, resolve(elixirSdk))

        // The user picks the other SDK at the same home before the queued repair runs.
        WriteAction.run<Throwable> { ElixirSdkMutation.applyDependencySelection(elixirSdk, chosenErlangSdk) }
        SdkFixtures.settle()

        assertEquals(chosenErlangSdk.name, storedErlangName(elixirSdk))
    }

    @RequiresEdt
    fun testAChangedErlangHomeIsCommitted() {
        val erlangSdk = register(SdkFixtures.erlangSdk("Heal moved Erlang", "/fake/erlang/heal-moved-new"))
        val elixirSdk = register(SdkFixtures.elixirSdk("Heal moved Elixir", "/fake/elixir/heal-moved"))
        SdkFixtures.commitPairing(elixirSdk, erlangSdk.name, "/fake/erlang/heal-moved-old")

        assertSame(erlangSdk, resolve(elixirSdk))

        SdkFixtures.settle()

        assertEquals("/fake/erlang/heal-moved-new", persistedElixirData(elixirSdk)?.getErlangSdkHomePath())
    }

    /**
     * A pairing found by name whose stored home the Erlang SDK that gave the code paths held, and that has left the
     * table: the heal stores the found SDK's home, and the code paths follow it.
     */
    @RequiresEdt
    fun testAHealedHomeMovesTheCodePaths() {
        val erlangSdk = register(SdkFixtures.erlangSdk("Heal rehomes Erlang", SdkFixtures.erlangHomeWithEbin("27", "27.3.4")))
        val gone = SdkFixtures.erlangSdk("Heal rehomes gone Erlang", SdkFixtures.erlangHomeWithEbin("26", "26.2.5"))
        WriteAction.run<Throwable> { listOf(erlangSdk, gone).forEach(ErlangType.instance::setupSdkPaths) }
        val elixirSdk = register(SdkFixtures.elixirSdk("Heal rehomes Elixir", SdkFixtures.elixirHome("1.18.4")))
        WriteAction.run<Throwable> { ElixirSdkMutation.applyDependencySelection(elixirSdk, gone) }
        ElixirSdkPathConfigurator.configure(elixirSdk)
        SdkFixtures.commitPairing(elixirSdk, erlangSdk.name, gone.homePath)
        assertEquals("precondition: the gone SDK's code paths", codePaths(gone), codePathsUnder(elixirSdk, gone.homePath!!))

        assertSame(erlangSdk, resolve(elixirSdk))
        SdkFixtures.settle()

        assertEquals(erlangSdk.homePath, persistedElixirData(elixirSdk)?.getErlangSdkHomePath())
        assertEquals("the found SDK's code paths", codePaths(erlangSdk), codePathsUnder(elixirSdk, erlangSdk.homePath!!))
        assertEquals("the gone SDK's code paths", emptyList<String>(), codePathsUnder(elixirSdk, gone.homePath!!))
    }

    @RequiresEdt
    fun testARepairQueuedBeforeTheUserRepairsIsDropped() {
        val erlangSdk = register(SdkFixtures.erlangSdk("Heal superseded Erlang", "/fake/erlang/heal-superseded"))
        val chosenErlangSdk = register(SdkFixtures.erlangSdk("Heal chosen Erlang", "/fake/erlang/heal-chosen"))
        val elixirSdk = register(SdkFixtures.elixirSdk("Heal superseded Elixir", "/fake/elixir/heal-superseded"))
        SdkFixtures.commitPairing(elixirSdk, erlangSdk.name)
        assertSame(erlangSdk, resolve(elixirSdk))

        // Re-paired by the user, for example in a settings dialog, before the queued repair runs.
        WriteAction.run<Throwable> { ElixirSdkMutation.applyDependencySelection(elixirSdk, chosenErlangSdk) }
        SdkFixtures.settle()

        assertEquals("Heal chosen Erlang", storedErlangName(elixirSdk))
    }

    @RequiresEdt
    fun testARepairAlreadyMadeCommitsNothing() {
        val erlangSdk = register(SdkFixtures.erlangSdk("Heal already Erlang", "/fake/erlang/heal-already"))
        val elixirSdk = register(SdkFixtures.elixirSdk("Heal already Elixir", "/fake/elixir/heal-already"))
        SdkFixtures.commitPairing(elixirSdk, erlangSdk.name)
        assertSame(erlangSdk, resolve(elixirSdk))
        // The same repair, committed before the queued one runs.
        WriteAction.run<Throwable> { ElixirSdkMutation.applyDependencySelection(elixirSdk, erlangSdk) }
        val commits = AtomicInteger()
        elixirSdk.rootProvider.addRootSetChangedListener({ commits.incrementAndGet() }, testRootDisposable)

        SdkFixtures.settle()

        assertEquals("a pairing that already names the SDK is not committed again", 0, commits.get())
    }

    @RequiresEdt
    fun testAPairingWithAHomelessErlangSdkIsNotCommitted() {
        val erlangSdk = register(SdkFixtures.erlangSdk("Heal homeless Erlang", ""))
        val elixirSdk = register(SdkFixtures.elixirSdk("Heal homeless Elixir", "/fake/elixir/heal-homeless"))
        SdkFixtures.commitPairing(elixirSdk, erlangSdk.name)
        val commits = AtomicInteger()
        elixirSdk.rootProvider.addRootSetChangedListener({ commits.incrementAndGet() }, testRootDisposable)

        resolve(elixirSdk)
        SdkFixtures.settle()

        // A repair would store the same blank home, so the next resolution would repair it again, forever.
        assertEquals("an Erlang SDK with no home has nothing to repair", 0, commits.get())
    }

    @RequiresEdt
    fun testSdkOutsideTheTableIsNotHealed() {
        // A settings dialog's editable copy: never in the table, so a commit to it would not persist.
        val erlangSdk = SdkFixtures.erlangSdk("Heal copy Erlang", "/fake/erlang/heal-copy")
        val elixirSdk = SdkFixtures.elixirSdk("Heal copy Elixir", "/fake/elixir/heal-copy")
        SdkFixtures.commitPairing(elixirSdk, erlangSdk.name)

        assertSame(erlangSdk, resolve(elixirSdk, ListedSdkModel(erlangSdk)))
        SdkFixtures.settle()

        assertNull(elixirSdk.elixirAdditionalData?.getErlangSdkHomePath())
        assertNull(persistedElixirData(elixirSdk)?.getErlangSdkHomePath())
    }

    private fun register(sdk: Sdk): Sdk = SdkFixtures.register(sdk, testRootDisposable)

    private fun resolve(elixirSdk: Sdk, sdkModel: SdkModel? = null): Sdk? =
        ReadAction.nonBlocking(Callable {
            ErlangSdkResolver.getInstance().resolveErlangSdk(elixirSdk, sdkModel)
        }).executeSynchronously()
}
