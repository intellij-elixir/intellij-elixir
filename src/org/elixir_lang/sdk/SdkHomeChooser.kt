package org.elixir_lang.sdk

import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectBundle
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.vfs.VirtualFile
import org.elixir_lang.sdk.wsl.wslCompat
import org.jetbrains.annotations.VisibleForTesting
import java.awt.Component
import java.nio.file.Path
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import com.intellij.openapi.util.io.FileUtil

/**
 * Plugin-side replacement for SdkConfigurationUtil.selectSdkHome.
 *
 * The platform method cannot be used:
 * - The deprecated 2-arg form anchors its execution-environment check at `user.home`, so any
 *   SDK home picked on WSL fails with "Environment Mismatch" and can never be created.
 * - The Path-aware forms are binary-incompatible across supported IDEs: 261 has
 *   selectSdkHome(SdkType, Component?, Path, Consumer); 262.4852.50 inserted Project? before
 *   Consumer (https://youtrack.jetbrains.com/issue/IJPL-236990 /
 *   https://github.com/JetBrains/intellij-community/commit/edf92c9185e1a3e2f28c237c91e6fe493f7f80ac),
 *   so no single call compiles and runs against both.
 *
 * Mirrors the platform implementation minus that environment check: the base path anchors the
 * chooser in the right environment, and validity is enforced by SdkType.isValidSdkHome plus the
 * chooser's own descriptor validation instead.
 */
object SdkHomeChooser {
    /**
     * The base path to anchor the chooser to when the caller has nothing better: [project]'s directory, else the
     * wizard's published target directory, else the user home directory.
     */
    fun defaultBasePath(project: Project? = null): Path =
        SdkDetectionContext.resolve(project) ?: Path.of(System.getProperty("user.home"))

    fun projectOf(component: Component?): Project? =
        component?.let { CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(it)) }

    /** Picks a home, opening at the newest install [sdkModel] has no SDK for yet, and creates an SDK from it. */
    fun createSdk(
        sdkModel: SdkModel,
        sdkType: SdkType,
        basePath: Path,
        preferredOtpMajor: Int? = null,
        onCreated: (Sdk) -> Unit,
    ) {
        val registeredHomes = sdkModel.sdks.filter { it.sdkType == sdkType }.mapNotNull(Sdk::getHomePath)
        selectSdkHome(sdkType, basePath, registeredHomes, preferredOtpMajor) { home ->
            onCreated(SdkConfigurationUtil.createSdk(sdkModel.sdks.toList(), home, sdkType, null, null))
        }
    }

    fun selectSdkHome(
        sdkType: SdkType,
        basePath: Path = defaultBasePath(),
        registeredHomes: Collection<String> = emptyList(),
        preferredOtpMajor: Int? = null,
        onHomeChosen: (String) -> Unit,
    ) {
        val homes = homesNewestFirst(sdkType)
        val suggestedRoot = startFolder(
            sdkType.presentableName,
            basePath,
            { path -> preferringOtp(homes(path), preferredOtpMajor) },
            registeredHomes,
        )

        FileChooser.chooseFiles(chooserDescriptor(sdkType), null, null, suggestedRoot) { chosen ->
            val chosenPath = chosen[0].path
            val adjustedPath = sdkType.adjustSelectedSdkHome(chosenPath)
            val adjustedPathValid = AtomicBoolean(false)
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                { adjustedPathValid.set(sdkType.isValidSdkHome(adjustedPath)) },
                ProjectBundle.message("progress.title.checking.sdk.home"), true, null
            )
            onHomeChosen(if (adjustedPathValid.get()) adjustedPath else chosenPath)
        }
    }

    @VisibleForTesting
    internal fun startFolder(
        presentableName: String,
        basePath: Path,
        homesNewestFirst: (Path) -> List<String>,
        registeredHomes: Collection<String>,
    ): VirtualFile? {
        var home: String? = null
        scanUnderProgress(
            "Looking for installed ${presentableName}s",
            { firstUnregistered(homesNewestFirst(basePath), registeredHomes) },
        ) { awaitScan -> home = awaitScan() }

        return home?.let { wslCompat.findFileByPath(it, refresh = true) }
            ?: wslCompat.findFileByPath(FileUtil.toSystemIndependentName(basePath.toString()), refresh = true)
    }

    /**
     * Runs [scan] on a pooled thread and [body] under modal progress titled [title]; [body] waits for the scan's result
     * through the function it is given. False when cancelled. Scanning a WSL distro goes through its connection, so it
     * is waited for rather than cut off, but on another thread: the scan never checks for cancellation, and an
     * unresponsive distro must not hold the progress open.
     */
    internal fun <T> scanUnderProgress(title: String, scan: () -> T, body: (awaitScan: () -> T) -> Unit): Boolean {
        val future = ApplicationManager.getApplication().executeOnPooledThread<T> { scan() }
        try {
            return ProgressManager.getInstance().runProcessWithProgressSynchronously(
                { body { awaitCancellably(future) } },
                title,
                true,
                null,
            )
        } finally {
            future.cancel(true)
        }
    }

    @VisibleForTesting
    internal fun <T> awaitCancellably(scan: Future<T>): T {
        val indicator = ProgressManager.getInstance().progressIndicator
        while (true) {
            indicator?.checkCanceled()
            try {
                return scan.get(50, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // Still scanning.
            }
        }
    }

    /** Every install the scan finds, not only the newest a type suggests, so one already an SDK can be passed over. */
    private fun homesNewestFirst(sdkType: SdkType): (Path) -> List<String> = when (sdkType) {
        is org.elixir_lang.sdk.erlang.Type -> { path -> org.elixir_lang.sdk.erlang.Type.homePathByVersion(path).values.toList() }
        is org.elixir_lang.sdk.elixir.Type -> { path -> sdkType.homePathByVersion(path).values.toList() }
        else -> { path -> listOfNotNull(sdkType.suggestHomePath(path)) }
    }

    @VisibleForTesting
    internal fun preferringOtp(homesNewestFirst: List<String>, otpMajor: Int?): List<String> {
        if (otpMajor == null) return homesNewestFirst

        // A newer OTP runs an older build, so those follow; one built for a newer OTP may not run at all.
        return homesNewestFirst.sortedBy { home ->
            val buildOtpMajor = OTP_SUFFIX.find(home.substringAfterLast('/').substringAfterLast('\\'))
                ?.groupValues?.get(1)?.toIntOrNull()
            when {
                buildOtpMajor == otpMajor -> 0
                buildOtpMajor == null -> 2
                buildOtpMajor < otpMajor -> 1
                else -> 3
            }
        }
    }

    /** mise's and asdf's name for an Elixir build: `1.20.4-otp-28`. */
    private val OTP_SUFFIX = Regex("""-otp-(\d+)$""")

    @VisibleForTesting
    internal fun firstUnregistered(homesNewestFirst: List<String>, registeredHomes: Collection<String>): String? =
        unregistered(homesNewestFirst, registeredHomes).firstOrNull() ?: homesNewestFirst.firstOrNull()

    /** The [homes] none of [registeredHomes] is. */
    internal fun unregistered(homes: List<String>, registeredHomes: Collection<String>): List<String> =
        homes.filter { home -> registeredHomes.none { wslCompat.pathsEqualWslAware(it, home) } }

    /** The native Windows and macOS dialogs may ignore the start folder. */
    @VisibleForTesting
    internal fun chooserDescriptor(sdkType: SdkType): FileChooserDescriptor =
        sdkType.homeChooserDescriptor.apply { isForcedToUseIdeaFileChooser = true }
}
