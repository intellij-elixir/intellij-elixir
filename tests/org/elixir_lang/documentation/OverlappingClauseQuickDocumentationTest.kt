package org.elixir_lang.documentation

/**
 * A `def` whose defaults cover an arity another `def` of its name already defines. Elixir keys each by its name and
 * full arity, so they are two functions, `snoc/1` and `snoc/2`, each with its own entry.
 */
class OverlappingClauseQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testQuickDocsAtTheLowerArityShowEachFunctionOnItsOwn() =
        assertEachFunctionOnItsOwn(quickDocumentationAtCaret(OVERLAPPING))

    fun testQuickDocsAtTheLowerArityShowEachFunctionOnItsOwnWithTheExpanderOn() = withTheExpanderOn {
        assertEachFunctionOnItsOwn(quickDocumentationAtCaret(OVERLAPPING))
    }

    /**
     * A `def` written at an arity the defaults of an overridden function had generated is a function of its own, as
     * `Docs` records `o(a)` beside `o(a, b \\ 1)`.
     */
    fun testADefWrittenAtAnOverriddenFunctionsDefaultArityIsShownWithTheExpanderOn() = withTheExpanderOn {
        assertTheWrittenDef(quickDocumentationAtCaret("$OV_FULL_GEN\n\ndefmodule Caller do\n  def calls(x), do: OvFullGen.<caret>o(x)\nend\n"))
    }

    fun testADefWrittenAtACompiledOverriddenFunctionsDefaultArityIsShownWithTheExpanderOn() = withTheExpanderOn {
        myFixture.addCompiledLibrary(OV_FULL_GEN_LIB, CompiledBuild("CompiledOvFullGenQuickDoc"))

        assertTheWrittenDef(
            quickDocumentationAtCaret("defmodule Caller do\n  def calls(x), do: CompiledOvFullGenQuickDoc.<caret>o(x)\nend\n")
        )
    }

    /**
     * Without debug info, `Docs` keys the written `def o(a)` as `o/1` and the generated `o/1` under `o/2`, so each
     * entry is titled by its own function's signature alone.
     */
    fun testEachFunctionOfADocsOnlyCompiledOverriddenFunctionIsTitledByItsOwnSignature() {
        myFixture.addCompiledLibrary(OV_FULL_GEN_LIB, CompiledBuild("CompiledOvFullGenDocsOnly", debugInfo = false))

        val documentation =
            quickDocumentationAtCaret("defmodule Caller do\n  def calls(x), do: CompiledOvFullGenDocsOnly.<caret>o(x)\nend\n")
        assertEquals(
            "Expected a link per function, each with its own signature, got: $documentation",
            listOf("1" to "a", "2" to """a, b \\ 1"""),
            linkedHeads(documentation, "o")
        )
    }

    fun testEachFunctionOfADocsOnlyCompiledOverriddenFunctionIsTitledByItsOwnSignatureWithTheExpanderOn() =
        withTheExpanderOn(::testEachFunctionOfADocsOnlyCompiledOverriddenFunctionIsTitledByItsOwnSignature)

    private fun assertTheWrittenDef(documentation: String) {
        assertTrue("Expected the written def's @doc, got: $documentation", "o written" in documentation)
        assertTrue("Expected the written def's head, got: $documentation", "def o(a)" in documentation)
    }

    private fun quickDocumentationAtCaret(text: String): String {
        myFixture.configureByText("overlapping.ex", text)

        return quickDocumentationAtCaret().orEmpty()
    }

    private fun assertEachFunctionOnItsOwn(documentation: String) {
        assertEquals(
            "Expected a link per function, ascending, got: $documentation",
            listOf("1" to "p0", "2" to """q, x \\ nil"""),
            linkedHeads(documentation, "snoc")
        )
        assertTrue("Expected snoc/2's @doc, got: $documentation", "Appends an element." in documentation)
    }

    companion object {
        private val OVERLAPPING = """
            defmodule Overlapping do
              def snoc(p0) do
                p0
              end

              @doc "Appends an element."
              def snoc(q, x \\ nil) do
                {q, x}
              end

              def calls(a), do: sn<caret>oc(a)
            end
        """.trimIndent()
    }
}
