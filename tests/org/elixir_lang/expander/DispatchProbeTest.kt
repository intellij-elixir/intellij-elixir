package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.UNREQUIRED_MACRO_CALLED_AS_FUNCTION
import org.elixir_lang.psi.ElixirFile

/**
 * Each call the expander dispatches and each import a `quote` traces, in its order, against the events the leg's
 * compiler traces for the same case module body, as [DispatchEvents] normalises them.
 */
class DispatchProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness) { createPsiFile(getTestName(false), it) as ElixirFile }

    fun testDispatchesMatchTheCompilersEvents() {
        probes.assertTracesMatchElixir(probes.expandAll(CASES))
    }

    /** Below 1.13, Elixir reads an unrequired module's macros only if the module happens to be loaded. */
    fun testAnUnrequiredMacroCaptureIsARemoteFunctionFrom113() {
        val expansions = probes.expandAll(listOf(UNREQUIRED_MACRO_CAPTURE))

        if (UNREQUIRED_MACRO_CALLED_AS_FUNCTION.isSufficient(legLevel())) {
            probes.assertTracesMatchElixir(expansions)
        } else {
            val expansion = expansions.cases.single()
            val unported = expansion.outcome as? Expansion.Unported

            assertEquals("&Integer.is_odd/1", unported?.at?.let(expansion::source))
        }
    }

    /** A call of a local macro in a function body stops that body at the call, and up to there agrees with Elixir. */
    fun testLocalMacrosStopAtTheCall() {
        probes.assertMatchesElixirUpToMacro(LOCAL_MACRO_CASES.associateWith { probes.expand(it) })
    }

    private companion object {
        /** Each must expand, and compile in a module body; a call that would raise when the body runs is in an `fn`. */
        val CASES = listOf(
            "x = 1\n_ = x + 1",
            "x = 1\n_ = Integer.to_string(x)",
            "t = {1}\n_ = elem(t, 0)",
            "t = {1}\n_ = abs(elem(t, 0) + 1)",
            "x = -1\n_ = abs(:erlang.abs(x))",
            "m = %{}\n_ = Map.get(m, String.length(\"a\"))",
            "m = %{a: 1}\n_ = m.a",
            "f = fn y -> y end\n_ = f.(1)",
            "l = [1]\n_ = :lists.reverse(l)",
            "_ = fn -> __MODULE__.foo() end",
            "_ = fn -> __ENV__.module.foo() end",
            "x = 1\n_ = -x",
            "_ = String.Chars.to_string(\"a\")",
            "_ = fn -> Kernel.alias(Foo) end",
            "_ = fn -> Kernel.require(Foo) end",
            "_ = fn -> Kernel.import(Foo) end",
            "_ = fn -> Kernel.quote(do: 1) end",
            "_ = fn -> (alias Foo.Bar).baz() end",
            "_ = fn -> (alias Foo).baz() end",
            "_ = fn -> (alias Foo.Bar, warn: false).baz() end",
            "_ = fn -> (import Integer, only: [parse: 1]).baz() end",
            "_ = fn -> (require Integer).baz() end",
            "case 1 do\n  y when y > 0 -> y\nend",
            "case {1} do\n  t when elem(t, 0) == 1 -> t\nend",
            "[1] ++ x = [1, 2]",
            "<<x::+8>> = <<1>>",
            "<<x::+(+8)>> = <<1>>",
            "try do\n  :ok\nrescue\n  _ -> System.stacktrace()\nend",
            "_ = System.stacktrace()",
            "import System, only: [stacktrace: 0]\ntry do\n  :ok\nrescue\n  _ -> stacktrace()\nend",
            "x = 1\n_ = fn -> Integer.parse(\"1\") end\n_ = x",
            "_ = quote(do: is_atom(1) and is_nil(2))",
            "_ = quote(do: &inspect/1)",
            "_ = quote(do: inspect)",
            "l = 3\n_ = quote(line: l, do: is_atom(1))",
            "import String, only: [split: 1, split: 3]\nimport Regex, only: [split: 2]\n_ = quote(do: split)",
            "var!(x) = 1",
            "var!(x, Kernel) = 1",
            "Kernel.var!(z) = 3",
            "_ = alias!(Foo)",
            "_ = &abs/1",
            "_ = &abs(&1)",
            "_ = &Integer.to_string/1",
            "_ = &Integer.to_string(&1)",
            "_ = &Integer.to_string(&1, 2)",
            "_ = &:lists.reverse/1",
            "_ = &NoSuchMod.foo/1",
            "_ = &__MODULE__.foo/0",
            "m = URI\n_ = &m.parse/1",
            "f = fn x -> x end\n_ = &f.(&1)",
            "def f, do: g()\ndef g, do: 1",
            "def f(0), do: 0\ndef f(n), do: f(n - 1)",
            "def f, do: &g/1\ndef g(x), do: x",
            "def f(x), do: &f/1",
            "Kernel.def h, do: 1",
            "defmodule Inner do\n  def f, do: 1\nend",
        )

        val LOCAL_MACRO_CASES = listOf(
            "defmacro m, do: 1\ndef f, do: m()",
            "defmacro m(a, b), do: {a, b}\ndefmacro m(x), do: m(x, x)",
            "defmacro m(x), do: x\ndef f, do: &m/1",
        )

        const val UNREQUIRED_MACRO_CAPTURE = "_ = &Integer.is_odd/1"
    }
}
