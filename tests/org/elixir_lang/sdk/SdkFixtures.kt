package org.elixir_lang.sdk

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkAdditionalData
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.util.io.FileUtil
import com.intellij.diagnostic.ThreadDumper
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.concurrency.annotations.RequiresEdt
import java.io.File
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType
import org.elixir_lang.sdk.erlang.Type as ErlangSdkType
import org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData as ElixirSdkAdditionalData

internal object SdkFixtures {
    fun elixirHome(vsn: String): String {
        val home = FileUtil.createTempDirectory("elixir_home", null, true)
        val ebin = File(home, "lib/elixir/ebin")
        check(ebin.mkdirs()) { "Failed to create $ebin" }
        File(ebin, "elixir.app").writeText("{application,elixir,\n [{description,\"elixir\"},\n  {vsn,\"$vsn\"}]}.\n")
        return home.path
    }

    fun erlangHome(major: String, otpVersion: String): String {
        val home = FileUtil.createTempDirectory("erlang_home", null, true)
        val release = File(home, "releases/$major")
        check(release.mkdirs()) { "Failed to create $release" }
        File(release, "OTP_VERSION").writeText("$otpVersion\n")
        return home.path
    }

    /** A home that does not exist, absolute on every OS as a real home is: a Unix `/fake` path is relative on Windows. */
    fun fakeHome(path: String): String = File(FileUtil.getTempDirectory(), "fake/$path").path

    fun elixirSdk(name: String, homePath: String): Sdk = ProjectJdkImpl(name, ElixirSdkType.instance, homePath, "")

    fun erlangSdk(name: String, homePath: String): Sdk = ProjectJdkImpl(name, ErlangSdkType.instance, homePath, "")

    @RequiresEdt
    fun register(sdk: Sdk, parentDisposable: Disposable): Sdk {
        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().addJdk(sdk, parentDisposable) }
        return sdk
    }

    /** [register], then [waitForRegistrationFills]. */
    @RequiresEdt
    fun registerAndWaitForFill(sdk: Sdk, parentDisposable: Disposable): Sdk =
        register(sdk, parentDisposable).also { waitForRegistrationFills() }

    /**
     * Waits for the reads that registering an SDK starts once a project's startup has installed the SDK table
     * listeners, so none lands between a test's own store writes and its assertion.
     */
    @RequiresEdt
    fun waitForRegistrationFills() =
        waitUntil("the registration fill finishes") { SdkVersionWatchService.isIdleForTests() }

    @RequiresEdt
    fun commit(sdk: Sdk, data: SdkAdditionalData?) {
        WriteAction.run<Throwable> {
            sdk.sdkModificator.apply {
                sdkAdditionalData = data
                commitChanges()
            }
        }
    }

    /** What the SDK entity's XML holds: a fresh modificator reloads the additional data from it. */
    fun persistedElixirData(sdk: Sdk): ElixirSdkAdditionalData? =
        sdk.sdkModificator.sdkAdditionalData as? ElixirSdkAdditionalData

    @RequiresEdt
    fun waitUntil(message: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                // Lands in the JUnit XML's system-err, beside the failure.
                System.err.println(ThreadDumper.dumpThreadsToString())
                throw AssertionError(
                    "$message (watch: ${SdkVersionWatchService.describeForTests()}; " +
                        "${SdkVersionsFiller.describeForTests()})"
                )
            }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }
}
