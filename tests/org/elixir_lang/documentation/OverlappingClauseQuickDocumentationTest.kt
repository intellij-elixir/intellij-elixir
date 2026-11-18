package org.elixir_lang.documentation

/**
 * A `def` whose defaults cover an arity another `def` of its name already defines. Elixir keys each by its name and
 * full arity, so they are two functions, `snoc/1` and `snoc/2`, each with its own entry.
 */
class OverlappingClauseQuickDocumentationTest : QuickDocumentationTestCase() {
    fun testQuickDocsAtTheLowerArityShowEachFunctionOnItsOwn() =
        assertEachFunctionOnItsOwn(quickDocumentationIn("overlapping.ex", OVERLAPPING))

    fun testQuickDocsAtTheLowerArityShowEachFunctionOnItsOwnWithTheExpanderOn() =
        withTheExpanderOn(::testQuickDocsAtTheLowerArityShowEachFunctionOnItsOwn)

    /**
     * A `def` written at an arity the defaults of an overridden function had generated is a function of its own, as
     * `Docs` records `o(a)` beside `o(a, b \\ 1)`.
     */
    fun testADefWrittenAtAnOverriddenFunctionsDefaultArityIsShownWithTheExpanderOn() = withTheExpanderOn {
        assertTheWrittenDef(quickDocumentationIn("overlapping.ex", "$OV_FULL_GEN\n\ndefmodule Caller do\n  def calls(x), do: OvFullGen.<caret>o(x)\nend\n"))
    }

    fun testADefWrittenAtACompiledOverriddenFunctionsDefaultArityIsShownWithTheExpanderOn() = withTheExpanderOn {
        myFixture.addCompiledLibrary(OV_FULL_GEN_LIB, CompiledBuild("CompiledOvFullGenQuickDoc"))

        assertTheWrittenDef(
            quickDocumentationIn("overlapping.ex", "defmodule Caller do\n  def calls(x), do: CompiledOvFullGenQuickDoc.<caret>o(x)\nend\n")
        )
    }

    /**
     * Without debug info, `Docs` keys the written `def o(a)` as `o/1` and the generated `o/1` under `o/2`, so each
     * entry is titled by its own function's signature alone.
     */
    fun testEachFunctionOfADocsOnlyCompiledOverriddenFunctionIsTitledByItsOwnSignature() {
        myFixture.addCompiledLibrary(OV_FULL_GEN_LIB, CompiledBuild("CompiledOvFullGenDocsOnly", debugInfo = false))

        val documentation =
            quickDocumentationIn("overlapping.ex", "defmodule Caller do\n  def calls(x), do: CompiledOvFullGenDocsOnly.<caret>o(x)\nend\n")
        assertEquals(
            "Expected a link per function, each with its own signature, got: $documentation",
            listOf("1" to "a", "2" to """a, b \\ 1"""),
            linkedHeads(documentation, "o")
        )
    }

    fun testEachFunctionOfADocsOnlyCompiledOverriddenFunctionIsTitledByItsOwnSignatureWithTheExpanderOn() =
        withTheExpanderOn(::testEachFunctionOfADocsOnlyCompiledOverriddenFunctionIsTitledByItsOwnSignature)

    /**
     * An override of the arity a head's defaults key leaves the arity they add to the head, and the two are one function,
     * as `Docs` records only `f(a, b \\ 1)`.
     */
    fun testAnOverrideOfADefaultHeadsFullArityIsOneFunctionWithTheExpanderOn() = withTheExpanderOn {
        val documentation = quickDocumentationIn(
            "overlapping.ex",
            """
            defmodule Overridden do
              def f(a, b \\ 1), do: {a, b}

              defoverridable f: 2

              def f(a, b), do: {b, a}
            end

            defmodule Caller do
              def calls(y), do: Overridden.<caret>f(y)
            end
            """.trimIndent()
        )
        assertOneFunction(documentation, """f(a, b \\ 1)""", "f")
    }

    /** As above, compiled: the debug info keeps the generated `o/1` as a definition of its own, but `Docs` does not. */
    fun testAnOverrideOfACompiledDefaultHeadsFullArityIsOneFunction() {
        myFixture.addCompiledLibrary(OV_FULL_LIB, CompiledBuild("CompiledOvFullQuickDoc"))

        val documentation =
            quickDocumentationIn("overlapping.ex", "defmodule Caller do\n  def calls(x), do: CompiledOvFullQuickDoc.<caret>o(x)\nend\n")
        assertOneFunction(documentation, """o(a, b \\ 1)""", "o")
    }

    fun testAnOverrideOfACompiledDefaultHeadsFullArityIsOneFunctionWithTheExpanderOn() =
        withTheExpanderOn(::testAnOverrideOfACompiledDefaultHeadsFullArityIsOneFunction)

    /** [documentation] is titled [title] and links no other head of [name]. */
    private fun assertOneFunction(documentation: String, title: String, name: String) {
        assertEquals(title, titleOf(documentation))
        assertEquals("Expected one function, got: $documentation", emptyList<Pair<String, String>>(), linkedHeads(documentation, name))
    }

    private fun assertTheWrittenDef(documentation: String) {
        assertTrue("Expected the written def's @doc, got: $documentation", "o written" in documentation)
        assertTrue("Expected the written def's head, got: $documentation", "def o(a)" in documentation)
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
