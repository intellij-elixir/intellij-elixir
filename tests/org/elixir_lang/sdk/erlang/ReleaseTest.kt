package org.elixir_lang.sdk.erlang

import org.elixir_lang.PlatformTestCase

class ReleaseTest : PlatformTestCase() {
    fun testOrdering() {
        val ascending = listOf("26.2.5.21", "27.0-rc1", "27.0-rc2", "27.0", "27.0.1", "28.0")

        for ((lower, higher) in ascending.zipWithNext()) {
            assertTrue("$lower must sort before $higher", release(lower) < release(higher))
            assertTrue("$higher must sort after $lower", release(higher) > release(lower))
        }

        assertEquals(ascending, ascending.reversed().map(::release).sorted().map { it.otpVersion })
    }

    fun testReleaseCandidatesCompareNumerically() {
        assertTrue("27.0-rc9 must sort before 27.0-rc10", release("27.0-rc9") < release("27.0-rc10"))
    }

    fun testMissingPartsCompareAsZero() {
        for (equivalent in listOf("27.0.0", "27")) {
            assertEquals("27.0 compared with $equivalent", 0, release("27.0").compareTo(release(equivalent)))
            assertEquals("27.0 equals $equivalent", release("27.0"), release(equivalent))
            assertEquals("27.0 and $equivalent hash alike", release("27.0").hashCode(), release(equivalent).hashCode())
        }
        assertEquals("the version keeps the text it was parsed from", "27.0", release("27.0").otpVersion)
    }

    fun testPatchApplySuffixIsIgnored() {
        val patched = release("26.2.5.1**")

        assertEquals("26.2.5.1", patched.otpVersion)
        assertEquals(release("26.2.5.1"), patched)
        assertEquals(0, patched.compareTo(release("26.2.5.1")))
    }

    fun testSurroundingWhitespaceIsIgnored() {
        assertEquals("28.0.3", release("  28.0.3\n").otpVersion)
    }

    fun testMajors() {
        mapOf(
            "26.2.5.21" to "26",
            "27.0-rc1" to "27",
            "27.0" to "27",
            "27.0.1" to "27",
            "28.0" to "28",
            "26.2.5.1**" to "26",
        ).forEach { (text, major) -> assertEquals("major of $text", major, release(text).otpMajor) }
    }

    fun testUnparseable() {
        listOf("", "   ", "OTP-27", "27.", "27..1", ".27", "27.0-beta1", "27.0-rc", "27.0-rc1.1", "x.1")
            .forEach { assertNull("'$it' is not an OTP version", Release.parse(it)) }
    }

    fun testOfParsesAnOtpVersion() {
        assertEquals(Release.parse("26.2.5.21"), Release.of("26.2.5.21"))
    }

    fun testOfKeepsARewrittenVersionAndTakesItsLeadingNumberAsTheMajor() {
        val rewritten = Release.of("27.3.4-1.fc42")
            ?: throw AssertionError("a packager's rewritten OTP_VERSION must still yield a release")

        assertEquals("the text is kept as written", "27.3.4-1.fc42", rewritten.otpVersion)
        assertEquals("the major is the leading number", "27", rewritten.otpMajor)
    }

    fun testOfOrdersAPackagedVersionByEveryNumberItStatesNotJustItsMajor() {
        // A Debian/Ubuntu OTP_VERSION.
        val packaged = Release.of("25.3.2.7-1") ?: throw AssertionError("a packaged OTP_VERSION must yield a release")

        assertTrue("25.3.2.7-1 is a later 25 than 25.1", packaged > release("25.1"))
        assertEquals("the text is kept as written", "25.3.2.7-1", packaged.otpVersion)
    }

    fun testOfDeclinesANumberTooLargeToBeAnOtpMajor() {
        // A build system writing a timestamp.
        assertNull(Release.of("20260101120000.1"))
    }

    fun testOfDeclinesTextThatStartsWithNoNumber() {
        listOf("", "   ", "OTP-27", "x.1").forEach { assertNull("'$it' starts with no number", Release.of(it)) }
    }

    fun testToString() {
        assertEquals("Erlang/OTP 27.0-rc1", release("27.0-rc1").toString())
    }

    private fun release(text: String): Release =
        Release.parse(text) ?: throw AssertionError("'$text' must parse as an OTP version")
}
