package org.elixir_lang.language_level

import com.intellij.util.text.SemVer

/**
 * The Elixir release a file is written for, which decides what the plugin parses, quotes and checks.
 *
 * Code asks an [ElixirLanguageFeature] whether a behaviour applies rather than comparing versions, so each boundary is
 * written once, on its feature.
 */
class ElixirLanguageLevel private constructor(val elixir: SemVer) {
    /** [elixir] as it was written, which is what a pusher stores. */
    val elixirVersion: String get() = elixir.rawVersion

    override fun equals(other: Any?): Boolean = other is ElixirLanguageLevel && elixir == other.elixir

    override fun hashCode(): Int = elixir.hashCode()

    override fun toString(): String = "Elixir $elixirVersion"

    companion object {
        /**
         * The language level to assume when the Elixir version behind an element cannot be determined - no module, no
         * Elixir SDK, or no version recorded for it.
         *
         * Deliberately the newest rather than the oldest: most users are on a recent Elixir, so a wrong-but-modern
         * quoted form is the least surprising default. It is the newest boundary any feature has, so it moves forward
         * as features are added.
         */
        @JvmStatic
        val FALLBACK: ElixirLanguageLevel = ElixirLanguageLevel(
            ElixirLanguageFeature.entries.flatMap { listOfNotNull(it.sinceElixir, it.removedInElixir) }.max()
        )

        /**
         * `MAJOR.MINOR[.PATCH][-PRE]`, wherever it sits in the string. A tool manager's `-otp-N` build tag is not a
         * pre-release, which `SemVer` would take it for and sort `1.20.4-otp-29` before `1.20.4`.
         */
        private val VERSION = Regex("""([0-9]+)[.]([0-9]+)(?:[.]([0-9]+))?(?:-(?!otp-)([0-9A-Za-z.]+))?""")

        /** The language level for an Elixir version, or [FALLBACK] when [elixirVersion] carries none. */
        @JvmStatic
        fun of(elixirVersion: String?): ElixirLanguageLevel = parse(elixirVersion) ?: FALLBACK

        /**
         * The language level for an Elixir version, or `null` when [elixirVersion] carries none. [elixirVersion] may be a
         * bare version (`"1.20.0-rc.0"`), one with a build tag (`"1.13.4-otp-24"`), or a whole SDK version string
         * (`"mise Elixir 1.13.4 (OTP 24)"`); a missing patch counts as 0.
         */
        @JvmStatic
        fun parse(elixirVersion: String?): ElixirLanguageLevel? {
            val match = elixirVersion?.let { VERSION.find(it) } ?: return null
            val (major, minor, patch, preRelease) = match.destructured
            val text = "$major.$minor.${patch.ifEmpty { "0" }}" + if (preRelease.isEmpty()) "" else "-$preRelease"

            return SemVer.parseFromText(text)?.let(::ElixirLanguageLevel)
        }
    }
}
