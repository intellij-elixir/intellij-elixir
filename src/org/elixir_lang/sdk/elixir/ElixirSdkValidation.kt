package org.elixir_lang.sdk.elixir

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.sdk.erlang_dependent.elixirAdditionalData
import java.util.concurrent.Callable
import org.elixir_lang.sdk.wsl.wslCompat

/** An Elixir build against an Erlang install's OTP release: [Unknown] when either side's major isn't known. */
sealed interface OtpMatch {
    data object Match : OtpMatch

    /** The OTP major the build was compiled for, and the Erlang's. */
    data class Mismatch(val elixirOtpMajor: String, val erlangOtpMajor: String) : OtpMatch {
        /** The warning the SDK page and the Erlang step show. */
        val warning: String
            get() = "Elixir SDK was compiled for OTP $elixirOtpMajor but is paired with OTP $erlangOtpMajor"
    }

    data object Unknown : OtpMatch
}

/**
 * Validation and health-check queries for Elixir SDK pairings.
 *
 * Houses logic that answers "is this SDK pairing healthy?" - distinct from path population
 * ([ElixirSdkPathConfigurator], [ElixirErlangClasspath]) and version detection
 * ([ElixirVersionDetector], [ElixirBuildInfo]).
 */
object ElixirSdkValidation {

    // -------------------------------------------------------------------------
    // OTP mismatch detection
    // -------------------------------------------------------------------------

    /**
     * Detects whether the OTP major the Elixir SDK was compiled against differs from the OTP
     * major of its currently paired Erlang IDE SDK.
     *
     * Returns `(elixirOtpMajor, erlangOtpMajor)` when they differ, `null` when they match,
     * when either side is not known (e.g. an Elixir built before the beam carried a major, a home not
     * read yet, a missing Erlang SDK), or when the user has suppressed this warning for the SDK.
     *
     * Must be called on a background thread, for the read action that resolves the paired Erlang SDK.
     */
    @RequiresBackgroundThread
    fun detectOtpMismatch(sdk: Sdk): Pair<String, String>? {
        ThreadingAssertions.assertBackgroundThread()
        return ReadAction.nonBlocking(Callable { otpMismatchOf(sdk) }).executeSynchronously()
    }

    @RequiresReadLock
    private fun otpMismatchOf(sdk: Sdk): Pair<String, String>? {
        ThreadingAssertions.assertReadAccess()
        val additionalData = sdk.elixirAdditionalData ?: return null
        if (additionalData.isSuppressOtpMismatchWarning()) return null
        val erlangSdk = additionalData.getErlangSdk() ?: return null

        return detectOtpMismatch(sdk.homePath ?: return null, erlangSdk.homePath ?: return null)
    }

    /**
     * For homes read on the EDT from a settings dialog's editable copies, which `SdkEditor` commits to inside a write
     * action. Reads only [SdkVersionsStore].
     */
    fun detectOtpMismatch(elixirHome: String, erlangHome: String): Pair<String, String>? =
        (otpMatch(elixirHome, erlangHome) as? OtpMatch.Mismatch)?.let { it.elixirOtpMajor to it.erlangOtpMajor }

    /** As [detectOtpMismatch] for two homes, telling a match from a side not known. */
    fun otpMatch(elixirHome: String, erlangHome: String): OtpMatch {
        val store = SdkVersionsStore.getInstance()
        val elixirOtpMajor = store.elixirVersions(elixirHome)?.elixirOtpMajor?.knownOrNull ?: return OtpMatch.Unknown
        val erlangOtpMajor = store.otpRelease(erlangHome)?.otpMajor ?: return OtpMatch.Unknown

        return if (elixirOtpMajor == erlangOtpMajor) OtpMatch.Match else OtpMatch.Mismatch(elixirOtpMajor, erlangOtpMajor)
    }

    // -------------------------------------------------------------------------
    // Erlang classpath presence checks
    // -------------------------------------------------------------------------

    /**
     * Returns `true` when the Elixir SDK's class roots contain at least one entry from the
     * Erlang SDK's home directory.
     *
     * Can be `false` when the JetBrains settings persistence does not save the SDK configuration
     * correctly, or when the Erlang SDK was changed after the classpath was last populated.
     */
    fun hasErlangClasspathInElixirSdk(elixirSdk: Sdk, erlangSdk: Sdk): Boolean {
        val classRoots = elixirSdk.rootProvider.getFiles(OrderRootType.CLASSES)
        return hasErlangClasspathInRoots(classRoots, erlangSdk)
    }

    /**
     * Returns `true` when [classRoots] contains at least one file rooted under the Erlang
     * SDK's home directory.
     *
     * Used by [hasErlangClasspathInElixirSdk] and by
     * [org.elixir_lang.sdk.erlang_dependent.AdditionalDataConfigurable] where the class roots
     * come from the UI-side [com.intellij.openapi.projectRoots.SdkModificator] rather than
     * the persisted SDK.
     */
    fun hasErlangClasspathInRoots(classRoots: Array<VirtualFile>, erlangSdk: Sdk): Boolean {
        val erlangHomePath = erlangSdk.homePath ?: return false
        // No refresh: a root under the home puts the home in the VFS already, and a refresh fires its events in a write
        // action, which throws inside the read action the settings page resets in.
        val erlangHomePathVf = wslCompat.findFileByPath(FileUtil.toSystemIndependentName(erlangHomePath)) ?: return false
        return classRoots.any { root -> VfsUtilCore.isAncestor(erlangHomePathVf, root, true) }
    }
}
