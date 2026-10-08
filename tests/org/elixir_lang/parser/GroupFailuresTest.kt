package org.elixir_lang.parser

import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiBuilderFactory
import com.intellij.lang.parser.GeneratedParserUtilBase
import org.elixir_lang.ElixirLexer
import org.elixir_lang.ElixirParserDefinition
import org.elixir_lang.parser_definition.ParsingTestCase
import org.elixir_lang.psi.ElixirTypes

/**
 * The memo [ElixirParserUtil] keeps of nesting groups that failed, driven the way the generated parser drives it:
 * `recursion_guard_`, then the group's section, then `exit_section_`.
 */
class GroupFailuresTest : ParsingTestCase() {
    fun testFailuresAreReplayed() {
        val builder = builder()
        fail(builder, LEVEL)

        assertFalse(guard(builder, LEVEL))
    }

    /** Different routes reach one group at different recursion levels, and each would otherwise retry it. */
    fun testFailuresAreReplayedAtAnyLevel() {
        val builder = builder()
        fail(builder, LEVEL)

        assertFalse(guard(builder, LEVEL - 1))
        assertFalse(guard(builder, LEVEL + 1))
    }

    /** A shallower route has more room before the recursion limit, so the group may parse there. */
    fun testFailuresThatReachedTheRecursionLimitAreReplayedOnlyAtTheirLevelOrDeeper() {
        val builder = builder()
        assertTrue(guard(builder, LEVEL))
        assertFalse(ElixirParserUtil.recursion_guard_(builder, PAST_RECURSION_LIMIT, "expression"))
        exitGroup(builder, false)

        assertTrue(guard(builder, LEVEL - 1))
        assertFalse(guard(builder, LEVEL))
        assertFalse(guard(builder, LEVEL + 1))
    }

    /** A group around one whose failure reached the limit is replayed only where that one would be. */
    fun testFailuresAroundAReplayedLimitFailureAreReplayedOnlyAtTheirLevelOrDeeper() {
        val builder = builder("((1))")
        val start = builder.mark()

        // the inner group, reached first by another route, fails for the limit
        builder.advanceLexer()
        assertTrue(guard(builder, LEVEL + 1))
        assertFalse(ElixirParserUtil.recursion_guard_(builder, PAST_RECURSION_LIMIT, "expression"))
        exitGroup(builder, false)
        start.rollbackTo()

        // the outer group fails only because the inner one is replayed
        val outer = builder.mark()
        assertTrue(guard(builder, LEVEL))
        builder.advanceLexer()
        assertFalse(guard(builder, LEVEL + 1))
        exitGroup(builder, false)
        outer.rollbackTo()

        assertTrue(guard(builder, LEVEL - 1))
    }

    /** A branch that is tried and abandoned reaches the limit at a token or two while the accepted parse does not. */
    fun testReachingTheRecursionLimitAtFewTokensAbortsNothing() {
        val builder = builder(statementListAndTokens())
        assertTrue(ElixirParserUtil.recursion_guard_(builder, STATEMENT_LIST_LEVEL, "expressionList"))
        reachRecursionLimitAt(builder, ElixirParserUtil.LIMIT_TOKENS_BEFORE_ABORT - 1)

        assertTrue(ElixirParserUtil.recursion_guard_(builder, STATEMENT_LIST_LEVEL + 1, "expression"))
    }

    /** Statements that each reach the limit at a token of their own are no more nested past it for being many. */
    fun testReachingTheRecursionLimitIsCountedPerStatement() {
        val builder = builder(statementListAndTokens())
        assertTrue(ElixirParserUtil.recursion_guard_(builder, STATEMENT_LIST_LEVEL, "expressionList"))
        val start = builder.mark()

        repeat(ElixirParserUtil.LIMIT_TOKENS_BEFORE_ABORT) {
            assertTrue(ElixirParserUtil.recursion_guard_(builder, STATEMENT_LIST_LEVEL + 1, "expression"))
            assertFalse(ElixirParserUtil.recursion_guard_(builder, PAST_RECURSION_LIMIT, "expression"))
            builder.advanceLexer()
        }

        assertTrue(ElixirParserUtil.recursion_guard_(builder, STATEMENT_LIST_LEVEL + 1, "expression"))
        start.rollbackTo()
    }

