package platform.windows

import quoter.QuoterPlatform
import quoter.getReleaseEnvironment
import org.gradle.api.logging.Logger
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Windows implementation of QuoterPlatform.
 * Uses ProcessBuilder to manage the quoter as a background process.
 *
 * Note: Windows releases don't support the 'daemon' command (no run_erl),
 * so we use 'start' and manage the process lifecycle directly.
 *
 * This approach does NOT use Windows services (no admin required).
 *
 * [startEpmd] false when an epmd is already answering. It matters most here: erlexec launches epmd
 * detached on Windows, and a running executable pins its own directory - see [quoter.Epmd].
 */
class WindowsQuoterPlatform(private val startEpmd: Boolean = true) : QuoterPlatform {

    /**
     * Converts the executable path to Windows format by appending .bat extension.
     */
    private fun toWindowsExecutable(executable: File): File {
        return File(executable.absolutePath + ".bat")
    }

    /**
     * Cleans stdout by removing any lines that also appear in stderr.
     * This handles Erlang releases writing warnings to both streams.
     *
     * @param stdout The standard output stream content
     * @param stderr The standard error stream content
     * @return Cleaned stdout with stderr lines removed
     */
    private fun cleanStdout(stdout: String, stderr: String): String {
        val stderrLines = stderr.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        return stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { it !in stderrLines }
            .joinToString("\n")
            .trim()
    }

