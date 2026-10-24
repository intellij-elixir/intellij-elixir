package org.elixir_lang.mise

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import org.elixir_lang.jps.shared.tool_manager.mise.MiseEnvironment
import org.elixir_lang.sdk.wsl.wslCompat
import org.jetbrains.annotations.VisibleForTesting
import java.nio.file.Path
import java.nio.file.Paths

private val LOG = logger<Mise>()

/**
 * A single tool entry from `mise ls --json`.
 */
data class MiseToolEntry(
    val version: String,
    val requestedVersion: String,
    val installPath: String,
    val sourceType: String?,
    val sourcePath: String?,
    val installed: Boolean,
    val active: Boolean,
)

/**
 * Resolved mise tool versions for the current project directory.
 */
data class MiseVersions(
    val elixir: MiseToolEntry?,
    val erlang: MiseToolEntry?,
)

/**
 * The result of a [Mise.resolveVersions] call.
 *
 * `null` from [Mise.resolveVersions] means mise is not on PATH or a transient failure occurred.
 * A non-null result is one of:
 * - [Success]          - mise ran successfully and returned version data.
 * - [UntrustedConfig]  - mise exited non-zero because a config file in the project directory has
 *   not been trusted yet.  The user must run `mise trust` in the directory to fix this.
 */
sealed interface MiseResult {
    data class Success(val versions: MiseVersions) : MiseResult
    data class UntrustedConfig(val configFilePath: String) : MiseResult
}

/**
 * Raw Gson model matching the snake_case JSON output of `mise ls --json`.
 * Converted to [MiseToolEntry] after parsing.
 */
private data class RawMiseEntry(
    val version: String?,
    val requestedVersion: String?,
    val installPath: String?,
    val source: RawMiseSource?,
    val installed: Boolean?,
    val active: Boolean?,
)

private data class RawMiseSource(
    val type: String?,
    val path: String?,
)

/** Raw Gson model for one entry from `mise config ls --json`. */
private data class RawMiseConfigEntry(
    val path: String?,
)

/** Raw Gson models for `mise doctor --json`. */
private data class RawMiseDoctorDirs(val state: String?)
private data class RawMiseDoctor(val dirs: RawMiseDoctorDirs?)

/**
 * Integrates with [mise](https://mise.jdx.dev/) to resolve tool versions for the current project.
 *
 * Shells out to `mise ls --current --json` using the module content root as the working directory,
 * which causes mise to apply its normal walk-up resolution rules (.tool-versions, mise.toml, etc.)
 * for that directory.
 *
 * All failures (mise not installed, non-zero exit, unparseable output) are swallowed and return
 * `null` - the feature simply doesn't activate when mise is unavailable.
 */
object Mise {
    private const val TIMEOUT_MS = 10_000

    /**
     * Invokes `mise ls --current --json` in [workDir] and parses the result.
     *
     * Returns `null` if mise is not on PATH, times out, or fails in a way that is not
     * otherwise classified.  Returns [MiseResult.UntrustedConfig] if mise exits non-zero because
     * a config file has not been trusted.  Returns [MiseResult.Success] on a clean run.
     *
     * **Threading**: Must not be called on the EDT or under a read lock. This method spawns
     * a subprocess and blocks for up to [Mise.TIMEOUT_MS]ms. Callers must ensure they are running
     * on a background thread outside of any read lock (e.g. inside `withContext(Dispatchers.IO)`
     * after releasing any read lock).
     */
    @RequiresBackgroundThread
    fun resolveVersions(workDir: Path): MiseResult? {
        // `assertBackgroundThread()` only checks EDT, NOT holdsReadLock - both guards are needed.
        ThreadingAssertions.assertBackgroundThread()
        check(!ApplicationManager.getApplication().holdsReadLock()) {
            "Mise.resolveVersions() must not be called under a read lock - " +
                    "it spawns a subprocess that blocks for up to ${TIMEOUT_MS}ms"
        }
        LOG.trace("resolveVersions: invoked for workDir=$workDir")
        return try {
            val commandLine = GeneralCommandLine("mise", "ls", "--current", "--json")
                .withWorkDirectory(workDir.toFile())
            LOG.trace("resolveVersions: running '${commandLine.commandLineString}' in $workDir")
            val handler = CapturingProcessHandler(commandLine)
            val output = handler.runProcess(TIMEOUT_MS)

            LOG.trace("resolveVersions: exit=${output.exitCode}, stdout.length=${output.stdout.length}, stderr.length=${output.stderr.length}")
            if (output.exitCode != 0) {
                val trustError = parseTrustError(output.stderr)
                if (trustError != null) {
                    LOG.warn(
                        "mise config not trusted in $workDir: '${trustError.configFilePath}'. " +
                                "Run `mise trust` in the project directory to allow mise to read it."
                    )
                    return trustError
                }
                LOG.debug("mise ls --current --json exited with ${output.exitCode} in $workDir: ${output.stderr.take(200)}")
                return null
            }

            LOG.trace("resolveVersions: stdout=${output.stdout.take(500)}")
            val result = parseOutput(output.stdout, workDir)
            LOG.trace("resolveVersions: parsed result=$result")
            result?.let { MiseResult.Success(it) }
        } catch (e: Exception) {
            LOG.debug("mise ls --current --json failed in $workDir: ${e.message}")
            LOG.trace("resolveVersions: exception class=${e::class.simpleName} message=${e.message}")
            null
        }
    }

