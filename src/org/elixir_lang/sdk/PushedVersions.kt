package org.elixir_lang.sdk

import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.vfs.newvfs.FileAttribute
import com.intellij.psi.FilePropertyKey
import com.intellij.psi.FilePropertyKeyImpl
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.sdk.elixir.ElixirSdkLookup
import org.elixir_lang.sdk.elixir.knownOrNull
import org.elixir_lang.sdk.elixir.sdk
import org.elixir_lang.sdk.erlang_dependent.elixirAdditionalData

/**
 * The Elixir and OTP versions [SdkVersionsPusher] puts on a directory, as one value: `1.18.4|27.3.4`, or `1.18.4` when
 * the OTP version is unknown. Neither half's form can hold the delimiter, and a value off those forms is not read.
 */
internal object PushedVersions {
    @Suppress("UnstableApiUsage")
    val KEY: FilePropertyKey<String> = FilePropertyKeyImpl.createPersistentStringKey("elixir.sdk.versions", attribute())

    /**
     * A `FileAttribute` id may be registered once per JVM, and loading the plugin again without a restart runs this
     * again, so the attribute is kept where it outlives the plugin's class loader.
     */
    internal fun attribute(): FileAttribute =
        System.getProperties().computeIfAbsent(ATTRIBUTE_ID) { FileAttribute(ATTRIBUTE_ID, 1, true) } as FileAttribute

    private const val ATTRIBUTE_ID = "elixir_sdk_versions"
    private const val DELIMITER = '|'
    private val ELIXIR = Regex("""[0-9]+[.][0-9]+[.][0-9]+(-[0-9A-Za-z.]+)?""")
    private val OTP = Regex("""[0-9]+([.][0-9]+)*(-rc[0-9]+)?""")

    /** The leading numbers of an `OTP_VERSION` a packager rewrote, which is what `Release.of` orders it by. */
    private val OTP_LEADING = Regex("""^[0-9]+([.][0-9]+)*""")

    fun encode(languageLevel: ElixirLanguageLevel): String {
        val elixir = languageLevel.elixirVersion
        check(ELIXIR.matches(elixir)) { "not an Elixir version: $elixir" }
        val otp = languageLevel.otp?.otpVersion?.let { if (OTP.matches(it)) it else OTP_LEADING.find(it)?.value }

        return if (otp == null) elixir else "$elixir$DELIMITER$otp"
    }

    fun decode(value: String?): ElixirLanguageLevel? {
        val parts = value?.split(DELIMITER) ?: return null
        if (parts.size > 2 || !ELIXIR.matches(parts[0])) return null
        val otp = parts.getOrNull(1)
        if (otp != null && !OTP.matches(otp)) return null

        return ElixirLanguageLevel.parse(parts[0], otp)
    }

    /** `null` until the store has read what [languageLevelOf] needs, which is not the same as having no SDK. */
    @RequiresReadLock
    fun versionsOf(module: Module): String? {
        val sdk = ElixirSdkLookup.resolve(module).sdk ?: return encode(ElixirLanguageLevel.FALLBACK)

        return languageLevelOf(sdk)?.let(::encode)
    }

    /** [versionsOf] with the OTP major the Elixir build targeted standing in for a paired Erlang SDK not read yet. */
    @RequiresReadLock
    fun unpairedVersionsOf(module: Module): String? =
        ElixirSdkLookup.resolve(module).sdk?.let(::unpairedLanguageLevelOf)?.let(::encode)

    /**
     * The Elixir version of [sdk]'s home and the OTP version of the Erlang SDK it is paired with, or `null` until the
     * store has read the Elixir home and any paired Erlang home. The OTP running Elixir is what counts, so without a
     * pairing the OTP major the Elixir build targeted stands in, and without that the OTP is unknown.
     *
     * The pairing is read as recorded rather than resolved, since resolving it can schedule a repair.
     */
    fun languageLevelOf(sdk: Sdk): ElixirLanguageLevel? {
        val erlangHome = sdk.elixirAdditionalData?.getErlangSdkHomePath() ?: return unpairedLanguageLevelOf(sdk)
        val store = SdkVersionsStore.getInstance()
        val elixirVersions = store.elixirVersions(sdk.homePath) ?: return null
        val otpVersion = store.otpRelease(erlangHome)?.otpVersion ?: return null

        return ElixirLanguageLevel.of(elixirVersions.elixirVersion, otpVersion)
    }

    /** [languageLevelOf] with the OTP major the Elixir build targeted standing in for any paired Erlang SDK. */
    fun unpairedLanguageLevelOf(sdk: Sdk): ElixirLanguageLevel? =
        SdkVersionsStore.getInstance().elixirVersions(sdk.homePath)?.let {
            ElixirLanguageLevel.of(it.elixirVersion, it.elixirOtpMajor.knownOrNull)
        }
}
