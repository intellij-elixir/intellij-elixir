package org.elixir_lang.inlay_hints

import com.intellij.codeInsight.hints.InlayGroup
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayHintsProviderExtensionBean
import org.elixir_lang.expander.ExpanderSourceRows.withBeamLibrary
import java.io.File

@Suppress("UnstableApiUsage")
class ParameterNameHintsProviderTest : InlayHintsTestCase() {
    override val providerId = "elixir.parameter.name"

    override fun provider(): InlayHintsProvider = ParameterNameHintsProvider()

    fun testItIsOnByDefault() = assertRegistered(enabledByDefault = true)

    fun testItIsAParameterHint() =
        assertEquals(InlayGroup.PARAMETERS_GROUP, InlayHintsProviderExtensionBean.EP.extensionList.single { it.providerId == providerId }.group)

    // Positions

    fun testEachPositionalArgumentIsNamed() = assertHints(definer("f(/*<# first: #>*/x, /*<# second: #>*/y)"))

    /** A name belongs to the argument after it, so a soft wrap before the argument takes the name along. */
    fun testANameStaysWithItsArgument() {
        assertHints(definer("f(first, /*<# second: #>*/x)"))

        assertFalse(myFixture.editor.inlayModel.getInlineElementsInRange(0, myFixture.editor.document.textLength).single().isRelatedToPrecedingText)
    }

    fun testADefaultIsStripped() = assertHints(definer("h(/*<# a: #>*/w, /*<# b: #>*/x, /*<# c: #>*/y)"))

    /** Elixir fills the last defaults first, so `h(x, y)` binds `a` and `c`. */
    fun testAnArgumentIsNamedByTheParameterTheDefaultsLeave() = assertHints(definer("h(/*<# a: #>*/x, /*<# c: #>*/y)"))

    /** A default a later clause declares is still a default (Elixir warns), so `m(x, y)` binds `a` and `c`. */
    fun testAnArgumentIsNamedByADefaultALaterClauseDeclares() = assertHints(
        """
        defmodule Definer do
          def m(a, b, c), do: {a, b, c}
          def m(a, b \\ 0, c), do: {a, b, c}

          def calls(x, y), do: m(/*<# a: #>*/x, /*<# c: #>*/y)
        end
        """
    )

    fun testAPipedArgumentIsNamedByTheParameterAfterTheOneThePipeFills() =
        assertHints(definer("x |> f(/*<# second: #>*/y)"))

    fun testAPipedCallWithSecondaryArgumentsNamesItsPrimaryArgumentsAtTheirPositions() =
        assertHints(definer("x |> g(/*<# a: #>*/y)(z)"))

    fun testADoBlockIsNotNamed() = assertHints(definer("f(/*<# first: #>*/x) do\n      :ok\n    end"))

    fun testATrailingKeywordListIsNotNamed() = assertHints(definer("f(/*<# first: #>*/x, second: y)"))

    fun testNoArgumentIsNamedAfterASplice() =
        assertHints(definer("quote do\n      f(unquote_splicing(/*<# expr: #>*/xs), y)\n    end"))

    /** A head that splices takes any number of arguments past its parameters, so a call with more is named. */
    fun testACallOfAHeadThatSplicesItsParametersIsNamedBeforeTheSplice() =
        assertHints(
            """
            defmodule Spliced do
              for rest <- [[quote(do: b), quote(do: c)]] do
                def f(a, unquote_splicing(/*<# expr: #>*/rest)), do: a
              end

              def calls(x, y, z), do: f(/*<# a: #>*/x, y, z)
            end
            """
        )

    fun testNoArgumentIsNamedInAPipedSplice() =
        assertHints(definer("quote do\n      x |> f(unquote_splicing(/*<# expr: #>*/xs))\n    end"))

    fun testACallAtAnArityNoSignatureHasIsNotNamed() = assertHints(definer("f(x)"))

    fun testAPipedCallPastTheLastParameterIsNotNamed() = assertHints(definer("x |> f(y, z)"))

    /** `two/2` and `two/3`'s defaults both cover arity 2, so which one is called is not known. */
    fun testACallWithTwoSignaturesAtItsArityIsNotNamed() = assertHints(definer("two(x, y)"))

    /** `greet_all/2` starts with the call's name but is not what `greet(x, y)` calls. */
    fun testAFunctionWhoseNameOnlyStartsWithTheCallsIsNotCalled() = assertHints(
        """
        defmodule Greeter do
          def greet(name, greeting), do: {name, greeting}
          def greet_all(names, greeting), do: {names, greeting}

          def calls(x, y), do: greet(/*<# name: #>*/x, /*<# greeting: #>*/y)
        end
        """
    )

    // Names

    fun testAnArgumentWhoseTextIsTheNameIsNotNamed() = assertHints(definer("f(first, /*<# second: #>*/y)"))

    fun testAnUnderscoreIsNotNamed() = assertHints(definer("u(x, /*<# b: #>*/y)"))

    /** Elixir records `_ignored` as `ignored`. */
    fun testAnUnderscoredNameIsNamedWithoutItsUnderscore() = assertHints(definer("v(/*<# ignored: #>*/x, /*<# b: #>*/y)"))

    /** Elixir records two tuple patterns as `arg1` and `arg2`, and that is the name Parameter Info shows. */
    fun testAnElixirGuessedNameIsNamed() = assertHints(definer("t(/*<# arg1: #>*/x, /*<# arg2: #>*/y)"))

    /** The name a pattern binds is what Elixir records. */
    fun testAPatternIsNamedByTheNameItBinds() = assertHints(definer("p(/*<# map: #>*/x, /*<# list: #>*/y)"))