    /**
     * Invokes `mise config ls --json` in [workDir] and returns the list of config file paths
     * that mise considers active for that directory.
     *
     * These are the files that, when modified, should trigger a re-scan.  The list typically
     * includes both project-local files (`mise.toml`, `.tool-versions`) and user-global files
     * (`~/.config/mise/config.toml`, `~/.tool-versions`).
     *
     * Returns `null` if mise is unavailable, times out, or the command fails.
     *
     * **Threading**: Must not be called on the EDT or under a read lock.
     */
    @RequiresBackgroundThread
    fun configFiles(workDir: Path): List<Path>? {
        ThreadingAssertions.assertBackgroundThread()
        check(!ApplicationManager.getApplication().holdsReadLock()) {
            "Mise.configFiles() must not be called under a read lock"
        }
        LOG.trace("configFiles: invoked for workDir=$workDir")
        return try {
            val commandLine = GeneralCommandLine("mise", "config", "ls", "--json")
                .withWorkDirectory(workDir.toFile())
            val handler = CapturingProcessHandler(commandLine)
            val output = handler.runProcess(TIMEOUT_MS)
            if (output.exitCode != 0) {
                LOG.debug("mise config ls --json exited with ${output.exitCode} in $workDir")
                return null
            }
            val gson = GsonBuilder().create()
            val type = object : TypeToken<List<RawMiseConfigEntry>>() {}.type
            @Suppress("UNCHECKED_CAST")
            val entries = gson.fromJson(output.stdout, type) as? List<RawMiseConfigEntry>
                ?: return null
            val workDirString = workDir.toString()
            entries.mapNotNull { entry ->
                entry.path?.let { p ->
                    // mise outputs Linux absolute paths (e.g. /home/user/.config/mise/config.toml)
                    // when running inside WSL.  Convert to a Windows UNC path using the workDir as
                    // context before handing the result to Paths.get(), because Paths.get("/...")
                    // on Windows produces a drive-relative WindowsPath, not a Linux path.
                    // maybeConvertLinuxPathToWindowsUncFromContext is a no-op on native Linux /
                    // plain Windows, so this is safe everywhere.
                    Paths.get(wslCompat.maybeConvertLinuxPathToWindowsUncFromContext(workDirString, p))
                }
            }
                .also { LOG.trace("configFiles: found ${it.size} config file(s) for $workDir") }
        } catch (e: Exception) {
            LOG.debug("mise config ls --json failed in $workDir: ${e.message}")
            null
        }
    }

    /**
     * Invokes `mise doctor --json` in [workDir] and returns the mise state directory path.
     *
     * The state directory contains the `trusted-configs` subdirectory, where mise records
     * which config files have been trusted by the user (`mise trust`).  Watching this
     * directory lets the plugin re-scan automatically when the user trusts a config.
     *
     * Returns `null` if mise is unavailable, times out, or the command fails.
     *
     * **Threading**: Must not be called on the EDT or under a read lock.
     */
    @RequiresBackgroundThread
    fun stateDir(workDir: Path): Path? {
        ThreadingAssertions.assertBackgroundThread()
        ThreadingAssertions.assertNoReadAccess()
        LOG.trace("stateDir: invoked for workDir=$workDir")
        return try {
            val commandLine = GeneralCommandLine("mise", "doctor", "--json")
                .withWorkDirectory(workDir.toFile())
            val handler = CapturingProcessHandler(commandLine)
            stateDirFrom(handler.runProcess(TIMEOUT_MS), workDir.toString())
                .also { LOG.trace("stateDir: resolved to $it") }
        } catch (e: Exception) {
            LOG.debug("mise doctor --json failed in $workDir: ${e.message}")
            null
        }
    }

