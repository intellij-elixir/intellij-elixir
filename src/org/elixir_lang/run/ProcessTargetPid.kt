package org.elixir_lang.run

import com.intellij.openapi.diagnostic.Logger
import java.io.File

internal object ProcessTargetPid {
    private val logger = Logger.getInstance(ProcessTargetPid::class.java)
    private val ERL_EXECUTABLES = setOf("erl", "beam.smp")

    fun select(process: Process, exePath: String?): Long? {
        val exeName = exePath?.let { File(it).name.lowercase() }
        if (exeName != null && exeName in ERL_EXECUTABLES) {
            val pid = safePid(process)
            logger.info("Target PID selected (direct): pid=$pid exePath=$exePath")
            return pid
        }

        val handle = runCatching { process.toHandle() }.getOrNull()
        val descendants = runCatching { handle?.descendants()?.toList() }.getOrNull() ?: emptyList()
        for (child in descendants) {
            val command = runCatching { child.info().command().orElse(null) }.getOrNull() ?: continue
            val commandName = File(command).name.lowercase()
            if (commandName in ERL_EXECUTABLES) {
                val pid = child.pid()
                logger.info("Target PID selected (child): pid=$pid command=$command")
                return pid
            }
        }

        val pid = safePid(process)
        logger.info("Target PID fallback to process: pid=$pid exePath=$exePath")
        return pid
    }

    private fun safePid(process: Process): Long? = runCatching { process.pid() }.getOrNull()
}
