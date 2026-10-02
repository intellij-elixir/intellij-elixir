package org.elixir_lang.expander

import org.elixir_lang.NameArity

/** In a function's body a call's name may be a local macro, which is looked up before the imports. */
class FunctionBodyExpanderTest : ExpanderTestCase() {
    override val function = NameArity("f", 0)

    fun testALocalCallIsUnported() = assertEvery("foo(1)", "unported `foo(1)`")

    fun testANamedBitstringSpecIsUnported() {
        assertEvery("<<x::foo()>> = <<1>>", "unported `foo()`")
        assertSplit("<<x::foo>> = <<1>>", "1.15.0-rc.0", "error undefined_bittype `x::foo`", "unported `foo`")
    }

    fun testARescueCallIsUnported() = assertEvery("try do\n1\nrescue\nfoo() -> 1\nend", "unported `foo()`")
}
