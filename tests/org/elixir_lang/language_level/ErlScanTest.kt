package org.elixir_lang.language_level

import org.elixir_lang.junit.LightTestCase

/** Each release pairs a CI leg's Elixir with its OTP. */
class ErlScanTest : LightTestCase() {
    fun testMaybeExprsKeywordsAreReservedFromOtp27() {
        for (word in listOf("maybe", "else")) {
            assertFalse("$word on OTP 26", erlScanReservedWord(word, ElixirLanguageLevel.of("1.16.3", "26.2.5.21")))
            assertTrue("$word on OTP 27", erlScanReservedWord(word, ElixirLanguageLevel.of("1.17.3", "27.1.2")))
        }
    }

    fun testFixedReservedWordsAreReservedOnEveryOtp() {
        val releases = listOf(
            "1.11.4" to "24.3.4.6",
            "1.13.4" to "25.3.2.21",
            "1.16.3" to "26.2.5.21",
            "1.18.4" to "27.3.4",
            "1.19.5" to "28.4",
            "1.20.4" to "29.0.6",
        )

        for ((elixir, otp) in releases) {
            for (word in listOf("after", "end")) {
                assertTrue("$word on OTP $otp", erlScanReservedWord(word, ElixirLanguageLevel.of(elixir, otp)))
            }
        }
    }
}
