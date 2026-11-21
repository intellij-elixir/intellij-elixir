package org.elixir_lang.sdk.erlang

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import java.io.File
import org.elixir_lang.sdk.SdkHomePaths
import org.elixir_lang.sdk.wsl.wslCompat

/**
 * Reads an Erlang/OTP installation's version from the filesystem - no subprocess required.
 *
 * The canonical source of truth for an installed OTP release is:
 *   `<sdkHome>/releases/<N>/OTP_VERSION`
 * where `<N>` is the OTP major release directory (e.g. `26`). An install prefix such as `/usr` keeps it
 * under `lib/erlang/releases` or, with a multilib `LIBDIR`, `lib64/erlang/releases` instead.
 *
 * Only [org.elixir_lang.sdk.SdkVersionsFiller] calls this; everything else reads what it found from
 * [org.elixir_lang.sdk.SdkVersionsStore], which needs no I/O.
 */
object ErlangVersionDetector {
    private val LOGGER = Logger.getInstance(ErlangVersionDetector::class.java)

    /**
     * Reads the installed Erlang/OTP version from `<N>/OTP_VERSION` in the first of
     * [SdkHomePaths.erlangReleasesDirs] that exists, or `null` if none exists or the `OTP_VERSION` file is absent or
     * unreadable. A releases directory with no `<N>` gives `null`; the next one is not tried.
     *
     * Must NOT be called on the EDT: on a `\\wsl.localhost` home every directory and file access goes through the
     * Plan 9 redirector.
     */
    @RequiresBackgroundThread
    fun detectReleaseAt(canonicalHome: String): Release? {
        ThreadingAssertions.assertBackgroundThread()
        val releasesDir = SdkHomePaths.erlangReleasesDirs(canonicalHome).firstOrNull(wslCompat::exists) ?: run {
            // Also every Elixir home: the version filler reads both kinds from each home.
            LOGGER.debug("Can't detect Erlang version: $canonicalHome has no releases directory")
            return null
        }

        val otpMajorDir = SdkHomePaths.otpReleaseDirs(releasesDir)
            .maxByOrNull { it.name.toIntOrNull() ?: 0 }
            ?: run {
                LOGGER.debug("No numeric subdirectory found in ${releasesDir.path}")
                return null
            }

        val otpVersionFile = File(otpMajorDir, "OTP_VERSION")
        val text = try {
            otpVersionFile.readText()
        } catch (e: Exception) {
            LOGGER.debug("Could not read OTP_VERSION from ${otpVersionFile.path}", e)
            return null
        }

        if (text.isBlank()) {
            // What a read racing the write of an install or upgrade sees; the version-file watch reads it again.
            LOGGER.debug("OTP_VERSION file is empty: ${otpVersionFile.path}")
            return null
        }

        val otpVersion = text.trim().trimEnd('*').trimEnd()
        val otpMajor = otpMajorDir.name
        // A packager's leading numbers are kept when they agree with the directory; when they contradict it, as
        // `8.2 (OTP 27)` under `releases/27` does, the directory wins.
        val release = Release.parse(otpVersion)
            ?: (Release.of(otpVersion)?.takeIf { it.otpMajor == otpMajor } ?: Release.ofOtpMajor(otpMajor, otpVersion))
                ?.also { LOGGER.info("OTP_VERSION file does not hold an OTP version: ${otpVersionFile.path}") }
            ?: return null
        LOGGER.debug("Detected Erlang release: $release (from ${otpVersionFile.path})")
        return release
    }
}
