package org.elixir_lang.sdk.wsl

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WslDistributionManager
import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import java.util.concurrent.CancellationException

/**
 * Default implementation of WslCompatService using the IntelliJ Platform WSL API.
 * Delegates to native IntelliJ APIs where possible to avoid redundancy.
 */
internal class WslCompatServiceImpl : WslCompatService {
    override val log = Logger.getInstance(WslCompatServiceImpl::class.java)

    override fun isWslUncPath(path: String?): Boolean =
        !path.isNullOrEmpty() && try {
            // Delegate to native IntelliJ API
            WslPath.isWslUncPath(path)
        } catch (e: Exception) {
            log.debug("Error checking if path is WSL: $path", e)
            false
        }

    override fun getDistributionByWindowsUncPath(path: String?): WSLDistribution? {
        if (path.isNullOrEmpty() || ! isWslUncPath(path)) {
            return null
        }

        return try {
            // Delegate to native IntelliJ API
            WslPath.getDistributionByWindowsUncPath(path)
        } catch (e: Exception) {
            log.debug("Error getting WSL distribution for path: $path", e)
            null
        }
    }

    override fun parseWindowsUncPath(windowsUncPath: String?): String? {
        if (windowsUncPath.isNullOrEmpty()) {
            return null
        }

        return try {
            // Delegate to native IntelliJ API, this just returns null if
            // running on a non-Windows system.
            val wslPath = WslPath.parseWindowsUncPath(windowsUncPath)
            wslPath?.linuxPath
        } catch (e: Exception) {
            log.debug("Error converting Windows UNC path to Linux path: $windowsUncPath", e)
            null
        }
    }

    /**
     * Never runs `wsl.exe` on the EDT or under any lock: there only a list the platform already holds counts.
     * `isReadAccessAllowed` is also true under a write or write-intent lock, which `holdsReadLock` is not.
     */
    override fun knownInstalledDistributions(): List<WSLDistribution>? = try {
        val manager = WslDistributionManager.getInstance()
        val application = ApplicationManager.getApplication()
        val distributions = if (application.isDispatchThread || application.isReadAccessAllowed) {
            manager.cachedInstalledDistributions ?: manager.lastInstalledDistributions
        } else {
            manager.installedDistributions
        }
        distributions?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        log.debug("Error getting WSL distributions", e)
        null
    }

    override fun getInstalledDistributions(): List<WSLDistribution> {
        return try {
            WslDistributionManager.getInstance().installedDistributions
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.debug("Error getting WSL distributions", e)
            emptyList()
        }
    }

    override fun getWslUserHome(distribution: WSLDistribution): String? {
        return try {
            // Waiting on getUserHome() needs a job or an indicator, and the home scan's thread has neither.
            runBlockingMaybeCancellable { distribution.userHome }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log.debug("Error getting WSL user home for distribution: ${distribution.msId}", e)
            null
        }
    }

    override fun convertLinuxPathToWindowsUnc(distribution: WSLDistribution, linuxPath: String?): String? {
        if (linuxPath.isNullOrEmpty()) {
            return null
        }

        // canonicalizePath handles an unresolvable path on its own; keep it outside this try or
        // a catch(Exception) here would also swallow its read-lock assertion.
        val converted = try {
            val wslPath = WslPath(distribution.msId, linuxPath)
            wslPath.toWindowsUncPath()
        } catch (e: Exception) {
            log.debug("Error converting Linux path to Windows UNC: $linuxPath", e)
            return null
        }
        return canonicalizePath(converted)
    }

    override fun convertSingleWslPath(windowsPath: String, distribution: WSLDistribution): String? {
        return try {
            // Normalize to backslashes for Path.of() on Windows
            val normalizedPath = windowsPath.replace('/', '\\')
            distribution.getWslPath(java.nio.file.Path.of(normalizedPath))
        } catch (e: Exception) {
            log.debug("Failed to convert path using getWslPath: $windowsPath", e)
            null
        }
    }
}
