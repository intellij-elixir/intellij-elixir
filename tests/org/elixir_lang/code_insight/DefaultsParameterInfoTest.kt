package org.elixir_lang.code_insight

import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.documentation.CompiledBuild
import org.elixir_lang.documentation.OV_FULL_GEN
import org.elixir_lang.documentation.OV_FULL_GEN_LIB
import org.elixir_lang.documentation.OV_FULL_LIB
import org.elixir_lang.documentation.PLACED_LIB
import org.elixir_lang.documentation.addCompiledLibrary

/**
 * A function with default arguments is one definition, so Parameter Info shows its head as written, once, whichever
 * arities the call or an `import` brings in.
 */
class DefaultsParameterInfoTest : ExpanderSettingTestCase() {
    private val pairHead = """
        defmodule Definer do
          def pair(a \\ nil, b \\ nil)
          def pair(a, b) when is_nil(a), do: {a, b}
          def pair(a, b), do: {a, b}

          def local(a, b), do: pair(LOCAL)
        end
    """.trimIndent()

    private val snoc = """
        defmodule Definer do
          def snoc(q, x \\ nil), do: {q, x}
          defmacro snoc_macro(q, x \\ nil), do: {q, x}
        end
    """.trimIndent()

    fun testALocalCallShowsTheBodilessHeadAlone() =
        assertEquals(listOf(PAIR_HEAD), shown(pairHead.replace("pair(LOCAL)", "pair(a<caret>, b)")))

    fun testACallThroughImportExceptShowsTheBodilessHeadAlone() =
        assertEquals(
            listOf(PAIR_HEAD),
            shown(
                pairHead.replace("pair(LOCAL)", "pair(a, b)") +
                    "\n\ndefmodule Caller do\n  import Definer, except: [pair: 1]\n\n  def calls(a), do: pair(a<caret>)\nend\n"
            )
        )

    fun testACallThroughImportOnlyOfALowerArityShowsTheBodilessHeadAlone() =
        assertEquals(
            listOf(PAIR_HEAD),
            shown(
                pairHead.replace("pair(LOCAL)", "pair(a, b)") +
                    "\n\ndefmodule Caller do\n  import Definer, only: [pair: 1]\n\n  def calls(a), do: pair(a<caret>)\nend\n"
            )
        )

    fun testARemoteCallShowsTheDefaultsOfAFunctionWithNoHead() =
        assertEquals(listOf(SNOC_HEAD), shown("$snoc\n\ndefmodule Caller do\n  def calls(a), do: Definer.snoc(a<caret>)\nend\n"))

    fun testACallThroughImportOnlyOfALowerArityShowsTheDefaults() =
        assertEquals(
            listOf(SNOC_HEAD),
            shown("$snoc\n\ndefmodule Caller do\n  import Definer, only: [snoc: 1]\n\n  def calls(a), do: snoc(a<caret>)\nend\n")
        )

    fun testAMacroShowsItsDefaults() =
        assertEquals(
            listOf(SNOC_HEAD),
            shown(
                "$snoc\n\ndefmodule Caller do\n  require Definer\n\n  def calls(a), do: Definer.snoc_macro(a<caret>)\nend\n"
            )
        )

    /** A head whose arity is open shares no fixed arity, so it is not the function of the one its minimum names. */
    fun testAnOpenHeadIsNotTheFunctionItsMinimumNames() =
        assertEquals(
            listOf("a", "a, unquote_splicing(rest)"),
            shown(
                """
                defmodule Open do
                  def f(a), do: a
                  def f(a, unquote_splicing(rest)), do: [a | rest]

                  def calls(a), do: f(a<caret>)
                end
                """.trimIndent()
            )
        )

    /** Two imported functions of one name are two definitions, though their heads reach the same largest arity. */
    fun testFunctionsOfTheSameNameImportedFromTwoModulesAreBothShown() =
        assertEquals(
            listOf("first, second", "left, opts \\\\ []"),
            shown(
                """
                defmodule A do
                  def foo(left, opts \\ []), do: {left, opts}
                end

                defmodule B do
                  def foo(first, second), do: {first, second}
                end

                defmodule Caller do
                  import A, only: [foo: 1]
                  import B, only: [foo: 2]

                  def calls(x), do: foo(x<caret>)
                end
                """.trimIndent()
            ).sorted()
        )

