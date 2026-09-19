package org.elixir_lang.sdk

import org.elixir_lang.language_level.ElixirLanguageLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushedVersionsTest {
    @Test
    fun `an unknown OTP version writes the Elixir version alone`() {
        assertEquals("1.18.4", PushedVersions.encode(ElixirLanguageLevel.of("1.18.4", null)))
    }

    @Test
    fun `both versions are written either side of the delimiter`() {
        assertEquals("1.18.4|27.3.4", PushedVersions.encode(ElixirLanguageLevel.of("1.18.4", "27.3.4")))
        assertEquals("1.20.0-rc.0|27.0-rc1", PushedVersions.encode(ElixirLanguageLevel.of("1.20.0-rc.0", "27.0-rc1")))
    }

    @Test
    fun `a build tag is not written`() {
        assertEquals("1.13.4|24.3.4.6", PushedVersions.encode(ElixirLanguageLevel.of("1.13.4-otp-24", "24.3.4.6")))
    }

    /** A packager can rewrite `OTP_VERSION`; its leading numbers are what the release is ordered by. */
    @Test
    fun `an OTP version a packager rewrote writes its leading numbers`() {
        assertEquals("1.18.4|25.3.2.7", PushedVersions.encode(ElixirLanguageLevel.of("1.18.4", "25.3.2.7-1")))
    }

    @Test
    fun `what is written reads back as the same level`() {
        for (level in listOf(
            ElixirLanguageLevel.of("1.18.4", null),
            ElixirLanguageLevel.of("1.18.4", "27.3.4"),
            ElixirLanguageLevel.of("1.20.0-rc.0", "27.0-rc1"),
            ElixirLanguageLevel.FALLBACK,
        )) {
            assertEquals("$level", level, PushedVersions.decode(PushedVersions.encode(level)))
        }
    }

    @Test
    fun `a value that is not exactly one or two versions is not read`() {
        for (value in listOf(
            null,
            "",
            "V1_12",
            "1.18",
            "1.18|27.0",
            "1.18.4|",
            "|27.0",
            "1.18.4|27.0|28.0",
            "1.18.4|27.0**",
            "1.18.4 27.0",
            "1.18.4|27.0 ",
            "mise Elixir 1.13.4 (OTP 24)",
        )) {
            assertNull("\"$value\"", PushedVersions.decode(value))
        }
    }
}
