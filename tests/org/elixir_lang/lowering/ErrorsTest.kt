package org.elixir_lang.lowering

/** Broken code lowers, each error to a placeholder that quotes as `__cursor__`. */
class ErrorsTest : LoweringTestCase() {
    fun testAnErrorElementIsAnErrorPlaceholder() {
        val lowered = lower("[1,")

        assertEquals(ERROR, placeholderReasons(lowered))
        assertFalse(lowered.hasUnlowered())
    }

    fun testAnErrorInsideAnotherShapeIsAPlaceholderWhereTheErrorIs() {
        val lowered = lower("@")

        assertEquals(ERROR, placeholderReasons(lowered))
        assertEquals(
            "{:@, [line: 1, column: 1], [{:__cursor__, [line: 1, column: 2], []}]}",
            inspect(lowered.toOtp(COLUMNS_AND_TOKEN_METADATA))
        )
    }

    fun testAnUnfinishedClauseIsAnErrorPlaceholder() = assertEquals(ERROR, placeholderReasons(lower("fn ->")))

    fun testFnWithoutAClauseIsAnErrorPlaceholder() {
        val lowered = lower("fn 1 end")

        assertEquals(ERROR, placeholderReasons(lowered))
        assertEquals("{:__cursor__, [line: 1, column: 1], []}", inspect(lowered.toOtp(COLUMNS_AND_TOKEN_METADATA)))
    }

    fun testAnOperatorMissingAnOperandIsBroken() {
        assertBroken("1 +")
        assertBroken("1 in")
        assertBroken("1 not in")
        assertBroken("1..2//")
        assertBroken("x = !")
    }

    fun testAnUnterminatedHeredocIsBroken() {
        assertBroken("x = \"\"\"\nabc")
        assertBroken("x = ~S\"\"\"\nabc")
    }

    fun testAnOperandTheParserRollsBackIsBroken() = assertBroken("a + %Foo")

    fun testAnInterpolatedRemoteNameIsBroken() {
        for (version in listOf(OLDEST, NEWEST)) {
            assertBroken("Foo.\"a#{b}\"()", version)
            assertBroken("Foo.\"a#{b}\"[0]", version)
            assertBroken("Foo.\"a#{b}\"", version)
            assertBroken("Foo.\"a#{b}\" 1", version)
        }
    }

    fun testAStringEscapingPastTheLastCodePointIsBroken() = assertBroken("\"\\u{110000}\"")

    fun testACharListEscapingPastTheLastCodePointIsBroken() = assertBroken("'\\u{110000}'")

    fun testAnAtomEscapingPastTheLastCodePointIsBroken() = assertBroken(":\"\\u{110000}\"")

    fun testARemoteNameEscapingPastTheLastCodePointIsBroken() = assertBroken("a.'\\u{110000}'")

    fun testAStringEscapingASurrogateIsBroken() = assertBroken("\"\\u{D800}\"")

    fun testAnAtomEscapingASurrogateIsBroken() = assertBroken(":\"\\u{D800}\"")

    fun testARemoteNameEscapingASurrogateIsBroken() = assertBroken("a.\"\\u{DFFF}\"")

    fun testAnAtomTooLongForAnAtomIsBroken() = assertBroken(":$TOO_LONG")

    fun testAQuotedAtomTooLongForAnAtomIsBroken() = assertBroken(":\"$TOO_LONG\"")

    fun testAKeywordKeyTooLongForAnAtomIsBroken() = assertBroken("f($TOO_LONG: 1)")

    fun testAQuotedKeywordKeyTooLongForAnAtomIsBroken() = assertBroken("f(\"$TOO_LONG\": 1)")

    fun testACallNameTooLongForAnAtomIsBroken() = assertBroken("$TOO_LONG(1)")

    fun testAVariableTooLongForAnAtomIsBroken() = assertBroken("{$TOO_LONG, 1}")

    fun testADefinitionNameTooLongForAnAtomIsBroken() = assertBroken("def $TOO_LONG, do: 1")

    fun testARemoteNameTooLongForAnAtomIsBroken() = assertBroken("x.$TOO_LONG()")

    fun testAQuotedRemoteNameTooLongForAnAtomIsBroken() {
        for (version in listOf(OLDEST, NEWEST)) {
            assertBroken("x.\"$TOO_LONG\"()", version)
        }
    }

    fun testAnAliasTooLongForAnAtomIsBroken() = assertBroken("A${TOO_LONG.drop(1)}")

    fun testAnAliasSegmentTooLongForAnAtomIsBroken() = assertBroken("Foo.A${TOO_LONG.drop(1)}")

    // `sigil_` and 250 letters
    fun testASigilNameTooLongForAnAtomIsBroken() = assertBroken("~${"A".repeat(250)}\"x\"")

    private fun assertBroken(code: String, elixirVersion: String = NEWEST) {
        val reasons = placeholderReasons(lower(code, elixirVersion))

        assertTrue("$code: $reasons", reasons.isNotEmpty() && reasons.all { it == ElixirAst.Placeholder.Reason.Error })
    }

    private fun placeholderReasons(ast: ElixirAst): List<ElixirAst.Placeholder.Reason> = ast.placeholders().map { it.reason }
}

private val ERROR = listOf(ElixirAst.Placeholder.Reason.Error)

/** One code point more than an atom may have. */
private val TOO_LONG = "a".repeat(256)