    /** Clauses with no head: the defaults one of them declares are shown, wherever the call stands. */
    fun testACallInALaterClauseStillShowsTheDefaults() =
        assertEquals(
            listOf("list, acc \\\\ []"),
            shown(
                """
                defmodule Rec do
                  def f([], acc \\ []), do: acc
                  def f([x | xs], acc), do: f(xs<caret>, [x | acc])
                end
                """.trimIndent()
            )
        )

    /**
     * A `def` written at an arity the defaults of an overridden function had generated is a function of its own, as
     * `Docs` records `o(a)` beside `o(a, b \\ 1)`.
     */
    fun testADefWrittenAtAnOverriddenFunctionsDefaultArityIsShownWithTheExpanderOn() = withTheExpanderOn {
        assertEquals(
            listOf("a", "a, b \\\\ 1"),
            shown("$OV_FULL_GEN\n\ndefmodule Caller do\n  def calls(x), do: OvFullGen.o(x<caret>)\nend\n").sorted()
        )
    }

    fun testADefWrittenAtACompiledOverriddenFunctionsDefaultArityIsShownWithTheExpanderOn() = withTheExpanderOn {
        assertEquals(listOf("a", "a, b \\\\ 1"), shownAtCompiledOvFullGen(CompiledBuild("CompiledOvFullGenParameterInfo")))
    }

    /** As above, with no debug info: the decompiled heads write the defaults the `Docs` chunk records, each once. */
    fun testADefWrittenAtADocsOnlyCompiledOverriddenFunctionsDefaultArityIsShownWithTheExpanderOn() = withTheExpanderOn {
        assertEquals(
            listOf("a", "a, b \\\\ 1"),
            shownAtCompiledOvFullGen(CompiledBuild("CompiledOvFullGenDocsOnlyParameterInfo", debugInfo = false))
        )
    }

    /**
     * An override of the arity a compiled head's defaults key is one function with the head, as `Docs` records only
     * `o(a, b \\ 1)`, though the debug info keeps the generated `o/1` as a definition of its own.
     */
    fun testAnOverrideOfACompiledDefaultHeadsFullArityIsOneFunction() {
        myFixture.addCompiledLibrary(OV_FULL_LIB, CompiledBuild("CompiledOvFullParameterInfo"))

        assertEquals(
            listOf("a, b \\\\ 1"),
            shown("defmodule Caller do\n  def calls(x), do: CompiledOvFullParameterInfo.o(x<caret>)\nend\n")
        )
    }

    fun testAnOverrideOfACompiledDefaultHeadsFullArityIsOneFunctionWithTheExpanderOn() =
        withTheExpanderOn(::testAnOverrideOfACompiledDefaultHeadsFullArityIsOneFunction)

    /** A head a compiled macro places at an arity a default of the macro module's own `f` adds is a function of its own. */
    fun testAHeadACompiledMacroPlacedIsShownAtItsOwnArityWithTheExpanderOn() = withTheExpanderOn {
        myFixture.addCompiledLibrary(PLACED_LIB, CompiledBuild("CompiledPlacedParameterInfo"))

        assertEquals(
            listOf("arg1"),
            shown("defmodule Placed do\n  use CompiledPlacedParameterInfo\n\n  def calls(y), do: f(y<caret>)\nend\n")
        )
    }

    /** [shown], sorted, at `o(x)` called on [OV_FULL_GEN_LIB] compiled as [build]. */
    private fun shownAtCompiledOvFullGen(build: CompiledBuild): List<String> {
        myFixture.addCompiledLibrary(OV_FULL_GEN_LIB, build)

        return shown("defmodule Caller do\n  def calls(x), do: ${build.name}.o(x<caret>)\nend\n").sorted()
    }

    private fun shown(text: String): List<String> = myFixture.parameterInfoSignaturesIn("defaults.ex", text)

    private companion object {
        const val PAIR_HEAD = "a \\\\ nil, b \\\\ nil"
        const val SNOC_HEAD = "q, x \\\\ nil"
    }
}
