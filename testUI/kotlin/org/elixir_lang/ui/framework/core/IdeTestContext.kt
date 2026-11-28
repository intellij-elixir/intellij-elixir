package org.elixir_lang.ui.framework.core

import com.intellij.driver.sdk.step
import com.intellij.ide.starter.buildTool.GradleBuildTool
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.community.model.BuildType
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.path.GlobalPaths
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.NoProject
import com.intellij.ide.starter.project.ProjectInfoSpec
import com.intellij.ide.starter.runner.Starter
import com.intellij.ide.starter.utils.Git
import com.intellij.util.system.OS
import org.elixir_lang.jps.shared.cli.CliTool
import org.elixir_lang.jps.shared.sdk.SdkPaths
import org.junit.jupiter.api.fail
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText

/**
 * Custom GlobalPaths implementation that points to the project's build directory.
 * This ensures all test artifacts are stored within the project structure.
 */
class TemplatePaths : GlobalPaths(Git.getRepoRoot().resolve("build"))

/**
 * Helper object for accessing test system properties with consistent error handling.
 *
 * All properties are lazy-loaded and throw IllegalStateException with a consistent
 * error message format if the property is not set.
 */
private object TestProperties {
    /**
     * Gets a required system property or throws an exception with a consistent error message.
     *
     * @param name The system property name
     * @return The property value
     * @throws IllegalStateException if the system property is not set
     */
    fun getRequired(name: String): String =
        System.getProperty(name) ?: throw IllegalStateException("System property '$name' not set")

    val projectPath: String by lazy { getRequired("projectPath") }
    val erlangSdkPath: String by lazy { getRequired("erlangSdkPath") }
    val elixirSdkPath: String by lazy { getRequired("elixirSdkPath") }
}

/**
 * Factory for configuring IntelliJ IDE UI tests using the IntelliJ IDE Starter framework.
 *
 * This provides the necessary configuration to:
 * - Initialize the test environment with proper paths and dependencies
 * - Install the plugin under test into the IDE instance
 * - Configure VM options for optimal test execution
 * - Apply OS-specific settings for compatibility
 * - Support multiple IDE types (IDEA Ultimate, Community, RubyMine, etc.)
 *
 * @see [IntelliJ IDE Starter Documentation](https://github.com/JetBrains/intellij-ide-starter)
 */
object IdeTestContext {
    // Expose test properties for use in test classes
    val projectPath: String get() = TestProperties.projectPath
    val erlangSdkPath: String get() = TestProperties.erlangSdkPath
    val elixirSdkPath: String get() = TestProperties.elixirSdkPath

    /** Where Starter puts each test's home. Read through this object, as its `init` binds the paths it answers from. */
    val testsDirectory: Path get() = GlobalPaths.instance.testsDirectory

    init {
        // Configure dependency injection to use our custom paths
        di = DI.Companion {
            extend(di)
            bindSingleton<GlobalPaths>(overrides = true) { TemplatePaths() }
            bindSingleton<CIServer>(overrides = true) {
                object : CIServer by NoCIServer {

                    /**
                     * Fails the test on an IDE error [blamesPlugin] counts as the Elixir plugin's, or on another report naming
                     * Elixir, such as processes left running, and only prints any other.
                     *
                     * @param testName The name of the test that failed
                     * @param message The failure message
                     * @param details Detailed failure information including stack traces
                     * @param linkToLogs Optional link to test logs
                     * @throws AssertionError if the failure is related to the Elixir plugin
                     */
                    override fun reportTestFailure(
                        testName: String,
                        message: String,
                        details: String,
                        linkToLogs: String?
                    ) {
                        // An IDE error's message names the test, so only Starter's other reports can name Elixir in theirs.
                        val namesElixir = message.contains("elixir", ignoreCase = true) && !message.startsWith("Test: org.elixir_lang")
                        if (!ignored(message) && (blamesPlugin(details) || namesElixir && !ignored(details))) {
                            fail { "$testName fails: $message. (plugin frames: ${pluginFramesOf(details)})\n$details" }
                        } else {
                            println("Ignoring unrelated error in $testName: $message")
                        }
                    }
                }
            }
        }
    }

