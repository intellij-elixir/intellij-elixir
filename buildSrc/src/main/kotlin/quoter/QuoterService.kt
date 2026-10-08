package quoter

import platform.detectPlatform
import platform.logPlatformDetection
import sdk.isCompatibleVersion
import sdk.readPropertiesFile
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * BuildService that manages the Quoter daemon lifecycle.
 * The daemon is started on first use and automatically stopped when the build ends,
 * regardless of whether the build succeeded or failed.
 *
 * Platform differences:
 * - POSIX: Uses 'daemon' command which detaches automatically
 * - Windows: Uses 'start' command with process management via ProcessBuilder
 *
 * See: https://github.com/gradle/gradle/issues/27707
 */
abstract class QuoterService : BuildService<QuoterService.Params>, AutoCloseable {

    interface Params : BuildServiceParameters {
        /** Path to the quoter executable */
        val executable: RegularFileProperty

        /** Temporary directory for quoter runtime files */
        val tmpDir: DirectoryProperty

        /**
         * Distributed Erlang node name the daemon registers with the machine-wide epmd. Per checkout,
         * so two worktrees can run tests at the same time - see [quoter.DEFAULT_QUOTER_NODE_NAME].
         */
        val nodeName: Property<String>

        /**
         * The Elixir the leg under test expects. The started node must report it, so a node that is not
         * of this leg is never mistaken for one. Optional: unset, the node's version is not checked.
         */
        val elixirVersion: Property<String>

        /**
         * Read for `erlang.sdk.path`, so epmd starts from the SDK rather than the release's bundled
         * ERTS - see [Epmd]. The daemon itself still needs no SDK; this is only about where the
         * machine-wide epmd lives. Optional: unset, and the daemon starts as it always has.
         */
        val sdkProperties: RegularFileProperty
    }

    @get:Inject
    abstract val execOps: ExecOperations

    private val logger = Logging.getLogger(QuoterService::class.java)
    private val platform = detectPlatform()

    /** Lazy: the epmd probe has to run before the platform is configured, and [close] needs the same
     * instance [startDaemon] used. */
    private val quoterPlatform by lazy { createQuoterPlatform(platform, startEpmd = !ensureSdkEpmdRunning()) }

    @Volatile
    private var started = false

    @Volatile
    private var process: Process? = null

