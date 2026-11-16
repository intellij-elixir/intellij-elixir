package org.elixir_lang.code_insight

import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.documentation.CompiledBuild
import org.elixir_lang.documentation.SAME_NAME_MACRO_LIB
import org.elixir_lang.documentation.SAME_NAME_MACRO_SOURCE
import org.elixir_lang.documentation.UNQUOTED_NAME_MACRO_LIB
import org.elixir_lang.documentation.addCompiledLibrary
import org.elixir_lang.documentation.unquotedNameMacroCaller

/** A head a macro's output placed, with nothing written declaring it, borrows nothing from the macro that wrote it. */
class PlacedHeadParameterInfoTest : ExpanderSettingTestCase() {
    /** `use Application` defines `stop/1`, whose head its debug info places at the macro's clause, so it gets the stub. */
    fun testAHeadAMacroPlacedShowsTheStubWithTheExpanderOn() = withTheExpanderOn {
        assertEquals(
            listOf("arg1"),
            shown(
                """
                defmodule Placed do
                  use Application

                  def calls, do: stop(1<caret>)
                end
                """.trimIndent()
            )
        )
    }

    /** A head a compiled macro places at its own clause, of the function's name, shows the stub, not the macro's parameters. */
    fun testAHeadPlacedAtACompiledMacroOfItsNameShowsTheStubWithTheExpanderOn() = withTheExpanderOn {
        myFixture.addCompiledLibrary(SAME_NAME_MACRO_LIB, CompiledBuild("CompiledSameNameParameterInfo"))

        assertEquals(
            listOf("arg1"),
            shown(
                """
                defmodule Placed do
                  require CompiledSameNameParameterInfo
                  CompiledSameNameParameterInfo.f(1, 2, 3)

                  def calls(y), do: f(y<caret>)
                end
                """.trimIndent()
            )
        )
    }

    /**
     * A head a compiled macro places at its own clause, whose name the decompiler unquotes, is not that clause's: `f/1`
     * keeps its own arity, though the macro's module defines `f/2` with a default.
     */
    fun testAHeadPlacedAtACompiledMacroWhoseNameIsUnquotedShowsTheStubAtItsOwnArityWithTheExpanderOn() =
        withTheExpanderOn { assertEquals(listOf("arg1"), shownWithUnquotedNameMacro("build f", "f(y<caret>)")) }

    /** As above: `g/1` shows the stub, not the parameter of the macro's clause, `n`. */
    fun testAHeadPlacedAtACompiledMacroWhoseNameIsUnquotedShowsTheStubNotTheMacrosParametersWithTheExpanderOn() =
        withTheExpanderOn { assertEquals(listOf("arg1"), shownWithUnquotedNameMacro("build g", "g(y<caret>)", "1")) }

    /** As above, for a macro the macro's quote writes, which its clause's own arity and kind do not tell apart. */
    fun testAMacroPlacedAtACompiledMacroWhoseNameIsUnquotedShowsTheStubNotTheMacrosParametersWithTheExpanderOn() =
        withTheExpanderOn { assertEquals(listOf("arg1"), shownWithUnquotedNameMacro("build m", "m(y<caret>)", "1")) }

    /** A source macro of the function's name writes its quote's `def f(x)`, whose parameters the function shows. */
    fun testADefASourceMacroOfItsNameQuotesShowsItsParametersWithTheExpanderOn() = withTheExpanderOn {
        myFixture.addFileToProject("same_name.ex", SAME_NAME_MACRO_SOURCE)

        assertEquals(
            listOf("x"),
            shown(
                """
                defmodule Placed do
                  require SameName
                  SameName.f(1, 2, 3)

                  def calls(y), do: f(y<caret>)
                end
                """.trimIndent()
            )
        )
    }

    /** A `def unquote(name)(x)` a macro's quote writes gives the function it defines its parameters. */
    fun testADefAQuoteWritesWithOnlyItsNameUnquotedShowsItsParametersWithTheExpanderOn() = withTheExpanderOn {
        assertEquals(
            listOf("x"),
            shown(
                """
                defmodule Gen do
                  defmacro named(name) do
                    quote do
                      def unquote(name)(x), do: x
                    end
                  end
                end

                defmodule Named do
                  require Gen

                  Gen.named(:hello)

                  def calls(y), do: hello(y<caret>)
                end
                """.trimIndent()
            )
        )
    }

    /**
     * `Gen.first(:f)` defines `f/1` before `Gen.second(:f)` does, so Elixir names the parameter from `first`'s clause,
     * though `second`'s quote is written first.
     */
    fun testClausesTwoMacrosWriteNameTheParametersInTheOrderTheModuleDefinesThemWithTheExpanderOn() = withTheExpanderOn {
        assertEquals(
            listOf("a"),
            shown(
                """
                defmodule Gen do
                  defmacro second(name) do
                    quote do
                      def unquote(name)(b), do: b
                    end
                  end

                  defmacro first(name) do
                    quote do
                      def unquote(name)(a), do: a
                    end
                  end
                end

                defmodule Named do
                  require Gen

                  Gen.first(:f)
                  Gen.second(:f)

                  def calls(y), do: f(y<caret>)
                end
                """.trimIndent()
            )
        )
    }