    override fun startDaemon(
        execOps: ExecOperations,
        executable: File,
        releaseTmp: File?,
        releaseName: String,
        logger: Logger
    ): Process {
        logger.lifecycle("Starting Quoter daemon (Windows - managed process)...")

        val windowsExecutable = toWindowsExecutable(executable)
        logger.debug("Running: ${windowsExecutable.absolutePath} start")

        // Use ProcessBuilder for better control over the child process
        val pb = ProcessBuilder(windowsExecutable.absolutePath, "start")

        // Set environment variables
        pb.environment().putAll(getReleaseEnvironment(releaseTmp, releaseName, startEpmd))

        logger.debug("Environment: ${pb.environment().filterKeys { it.startsWith("RELEASE_") }}")

        var spawned: Process? = null

        try {
            // Start the process
            val process = pb.start()
            spawned = process

            // Consume output streams in background threads to prevent blocking
            // This is critical - if we don't consume the streams, the process will block when buffers fill
            thread(name = "quoter-stdout", isDaemon = true) {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            logger.debug("[QUOTER-OUT] $line")
                        }
                    }
                }
            }

            thread(name = "quoter-stderr", isDaemon = true) {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            logger.debug("[QUOTER-ERR] $line")
                        }
                    }
                }
            }

            // Give the process time to initialize
            Thread.sleep(3000)

            // Check if process crashed immediately
            if (!process.isAlive) {
                val exitCode = process.exitValue()
                logger.error("Process died immediately with exit code: $exitCode")
                throw RuntimeException("Windows quoter process died immediately (exit code $exitCode)")
            }

            logger.lifecycle("Process started successfully")
            return process

        } catch (e: Exception) {
            logger.error("Failed to start Windows daemon: ${e.message}")
            // The caller never receives the handle of a start that fails after spawning, and the bounded
            // start interrupts the sleep above, so nothing else can end this process.
            spawned?.destroyForcibly()
            throw e
        }
    }

    override fun checkStatus(
        execOps: ExecOperations,
        executable: File,
        releaseTmp: File?,
        releaseName: String,
        process: Process?,
        logger: Logger
    ): Pair<Boolean, String> {
        // First, check if the process is still alive (quick failure detection)
        if (process != null && !process.isAlive) {
            logger.debug("Process is not alive")
            return Pair(false, "")
        }

        // Process is alive, but we need to verify the Erlang node is actually responding
        val windowsExecutable = toWindowsExecutable(executable)

        val pidOutput = ByteArrayOutputStream()
        val errorStream = ByteArrayOutputStream()
        val result = execOps.exec {
            commandLine(windowsExecutable.absolutePath, "pid")
            environment(getReleaseEnvironment(releaseTmp, releaseName, startEpmd))
            standardOutput = pidOutput
            errorOutput = errorStream
            isIgnoreExitValue = true
        }

        if (result.exitValue != 0) {
            return Pair(false, "")
        }

        // Clean stdout by removing any lines that appear in stderr
        val cleanedOutput = cleanStdout(pidOutput.toString(), errorStream.toString())

        // The node may print boot warnings to stdout before the PID (e.g. OTP 28 emitting
        // `inet_parse:".../etc/hosts":N: erroneous line, SKIPPED` for a malformed hosts file), so
        // extract the PID as the last purely-numeric line rather than requiring the whole output.
        val pidLine = cleanedOutput.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.matches(Regex("^\\d+$")) }
        if (pidLine != null) {
            logger.debug("Quoter daemon responding with PID: $pidLine")
            return Pair(true, pidLine)
        }

        logger.debug("Invalid PID output after cleaning: '$cleanedOutput'")
        return Pair(false, "")
    }

    override fun readElixirVersion(
        execOps: ExecOperations,
        executable: File,
        releaseTmp: File?,
        releaseName: String,
        process: Process?,
        logger: Logger
    ): Pair<Boolean, String> {
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val result = execOps.exec {
            commandLine(toWindowsExecutable(executable).absolutePath, "rpc", "IO.puts(System.version())")
            environment(getReleaseEnvironment(releaseTmp, releaseName, startEpmd))
            standardOutput = output
            errorOutput = errors
            isIgnoreExitValue = true
        }

        if (result.exitValue != 0) {
            return Pair(false, "exit ${result.exitValue}: ${errors.toString().trim()}")
        }

        val version = cleanStdout(output.toString(), errors.toString()).lines().lastOrNull { it.isNotEmpty() }
        return if (version == null) Pair(false, "no output") else Pair(true, version)
    }

    override fun stopDaemon(
        execOps: ExecOperations,
        executable: File,
        releaseTmp: File?,
        releaseName: String,
        process: Process?,
        logger: Logger
    ) {
        logger.lifecycle("Stopping Quoter daemon (Windows)...")

        val windowsExecutable = toWindowsExecutable(executable)

        try {
            // Try graceful stop via RPC first
            val stopOutput = ByteArrayOutputStream()
            val stopError = ByteArrayOutputStream()
            val result = execOps.exec {
                commandLine(windowsExecutable.absolutePath, "stop")
                environment(getReleaseEnvironment(releaseTmp, releaseName, startEpmd))
                standardOutput = stopOutput
                errorOutput = stopError
                isIgnoreExitValue = true
            }

            logger.debug("Stop command exit code: ${result.exitValue}")

            // Clean stdout by removing stderr pollution
            val cleanedOutput = cleanStdout(stopOutput.toString(), stopError.toString())
            if (cleanedOutput.isNotEmpty()) {
                logger.debug("Stop output: $cleanedOutput")
            }

            if (result.exitValue == 0) {
                logger.lifecycle("Quoter stopped gracefully via RPC")
            }

            // If we have a process reference, ensure it's terminated
            process?.let { proc ->
                if (proc.isAlive) {
                    logger.debug("Process still alive, destroying...")

                    // Try graceful shutdown first
                    proc.destroy()

                    // Wait up to 5 seconds for graceful shutdown
                    val terminated = proc.waitFor(5, TimeUnit.SECONDS)

                    if (!terminated) {
                        logger.lifecycle("Process didn't terminate gracefully, forcing...")
                        proc.destroyForcibly()
                        proc.waitFor(2, TimeUnit.SECONDS)
                    }

                    logger.lifecycle("Process terminated")
                } else {
                    logger.debug("Process already dead")
                }
            }

            logger.lifecycle("Windows daemon stopped")

        } catch (e: Exception) {
            logger.error("Error stopping Windows daemon: ${e.message}")
            // Don't rethrow - best effort cleanup
        }
    }
}
