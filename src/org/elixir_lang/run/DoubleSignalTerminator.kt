package org.elixir_lang.run

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.diagnostic.traceThrowable
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * Handles graceful termination of Elixir/Erlang processes using double SIGINT.
 *
 * The Erlang VM (BEAM) treats the first SIGINT (Ctrl-C) as entering BREAK mode, displaying
 * a menu with options to halt the VM, and requires a second SIGINT to actually exit.
 * This interface implements that behavior for clean process shutdown.
 *
 * ## Why Double SIGINT?
 *
 * Elixir/Erlang processes need graceful termination to:
 * - Flush buffered log output
 * - Complete in-flight operations
 * - Clean up resources (file handles, network connections)
 * - Trigger supervision tree shutdowns
 *
 * A single SIGKILL or hard kill can leave resources in inconsistent states.
 *
 * ## Implementation Strategy
 *
 * Implementations should attempt termination in this order:
 * 1. WSL/IJent-specific interrupt (for remote processes)
 * 2. PID-based SIGINT via kill command
 * 3. Caller-provided fallback (typically `super.destroyProcessImpl()`)
 *
 * @see ElixirDoubleSignalTerminator
 */
interface DoubleSignalTerminator {
    /**
     * Performs double-SIGINT termination on the given process.
     *
     * Sends two SIGINT signals with a brief delay (typically 200ms) between them to allow
     * the BEAM BREAK menu to appear before the final exit.
     *
     * @param process The process to terminate gracefully
     * @param fallback Called if double-SIGINT fails or is unavailable. Typically delegates
     *                 to the parent class's destroyProcessImpl() for platform-specific
     *                 termination (e.g., Process.destroy() or SIGKILL)
     */
    fun performDoubleSignalTermination(process: Process, fallback: () -> Unit)
}