    /**
     * Hex where the IDE's Mix looks for it, which [SdkPaths.mixHomeVariables] decides, and without it `mix deps` waits at
     * a prompt to install it. The private home is emptied before each test, so a test that needs Hex in it calls this
     * after the test starts and before the IDE does.
     */
    fun installHexInPrivateHome() =
        runInPrivateHome(elixirSdkPath, erlangSdkPath, NoScreen.privateHome, CliTool.MIX.getExecutableFilepath(elixirSdkPath), "local.hex", "--force", "--if-missing")

    /**
     * [command] run in [dir] with the installs at [elixirHome] and [erlangHome] first on the PATH, the private home as its
     * home and the `MIX_HOME` the IDE's Mix gets for that Elixir install, which must succeed.
     */
    fun runInPrivateHome(elixirHome: String, erlangHome: String, dir: Path, vararg command: String) {
        val home = NoScreen.privateHome
        val path = listOf(Path.of(elixirHome, "bin"), Path.of(erlangHome, "bin"), System.getenv("PATH").orEmpty())
            .joinToString(File.pathSeparator)
        // Into a file, not a pipe, so a stalled command can't block a read and the timeout always runs.
        val output = createTempFile("command", ".log")
        val process = ProcessBuilder(*command).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).apply {
            environment().apply {
                remove("MIX_HOME")
                remove("MIX_ARCHIVES")
                putAll(mapOf("HOME" to "$home", "PATH" to path))
                putAll(SdkPaths.mixHomeVariables(emptyMap(), emptyMap(), elixirHome))
            }
        }.start()
        try {
            check(process.waitFor(5, TimeUnit.MINUTES) && process.exitValue() == 0) { "${command.joinToString(" ")} failed: ${output.readText()}" }
        } finally {
            process.destroyForcibly()
            output.deleteIfExists()
        }
    }

    /**
     * Sets up the test context for UI testing with IntelliJ IDE Starter.
     *
     * This method:
     * 1. Creates a test case with the specified IDE product and version
     * 2. Configures the test context with the built plugin, and the Marketplace's latest build of each of [plugins]
     * 3. Applies VM options for proper IDE behavior during tests
     * 4. Applies OS-specific configurations
     *
     * @param testName The name of the test (used for test identification)
     * @param ideProduct The IDE product to test (default: IntelliJ IDEA Ultimate)
     * @param project The project the IDE opens at start (default: none)
     * @param plugins The Marketplace plugin ids to install beside it (default: none)
     * @return IDETestContext configured for running UI tests
     *
     * @throws IllegalStateException if required system properties are not set
     */
    fun setupTestContext(
        testName: String,
        ideProduct: IdeInfo = IdeProductProvider.IU,
        project: ProjectInfoSpec = NoProject,
        plugins: List<String> = emptyList(),
    ): IDETestContext =
        step("Setup test context for $testName (${ideProduct.productCode})") {
            checkNotNull(NoScreenGuard.active) { "no NoScreenGuard is active: testUI/resources must register NoScreenExtension" }

            // Create test case with the specified IDE product and version
            val testCase = TestCase(
                ideProduct.copy(
                    version = System.getProperty("uiPlatformBuildVersion"),
                    buildType = BuildType.RELEASE.type
                ), project
            )

            Starter.newContext(testName = testName, testCase = testCase).apply {
                // Install the plugin that was built by the buildPlugin task
                val pluginPath = System.getProperty("path.to.build.plugin")
                PluginConfigurator(this).installPluginFromPath(Paths.get(pluginPath))
                plugins.forEach { PluginConfigurator(this).installPluginFromPluginManager(it, ide) }
                withBuildTool<GradleBuildTool>()
                NoScreen.prepareFreeMode(paths.configDir)
            }.applyVMOptionsPatch {
                // The driver's Robot can't click under WLToolkit on WSLg, which `auto` picks from the `wayland-0` socket.
                if (NoScreen.onScreen && NoScreen.onLinux) addSystemProperty("awt.toolkit.name", "XToolkit")
                val home = NoScreen.privateHome
                addSystemProperty("user.home", home)
                addSystemProperty("java.util.prefs.userRoot", home)
                withEnv("HOME", home.toString())
                listOf("XDG_CONFIG_HOME" to ".config", "XDG_DATA_HOME" to ".local/share", "XDG_CACHE_HOME" to ".cache")
                    .forEach { (name, path) -> withEnv(name, home.resolve(path).toString()) }

                // === Common system properties for all operating systems ===

                // Required JVM arguments for module access
                addLine("--add-opens", "java.base/java.lang=ALL-UNNAMED")
                addLine("--add-opens", "java.desktop/javax.swing=ALL-UNNAMED")

                // Core IDE configuration
                addSystemProperty("idea.trust.all.projects", true) // Trust all projects automatically
                addSystemProperty("jb.consents.confirmation.enabled", false) // Disable consent dialogs
                addSystemProperty("jb.privacy.policy.text", "<!--999.999-->") // Skip privacy policy
                addSystemProperty("ide.show.tips.on.startup.default.value", false) // No tips on startup

                // Test framework configuration
                addSystemProperty("junit.jupiter.extensions.autodetection.enabled", true)
                addSystemProperty("shared.indexes.download.auto.consent", true)

                // UI testing specific
                addSystemProperty("expose.ui.hierarchy.url", true) // Enable UI hierarchy inspection
                addSystemProperty("ide.experimental.ui", true) // Use new UI for testing

                // === Logging configuration ===
                // Enable DEBUG logging for Elixir plugin components
                addSystemProperty("idea.log.debug.categories", "#org.elixir_lang")
                // Alternative: set specific loggers to DEBUG
                addSystemProperty("idea.log.trace.categories", "")

                // === OS-specific system properties ===

                when (OS.CURRENT) {
                    OS.macOS -> {
                        // macOS specific settings
                        addSystemProperty("ide.mac.file.chooser.native", false) // Use Java file chooser
                        addSystemProperty("ide.mac.message.dialogs.as.sheets", false) // Use regular dialogs
                        addSystemProperty("jbScreenMenuBar.enabled", false) // Disable native menu bar
                        addSystemProperty("ide.native.launcher", true) // Use native launcher
                    }

                    OS.Windows -> {
                        // Windows specific settings
                        addSystemProperty("ide.win.file.chooser.native", false)
                    }

                    OS.Linux -> {
                        // Linux specific settings
                        addSystemProperty("ide.browser.jcef.enabled", true)
                        addSystemProperty("ide.native.launcher", false) // Avoid launcher issues on Linux

                        // X11/Wayland compatibility
                        addSystemProperty("sun.java2d.uiScale.enabled", false)
                        addSystemProperty("sun.java2d.xrender", false)
                    }

                    else -> {} // FreeBSD / Other fall through to statisy linter but unsupported
                }

            }.addProjectToTrustedLocations()
        }
}

