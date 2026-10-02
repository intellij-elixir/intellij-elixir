package org.elixir_lang.expander

import org.elixir_lang.NameArity

/** `super(...)`, `&super(...)` and `&super/N`, which only a function can call. */
class SuperExpanderTest : ExpanderTestCase() {
    override var module: String? = "Elixir.Overriding"
    override var function: NameArity? = null

    fun testSuperOutsideAFunction() {
        assertEvery("super()", "error invalid_expr_in_scope `super()`")
        assertEvery("super(1)", "error invalid_expr_in_scope `super(1)`")
        assertEvery("_ = fn -> super() end", "error invalid_expr_in_scope `super()`")
        assertEvery("&super/1", "error invalid_expr_in_scope `&super/1`")
        assertEvery("&super(&1)", "error invalid_expr_in_scope `&super(&1)`")
    }

    fun testSuperOutsideAModule() {
        module = null

        assertEvery("super()", "error invalid_expr_in_scope `super()`")
        assertEvery("&super/1", "error invalid_expr_in_scope `&super/1`")
        assertEvery("&super(&1)", "error invalid_expr_in_scope `&super(&1)`")
    }

    /** The scope is checked before the arguments are expanded. */
    fun testSuperArgumentsAreNotExpandedOutsideAFunction() =
        assertEvery("super(y)", "error invalid_expr_in_scope `super(y)`")

    fun testSuperInAFunctionIsUnported() {
        function = NameArity("f", 1)

        assertEvery("super(1)", "unported `super(1)`")
        assertEvery("&super/1", "unported `&super/1`")
        assertEvery("&super(&1)", "unported `&super(&1)`")
    }

    fun testSuperInAPattern() {
        assertEvery("super(1) = 1", "error invalid_pattern_in_match `super(1)`")
        assertEvery("(&super/1) = 1", "error invalid_pattern_in_match `&super/1`")
        assertEvery("(&super(&1)) = 1", "error invalid_pattern_in_match `&super(&1)`")
    }

    fun testSuperInAGuard() {
        assertEvery("x = 1\ncase x do\ny when super(y) -> y\nend", "error invalid_expr_in_guard `super(y)`")
        assertEvery("x = 1\ncase x do\ny when &super/1 -> y\nend", "error invalid_expr_in_guard `&super/1`")
    }
}
