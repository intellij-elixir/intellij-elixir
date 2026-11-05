package org.elixir_lang.dialyzer.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.testFramework.registerOrReplaceServiceInstance
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.junit.onPooledThread
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService
import com.intellij.util.concurrency.annotations.RequiresEdt

/**
 * The Dialyzer inspection runs with `isReadActionNeeded = false`, so the platform holds no read lock while it builds
 * its command line, which resolves the paired Erlang SDK.
 */
class DialyzerCommandLineTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            MockWslCompatService(),
            testRootDisposable,
        )
    }

    @RequiresEdt
    fun testBuildingTheCommandLineTakesItsOwnReadAction() {
        val erlangSdk = register(SdkFixtures.erlangSdk("Dialyzer Erlang", "/fake/erlang/dialyzer"))
        val elixirSdk = register(SdkFixtures.elixirSdk("Dialyzer Elixir", "/fake/elixir/dialyzer"))
        SdkFixtures.commitPairing(elixirSdk, erlangSdk)

        val failure = onPooledThread {
            runCatching { DialyzerServiceImpl().commandLine("/fake/project", elixirSdk, project) }.exceptionOrNull()
        }

        // The command line may still fail on the fake homes; it must not fail for want of a read lock.
        assertFalse(
            "building the command line must take its own read action; failed with $failure",
            generateSequence(failure) { it.cause }
                .any { it.message?.contains("Read access is allowed from inside read-action") == true },
        )
    }

    private fun register(sdk: Sdk): Sdk = SdkFixtures.register(sdk, testRootDisposable)
}
