package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.COMPILE_ENV_TAKES_ENV_FIRST

/**
 * A `try` whose body may raise on an unknown: its rescues join with the body, `else` takes the body's value, and what
 * follows a possible raise in the body may not run, as an arm may not. Where which raise a rescue sees depends on which
 * came first, or something past the raise writes what no join undoes, the read declines.
 */
class ConfigTryHeavyTest : ConfigHeavyTestCase() {
    fun testARescueOfAFetchThatMayRaise() = assertRescued("System.fetch_env!(\"X\")", rescued = "0")

    fun testAnElseAfterARescue() = assertElseAfterARescue("System.fetch_env!(\"X\")")

    fun testAConfigPastARaise() = assertConfigPastARaise("System.fetch_env!(\"X\")")

    fun testAnElseWithNoClauseThatMatches() =
        assertEquals(
            "k=Configured k1=1",
            keyBeside("(try do System.fetch_env!(\"X\") rescue TryClauseError -> 1; _ -> 0 else :never -> 2 end)"),
        )

    fun testARescueThatMayRaise() = assertRescueThatMayRaise("System.fetch_env!(\"X\")")

    fun testAnElseThatMayRaise() = assertElseThatMayRaise("System.fetch_env!(\"X\")")

    fun testAnImportOfAFileThatMayRaise() = assertImportOfAFileThatMayRaise("System.fetch_env!(\"X\")")

    fun testABinarySegmentThatMayRaise() = assertRescued("_ = <<System.get_env(\"X\")::binary>>; :ok", rescued = ":err")

    fun testAStrictAndThatMayRaise() = assertRescued("_ = (System.get_env(\"X\") || false) and true; :ok", rescued = ":err")

    fun testAForThatMayRaise() = assertRescued("_ = (for {\"X\", v} <- System.get_env(), do: String.to_integer(v)); :ok", rescued = ":err")

    /** A generator over a value not known, the env's map or a binary, raises where that isn't enumerable. */
    fun testAForOverAValueNotKnownThatMayRaise() =
        assertRescued(
            "_ = (for x <- (case System.get_env(\"X\") do nil -> System.get_env(); _ -> System.get_env(\"X\") end), do: x); :ok",
            rescued = ":err",
        )

    /** A match is a test as a `case` is, and may raise as one may. */
    fun testAMatchThatMayRaise() = assertRescued("{:ok, v} = System.fetch_env(\"X\"); v", rescued = "0")

    fun testACaseThatMayRaise() = assertRescued("case System.fetch_env(\"X\") do {:ok, v} -> v end", rescued = "0")

    fun testAFetchThatMayRaise() = assertRescued("_ = System.fetch_env!(\"X\"); :ok", rescued = ":err")

    /** Where the body raised, a rescue gives the value; where it didn't, `else` raises past the rescues. */
    fun testARaiseInAnElseDeclines() =
        assertEquals(
            "declined",
            keyBeside("(try do (try do System.fetch_env!(\"X\") rescue _ -> 0 else _ -> raise \"e\" end) rescue _ -> 1 end)"),
        )

    fun testAnImportPastARaiseDeclines() = assertImportedTwiceDeclines("System.fetch_env!(\"X\")")

    fun testAnImportPastAnArmThatMayRaiseDeclines() = assertImportedTwiceDeclines("if System.get_env(\"X\"), do: raise(\"x\")")

