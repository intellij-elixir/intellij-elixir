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

    /** `@doc` above a call whose quote writes the `def` is the function's doc, as `compile_doc` gives it. */
    fun testADocAboveACallWhoseQuoteDefinesTheFunctionIsItsDocWithTheExpanderOn() = withTheExpanderOn {
        assertDocumented("Mine.", "@doc \"Mine.\"\n  Gen.shape_a(:mine)", "Mine.mi<caret>ne()")
    }

    /** The quote writes the head itself, so the `@doc` is above the call, not above the `def`. */
    fun testADocAboveACallWhoseQuoteWritesTheHeadIsTheFunctionsDocWithTheExpanderOn() = withTheExpanderOn {
        assertDocumented("Written.", "@doc \"Written.\"\n  Gen.shape_w()", "Mine.written_<caret>head()")
    }

    /** `@doc` written in the quote before its `def` is the function's doc. */
    fun testADocTheQuoteWritesIsTheFunctionsDocWithTheExpanderOn() = withTheExpanderOn {
        assertDocumented("Hello.", "Gen.shape_c(:hello)", "Mine.he<caret>llo()")
    }

    /** One `@doc` goes to the first function the quote defines, and the second has none. */
    fun testADocAboveACallThatDefinesTwoFunctionsIsTheFirstsWithTheExpanderOn() = withTheExpanderOn {
        assertDocumented("Above two.", "@doc \"Above two.\"\n  Gen.two(:first, :second)", "Mine.fi<caret>rst()")
        assertDocumented("Above two.", null, "Mine.sec<caret>ond()", documented = false)
    }

    /** A `@doc` the quote writes wins over the one above the call, which `compile_doc` has already taken. */
    fun testADocTheQuoteWritesWinsOverTheOneAboveTheCallWithTheExpanderOn() = withTheExpanderOn {
        assertDocumented("Own.", "@doc \"Above own.\"\n  Gen.own(:mine)", "Mine.mi<caret>ne()")
    }

    /** `@doc` through a nested macro: the outer quote's `@doc` is the function's. */
    fun testADocTheOuterQuoteWritesIsTheFunctionsDocWithTheExpanderOn() = withTheExpanderOn {
        assertDocumented("Mid.", "@doc \"Above mid.\"\n  Outer.mid(:m)", "Mine.<caret>m()")
    }

    /** As above, where the inner macro has the outer's name and arity. */
    fun testADocTheOuterQuoteWritesIsTheFunctionsDocThoughTheInnerMacroSharesItsNameWithTheExpanderOn() = withTheExpanderOn {
        assertDocumented("Mid.", "@doc \"Above same.\"\n  Outer5.gen(:s)", "Mine.<caret>s()")
    }

    /** Where the outer quote writes no `@doc`, the one above the call reaches the `def` through the nesting. */
    fun testADocAboveACallThroughANestedMacroIsTheFunctionsDocWithTheExpanderOn() = withTheExpanderOn {
        assertDocumented("Above plain.", "@doc \"Above plain.\"\n  Outer.plain(:p)", "Mine.<caret>p()")
    }

    /** A `def` inside an `if` in the quote is still the function's, and takes the `@doc` above the call. */
    fun testADocAboveACallWhoseQuoteDefinesInAnIfIsTheFunctionsDocWithTheExpanderOn() = withTheExpanderOn {
        assertDocumented("Above wrapped.", "@doc \"Above wrapped.\"\n  Outer.wrapped(:w)", "Mine.<caret>w()")
    }

    /** `use` is a call too: the `@doc` above it is the doc of the function its `__using__` defines. */
    fun testADocAboveAUseIsTheDocOfTheFunctionItsUsingDefinesWithTheExpanderOn() = withTheExpanderOn {
        assertDocumented("Above use.", "@doc \"Above use.\"\n  use Using5, name: :u", "Mine.<caret>u()")
    }

    /** A statement before the macro's `quote` doesn't change where the `@doc` goes. */
    fun testADocAboveACallWhoseMacroHasAStatementBeforeItsQuoteIsTheFunctionsDocWithTheExpanderOn() = withTheExpanderOn {
        assertDocumented("Above stmt.", "@doc \"Above stmt.\"\n  Outer5.stmt(:t)", "Mine.<caret>t()")
    }

    /** A call that leaves off the macro's default argument is the same call. */
    fun testADocAboveACallThatLeavesOffADefaultIsTheFunctionsDocWithTheExpanderOn() = withTheExpanderOn {
        assertDocumented("Above dflt.", "@doc \"Above dflt.\"\n  Outer5.dflt(:d)", "Mine.<caret>d()")
    }

    /** A `for` in the quote defines `a` first, which takes the `@doc`; `b` has none. */
    fun testADocAboveACallWhoseQuoteLoopsIsTheFirstFunctionsDocWithTheExpanderOn() = withTheExpanderOn {
        assertDocumented("Above each.", "@doc \"Above each.\"\n  Gen.each([:a, :b])", "Mine.<caret>a()")
        assertDocumented("Above each.", null, "Mine.<caret>b()", documented = false)
    }

    /** A `@doc` inside a module-body `for` is each function's own; one above the `for` is the first's. */
    fun testADocInsideAModuleBodyForIsEachFunctionsOwnWithTheExpanderOn() = withTheExpanderOn {
        val loops = listOf(
            "for n <- [:c, :d] do",
            "    @doc \"Fragment #{n}.\"",
            "    def unquote(n)(), do: :ok",
            "  end",
            "",
            "  @doc \"Above frag.\"",
            "  for n <- [:e, :f] do",
            "    def unquote(n)(), do: :ok",
            "  end"
        ).joinToString("\n")

        assertDocumented("Fragment c.", loops, "Mine.<caret>c()")
        assertDocumented("Fragment d.", null, "Mine.<caret>d()")
        assertDocumented("Above frag.", null, "Mine.<caret>e()")
        assertDocumented("Above frag.", null, "Mine.<caret>f()", documented = false)
    }

    /** A quote that writes `@doc false` hides the function the `@doc` above the call would have documented. */
    fun testAFunctionAQuoteHidesIsNotListedBesideAnotherArityWithTheExpanderOn() = withTheExpanderOn {
        val documentation = documentationOf(
            "@doc \"Above hide.\"\n  Gen.hide(:hidden_one)\n\n  def hidden_one(a, b), do: {a, b}",
            "Mine.hidden_<caret>one(1, 2)"
        )

        assertFalse("Expected hidden_one/1 left out, got: $documentation", "hidden_one(a)" in documentation)
    }

    /** `use Application` writes `@doc false` before `stop/1`, so another module's listing of `stop` leaves it out. */
    fun testAFunctionUseApplicationHidesIsNotListedBesideAnotherArityWithTheExpanderOn() = withTheExpanderOn {
        myFixture.addFileToProject(
            "placed.ex",
            """
            defmodule Placed do
              use Application

              def stop(a, b), do: {a, b}
            end
            """.trimIndent()
        )
        val documentation = quickDocumentationIn(
            "caller.ex",
            """
            defmodule Caller do
              def calls, do: Placed.st<caret>op(1, 2)
            end
            """.trimIndent()
        )

        assertFalse("Expected stop/1 left out, got: $documentation", "stop(arg1)" in documentation)
    }

    /** Quick Documentation at [call], as [documentationOf] gives it, shows [doc] exactly where [documented]. */
    private fun assertDocumented(doc: String, definitions: String?, call: String, documented: Boolean = true) {
        val documentation = documentationOf(definitions, call)

        assertEquals("Expected \"$doc\" shown exactly where documented, got: $documentation", documented, doc in documentation)
    }

    /**
     * Quick Documentation at [call], in a module other than `Mine`; where [definitions] is given, `Mine` is added first,
     * holding them after its `require`s.
     */
    private fun documentationOf(definitions: String?, call: String): String {
        definitions?.let {
            myFixture.addFileToProject("macros.ex", MACROS)
            myFixture.addFileToProject(
                "mine.ex",
                "defmodule Mine do\n  require Gen\n  require Inner\n  require Inner5\n  require Outer\n  require Outer5\n  require Using5\n\n  $it\nend"
            )
        }

        callers++

        return quickDocumentationIn("caller$callers.ex", "defmodule Caller$callers do\n  def calls, do: $call\nend")
    }

    private var callers = 0

    /** Quick Documentation at [call] in a module that calls [UNQUOTED_NAME_MACRO_LIB]'s [macro] with [arguments]. */
    private fun documentationWithUnquotedNameMacro(macro: String, call: String, arguments: String = ""): String {
        myFixture.addCompiledLibrary(UNQUOTED_NAME_MACRO_LIB, CompiledBuild("CompiledUnquotedNameQuickDoc"))

        return quickDocumentationIn("placed.ex", unquotedNameMacroCaller("CompiledUnquotedNameQuickDoc", macro, call, arguments))
    }

    private companion object {
        /** The macros the rows above call, each in a module of the one file: `Inner` and `Inner5` are the nested ones. */
        val MACROS = """
            defmodule Gen do
              defmacro shape_a(name) do
                quote do
                  def unquote(name)(), do: :ok
                end
              end

              defmacro shape_w do
                quote do
                  def written_head(), do: :ok
                end
              end

              defmacro shape_c(name) do
                quote do
                  @doc "Hello."
                  def unquote(name)(), do: :ok
                end
              end

              defmacro two(first, second) do
                quote do
                  def unquote(first)(), do: :ok
                  def unquote(second)(), do: :ok
                end
              end

              defmacro own(name) do
                quote do
                  @doc "Own."
                  def unquote(name)(), do: :ok
                end
              end

              defmacro hide(name) do
                quote do
                  @doc false
                  def unquote(name)(a), do: a
                end
              end

              defmacro each(names) do
                quote bind_quoted: [names: names] do
                  for n <- names do
                    def unquote(n)(), do: :ok
                  end
                end
              end
            end

            defmodule Inner do
              defmacro gen(name) do
                quote do
                  def unquote(name)(), do: :ok
                end
              end
            end

            defmodule Outer do
              defmacro mid(name) do
                quote do
                  @doc "Mid."
                  Inner.gen(unquote(name))
                end
              end

              defmacro plain(name) do
                quote do
                  Inner.gen(unquote(name))
                end
              end

              defmacro wrapped(name) do
                quote do
                  if true do
                    def unquote(name)(), do: :ok
                  end
                end
              end
            end

            defmodule Inner5 do
              defmacro gen(name) do
                quote do
                  def unquote(name)(), do: :ok
                end
              end
            end

            defmodule Outer5 do
              defmacro gen(name) do
                quote do
                  @doc "Mid."
                  Inner5.gen(unquote(name))
                end
              end

              defmacro stmt(name) do
                inner = name

                quote do
                  Inner5.gen(unquote(inner))
                end
              end

              defmacro dflt(name, _extra \\ nil) do
                quote do
                  Inner5.gen(unquote(name))
                end
              end
            end

            defmodule Using5 do
              defmacro __using__(options) do
                name = Keyword.fetch!(options, :name)

                quote do
                  def unquote(name)(), do: :ok
                end
              end
            end
        """.trimIndent()
    }
}
