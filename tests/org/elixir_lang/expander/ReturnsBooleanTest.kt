package org.elixir_lang.expander

/** `elixir_utils:returns_boolean/1` of an expression's expansion, which `expand_case` reads to rewrite an `if`. */
class ReturnsBooleanTest : ExpanderTestCase() {
    fun testTrueDoes() = assertEvery("true", BOOLEAN)

    fun testFalseDoes() = assertEvery("false", BOOLEAN)

    fun testNilDoesNot() = assertEvery("nil", OTHER)

    fun testAnotherAtomDoesNot() = assertEvery(":a", OTHER)

    fun testErlangNotDoes() = assertEvery(":erlang.not(true)", BOOLEAN)

    fun testAnInlinedKernelFunctionDoesAsItsErlangFunction() = assertEvery("Kernel.is_integer(1)", BOOLEAN)

    fun testTheComparisonAndBooleanOperatorsDo() {
        for (operator in listOf("and", "or", "xor", "==", "/=", "=<", ">=", "<", ">", "=:=", "=/=")) {
            assertEvery(":erlang.\"$operator\"(1, 2)", BOOLEAN)
        }
    }

    fun testAComparisonOfOneArgumentDoesNot() = assertEvery(":erlang.\"==\"(1)", OTHER)

    fun testAndalsoDoesWhenItsRightSideDoes() = assertEvery(":erlang.andalso(1, :erlang.is_atom(1))", BOOLEAN)

    fun testAndalsoDoesNotWhenItsRightSideDoesNot() = assertEvery(":erlang.andalso(true, 1)", OTHER)

    fun testOrelseDoesWhenItsRightSideDoes() = assertEvery(":erlang.orelse(1, false)", BOOLEAN)

    fun testOrelseDoesNotWhenItsRightSideDoesNot() = assertEvery(":erlang.orelse(false, 1)", OTHER)

    fun testTheGuardsOfOneArgumentDo() {
        for (
            guard in listOf(
                "is_atom", "is_binary", "is_bitstring", "is_boolean", "is_float", "is_function", "is_integer", "is_list",
                "is_number", "is_pid", "is_port", "is_reference", "is_tuple", "is_map", "is_process_alive",
            )
        ) {
            assertEvery(":erlang.$guard(1)", BOOLEAN)
        }
    }

    fun testIsRecordOfOneArgumentDoesNot() = assertEvery(":erlang.is_record(1)", OTHER)

    fun testIsFunctionOfTwoArgumentsDoes() = assertEvery(":erlang.is_function(1, 2)", BOOLEAN)

    fun testIsRecordOfTwoArgumentsDoes() = assertEvery(":erlang.is_record(1, 2)", BOOLEAN)

    fun testIsMapKeyDoes() = assertEvery(":erlang.is_map_key(1, 2)", BOOLEAN)

    /** Before 1.20.0-rc.6 `rewrite/5` gives `:erlang.is_map_key/2`; from it the call stays `Kernel`'s. */
    fun testARewrittenCallDoesAsItsErlangCall() =
        assertSplit("Kernel.is_map_key(%{}, 1)", "1.20.0-rc.6", BOOLEAN, OTHER)

    fun testIsAtomOfTwoArgumentsDoesNot() = assertEvery(":erlang.is_atom(1, 2)", OTHER)

    fun testFunctionExportedDoes() = assertEvery(":erlang.function_exported(1, 2, 3)", BOOLEAN)

    fun testIsRecordOfThreeArgumentsDoes() = assertEvery(":erlang.is_record(1, 2, 3)", BOOLEAN)

    fun testListsMemberDoesFrom1_20_1() = assertSplit(":lists.member(1, [])", "1.20.1", OTHER, BOOLEAN)

    fun testAnotherErlangFunctionDoesNot() = assertEvery(":erlang.\"+\"(1, 2)", OTHER)

    fun testAnotherModulesGuardNameDoesNot() = assertEvery(":maps.is_key(1, %{})", OTHER)

    fun testACaseDoesWhenEveryClauseDoes() = assertEvery("case 1 do\n  1 -> true\n  _ -> :erlang.is_atom(1)\nend", BOOLEAN)

    fun testACaseDoesNotWhenOneClauseDoesNot() = assertEvery("case 1 do\n  1 -> true\n  _ -> 2\nend", OTHER)

    fun testACondDoesWhenEveryClauseDoes() = assertEvery("cond do\n  1 -> false\n  true -> true\nend", BOOLEAN)

    fun testACondDoesNotWhenOneClauseDoesNot() = assertEvery("cond do\n  1 -> false\n  true -> 2\nend", OTHER)

    fun testABlockDoesWhenItsLastExpressionDoes() = assertEvery("(\n  1\n  true\n)", BOOLEAN)

    fun testABlockDoesNotWhenOnlyAnEarlierExpressionDoes() = assertEvery("(\n  true\n  1\n)", OTHER)

    fun testAReceiveDoesNot() = assertEvery("receive do\n  _ -> true\nend", OTHER)

    override fun render(code: String, expansion: Expansion): String =
        when (expansion) {
            is Expansion.Expanded -> if (returnsBoolean(expansion.value)) BOOLEAN else OTHER
            else -> super.render(code, expansion)
        }

    private companion object {
        const val BOOLEAN = "boolean"
        const val OTHER = "other"
    }
}
