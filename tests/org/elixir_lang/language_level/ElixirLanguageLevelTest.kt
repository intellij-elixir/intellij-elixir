package org.elixir_lang.language_level

import com.intellij.util.text.SemVer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElixirLanguageLevelTest {
    @Test
    fun `a bare version is read as it is`() {
        assertEquals(SemVer.parseFromText("1.16.2"), ElixirLanguageLevel.of("1.16.2").elixir)
    }

    @Test
    fun `a pre-release comes before its release`() {
        val preRelease = ElixirLanguageLevel.of("1.20.0-rc.0").elixir

        assertEquals(SemVer.parseFromText("1.20.0-rc.0"), preRelease)
        assertTrue(preRelease < ElixirLanguageLevel.of("1.20.0").elixir)
        assertTrue(preRelease > ElixirLanguageLevel.of("1.19.5").elixir)
    }

    /** `SemVer` would read an OTP build tag as a pre-release and sort `1.20.4-otp-29` before `1.20.4`. */
    @Test
    fun `an OTP build tag is not a pre-release`() {
        assertEquals(ElixirLanguageLevel.of("1.13.4"), ElixirLanguageLevel.of("1.13.4-otp-24"))
        assertEquals(ElixirLanguageLevel.of("1.20.4"), ElixirLanguageLevel.of("1.20.4-otp-29"))
    }

    @Test
    fun `an absent patch counts as zero`() {
        assertEquals(ElixirLanguageLevel.of("1.16.0"), ElixirLanguageLevel.of("1.16"))
    }

    /** Minor is compared numerically, so 1.9 must not sort above 1.15 the way strings would. */
    @Test
    fun `version parts are compared as numbers`() {
        assertTrue(ElixirLanguageLevel.of("1.9.4").elixir < ElixirLanguageLevel.of("1.15.0").elixir)
    }

    @Test
    fun `a whole SDK version string resolves on its embedded version`() {
        assertEquals(ElixirLanguageLevel.of("1.13.4"), ElixirLanguageLevel.of("mise Elixir 1.13.4 (OTP 24)"))
        assertEquals(
            ElixirLanguageLevel.of("1.13.4"),
            ElixirLanguageLevel.of("mise Elixir 1.13.4-otp-24 (Erlang 24.3.4.6)")
        )
    }

    @Test
    fun `a version-less string falls back`() {
        assertEquals(ElixirLanguageLevel.FALLBACK, ElixirLanguageLevel.of(null))
        assertEquals(ElixirLanguageLevel.FALLBACK, ElixirLanguageLevel.of(""))
        assertEquals(ElixirLanguageLevel.FALLBACK, ElixirLanguageLevel.of("Elixir at /opt/elixir"))
    }

    /**
     * The fallback is deliberately the newest behaviour, not the oldest: flipping it would silently change what every
     * module-less file quotes as.
     */
    @Test
    fun `the fallback has every feature Elixir still has`() {
        for (feature in ElixirLanguageFeature.entries) {
            assertEquals(
                feature.name,
                feature.removedInElixir == null,
                feature.isSufficient(ElixirLanguageLevel.FALLBACK)
            )
        }
    }

    @Test
    fun `versions after the newest boundary have every feature Elixir still has`() {
        val future = ElixirLanguageLevel.of("2.0.0")

        for (feature in ElixirLanguageFeature.entries) {
            assertEquals(feature.name, feature.removedInElixir == null, feature.isSufficient(future))
        }
        assertFalse(ElixirLanguageFeature.STEP_OPERATOR.isSufficient(ElixirLanguageLevel.of("1.9.4")))
    }
}
