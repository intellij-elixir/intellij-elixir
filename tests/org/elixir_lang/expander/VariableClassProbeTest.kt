package org.elixir_lang.expander

import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.ElixirFile
import java.io.File

/**
 * After each statement of a case module body, and at each identity probe in its patterns, the expander's variables
 * fall into the same classes as Elixir's, and its env's other fields equal Elixir's, on the leg's Elixir. A case the
 * expander reports an error for is compared as an error. One that reaches an unported clause at any statement is
 * counted as uncovered.
 */
class VariableClassProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness) { createPsiFile(getTestName(false), it) as ElixirFile }

    fun testTheVariableOraclesTopLevelCases() {
        val cases = File(ORACLE_CASES)
            .listFiles { file -> file.name.startsWith("top__") && file.name.endsWith(".exs") }
            .orEmpty()
            .sortedBy { it.name }
            .associate { it.name to it.readLines().drop(HEADER_LINES).joinToString("\n") }
        val expansions = cases.mapValues { (_, body) -> probes.expand(body) }
        val covered = expansions.filterValues { it.outcome is Expansion.Expanded }
        val uncoveredAt = expansions.filterKeys { it !in covered }.values
            .map { expansion ->
                when (val outcome = expansion.outcome) {
                    is Expansion.Unported -> describe(outcome.at)
                    is Expansion.Error -> "error ${outcome.kind}"
                    is Expansion.Expanded -> error("unreachable")
                }
            }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }

        println("covered ${covered.size} of ${cases.size}")
        println("uncovered, by the first unported node: " + uncoveredAt.joinToString { "${it.key} ${it.value}" })
        println("covered cases: " + covered.keys.joinToString(" "))

        probes.assertMatchesElixir(expansions.filterValues { it.outcome !is Expansion.Unported })
        assertTrue("covered ${covered.size} of ${cases.size}, below $FLOOR", covered.size >= FLOOR)
    }

    fun testOwnCases() {
        val expansions = OWN_CASES.associateWith { probes.expand(it) }

        assertEquals(
            "",
            expansions.filterValues { it.outcome !is Expansion.Expanded }.keys.joinToString("\n")
        )

        probes.assertMatchesElixir(expansions)
    }

    /** A call as its name and arity, anything else as its kind. */
    private fun describe(node: ElixirAst): String =
        when {
            node is ElixirAst.Call && node.callee is ElixirAst.Literal.Atom ->
                node.callee.name + (node.arguments?.let { "/${it.size}" } ?: " (variable)")
            node is ElixirAst.Call -> "remote or anonymous call"
            else -> node.javaClass.simpleName
        }

    private companion object {
        const val ORACLE_CASES = "testData/org/elixir_lang/model/psi/variable/oracle/cases"

        /** The comment lines `generate.exs` writes at the top of each case. */
        const val HEADER_LINES = 2

        /** The covered count measured when these clauses were ported; it may only grow. */
        const val FLOOR = 260

        val OWN_CASES = listOf(
            "()",
            "(a = 1; b = a)\na",
            "{a, b, c} = {1, 2, 3}",
            "[a | b] = [1, 2]",
            "x = y = 1",
            "{x, x} = {1, 1}",
            "_ = 1",
            "key = :k\n%{^key => v} = %{key => 1}",
            "x = 1\n{x, y} = {x, 2}",
            "y = 1\n(x = y) = 1",
            "{a = b, c} = {1, 2}",
            "t = {x = 1, 2}",
            "l = [x = 1]",
            "t = [{x, x} = {1, 1}]",
            "t = {{x, x} = {1, 1}, 2}\ny = 1",
            "t = [{x, x, _} = {1, 1, 2}]",
            // Clauses: each body and guard, and after the construct
            "x = 1\ncase x do\n1 -> y = 2\nend",
            "x = 1\ncase x, do: (1 -> y = 2)",
            "case x = 1 do\n_ -> y = x\nend\nz = x",
            "x = true\ncase x do\ny when y -> z = y\nend",
            "case 1 do\n1 -> a = 1\n_ -> b = 2\nend\nc = 3",
            "case {1, 2} do\n{a, b} when b -> {a, b}\n{c, _} -> c\nend",
            "x = 1\ncond do\nx -> y = x\nend",
            "cond do\na = 1 -> b = a\ntrue -> c = 2\nend",
            "receive do\nm -> n = m\nafter\n0 -> t = 1\nend",
            "receive do\nafter\n(t = 0) -> u = t\nend",
            "try do\nd = 1\nrescue\ne -> e\ncatch\nk, v -> v\nelse\nr -> r\nafter\naf = 1\nend",
            "x = 1\ntry do\ny = x\nafter\nz = x\nend",
            "try do: (a = 1), after: (b = 2)",
            "try do: a = 1, after: b = 2",
            "try do\n1\nrescue\ne in [:\"Elixir.ArgumentError\"] -> e\nend",
            "try do\n1\nrescue\ne in :\"Elixir.ArgumentError\" -> e\nend",
            "try do\n1\nrescue\n[:\"Elixir.ArgumentError\"] -> 1\nend",
            "try do\n1\nrescue\ne in _ -> e\nend",
            "try do\n1\ncatch\nk -> k\nend",
            "try do\n1\ncatch\nk, v when v -> 1\nend",
            "try do\n1\ncatch\nv when v -> 1\nend",
            "try do\n1\nrescue\n_ -> s = __STACKTRACE__\nend",
            "try do\n1\ncatch\n_ -> s = __STACKTRACE__\nend",
            "try do\n1\nrescue\n_ -> fn -> __STACKTRACE__ end\nend",
            "try do\n1\nrescue\n_ ->\ntry do\n2\nrescue\n_ -> s = __STACKTRACE__\nend\nend",
            "try do\n1\ncatch\n_ ->\ntry do\n2\nrescue\n_ -> s = __STACKTRACE__\nend\nend",
            "x = 1\nf = fn a -> b = {a, x} end",
            "a = 1\nf = fn a -> a end",
            "f = fn a when a -> a end",
            "f = fn\n1 -> a = 1\n_ -> b = 2\nend\nc = 3",
            "f = fn\na, b when a -> 1\nc, d -> 2\nend",
            "f = fn -> fn x -> y = x end end",
            // The version each construct takes from 1.20, against the repeated variable's write on 1.18 and 1.19
            "t = [{x, x} = {1, 1}, case 1 do 1 -> 1 end]",
            "t = [{x, x} = {1, 1}, cond do true -> 1 end]",
            "t = [{x, x} = {1, 1}, receive do after 0 -> 1 end]",
            "t = [{x, x} = {1, 1}, try do 1 after 2 end]",
            "t = [{x, x} = {1, 1}, fn -> 1 end]",
            "t = [{x, x} = {1, 1}, with(1 <- 1, do: 1)]",
            "t = [{x, x} = {1, 1}, for(1 <- [1], do: 1)]",
            "t = [{x, x} = {1, 1}, (_ = for(a <- [1], do: a); 1)]",
            "t = [{x, x} = {1, 1}, &[1, &1]]",
            // with: each pattern for the clauses after it and the do, else from before the with
            "x = 1\nwith {:ok, y} <- {:ok, x} do\nz = y\nend",
            "with {:ok, a} <- {:ok, 1},\n{:ok, b} <- {:ok, a} do\n{a, b}\nend",
            "with a = 1,\n{:ok, b} <- {:ok, a} do\n{a, b}\nend",
            "with a <- 1 do\na\nend",
            "with {:ok, a} <- {:ok, b = 1} do\na\nend",
            "with {:ok, a} <- {:ok, 1},\n^a <- 1 do\na\nend",
            "with x when x <- true do\nx\nend",
            "with {:ok, a} <- 1 do\na\nelse\n{:error, b} -> b\nc -> c\nend\nd = 1",
            "with x <- 1, do: x, else: (y -> y)",
            // for: options from before it, generators and filters for those after them and the do
            "for x <- [1, 2], do: y = x",
            "for x <- [1],\ny <- [x], do: {x, y}",
            "for x <- [1],\ny = x, do: y",
            "for x <- (y = [1]), do: x",
            "for x <- [1], into: (y = []), do: {x, y}",
            "for x <- i, into: (i = []), do: x",
            "for x <- [1],\ny = x, into: (i = []) do\ny\nend",
            "y = [1]\nfor ^y <- [[1]], do: y",
            "for x when x <- [true], do: x",
            "for <<c <- \"ab\">>, do: c",
            "for <<a, b <- \"ab\">>, do: {a, b}",
            "for <<x::binary-size(1) <- \"ab\">>, do: x",
            "for x <- [1], reduce: 0 do\n^x -> x\nacc -> {acc, x}\nend",
            "for x <- [1], uniq: true, do: x",
            "for x <- [1], do: x\ny = 1",
            "_ = for x <- [1], do: x\ny = 1",
            "y = 1\n_ = for x <- [1], do: x",
            // &: the fn a capture that needs no lookup is rewritten to
            "&[&1]",
            "&[&2, &1, &2]",
            "&{&1, 1}",
            "&{&1, &2}",
            "&{&1}",
            "&<<&1>>",
            "&%{a: &1}",
            "& &1",
            "x = 1\n&{&1, x}",
            "&[y = &1]",
            "&(fn x -> &1 end)",
            "m = :lists\n&m.reverse/1",
            "m = :lists\n&m.reverse(&1)",
        )
    }
}