    /** The file's statement list finishes, keeping the statements before the one nested past the limit. */
    fun testRulesBelowTheStatementListFailOnceTheRecursionLimitIsReachedAtManyTokens() {
        val builder = builder(statementListAndTokens())
        assertTrue(ElixirParserUtil.recursion_guard_(builder, STATEMENT_LIST_LEVEL, "expressionList"))
        reachRecursionLimitAt(builder, ElixirParserUtil.LIMIT_TOKENS_BEFORE_ABORT)

        assertTrue(ElixirParserUtil.recursion_guard_(builder, STATEMENT_LIST_LEVEL, "endOfExpressionMaybe"))
        assertFalse(ElixirParserUtil.recursion_guard_(builder, STATEMENT_LIST_LEVEL + 1, "expression"))
    }

    fun testFailuresBelongToTheirOwnParse() {
        val first = builder()
        val second = builder()
        fail(first, LEVEL)

        assertTrue(guard(second, LEVEL))
        exitGroup(second, true)
        assertFalse(guard(first, LEVEL))
        assertTrue(guard(second, LEVEL))
    }

    fun testRecursionLimitsBelongToTheirOwnParse() {
        val first = builder(statementListAndTokens())
        val second = builder(statementListAndTokens())
        assertTrue(ElixirParserUtil.recursion_guard_(first, STATEMENT_LIST_LEVEL, "expressionList"))
        reachRecursionLimitAt(first, ElixirParserUtil.LIMIT_TOKENS_BEFORE_ABORT)

        assertTrue(ElixirParserUtil.recursion_guard_(second, STATEMENT_LIST_LEVEL, "expressionList"))
        assertTrue(ElixirParserUtil.recursion_guard_(second, STATEMENT_LIST_LEVEL + 1, "expression"))
        assertFalse(ElixirParserUtil.recursion_guard_(first, STATEMENT_LIST_LEVEL + 1, "expression"))
    }

    fun testSuccessesAreNotCounted() {
        val builder = builder()
        exit(builder, LEVEL, true)

        assertTrue(guard(builder, LEVEL))
    }

    private fun builder(text: String = "(1)"): PsiBuilder =
        ElixirParserUtil.adapt_builder_(
            ElixirTypes.PARENTHETICAL_STAB,
            PsiBuilderFactory.getInstance().createBuilder(ElixirParserDefinition(), ElixirLexer(), text),
            ElixirParser()
        )

    private fun statementListAndTokens(): String = "a ".repeat(ElixirParserUtil.LIMIT_TOKENS_BEFORE_ABORT + 1)

    /** Reaches the limit at each of the next [tokens] tokens, then returns to where it started. */
    private fun reachRecursionLimitAt(builder: PsiBuilder, tokens: Int) {
        val start = builder.mark()

        repeat(tokens) {
            assertFalse(ElixirParserUtil.recursion_guard_(builder, PAST_RECURSION_LIMIT, "expression"))
            builder.advanceLexer()
        }

        start.rollbackTo()
    }

    private fun guard(builder: PsiBuilder, level: Int): Boolean =
        ElixirParserUtil.recursion_guard_(builder, level, "parentheticalStab")

    private fun fail(builder: PsiBuilder, level: Int) = exit(builder, level, false)

    /** Nothing is consumed, so every attempt starts at the same `(`. */
    private fun exit(builder: PsiBuilder, level: Int, result: Boolean) {
        assertTrue(guard(builder, level))
        exitGroup(builder, result)
    }

    private fun exitGroup(builder: PsiBuilder, result: Boolean) {
        ElixirParserUtil.exit_section_(
            builder,
            GeneratedParserUtilBase.enter_section_(builder),
            ElixirTypes.PARENTHETICAL_STAB,
            result
        )
    }

    private companion object {
        const val LEVEL = 10
        const val STATEMENT_LIST_LEVEL = 4

        /** Past GrammarKit's default limit of 1000 and any plausible `grammar.kit.gpub.max.level`. */
        const val PAST_RECURSION_LIMIT = 1_000_000
    }
}
