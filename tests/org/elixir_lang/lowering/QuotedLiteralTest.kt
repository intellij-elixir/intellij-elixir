package org.elixir_lang.lowering

import com.ericsson.otp.erlang.OtpErlangPid
import com.intellij.openapi.util.TextRange
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.ElixirTuple

class QuotedLiteralTest : LoweringTestCase() {
    fun testEachCaseAtTheNewestLevel() = assertEachAnswers(QUOTED_LITERAL_CASES) { isQuotedLiteral(it, NEWEST) }

    fun testModuleIsALiteralFrom1_14_1() = assertAnswers("__MODULE__", "1.14.0" to false, "1.14.1" to true)

    fun testAnAliasAfterModuleIsALiteralFrom1_14_1() = assertAnswers("__MODULE__.A", "1.14.0" to false, "1.14.1" to true)

    fun testABitstringIsALiteralFrom1_15_0_rc_0() =
        assertAnswers("<<1, \"a\">>", "1.14.5" to false, "1.15.0-rc.0" to true)

    fun testAnEmptyBitstringIsALiteralFrom1_15_0_rc_0() = assertAnswers("<<>>", "1.14.5" to false, "1.15.0-rc.0" to true)

    fun testAPairCalledAsATupleIsALiteral() =
        assertTrue(QuotedLiteral.of(ElixirAst.tupleCall(meta(), listOf(atom(), atom())), ElixirLanguageLevel.FALLBACK))

    fun testAValueIsNot() =
        assertFalse(QuotedLiteral.of(ElixirAst.Value(meta(), OtpErlangPid("node@host", 1, 2, 3)), ElixirLanguageLevel.FALLBACK))

    fun testAPlaceholderIsNot() =
        assertFalse(
            QuotedLiteral.of(
                ElixirAst.Placeholder(meta(), ElixirAst.Placeholder.Reason.Unlowered(ElixirTuple::class.java)),
                ElixirLanguageLevel.FALLBACK
            )
        )

    private fun assertAnswers(text: String, vararg expected: Pair<String, Boolean>) =
        assertEachAnswers(expected.toList()) { isQuotedLiteral(text, it) }

    private fun isQuotedLiteral(text: String, version: String): Boolean =
        QuotedLiteral.of(lower(text, version), ElixirLanguageLevel.of(version))

    private fun meta() = Meta(TextRange.EMPTY_RANGE, Meta.Position(0, 0), Meta.Position(0, 0))

    private fun atom() = ElixirAst.Literal.Atom(meta(), "a")
}
