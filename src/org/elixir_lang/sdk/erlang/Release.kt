package org.elixir_lang.sdk.erlang

/**
 * An Erlang/OTP version as written in `<N>/OTP_VERSION` in the home's releases dir.
 *
 * OTP versions are not SemVer (OTP's `system/doc/system_principles/versions.xml`): a maintenance branch adds
 * parts (`26.2.5.21`), trailing zero parts are omitted (`27.0` is `27.0.0`), a release candidate is `-rcN`, and
 * `otp_patch_apply` appends `**`.
 */
class Release private constructor(
    /** As written, without surrounding whitespace or the `**` suffix, e.g. `"26.2.5.21"`. */
    val otpVersion: String,
    private val parts: List<Int>,
    private val releaseCandidate: Int?,
) : Comparable<Release> {
    val otpMajor: String
        get() = parts.first().toString()

    override fun compareTo(other: Release): Int {
        for (index in 0 until maxOf(parts.size, other.parts.size)) {
            val comparison = parts.getOrElse(index) { 0 }.compareTo(other.parts.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }

        return when {
            releaseCandidate == other.releaseCandidate -> 0
            releaseCandidate == null -> 1
            other.releaseCandidate == null -> -1
            else -> releaseCandidate.compareTo(other.releaseCandidate)
        }
    }

    override fun equals(other: Any?): Boolean = other is Release && compareTo(other) == 0

    override fun hashCode(): Int = 31 * parts.dropLastWhile { it == 0 }.hashCode() + (releaseCandidate ?: -1)

    override fun toString(): String = "Erlang/OTP $otpVersion"

    companion object {
        private val OTP_VERSION = Regex("""(\d+(?:\.\d+)*)(?:-rc(\d+))?""")

        /**
         * A version [parse] does not understand, kept as written and ordered by [otpMajor] alone: a packager can
         * rewrite `OTP_VERSION`, and that must not cost the SDK.
         */
        fun ofOtpMajor(otpMajor: String, otpVersion: String): Release? {
            val major = otpMajor.toIntOrNull() ?: return null

            return Release(otpVersion, listOf(major), null)
        }

        /**
         * Falls back to the dotted numbers [text] starts with when it is not an OTP version, so a packager's
         * `25.3.2.7-1` orders above `25.1` rather than as a bare `25`.
         */
        fun of(text: String): Release? {
            val otpVersion = text.trim().trimEnd('*').trimEnd()
            parse(otpVersion)?.let { return it }
            val leading = LEADING_PARTS.find(otpVersion)?.value ?: return null
            // A number too large to be an OTP part declines, like any other text that is not a version.
            val parts = leading.split('.').map { it.toIntOrNull() ?: return null }

            return Release(otpVersion, parts, null)
        }

        private val LEADING_PARTS = Regex("""^\d+(?:\.\d+)*""")

        /** Returns `null` for anything that is not an OTP version. */
        fun parse(text: String): Release? {
            val otpVersion = text.trim().trimEnd('*').trimEnd()
            val match = OTP_VERSION.matchEntire(otpVersion) ?: return null
            val parts = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
            val releaseCandidate = match.groups[2]?.let { it.value.toIntOrNull() ?: return null }

            return Release(otpVersion, parts, releaseCandidate)
        }
    }
}
