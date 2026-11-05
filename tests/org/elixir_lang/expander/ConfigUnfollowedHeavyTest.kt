package org.elixir_lang.expander

/**
 * A file the evaluator can't follow, `Code.require_file/2`, may write any key, so the read declines wherever its result
 * reaches: as a subject, a test, a value passed on, and through a block, a loop's accumulator or a closure, which each
 * hand the decline on only at their end.
 */
class ConfigUnfollowedHeavyTest : ConfigHeavyTestCase() {
    fun testACaseOnARequiredFileDeclines() =
        assertDeclines(
            "config :app, k1: 1\ncase Code.require_file(\"w.exs\", __DIR__) do\n  nil -> raise \"no\"\n  _ -> :ok\nend\nconfig :app, k2: 2\n",
            "Application.put_env(:app, :w, 1)\n",
        )

    fun testACondTestOfARequiredFileDeclines() =
        assertDeclines("config :app, k1: 1\ncond do\n  Code.require_file(\"w.exs\", __DIR__) -> :ok\n  true -> raise \"no\"\nend\nconfig :app, k2: 2\n")

    fun testACondTestOfARequiredFileBeforeARaiseDeclines() =
        assertDeclines(
            "config :app, k1: 1\ncond do\n  Code.require_file(\"w.exs\", __DIR__) -> raise \"no\"\n  true -> :ok\nend\nconfig :app, k2: 2\n",
            "nil\n",
        )

    fun testARequiredFileBoundThenTestedDeclines() =
        assertDeclines(
            "config :app, k1: 1\nv = Code.require_file(\"w.exs\", __DIR__)\ncase v do\n  nil -> raise \"no\"\n  _ -> :ok\nend\nconfig :app, k2: 2\n",
        )

    fun testAnIfOnARequiredFileDeclines() =
        assertDeclines("config :app, k1: 1\nif Code.require_file(\"w.exs\", __DIR__), do: :ok, else: raise(\"no\")\nconfig :app, k2: 2\n")

    fun testARequiredFileOrRaiseDeclines() =
        assertDeclines("config :app, k1: 1\nv = Code.require_file(\"w.exs\", __DIR__) || raise \"no\"\nconfig :app, k2: 2, k: v\n")

    fun testAWithOnARequiredFileDeclines() =
        assertDeclines("config :app, k1: 1\nwith nil <- Code.require_file(\"w.exs\", __DIR__), do: raise(\"no\")\nconfig :app, k2: 2\n")

    fun testARequiredFilePassedToAFunctionDeclines() =
        assertDeclines(
            "defmodule H do\n  def f(nil), do: raise(\"no\")\n  def f(_), do: :ok\nend\nconfig :app, k1: 1\nH.f(Code.require_file(\"w.exs\", __DIR__))\n" +
                "config :app, k2: 2\n",
        )

    fun testARequiredFileRescuedThenTestedDeclines() =
        assertDeclines(
            "config :app, k1: 1\nv = try do\n  Code.require_file(\"w.exs\", __DIR__)\nrescue\n  _ -> nil\nend\n" +
                "case v do\n  nil -> raise \"no\"\n  _ -> :ok\nend\nconfig :app, k2: 2\n",
        )

    fun testARequiredFileInAnArmOfAnUnknownDeclines() =
        assertDeclines(
            "config :app, k1: 1\ncase System.get_env(\"X\") do\n  nil -> Code.require_file(\"w.exs\", __DIR__)\n  _ -> :ok\nend\nconfig :app, k2: 2\n",
        )

    fun testARequiredFileInAReducesAccumulatorDeclines() =
        assertDeclines(
            "config :app, k1: 1\nv = for a <- [1], reduce: nil do\n  _ -> Code.require_file(\"w.exs\", __DIR__) && a\nend\n" +
                "case v do\n  nil -> raise \"no\"\n  _ -> :ok\nend\nconfig :app, k2: 2\n",
        )

    fun testARequiredFileTestedInAFunctionsBlockDeclines() =
        assertDeclines(
            "defmodule H do\n  def f do\n    v = Code.require_file(\"w.exs\", __DIR__)\n\n    case v do\n      nil -> raise \"no\"\n      _ -> :ok\n    end\n\n" +
                "    :ok\n  end\nend\nconfig :app, k1: 1\nH.f()\nconfig :app, k2: 2\n",
        )

    fun testARequiredFileInAClosureRunOverAnUnknownListDeclines() =
        assertDeclines(
            "config :app, k1: 1\nEnum.map(String.split(System.get_env(\"X\", \"w.exs\"), \",\"), fn p -> Code.require_file(p, __DIR__) end)\n" +
                "config :app, k2: 2\n",
        )

    fun testARequiredFileTestedInAnIfsBlockDeclines() =
        assertDeclines(
            "config :app, k1: 1\nif true do\n  v = Code.require_file(\"w.exs\", __DIR__)\n\n  case v do\n    nil -> raise \"no\"\n    _ -> :ok\n  end\nend\n" +
                "config :app, k2: 2\n",
        )

    /** [config] declines, with `config/w.exs` holding [required]. */
    private fun assertDeclines(config: String, required: String = ":ok\n") =
        assertEquals("declined", keys(config, "k1", "k2", extra = arrayOf("config/w.exs" to required)))
}
