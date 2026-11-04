package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.registerOrReplaceServiceInstance
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.persistedElixirData
import org.elixir_lang.sdk.elixir.ElixirSdkMutation
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService
import org.jdom.Element
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
        SdkFixtures.commit(elixirSdk, SdkAdditionalData(elixirSdk).apply { readExternal(pairing(name = erlangSdk.name)) })

        assertSame(erlangSdk, resolve(elixirSdk))

        assertNull(
            "resolving under a read lock must not write the pairing into the live additional data",
            elixirSdk.elixirAdditionalData?.getErlangSdkHomePath(),
        )

        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("/fake/erlang/heal-by-name", persistedElixirData(elixirSdk)?.getErlangSdkHomePath())
    }

    @RequiresEdt
    fun testAPairingFoundOnlyByHomeIsNotRewritten() {
        val erlangSdk = register(SdkFixtures.erlangSdk("Heal renamed Erlang", "/fake/erlang/heal-renamed"))
        val elixirSdk = register(SdkFixtures.elixirSdk("Heal renamed Elixir", "/fake/elixir/heal-renamed"))
        SdkFixtures.commit(
            elixirSdk,
            SdkAdditionalData(elixirSdk).apply {
                readExternal(pairing(name = "Name before rename", homePath = "/fake/erlang/heal-renamed"))
            },
        )

        assertSame(erlangSdk, resolve(elixirSdk))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("Name before rename", persistedElixirData(elixirSdk)?.getErlangSdkName())
    }

    @RequiresEdt
    fun testTwoErlangSdksAtOneHomeKeepTheNamedOne() {
        val other = register(SdkFixtures.erlangSdk("Heal shared home other Erlang", "/fake/erlang/heal-shared"))
        val named = register(SdkFixtures.erlangSdk("Heal shared home named Erlang", "/fake/erlang/heal-shared"))
        val elixirSdk = register(SdkFixtures.elixirSdk("Heal shared home Elixir", "/fake/elixir/heal-shared"))
        SdkFixtures.commit(
            elixirSdk,
            SdkAdditionalData(elixirSdk).apply { readExternal(pairing(name = named.name, homePath = "/fake/erlang/heal-shared")) },
        )

        // Listing the other SDK first, so the home lookup finds it rather than the named one.
        resolve(elixirSdk, sdkModel(other, named))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(named.name, persistedElixirData(elixirSdk)?.getErlangSdkName())
    }

    @RequiresEdt
    fun testARepairQueuedBeforeAPickAtTheSameHomeIsDropped() {
        val erlangSdk = register(SdkFixtures.erlangSdk("Heal same home Erlang", "/fake/erlang/heal-same-home"))
        val chosenErlangSdk = register(SdkFixtures.erlangSdk("Heal same home chosen Erlang", "/fake/erlang/heal-same-home"))
        val elixirSdk = register(SdkFixtures.elixirSdk("Heal same home Elixir", "/fake/elixir/heal-same-home"))
        SdkFixtures.commit(elixirSdk, SdkAdditionalData(elixirSdk).apply { readExternal(pairing(name = erlangSdk.name)) })
        assertSame(erlangSdk, resolve(elixirSdk))

        // The user picks the other SDK at the same home before the queued repair runs.
        WriteAction.run<Throwable> { ElixirSdkMutation.applyDependencySelection(elixirSdk, chosenErlangSdk) }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(chosenErlangSdk.name, persistedElixirData(elixirSdk)?.getErlangSdkName())
    }

    @RequiresEdt
    fun testAChangedErlangHomeIsCommitted() {
        val erlangSdk = register(SdkFixtures.erlangSdk("Heal moved Erlang", "/fake/erlang/heal-moved-new"))
        val elixirSdk = register(SdkFixtures.elixirSdk("Heal moved Elixir", "/fake/elixir/heal-moved"))
        SdkFixtures.commit(
            elixirSdk,
            SdkAdditionalData(elixirSdk).apply {
                readExternal(pairing(name = erlangSdk.name, homePath = "/fake/erlang/heal-moved-old"))
            },
        )

        assertSame(erlangSdk, resolve(elixirSdk))

        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("/fake/erlang/heal-moved-new", persistedElixirData(elixirSdk)?.getErlangSdkHomePath())
    }

    @RequiresEdt
    fun testARepairQueuedBeforeTheUserRepairsIsDropped() {
        val erlangSdk = register(SdkFixtures.erlangSdk("Heal superseded Erlang", "/fake/erlang/heal-superseded"))
        val chosenErlangSdk = register(SdkFixtures.erlangSdk("Heal chosen Erlang", "/fake/erlang/heal-chosen"))
        val elixirSdk = register(SdkFixtures.elixirSdk("Heal superseded Elixir", "/fake/elixir/heal-superseded"))
        SdkFixtures.commit(elixirSdk, SdkAdditionalData(elixirSdk).apply { readExternal(pairing(name = erlangSdk.name)) })
        assertSame(erlangSdk, resolve(elixirSdk))

        // Re-paired by the user, for example in a settings dialog, before the queued repair runs.
        WriteAction.run<Throwable> { ElixirSdkMutation.applyDependencySelection(elixirSdk, chosenErlangSdk) }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("Heal chosen Erlang", persistedElixirData(elixirSdk)?.getErlangSdkName())
    }

    @RequiresEdt
    fun testARepairAlreadyMadeCommitsNothing() {
        val erlangSdk = register(SdkFixtures.erlangSdk("Heal already Erlang", "/fake/erlang/heal-already"))
        val elixirSdk = register(SdkFixtures.elixirSdk("Heal already Elixir", "/fake/elixir/heal-already"))
        SdkFixtures.commit(elixirSdk, SdkAdditionalData(elixirSdk).apply { readExternal(pairing(name = erlangSdk.name)) })
        assertSame(erlangSdk, resolve(elixirSdk))
        // The same repair, committed before the queued one runs.
        WriteAction.run<Throwable> { ElixirSdkMutation.applyDependencySelection(elixirSdk, erlangSdk) }
        val commits = AtomicInteger()
        elixirSdk.rootProvider.addRootSetChangedListener({ commits.incrementAndGet() }, testRootDisposable)

        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals("a pairing that already names the SDK is not committed again", 0, commits.get())
    }

    @RequiresEdt
    fun testAPairingWithAHomelessErlangSdkIsNotCommitted() {
        val erlangSdk = register(SdkFixtures.erlangSdk("Heal homeless Erlang", ""))
        val elixirSdk = register(SdkFixtures.elixirSdk("Heal homeless Elixir", "/fake/elixir/heal-homeless"))
        SdkFixtures.commit(elixirSdk, SdkAdditionalData(elixirSdk).apply { readExternal(pairing(name = erlangSdk.name)) })
        val commits = AtomicInteger()
        elixirSdk.rootProvider.addRootSetChangedListener({ commits.incrementAndGet() }, testRootDisposable)

        resolve(elixirSdk)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        // A repair would store the same blank home, so the next resolution would repair it again, forever.
        assertEquals("an Erlang SDK with no home has nothing to repair", 0, commits.get())
    }

    @RequiresEdt
    fun testSdkOutsideTheTableIsNotHealed() {
        // A settings dialog's editable copy: never in the table, so a commit to it would not persist.
        val erlangSdk = SdkFixtures.erlangSdk("Heal copy Erlang", "/fake/erlang/heal-copy")
        val elixirSdk = SdkFixtures.elixirSdk("Heal copy Elixir", "/fake/elixir/heal-copy")
        SdkFixtures.commit(elixirSdk, SdkAdditionalData(elixirSdk).apply { readExternal(pairing(name = erlangSdk.name)) })

        assertSame(erlangSdk, resolve(elixirSdk, sdkModel(erlangSdk)))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertNull(elixirSdk.elixirAdditionalData?.getErlangSdkHomePath())
        assertNull(persistedElixirData(elixirSdk)?.getErlangSdkHomePath())
    }

    private fun register(sdk: Sdk): Sdk = SdkFixtures.register(sdk, testRootDisposable)

    private fun resolve(elixirSdk: Sdk, sdkModel: SdkModel? = null): Sdk? =
        ReadAction.nonBlocking(Callable {
            ErlangSdkResolver.getInstance().resolveErlangSdk(elixirSdk, sdkModel)
        }).executeSynchronously()

    private fun pairing(name: String, homePath: String? = null): Element =
        Element("additional").apply {
            setAttribute("erlang-sdk-name", name)
            if (homePath != null) setAttribute("erlang-sdk-home-path", homePath)
        }

    private fun sdkModel(vararg sdks: Sdk): SdkModel = object : SdkModel {
        override fun getSdks(): Array<out Sdk> = arrayOf(*sdks)
        override fun findSdk(sdkName: String?): Sdk? = sdks.find { it.name == sdkName }
        override fun addSdk(sdk: Sdk) {}
        override fun addListener(listener: SdkModel.Listener) {}
        override fun removeListener(listener: SdkModel.Listener) {}
        override fun getMulticaster(): SdkModel.Listener = object : SdkModel.Listener {}
    }
}
