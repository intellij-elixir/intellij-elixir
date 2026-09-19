package org.elixir_lang.language_level

import com.intellij.util.text.SemVer
import org.elixir_lang.sdk.erlang.Release

/**
 * The Elixir release a file is written for, and the Erlang/OTP release running it, which together decide what the
 * plugin parses, quotes and checks.
 */
class ElixirLanguageLevel private constructor(val elixir: SemVer, val otp: Release?) {
    /** [elixir] as `MAJOR.MINOR.PATCH[-PRE]`. */
    val elixirVersion: String get() = elixir.rawVersion

    override fun equals(other: Any?): Boolean =
        other is ElixirLanguageLevel && elixir == other.elixir && otp == other.otp

    override fun hashCode(): Int = 31 * elixir.hashCode() + otp.hashCode()

    override fun toString(): String = "Elixir $elixirVersion" + (otp?.let { " on $it" } ?: "")

    companion object {
        /**
         * The language level to assume when the Elixir version behind an element cannot be determined - no module, no
         * Elixir SDK, or no version recorded for it. The newest rather than the oldest, as most users are on a recent
         * Elixir; its OTP is unknown, which every OTP window admits.
         */
        @JvmStatic
        val FALLBACK: ElixirLanguageLevel = ElixirLanguageLevel(
            ElixirLanguageFeature.entries.flatMap { listOfNotNull(it.sinceElixir, it.removedInElixir) }.max(),
            null,
        )

        /**
         * `MAJOR.MINOR[.PATCH][-PRE]`, wherever it sits in the string. A tool manager's `-otp-N` build tag is not a
         * pre-release, which `SemVer` would take it for and sort `1.20.4-otp-29` before `1.20.4`.
         */
        private val VERSION = Regex("""([0-9]+)[.]([0-9]+)(?:[.]([0-9]+))?(?:-(?!otp-)([0-9A-Za-z.]+))?""")

        /** The language level for an Elixir version, or [FALLBACK] when [elixirVersion] carries none. */
        @JvmStatic
        @JvmOverloads
        fun of(elixirVersion: String?, otpVersion: String? = null): ElixirLanguageLevel =
            parse(elixirVersion, otpVersion) ?: FALLBACK

        /**
         * The language level for an Elixir version, or `null` when [elixirVersion] carries none. [elixirVersion] may be
         * a bare version (`"1.20.0-rc.0"`), one with a build tag (`"1.13.4-otp-24"`), or a whole SDK version string
         * (`"mise Elixir 1.13.4 (OTP 24)"`); a missing patch counts as 0. An [otpVersion] that is not one leaves the
         * OTP unknown.
         */
        @JvmStatic
        @JvmOverloads
        fun parse(elixirVersion: String?, otpVersion: String? = null): ElixirLanguageLevel? {
            val match = elixirVersion?.let { VERSION.find(it) } ?: return null
            val (major, minor, patch, preRelease) = match.destructured
            val text = "$major.$minor.${patch.ifEmpty { "0" }}" + if (preRelease.isEmpty()) "" else "-$preRelease"
            val elixir = SemVer.parseFromText(text) ?: return null

            return ElixirLanguageLevel(elixir, otpVersion?.let { Release.parse(it) ?: Release.of(it) })
        }
    }
}
