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

    /** An unquote fragment's `def`, only its name unquoted, titles the function it defines with its parameters. */
    fun testAnUnquoteFragmentsDefIsTitledByItsHeadWithTheExpanderOn() = withTheExpanderOn {
        myFixture.configureByText(
            "fragment.ex",
            """
            defmodule Fragment do
              for name <- [:hello] do
                def unquote(name)(x), do: x
              end

              def calls(y), do: hel<caret>lo(y)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the fragment's head, got: $documentation", "hello(x)" in documentation)
        assertFalse("Expected no stub, got: $documentation", "hello(arg1)" in documentation)
    }

    /**
     * An unquote fragment's `def`, only its name unquoted, and a `def` written after it are one function, titled by the
     * parameters Elixir names from both: `f(b)`, as `:x` names none.
     */
    fun testAnUnquoteFragmentsDefAndAWrittenDefAreTitledByBothWithTheExpanderOn() = withTheExpanderOn {
        myFixture.configureByText(
            "mixed.ex",
            """
            defmodule Mixed do
              @doc "Fs."
              for name <- [:f] do
                def unquote(name)(:x), do: :x
              end

              def f(b), do: b

              def calls(y), do: <caret>f(y)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the title f(b), got: $documentation", documentation.lines().any { it == "f(b)" })
    }

    /** Where both name the parameter, the unquote fragment's `def`, the older clause, names it, as Elixir's merge does. */
    fun testAnUnquoteFragmentsDefNamesTheParameterBeforeAWrittenDefWithTheExpanderOn() = withTheExpanderOn {
        myFixture.configureByText(
            "mixed.ex",
            """
            defmodule Mixed do
              @doc "Fs."
              for name <- [:f] do
                def unquote(name)(a), do: a
              end

              def f(b), do: b

              def calls(y), do: <caret>f(y)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue("Expected the title f(a), got: $documentation", documentation.lines().any { it == "f(a)" })
    }

    /**
     * An unquote fragment's head with a default declares `f/1` and `f/2`; replacing `f/2` leaves `f/1`, the same function
     * as the override, so it is titled once, with the default, and lists no `f/1` of its own.
     */
    fun testAnUnquoteFragmentsDefaultHeadWhoseFullArityIsOverriddenIsOneFunctionWithTheExpanderOn() = withTheExpanderOn {
        myFixture.configureByText(
            "overridden.ex",
            """
            defmodule Overridden do
              @doc "Fs."
              for name <- [:f] do
                def unquote(name)(a, b \\ 1), do: {a, b}
              end

              defoverridable f: 2

              def f(a, b), do: {b, a}

              def run(y), do: <caret>f(y)
            end
            """.trimIndent()
        )

        val documentation = quickDocumentationAtCaret().orEmpty()

        assertTrue(
            "Expected the title f(a, b \\\\ 1), got: $documentation",
            documentation.lines().any { it == "f(a, b \\\\ 1)" }
        )
        assertFalse(documentation, "f(arg1)" in documentation)
    }

    /**
     * A head a compiled macro places at its own clause, whose name the decompiler unquotes, is not that clause's: `f/1`
     * is titled at its own arity, though the macro's module defines `f/2` with a default.
     */
    fun testAHeadPlacedAtACompiledMacroWhoseNameIsUnquotedIsTitledAtItsOwnArityWithTheExpanderOn() = withTheExpanderOn {
        val documentation = documentationWithUnquotedNameMacro("build f", "<caret>f(y)")

        assertTrue("Expected the title f(arg1), got: $documentation", documentation.lines().any { it == "f(arg1)" })
        assertFalse("Expected nothing of f/2, got: $documentation", "f(arg1, arg2)" in documentation)
    }

    /** As above: `g/1` is titled by the stub, not by the parameter of the macro's clause, `n`. */
    fun testAHeadPlacedAtACompiledMacroWhoseNameIsUnquotedIsTitledByTheStubWithTheExpanderOn() = withTheExpanderOn {
        val documentation = documentationWithUnquotedNameMacro("build g", "<caret>g(y)", "1")

        assertTrue("Expected the title g(arg1), got: $documentation", documentation.lines().any { it == "g(arg1)" })
        assertFalse("Expected nothing of the macro's clause, got: $documentation", "g(n)" in documentation)
    }

    /** Quick Documentation at [call] in a module that calls [UNQUOTED_NAME_MACRO_LIB]'s [macro] with [arguments]. */
    private fun documentationWithUnquotedNameMacro(macro: String, call: String, arguments: String = ""): String {
        myFixture.addCompiledLibrary(UNQUOTED_NAME_MACRO_LIB, CompiledBuild("CompiledUnquotedNameQuickDoc"))
        myFixture.configureByText("placed.ex", unquotedNameMacroCaller("CompiledUnquotedNameQuickDoc", macro, call, arguments))

        return quickDocumentationAtCaret().orEmpty()
    }
}
