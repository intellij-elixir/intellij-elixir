package org.elixir_lang.annotator

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver
import org.elixir_lang.language_level.elixir

/** Expected messages were taken from `Code.string_to_quoted/1` on every release from 1.11.4 to 1.20.4. */
class UnclosedInterpolationTest : BasePlatformTestCase() {
    private var files = 0

    override fun tearDown() {
        try {
            ElixirLanguageLevelResolver.overrideLanguageLevel(project, null)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun testOwners() {
        for ((source, owner) in listOf(
            "$Q#{" to "string",
            "'#{" to "string",
            "~s(#{" to "sigil ~s(",
            "~c$Q#{" to "sigil ~c$Q",
            "~r/#{" to "sigil ~r/",
            ":$Q#{" to "atom",
            "${Q}a#{b" to "string",
            "$Q#{1}#{" to "string",
            "$Q#{${NL}foo" to "string",
            "$Q#{1 +" to "string",
        )) {
            for (languageLevel in listOf(elixir("1.11.0"), elixir("1.12.0"), elixir("1.20.0"))) {
                assertEquals(
                    "$source on $languageLevel",
                    listOf(Triple(source.lastIndexOf("#{"), "#{", interpolation(owner, 1))),
                    errors(languageLevel, source).filter { it.third.startsWith(INTERPOLATION) }
                )
            }
        }
    }

    /** Elixir reads an interpolation's content before looking for its `}`, so the innermost one without it fails first. */
    fun testNestedReportsTheInnermost() {
        val source = "$Q#{$Q#{"

        for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
            assertEquals(listOf(Triple(4, "#{", interpolation("string", 1))), errors(languageLevel, source))
        }
    }

    fun testClosedInterpolations() {
        for (source in listOf("$Q#{1}$Q", "$D$NL#{1}$NL$D", "~s(#{1})", "$Q#{$Q#{1}$Q}$Q")) {
            for (languageLevel in listOf(elixir("1.11.0"), elixir("1.20.0"))) {
                assertEquals(
                    "$source on $languageLevel",
                    emptyList<Triple<Int, String, String>>(),
                    errors(languageLevel, source)
                )
            }
        }
    }

    /** Before 1.12 a heredoc without a terminator line fails as a whole before its interpolations are read. */
    fun testHeredocOwner() {
        for ((source, opening, line) in listOf(
            Triple("$D$NL#{$NL", 0, 1),
            Triple("~s$D$NL#{$NL", 2, 1),
            Triple("$S$NL#{$NL", 0, 1),
            Triple("$D${NL}a #{b$NL", 0, 1),
            Triple("x = 1$NL$D$NL#{$NL", 6, 2),
        )) {
            val terminator = source.substring(opening, opening + 3)

            assertEquals(
                "$source on 1.11.0",
                listOf(Triple(opening, source.substring(opening), terminator(terminator, "heredoc", line))),
                errors(elixir("1.11.0"), source)
            )

            for (languageLevel in listOf(elixir("1.12.0"), elixir("1.20.0"))) {
                assertEquals(
                    "$source on $languageLevel",
                    listOf(Triple(source.indexOf("#{"), "#{", interpolation("heredoc", line))),
                    errors(languageLevel, source)
                )
            }
        }
    }

    /**
     * Before 1.12 Elixir ends an enclosing heredoc at its terminator line before it reads the heredoc's interpolations, so the
     * first quote or interpolation that runs past that line fails, and nothing after it. From 1.12 an interpolation without its
     * `}` fails only when nothing inside it fails first.
     */
    fun testCutByTheEnclosingHeredocsTerminatorLine() {
        for ((source, beforeTwelve, fromTwelve) in listOf(
            Triple(
                "$D$NL#{'$NL$D$NL'${NL}foo",
                listOf(Triple("'", 1, terminator("'", "string", 2))),
                listOf(Triple("#{", 1, interpolation("heredoc", 1)))
            ),
            Triple(
                "$S$NL#{~s($NL$S$NL)${NL}foo",
                listOf(Triple("~", 1, terminator(")", "sigil ~s(", 2))),
                listOf(Triple("#{", 1, interpolation("heredoc", 1)))
            ),
            Triple(
                "$S$NL#{:$Q$NL$S$NL$Q${NL}foo",
                listOf(Triple(":", 1, terminator(Q, "atom", 2))),
                listOf(Triple("#{", 1, interpolation("heredoc", 1)))
            ),
            Triple(
                "$D$NL#{$NL$S$NL$D$NL$S",
                listOf(Triple(S, 1, terminator(S, "heredoc", 3))),
                listOf(Triple("#{", 1, interpolation("heredoc", 1)))
            ),
            Triple("$S$NL#{$Q$NL$S$NL$Q}$NL$S", listOf(Triple(Q, 1, terminator(Q, "string", 2))), emptyList()),
            Triple("~s$S$NL#{$Q$NL$S$NL$Q}$NL$S", listOf(Triple(Q, 1, terminator(Q, "string", 2))), emptyList()),
            Triple(
                "$S$NL#{${Q}a${NL}b$Q$NL$S$NL}",
                listOf(Triple("#{", 1, interpolation("heredoc", 1))),
                listOf(Triple(S, 2, terminator(S, "heredoc", 4)))
            ),
            Triple("$S$NL#{$D$NL$S$NL$D}$NL$S", listOf(Triple(D, 1, terminator(D, "heredoc", 2))), emptyList()),
            Triple(
                "$S$NL#{$NL$D$NL$S$NL$D",
                listOf(Triple(D, 1, terminator(D, "heredoc", 3))),
                listOf(Triple("#{", 1, interpolation("heredoc", 1)))
            ),
            Triple(
                "$D$NL#{$NL$S$NL#{$NL$D${NL}foo$NL$S",
                listOf(Triple(S, 1, terminator(S, "heredoc", 3))),
                listOf(Triple(D, 2, terminator(D, "heredoc", 5)))
            ),
            Triple(
                "$S$NL#{$Q$NL$S$NL$Q$NL$D${NL}foo",
                listOf(Triple(Q, 1, terminator(Q, "string", 2))),
                listOf(Triple(D, 1, terminator(D, "heredoc", 5)))
            ),
            Triple(
                "$D$NL#{$Q#{$NL$D${NL}foo",
                listOf(Triple("#{", 2, interpolation("string", 2))),
                listOf(Triple(D, 2, terminator(D, "heredoc", 3)))
            ),
            Triple(
                "$S$NL#{$Q#{$NL$D${NL}foo$NL$S",
                listOf(Triple(D, 1, terminator(D, "heredoc", 3))),
                listOf(Triple(D, 1, terminator(D, "heredoc", 3)))
            ),
            Triple(
                "$D$NL#{${NL}foo$NL$D",
                listOf(Triple("#{", 1, interpolation("heredoc", 1))),
                listOf(Triple(D, 2, ONLY_WHITESPACE + D))
            ),
            Triple("$S$NL#{${Q}a$Q}$NL$S", emptyList(), emptyList()),
        )) {
            assertEquals(
                "$source on 1.11.0",
                beforeTwelve.map { at(source, it) },
                errors(elixir("1.11.0"), source).map { it.first to it.third }
            )
            assertEquals(
                "$source on 1.20.0",
                fromTwelve.map { at(source, it) },
                errors(elixir("1.20.0"), source).map { it.first to it.third }
            )
        }
    }

    /** The offset of the [Triple.second] occurrence of [Triple.first], a lone quote never matching inside a heredoc's three. */
    private fun at(source: String, expected: Triple<String, Int, String>): Pair<Int, String> {
        val (token, occurrence, message) = expected
        val pattern = if (token.length == 1 && token in QUOTES) "(?<!$token)$token(?!$token)" else Regex.escape(token)

        return Regex(pattern).findAll(source).elementAt(occurrence - 1).range.first to message
    }

    private fun errors(languageLevel: ElixirLanguageLevel, source: String): List<Triple<Int, String, String>> {
        ElixirLanguageLevelResolver.overrideLanguageLevel(project, languageLevel)
        myFixture.configureByText("interpolation_${files++}.ex", source)

        return myFixture
            .doHighlighting(HighlightSeverity.ERROR)
            .mapNotNull { info ->
                info.description
                    ?.takeIf { message -> PREFIXES.any { message.startsWith(it) } }
                    ?.let { Triple(info.startOffset, source.substring(info.startOffset, info.endOffset), it) }
            }
            .sortedBy { it.first }
    }

    private fun interpolation(owner: String, line: Int): String = "$INTERPOLATION: $Q}$Q (for $owner starting at line $line)"

    private fun terminator(terminator: String, owner: String, line: Int): String =
        "missing terminator: $terminator (for $owner starting at line $line)"

    private companion object {
        const val Q = "\""
        const val S = "'''"
        const val D = "\"\"\""
        const val NL = "\n"
        const val INTERPOLATION = "missing interpolation terminator"
        const val ONLY_WHITESPACE = "heredoc allows only whitespace characters followed by a new line after opening "
        val QUOTES = setOf("'", Q)
        val PREFIXES = listOf("missing terminator", INTERPOLATION, "heredoc allows only", "invalid location for heredoc terminator")
    }
}
