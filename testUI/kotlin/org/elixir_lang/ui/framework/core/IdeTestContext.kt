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
import org.junit.jupiter.api.fail
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.nio.file.Path
import java.nio.file.Paths

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
                     * Reports test failures, but only for failures related to the Elixir plugin.
                     *
                     * This method filters test failures to avoid failing tests due to unrelated
                     * plugin errors or IDE issues. It checks if the failure message, details, or
                     * stack trace contain references to the Elixir plugin code (org.elixir_lang package).
                     *
                     * Known problematic plugins and components that are unrelated to the Elixir plugin
                     * are ignored even if they fail.
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
                        // Ignore known problematic plugins and components
                        val ignoredPlugins = listOf(
                            "ReactNativePackagerBeforeRunTaskProvider",
                            "JVMDTraceProfilerConfiguration",
                            "com.intellij.profiler"
                        )
                        val foundIgnoredPlugins = ignoredPlugins.filter {
                            message.contains(it, true) || details.contains(it, true)
                        }
                        if (foundIgnoredPlugins.isNotEmpty()) {
                            println("Ignoring plugin error from ($foundIgnoredPlugins) in $testName: $message")
                            return
                        }

                        // Check if your plugin is mentioned in the error message or details
                        val mentionsElixir = message.contains("elixir", ignoreCase = true) && ! message.startsWith("Test: org.elixir_lang")

                        // Check if stack trace contains references to your plugin's code
                        // Exclude the error reporting mechanism itself (IdeTestContext.kt reportTestFailure)
                        val elixirInStackTrace = details.lines().filter { line ->
                            val trimmed = line.trim()
                            trimmed.startsWith("at org.elixir_lang.") &&
                                    !trimmed.contains("IdeTestContext") &&
                                    !trimmed.contains("reportTestFailure")
                        }

                        if ((mentionsElixir || elixirInStackTrace.isNotEmpty())) {
                            fail { "$testName fails: $message. (mentionsElixir=$mentionsElixir, elixirInStackTrace=$elixirInStackTrace)\n$details" }
                        } else {
                            println("Ignoring unrelated error in $testName: $message")
                        }
                    }
                }
            }
        }
    }

    /**
     * Sets up the test context for UI testing with IntelliJ IDE Starter.
     *
     * This method:
     * 1. Creates a test case with the specified IDE product and version
     * 2. Configures the test context with the built plugin
     * 3. Applies VM options for proper IDE behavior during tests
     * 4. Applies OS-specific configurations
     *
     * @param testName The name of the test (used for test identification)
     * @param ideProduct The IDE product to test (default: IntelliJ IDEA Ultimate)
     * @param project The project the IDE opens at start (default: none)
     * @return IDETestContext configured for running UI tests
     *
     * @throws IllegalStateException if required system properties are not set
     */
    fun setupTestContext(
        testName: String,
        ideProduct: IdeInfo = IdeProductProvider.IU,
        project: ProjectInfoSpec = NoProject,
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