    @VisibleForTesting
    internal fun stateDirFrom(output: ProcessOutput, workDirString: String): Path? {
        // `mise doctor` exits non-zero whenever it finds a problem, such as a pinned tool not installed.
        if (output.exitCode != 0) LOG.debug("mise doctor --json exited with ${output.exitCode} in $workDirString")
        return parseDoctorStateDir(output.stdout, workDirString)
    }

    /**
     * Parses the JSON output of `mise doctor --json` and returns the `dirs.state` path.
     *
     * WSL Linux paths are converted to Windows UNC paths using [workDirString] as context
     * (a no-op on native Linux / plain Windows).
     *
     * `internal` for testing - use [Mise.stateDir] in production code.
     */
    @VisibleForTesting
    internal fun parseDoctorStateDir(json: String, workDirString: String): Path? {
        return try {
            val gson = GsonBuilder().create()
            val parsed = gson.fromJson(json, RawMiseDoctor::class.java) ?: return null
            val state = parsed.dirs?.state ?: return null
            Paths.get(wslCompat.maybeConvertLinuxPathToWindowsUncFromContext(workDirString, state))
        } catch (e: Exception) {
            LOG.debug("parseDoctorStateDir: failed to parse: ${e.message}")
            null
        }
    }

    /**
     * Detects a `mise trust` error in [stderr].
     *
     * When mise encounters an untrusted config it prints two lines to stderr:
     * ```
     * mise ERROR error parsing config file: <path>
     * mise ERROR Config files in <path> are not trusted.
     * ```
     *
     * We match the second line to extract the untrusted config path.
     */
    @VisibleForTesting
    internal fun parseTrustError(stderr: String): MiseResult.UntrustedConfig? =
        MiseEnvironment.untrustedConfigPath(stderr)?.let { MiseResult.UntrustedConfig(it) }

    /**
     * Parses the JSON output of `mise ls --local --json` into a [MiseVersions] result.
     *
     * Takes the first entry for each tool key where `installed == true && active == true`.
     *
     * `internal` for testing - use [Mise.resolveVersions] in production code.
     */
    @VisibleForTesting
    internal fun parseOutput(json: String, workDir: Path): MiseVersions? {
        return try {
            val gson = GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .create()
            val type = object : TypeToken<Map<String, List<RawMiseEntry>>>() {}.type
            // fromJson returns a platform type (T!) which can be null for empty/invalid JSON.
            @Suppress("UNCHECKED_CAST")
            val allTools = gson.fromJson(json, type) as? Map<String, List<RawMiseEntry>>
                ?: return null

            val elixir = allTools["elixir"]?.activeEntry()?.toMiseToolEntry(workDir)

            val erlang = allTools["erlang"]?.activeEntry()?.toMiseToolEntry(workDir)

            MiseVersions(elixir, erlang)
        } catch (e: Exception) {
            LOG.debug("Failed to parse mise ls output: ${e.message}")
            null
        }
    }

    /** The installed, active build; failing that, one not installed, which mise also reports as inactive. */
    private fun List<RawMiseEntry>.activeEntry(): RawMiseEntry? =
        firstOrNull { it.installed == true && it.active == true } ?: firstOrNull { it.installed == false }

    private fun RawMiseEntry.toMiseToolEntry(workDir: Path): MiseToolEntry? {
        val v = version ?: return null
        val workDirString = workDir.toString()
        val ip = installPath?.let {
            wslCompat.maybeConvertLinuxPathToWindowsUncFromContext(workDirString, it)
        } ?: return null
        return MiseToolEntry(
            version = v,
            requestedVersion = requestedVersion ?: v,
            installPath = ip,
            sourceType = source?.type,
            sourcePath = source?.path?.let {
                wslCompat.maybeConvertLinuxPathToWindowsUncFromContext(workDirString, it)
            },
            installed = installed ?: false,
            active = active ?: false,
        )
    }
}