/** Plugins and components whose errors are known to be unrelated to the Elixir plugin. */
private val ignoredPlugins = listOf(
    "ReactNativePackagerBeforeRunTaskProvider",
    "JVMDTraceProfilerConfiguration",
    "com.intellij.profiler"
)

private fun ignored(text: String) = ignoredPlugins.any { text.contains(it, ignoreCase = true) }

/**
 * Whether an IDE error, from [details], its stack trace, is the Elixir plugin's, so a test fails on it: the trace has
 * frames [pluginFramesOf] counts, and names none of [ignoredPlugins].
 */
internal fun blamesPlugin(details: String): Boolean = !ignored(details) && pluginFramesOf(details).isNotEmpty()

/**
 * The plugin's frames in [details], a failure's stack trace, that ran the event that threw. In each section of the trace,
 * the top one and each `Caused by:` or `Suppressed:`, only the frames above its innermost event dispatch count: the frames
 * below it only wait in a modal loop, such as a dialog's, while some other component handles the event. A section with no
 * event dispatch, such as a pooled thread's, counts whole.
 */
internal fun pluginFramesOf(details: String): List<String> =
    sections(details.lines()).flatMap { section ->
        section.takeWhile { !eventDispatch.containsMatchIn(it) }.filter { it.trim().startsWith("at org.elixir_lang.") }
    }

private val eventDispatch = Regex("""^\s*at (\S+/)?(java\.awt\.EventQueue|com\.intellij\.ide\.IdeEventQueue)\.dispatchEvent\(""")

private fun sections(lines: List<String>): List<List<String>> =
    lines.fold(mutableListOf(mutableListOf<String>())) { sections, line ->
        if (line.trimStart().let { it.startsWith("Caused by:") || it.startsWith("Suppressed:") }) sections.add(mutableListOf())
        sections.last().add(line)
        sections
    }
