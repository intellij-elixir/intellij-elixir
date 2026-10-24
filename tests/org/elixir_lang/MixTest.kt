package org.elixir_lang

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.system.OS
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironment
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironmentResolver
import org.elixir_lang.run.WslAwareCommandLine
import org.elixir_lang.run.WslAwarePtyCommandLine
import org.elixir_lang.sdk.erlang_dependent.ErlangSdkResolver
import org.elixir_lang.sdk.erlang_dependent.ErlangSdkResult
import org.elixir_lang.sdk.erlang_dependent.MissingErlangSdkReason
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType
import org.elixir_lang.sdk.erlang.Type as ErlangSdkType

/**
 * A version manager installs Hex under its own Elixir install rather than `~/.mix`, and only exports
 * `MIX_HOME` inside the shim's exec-env - which these command lines bypass by construction, since
 * they run the SDK's `mix` directly. `SdkPathsTest` pins the derivation itself, but nothing pinned
 * that [Mix.commandLine] applies it, so dropping that call left the suite green while sending `mix`
 * back to `~/.mix`, where the archive is not.
 */
class MixTest : PlatformTestCase() {
    private val erlangSdkByElixirSdk: MutableMap<Sdk, Sdk> = mutableMapOf()
    private val createdPaths: MutableList<Path> = mutableListOf()
    private lateinit var erlangSdk: Sdk

    override fun setUp() {
        super.setUp()

        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            ErlangSdkResolver::class.java,
            ErlangSdkResolverMockImpl(erlangSdkByElixirSdk),
            testRootDisposable,
        )

