package org.elixir_lang.inspection

import org.elixir_lang.PlatformTestCase

/**
 * What the Elixir references inspection suggests for a remote call to a name its module does not export at any arity,
 * as the compiler's "Did you mean" does: the module's functions and macros whose names are near the name called.
 */
class ReferencesDidYouMeanTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(References::class.java)
    }

    fun testAMisspelledRemoteCallIsOfferedTheNameItIsNearTo() {
        assertEquals(
            "$UNRESOLVED. Did you mean: snoc/2?",
            reportOn(
                "Mr.sno(q, x)",
                "defmodule Mr do\n  def snoc(q, x), do: [x | q]\nend\n\ndefmodule U do\n  def run(q, x), do: Mr.sno(q, x)\nend\n"
            )
        )
    }

    fun testEveryArityOfANearNameIsOffered() {
        assertEquals(
            "$UNRESOLVED. Did you mean: snoc/1, snoc/2?",
            reportOn(
                "Mr.sno(q, x)",
                "defmodule Mr do\n  def snoc(q, x \\\\ 1), do: [x | q]\nend\n\ndefmodule U do\n  def run(q, x), do: Mr.sno(q, x)\nend\n"
            )
        )
    }

    /** The compiler lists a module's macros before its functions, so a macro is kept where the cut falls among equals. */
    fun testAMacroIsKeptAheadOfFunctionsAtTheCut() {
        val functions = listOf("snoca", "snocb", "snocc", "snocd", "snoce").joinToString("") { "  def $it(q), do: q\n" }

        assertEquals(
            "$UNRESOLVED. Did you mean: snoca/1, snocb/1, snocc/1, snocd/1, snocz/1?",
            reportOn(
                "Mr.snocx(q)",
                "defmodule Mr do\n$functions  defmacro snocz(q), do: q\nend\n\ndefmodule U do\n  def run(q), do: Mr.snocx(q)\nend\n"
            )
        )
    }

    fun testANameBelowTheThresholdIsNotOffered() {
        assertEquals(
            UNRESOLVED,
            reportOn(
                "Mr.to_list(q)",
                "defmodule Mr do\n  def to_string(q), do: q\nend\n\ndefmodule U do\n  def run(q), do: Mr.to_list(q)\nend\n"
            )
        )
    }

    fun testOnlyTheNearestFiveAreOffered() {
        val functions = listOf("snoc", "snoca", "snocb", "snocc", "snocd", "snoce", "snocf")
            .joinToString("") { "  def $it(q), do: q\n" }

        assertEquals(
            "$UNRESOLVED. Did you mean: snoc/1, snoca/1, snocb/1, snocc/1, snocd/1?",
            reportOn("Mr.snocx(q)", "defmodule Mr do\n$functions\nend\n\ndefmodule U do\n  def run(q), do: Mr.snocx(q)\nend\n")
        )
    }

    /** `priv/2` is private, so the call to `priv/1` names nothing the module exports. */
    fun testAPrivateFunctionAtAnotherArityIsOfferedTheNamesItIsNearTo() {
        assertEquals(
            "$INVALID. Did you mean: prim/1?",
            reportOn(
                "P.priv(1)",
                "defmodule P do\n  def prim(a), do: a\n  defp priv(a, b), do: {a, b}\nend\n\ndefmodule U do\n  def run, do: P.priv(1)\nend\n"
            )
        )
    }

    fun testACaptureIsOfferedTheNameItIsNearTo() {
        assertEquals(
            "$UNRESOLVED. Did you mean: snoc/2?",
            reportOn(
                "&Mr.sno/2",
                "defmodule Mr do\n  def snoc(q, x), do: [x | q]\nend\n\ndefmodule U do\n  def run, do: &Mr.sno/2\nend\n"
            )
        )
    }

    fun testALocalCallIsOfferedNothing() {
        assertEquals(
            UNRESOLVED,
            reportOn("sno(q, x)", "defmodule Mr do\n  def snoc(q, x), do: [x | q]\n\n  def run(q, x), do: sno(q, x)\nend\n")
        )
    }

    fun testANameFromAnUnquoteIsOfferedNothing() {
        assertEquals(
            UNRESOLVED,
            reportOn(
                "Qualifier.unquote(v)(1, 2, 3, 4, 5, 6, 7, 8, 9)",
                "defmodule Qualifier do\n  def f(a, b), do: {a, b}\n  def unquot(a), do: a\nend\n\n" +
                    "defmodule U do\n  defmacro m(v) do\n    quote do\n      Qualifier.unquote(v)(1, 2, 3, 4, 5, 6, 7, 8, 9)\n    end\n  end\nend\n"
            )
        )
    }

    fun testAModuleThatIsNotDefinedIsOfferedNothing() {
        assertEquals(
            UNRESOLVED,
            reportOn("Nope.sno(q, x)", "defmodule U do\n  def run(q, x), do: Nope.sno(q, x)\nend\n")
        )
    }

    private fun reportOn(call: String, text: String): String {
        myFixture.configureByText("m.ex", text)

        val reports = myFixture.doHighlighting()
            .filter { it.inspectionToolId == "ElixirReferences" && it.text == call }
            .map { it.description }

        return reports.singleOrNull() ?: throw AssertionError("Expected one report on `$call`, found $reports")
    }

    private companion object {
        const val UNRESOLVED = "Does not resolve to anything"
        const val INVALID = "Only resolves to invalid results"
    }
}
