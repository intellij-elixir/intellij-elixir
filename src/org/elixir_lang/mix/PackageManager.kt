package org.elixir_lang.mix

import com.intellij.execution.CantRunException
import com.intellij.execution.ExecutionException
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.elixir_lang.Mix
import org.elixir_lang.package_manager.DepGatherer
import org.elixir_lang.package_manager.DepsStatusResult
import org.elixir_lang.run.ElixirCapturingProcessHandler
import org.elixir_lang.sdk.erlang_dependent.ElixirSdkSnapshot

private val LOG = logger<PackageManager>()
private val ANSI_REGEX = Regex("\u001B\\[[;\\d]*m")

private fun String.stripColor(): String = replace(ANSI_REGEX, "")

internal class PackageManager : org.elixir_lang.PackageManager {
    override val fileName: String = org.elixir_lang.mix.Project.MIX_EXS
    override fun depGatherer(): DepGatherer = org.elixir_lang.mix.DepGatherer()

    override fun depGatherer(isDependency: Boolean): DepGatherer =
        DepGatherer(isDependency)

    override fun depsStatus(project: Project, packageVirtualFile: VirtualFile, snapshot: ElixirSdkSnapshot?): DepsStatusResult {
        // The missing SDK is reported where it can be set up, so it is not a failed check too.
        if (snapshot == null) {
            LOG.debug("No Elixir SDK, so not checking the deps of ${packageVirtualFile.path}")
            return DepsStatusResult.Unsupported
        }

        val workingDirectory = packageVirtualFile.parent?.path
            ?: return DepsStatusResult.Error("Missing working directory for ${packageVirtualFile.path}")

        val commandLine = try {
            Mix.commandLine(emptyMap(), workingDirectory, snapshot, project = project)
        } catch (_: CantRunException.CustomProcessedCantRunException) {
            // The missing Erlang SDK is already reported where it can be set up, so it is not a failed check too.
            return DepsStatusResult.Unsupported
        } catch (e: ExecutionException) {
            return DepsStatusResult.Error(e.message ?: "Could not build the mix command line for ${snapshot.elixirSdkName}")
        }
        commandLine.addParameters("deps")

        return try {
            val output = waitForOutput(ElixirCapturingProcessHandler(commandLine), DEPS_TIMEOUT_MS)

            if (output.isTimeout) {
                return DepsStatusResult.Error("mix deps timed out")
            }

            if (output.exitCode != 0) {
                val message = output.stderr.stripColor().trim().ifEmpty {
                    output.stdout.stripColor().trim().ifEmpty { "mix deps failed" }
                }
                return DepsStatusResult.Error(message)
            }

            val status = MixDepsStatusParser.parse(output.stdout)
            DepsStatusResult.Available(status)
        } catch (e: ExecutionException) {
            LOG.warn("mix deps failed for ${packageVirtualFile.path}", e)
            DepsStatusResult.Error(e.message ?: "mix deps failed")
        }
    }

}

private const val DEPS_TIMEOUT_MS = 120_000

/**
 * Runs [handler]'s process to completion, destroying it on timeout or once the calling thread's progress is cancelled -
 * under `coroutineToIndicator`, once the calling coroutine is, such as by its project closing - and then rethrowing
 * that cancellation.
 */
internal fun waitForOutput(handler: CapturingProcessHandler, timeoutMs: Int): ProcessOutput {
    val indicator = ProgressManager.getGlobalProgressIndicator() ?: EmptyProgressIndicator()
    val output = handler.runProcessWithProgressIndicator(indicator, timeoutMs, true)
    if (output.isCancelled) ProgressManager.checkCanceled()

    return output
}