    /** A head a macro placed, where its clauses are not known, has names the plugin made up, so none is shown. */
    fun testAPlacedHeadWithUnknownClausesIsNotNamed() = assertHints(
        """
        defmodule Placed do
          use Application

          def calls(x), do: stop(x)
        end
        """
    )

    // Typespecs

    /** A typespec's calls are types, the head of `@spec` and `@callback` included, not calls of the module's functions. */
    fun testATypespecIsNotNamed() = assertHints(
        """
        defmodule Specced do
          @callback f(integer, atom) :: :ok
          @spec f(integer, atom) :: :ok
          def f(first, second), do: {first, second}

          @type t :: g(integer)
          def g(a), do: a
        end
        """
    )

    /** A bitstring segment's `size(8)` is one of its options, not a call of the module's `size/1`. */
    fun testABitstringSegmentOptionIsNotNamed() = assertHints(
        """
        defmodule Segmented do
          def size(n), do: n

          def calls(x), do: <<x::size(8)>>
        end
        """
    )

    /** Ecto's `assoc` in a join names an association; it calls no `assoc/2` the module defines. */
    fun testAnEctoJoinAssocIsNotNamed() = assertHints(
        """
        defmodule Joined do
          def assoc(struct, name), do: {struct, name}

          def calls(p), do: from(c in Comment, join: a in assoc(p, :author))
        end
        """
    )

    /** An `unquote` in a typespec is a call, not a type. */
    fun testAnUnquoteInATypespecIsNamed() = assertHints(
        """
        defmodule Quoted do
          defmacro typed(return) do
            quote do
              @spec f(integer) :: unquote(/*<# expr: #>*/return)
            end
          end
        end
        """
    )

    // Recorded names

    /** On OTP 27+, an Erlang module's `Docs` chunk names its parameters as Erlang writes them. */
    fun testAnErlangDocsChunkNameIsNamedAsErlangWritesIt() =
        assertCallerHints("beam/decompiler/OTP29_embedded_docs/stdlib/ebin/queue.beam", ":queue.in(/*<# Item: #>*/x, /*<# Q1: #>*/y)")

    /** Without a `Docs` chunk, the names are the ones the stub reads from the function's spec. */
    fun testAnErlangStubNameIsNamed() =
        assertCallerHints("code_insight/parameter_info/beam/ebin/queue.beam", ":queue.in(/*<# item: #>*/x, /*<# q1: #>*/y)")

    /** `pattern({X}, Y)` has no spec, so the stub keeps its first clause, whose first parameter is no name. */
    fun testAnErlangStubPatternIsNotNamed() =
        assertCallerHints("beam/decompiler/erlang_signature/erlang_signature.beam", ":erlang_signature.pattern(x, /*<# y: #>*/y1)")

    fun testAnErlangStubUnderscoreIsNotNamed() =
        assertCallerHints("beam/decompiler/erlang_signature/erlang_signature.beam", ":erlang_signature.underscore(x, /*<# value: #>*/y)")

    /** An Elixir `.beam` with debug info and no `Docs` chunk is named by its decompiled head. */
    fun testAnElixirDebugInfoHeadIsNamed() =
        assertCallerHints("beam/generated_arguments/ebin/Elixir.DebugDefaults.beam", "DebugDefaults.snoc(/*<# q: #>*/a, /*<# x: #>*/b)")

    fun testAnElixirDocsChunkNameIsNamed() =
        assertCallerHints("beam/generated_arguments/ebin/Elixir.DocsDefaults.beam", "DocsDefaults.snoc(/*<# q: #>*/a, /*<# x: #>*/b)")

    /** With neither debug info nor `Docs`, the names are made up from the arity, and none is shown. */
    fun testGeneratedNamesAreNotNamed() =
        assertCallerHints("beam/generated_arguments/ebin/Elixir.NoNamesDefaults.beam", "NoNamesDefaults.d(a)")

    // Calls without parentheses

    fun testACallWithoutParenthesesIsNotNamed() =
        assertHints(
            """
            defmodule Caller do
              use GenServer

              def run(x) do
                if x do
                  IO.puts "x"
                end
              end
            end
            """
        )

    /** The same calls with parentheses are named, so the calls above resolve. */
    fun testTheSameCallsWithParenthesesAreNamed() =
        assertHints(
            """
            defmodule Caller do
              use(/*<# module: #>*/GenServer)

              def(/*<# call: #>*/parenthesised(x), do: x)

              def run(x) do
                if(/*<# condition: #>*/x, do: IO.puts(/*<# item: #>*/"x"))
              end
            end
            """
        )

    private fun definer(call: String) =
        """
        defmodule Definer do
          def f(first, second), do: {first, second}
          def g(a), do: fn b -> {a, b} end
          def h(a, b \\ 1, c, d \\ 2), do: {a, b, c, d}
          def two(a, b), do: {a, b}
          def two(a, b, c \\ 1), do: {a, b, c}
          def u(_, b), do: b
          def v(_ignored, b), do: b
          def t({a}, {b}), do: {a, b}
          def p(%{} = map, [_ | _] = list), do: {map, list}

          def calls(x, y, z, w, xs) do
            $call
          end
        end
        """

    /**
     * Asserts the hints of [call], written in a function body, with a library of only [beam], under
     * `testData/org/elixir_lang`, so no decompiled `.ex` beside it is read instead.
     */
    private fun assertCallerHints(beam: String, call: String) =
        withBeamLibrary(project, module, testRootDisposable, "parameter-name-hints", File("testData/org/elixir_lang", beam)) {
            assertHints(
                """
                defmodule Caller do
                  def run(x, y, a, b, y1) do
                    $call
                  end
                end
                """
            )
        }
}
