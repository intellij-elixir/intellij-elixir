package org.elixir_lang.documentation

/** A head a macro's output placed, with nothing written declaring it, borrows nothing from the macro that wrote it. */
class PlacedHeadQuickDocumentationTest : QuickDocumentationTestCase() {
    /** `use Application` defines `stop/1`, whose head its debug info places at the macro's clause, so it gets the stub. */
    fun testAHeadAMacroPlacedIsTitledByTheStubWithTheExpanderOn() = withTheExpanderOn {
        val documentation = quickDocumentationIn(
            "placed.ex",
            """
            defmodule Placed do
              use Application

              def calls, do: st<caret>op(1)
            end
            """.trimIndent()
        )

        assertTrue("Expected the stub, got: $documentation", "stop(arg1)" in documentation)
        assertFalse("Expected nothing of the macro's clause, got: $documentation", "defmacro" in documentation)
    }

    /**
     * A head a compiled macro places in the module that uses it is that module's function, at the arity its quote writes,
     * not the export of that name in the macro's `.beam`.
     */
    fun testAHeadACompiledMacroPlacedIsTitledAtItsOwnArityWithTheExpanderOn() = withTheExpanderOn {
        myFixture.addCompiledLibrary(PLACED_LIB, CompiledBuild("CompiledPlacedQuickDoc"))
        val documentation = quickDocumentationIn(
            "placed.ex",
            """
            defmodule Placed do
              use CompiledPlacedQuickDoc

              def calls(y), do: <caret>f(y)
            end
            """.trimIndent()
        )

        assertEquals("f(arg1)", titleOf(documentation))
    }

    /**
     * A head a compiled macro places at its own clause, which has the name of the function its quote writes, is that
     * function's, at its own arity: the macro's clause writes none of it.
     */
    fun testAHeadPlacedAtACompiledMacroOfItsNameIsTitledByTheStubWithTheExpanderOn() = withTheExpanderOn {
        myFixture.addCompiledLibrary(SAME_NAME_MACRO_LIB, CompiledBuild("CompiledSameNameQuickDoc"))
        val documentation = quickDocumentationIn(
            "placed.ex",
            """
            defmodule Placed do
              require CompiledSameNameQuickDoc
              CompiledSameNameQuickDoc.f(1, 2, 3)

              def calls(y), do: <caret>f(y)
            end
            """.trimIndent()
        )

        assertEquals("f(arg1)", titleOf(documentation))
    }

    /** A source macro of the function's name writes its quote's `def f(x)`, which titles the function. */
    fun testADefASourceMacroOfItsNameQuotesIsTitledByItsHeadWithTheExpanderOn() = withTheExpanderOn {
        myFixture.addFileToProject("same_name.ex", SAME_NAME_MACRO_SOURCE)
        val documentation = quickDocumentationIn(
            "placed.ex",
            """
            defmodule Placed do
              require SameName
              SameName.f(1, 2, 3)

              def calls(y), do: <caret>f(y)
            end
            """.trimIndent()
        )

        assertEquals("f(x)", titleOf(documentation))
    }

    /** An unquote fragment's `def`, only its name unquoted, titles the function it defines with its parameters. */
    fun testAnUnquoteFragmentsDefIsTitledByItsHeadWithTheExpanderOn() = withTheExpanderOn {
        val documentation = quickDocumentationIn(
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

        assertTrue("Expected the fragment's head, got: $documentation", "hello(x)" in documentation)
        assertFalse("Expected no stub, got: $documentation", "hello(arg1)" in documentation)
    }

    /**
     * An unquote fragment's `def`, only its name unquoted, and a `def` written after it are one function, titled by the
     * parameters Elixir names from both: `f(b)`, as `:x` names none.
     */
    fun testAnUnquoteFragmentsDefAndAWrittenDefAreTitledByBothWithTheExpanderOn() = withTheExpanderOn {
        val documentation = quickDocumentationIn(
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

        assertEquals("f(b)", titleOf(documentation))
    }

    /** Where both name the parameter, the unquote fragment's `def`, the older clause, names it, as Elixir's merge does. */
    fun testAnUnquoteFragmentsDefNamesTheParameterBeforeAWrittenDefWithTheExpanderOn() = withTheExpanderOn {
        val documentation = quickDocumentationIn(
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

        assertEquals("f(a)", titleOf(documentation))
    }

    /**
     * An unquote fragment's head with a default declares `f/1` and `f/2`; replacing `f/2` leaves `f/1`, the same function
     * as the override, so it is titled once, with the default, and lists no `f/1` of its own.
     */
    fun testAnUnquoteFragmentsDefaultHeadWhoseFullArityIsOverriddenIsOneFunctionWithTheExpanderOn() = withTheExpanderOn {
        val documentation = quickDocumentationIn(
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

        assertEquals("f(a, b \\\\ 1)", titleOf(documentation))
        assertFalse(documentation, "f(arg1)" in documentation)
    }

    /**
     * A head a compiled macro places at its own clause, whose name the decompiler unquotes, is not that clause's: `f/1`
     * is titled at its own arity, though the macro's module defines `f/2` with a default.
     */
    fun testAHeadPlacedAtACompiledMacroWhoseNameIsUnquotedIsTitledAtItsOwnArityWithTheExpanderOn() = withTheExpanderOn {
        assertEquals("f(arg1)", titleOf(documentationWithUnquotedNameMacro("build f", "<caret>f(y)")))
    }

    /** As above: `g/1` is titled by the stub, not by the parameter of the macro's clause, `n`. */
    fun testAHeadPlacedAtACompiledMacroWhoseNameIsUnquotedIsTitledByTheStubWithTheExpanderOn() = withTheExpanderOn {
        assertEquals("g(arg1)", titleOf(documentationWithUnquotedNameMacro("build g", "<caret>g(y)", "1")))
    }

    /** Quick Documentation at [call] in a module that calls [UNQUOTED_NAME_MACRO_LIB]'s [macro] with [arguments]. */
    private fun documentationWithUnquotedNameMacro(macro: String, call: String, arguments: String = ""): String {
        myFixture.addCompiledLibrary(UNQUOTED_NAME_MACRO_LIB, CompiledBuild("CompiledUnquotedNameQuickDoc"))

        return quickDocumentationIn("placed.ex", unquotedNameMacroCaller("CompiledUnquotedNameQuickDoc", macro, call, arguments))
    }
}
