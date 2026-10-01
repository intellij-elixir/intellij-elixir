package org.elixir_lang.sdk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.elixir_lang.sdk.elixir.ElixirBuildInfo
import org.elixir_lang.sdk.elixir.ElixirSdkLookup
import org.elixir_lang.sdk.elixir.ElixirVersionDetector
import org.elixir_lang.sdk.elixir.ElixirVersions
import org.elixir_lang.sdk.elixir.OtpMajor
import org.elixir_lang.sdk.erlang.ErlangVersionDetector
import org.elixir_lang.sdk.erlang.Release
import org.elixir_lang.sdk.erlang_dependent.elixirAdditionalData
import org.elixir_lang.sdk.wsl.wslCompat
import org.jetbrains.annotations.TestOnly
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalTime

private val LOG = logger<SdkVersionsFiller>()

/**
 * The only reader of an installation's version files, writing what they report to [SdkVersionsStore]. Works from a
 * home alone, so it also serves an SDK a settings dialog has not saved. Resolving the home is filesystem I/O, so
 * filling runs off the EDT with no lock held.
 */
internal object SdkVersionsFiller {
    /**
     * @param clearWhenUnreadable forget what was stored when the files report nothing. Only for a change the files
     * themselves signalled: otherwise a transient failure to read a WSL home would erase a good value.
     * @return whether anything changed.
     */
    suspend fun fill(homePath: String, clearWhenUnreadable: Boolean = false): Boolean =
        isInstallationPath(homePath) &&
            fillCanonical(withContext(Dispatchers.IO) { wslCompat.canonicalizePath(homePath) }, homePath, clearWhenUnreadable)

    /** For a caller that has already resolved the home: resolving a WSL home is uncached I/O. */
    suspend fun fillCanonical(
        canonicalHomePath: String,
        homePath: String = canonicalHomePath,
        clearWhenUnreadable: Boolean = false,
    ): Boolean =
        isInstallationPath(canonicalHomePath) &&
            record(withContext(Dispatchers.IO) { detect(canonicalHomePath) }, homePath, clearWhenUnreadable)

    /**
     * An SDK created without a home has `""`, which, like a relative path, resolves against the IDE's working
     * directory. A WSL home is recognised first: in the IDE `File.isAbsolute` goes through its NIO file system, which
     * routes a `\\wsl` path to its distribution.
     */
    private fun isInstallationPath(homePath: String): Boolean =
        homePath.isNotBlank() && (wslCompat.isWslUncPath(homePath) || File(homePath).isAbsolute)

    private fun record(detected: DetectedVersions, homePath: String, clearWhenUnreadable: Boolean): Boolean {
        val store = SdkVersionsStore.getInstance()
        // Each value is merged on its own, including the two inside ElixirVersions: a readable elixir.app does not make
        // an unreadable Elixir.System.beam mean the build reports no OTP major.
        val stored = store.elixirVersions(detected.canonicalHomePath) ?: store.elixirVersions(homePath)
        val storedOtpRelease = store.otpRelease(detected.canonicalHomePath) ?: store.otpRelease(homePath)
        val elixirVersion = detected.elixirVersion ?: keptWhenUnread(clearWhenUnreadable) { stored?.elixirVersion }
        val elixirOtpMajor = mergedOtpMajor(detected.elixirOtpMajor, stored?.elixirOtpMajor, clearWhenUnreadable)
        val elixir = elixirVersion?.let { ElixirVersions(it, elixirOtpMajor) }
        val otpRelease = detected.otpRelease ?: keptWhenUnread(clearWhenUnreadable) { storedOtpRelease }
        if (elixir == null && otpRelease == null) {
            // Recording an empty entry would publish a change that did not happen, and every parsed Elixir file in the
            // affected modules would lose its tree for it.
            if (!clearWhenUnreadable || (stored == null && storedOtpRelease == null)) {
                if (!detected.homeIsGone) SdkVersionWatchService.watchUnanswered(detected.canonicalHomePath, homePath)
                return false
            }

            // A signal from the files is not proof the installation went: a forced refresh of a path the VFS could
            // not stat, such as a home on a stopped distro, reports a deletion.
            if (!detected.homeIsGone) {
                LOG.debug("Kept the versions of '${detected.canonicalHomePath}': it reported nothing but is still there")
                return false
            }

            return store.forgetInstallation(detected.canonicalHomePath, homePath)
        }

        val changed = store.record(detected.canonicalHomePath, homePath, elixir, otpRelease)
        if (changed) LOG.debug("Read the versions of the installation at '${detected.canonicalHomePath}'")

        return changed
    }

