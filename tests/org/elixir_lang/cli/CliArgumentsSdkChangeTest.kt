package org.elixir_lang.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.testFramework.registerOrReplaceServiceInstance
import org.elixir_lang.Mix
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.jps.shared.cli.CliTool
import org.elixir_lang.junit.onPooledThread
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.erlang_dependent.ElixirSdkSnapshot
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.effectiveOS
import org.elixir_lang.sdk.wsl.WslCompatService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.elixir_lang.jps.shared.cli.CliArguments as SharedCliArguments

/** An Elixir SDK changed while its command line is being built gives a command line of one installation, not of both. */
class CliArgumentsSdkChangeTest : PlatformTestCase() {
    private lateinit var erlangSdk: Sdk
    private lateinit var elixirSdk: Sdk

    override fun setUp() {
        super.setUp()
        // A version before 1.15 lists the home's libraries, so the old home has one for arguments mixing it with the new version.
        File(OLD_HOME, "lib/elixir").mkdirs()
        val (erlang, elixir) = SdkFixtures.pairedInTable("Changing Elixir", add = { SdkFixtures.register(it, testRootDisposable) })
        erlangSdk = erlang
        elixirSdk = elixir
        SdkFixtures.commit(elixirSdk) {
            homePath = OLD_HOME
            versionString = "1.18.4"
        }
    }

    fun testArguments() {
        val expected = SharedCliArguments.args(
            OLD_HOME, "1.18.4", erlangSdk.homePath!!, CliTool.MIX, emptyList(), emptyList(), effectiveOS(elixirSdk.homePath), true,
        )

        val args = whileTheSdkChangesDuringItsProbe { CliArguments.argsOrThrow(ElixirSdkSnapshot.of(elixirSdk), CliTool.MIX, project = project) }

        assertEquals(expected, args)
    }

    fun testMix() = assertOfTheOldHome(whileTheSdkChangesDuringItsProbe { Mix.commandLine(emptyMap(), null, elixirSdk, project = project) })

    fun testIExMix() = assertOfTheOldHome(
        whileTheSdkChangesDuringItsProbe {
            org.elixir_lang.iex.Mix.commandLine(emptyMap(), null, elixirSdk, emptyList(), emptyList(), project)
        },
    )

    private fun assertOfTheOldHome(commandLine: GeneralCommandLine) {
        val environment = commandLine.effectiveEnvironment
        assertEquals(File(OLD_HOME, ".mix").absolutePath, environment["MIX_HOME"])
        val strings = buildList {
            add(commandLine.exePath)
            addAll(commandLine.parametersList.list)
            addAll(environment.values)
        }
        assertEmpty(strings.filter { NEW_HOME in it })
    }

    private fun <T> whileTheSdkChangesDuringItsProbe(build: () -> T): T {
        val changed = AtomicBoolean()
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            object : WslCompatService by MockWslCompatService() {
                override fun requireReachable(path: String, what: String) {
                    if (changed.compareAndSet(false, true)) {
                        // A commit waits for a read lock held here, so it would hang rather than fail.
                        assertFalse("the SDK's home was probed under a read lock", ApplicationManager.getApplication().isReadAccessAllowed)
                        ApplicationManager.getApplication().invokeAndWait {
                            SdkFixtures.commit(elixirSdk) {
                                homePath = NEW_HOME
                                versionString = "1.14.5"
                            }
                        }
                    }
                }
            },
            testRootDisposable,
        )

        val built = onPooledThread { runCatching(build) }.getOrThrow()

        assertTrue("the SDK was not changed while its command line was built", changed.get())
        return built
    }

    private companion object {
        val OLD_HOME: String = SdkFixtures.fakeHome(".asdf/installs/elixir/1.18.4")
        val NEW_HOME: String = SdkFixtures.fakeHome(".asdf/installs/elixir/1.14.5")
    }
}
