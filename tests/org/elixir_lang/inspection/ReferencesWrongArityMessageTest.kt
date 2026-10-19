package org.elixir_lang.inspection

import org.elixir_lang.PlatformTestCase

/**
 * The Elixir references inspection's message for a call whose name is declared, but not at the call's arity: it names
 * the arities the call could mean, as the compiler's "Did you mean" does.
 */
class ReferencesWrongArityMessageTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(References::class.java)
    }

    fun testARemoteCallNamesTheOneArityItsNameIsDefinedAt() {
        assertEquals(
            INVALID + "snoc/2",
            reportOn("Mr.snoc(q)", "defmodule Mr do\n  def snoc(q, x), do: [x | q]\nend\n\ndefmodule U do\n  def run(q), do: Mr.snoc(q)\nend\n")
        )
    }

    fun testALocalCallNamesTheArityToo() {
        assertEquals(
            INVALID + "snoc/2",
            reportOn("snoc(q)", "defmodule Mr do\n  def snoc(q, x), do: [x | q]\n\n  def run(q), do: snoc(q)\nend\n")
        )
    }

    fun testADefaultArgumentNamesEachArityItAccepts() {
        assertEquals(
            INVALID + "snoc/1, snoc/2",
            reportOn("snoc()", "defmodule Mr do\n  def snoc(q, x \\\\ 1), do: [x | q]\n\n  def run, do: snoc()\nend\n")
        )
    }

    fun testSeparateClausesNameEachOfTheirArities() {
        assertEquals(
            INVALID + "snoc/2, snoc/4",
            reportOn(
                "snoc()",
                "defmodule Mr do\n  def snoc(q, x), do: [x | q]\n  def snoc(a, b, c, d), do: [a, b, c, d]\n\n  def run, do: snoc()\nend\n"
            )
        )
    }

    fun testANameThatOnlySharesAPrefixIsNotNamed() {
        assertEquals(
            INVALID + "snoc/2",
            reportOn(
                "snoc()",
                "defmodule Mr do\n  def snoc(q, x), do: [x | q]\n  def snoc_all(a, b, c), do: [a, b, c]\n\n  def run, do: snoc()\nend\n"
            )
        )
    }

    fun testAHeadOpenFromAnArityIsNamedWithAPlus() {
        assertEquals(
            INVALID + "foo/1+",
            reportOn("foo()", "defmodule Mr do\n  def foo(a, unquote_splicing(rest)), do: [a | rest]\n\n  def run, do: foo()\nend\n")
        )
    }

    fun testADelegationIsNamedAtItsHeadsArityNotItsTargets() {
        assertEquals(
            INVALID + "foo/1",
            reportOn(
                "foo(1, 2)",
                "defmodule Target do\n  def foo(a), do: a\n  def foo(a, b, c), do: {a, b, c}\nend\n\n" +
                    "defmodule Mr do\n  defdelegate foo(a), to: Target\n\n  def run, do: foo(1, 2)\nend\n"
            )
        )
    }

    fun testADelegationIgnoresItsTargetsDefaultArguments() {
        assertEquals(
            INVALID + "foo/1",
            reportOn(
                "foo(1, 2, 3)",
                "defmodule Target do\n  def foo(a, b \\\\ 1), do: {a, b}\nend\n\n" +
                    "defmodule Mr do\n  defdelegate foo(a), to: Target\n\n  def run, do: foo(1, 2, 3)\nend\n"
            )
        )
    }

    fun testAnImportOnlyNamesTheArityItBringsIn() {
        assertEquals(
            INVALID + "snoc/2",
            reportOn(
                "snoc()",
                "defmodule Mr do\n  def snoc(q, x), do: [x | q]\n  def snoc(q, x, y), do: [x, y | q]\nend\n\n" +
                    "defmodule U do\n  import Mr, only: [snoc: 2]\n\n  def run, do: snoc()\nend\n"
            )
        )
    }

    fun testTwoSpellingsOfOneNameAreNamedOnce() {
        assertEquals(
            INVALID + "snoć/2",
            reportOn(
                "snoć()",
                "defmodule Mr do\n  def snoć(a, b), do: [a | b]\n  def snoć(a, b), do: [a, b]\n\n  def run, do: snoć()\nend\n"
            )
        )
    }

    fun testADecomposedDeclarationIsNamedPrecomposed() {
        assertEquals(
            INVALID + "snoć/2",
            reportOn(
                "snoć()",
                "defmodule Mr do\n  def snoć(a, b), do: [a | b]\n\n  def run, do: snoć()\nend\n"
            )
        )
    }

    fun testASpecialFormIsNamedAtTheArityItsDeclarationWrites() {
        val description = reportOn(
            "alias(A, B, C)",
            "defmodule Kernel.SpecialForms do\n  defmacro alias(module, opts), do: nil\nend\n\ndefmodule U do\n  def run, do: alias(A, B, C)\nend\n"
        )

        assertTrue(description, description.startsWith(INVALID) && "alias/2" in description)
    }

    fun testACaptureNamesTheArityItsNameIsDefinedAt() {
        assertEquals(
            INVALID + "snoc/2",
            reportOn("&Mr.snoc/1", "defmodule Mr do\n  def snoc(q, x), do: [x | q]\nend\n\ndefmodule U do\n  def run, do: &Mr.snoc/1\nend\n")
        )
    }

    fun testASpecHeadNamesTheAritiesDefined() {
        assertEquals(
            INVALID + "f/2",
            reportOn("f(integer)", "defmodule M do\n  @spec f(integer) :: integer\n  def f(a, b), do: a + b\nend\n")
        )
    }

    fun testAnUndeclaredNameKeepsTheGenericMessage() {
        assertEquals(
            UNRESOLVED,
            reportOn("nope()", "defmodule Mr do\n  def snoc(q, x), do: [x | q]\n\n  def run, do: nope()\nend\n")
        )
    }

    fun testAQualifiedUnquoteNamesNothing() {
        assertEquals(
            UNRESOLVED,
            reportOn(
                "Qualifier.unquote(v)(1, 2, 3, 4, 5, 6, 7, 8, 9)",
                "defmodule Qualifier do\n  def f(a, b), do: {a, b}\nend\n\n" +
                    "defmodule U do\n  defmacro m(v) do\n    quote do\n      Qualifier.unquote(v)(1, 2, 3, 4, 5, 6, 7, 8, 9)\n    end\n  end\nend\n"
            )
        )
    }

    fun testARemoteCallNamesAtMostFiveAritiesAsTheCompilerDoes() {
        assertEquals(
            INVALID + "f/1, f/2, f/3, f/4, f/5",
            reportOn(
                "Six.f()",
                "defmodule Six do\n  def f(a, b \\\\ 1, c \\\\ 2, d \\\\ 3, e \\\\ 4, g \\\\ 5), do: [a, b, c, d, e, g]\nend\n\n" +
                    "defmodule U do\n  def run, do: Six.f()\nend\n"
            )
        )
    }

    fun testALocalCallNamesEveryArity() {
        assertEquals(
            INVALID + "f/1, f/2, f/3, f/4, f/5, f/6",
            reportOn(
                "f()",
                "defmodule Six do\n  def f(a, b \\\\ 1, c \\\\ 2, d \\\\ 3, e \\\\ 4, g \\\\ 5), do: [a, b, c, d, e, g]\n\n  def run, do: f()\nend\n"
            )
        )
    }

    fun testANameThatNeedsQuotingIsPrintedQuoted() {
        assertEquals(
            INVALID + "\"my fun\"/2",
            reportOn(
                "Mr.\"my fun\"(1)",
                "defmodule Mr do\n  def unquote(:\"my fun\")(a, b), do: {a, b}\nend\n\ndefmodule U do\n  def run, do: Mr.\"my fun\"(1)\nend\n"
            )
        )
    }

    fun testMacrosAreNamedBeforeFunctions() {
        assertEquals(
            INVALID + "m/2, m/1",
            reportOn(
                "Mr.m()",
                "defmodule Mr do\n  def m(a), do: a\n  defmacro m(a, b), do: {a, b}\nend\n\ndefmodule U do\n  def run, do: Mr.m()\nend\n"
            )
        )
    }

    fun testAGuardIsNamedBeforeTheFunctionsOfItsName() {
        assertEquals(
            INVALID + "g/2, g/1",
            reportOn(
                "G.g()",
                "defmodule G do\n  def g(a), do: a\n  defguard g(a, b) when a == b\nend\n\ndefmodule U do\n  def run, do: G.g()\nend\n"
            )
        )
    }

    /** A `@callback` is a contract, not a definition, so nothing is defined at its arity. */
    fun testACallbackIsNotNamedAsAnArityTheCallCouldMean() {
        assertEquals(
            "Only resolves to invalid results",
            reportOn("cb(1, 2)", "defmodule M do\n  @callback cb(term) :: term\n\n  def run, do: cb(1, 2)\nend\n")
        )
    }

    /** The description of the one report on [call] in [text]. */
    private fun reportOn(call: String, text: String): String {
        myFixture.configureByText("m.ex", text)

        val reports = myFixture.doHighlighting()
            .filter { it.inspectionToolId == "ElixirReferences" && it.text == call }
            .map { it.description }

        return reports.singleOrNull() ?: throw AssertionError("Expected one report on `$call`, found $reports")
    }

    private companion object {
        const val UNRESOLVED = "Does not resolve to anything"
        const val INVALID = "Only resolves to invalid results; defined as "
    }
}
