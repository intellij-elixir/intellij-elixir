package org.elixir_lang.jps.shared.tool_manager

import com.intellij.util.system.OS
import org.elixir_lang.jps.shared.tool_manager.mise.MiseEnvironment
import java.io.IOException
import java.nio.file.Path
import java.util.Locale

/** The environment a tool manager applies to processes started in a directory. */
sealed interface ToolManagerEnvironment {
    data class Variables(val variables: Map<String, String>) : ToolManagerEnvironment

    /** The manager applies to the directory but cannot produce its environment; [message] is plain text naming the fix. */
    data class Error(val message: String) : ToolManagerEnvironment
}

/**
 * Resolves a tool manager's environment for a working directory.
 *
 * Runs in both the IDE and the JPS build process, so implementations must not use IDE services.
 */
fun interface ToolManagerEnvironmentResolver {
    /**
     * `null` when the manager has no environment for [workDir]. Blocks on a subprocess.
     *
     * [launchVariables] are the variables the launch sets itself, which can change what the manager resolves.
     */
    fun resolveEnvironment(workDir: Path, launchVariables: Map<String, String>): ToolManagerEnvironment?
}

fun interface ToolManagerCommandRunner {
    /**
     * Runs [command] in [workDir] with [environment] added to the runner's own.
     *
     * @throws IOException when the command could not be started or timed out.
     */
    @Throws(IOException::class)
    fun run(command: List<String>, workDir: Path, environment: Map<String, String>): ToolManagerCommandOutput
}

data class ToolManagerCommandOutput(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * The tool managers that can apply an environment, keyed by their persistence name in [ToolManagerSettingsState],
 * in the order they are consulted.
 */
fun builtInToolManagerEnvironments(runner: ToolManagerCommandRunner): Map<String, ToolManagerEnvironmentResolver> =
    linkedMapOf(MiseEnvironment.NAME to MiseEnvironment(runner))

/** The first environment from the resolvers, in order, whose manager [settings] has applying its environment switched on. */
fun resolveToolManagerEnvironment(
    settings: ToolManagerSettingsState,
    resolvers: Map<String, ToolManagerEnvironmentResolver>,
    workDir: Path,
    launchVariables: Map<String, String>,
): ToolManagerEnvironment? =
    resolvers.entries
        .asSequence()
        .filter { (name, _) -> settings.appliesEnvironment(name) }
        .firstNotNullOfOrNull { (_, resolver) -> resolver.resolveEnvironment(workDir, launchVariables) }

/**
 * Variables derived from the SDK, decided once the tool manager's are known. Where the OS ignores case, the names it
 * is given are upper case.
 */
fun interface SdkEnvironment {
    fun variables(launchVariables: Map<String, String>, toolManagerVariables: Map<String, String>): Map<String, String>
}

/**
 * Puts [variables] into [environment], except those the launch set explicitly in [explicit], which win, and then the
 * variables [sdkEnvironment] derives from both.
 *
 * [environment] is the process's environment, already holding the inherited and explicit variables. Names are
 * compared ignoring case where the OS does, so a manager's `PATH` replaces an inherited `Path`.
 */
@JvmOverloads
fun putToolManagerEnvironment(
    environment: MutableMap<String, String>,
    variables: Map<String, String>,
    explicit: Map<String, String>,
    sdkEnvironment: SdkEnvironment? = null,
    ignoreCase: Boolean = OS.CURRENT == OS.Windows,
) {
    for ((name, value) in variables) {
        if (explicit.keys.none { it.equals(name, ignoreCase) }) {
            put(environment, name, value, ignoreCase)
        }
    }
    sdkEnvironment
        ?.variables(explicit.namesFor(ignoreCase), variables.namesFor(ignoreCase))
        ?.forEach { (name, value) -> put(environment, name, value, ignoreCase) }
}

/** Where case is ignored, the names in upper case; of several spellings of one name, the last wins. */
private fun Map<String, String>.namesFor(ignoreCase: Boolean): Map<String, String> =
    if (ignoreCase) entries.associate { (name, value) -> name.uppercase(Locale.ROOT) to value } else this

private fun put(environment: MutableMap<String, String>, name: String, value: String, ignoreCase: Boolean) {
    environment.keys.removeIf { it != name && it.equals(name, ignoreCase) }
    environment[name] = value
}
