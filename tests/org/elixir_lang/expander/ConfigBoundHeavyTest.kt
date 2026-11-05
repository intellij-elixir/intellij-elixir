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

    /** Twenty `case`s on twenty variables in turn, each arm's value [arm] of the last `case`'s. */
    private fun cases(arm: (String) -> String) =
        (0 until 20).joinToString("") { n ->
            val previous = if (n == 0) "0" else "v${n - 1}"
            "v$n =\n  case System.get_env(\"X$n\") do\n    nil -> $previous\n    _ -> ${arm(previous)}\n  end\n\n"
        } + "config :app, k: v19, k1: 1\n"
}
