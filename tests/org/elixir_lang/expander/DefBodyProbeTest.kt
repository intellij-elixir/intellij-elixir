package org.elixir_lang.expander

import org.elixir_lang.psi.ElixirFile

/**
 * The bodies of a module's definitions and of the modules nested in it, compiled on the leg's Elixir: each statement's
 * probe sees the env of the line that defines the body, with its own function, and the bodies are expanded in
 * Elixir's order, the module body first, then each definition and nested module as the body defines them.
 */
class DefBodyProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness) { createPsiFile(getTestName(false), it) as ElixirFile }

    fun testDefinitionBodies() {
        val expansions = CASES.associateWith { probes.expand(it) }

        assertEquals("", expansions.filterValues { it.ended is ExpansionResult.Ended.Stopped }.keys.joinToString("\n"))

        probes.assertMatchesElixir(expansions)
    }

    private companion object {
        val CASES = listOf(
            "def f(a) do\n  b = a\n  {a, b}\nend",
            "defp f(a) do\n  b = a\n  {a, b}\nend",
            "defmacro m(a) do\n  b = a\n  {a, b}\nend",
            "defmacrop m(a) do\n  b = a\n  {a, b}\nend",
            "def f(a), do: a",
            "def f(a) when is_integer(a), do: a",
            "def f(a)\ndef f(a), do: a",
            "def f(a), do: a\ndef f(a, b), do: {a, b}",
            "def f(1), do: 1\ndef f(a), do: a",
            // Directives after a definition aren't seen in its body.
            "alias String.Chars, as: SC\nrequire Integer\nimport List, only: [first: 1]\n" +
                "def f do\n  :ok\nend\n" +
                "alias URI, as: U\nrequire Record\nimport Enum, only: [count: 1]\n" +
                "def g do\n  :ok\nend",
            "def f(a, b \\\\ 1) do\n  {a, b}\nend",
            "def f(a \\\\ abs(-1), b \\\\ 2), do: {a, b}",
            "defmodule Inner do\n  x = 1\n  def g do\n    :ok\n  end\nend",
            // `body1 body2 f inner_body h g`: the module body whole, then each definition and nested module in order.
            "_ = :body1\n" +
                "def f do\n  :f\nend\n" +
                "defmodule Inner do\n  _ = :inner_body\n  def h do\n    :h\n  end\nend\n" +
                "def g do\n  :g\nend\n" +
                "_ = :body2",
            "defmacro m do\n  try do\n    :ok\n  rescue\n    _ -> :error\n  end\nend",
            "def f do\n  try do\n    :ok\n  rescue\n    _ -> __STACKTRACE__\n  end\nend",
            "defmacro m do\n  __CALLER__\nend",
            // A module-body variable isn't a variable in a definition's body.
            "x = 1\ndef f do\n  x\nend",
            "def f(a) do\n  b = __STACKTRACE__\n  {a, b, undefined_q}\nend\ndef g, do: :g",
            // An `unquote` inside a `quote` isn't a fragment, even as a call's name.
            "def f(x), do: quote(do: unquote(x))",
            "def g(f), do: quote(do: unquote(f)(1))",
            "def f, do: unquote([:a, {:b, 1}])",
            // An `unquote` in a `quote`'s options, without and with what makes the definition one with fragments.
            "def f do\n  quote bind_quoted: [y: unquote(:a)] do\n    y\n  end\nend",
            "def unquote(:f)() do\n  quote bind_quoted: [y: unquote(:a)] do\n    y\n  end\nend",
            "def f(g) do\n  quote(do: unquote(g)(1))\n  quote bind_quoted: [y: unquote(:a)] do\n    y\n  end\nend",
        )
    }
}
