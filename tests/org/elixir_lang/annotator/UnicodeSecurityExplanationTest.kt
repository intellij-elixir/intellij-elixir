package org.elixir_lang.annotator

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.text.StringUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.ElixirFileType
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir

/**
 * The hover text follows the rest of Elixir's error, measured by running `Code.string_to_quoted/1` on 1.14, 1.17, 1.18
 * and 1.20. Elixir's confusable hints, such as `admin` for a Cyrillic letter a, need Unicode data the plugin does not ship.
 */
class UnicodeSecurityExplanationTest : BasePlatformTestCase() {
    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testMixedScriptTooltipListsEachCharactersScripts() {
        for (languageLevel in listOf(elixir("1.17.0"), elixir("1.20.0"))) {
            val tooltip = tooltip(languageLevel, "\u0430dmin = 1")

            assertContainsText(
                tooltip,
                languageLevel,
                "invalid mixed-script identifier found: \u0430dmin (U+0430 \u0430 is Cyrillic; the rest is Latin)",
                "Mixed-script identifiers are not supported for security reasons. '\u0430dmin' is made of the following scripts:",
                "U+0430 \u0430 Cyrillic",
                "U+0064 d Latin",
                "U+006D m Latin",
                "U+0069 i Latin",
                "U+006E n Latin",
            )
            assertLinks(tooltip, "c3-mixed-script-detection")
        }
    }

    fun testMixedScriptTooltipGivesTheGuidanceOfTheRelease() {
        assertContainsText(
            tooltip(elixir("1.17.0"), "\u0430dmin = 1"),
            elixir("1.17.0"),
            "All characters in the identifier should resolve to a single script, or use a highly restrictive set of scripts."
        )
        assertContainsText(
            tooltip(elixir("1.18.0"), "\u0430dmin = 1"),
            elixir("1.18.0"),
            "Characters in identifiers from different scripts must be separated by underscore (_)."
        )
    }

    fun testMixedScriptTooltipNamesEveryScriptOfACharacter() {
        assertContainsText(
            tooltip(elixir("1.20.0"), "[\u0422\u30B7\u30E3\u30C4: 1]"),
            elixir("1.20.0"),
            "U+0422 \u0422 Cyrillic",
            "U+30B7 \u30B7 Japanese, Katakana",
        )
    }

    fun testRestrictedCharacterTooltipHintsTheCompatibleForm() {
        for (languageLevel in listOf(elixir("1.14.0"), elixir("1.20.0"))) {
            for ((source, got, hint) in listOf(
                Triple(
                    "foo\uD835\uDECD = 1",
                    "\"foo\uD835\uDECD\" (code points 0x00066 0x0006F 0x0006F 0x1D6CD)",
                    "\"foo\u03BC\" (code points 0x00066 0x0006F 0x0006F 0x003BC)"
                ),
                Triple(
                    "fo\uFF4F = 1",
                    "\"fo\uFF4F\" (code points 0x00066 0x0006F 0x0FF4F)",
                    "\"foo\" (code points 0x00066 0x0006F 0x0006F)"
                ),
            )) {
                val tooltip = tooltip(languageLevel, source)

                assertContainsText(
                    tooltip,
                    languageLevel,
                    "Elixir expects unquoted Unicode atoms, variables, and calls to use allowed codepoints and to be in NFC form.",
                    "Got: $got",
                    "Hint: You could write the above in a compatible format that is accepted by Elixir: $hint",
                )
                assertLinks(tooltip, "r4-equivalent-normalized-identifiers")
            }
        }
    }

    /**
     * Elixir gives no hint for the first three: two reject a first character, and the compatible form of U+3164 is not
     * allowed either. For the last two it hints from its confusable data (`foOM`) or, from 1.18, from the rejected code
     * point alone, which the plugin does not.
     */
    fun testRestrictedCharacterTooltipHasNoHintWithoutACompatibleForm() {
        for ((source, message) in listOf(
            "\u3164 = 1" to "unexpected token: \"\u3164\" (code point U+3164)",
            "_shib\u3164 = 1" to "unexpected token: \"\u3164\" (code point U+3164)",
            "\uFF46\uFF4F\uFF4F = 1" to "unexpected token: \"\uFF46\" (code point U+FF46)",
            "foO\uD835\uDEB3" to "unexpected token: \"\uD835\uDEB3\" (code point U+1D6B3)",
            "foo\uFF71 = 1" to "unexpected token: \"\uFF71\" (code point U+FF71)",
        )) {
            val tooltip = tooltip(elixir("1.20.0"), source)

            assertContainsText(
                tooltip,
                elixir("1.20.0"),
                message,
                "Elixir does not allow this code point in unquoted atoms, variables, and calls: Unicode's identifier rules exclude it, or its security profile restricts it.",
            )
            assertFalse("hint in ${escaped(source)}", StringUtil.removeHtmlTags(tooltip).contains("Hint:"))
            assertLinks(tooltip, "r1-default-identifiers", "c1-general-security-profile-for-identifiers")
        }
    }

    fun testRestrictedCharacterTooltipShowsTheIdentifierAsElixirTokenizedIt() {
        assertContainsText(
            tooltip(elixir("1.20.0"), "a\u00B5\uD835\uDECD = 1"),
            elixir("1.20.0"),
            "Got: \"a\u03BC\uD835\uDECD\" (code points 0x00061 0x003BC 0x1D6CD)",
            "Hint: You could write the above in a compatible format that is accepted by Elixir: \"a\u03BC\u03BC\" (code points 0x00061 0x003BC 0x003BC)",
        )
    }

    fun testRestrictedCharacterHintFollowsTheRelease() {
        assertContainsText(
            tooltip(elixir("1.17.0"), "foo\uFF71 = 1"),
            elixir("1.17.0"),
            "Hint: You could write the above in a compatible format that is accepted by Elixir: \"foo\u30A2\" (code points 0x00066 0x0006F 0x0006F 0x030A2)",
        )
    }

    private fun tooltip(languageLevel: ElixirLanguageLevel, source: String): String {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, languageLevel)
        myFixture.configureByText(ElixirFileType.INSTANCE, source)

        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)
        assertEquals("errors in ${escaped(source)} on $languageLevel: ${errors.map { it.description }}", 1, errors.size)

        return errors.single().toolTip!!
    }

    private fun assertContainsText(tooltip: String, languageLevel: ElixirLanguageLevel, vararg expected: String) {
        val text = StringUtil.unescapeXmlEntities(StringUtil.removeHtmlTags(tooltip))
            .replace(Regex("&#x([0-9a-fA-F]+);")) { String(Character.toChars(it.groupValues[1].toInt(16))) }
            .replace(Regex("&#([0-9]+);")) { String(Character.toChars(it.groupValues[1].toInt())) }

        for (part in expected) {
            assertTrue("tooltip on $languageLevel lacks <$part>:\n$text", text.contains(part))
        }
    }

    private fun assertLinks(tooltip: String, vararg anchors: String) {
        for (anchor in anchors) {
            assertTrue(
                "no link to #$anchor in $tooltip",
                tooltip.contains("href=\"https://hexdocs.pm/elixir/unicode-syntax.html#$anchor\"")
            )
        }
    }
}
