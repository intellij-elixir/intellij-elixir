package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.NULLARY_RANGE
import org.elixir_lang.language_level.ElixirLanguageFeature.STEP_OPERATOR
import org.elixir_lang.psi.ElixirFile

/**
 * The `Kernel` macros that control flow and operators are built from, compiled in module bodies, function bodies,
 * guards and matches on the leg's Elixir: at each probe the env, the variables and the hygiene counters taken, then
 * the errors a macro raises and, where the module compiles, the dispatch events.
 */
class KernelSummaryProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness, accounting = true) {
        createPsiFile(getTestName(false), it) as ElixirFile
    }

    fun testModuleBodies() = assertMatchesElixir(MODULE_BODY_CASES)

    fun testFunctionBodies() = assertMatchesElixir(FUNCTION_BODY_CASES.map { "def f(x, y, a, b) do\n  $it\nend" })

    fun testGuards() = assertMatchesElixir(GUARD_CASES.map { "x = 1\n_ = case x do\n  z when $it -> z\n  _ -> 0\nend" })

    fun testMatches() = assertMatchesElixir(MATCH_CASES)

    /** Each macro that raises while it expands is that error, where Elixir reports it. */
    fun testRaises() = assertMatchesElixir(RAISE_CASES)

    /** Ranges with a step, from [STEP_OPERATOR], and `..` alone, from [NULLARY_RANGE], which earlier parsers reject. */
    fun testStepsAndFullRanges() {
        val level = legLevel()
        val steps = if (STEP_OPERATOR.isSufficient(level)) STEP_CASES else emptyList()
        val full = if (NULLARY_RANGE.isSufficient(level)) FULL_RANGE_CASES else emptyList()

        if (steps.isNotEmpty() || full.isNotEmpty()) {
            assertMatchesElixir(steps + full)
        }
    }

    /**
     * Compares each of [bodies], none of which stops where the expander can't follow, with Elixir, then the dispatch
     * events of those that expand.
     */
    private fun assertMatchesElixir(bodies: List<String>) {
        val expansions = bodies.associateWith { probes.expand(it) }

        assertEquals(
            "",
            expansions.filterValues { it.outcome is Expansion.Unported || it.outcome is Expansion.Opaque }
                .map { (body, expansion) -> "${body.replace("\n", "; ")}: ${expansion.outcome}" }
                .joinToString("\n"),
        )
        printed { probes.assertMatchesElixir(expansions) }

        val expanded = bodies.filter { expansions.getValue(it).outcome is Expansion.Expanded }

        if (expanded.isNotEmpty()) {
            printed { probes.assertTracesMatchElixir(probes.expandAll(expanded)) }
        }
    }

    private companion object {
        /** Each must compile in a module body; a call that would raise when the body runs is in an `fn`. */
        val MODULE_BODY_CASES = listOf(
            // if, unless and the boolean operators
            "c = 1\na = 2\n_ = if c, do: a",
            "c = 1\n_ = if c, do: 1, else: 2",
            "c = 1\n_ = if is_integer(c), do: 1, else: 2",
            "c = 1\n_ = if !is_integer(c), do: 1",
            "c = 1\n_ = if is_integer(c) and c > 0 or is_atom(c), do: 1",
            "m = %{a: 1}\n_ = if :erlang.is_map_key(:a, m), do: 1",
            "m = %{a: 1}\n_ = if is_map_key(m, :a), do: 1, else: 2",
            "c = 1\n_ = unless c, do: 1",
            "c = 1\n_ = unless c, do: 1, else: 2",
            "_ = if true, do: 1",
            "_ = unless false, do: 1",
            "_ = true && 1",
            "a = 1\nb = 2\n_ = a && b",
            "a = nil\nb = 2\n_ = a || b",
            "a = 1\n_ = !a",
            "a = 1\n_ = !!a",
            "c = 1\n_ = !is_integer(c)",
            "a = true\nb = false\n_ = a or b",
            "a = true\nb = false\n_ = a and b",
            // |>
            "x = -1\n_ = x |> abs()",
            "x = -1\n_ = x |> abs() |> Integer.to_string() |> String.length()",
            "x = -1\n_ = x |> abs",
            "x = [1]\n_ = x |> Enum.at(0)",
            "x = %{a: 1}\n_ = x |> Access.get(:a)",
            // in
            "x = 1\n_ = x in []",
            "x = 1\n_ = x in [1, 2]",
            "x = 1\n_ = x in [1, 2, 3, 4, 5, 6]",
            "x = 1\n_ = x in [${(1..33).joinToString()}]",
            "x = 1\na = 2\n_ = x in [a, 1]",
            "x = 1\ny = [2]\n_ = x in [1 | y]",
            "x = 1\ny = [1]\n_ = x in y",
            "x = 1\n_ = x in 1..3",
            "x = 1\n_ = x in 1..1",
            "x = 1\n_ = x in 3..1",
            "x = 1\n_ = x in -1..1",
            "_ = 1 in 1..3",
            "_ = abs(-1) in 1..3",
            "_ = 1 in [1, 2]",
            "_ = abs(-1) in [1, 2]",
            "x = 1\na = 1\nb = 3\n_ = x in a..b",
            "x = 1\n_ = x in %{first: 1, __struct__: :\"Elixir.Range\", last: 3, step: 1}",
            "x = 1\n_ = x in %{__struct__: :\"Elixir.Range\", last: 3, first: 1, step: 1}",
            "x = 1\n_ = fn -> x in Foo end",
            "x = 1\ny = [2]\n_ = fn -> x in y.Foo end",
            "x = 1\n_ = fn -> x in __MODULE__ end",
            "x = 1\ny = [2]\n_ = fn -> x in abs(y) end",
            "x = 1\ny = [2]\n_ = fn -> x in y.foo() end",
            "x = 1\n_ = fn -> x in Foo.bar() end",
            "x = 1\n_ = fn -> x in Kernel.to_string(1) end",
            "x = 1\n_ = fn -> x in __DIR__ end",
            // <>
            "b = \"b\"\n_ = \"a\" <> b",
            "b = \"b\"\n_ = \"a\" <> b <> \"c\"",
            "_ = \"a\" <> \"b\"",
            "a = \"a\"\nb = \"b\"\n_ = a <> b",
            // to_string, raise, binding and destructure
            "x = 1\n_ = to_string(x)",
            "x = 1\n_ = \"a#{x}\"",
            "_ = &to_string/1",
            "_ = fn -> raise \"boom\" end",
            "b = 1\n_ = fn -> raise \"a#{b}\" end",
            "_ = fn -> raise ArgumentError end",
            "msg = \"m\"\n_ = fn -> raise msg end",
            "_ = fn -> raise ArgumentError, \"m\" end",
            "_ = fn -> raise ArgumentError, message: \"m\" end",
            "x = 1\n_ = fn ->\n  raise(\n    if x, do: \"a\", else: \"b\"\n  )\nend",
            "_ = fn -> raise __DIR__ end",
            "_ = fn -> raise(case 1 do\n  _ -> \"m\"\nend) end",
            "a = 1\n_ = binding()",
            "a = 1\n_ = binding(:other)",
            "l = [1, 2]\ndestructure([a, b], l)",
            "_ = try do\n  :ok\nrescue\n  binding() -> 1\nend",
            // ranges
            "_ = 1..3",
            "_ = 3..1",
            "a = 1\nb = 3\n_ = a..b",
            "a = 1\nb = 3\n_ = case 1 do\n  z when z in a..b -> z\n  _ -> 0\nend",
            "if true do\n  def g, do: 1\nend",
        )

        /** Each in the body of a function of `x`, `y`, `a` and `b`. */
        val FUNCTION_BODY_CASES = listOf(
            "_ = if true, do: 1",
            "_ = unless false, do: 1",
            "_ = true && 1",
            "_ = if x, do: 1",
            "_ = if is_integer(x), do: 1",
            "_ = unless x, do: 1",
            "_ = x && y",
            "_ = x || y",
            "_ = x |> abs() |> Integer.to_string() |> String.length()",
            "_ = Kernel.|>(x, abs())",
            "raise Kernel.|>(b, String.trim())",
            "_ = Kernel.|>(x |> abs(), Integer.to_string())",
            "_ = x in [1, 2]",
            "_ = x in 1..3",
            "_ = x in y",
            "_ = x in []",
            "_ = x in f(x, y, a, b)",
            "_ = \"a\" <> b",
            "\"a\" <> rest = b\nrest",
            "_ = Kernel.<>(\"a\", b)",
            "_ = b |> Kernel.<>(\"c\")",
            "raise Kernel.<>(\"a\", b)",
            "Kernel.<>(\"a\", rest) = b\nrest",
            "_ = \"a\" <> Kernel.<>(b, \"c\")",
            "_ = to_string(x)",
            "raise \"m\"",
            "raise ArgumentError",
            "raise ArgumentError, \"m\"",
            "destructure([c, d], y)\n{c, d}",
            "_ = binding()",
            "_ = a..b",
            "if true do\n  def g, do: 1\nend",
        )

        /** Each a guard of `z`, with `x` bound outside it. */
        val GUARD_CASES = listOf(
            "z in []",
            "z in [1, 2]",
            "z in 1..3",
            "z in [1 | [2, 3]]",
            "z in -1..1",
            "is_integer(z) and z > 0",
            "is_integer(z) or z > 0",
            "z in %{first: 1, __struct__: :\"Elixir.Range\", last: 3, step: 1}",
            "x in [1, 2] and z == 1",
        )

        val MATCH_CASES = listOf(
            "b = \"ab\"\n\"a\" <> rest = b",
            "a = 1\nbinding() = [a: 1]",
            "a..b = 1..3",
            // A pin raises before PINNED_BINARY_SEGMENT_INFERS_SIZE.
            "a = \"a\"\n_ = fn ^a <> rest -> rest end",
            "b = \"ab\"\n_ = case b do\n  __DIR__ <> rest -> rest\n  _ -> b\nend",
        )

        /** Each raises while its macro expands, on some legs or all. */
        val RAISE_CASES = listOf(
            "c = 1\n_ = if c, foo: 1",
            "c = 1\n_ = unless c, foo: 1",
            "x = 1\n_ = x |> &abs/1",
            "x = 1\n_ = x |> {1, 2, 3}",
            "x = 1\n_ = x |> %{}",
            "x = 1\n_ = x |> Foo",
            "x = 1\ny = 2\n_ = x |> <<y>>",
            "x = 1\n_ = x |> fn y -> y end",
            "x = 1\ny = 2\n_ = x |> +y",
            "x = 1\ny = true\n_ = x |> !y",
            "x = 1\ny = 2\n_ = x |> y + 1",
            "x = 1\ny = 2\n_ = x |> y * 1",
            "x = 1\n_ = x |> 1",
            "x = 1\n_ = x |> unquote()",
            "x = 1\ny = [1]\n_ = fn -> x |> y[1] end",
            "x = 1\n_ = x in %{}",
            "x = 1\ny = [1]\n_ = case x do\n  z when z in y -> z\nend",
            "x = 1\n_ = case x do\n  z when z in [1 | 2] -> z\nend",
            "x = 1\n_ = case x do\n  z when z && true -> z\nend",
            "x = 1\n_ = case x do\n  z when z || true -> z\nend",
            "x = 1\n_ = case x do\n  z when !z -> z\nend",
            "a && b = 1",
            "a || b = 1",
            "!a = 1",
            "a or b = true",
            "a and b = true",
            "b = \"b\"\n_ = :a <> b",
            "b = \"b\"\n_ = 1 <> b",
            "a <> b = \"ab\"",
            "_ = 1.0..2",
            "_ = __DIR__..2",
            "x = 1\n_ = case x do\n  z when z in __DIR__ -> z\nend",
            "a = 1\n_ = a..:b",
            "x = 1\nl = [1]\ndestructure(x, l)",
        )

        val STEP_CASES = listOf(
            "_ = 1..9//2",
            "a = 1\nb = 9\nc = 2\n_ = a..b//c",
            "c = 2\n_ = 1..3//c",
            "x = 1\n_ = x in 3..1//-1",
            "x = 1\n_ = x in 1..9//2",
            "x = 1\na = 1\nb = 3\nc = 1\n_ = x in a..b//c",
            "a..b//c = 1..3//1",
            "a = 1\nb = 3\nc = 1\n_ = case 1 do\n  z when z in a..b//c -> z\n  _ -> 0\nend",
            "b = 3\n_ = case 1 do\n  z when z in 1..b//1 -> z\n  _ -> 0\nend",
            "_ = 1..3//0",
            "_ = 1..3//1.5",
            "_ = 1..3//__DIR__",
        )

        val FULL_RANGE_CASES = listOf("_ = ..")
    }
}
