package platform.posix

import quoter.QuoterPlatform
import quoter.getReleaseEnvironment
import org.gradle.api.logging.Logger
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * POSIX implementation of QuoterPlatform.
 * Uses the 'daemon' command which detaches the process automatically.
 *
 * [startEpmd] false when an epmd is already answering - see [quoter.Epmd].
 */
class PosixQuoterPlatform(private val startEpmd: Boolean = true) : QuoterPlatform {

    override fun startDaemon(
        execOps: ExecOperations,
        executable: File,
        releaseTmp: File?,
        releaseName: String,
        logger: Logger
    ): Process? {
        logger.lifecycle("Starting Quoter daemon (POSIX - detached)...")

        val startOutput = ByteArrayOutputStream()
        val result = execOps.exec {
            commandLine(executable.absolutePath, "daemon")
            environment(getReleaseEnvironment(releaseTmp, releaseName, startEpmd))
            standardOutput = startOutput
            errorOutput = startOutput
            isIgnoreExitValue = true
        }

        if (result.exitValue != 0) {
            logger.error("Quoter daemon failed to launch")
            logger.error("Output:\n$startOutput")
            throw RuntimeException("Quoter daemon failed to launch (exit code ${result.exitValue})")
        }

        logger.lifecycle("Daemon start command completed")
        return null // POSIX daemon detaches, no process handle
    }

    override fun checkStatus(
        execOps: ExecOperations,
        executable: File,
        releaseTmp: File?,
        releaseName: String,
        process: Process?,
        logger: Logger
    ): Pair<Boolean, String> {
        val pidOutput = ByteArrayOutputStream()
        val errorStream = ByteArrayOutputStream()
        val result = execOps.exec {
            commandLine(executable.absolutePath, "pid")
            environment(getReleaseEnvironment(releaseTmp, releaseName, startEpmd))
            standardOutput = pidOutput
            errorOutput = errorStream
            isIgnoreExitValue = true
        }

        // Log stderr separately if present
        val stderr = errorStream.toString().trim()
        if (stderr.isNotEmpty()) {
            logger.debug("Quoter stderr: $stderr")
        }

        val output = pidOutput.toString().trim()
        return Pair(result.exitValue == 0, output)
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
            commandLine(executable.absolutePath, "rpc", "IO.puts(System.version())")
            environment(getReleaseEnvironment(releaseTmp, releaseName, startEpmd))
            standardOutput = output
            errorOutput = errors
            isIgnoreExitValue = true
        }

        if (result.exitValue != 0) {
            return Pair(false, "exit ${result.exitValue}: ${errors.toString().trim()}")
        }

        val version = output.toString().lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }
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
        logger.lifecycle("Stopping Quoter daemon (POSIX)...")

        // `quoter stop` is `System.stop()`, an orderly shutdown that ends in a halt which flushes, and a
        // flush waits on outstanding output and on every port: a node whose standard error is stuck never
        // finishes it. The halt below skips the flush, so the rpc never gets a reply and exits nonzero, with
        // a reason that differs by release (`:noconnection`, `:nodedown`).
        val stopOutput = ByteArrayOutputStream()
        val result = execOps.exec {
            commandLine(executable.absolutePath, "rpc", ":erlang.halt(0, flush: false)")
            environment(getReleaseEnvironment(releaseTmp, releaseName, startEpmd))
            standardOutput = stopOutput
            errorOutput = stopOutput
            isIgnoreExitValue = true
        }

        if (result.exitValue != 0) {
            logger.lifecycle("Quoter daemon halted, or was not running (${stopOutput.toString().trim()})")
        } else {
            logger.lifecycle("Quoter daemon answered the halt: ${stopOutput.toString().trim()}")
        }
    }
}
