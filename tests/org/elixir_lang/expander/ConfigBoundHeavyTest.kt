package org.elixir_lang.expander

/** Every read over an unknown ends. */
class ConfigBoundHeavyTest : ConfigHeavyTestCase() {
    fun testTwentyCasesWhoseArmsGiveOneValue() = assertEquals("k=0 k1=1", keys(cases { it }, "k", "k1"))

    fun testTwentyCasesWhoseArmsGiveValuesApart() = assertEquals("k=Configured k1=1", keys(cases { "[$it]" }, "k", "k1"))

    /** Each function's two arms call the next, so the arms double with each level, past the run's bound. */
    fun testArmsCallingFunctionsWithArmsDecline() =
        assertEquals(
            "declined",
            keys(
                "defmodule H do\n" +
                    (0 until 20).joinToString("") { "  def f$it(v) do\n    case v do\n      nil -> f${it + 1}(v)\n      _ -> f${it + 1}(v)\n    end\n  end\n" } +
                    "  def f20(_), do: :ok\nend\nconfig :app, k: H.f0(System.get_env(\"X\")), k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** `last/1` given an unknown calls itself with its tail, an unknown alike, which answers unknown. */
    fun testAFunctionCallingItselfWithWhatItWasGiven() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "defmodule H do\n  def last([x]), do: x\n  def last([_ | t]), do: last(t)\n  def last(v), do: v\nend\n" +
                    "config :app, k: H.last(System.get_env(\"X\")), k1: 1\n",
                "k",
                "k1",
            ),
        )

    fun testACountdownFromAnUnknown() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "defmodule H do\n  def f(0), do: 0\n  def f(n), do: f(n - 1)\nend\n" +
                    "config :app, k: H.f(String.to_integer(System.get_env(\"X\", \"1\"))), k1: 1\n",
                "k",
                "k1",
            ),
        )

    fun testAWalkUpAnUnknownPath() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "defmodule H do\n  def walk(nil), do: :ok\n  def walk(\"/\"), do: :root\n  def walk(\".\"), do: :dot\n" +
                    "  def walk(p), do: walk(Path.dirname(p))\nend\nconfig :app, k: H.walk(System.get_env(\"X\", \"/a\")), k1: 1\n",
                "k",
                "k1",
            ),
        )

    fun testAStreamFromAnUnknown() =
        assertEquals(
            "k=Configured k1=1",
            keyBeside("Stream.iterate(System.schedulers(), &(&1 + 1)) |> Enum.take(2)"),
        )

    fun testAReduceWhileOverAnUnknownListDeclines() =
        assertEquals(
            "declined",
            keyBeside("Enum.reduce_while(String.split(System.get_env(\"X\", \"\"), \",\"), 0, fn _, a -> {:cont, a + 1} end)"),
        )

    fun testAFoldOverAnUnknownListDeclines() =
        assertEquals(
            "declined",
            keyBeside(":lists.foldl(fn _, a -> a + 1 end, 0, String.split(System.get_env(\"X\", \"\"), \",\"))"),
        )

    fun testMutualRecursionThroughGeneratedFunctions() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "defmodule H do\n  for {a, b} <- [ping: :pong, pong: :ping] do\n    def unquote(a)(\"\"), do: unquote(a)\n" +
                    "    def unquote(a)(n), do: unquote(b)(binary_part(n, 1, byte_size(n) - 1))\n  end\nend\n" +
                    "config :app, k: H.ping(System.get_env(\"X\", \"\")), k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** On 1.11.4, `in` a list of 33 is 33 nested `orelse`, each left side over the unknown evaluated once. */
    fun testAnInOfALongListOverAnUnknown() =
        assertEquals(
            "k=Configured k1=1",
            keyBeside("System.get_env(\"X\") in ${(1..33).joinToString(", ", "[", "]") { "\"$it\"" }}"),
        )

    /** Twenty `case`s on twenty variables in turn, each arm's value [arm] of the last `case`'s. */
    private fun cases(arm: (String) -> String) =
        (0 until 20).joinToString("") { n ->
            val previous = if (n == 0) "0" else "v${n - 1}"
            "v$n =\n  case System.get_env(\"X$n\") do\n    nil -> $previous\n    _ -> ${arm(previous)}\n  end\n\n"
        } + "config :app, k: v19, k1: 1\n"
}