    /**
     * Ensures the Quoter daemon is started and ready.
     * Safe to call multiple times - only starts once.
     */
    fun ensureStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            startDaemon()
            started = true
        }
    }

    private fun startDaemon() {
        val executable = parameters.executable.get().asFile
        val releaseTmp = parameters.tmpDir.orNull?.asFile
        val nodeName = parameters.nodeName.getOrElse(DEFAULT_QUOTER_NODE_NAME)
        val maxAttempts = 20

        logPlatformDetection(logger)
        logger.lifecycle("Starting Quoter daemon: ${executable.absolutePath} as $nodeName")

        replaceNodeAnsweringToName(executable, releaseTmp, nodeName)

        // Start the daemon (platform-specific)
        process = quoterPlatform.startDaemon(execOps, executable, releaseTmp, nodeName, logger)

        // Wait for daemon to be ready (both platforms use RPC 'pid' command)
        logger.lifecycle("Waiting for Quoter daemon to be ready...")

        repeat(maxAttempts) { attempt ->
            Thread.sleep(1000)

            val (isRunning, pidOutput) = quoterPlatform.checkStatus(
                execOps, executable, releaseTmp, nodeName, process, logger
            )

            if (isRunning) {
                logger.lifecycle("Quoter daemon is UP! (PID: ${pidOutput.trim()})")
                requireExpectedElixir(executable, releaseTmp, nodeName)
                return
            }

            if (attempt < maxAttempts - 1) {
                logger.lifecycle("Quoter daemon not ready yet (Attempt ${attempt + 1}/$maxAttempts). Retrying...")
            }
        }

        // The daemon was spawned but never became ready. close() only stops the daemon once
        // `started` is true (which we never reach), so stop the spawned process here - otherwise it
        // leaks and holds the distributed node name, making every subsequent start fail with
        // "name ... in use by another Erlang node".
        logger.warn("Quoter daemon did not become ready; stopping the spawned process to avoid a leak.")
        quoterPlatform.stopDaemon(execOps, executable, releaseTmp, nodeName, process, logger)
        process = null

        throw RuntimeException("Quoter daemon failed to start after $maxAttempts attempts.")
    }

    /** A node of another leg's Elixir would answer every quote, with the wrong parser. */
    private fun requireExpectedElixir(executable: File, releaseTmp: File?, nodeName: String) {
        val expected = parameters.elixirVersion.orNull ?: return
        val (read, output) = quoterPlatform.readElixirVersion(execOps, executable, releaseTmp, nodeName, process, logger)

        if (read && isCompatibleVersion(expected, output)) {
            logger.lifecycle("Quoter daemon runs Elixir $output")
            return
        }

        quoterPlatform.stopDaemon(execOps, executable, releaseTmp, nodeName, process, logger)
        process = null

        throw RuntimeException(
            if (read) {
                "Quoter daemon reports Elixir $output, but this leg expects Elixir $expected."
            } else {
                "Could not read the Elixir version of the Quoter daemon, which this leg expects to be $expected: $output"
            }
        )
    }

    /**
     * A second node started under a name that is held exits 0 and dies, leaving the first answering, so
     * whatever answers to [nodeName] before this start is a node from an earlier run - possibly stuck,
     * possibly another leg's Elixir. Halts it and waits until it stops answering.
     */
    private fun replaceNodeAnsweringToName(executable: File, releaseTmp: File?, nodeName: String) {
        val (answering, pid) = quoterPlatform.checkStatus(execOps, executable, releaseTmp, nodeName, null, logger)

        if (!answering) return

        logger.lifecycle("A Quoter node already answers to $nodeName (PID: ${pid.trim()}); replacing it")
        quoterPlatform.stopDaemon(execOps, executable, releaseTmp, nodeName, null, logger)

        repeat(MAX_STOP_POLLS) {
            Thread.sleep(1000)

            if (!quoterPlatform.checkStatus(execOps, executable, releaseTmp, nodeName, null, logger).first) return
        }

        throw RuntimeException(
            "The Quoter node answering to $nodeName (PID: ${pid.trim()}) still answers $MAX_STOP_POLLS seconds " +
                "after being halted; stop it by hand."
        )
    }

    /**
     * Starts epmd from the Erlang SDK so the one owning port 4369 lives outside the checkout, and
     * reports whether it answers. Anything missing or failing returns false, restoring the previous
     * behaviour rather than failing the build.
     */
    private fun ensureSdkEpmdRunning(): Boolean = try {
        val sdkProperties = parameters.sdkProperties.orNull?.asFile?.takeIf { it.isFile }
        val erlangHome = sdkProperties?.let { readPropertiesFile(it)["erlang.sdk.path"] }?.let(::File)
        val epmd = erlangHome?.let(Epmd::find)

        when {
            erlangHome == null -> false
            epmd == null -> {
                logger.info("No epmd under ${erlangHome.absolutePath}; the release will start its own")
                false
            }
            else -> Epmd.ensureRunning(epmd, logger)
        }
    } catch (exception: Exception) {
        // readPropertiesFile throws on a malformed file, and this path is only an optimisation.
        logger.info("Could not resolve an SDK epmd: ${exception.message}")
        false
    }

    private companion object {
        const val MAX_STOP_POLLS = 10
    }

    override fun close() {
        if (!started) return

        val executable = parameters.executable.get().asFile
        val releaseTmp = parameters.tmpDir.orNull?.asFile
        val nodeName = parameters.nodeName.getOrElse(DEFAULT_QUOTER_NODE_NAME)

        logger.lifecycle("Shutting down Quoter daemon...")

        quoterPlatform.stopDaemon(execOps, executable, releaseTmp, nodeName, process, logger)

        logger.lifecycle("Quoter daemon shutdown complete")
    }
}