class ElixirDoubleSignalTerminator(
    private val pidProvider: (() -> Long?)? = null
) : DoubleSignalTerminator {
    private val logger = Logger.getInstance(ElixirDoubleSignalTerminator::class.java)
    private var doubleSigintInProgress = false

    override fun performDoubleSignalTermination(process: Process, fallback: () -> Unit) {
        logger.trace { "performDoubleSignalTermination called, doubleSigintInProgress=$doubleSigintInProgress" }

        // Prevent infinite recursion
        if (doubleSigintInProgress) {
            logger.warn("Already in double-SIGINT mode, calling fallback")
            fallback()
            return
        }

        try {
            doubleSigintInProgress = true

            if (!process.isAlive) {
                logger.trace { "Process already terminated" }
                return
            }

            logger.trace { "Process is alive: ${process.isAlive}, attempting first SIGINT" }

            // Send first SIGINT
            val firstSentNs = System.nanoTime()
            val firstResult = sendSignal(process, fallback)
            logger.trace { "First SIGINT result: $firstResult" }

            // Wait briefly for the BREAK menu to appear and give process a chance to exit.
            // Using waitFor() instead of Thread.sleep() allows early return if process exits quickly.
            logger.info("Waiting up to 200ms for process to respond to first SIGINT...")
            val terminatedEarly = process.waitFor(200, TimeUnit.MILLISECONDS)

            // Check if process is still alive
            if (terminatedEarly || !process.isAlive) {
                logger.trace { "Process terminated after first SIGINT" }
                return
            }

            // Send second SIGINT
            logger.trace { "Process still alive after first SIGINT, sending second SIGINT" }
            val secondResult = sendSignal(process, fallback)
            logger.trace { "Second SIGINT result: $secondResult" }
            logExit(process, firstSentNs)

        } catch (e: Exception) {
            logger.error("Exception during double-SIGINT termination: ${e.message}", e)
            fallback()
        } finally {
            doubleSigintInProgress = false
        }
    }

    // Only while debugging: a process that never exits holds the thread `onExit` waits on.
    private fun logExit(process: Process, firstSentNs: Long) {
        if (!logger.isTraceEnabled) return
        val exited = process.onExit()
        exited.thenAccept {
            logger.trace { "Process exited with code ${it.exitValue()} ${elapsedMs(firstSentNs)}ms after the first SIGINT" }
        }
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { if (!exited.isDone) logger.trace("Process still running ${elapsedMs(firstSentNs)}ms after the first SIGINT") },
            STILL_RUNNING_AFTER_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun elapsedMs(sinceNs: Long): Long = (System.nanoTime() - sinceNs) / 1_000_000

    /**
     * Sends a single SIGINT signal to the process using the most appropriate method.
     *
     * Attempts termination strategies in order of preference:
     * 1. **IJent/WSL interrupt**: For processes running in WSL2 or remote environments via IntelliJ's
     *    IJent (IntelliJ Agent) framework. Uses EelProcess.interrupt() which properly handles
     *    cross-environment signaling.
     * 2. **PID-based kill**: For native processes where we can access the Unix PID via reflection.
     *    Directly sends SIGINT (signal 2) using the `kill` command.
     * 3. **Fallback strategy**: Uses the caller-provided fallback, typically the parent class's
     *    destroyProcessImpl() which may use Process.destroy() or platform-specific termination.
     *
     * @param process The process to send a signal to
     * @param fallback Termination strategy to use if IJent and PID-based approaches fail
     * @return true if signal was sent successfully, false otherwise
     */
    private fun sendSignal(process: Process, fallback: () -> Unit): Boolean {
        // Try IJent-aware approach first (WSL2/remote processes)
        if (tryIjentInterrupt(process)) {
            return true
        }

        // Try PID-based kill command (native Unix processes)
        if (tryPidKill(process)) {
            return true
        }

        // Use caller's fallback (typically super.destroyProcessImpl() or process.destroy())
        return try {
            logger.trace { "Using provided fallback termination strategy" }
            fallback()
            true
        } catch (e: Exception) {
            logger.error("Fallback termination failed: ${e.message}", e)
            false
        }
    }

    // EelProcess interrupt is experimental; required for IJent/WSL interrupt with no stable alternative.
    @Suppress("UnstableApiUsage")
    private fun tryIjentInterrupt(process: Process): Boolean {
        try {
            // Check if this is an IJent wrapper process
            if (!process.javaClass.name.contains("IjentChildProcessAdapter")) {
                return false
            }

            logger.trace { ("Detected IjentChildProcessAdapter, using EelProcess.interrupt()") }

            // Access the internal ijentChildProcess field (EelProcess)
            val ijentChildProcessField = process.javaClass.getDeclaredField("ijentChildProcess")
            ijentChildProcessField.isAccessible = true
            val ijentChildProcess = ijentChildProcessField.get(process)

            if (ijentChildProcess != null) {
                logger.trace { ("Got ijentChildProcess, calling interrupt()") }

                // Cast to EelProcess and call interrupt()
                val eelProcess = ijentChildProcess as? com.intellij.platform.eel.EelProcess
                if (eelProcess != null) {
                    runBlocking {
                        try {
                            eelProcess.interrupt()
                            logger.trace { ("interrupt() completed successfully") }
                        } catch (e: Exception) {
                            logger.error("interrupt() failed: ${e.message}", e)
                            throw e
                        }
                    }
                    return true
                } else {
                    logger.trace { "Could not cast to EelProcess" }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to call EelProcess.interrupt(): ${e.message}", e)
        }
        return false
    }

    private fun tryPidKill(process: Process): Boolean {
        // Windows has no `kill`: Git's cannot signal a native process, and without it on the PATH `exec` throws. A
        // `KillableProcessHandler`'s fallback stops a Windows BEAM gracefully; any other handler's kills it.
        if (SystemInfo.isWindows) return false
        try {
            val providedPid = pidProvider?.invoke()
            if (providedPid != null) {
                logger.trace { "Using provided PID $providedPid" }
                return sendSigint(providedPid)
            }

            // Try to get the PID using reflection
            logger.trace { "Getting PID via reflection from ${process.javaClass.name}" }
            val pidField = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            val pid = pidField.get(process)
            logger.trace { ("PID value: $pid (type: ${pid?.javaClass?.name})") }

            val pidLong = when (pid) {
                is Long -> pid
                is Int -> pid.toLong()
                else -> {
                    logger.warn("Unexpected PID type: ${pid?.javaClass?.name}")
                    return false
                }
            }

            return sendSigint(pidLong)
        } catch (e: NoSuchFieldException) {
            logger.traceThrowable { Throwable("Could not find 'pid' field in ${process.javaClass.name}", e) }
            return false
        } catch (e: Exception) {
            logger.error("Exception in tryPidKill: ${e.message}", e)
            return false
        }
    }

    private fun sendSigint(pidLong: Long): Boolean {
        logger.trace { ("Sending SIGINT (signal 2) to PID $pidLong") }

        // Send signal using kill command
        val killProcess = Runtime.getRuntime().exec(arrayOf("kill", "-2", pidLong.toString()))
        val exitCode = killProcess.waitFor()

        logger.trace { "kill command exit code: $exitCode" }
        return exitCode == 0
    }

    private companion object {
        const val STILL_RUNNING_AFTER_MS = 5_000L
    }
}
