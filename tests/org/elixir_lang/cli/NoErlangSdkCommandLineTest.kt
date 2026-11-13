package org.elixir_lang.cli

import com.intellij.execution.CantRunException
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.registerOrReplaceServiceInstance
import org.elixir_lang.Elixir
import org.elixir_lang.IEx
import org.elixir_lang.Mix
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.dialyzer.service.DialyzerServiceImpl
import org.elixir_lang.junit.onPooledThread
import org.elixir_lang.mix.runner.MixTaskRunner
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService
import java.util.Collections

/**
 * An Elixir SDK with no Erlang SDK fails its command line, from any thread and with no lock of the caller's, with the
 * [CantRunException] that already told the user.
 */
class NoErlangSdkCommandLineTest : PlatformTestCase() {
    private lateinit var elixirSdk: Sdk

    override fun setUp() {
        super.setUp()
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            MockWslCompatService(),
            testRootDisposable,
        )
        elixirSdk = SdkFixtures.register(
            SdkFixtures.elixirSdk("No Erlang Elixir", SdkFixtures.elixirHome("1.18.4")),
            testRootDisposable,
        )
    }

    fun testMix() = assertFailsForTheMissingErlangSdk { Mix.commandLine(emptyMap(), workingDirectory(), elixirSdk, project = project) }

    fun testElixir() = assertFailsForTheMissingErlangSdk { Elixir.commandLine(emptyMap(), workingDirectory(), elixirSdk, project = project) }

    fun testIEx() = assertFailsForTheMissingErlangSdk { IEx.commandLine(emptyMap(), workingDirectory(), elixirSdk, emptyList(), project) }

    fun testAMixRunConfiguration() {
        ModuleRootModificationUtil.setModuleSdk(module, elixirSdk)
        val configuration =
            org.elixir_lang.mix.Configuration("No Erlang", project, org.elixir_lang.mix.configuration.Factory).apply {
                configurationModule.module = module
                workingDirectory = workingDirectory()
            }

        assertFailsForTheMissingErlangSdk { configuration.commandLine() }
    }

    /** The import, which has no project to tell once, reports the missing Erlang SDK once rather than for each task. */
    fun testTheImportsMixTasks() {
        val titles = Collections.synchronizedList(mutableListOf<String>())
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    titles.add(notification.title)
                }
            },
        )

        onPooledThread { MixTaskRunner.runImportTasks(null, workingDirectory(), elixirSdk) }

        assertEquals(listOf("Elixir SDK missing Erlang SDK"), titles.toList())
    }

    fun testDialyzer() = assertFailsForTheMissingErlangSdk { DialyzerServiceImpl().commandLine(workingDirectory(), elixirSdk, project) }

    private fun assertFailsForTheMissingErlangSdk(build: () -> Any) {
        val failure = onPooledThread { runCatching(build).exceptionOrNull() }

        assertInstanceOf(failure, CantRunException.CustomProcessedCantRunException::class.java)
    }

    private fun workingDirectory(): String = FileUtil.createTempDirectory("no-erlang", null, true).path
}