    /** A `try` run past a raise in an outer `try`'s body doesn't take that raise for its own. */
    fun testATryPastARaise() =
        assertEquals(
            "k=1 k1=1",
            keys(
                "config :app, k: 1\ntry do\n  System.fetch_env!(\"X\")\n  config :app, k: (try do 1 rescue _ -> 2 end)\nrescue\n  _ -> :ok\nend\n" +
                    "config :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** What an arm configured before it raised stays configured where the raise is rescued. */
    fun testAConfigInEachArmBeforeARaise() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "config :app, k: 1\n\ntry do\n  if System.get_env(\"X\") do\n    config :app, k: 2\n    raise \"x\"\n  else\n" +
                    "    config :app, k: 3\n    raise \"y\"\n  end\nrescue\n  _ -> :ok\nend\n\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    fun testAConfigInOneArmBeforeARaise() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "config :app, k: 1\n\ntry do\n  if System.get_env(\"X\") do\n    config :app, k: 2\n    raise \"x\"\n  end\n" +
                    "rescue\n  _ -> :ok\nend\n\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** Every arm raises, but which raise depends on the arm taken. */
    fun testArmsThrowingValuesApart() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "k =\n  try do\n    case System.get_env(\"X\") do\n      nil -> (v = 1; throw(v))\n      _ -> (v = 2; throw(v))\n    end\n" +
                    "  catch\n    v -> v\n  end\n\nconfig :app, k: k, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** A rescue of one exception passes another on to the `try` around it. */
    fun testARaiseARescueByTypePassesOn() =
        assertEquals(
            "j=Configured k1=1",
            keys(
                "try do\n  try do\n    System.fetch_env!(\"X\")\n  rescue\n    RuntimeError -> :a\n  end\n\n  config :app, j: 2\n" +
                    "rescue\n  _ -> :ok\nend\n\nconfig :app, k1: 1\n",
                "j",
                "k1",
            ),
        )

    /** A clock given a unit it takes doesn't raise. */
    fun testATimeInAUnitItTakes() =
        assertEquals(
            "k=:ok k1=1",
            keys("k =\n  try do\n    _ = System.system_time(:second)\n    :ok\n  rescue\n    _ -> :err\n  end\n\nconfig :app, k: k, k1: 1\n", "k", "k1"),
        )

    fun testATimeInAUnitItRefuses() =
        assertEquals(
            "k=:err k1=1",
            keys("k =\n  try do\n    _ = :erlang.system_time(:fortnight)\n    :ok\n  rescue\n    _ -> :err\n  end\n\nconfig :app, k: k, k1: 1\n", "k", "k1"),
        )

    /** `:erlang.error/3` raises whatever its options are, but it declines on options not known. */
    fun testAnErrorOfOptionsNotKnownDeclines() =
        assertEquals(
            "declined",
            keyBeside("(try do :erlang.error(:x, [], [System.get_env(\"X\")]) catch _, _ -> :c end)"),
        )

    /** `:erlang.error/2` in a function raises whatever its arguments are. */
    fun testAnErrorOverArgumentsNotKnownRaises() =
        assertEquals(
            "k=:r k1=1",
            keys(
                "defmodule H do\n  def f(a), do: :erlang.error(:x, a)\nend\n\n" +
                    "k =\n  try do\n    H.f(System.get_env(\"X\"))\n    :returned\n  rescue\n    _ -> :r\n  end\n\n" +
                    "config :app, k: k, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** A binary generator over a binary not known takes whole bytes, so it can't raise. */
    fun testABinaryGeneratorOverABinaryNotKnown() =
        assertEquals(
            "k=:done k1=1",
            keys(
                "k =\n  try do\n    for <<c <- System.get_env(\"X\", \"\")>>, do: c\n    :done\n  rescue\n    _ -> :err\n  end\n\n" +
                    "config :app, k: k, k1: 1\n",
                "k",
                "k1",
            ),
        )

    fun testAModuleCreatedPastARaiseDeclines() =
        assertCreatedDeclines("try do\n  System.fetch_env!(\"X\")\n  $CREATE\nrescue\n  _ -> :ok\nend\n")

    /** `else` runs where the body didn't raise, past the rescues. */
    fun testAModuleCreatedInAnElsePastARaiseDeclines() =
        assertCreatedDeclines("try do\n  System.fetch_env!(\"X\")\nrescue\n  _ -> :ok\nelse\n  _ -> $CREATE\nend\n")

    fun testAModuleCreatedInATryPastARaiseDeclines() =
        assertCreatedDeclines("try do\n  System.fetch_env!(\"X\")\n  try do\n    $CREATE\n  rescue\n    _ -> :ok\n  end\nrescue\n  _ -> :ok\nend\n")

    /** A written app's env reads as not known, so a write that may not have run changes no answer. */
    fun testAnAppEnvWritePastARaise() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "try do\n  System.fetch_env!(\"X\")\n  Application.put_env(:a, :b, 1)\nrescue\n  _ -> :ok\nend\n" +
                    "config :app, k: Application.get_env(:a, :b, 0), k1: 1\n",
                "k",
                "k1",
            ),
        )

    fun testAConfigPastARaiseThenARaiseDeclines() =
        assertEquals(
            "declined",
            keys(
                "try do\n  System.fetch_env!(\"X\")\n  config :app, j: 2\n  raise \"x\"\nrescue\n  _ -> :ok\nend\nconfig :app, k1: 1\n",
                "j",
                "k1",
            ),
        )

    fun testARaisePastARaiseRescuedByTypeDeclines() =
        assertEquals(
            "declined",
            keys(
                "k = try do\n  System.fetch_env!(\"X\")\n  raise \"x\"\nrescue\n  System.EnvError -> 1\n  RuntimeError -> 2\nend\n" +
                    "config :app, k: k, k1: 1\n",
                "k",
                "k1",
            ),
        )

    fun testARaisePastARaiseInAnInnerTryRescuedByTypeDeclines() =
        assertEquals(
            "declined",
            keys(
                "k = try do\n  try do\n    System.fetch_env!(\"X\")\n    raise \"x\"\n  after\n    :ok\n  end\n" +
                    "rescue\n  System.EnvError -> 1\n  RuntimeError -> 2\nend\nconfig :app, k: k, k1: 1\n",
                "k",
                "k1",
            ),
        )

    fun testARescueOfAnIntegerThatMayRaise() = assertRescued("String.to_integer(System.get_env(\"X\"))", rescued = "0")

    fun testAnElseAfterARescueOfAnInteger() = assertElseAfterARescue("String.to_integer(System.get_env(\"X\"))")

    fun testAConfigPastARaiseOfAnInteger() = assertConfigPastARaise("String.to_integer(System.get_env(\"X\"))")

    fun testAnElseWithNoClauseThatMatchesOfAnInteger() =
        assertEquals(
            "k=Configured k1=1",
            keyBeside("(try do String.to_integer(System.get_env(\"X\")) rescue ArgumentError -> 0; TryClauseError -> 1 else :never -> 2 end)"),
        )

    fun testARescueThatMayRaiseOfAnInteger() = assertRescueThatMayRaise("String.to_integer(System.get_env(\"X\"))")

    fun testAnElseThatMayRaiseOfAnInteger() = assertElseThatMayRaise("String.to_integer(System.get_env(\"X\"))")

    fun testAnImportOfAFileThatMayRaiseOfAnInteger() = assertImportOfAFileThatMayRaise("String.to_integer(System.get_env(\"X\"))")

    /** A call repeating one running over a value not known may raise as that one may, and the `try` may rescue it. */
    fun testARescueOfACountdownFromAnUnknown() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "defmodule H do\n  def f(nil), do: 0\n  def f([]), do: 0\n  def f([_ | t]), do: f(t)\nend\n" +
                    "config :app, k: (try do\n  H.f(System.get_env(\"X\"))\nrescue\n  _ -> :r\nend), k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** `fetch_env!/2` of a value not known may raise, and the `try` may rescue it. */
    fun testARescueOfAnAppEnvFetch() = assertRescued("Application.fetch_env!(:dep, :k)")

    /**
     * `compile_env/4` given an env not known may raise in its env match, and the `try` may rescue it, a default holding
     * a function included.
     */
    fun testARescueOfACompileEnvOfAnEnvNotKnown() {
        val env = "if(System.get_env(\"X\"), do: __ENV__, else: %{})"

        assertRescued(
            if (COMPILE_ENV_TAKES_ENV_FIRST.isSufficient(legLevel())) "Application.compile_env($env, :dep, :k, [fn -> 1 end])"
            else "Application.__compile_env__(:dep, :k, [fn -> 1 end], $env)",
        )
    }

    /**
     * `traverse_env/2` down a path past a value not known may raise in `Access.fetch/2`, and the `try` may rescue it, a
     * default holding a function included.
     */
    fun testARescueOfACompileEnvPastAValueNotKnown() =
        assertRescued(
            if (COMPILE_ENV_TAKES_ENV_FIRST.isSufficient(legLevel())) "Application.compile_env(__ENV__, :dep, [:k, :sub], [fn -> 1 end])"
            else "Application.__compile_env__(:dep, [:k, :sub], [fn -> 1 end], __ENV__)",
        )

    /** `set_env/4` given options not known may raise in its guards, and the `try` may rescue it. */
    fun testARescueOfAnAppEnvWriteOfOptionsNotKnown() = assertRescued(":application.set_env(:dep, :k, 1, System.get_env(\"X\") && [])")

    /** `unset_env/3` given options not known may raise in its guards too. */
    fun testARescueOfAnAppEnvDeleteOfOptionsNotKnown() = assertRescued(":application.unset_env(:dep, :k, System.get_env(\"X\") && [])")

    /** [statements], then a read of what `H`, which they may create, gives. */
    private fun assertCreatedDeclines(statements: String) = assertEquals("declined", keys(statements + "config :app, k: H.v(), k1: 1\n", "k", "k1"))

    /** A `try` of [mayRaise], rescuing it with [rescued], then a key beside it. */
    private fun assertRescued(mayRaise: String, rescued: String = ":d") =
        assertEquals("k=Configured k1=1", keyBeside("(try do $mayRaise rescue _ -> $rescued end)"))

    /** `o.exs`, imported past [mayRaise] in a `try`'s body, may have been read, so a later import of it may raise. */
    private fun assertImportedTwiceDeclines(mayRaise: String) =
        assertImportedAgainDeclines("try do\n  $mayRaise\n  import_config \"o.exs\"\nrescue\n  _ -> :ok\nend\n")

    /** A `try` of [mayRaise], rescuing it with `0`, whose `else` gives `1`, then a key beside it. */
    private fun assertElseAfterARescue(mayRaise: String) =
        assertEquals("k=Configured k1=1", keyBeside("(try do $mayRaise rescue _ -> 0 else _ -> 1 end)"))

    /** [mayRaise] in a `try`'s body, then a config of `j` past it, which may not run, then `k1`. */
    private fun assertConfigPastARaise(mayRaise: String) =
        assertEquals(
            "j=Configured k1=1",
            keys("try do\n  $mayRaise\n  config :app, j: 2\nrescue\n  _ -> :ok\nend\nconfig :app, k1: 1\n", "j", "k1"),
        )

    /** A rescue that runs [mayRaise] again, in a `try` that rescues it, then a key beside it. */
    private fun assertRescueThatMayRaise(mayRaise: String) =
        assertEquals("k=Configured k1=1", keyBeside("(try do (try do $mayRaise rescue _ -> $mayRaise end); :ok rescue _ -> :err end)"))

    /** An `else` that runs [mayRaise], in a `try` that rescues it, then a key beside it. */
    private fun assertElseThatMayRaise(mayRaise: String) =
        assertEquals("k=Configured k1=1", keyBeside("(try do (try do :ok rescue _ -> :no else _ -> $mayRaise end); :ok rescue _ -> :err end)"))

    /** An import in a `try` of `o.exs`, which runs [mayRaise]. */
    private fun assertImportOfAFileThatMayRaise(mayRaise: String) =
        assertEquals("k=Configured k1=1", keys(IMPORT_IN_A_TRY, "k", "k1", extra = arrayOf("config/o.exs" to "import Config\n$mayRaise\n")))

    private companion object {
        const val CREATE = "Module.create(H, quote(do: def(v, do: 1)), Macro.Env.location(__ENV__))"
    }
}