    private fun <T> keptWhenUnread(clearWhenUnreadable: Boolean, stored: () -> T?): T? =
        if (clearWhenUnreadable) null else stored()

    /**
     * [OtpMajor.Unread] never overwrites: it is the read failing, not the installation answering. A build reporting no
     * major does not overwrite one already read either, unless the files themselves signalled the change.
     */
    private fun mergedOtpMajor(detected: OtpMajor, stored: OtpMajor?, clearWhenUnreadable: Boolean): OtpMajor =
        when (detected) {
            is OtpMajor.Known -> detected
            OtpMajor.None ->
                if (clearWhenUnreadable || stored == null || stored == OtpMajor.Unread) detected else stored
            OtpMajor.Unread -> stored ?: OtpMajor.Unread
        }

    suspend fun fillUsedBy(project: Project) {
        val homePaths = readAction { homePathsUsedBy(project) }
        if (ApplicationManager.getApplication().isUnitTestMode) lastFillUsedBy = "${LocalTime.now()}: $homePaths"
        for (homePath in homePaths) {
            fillIfUnread(homePath)
        }
    }

    // Exists only to track down a flaky SDK-watch test; remove it if that flake has not shown up in a while.
    @Volatile
    private var lastFillUsedBy = "never"

    /** When [fillUsedBy] last ran and the homes it found, for a test that timed out waiting on a fill. */
    @TestOnly
    fun describeForTests(): String = "last fillUsedBy: $lastFillUsedBy"

    /**
     * For a caller that cannot suspend, such as the platform's pre-scan dumb task, which may run inside a write action
     * (then nothing is read). The model read is a plain one: a non-blocking read never returns while the EDT waits on
     * this thread.
     */
    fun fillUsedByBlocking(project: Project) {
        fillIfUnreadBlocking(ApplicationManager.getApplication().runReadAction(Computable { homePathsUsedBy(project) }))
    }

    /** Skips a home already read: resolving it again is uncached I/O, and a change to its files is the watcher's. */
    suspend fun fillIfUnread(homePath: String) {
        if (!isRead(homePath)) fill(homePath)
    }

    /**
     * For a platform hook that must answer synchronously, such as `SdkType.getVersionString`. Reads nothing while the
     * thread holds a lock, since resolving the home can boot a WSL distro; the caller answers from the store instead.
     * Before 2026.3, modal progress started inside a read action is handed a read permit, so the lock is checked again
     * inside it.
     */
    fun fillIfUnreadBlocking(homePath: String) = fillIfUnreadBlocking(listOf(homePath))

    fun fillIfUnreadBlocking(homePaths: Collection<String>) {
        val unread = homePaths.filter(::isInstallationPath).filterNot(::isRead)
        if (unread.isEmpty()) return
        val app = ApplicationManager.getApplication()
        // `holdsReadLock` is false under a write lock, so that is asked separately.
        if (app.isWriteAccessAllowed || app.holdsReadLock()) return

        if (app.isDispatchThread) {
            runWithModalProgressBlocking(ModalTaskOwner.guess(), "Reading SDK version...") {
                if (!app.holdsReadLock()) unread.forEach { fill(it) }
            }
        } else {
            for (homePath in unread) {
                record(detect(wslCompat.canonicalizePath(homePath)), homePath, clearWhenUnreadable = false)
            }
        }
    }

