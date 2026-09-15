package org.elixir_lang.language_level

/**
 * The Elixir release a file is written for, as the first release of a run the plugin parses, quotes and checks alike.
 *
 * A level exists where an [ElixirLanguageFeature]'s window starts or ends; [of] resolves a version from each level's
 * [firstRelease].
 */
enum class ElixirLanguageLevel(
    /** The first Elixir release at this level, which [of] resolves from. */
    val firstRelease: String,
) {
    /** The floor: nothing resolves below it. */
    V1_11("1.11.0"),
    V1_12("1.12.0"),
    V1_13("1.13.0"),
    V1_14("1.14.0"),
    V1_15("1.15.0"),
    V1_16_0("1.16.0"),
    V1_16_2("1.16.2"),
    V1_17("1.17.0"),
    V1_18("1.18.0"),
    V1_19("1.19.0"),
    V1_20("1.20.0");

    private val release: List<Int> = firstRelease.split('.').map(String::toInt)

    companion object {
        /**
         * The language level to assume when the Elixir version behind an element cannot be determined - no
         * module, no Elixir SDK, or an SDK whose version string carries no version.
         *
         * Deliberately the newest rather than the oldest: most users are on a recent Elixir, so a
         * wrong-but-modern quoted form is the least surprising default. It is also the direction
         * that ages well, since a new level added below shifts the fallback forward with it.
         */
        @JvmStatic
        val FALLBACK: ElixirLanguageLevel = entries.last()

        /** Leading `MAJOR.MINOR[.PATCH]`, wherever it sits in the string. */
        private val VERSION = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""")

        /**
         * The language level for an Elixir version, or [FALLBACK] when [version] carries no version number.
         *
         * [version] may be a bare version (`"1.16.2"`), a mise-style version with a build
         * tag (`"1.13.4-otp-24"`), or a whole SDK version string
         * (`"mise Elixir 1.13.4 (OTP 24)"`). Anything after the version number is ignored, which
         * also means a pre-release resolves as its release.
         */
        @JvmStatic
        fun of(version: String?): ElixirLanguageLevel {
            val match = version?.let { VERSION.find(it) } ?: return FALLBACK
            val (major, minor, patch) = match.destructured
            val numbers = listOf(major.toInt(), minor.toInt(), patch.ifEmpty { "0" }.toInt())

            return entries.lastOrNull { compareReleases(it.release, numbers) <= 0 } ?: entries.first()
        }

        private fun compareReleases(left: List<Int>, right: List<Int>): Int =
            compareValuesBy(left, right, { it[0] }, { it[1] }, { it[2] })
    }
}