    /** An unquote fragment's `def`, only its name unquoted, gives the function it defines its parameters. */
    fun testAnUnquoteFragmentsDefShowsItsParametersWithTheExpanderOn() = withTheExpanderOn {
        assertEquals(
            listOf("x"),
            shown(
                """
                defmodule Fragment do
                  for name <- [:hello] do
                    def unquote(name)(x), do: x
                  end

                  def calls(y), do: hello(y<caret>)
                end
                """.trimIndent()
            )
        )
    }

    /**
     * An unquote fragment's `def`, only its name unquoted, and a `def` written after it are one function, whose parameters
     * Elixir names from both: `b`, as `:x` names none.
     */
    fun testAnUnquoteFragmentsDefAndAWrittenDefShowTheParametersBothNameWithTheExpanderOn() = withTheExpanderOn {
        assertEquals(
            listOf("b"),
            shown(
                """
                defmodule Mixed do
                  for name <- [:f] do
                    def unquote(name)(:x), do: :x
                  end

                  def f(b), do: b

                  def calls(y), do: f(y<caret>)
                end
                """.trimIndent()
            )
        )
    }

    /** Where both name the parameter, the unquote fragment's `def`, the older clause, names it, as Elixir's merge does. */
    fun testAnUnquoteFragmentsDefNamesTheParameterBeforeAWrittenDefWithTheExpanderOn() = withTheExpanderOn {
        assertEquals(
            listOf("a"),
            shown(
                """
                defmodule Mixed do
                  for name <- [:f] do
                    def unquote(name)(a), do: a
                  end

                  def f(b), do: b

                  def calls(y), do: f(y<caret>)
                end
                """.trimIndent()
            )
        )
    }

    /**
     * An unquote fragment's head with a default declares `f/1` and `f/2`; replacing `f/2` leaves `f/1`, which is the same
     * function as the override, so Parameter Info shows its signature once.
     */
    fun testAnUnquoteFragmentsDefaultHeadWhoseFullArityIsOverriddenShowsTheFunctionOnceWithTheExpanderOn() =
        withTheExpanderOn { assertEquals(listOf("a, b \\\\ 1"), shown(overriddenFragmentDefaultHead("f(y<caret>)"))) }

    /** At the overridden arity, as above. */
    fun testACallAtTheOverriddenArityOfAnUnquoteFragmentsDefaultHeadShowsTheFunctionOnceWithTheExpanderOn() =
        withTheExpanderOn { assertEquals(listOf("a, b \\\\ 1"), shown(overriddenFragmentDefaultHead("f(y, 2<caret>)"))) }

    /** As above, for the head with a default a macro's quote writes. */
    fun testAQuotedDefaultHeadWhoseFullArityIsOverriddenShowsTheFunctionOnceWithTheExpanderOn() = withTheExpanderOn {
        assertEquals(
            listOf("a, b \\\\ 1"),
            shown(
                """
                defmodule Gen do
                  defmacro named(name) do
                    quote do
                      def unquote(name)(a, b \\ 1), do: {a, b}
                    end
                  end
                end

                defmodule Overridden do
                  require Gen

                  Gen.named(:f)

                  defoverridable f: 2

                  def f(a, b), do: {b, a}

                  def run(y), do: f(y<caret>)
                end
                """.trimIndent()
            )
        )
    }

    /**
     * As above, for a head whose default's value is unquoted: its arity is known as written, so `f/1` and the override are
     * one function, shown once, by the stub, as an `unquote` among its parameters leaves their names unknown.
     */
    fun testAnUnquoteFragmentsHeadWithAnUnquotedDefaultWhoseFullArityIsOverriddenShowsTheFunctionOnceWithTheExpanderOn() =
        withTheExpanderOn {
            assertEquals(
                listOf("arg1, arg2"),
                shown(
                    """
                    defmodule Overridden do
                      for {name, default} <- [f: 1] do
                        def unquote(name)(a, b \\ unquote(default)), do: {a, b}
                      end

                      defoverridable f: 2

                      def f(a, b), do: {b, a}

                      def run(y), do: f(y<caret>)
                    end
                    """.trimIndent()
                )
            )
        }

    /** `Overridden`, whose unquote fragment's `f(a, b \\ 1)` has its `f/2` overridden, and whose `run(y)` calls [call]. */
    private fun overriddenFragmentDefaultHead(call: String): String =
        """
        defmodule Overridden do
          for name <- [:f] do
            def unquote(name)(a, b \\ 1), do: {a, b}
          end

          defoverridable f: 2

          def f(a, b), do: {b, a}

          def run(y), do: $call
        end
        """.trimIndent()

    /** [shown] at [call] in a module that calls [UNQUOTED_NAME_MACRO_LIB]'s [macro] with [arguments]. */
    private fun shownWithUnquotedNameMacro(macro: String, call: String, arguments: String = ""): List<String> {
        myFixture.addCompiledLibrary(UNQUOTED_NAME_MACRO_LIB, CompiledBuild("CompiledUnquotedNameParameterInfo"))

        return shown(unquotedNameMacroCaller("CompiledUnquotedNameParameterInfo", macro, call, arguments))
    }

    private fun shown(text: String): List<String> = myFixture.parameterInfoSignaturesIn("placed.ex", text)
}