        erlangSdk = mockSdk(ErlangSdkType.instance, "28.0.2", createErlangHome())
    }

    override fun tearDown() {
        try {
            createdPaths.forEach { path -> FileUtil.delete(path.toFile()) }
            createdPaths.clear()
            erlangSdkByElixirSdk.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testMixHomeIsDerivedFromAVersionManagerElixirSdkHome() {
        val elixirHome = createVersionManagerElixirHome()

        assertEquals(sdkMixVariables(elixirHome), mixVariables(commandLine(elixirHome)))
    }

    fun testMixHomeIsLeftAloneForAnElixirSdkHomeOutsideAVersionManager() {
        val inherited = listOfNotNull(
            System.getenv("MIX_HOME")?.let { "MIX_HOME" to it },
            System.getenv("MIX_ARCHIVES")?.let { "MIX_ARCHIVES" to it },
        ).toMap()

        assertEquals(inherited, mixVariables(commandLine(createElixirHome())))
    }

    fun testAToolManagersMixHomeOverridesTheSdks() {
        val commandLine = commandLine(createVersionManagerElixirHome())

        assertEquals(TOOL_MANAGER_MIX_VARIABLES, mixVariables(commandLine, TOOL_MANAGER_MIX_VARIABLES))
    }

    fun testAToolManagersMixHomeOverridesTheSdksForIEx() {
        val elixirSdk = mockSdk(ElixirSdkType.instance, ELIXIR_VERSION, createVersionManagerElixirHome(), erlangSdk)
        val commandLine = runReadActionBlocking {
            org.elixir_lang.iex.Mix.commandLine(
                environment = emptyMap(),
                workingDirectory = null,
                elixirSdk = elixirSdk,
                erlArgumentList = emptyList(),
                iexArgumentList = emptyList(),
            )
        }

        assertEquals(TOOL_MANAGER_MIX_VARIABLES, mixVariables(commandLine, TOOL_MANAGER_MIX_VARIABLES))
    }

    fun testAToolManagersMixHomeIsUsedEvenForAnotherVersion() {
        val elixirHome = createVersionManagerElixirHome()
        val otherVersionMixHome = elixirHome.resolveSibling("1.16.0").resolve(".mix")
        val toolManagerVariables = mapOf(
            "MIX_HOME" to otherVersionMixHome.absolutePathString(),
            "MIX_ARCHIVES" to otherVersionMixHome.resolve("archives").absolutePathString(),
        )

        assertEquals(toolManagerVariables, mixVariables(commandLine(elixirHome), toolManagerVariables))
    }

    fun testAToolManagerWithoutAMixHomeLeavesTheSdks() {
        val elixirHome = createVersionManagerElixirHome()

        assertEquals(sdkMixVariables(elixirHome), mixVariables(commandLine(elixirHome), mapOf("RUSTUP_TOOLCHAIN" to "1.94.1")))
    }

    fun testAMixHomeTheLaunchSetsOverridesTheToolManagers() {
        val custom = trackPath(Files.createTempDirectory("custom-mix-home")).absolutePathString()
        val commandLine = commandLine(createVersionManagerElixirHome(), mapOf("MIX_HOME" to custom))

        assertEquals(
            mapOf("MIX_HOME" to custom, "MIX_ARCHIVES" to "$custom${File.separator}archives"),
            mixVariables(commandLine, TOOL_MANAGER_MIX_VARIABLES),
        )
    }

    fun testAToolManagersMixHomeWithoutArchivesGetsItsOwnArchives() {
        val mixHome = TOOL_MANAGER_MIX_VARIABLES.getValue("MIX_HOME")

        assertEquals(
            mapOf("MIX_HOME" to mixHome, "MIX_ARCHIVES" to "$mixHome/archives"),
            mixVariables(commandLine(createVersionManagerElixirHome()), mapOf("MIX_HOME" to mixHome)),
        )
    }

    fun testAToolManagersArchivesWithoutAMixHomeAreKept() {
        val elixirHome = createVersionManagerElixirHome()
        val mixArchives = TOOL_MANAGER_MIX_VARIABLES.getValue("MIX_ARCHIVES")

        assertEquals(
            mapOf("MIX_HOME" to sdkMixVariables(elixirHome).getValue("MIX_HOME"), "MIX_ARCHIVES" to mixArchives),
            mixVariables(commandLine(elixirHome), mapOf("MIX_ARCHIVES" to mixArchives)),
        )
    }

    fun testAMixArchivesTheLaunchSetsOverridesTheToolManagers() {
        val custom = trackPath(Files.createTempDirectory("custom-mix-archives")).absolutePathString()
        val commandLine = commandLine(createVersionManagerElixirHome(), mapOf("MIX_ARCHIVES" to custom))

        assertEquals(
            mapOf("MIX_HOME" to TOOL_MANAGER_MIX_VARIABLES.getValue("MIX_HOME"), "MIX_ARCHIVES" to custom),
            mixVariables(commandLine, TOOL_MANAGER_MIX_VARIABLES),
        )
    }

    private fun sdkMixVariables(elixirHome: Path): Map<String, String> {
        val mixHome = elixirHome.resolve(".mix")
        return mapOf(
            "MIX_HOME" to mixHome.absolutePathString(),
            "MIX_ARCHIVES" to mixHome.resolve("archives").absolutePathString(),
        )
    }

    /**
     * The `MIX_HOME` and `MIX_ARCHIVES` the process [commandLine] starts sees, with a tool manager that sets
     * [toolManagerVariables].
     */
    private fun mixVariables(
        commandLine: GeneralCommandLine,
        toolManagerVariables: Map<String, String>? = null,
    ): Map<String, String> {
        val toolManager = toolManagerVariables?.let {
            ToolManagerEnvironmentResolver { _, _ -> ToolManagerEnvironment.Variables(it) }
        }
        (commandLine as? WslAwareCommandLine)?.toolManagerEnvironment = toolManager
        (commandLine as? WslAwarePtyCommandLine)?.toolManagerEnvironment = toolManager
        commandLine.parametersList.clearAll()
        if (OS.CURRENT == OS.Windows) {
            commandLine.exePath = "cmd.exe"
            commandLine.addParameters("/c", "set")
        } else {
            commandLine.exePath = "env"
        }
        commandLine.withWorkingDirectory(trackPath(Files.createTempDirectory("mix-work-dir")))

        val output = AppExecutorUtil.getAppExecutorService().submit(Callable {
            val process = commandLine.createProcess()
            process.inputStream.bufferedReader().readText().also { process.waitFor(30, TimeUnit.SECONDS) }
        }).get(60, TimeUnit.SECONDS)

        return output.lines()
            .map { it.trim().split("=", limit = 2) }
            .filter { it.size == 2 && it[0] in setOf("MIX_HOME", "MIX_ARCHIVES") }
            .associate { it[0] to it[1] }
    }

    private fun commandLine(elixirHome: Path, environment: Map<String, String> = emptyMap()): GeneralCommandLine =
        runReadActionBlocking {
            Mix.commandLine(
                environment = environment,
                workingDirectory = null,
                elixirSdk = mockSdk(ElixirSdkType.instance, ELIXIR_VERSION, elixirHome, erlangSdk),
            )
        }

    private fun mockSdk(sdkType: SdkType, version: String, homePath: Path, erlangSdk: Sdk? = null): Sdk {
        val sdk = ProjectJdkImpl(version, sdkType, homePath.toString(), version)
        if (erlangSdk != null) {
            erlangSdkByElixirSdk[sdk] = erlangSdk
        }
        return sdk
    }

    /** An asdf-shaped home, since `detectSource` recognises a version manager by path shape alone. */
    private fun createVersionManagerElixirHome(): Path =
        populateElixirHome(
            Files.createDirectories(
                trackPath(Files.createTempDirectory("asdf-user-home"))
                    .resolve(".asdf/installs/elixir/$ELIXIR_VERSION")
            )
        )

    private fun createElixirHome(): Path =
        populateElixirHome(trackPath(Files.createTempDirectory("elixir-home")))

    private fun populateElixirHome(home: Path): Path {
        Files.createDirectories(home.resolve("bin"))
        val libDir = home.resolve("bin/../lib")
        listOf("eex", "elixir", "mix").forEach { lib -> Files.createDirectories(libDir.resolve(lib).resolve("ebin")) }
        return home
    }

    private fun createErlangHome(): Path {
        val home = trackPath(Files.createTempDirectory("erlang-home"))
        Files.createFile(Files.createDirectories(home.resolve("bin")).resolve("erl"))
        return home
    }

    private fun trackPath(path: Path): Path {
        createdPaths.add(path)
        return path
    }

    private class ErlangSdkResolverMockImpl(
        private val erlangSdkByElixirSdk: Map<Sdk, Sdk>
    ) : ErlangSdkResolver {
        override fun resolveErlangSdkResult(
            elixirSdk: Sdk,
            sdkModel: SdkModel?
        ): ErlangSdkResult {
            val erlangSdk = erlangSdkByElixirSdk[elixirSdk]
                ?: return ErlangSdkResult.Missing(
                    elixirSdk,
                    MissingErlangSdkReason.NOT_FOUND,
                )
            return ErlangSdkResult.Success(erlangSdk)
        }
    }

    private companion object {
        private const val ELIXIR_VERSION = "1.18.4-otp-27"
        private val TOOL_MANAGER_MIX_VARIABLES =
            mapOf("MIX_HOME" to "/tool-manager/.mix", "MIX_ARCHIVES" to "/tool-manager/.mix/archives")
    }
}
