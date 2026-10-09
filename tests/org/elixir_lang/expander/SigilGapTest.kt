package org.elixir_lang.expander

import org.elixir_lang.NameArity

/** The sigil forms the expander stops at as `Unported` instead of building, which [SigilOutputTest] cannot compare. */
class SigilGapTest : ExpanderTestCase() {
    override val kernel = KernelImports(emptyList(), listOf(NameArity("sigil_D", 2), NameArity("sigil_w", 2)))

    /** A calendar other than `Calendar.ISO` is a module whose functions the expander can't run. */
    fun testAnotherCalendarIsUnported() = assertEvery("~D[2015-01-13 Foo.Cal]", "unported `~D[2015-01-13 Foo.Cal]`")

    /** `String.split/1` splits bytes, where the expander's strings are text. */
    fun testWordsOfInvalidUtf8AreUnported() = assertEvery("~w(\\xFF a)", "unported `~w(\\xFF a)`")
}
