package org.elixir_lang.expander

/**
 * A file `Code.require_file/2` follows hands its result to the code after it, as a subject, a test, a value passed on,
 * and through a block, a loop's accumulator or a closure. A file it requires in code that may not run, or by a path not
 * known, may write any key, so the read declines.
 */
class ConfigRequiredFileHeavyTest : ConfigHeavyTestCase() {
    fun testACaseOnARequiredFileTakesItsResult() =
        assertReads(
            "config :app, k1: 1\ncase Code.require_file(\"w.exs\", __DIR__) do\n  nil -> raise \"no\"\n  _ -> :ok\nend\nconfig :app, k2: 2\n",
            "Application.put_env(:app, :w, 1)\n",
        )

    fun testACondTestOfARequiredFileTakesItsResult() =
        assertReads("config :app, k1: 1\ncond do\n  Code.require_file(\"w.exs\", __DIR__) -> :ok\n  true -> raise \"no\"\nend\nconfig :app, k2: 2\n")

    fun testACondTestOfARequiredFileBeforeARaiseRaises() =
        assertEquals(
            "raised",
            withRequired(
                "config :app, k1: 1\ncond do\n  Code.require_file(\"w.exs\", __DIR__) -> raise \"no\"\n  true -> :ok\nend\nconfig :app, k2: 2\n",
                "nil\n",
            ),
        )

    fun testARequiredFileBoundThenTestedTakesItsResult() =
        assertReads(
            "config :app, k1: 1\nv = Code.require_file(\"w.exs\", __DIR__)\ncase v do\n  nil -> raise \"no\"\n  _ -> :ok\nend\nconfig :app, k2: 2\n",
        )

    fun testAnIfOnARequiredFileTakesItsResult() =
        assertReads("config :app, k1: 1\nif Code.require_file(\"w.exs\", __DIR__), do: :ok, else: raise(\"no\")\nconfig :app, k2: 2\n")

    fun testARequiredFileOrRaiseTakesItsResult() =
        assertReads("config :app, k1: 1\nv = Code.require_file(\"w.exs\", __DIR__) || raise \"no\"\nconfig :app, k2: 2, k: v\n")

    fun testAWithOnARequiredFileTakesItsResult() =
        assertReads("config :app, k1: 1\nwith nil <- Code.require_file(\"w.exs\", __DIR__), do: raise(\"no\")\nconfig :app, k2: 2\n")

    fun testARequiredFilePassedToAFunctionTakesItsResult() =
        assertReads(
            "defmodule H do\n  def f(nil), do: raise(\"no\")\n  def f(_), do: :ok\nend\nconfig :app, k1: 1\nH.f(Code.require_file(\"w.exs\", __DIR__))\n" +
                "config :app, k2: 2\n",
        )

    fun testARequiredFileRescuedThenTestedTakesItsResult() =
        assertReads(
            "config :app, k1: 1\nv = try do\n  Code.require_file(\"w.exs\", __DIR__)\nrescue\n  _ -> nil\nend\n" +
                "case v do\n  nil -> raise \"no\"\n  _ -> :ok\nend\nconfig :app, k2: 2\n",
        )

    fun testARequiredFileInAnArmOfAnUnknownDeclines() =
        assertDeclines(
            "config :app, k1: 1\ncase System.get_env(\"X\") do\n  nil -> Code.require_file(\"w.exs\", __DIR__)\n  _ -> :ok\nend\nconfig :app, k2: 2\n",
        )

    fun testARequiredFileInAReducesAccumulatorTakesItsResult() =
        assertReads(
            "config :app, k1: 1\nv = for a <- [1], reduce: nil do\n  _ -> Code.require_file(\"w.exs\", __DIR__) && a\nend\n" +
                "case v do\n  nil -> raise \"no\"\n  _ -> :ok\nend\nconfig :app, k2: 2\n",
        )

    fun testARequiredFileTestedInAFunctionsBlockTakesItsResult() =
        assertReads(
            "defmodule H do\n  def f do\n    v = Code.require_file(\"w.exs\", __DIR__)\n\n    case v do\n      nil -> raise \"no\"\n      _ -> :ok\n    end\n\n" +
                "    :ok\n  end\nend\nconfig :app, k1: 1\nH.f()\nconfig :app, k2: 2\n",
        )

    fun testARequiredFileInAClosureRunOverAnUnknownListDeclines() =
        assertDeclines(
            "config :app, k1: 1\nEnum.map(String.split(System.get_env(\"X\", \"w.exs\"), \",\"), fn p -> Code.require_file(p, __DIR__) end)\n" +
                "config :app, k2: 2\n",
        )

    fun testARequiredFileTestedInAnIfsBlockTakesItsResult() =
        assertReads(
            "config :app, k1: 1\nif true do\n  v = Code.require_file(\"w.exs\", __DIR__)\n\n  case v do\n    nil -> raise \"no\"\n    _ -> :ok\n  end\nend\n" +
                "config :app, k2: 2\n",
        )

    /**
     * A floor: the followed file assumes the conversion returns, which leaves its run uncertain, so the read declines.
     * Elixir sets `k1=1 k2=2`.
     */
    fun testARequiredFileAssumingACallReturnsDeclinesAsAFloor() =
        assertDeclines(
            "config :app, k1: 1\nCode.require_file(\"w.exs\", __DIR__)\nconfig :app, k2: 2\n",
            "_ = :erlang.binary_to_integer(System.get_env(\"UNSET_N\", \"1\"))\n",
        )

    fun testAFileARequiredFileRequiresLoadsItsModulesAsItsOwn() = assertNestedLoad("require_file", "a=1")

    fun testAFileARequiredFileCompilesLoadsItsModulesAsItsOwn() = assertNestedLoad("compile_file", "a=1")

    fun testAFileARequiredFileEvaluatesLoadsItsModulesAsTheRequiredFiles() = assertNestedLoad("eval_file", "a=2")

    /**
     * The modules `Code.require_file/2` gives for `config/a.exs`, which defines `A` after following `config/b.exs`, which
     * defines `B`, by `Code`'s [form].
     */
    private fun assertNestedLoad(form: String, expected: String) =
        assertEquals(
            expected,
            keys(
                "config :app, a: length(Code.require_file(\"a.exs\", __DIR__))\n",
                "a",
                extra = arrayOf(
                    "config/a.exs" to "Code.$form(\"b.exs\", __DIR__)\ndefmodule A do\nend\n",
                    "config/b.exs" to "defmodule B do\nend\n",
                ),
            ),
        )

    /** [config] sets `k1` and `k2`, with `config/w.exs` holding [required]. */
    private fun assertReads(config: String, required: String = ":ok\n") = assertEquals("k1=1 k2=2", withRequired(config, required))

    /** [config] declines, with `config/w.exs` holding [required]. */
    private fun assertDeclines(config: String, required: String = ":ok\n") = assertEquals("declined", withRequired(config, required))

    /** `k1` and `k2` as [config] reads them, with `config/w.exs` holding [required]. */
    private fun withRequired(config: String, required: String) =
        keys(config, "k1", "k2", extra = arrayOf("config/w.exs" to required))
}
