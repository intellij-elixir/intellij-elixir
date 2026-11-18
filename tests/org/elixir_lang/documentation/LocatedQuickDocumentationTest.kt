package org.elixir_lang.documentation

/** Quick Documentation reads a `.beam`'s definition at the clauses of it the `.beam`'s decompiled source writes. */
class LocatedQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testADocsOnlyDeprecatedDefaultsHeadKeepsItsDeprecation() = assertDeprecationKept("LocatedDocsOnly")

    fun testADocsOnlyDeprecatedDefaultsHeadKeepsItsDeprecationWithTheExpanderOn() =
        withTheExpanderOn { assertDeprecationKept("LocatedDocsOnlyExpanded") }

    /**
     * A Docs-only `.beam`'s decompiled source writes a head with defaults beside its lower arity's clause: documented at
     * a call of its full arity, the head is the one clause the use's find is, so its `@deprecated` is kept.
     */
    private fun assertDeprecationKept(module: String) {
        myFixture.addCompiledLibrary(
            "defmodule Compiled do\n  @doc \"Ds.\"\n  @deprecated \"Use e.\"\n  def located_d(a, b \\\\ 1), do: a + b\nend\n",
            CompiledBuild(module, debugInfo = false),
        )
        val documentation = quickDocumentationIn("caller.ex", "defmodule Caller do\n  def g(x), do: $module.located_<caret>d(x, 2)\nend\n")

        assertTrue("Expected the deprecation, got: $documentation", "Use e." in documentation)
    }
}
