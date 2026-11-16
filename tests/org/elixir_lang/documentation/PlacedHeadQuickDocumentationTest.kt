package org.elixir_lang.documentation

/** A head a macro's output placed, with nothing written declaring it, borrows nothing from the macro that wrote it. */
class PlacedHeadQuickDocumentationTest : QuickDocumentationTestCase() {
    /** `use Application` defines `stop/1`, whose head its debug info places at the macro's clause, so it gets the stub. */
    fun testAHeadAMacroPlacedIsTitledByTheStubWithTheExpanderOn() = withTheExpanderOn {
        myFixture.configureByText(
            "placed.ex",
            """
            defmodule Placed do
              use Application

              def calls, do: st<caret>op(1)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the stub, got: $documentation", "stop(arg1)" in documentation)
        assertFalse("Expected nothing of the macro's clause, got: $documentation", "defmacro" in documentation)
    }

    /**
     * A head a compiled macro places in the module that uses it is that module's function, at the arity its quote writes,
     * not the export of that name in the macro's `.beam`.
     */
    fun testAHeadACompiledMacroPlacedIsTitledAtItsOwnArityWithTheExpanderOn() = withTheExpanderOn {
        myFixture.addCompiledLibrary(PLACED_LIB, CompiledBuild("CompiledPlacedQuickDoc"))
        myFixture.configureByText(
            "placed.ex",
            """
            defmodule Placed do
              use CompiledPlacedQuickDoc

              def calls(y), do: <caret>f(y)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected f/1's stub, got: $documentation", "f(arg1)" in documentation)
        assertFalse("Expected nothing of f/2, got: $documentation", "f(arg1, arg2)" in documentation)
    }

    /**
     * A head a compiled macro places at its own clause, which has the name of the function its quote writes, is that
     * function's, at its own arity: the macro's clause writes none of it.
     */
    fun testAHeadPlacedAtACompiledMacroOfItsNameIsTitledByTheStubWithTheExpanderOn() = withTheExpanderOn {
        myFixture.addCompiledLibrary(SAME_NAME_MACRO_LIB, CompiledBuild("CompiledSameNameQuickDoc"))
        myFixture.configureByText(
            "placed.ex",
            """
            defmodule Placed do
              require CompiledSameNameQuickDoc
              CompiledSameNameQuickDoc.f(1, 2, 3)

              def calls(y), do: <caret>f(y)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected f/1's stub, got: $documentation", "f(arg1)" in documentation)
        assertFalse("Expected nothing of the macro's clause, got: $documentation", "f(n, o, p)" in documentation)
    }

    /** A source macro of the function's name writes its quote's `def f(x)`, which titles the function. */
    fun testADefASourceMacroOfItsNameQuotesIsTitledByItsHeadWithTheExpanderOn() = withTheExpanderOn {
        myFixture.addFileToProject("same_name.ex", SAME_NAME_MACRO_SOURCE)
        myFixture.configureByText(
            "placed.ex",
            """
            defmodule Placed do
              require SameName
              SameName.f(1, 2, 3)

              def calls(y), do: <caret>f(y)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the quote's head, got: $documentation", "f(x)" in documentation)
    }
}