    /** An [OtpMajor.Unread] major counts as unread, so the beam is read again. */
    private fun isRead(homePath: String): Boolean {
        val store = SdkVersionsStore.getInstance()

        return store.elixirVersions(homePath)?.let { it.elixirOtpMajor != OtpMajor.Unread } == true ||
            store.otpVersion(homePath) != null
    }

    @RequiresReadLock
    fun homePathsUsedBy(project: Project): List<String> {
        ThreadingAssertions.assertReadAccess()

        return ElixirSdkLookup.resolveAll(project)
            .flatMap { elixirSdk -> listOfNotNull(elixirSdk, elixirSdk.elixirAdditionalData?.getErlangSdk()) }
            .mapNotNull(Sdk::getHomePath)
            .distinct()
    }

    /** Kept apart rather than as an [ElixirVersions], so a read that failed for one does not speak for the other. */
    private class DetectedVersions(
        val canonicalHomePath: String,
        val elixirVersion: String?,
        val elixirOtpMajor: OtpMajor,
        val otpRelease: Release?,
        val homeIsGone: Boolean,
    )

    /**
     * Only "no such file" means the home was removed. A stopped `\\wsl.localhost` distro, a permission error or a
     * dropped mount also report nothing while the installation is still there.
     */
    private fun homeIsGone(canonicalHome: String): Boolean =
        wslCompat.isReachable(canonicalHome) && try {
            Files.readAttributes(Paths.get(canonicalHome), BasicFileAttributes::class.java)
            false
        } catch (_: NoSuchFileException) {
            true
        } catch (e: Exception) {
            // `Exception`, not `IOException`: `Paths.get` throws `InvalidPathException` for a home Windows rejects,
            // which canonicalisation passes through lexically.
            LOG.debug("Could not tell whether '$canonicalHome' still exists: ${e.message}")
            false
        }

    /**
     * A missing beam is the build answering, as every Elixir below 1.6 does; any other failure learned nothing.
     * `File.exists` answers `false` for both.
     */
    private fun beamAbsence(canonicalHome: String): OtpMajor =
        if (!wslCompat.isReachable(canonicalHome)) OtpMajor.Unread else try {
            Files.readAttributes(
                Paths.get(canonicalHome, "lib", "elixir", "ebin", "Elixir.System.beam"),
                BasicFileAttributes::class.java,
            )
            OtpMajor.Unread
        } catch (_: NoSuchFileException) {
            OtpMajor.None
        } catch (e: IOException) {
            beamUnreadable(canonicalHome, e)
        } catch (e: InvalidPathException) {
            beamUnreadable(canonicalHome, e)
        }

    private fun beamUnreadable(canonicalHome: String, e: Exception): OtpMajor {
        LOG.debug("Could not tell whether '$canonicalHome' has an Elixir.System.beam: ${e.message}")
        return OtpMajor.Unread
    }

    /** Reads both kinds, because a home alone does not say which it is, and neither read costs anything when absent. */
    @RequiresBackgroundThread
    private fun detect(canonicalHome: String): DetectedVersions {
        ThreadingAssertions.assertBackgroundThread()
        check(!ApplicationManager.getApplication().holdsReadLock()) {
            "Reading an installation's version files must not happen under a read lock"
        }
        val elixirVersion = ElixirVersionDetector.readElixirAppVersion(canonicalHome)

        val elixirOtpMajor = when {
            elixirVersion == null -> OtpMajor.Unread
            else ->
                ElixirBuildInfo.elixirOtpRelease(canonicalHome)?.let(OtpMajor::Known)
                    ?: beamAbsence(canonicalHome)
        }

        val otpRelease = ErlangVersionDetector.detectReleaseAt(canonicalHome)

        return DetectedVersions(
            canonicalHomePath = canonicalHome,
            elixirVersion = elixirVersion,
            elixirOtpMajor = elixirOtpMajor,
            otpRelease = otpRelease,
            // Probed only when nothing read, which is the only case that can clear an entry.
            homeIsGone = elixirVersion == null && otpRelease == null && homeIsGone(canonicalHome),
        )
    }
}
