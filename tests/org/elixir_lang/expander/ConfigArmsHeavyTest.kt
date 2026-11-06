package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.BITSTRING_SIZE_EXPANDED_AS_GUARD
import org.elixir_lang.language_level.ElixirLanguageFeature.COMPILE_ENV_TAKES_ENV_FIRST
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_MAP_ENTRIES_QUOTED_APART
import org.elixir_lang.language_level.ElixirLanguageFeature.IO_WARN_OPTIONS_TAKE_A_COLUMN
import org.elixir_lang.language_level.ElixirLanguageFeature.IO_WARN_READS_THE_MESSAGE_FIRST
import org.elixir_lang.language_level.ElixirLanguageFeature.IO_WARN_TAKES_AN_ENV_OR_OPTIONS
import org.elixir_lang.language_level.ElixirLanguageFeature.REGEX_ESCAPED_IN_FIXED_FIELD_ORDER

/**
 * A value from the environment, unknown to the plugin, passing through code that tests it. Each arm that may be taken runs
 * from the state before the test, and the arms join: a key the arms set alike keeps its value, one they set apart is
 * unknown, and a key nothing unknown reaches stays known. Each expected answer is checked against Elixir's reads of the
 * same file with `X` unset, empty, `123`, `abc`, `true` and `ok`, on 1.20.4 and 1.11.4: a value is known only where every
 * run that doesn't raise gives it.
 */
class ConfigArmsHeavyTest : ConfigHeavyTestCase() {
    fun testAnOrDefault() =
        assertConfiguredBeside("System.get_env(\"X\") || \"4000\"")

    fun testAnIfsValue() =
        assertConfiguredBeside("if(System.get_env(\"X\"), do: \"a\", else: \"b\")")

    fun testAnIfWhoseArmsGiveOneValue() =
        assertEquals("k=\"a\" k1=1", keyBeside("if(System.get_env(\"X\"), do: \"a\", else: \"a\")"))

    fun testAConfigInAnIf() =
        assertEquals(
            "k0=0 k=Configured k1=1",
            keys("config :app, k0: 0\nif System.get_env(\"X\") do\n  config :app, k: 1\nend\nconfig :app, k1: 1\n", "k0", "k", "k1"),
        )

    fun testAConfigInAnIfOverAnEarlierOne() =
        assertEquals("k=Configured", keys("config :app, k: 0\nif System.get_env(\"X\") do\n  config :app, k: 1\nend\n", "k"))

    fun testAConfigInEachArmOfAnIf() =
        assertEquals(
            "k=1 j=Configured",
            keys("if System.get_env(\"X\") do\n  config :app, k: 1\nelse\n  config :app, k: 1, j: 2\nend\n", "k", "j"),
        )

    /** Every arm throws one value, so whichever is taken, the `catch` gives it. */
    fun testArmsThrowingOneValue() =
        assertEquals(
            "k=1 k1=1",
            keyBound(
                "k =\n  try do\n    case System.get_env(\"X\") do\n      nil -> (v = 1; throw(v))\n      _ -> (v = 1; throw(v))\n    end\n" +
                    "  catch\n    v -> v\n  end\n\n",
            ),
        )

    fun testAConcatenation() =
        assertConfiguredBeside("\"a\" <> System.get_env(\"X\", \"\")")

    fun testABinarySegment() =
        assertConfiguredBeside("<<System.get_env(\"X\", \"\")::binary>>")

    fun testANot() = assertConfiguredBeside("!System.get_env(\"X\")")

    fun testAnAndAnd() = assertConfiguredBeside("System.get_env(\"X\") && 1")

    fun testAnUnless() =
        assertConfiguredBeside("unless(System.get_env(\"X\"), do: 1)")

    fun testAFieldOfAnUnknownDeclines() =
        assertEquals("declined", keys("v = System.get_env(\"X\")\nconfig :app, k: v.y, k1: 1\n", "k", "k1"))

    fun testAMapGet() =
        assertConfiguredBeside("Map.get(%{\"1\" => 2}, System.get_env(\"X\"))")

    fun testAKeywordGet() =
        assertConfiguredBeside("Keyword.get([a: System.get_env(\"X\")], :a)")

    fun testAnAnonymousFunctionCalled() =
        assertEquals(
            "k=Configured k1=1",
            keys("f = fn nil -> 1; v -> v end\nconfig :app, k: f.(System.get_env(\"X\")), k1: 1\n", "k", "k1"),
        )

    fun testAnAnonymousFunctionClauseWhoseGuardCantHold() =
        assertEquals(
            "k=0 k1=1",
            keyBound("limit = 0\nf = fn\n  \"1\" when limit > 0 -> 1\n  _ -> 0\nend\n\nk = f.(System.get_env(\"X\"))\n"),
        )

    /** A fun's head, tested on a value not known, binds its variables afresh. */
    fun testAFunHeadInADefaultArgumentTestedOnAValueNotKnownReadsWhatItBinds() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "defmodule H do\n" +
                    "  def f(a, b \\\\ (fn %{\"HOME\" => y} -> Config.config(:app, k: y); _ -> :ok end).(System.get_env())), do: {a, b}\n" +
                    "end\n\nconfig :app, k: 0\nH.f(0)\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** A clause that may match a value not known isn't taken where a known argument beside it doesn't match. */
    fun testAClauseWhoseKnownArgumentDoesNotMatchBesideOneNotKnownIsNotTaken() =
        assertEquals(
            "k=0 k1=1",
            keyBound("defmodule H do\n  def f(\"\", :a), do: 1\n  def f(_, _), do: 0\nend\n\nk = H.f(System.get_env(\"X\"), :b)\n"),
        )

    /** A clause that may match a value not known binds a known argument beside it as itself. */
    fun testAClauseThatMayMatchAValueNotKnownBindsAKnownArgumentBesideIt() =
        assertEquals(
            "k=:b k1=1",
            keyBound("defmodule H do\n  def f(\"\", t), do: t\n  def f(_, _), do: :b\nend\n\nk = H.f(System.get_env(\"X\"), :b)\n"),
        )

    /** A clause isn't taken where a known part of an argument that holds a value not known doesn't match. */
    fun testAClauseWhoseKnownPartDoesNotMatchAnArgumentHoldingAValueNotKnownIsNotTaken() =
        assertEquals(
            "k=0 k1=1",
            keyBound(
                "defmodule H do\n  def f(nil, {:a, _}), do: 1\n  def f(_, _), do: 0\nend\n\n" +
                    "k = H.f(System.get_env(\"X\"), {:b, System.get_env(\"Y\")})\n",
            ),
        )

    /** A tuple pattern a known element fails never matches, though an element before it isn't known, so its arm isn't taken. */
    fun testATuplePatternWhoseKnownElementFailsPastOneNotKnownIsNotTaken() =
        assertKnownPartFails("{System.get_env(\"X\"), :b}", "{nil, :a}")

    /** A list pattern a known element fails never matches, though an element before it isn't known, so its arm isn't taken. */
    fun testAListPatternWhoseKnownElementFailsPastOneNotKnownIsNotTaken() =
        assertKnownPartFails("[System.get_env(\"X\"), :b]", "[nil, :a]")

    /** An improper list pattern whose known tail fails never matches, though an element before it isn't known, so its arm isn't taken. */
    fun testAnImproperListPatternWhoseKnownTailFailsPastOneNotKnownIsNotTaken() =
        assertKnownPartFails("[System.get_env(\"X\"), :b]", "[nil | [:a]]")

    /** A map pattern a known value fails never matches, though a value before it isn't known, so its arm isn't taken. */
    fun testAMapPatternWhoseKnownValueFailsPastOneNotKnownIsNotTaken() =
        assertKnownPartFails("%{a: System.get_env(\"X\"), b: :b}", "%{a: nil, b: :a}")

    /** A struct pattern a known field fails never matches, though the struct's name isn't known, so its arm isn't taken. */
    fun testAStructPatternWhoseKnownFieldFailsPastANameNotKnownIsNotTaken() =
        assertKnownPartFails("%{__struct__: System.get_env(\"X\"), a: :b}", "%_{a: :a}")

    /** Two patterns matched as one never match where the second fails on a known part, though the first reads one not known. */
    fun testAMatchOfTwoPatternsWhoseSecondFailsPastOneNotKnownIsNotTaken() =
        assertKnownPartFails("{System.get_env(\"X\"), :b}", "{nil, _} = {_, :a}")

    /** A function clause never matches where a known argument fails, though an argument before it is not known. */
    fun testAFunctionClauseWhoseKnownArgumentFailsPastOneNotKnownIsNotTaken() =
        assertEquals(
            "k=0 k1=1",
            keys(
                "config :app, k: 0\n\n(fn\n  nil, :a -> config :app, k: 1\n  _, _ -> :ok\nend).(System.get_env(\"X\"), :b)\n\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    fun testACase() =
        assertEquals(
            "k=Configured k1=1",
            keyBound("k =\n  case System.get_env(\"X\") do\n    nil -> 1\n    \"\" -> 2\n    _ -> 3\n  end\n\n"),
        )

    fun testACaseWhoseArmsGiveOneValue() =
        assertEquals(
            "k=1 k1=1",
            keyBound("k =\n  case System.get_env(\"X\") do\n    nil -> 1\n    _ -> 1\n  end\n\n"),
        )

    /** A clause whose guard can't hold is never taken, though its pattern may match a value not known. */
    fun testACaseClauseWhoseGuardCantHold() =
        assertEquals(
            "k=0 k1=1",
            keyBound("limit = 0\n\nk =\n  case System.get_env(\"X\") do\n    \"1\" when limit > 0 -> 1\n    _ -> 0\n  end\n\n"),
        )

    /** A variable the pattern matched to a known part keeps it, so a guard on it can't hold, though the rest isn't known. */
    fun testACaseClauseWhoseGuardCantHoldOnAPartMatched() =
        assertEquals(
            "k=0 k1=1",
            keyBeside("(case {config_env(), System.get_env(\"X\")} do\n  {env, nil} when env == :prod -> 1\n  _ -> 0\nend)"),
        )

    /** A segment's size in a head that may match reads the variable bound before it, which the head doesn't bind. */
    fun testASegmentSizeInAFunctionHeadThatMayMatchReadsTheVariableBoundBefore() =
        assertEquals(
            "k=8 k1=1",
            keys(
                "n = 8\nconfig :app, k: 8\n\n(fn\n  <<_::size(n)>> -> config(:app, k: n)\n  _ -> :ok\nend).(System.get_env(\"X\", \"a\"))\n\n" +
                    "config :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /**
     * An anonymous function's head binds its variables afresh, so its guard reads the value it is given, not a variable of
     * the same version the clause around it bound, as a default argument's variables may be before Elixir 1.14.
     */
    fun testAnAnonymousFunctionClauseInADefaultArgumentReadsTheValueItIsGiven() =
        assertEquals(
            "enabled=Configured k1=1",
            keys(
                "defmodule H do\n" +
                    "  def f(a, b \\\\ (fn\n    [y] when y != 0 -> Config.config(:app, enabled: true)\n    _ -> :ok\n  end).(max(System.get_env(\"X\"), [1]))),\n" +
                    "    do: {a, b}\nend\n\nconfig :app, enabled: false\nH.f(0)\nconfig :app, k1: 1\n",
                "enabled",
                "k1",
            ),
        )

    fun testAConfigInEachArmOfACase() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "case System.get_env(\"X\") do\n  nil -> config :app, k: 1\n  _ -> config :app, k: 2\nend\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    fun testACond() =
        assertEquals(
            "k=Configured k1=1",
            keyBound("k =\n  cond do\n    System.get_env(\"X\") -> 1\n    true -> 2\n  end\n\n"),
        )

    fun testAVariableUsedLater() =
        assertEquals("k=Configured k1=1", keys("port = System.get_env(\"X\")\nconfig :app, k: port, k1: 1\n", "k", "k1"))

    fun testAFunctionWithClausesTheFileDefines() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "defmodule H do\n  def f(nil), do: 1\n  def f(v), do: v\nend\nconfig :app, k: H.f(System.get_env(\"X\")), k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** A native running a function over a list not known declines. */
    fun testAFunctionMappedOverAnUnknownListDeclines() =
        assertEquals(
            "declined",
            keyBeside("Enum.map(String.split(System.get_env(\"X\", \"\"), \",\"), fn x -> x end)"),
        )

    fun testANestedValue() =
        assertEquals(
            "url=Configured url/port=4000 k1=1",
            keys("config :app, url: [host: System.get_env(\"X\"), port: 4000], k1: 1\n", "url", "url/port", "k1"),
        )

    /** A key not known may be any key, so it may overwrite each. */
    fun testAnUnknownKeyDeclines() =
        assertEquals("declined", keys("config :app, k0: 0\nconfig :app, String.to_atom(System.get_env(\"X\", \"ok\")), 1\n", "k0"))

    fun testAnUnknownKeyThatMayNotBeAnAtomDeclines() =
        assertEquals("declined", keys("config :app, System.get_env(\"X\"), 1\n", "k0"))

    /** An app not known may be any app, so it may overwrite each. */
    fun testAnUnknownAppDeclines() =
        assertEquals(
            "declined",
            keys("config :app, k0: 0\nconfig String.to_atom(System.get_env(\"X\", \"ok\")), k: 1\nconfig :other, j: 2\n", "k0", "k", "other:j"),
        )

    fun testAnUnknownAppThatMayNotBeAnAtomDeclines() = assertEquals("declined", keys("config System.get_env(\"X\"), k: 1\n", "k"))

    fun testARaiseInACaseArm() =
        assertEquals(
            "k1=1 k2=2",
            keys("config :app, k1: 1\ncase System.get_env(\"X\") do\n  nil -> raise \"no\"\n  _ -> :ok\nend\nconfig :app, k2: 2\n", "k1", "k2"),
        )

    fun testAFetchThatMayRaise() =
        assertEquals(
            "k1=1 k=Configured k2=2",
            keys("config :app, k1: 1, k: System.fetch_env!(\"X\")\nconfig :app, k2: 2\n", "k1", "k", "k2"),
        )

    fun testAnOrRaise() =
        assertEquals(
            "k1=1 k=Configured k2=2",
            keys("config :app, k1: 1, k: System.get_env(\"X\") || raise(\"no\")\nconfig :app, k2: 2\n", "k1", "k", "k2"),
        )

    fun testARaiseInACondArm() =
        assertEquals(
            "k1=1 k2=2",
            keys("config :app, k1: 1\ncond do\n  System.get_env(\"X\") -> raise \"no\"\n  true -> :ok\nend\nconfig :app, k2: 2\n", "k1", "k2"),
        )

    fun testACaseOfAWrittenAppsEnv() =
        assertEquals(
            "k1=1 k2=2",
            keys(
                "Application.put_env(:other, :w, 1, persistent: true)\nconfig :app, k1: 1\n" +
                    "case Application.get_env(:other, :w) do\n  nil -> raise \"no\"\n  _ -> :ok\nend\nconfig :app, k2: 2\n",
                "k1",
                "k2",
            ),
        )

    fun testACaseOfAProcessLookup() =
        assertEquals(
            "k1=1 k2=2",
            keys("config :app, k1: 1\ncase Process.whereis(:nobody) do\n  nil -> :ok\n  _ -> raise \"no\"\nend\nconfig :app, k2: 2\n", "k1", "k2"),
        )

    /** Elixir raises where `X` is set and configures `k1` where it isn't: the raise ends the arm, not the read. */
    fun testARaiseInAnArmOverASystemReadDeclines() =
        assertEquals("declined", keys("if System.get_env(\"X\"), do: String.to_integer(\"x\")\nconfig :app, k1: 1\n", "k1"))

    /** Elixir raises on every machine, as every arm raises, but each raise ends its arm, so the read declines as above. */
    fun testARaiseInEveryArmOverASystemReadDeclines() =
        assertEquals(
            "declined",
            keys(
                "case System.get_env(\"X\") do\n  nil -> String.to_integer(\"x\")\n  _ -> String.to_integer(\"y\")\nend\n" +
                    "config :app, k1: 1\n",
                "k1",
            ),
        )

    /** As where the raise is in a function the arm calls. */
    fun testARaiseInAFunctionAnArmCallsDeclines() =
        assertEquals(
            "declined",
            keys(
                "defmodule H do\n  def f(_v), do: String.to_integer(\"x\")\nend\n\n" +
                    "if System.get_env(\"X\"), do: H.f(System.get_env(\"Y\"))\nconfig :app, k1: 1\n",
                "k1",
            ),
        )

    /** As where the function the arm calls is undefined. */
    fun testAnUndefinedFunctionInAnArmDeclines() =
        assertEquals("declined", keys("if System.get_env(\"X\"), do: Config.nope(:app)\nconfig :app, k1: 1\n", "k1"))

    /** Each arm defines the module its own way, and nothing joins what a module defined. */
    fun testAModuleDefinedInEachArmDeclines() =
        assertEquals(
            "declined",
            keys(
                "if System.get_env(\"X\") do\n  defmodule H do\n    def v, do: 1\n  end\nelse\n  defmodule H do\n    def v, do: 2\n  end\nend\n" +
                    "config :app, k: H.v(), k1: 1\n",
                "k",
                "k1",
            ),
        )

    fun testAModuleRedefinedInAnArmDeclines() =
        assertEquals(
            "declined",
            keys(
                "defmodule H do\n  def v, do: 2\nend\nif System.get_env(\"X\") do\n  defmodule H do\n    def v, do: 1\n  end\nend\n" +
                    "config :app, k: H.v(), k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** The second test runs only where the first is false, so its config may not be written. */
    fun testAConfigInALaterCondTestMayBeWritten() =
        assertEquals(
            "j=Configured k1=1",
            keys("cond do\n  System.get_env(\"X\") -> config :app, k: 1\n  (config :app, j: 1; true) -> :ok\nend\nconfig :app, k1: 1\n", "j", "k1"),
        )

    fun testEachArmSettingTheSameDuplicateKeys() =
        assertEquals(
            "k=[a: 1, a: 2] k1=1",
            keys(
                "if System.get_env(\"X\") do\n  config :app, k: [a: 1, a: 2]\nelse\n  config :app, k: [a: 1, a: 2]\nend\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    fun testArmsSettingKeysInAnotherOrder() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "if System.get_env(\"X\") do\n  config :app, k: [a: 1, b: 2]\nelse\n  config :app, k: [b: 2, a: 1]\nend\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    fun testArmsSettingADuplicateKeyApart() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "if System.get_env(\"X\") do\n  config :app, k: [a: 1, a: 2]\nelse\n  config :app, k: [a: 1, a: 3]\nend\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    fun testAWith() =
        assertEquals(
            "k=Configured k1=1",
            keyBound("k = with {:ok, v} <- System.fetch_env(\"X\"), do: v\n"),
        )

    fun testAWithElse() =
        assertEquals(
            "k=Configured k1=1",
            keyBound("k =\n  with {:ok, v} <- System.fetch_env(\"X\") do\n    v\n  else\n    :error -> \"e\"\n  end\n\n"),
        )

    fun testAWithClauseWhoseGuardCantHold() =
        assertEquals(
            "k=0 k1=1",
            keyBound("limit = 0\n\nk =\n  with \"1\" when limit > 0 <- System.get_env(\"X\") do\n    1\n  else\n    _ -> 0\n  end\n\n"),
        )

    /** A variable the clause matched to a known part keeps it, so a guard on it can't hold, though the rest isn't known. */
    fun testAWithClauseWhoseGuardCantHoldOnAPartMatched() =
        assertEquals(
            "k=0 k1=1",
            keyBound(
                "k =\n  with {env, nil} when env == :prod <- {config_env(), System.get_env(\"X\")} do\n    1\n  else\n    _ -> 0\n  end\n\n",
            ),
        )

    fun testAWithWritingInItsDo() =
        assertEquals(
            "k=Configured k1=1",
            keys("config :app, k: 1\nwith {:ok, _} <- System.fetch_env(\"X\"), do: config(:app, k: 2)\nconfig :app, k1: 1\n", "k", "k1"),
        )

    fun testAMatch() = assertEquals("k=Configured k1=1", keyBound("{:ok, k} = System.fetch_env(\"X\")\n"))

    /** A variable a match that may raise matched to a known part keeps it. */
    fun testAMatchThatMayRaiseKeepsAPartMatched() =
        assertEquals("k=:dev k1=1", keyBound("{k, nil} = {config_env(), System.get_env(\"X\")}\n"))

    fun testAFor() =
        assertConfiguredBeside("(for {\"X\", v} <- System.get_env(), do: v)")

    fun testABitstringFor() =
        assertConfiguredBeside("(for <<c <- System.get_env(\"X\", \"\")>>, do: c)")

    /** A generator whose guard can't hold skips every element of a value not known. */
    fun testAGeneratorWhoseGuardCantHold() =
        assertEquals(
            "k=0 k1=1",
            keys("config :app, k: 0\nlimit = 0\nfor _ when limit > 0 <- System.get_env(), do: config(:app, k: 1)\nconfig :app, k1: 1\n", "k", "k1"),
        )

    /** A `reduce:` whose generator's guard keeps no element runs no iteration, so its accumulator stays where it started. */
    fun testAReduceWhoseGeneratorGuardCantHoldKeepsItsAccumulator() =
        assertEquals(
            "k=0 k1=1",
            keyBound("limit = 0\nk = for _ when limit > 0 <- System.get_env(), reduce: 0 do\n  acc -> acc + 1\nend\n"),
        )

    /** Nor does a `for` whose generator's guard keeps no element, so it gives `[]`. */
    fun testAForWhoseGeneratorGuardCantHoldGivesNothing() =
        assertEquals("k=[] k1=1", keyBound("limit = 0\nk = for _ when limit > 0 <- System.get_env(), do: 1\n"))

    /** Nor does one whose filter keeps no element. */
    fun testAForWhoseFilterCantHoldGivesNothing() =
        assertEquals("k=[] k1=1", keyBound("limit = 0\nk = for _ <- System.get_env(), limit > 0, do: 1\n"))

    /** Nor does a `reduce:` whose filter keeps no element, which keeps its accumulator. */
    fun testAReduceWhoseFilterCantHoldKeepsItsAccumulator() =
        assertEquals(
            "k=0 k1=1",
            keyBound("limit = 0\nk = for _ <- System.get_env(), limit > 0, reduce: 0 do\n  acc -> acc + 1\nend\n"),
        )

    /** Nor does one whose later generator's guard keeps no element. */
    fun testAForWhoseLaterGeneratorGuardCantHoldGivesNothing() =
        assertEquals(
            "k=[] k1=1",
            keyBound("limit = 0\nk = for _ <- System.get_env(), _ when limit > 0 <- System.get_env(), do: 1\n"),
        )

    /** Nor does a `for` over a bitstring whose filter keeps no byte. */
    fun testABitstringForWhoseFilterCantHoldGivesNothing() =
        assertEquals("k=[] k1=1", keyBound("limit = 0\nk = for <<_ <- System.get_env(\"X\", \"\")>>, limit > 0, do: 1\n"))

    /**
     * A generator binds its variables afresh, so its guard reads the element, not a variable of the same version the
     * clause around it bound, as a default argument's variables may be before Elixir 1.14.
     */
    fun testAGeneratorGuardInADefaultArgumentReadsTheElement() =
        assertEquals(
            "enabled=Configured k1=1",
            keys(
                "defmodule H do\n" +
                    "  def f(a, b \\\\ (for y when y != 0 <- System.get_env(), do: Config.config(:app, enabled: true))), do: {a, b}\nend\n\n" +
                    "config :app, enabled: false\nH.f(0)\nconfig :app, k1: 1\n",
                "enabled",
                "k1",
            ),
        )

    /** The generator's body reads the element too. */
    fun testAGeneratorBodyInADefaultArgumentReadsTheElement() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "defmodule H do\n" +
                    "  def f(a, b \\\\ (for y <- System.get_env(), do: Config.config(:app, k: y))), do: {a, b}\nend\n\n" +
                    "config :app, k: 0\nH.f(0)\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** A segment's size in a generator over a binary not known reads the variable bound before it. */
    fun testASegmentSizeInAGeneratorOverABinaryNotKnownReadsTheVariableBoundBefore() =
        assertEquals(
            "k=8 k1=1",
            keys("n = 8\nconfig :app, k: 8\nfor <<_::size(n) <- System.get_env(\"XS\", \"ab\")>>, do: config(:app, k: n)\nconfig :app, k1: 1\n", "k", "k1"),
        )

    /** A generator whose segments can't match any binary runs no iteration over a binary not known. */
    fun testAGeneratorWhoseSegmentsCantMatchSkipsABinaryNotKnown() =
        assertEquals("k=0 k1=1", sizedBefore("-1", "for <<_::size(n) <- System.get_env(\"X\", \"ab\")>>, do: config(:app, k: 1)\n"))

    /** A size that is known and isn't an integer matches no binary, so the generator runs no iteration. */
    fun testAGeneratorWhoseSizeIsntAnIntegerSkipsABinaryNotKnown() =
        assertEquals("k=0 k1=1", sizedBefore(":a", "for <<_::size(n) <- System.get_env(\"X\", \"ab\")>>, do: config(:app, k: 1)\n"))

    /** A size that reads an earlier segment's variable may match, as that variable isn't known. */
    fun testAGeneratorWhoseSizeReadsAnEarlierSegmentMayMatchABinaryNotKnown() =
        assertEquals(
            "k=Configured k1=1",
            keys("config :app, k: 0\nfor <<len, _::size(len) <- System.get_env(\"X\", \"ab\")>>, do: config(:app, k: 1)\nconfig :app, k1: 1\n", "k", "k1"),
        )

    /** A size that fails as a guard matches no binary, where a size is one ([BITSTRING_SIZE_EXPANDED_AS_GUARD]); on 1.11.4 such a size doesn't compile. */
    fun testAGeneratorWhoseSizeFailsAsAGuardSkipsABinaryNotKnown() {
        if (!BITSTRING_SIZE_EXPANDED_AS_GUARD.isSufficient(legLevel())) return

        assertEquals("k=0 k1=1", sizedBefore(":a", "for <<_::size(n * 8) <- System.get_env(\"X\", \"ab\")>>, do: config(:app, k: 1)\n"))
    }

    /** A segment whose spec the evaluator can't read, such as a `native` one, may match a binary not known. */
    fun testAGeneratorWhoseSegmentSpecIsntReadMayMatchABinaryNotKnown() =
        assertEquals(
            "k=Configured k1=1",
            keys("config :app, k: 0\nfor <<_::size(16)-native <- System.get_env(\"X\", \"ab\")>>, do: config(:app, k: 1)\nconfig :app, k1: 1\n", "k", "k1"),
        )

    /** A list generator whose pattern holds a bitstring that can't match runs no iteration over a value not known. */
    fun testAListGeneratorWhosePatternsBitstringCantMatchSkipsAValueNotKnown() =
        assertEquals("k=0 k1=1", sizedBefore("-1", "for {_, <<_::size(n)>>} <- System.get_env(), do: config(:app, k: 1)\n"))

    /** A known size that isn't an integer fails a list generator's pattern too. */
    fun testAListGeneratorWhosePatternsSizeIsntAnIntegerSkipsAValueNotKnown() =
        assertEquals("k=0 k1=1", sizedBefore(":a", "for {_, <<_::size(n)>>} <- System.get_env(), do: config(:app, k: 1)\n"))

    /** A `case` clause whose bitstring can't match isn't taken over a value not known. */
    fun testACaseClauseWhoseBitstringCantMatchIsntTakenOverAValueNotKnown() =
        assertEquals(
            "k=0 k1=1",
            sizedBefore("-1", "case System.get_env(\"X\", \"\") do\n  <<_::size(n)>> -> config(:app, k: 1)\n  _ -> :ok\nend\n"),
        )

    /** A `with` clause whose bitstring can't match goes to what the `with` gives where it doesn't match. */
    fun testAWithClauseWhoseBitstringCantMatchDoesntRunItsBodyOverAValueNotKnown() =
        assertEquals("k=0 k1=1", sizedBefore("-1", "with <<_::size(n)>> <- System.get_env(\"X\", \"\") do\n  config(:app, k: 1)\nend\n"))

    /** A match whose bitstring can't match raises over a value not known, so the code past it doesn't run. */
    fun testAMatchWhoseBitstringCantMatchRaisesOverAValueNotKnown() =
        assertEquals(
            "k=0 k1=1",
            sizedBefore("-1", "try do\n  <<_::size(n)>> = System.get_env(\"X\", \"\")\n  config(:app, k: 1)\nrescue\n  _ -> :ok\nend\n"),
        )

    /**
     * A size in a `fn` head over a value not known reads the variable its own earlier segment binds, not an argument
     * of the function around it, which has the same version on 1.11.4.
     */
    fun testAFnHeadsSizeReadsItsOwnSegmentNotAnOuterArgumentOverAValueNotKnown() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "defmodule H do\n" +
                    "  def f(a, b \\\\ (fn\n    <<len, _::size(len)>> -> Config.config(:app, k: 1)\n    _ -> :ok\n" +
                    "  end).(System.get_env(\"X\", <<8, 65>>))), do: {a, b}\nend\n\n" +
                    "config :app, k: 0\nH.f(-1)\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** What `k` and `k1` answer where `k` is `0`, `n` is [size], then [statements] run, then `k1` is `1`. */
    private fun sizedBefore(size: String, statements: String) =
        keys("config :app, k: 0\nn = $size\n${statements}config :app, k1: 1\n", "k", "k1")

    fun testAForWritingInItsBody() =
        assertEquals(
            "k=Configured k1=1",
            keys("config :app, k: 1\nfor {\"X\", _} <- System.get_env(), do: config(:app, k: 2)\nconfig :app, k1: 1\n", "k", "k1"),
        )

    /**
     * A second iteration reads the step the first configured and takes the other arm. Before `Config.read_config/1`,
     * its call raises where an iteration runs, which none does where `X` is empty.
     */
    fun testAForWhoseIterationsReadWhatTheOnesBeforeConfigured() =
        assertRead(
            "other:enabled=Configured k1=1",
            keys(
                "config :app, step: 0\n\nfor <<_ <- System.get_env(\"X\", \"ab\")>> do\n" +
                    "  if Config.read_config(:app)[:step] == 0, do: config(:app, step: 1), else: config(:other, enabled: true)\nend\n\n" +
                    "config :app, k1: 1\n",
                "other:enabled",
                "k1",
            ),
            before = "declined",
        )

    /**
     * A third iteration reads the accumulator the second gave and configures. Before `Config.read_config/1`, its call
     * raises where an iteration runs.
     */
    fun testAReduceWhoseIterationsReadTheAccumulatorTheOnesBeforeGave() =
        assertRead(
            "other:enabled=Configured k1=1",
            keys(
                "config :app, step: 0\nconfig :other, enabled: false\n\nfor <<_ <- System.get_env(\"X\", \"abc\")>>, reduce: :x do\n" +
                    "  acc ->\n" +
                    "    case Config.read_config(:app)[:step] do\n" +
                    "      0 ->\n        config(:app, step: 1)\n        :a\n\n" +
                    "      _ ->\n        case acc do\n          :b -> config(:other, enabled: true)\n          _ -> :ok\n        end\n\n        :b\n" +
                    "    end\nend\n\n" +
                    "config :app, k1: 1\n",
                "other:enabled",
                "k1",
            ),
            before = "declined",
        )

    /**
     * Iterations each setting a setting built from what the one before set end where the setting holds what it held.
     * Before `Config.read_config/1`, its call raises where an iteration runs.
     */
    fun testAForWhoseIterationsSetASettingBuiltFromItsValueBefore() =
        assertRead(
            "seen=Configured k1=1",
            keys(
                "config :app, seen: nil\n" +
                    "for <<c <- System.get_env(\"XS\", \"ab\")>>, do: config(:app, seen: [c | Config.read_config(:app)[:seen]])\n" +
                    "config :app, k1: 1\n",
                "seen",
                "k1",
            ),
            before = "declined",
        )

    /** A second iteration imports the file again, which raises. */
    fun testAForImportingAFileInEachIterationDeclines() =
        assertEquals(
            "declined",
            keys(
                "for <<_ <- System.get_env(\"X\", \"ab\")>>, do: import_config(\"o.exs\")\nconfig :app, k1: 1\n",
                "k1",
                extra = arrayOf(EMPTY_O_EXS),
            ),
        )

    fun testABinarySegmentThatMayNotBeABinary() =
        assertConfiguredBeside("<<System.get_env(\"X\")::binary>>")

    /** A bitstring segment of a value not known may be a bitstring that isn't a binary. */
    fun testABitstringSegmentThatMayNotBeABinary() =
        assertConfiguredBeside(
            "(case <<(if System.get_env(\"X\"), do: <<1::3>>, else: \"a\")::bitstring>> do\n  b when is_binary(b) -> :binary\n  _ -> :bits\nend)",
        )

    fun testArmsSettingValuesThatShareTheirPartsTwelveDeep() = assertSharedPartsJoin(12)

    fun testArmsSettingValuesThatShareTheirPartsSixteenDeep() = assertSharedPartsJoin(16)

    fun testArmsSettingValuesThatShareTheirPartsTwentyDeep() = assertSharedPartsJoin(20)

    fun testAnIntegerOfAnOrDefault() =
        assertConfiguredBeside("String.to_integer(System.get_env(\"X\") || \"4000\")")

    fun testAnExistingAtomThroughAPipe() =
        assertConfiguredBeside("System.get_env(\"X\", \"ok\") |> String.to_existing_atom()")

    fun testAnExistingAtom() =
        assertConfiguredBeside("String.to_existing_atom(System.get_env(\"X\", \"ok\"))")

    fun testAnAtom() =
        assertConfiguredBeside("String.to_atom(System.get_env(\"X\", \"ok\"))")

    /** `String.to_atom/1` on a receiver the compiler doesn't know, so doesn't inline, is called as the native. */
    fun testAnAtomOfAReceiverInAVariable() =
        assertEquals("k=Configured k1=1", keys("m = String\nconfig :app, k: m.to_atom(System.get_env(\"X\", \"ok\")), k1: 1\n", "k", "k1"))

    /** `Mix.Config`'s import of a path not known may set any key, so the read declines. */
    fun testAMixConfigImportOfAPathNotKnownDeclines() =
        assertEquals(
            "declined",
            keys(
                "config :app, k: 0\nMix.Config.__import__!(System.get_env(\"X\", \"o.exs\"), __DIR__)\nconfig :app, k1: 1\n",
                "k",
                "k1",
                extra = arrayOf("config/o.exs" to "import Config\nconfig :app, k: 2\n"),
            ),
        )

    fun testAnIntegerThroughAPipe() =
        assertConfiguredBeside("System.get_env(\"X\", \"1\") |> String.to_integer()")

    fun testAnInterpolation() =
        assertConfiguredBeside("\"#{System.get_env(\"X\")}/y\"")

    fun testAString() =
        assertConfiguredBeside("to_string(System.get_env(\"X\"))")

    fun testAComparison() =
        assertConfiguredBeside("System.get_env(\"X\") == \"true\"")

    fun testAnIn() = assertConfiguredBeside("System.get_env(\"X\") in [\"a\"]")

    fun testAStrictAnd() =
        assertConfiguredBeside("(System.get_env(\"X\") == \"1\") and true")

    fun testAPutUnderAnUnknownKey() =
        assertConfiguredBeside("map_size(:maps.put(System.get_env(\"X\"), 1, %{nil => 0}))")

    fun testAWarningOfAnUnknown() = assertEquals("k1=1", keys("IO.warn(System.get_env(\"X\", \"w\"))\nconfig :app, k1: 1\n", "k1"))

    /** `Access.get/2` runs a struct's `fetch/2`, which may write, so given a value not known it declines. */
    fun testAGetFromAStructOverAnUnknownDeclines() =
        assertStructDeclines(
            "defmodule S do\n  defstruct []\n\n  def fetch(_, k) do\n    Config.config(:app, k: 2)\n" +
                "    Config.config(:app, [{String.to_atom(k), 1}])\n    {:ok, 0}\n  end\nend\n\n",
            "config :app, j: Access.get(struct(S), System.get_env(\"X\", \"j\")), k1: 1\n",
            struct = "",
        )

    /** A value not known that may be a struct of a module the file defines may run its `fetch/2`. */
    fun testAGetFromAValueThatMayBeAStructDeclines() =
        assertStructDeclines(FETCH, "config :app, j: Access.get(max(System.get_env(\"X\"), struct(S)), :j), k1: 1\n", struct = "")

    /** A struct whose module isn't known may be one the file defines, whose `fetch/2` it runs. */
    fun testAGetFromAStructOfAModuleNotKnownDeclines() =
        assertStructDeclines(
            FETCH,
            "config :app, j: Access.get(%{__struct__: String.to_atom(System.get_env(\"X\", \"Elixir.S\"))}, :j), k1: 1\n",
            struct = "",
        )

    /** `to_string/1` dispatches on a struct, so of a value that may be a struct of a module the file defines it declines. */
    fun testAStringOfAValueThatMayBeAStructDeclines() =
        assertStructDeclines(
            "defmodule String.Chars.S do\n  def to_string(_) do\n$WRITES",
            "config :app, j: to_string(max(System.get_env(\"X\"), struct(S))), k1: 1\n",
            struct = FIELDLESS,
        )

    /** `Enum.member?/2` dispatches on a struct too. */
    fun testAMemberOfAValueThatMayBeAStructDeclines() =
        assertStructDeclines(
            "defmodule Enumerable.S do\n  def member?(_, _) do\n" +
                "    Config.config(:app, k: 2)\n    {:ok, true}\n  end\n\n  def count(_), do: {:error, __MODULE__}\n" +
                "  def slice(_), do: {:error, __MODULE__}\n  def reduce(_, acc, _), do: acc\nend\n\n",
            "config :app, j: Enum.member?(max(System.get_env(\"X\"), struct(S)), 1), k1: 1\n",
            struct = FIELDLESS,
        )

    /** A generator calls its enumerable's `Enumerable` implementation too. */
    fun testAForOverAValueThatMayBeAStructDeclines() =
        assertStructDeclines(
            "defmodule Enumerable.S do\n  def reduce(_, {:cont, acc}, _) do\n    Config.config(:app, k: 2)\n    {:done, acc}\n  end\nend\n\n",
            "config :app, j: (for _ <- max(System.get_env(\"X\"), struct(S)), do: :ok), k1: 1\n",
            struct = FIELDLESS,
        )

    /** `to_string/1` runs a known struct's `String.Chars` implementation, though a field isn't known. */
    fun testAStringOfAStructHoldingAnUnknownDeclines() =
        assertStructDeclines(CHARS, "config :app, j: to_string(Map.put(struct(S), :a, System.get_env(\"X\"))), k1: 1\n")

    /** `IO.warn/1` makes a string of what it is given as `to_string/1` does. */
    fun testAWarningOfAValueThatMayBeAStructDeclines() =
        assertStructDeclines(CHARS, "IO.warn(max(System.get_env(\"X\"), struct(S)))\nconfig :app, k1: 1\n")

    /** `inspect/1` runs the `Inspect` implementation of every struct its term holds. */
    fun testAnInspectionOfAListHoldingAValueThatMayBeAStructDeclines() =
        assertStructDeclines(INSPECT, "config :app, j: inspect([max(System.get_env(\"X\"), struct(S))]), k1: 1\n")

    /** A known struct's `Inspect` implementation may write too. */
    fun testAnInspectionOfAStructDeclines() = assertStructDeclines(INSPECT, "config :app, j: inspect(struct(S)), k1: 1\n")

    /** What the file wrote to an app's env may be a struct of a module it defines, as what it reads back may. */
    fun testAStringOfAnAppEnvTheFileWroteAStructToDeclines() =
        assertStructDeclines(CHARS, "Application.put_env(:other, :s, struct(S))\nconfig :app, j: to_string(Application.get_env(:other, :s)), k1: 1\n")

    /**
     * What the file wrote to an app's env may be options holding an `:inspect_fun`, which `inspect/2` runs: Elixir sets
     * `k` to 2.
     */
    fun testAnInspectionWithOptionsTheFileWroteToAnAppEnvDeclines() =
        assertInspectionDeclines(
            "Application.put_env(:other, :opts, inspect_fun: $INSPECT_FUN)\n" +
                "config :app, j: inspect(1, Application.get_env(:other, :opts)), k1: 1\n",
        )

    /** As above, an option the file wrote to an app's env, in options that are known otherwise. */
    fun testAnInspectionWithAnOptionTheFileWroteToAnAppEnvDeclines() =
        assertInspectionDeclines(
            "Application.put_env(:other, :opt, {:inspect_fun, $INSPECT_FUN})\n" +
                "config :app, j: inspect(1, [Application.get_env(:other, :opt)]), k1: 1\n",
        )

    /** As above, an option whose key and value the file wrote to an app's env. */
    fun testAnInspectionWithAnOptionKeyTheFileWroteToAnAppEnvDeclines() =
        assertInspectionDeclines(
            "Application.put_env(:other, :key, :inspect_fun)\nApplication.put_env(:other, :f, $INSPECT_FUN)\n" +
                "config :app, j: inspect(1, [{Application.get_env(:other, :key), Application.get_env(:other, :f)}]), k1: 1\n",
        )

    /** As above, options whose tail the file wrote to an app's env, which makes the whole list one value not known. */
    fun testAnInspectionWithOptionsWhoseTailTheFileWroteToAnAppEnvDeclines() =
        assertInspectionDeclines(
            "Application.put_env(:other, :opts, inspect_fun: $INSPECT_FUN)\n" +
                "config :app, j: inspect(1, [{:limit, 5} | Application.get_env(:other, :opts)]), k1: 1\n",
        )

    /**
     * Options whose tail is a binary from the environment: Elixir raises `FunctionClauseError` for every value, where
     * `Kernel.inspect/2` reduces over them.
     */
    fun testAnInspectionWithOptionsWhoseTailIsABinaryNotKnownDeclines() =
        assertInspectionDeclines("_ = inspect(1, [{:limit, 5} | System.get_env(\"X\", \"\")])\nconfig :app, k1: 1\n")

    fun testAStringOfAFetchedAppEnvTheFileWroteAStructToDeclines() =
        assertStructDeclines(CHARS, "Application.put_env(:other, :s, struct(S))\nconfig :app, j: to_string(Application.fetch_env!(:other, :s)), k1: 1\n")

    fun testAStringOfACompileEnvTheFileWroteAStructToDeclines() =
        assertStructDeclines(CHARS, "Application.put_env(:other, :s, struct(S))\nconfig :app, j: to_string(${compileEnv(":s")}), k1: 1\n")

    /** `compile_env/4` fetches a path's next key from the struct the file wrote through its `fetch/2`, which may write. */
    fun testACompileEnvPathThroughAnAppEnvTheFileWroteAStructToDeclines() =
        assertStructDeclines(
            FETCH,
            "Application.put_env(:other, :s, struct(S))\nconfig :app, j: ${compileEnv("[:s, :key]")}, k1: 1\n",
            struct = "",
        )

    /** As where the path's first key isn't known: it may be the one the struct is under. */
    fun testACompileEnvOfAKeyNotKnownPastAnAppEnvTheFileWroteAStructToDeclines() =
        assertStructDeclines(
            FETCH,
            "Application.put_env(:other, :s, %{__struct__: S})\n" +
                "config :app, j: ${compileEnv("[String.to_atom(System.get_env(\"K\", \"s\")), :key]")}, k1: 1\n",
            struct = "",
        )

    /** As where the path isn't known. */
    fun testACompileEnvOfAPathNotKnownPastAnAppEnvTheFileWroteAStructToDeclines() =
        assertStructDeclines(
            FETCH,
            "Application.put_env(:other, :s, %{__struct__: S})\n" +
                "config :app, j: ${compileEnv("if(System.get_env(\"K\"), do: [:s, :key], else: [:s])")}, k1: 1\n",
            struct = "",
        )

    /** As where the path's tail isn't known. */
    fun testACompileEnvOfATailNotKnownPastAnAppEnvTheFileWroteAStructToDeclines() =
        assertStructDeclines(
            FETCH,
            "Application.put_env(:other, :s, %{__struct__: S})\n" +
                "config :app, j: ${compileEnv("[:s | if(System.get_env(\"K\"), do: [:key], else: [])]")}, k1: 1\n",
            struct = "",
        )

    /** As where the app isn't known: it may be the one the file wrote. */
    fun testACompileEnvOfAnAppNotKnownPastAnAppEnvTheFileWroteAStructToDeclines() =
        assertStructDeclines(
            FETCH,
            "Application.put_env(:other, :s, %{__struct__: S})\n" +
                "config :app, j: ${legCompileEnv("String.to_atom(System.get_env(\"A\", \"other\"))", "[:s, :key]", "0")}, k1: 1\n",
            struct = "",
        )

    /** `get_env/2` of an app not known may read the struct the file wrote. */
    fun testAStringOfAnAppEnvOfAnAppNotKnownPastOneTheFileWroteAStructToDeclines() =
        assertStructDeclines(
            CHARS,
            "Application.put_env(:other, :s, %{__struct__: S})\n" +
                "config :app, j: to_string(Application.get_env(String.to_atom(System.get_env(\"A\", \"other\")), :s)), k1: 1\n",
        )

    /** As `fetch_env!/2` may. */
    fun testAStringOfAFetchedAppEnvOfAnAppNotKnownPastOneTheFileWroteAStructToDeclines() =
        assertStructDeclines(
            CHARS,
            "Application.put_env(:other, :s, %{__struct__: S})\n" +
                "config :app, j: to_string(Application.fetch_env!(String.to_atom(System.get_env(\"A\", \"other\")), :s)), k1: 1\n",
        )

    /**
     * From [COMPILE_ENV_TAKES_ENV_FIRST], `compile_env/4` matches its env first, so a binary raises before the struct's
     * `fetch/2` runs; before it, `__compile_env__/4` reads its env only after `traverse_env/2`, which runs it.
     */
    fun testACompileEnvGivenABinaryEnvPastAnAppEnvTheFileWroteAStructTo() {
        val compileEnv = legCompileEnv(":other", "[:s, :key]", "0", env = "\"\"")

        assertEquals(
            if (COMPILE_ENV_TAKES_ENV_FIRST.isSufficient(legLevel())) "k=0 k1=1" else "declined",
            keys(
                FETCH + "config :app, k: 0\nApplication.put_env(:other, :s, %{__struct__: S})\n\n" +
                    "try do\n  _ = $compileEnv\nrescue\n  _ -> :ok\nend\n\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )
    }

    /** `traverse_env/2` fetches each key past the first from a value not known, which holds none of the keys. */
    fun testACompileEnvPathOfThreeKeysPastAValueNotKnown() = assertConfiguredBeside(legCompileEnv(":dep", "[:k, :a, :b]", "0"))

    /** As `Access.get/2` does. */
    fun testAnAccessChainPastAValueNotKnown() = assertConfiguredBeside("Application.get_env(:dep, :k)[:a][:b]")

    /** `Keyword.get/2` returns a part of its list, never its key. */
    fun testAKeywordGetPastAValueNotKnown() = assertConfiguredBeside("Keyword.get(Application.get_env(:dep, :k), :a)[:b]")

    /** `Keyword.get/3` returns a part of its list or its default, never its key. */
    fun testAKeywordGetWithADefaultPastAValueNotKnown() =
        assertConfiguredBeside("to_string(Keyword.get(Application.get_env(:dep, :k), :a, \"d\"))")

    /** `Keyword.fetch/2` returns a part of its list, never its key. */
    fun testAKeywordFetchPastAValueNotKnown() =
        assertConfiguredBeside("elem(Keyword.fetch(Application.get_env(:dep, :k), :a), 1)[:b]")

    /** `Keyword.fetch!/2` returns a part of its list, never its key. */
    fun testAKeywordBangFetchPastAValueNotKnown() = assertConfiguredBeside("Keyword.fetch!(Application.get_env(:dep, :k), :a)[:b]")

    /** `Keyword.pop/2` returns parts of its list, never its key. */
    fun testAKeywordPopPastAValueNotKnown() = assertConfiguredBeside("elem(Keyword.pop(Application.get_env(:dep, :k), :a), 0)[:b]")

    /** `Keyword.pop/3` returns parts of its list or its default, never its key. */
    fun testAKeywordPopWithADefaultPastAValueNotKnown() =
        assertConfiguredBeside("to_string(elem(Keyword.pop(Application.get_env(:dep, :k), :a, \"d\"), 0))")

    /** `Keyword.has_key?/2` returns a boolean. */
    fun testAKeywordHasKeyPastAValueNotKnown() = assertConfiguredBeside("to_string(Keyword.has_key?(Application.get_env(:dep, :k), :a))")

    /** `Keyword.delete/2` returns a part of its list, never its key. */
    fun testAKeywordDeletePastAValueNotKnown() = assertConfiguredBeside("Keyword.delete(Application.get_env(:dep, :k), :a)[:c]")

    /** `Keyword.take/2` returns a part of its list, never its keys. */
    fun testAKeywordTakePastAValueNotKnown() = assertConfiguredBeside("Keyword.take(Application.get_env(:dep, :k), [:a])[:a]")

    /** `Keyword.drop/2` returns a part of its list, never its keys. */
    fun testAKeywordDropPastAValueNotKnown() = assertConfiguredBeside("Keyword.drop(Application.get_env(:dep, :k), [:c])[:a]")

    /** `maps:get/2` returns a part of its map, never its key. */
    fun testAMapsGetPastAValueNotKnown() = assertConfiguredBeside(":maps.get(:a, Application.get_env(:dep, :k))[:b]")

    /** `maps:get/3` returns a part of its map or its default, never its key. */
    fun testAMapsGetWithADefaultPastAValueNotKnown() =
        assertConfiguredBeside("to_string(:maps.get(:a, Application.get_env(:dep, :k), \"d\"))")

    /** `maps:find/2` returns a part of its map, never its key. */
    fun testAMapsFindPastAValueNotKnown() = assertConfiguredBeside("elem(:maps.find(:a, Application.get_env(:dep, :k)), 1)[:b]")

    /** `maps:is_key/2` returns a boolean. */
    fun testAMapsIsKeyPastAValueNotKnown() = assertConfiguredBeside("to_string(:maps.is_key(:a, Application.get_env(:dep, :k)))")

    /** `lists:keyfind/3` returns a part of its list, never its key. */
    fun testAListsKeyfindPastAValueNotKnown() = assertConfiguredBeside("elem(:lists.keyfind(:a, 1, Application.get_env(:dep, :k)), 1)[:b]")

    /** `lists:keymember/3` returns a boolean. */
    fun testAListsKeymemberPastAValueNotKnown() = assertConfiguredBeside("to_string(:lists.keymember(:a, 1, Application.get_env(:dep, :k)))")

    /** `lists:member/2` returns a boolean. */
    fun testAListsMemberPastAValueNotKnown() = assertConfiguredBeside("to_string(:lists.member(:a, Application.get_env(:dep, :k)))")

    /** `ordsets:is_element/2` returns a boolean. */
    fun testAnOrdsetsIsElementPastAValueNotKnown() =
        assertConfiguredBeside("to_string(:ordsets.is_element(:a, Application.get_env(:dep, :k)))")

    /** `Enum.member?/2` returns a boolean. */
    fun testAnEnumMemberPastAValueNotKnown() = assertConfiguredBeside("to_string(Enum.member?(Application.get_env(:dep, :k), :a))")

    /** `lists:keydelete/3` returns a part of its list, never its key. */
    fun testAListsKeydeletePastAValueNotKnown() = assertConfiguredBeside(":lists.keydelete(:a, 1, Application.get_env(:dep, :k))[:c]")

    /** `erlang:map_get/2` returns a part of its map, never its key. */
    fun testAMapGetPastAValueNotKnown() = assertConfiguredBeside(":erlang.map_get(:a, Application.get_env(:dep, :k))[:b]")

    /** `erlang:is_map_key/2` returns a boolean. */
    fun testAnIsMapKeyPastAValueNotKnown() = assertConfiguredBeside("to_string(:erlang.is_map_key(:a, Application.get_env(:dep, :k)))")

    /** `elem/2`'s index is a number, which holds no module. */
    fun testAnElemPastAValueNotKnown() = assertConfiguredBeside("elem(Application.get_env(:dep, :k), 0)[:b]")

    /** A `try` that may rescue the fetch doesn't stop its write. */
    fun testACompileEnvPathInATryThroughAnAppEnvTheFileWroteAStructToDeclines() =
        assertStructDeclines(
            FETCH,
            "Application.put_env(:other, :s, struct(S))\n\ntry do\n  _ = ${compileEnv("[:s, :key]")}\nrescue\n  _ -> :ok\nend\n\n" +
                "config :app, k1: 1\n",
            struct = "",
        )

    /** `Mix.Project`'s paths read the config they are given through `config[key]`, which runs a struct's `fetch/2`. */
    fun testABuildPathOfAnAppEnvTheFileWroteAStructToDeclines() = assertMixProjectPathDeclines("build_path")

    fun testAnAppPathOfAnAppEnvTheFileWroteAStructToDeclines() = assertMixProjectPathDeclines("app_path")

    /** `deps_path/1` raises on the struct's `0` once its `fetch/2` has written. */
    fun testADepsPathInATryOfAnAppEnvTheFileWroteAStructToDeclines() =
        assertStructDeclines(
            FETCH,
            "Application.put_env(:other, :s, struct(S))\n\ntry do\n  _ = Mix.Project.deps_path(Application.get_env(:other, :s))\n" +
                "rescue\n  _ -> :ok\nend\n\nconfig :app, k1: 1\n",
            struct = "",
        )

    private fun assertMixProjectPathDeclines(function: String) =
        assertStructDeclines(
            FETCH,
            "Application.put_env(:other, :s, struct(S))\nconfig :app, j: Mix.Project.$function(Application.get_env(:other, :s)), k1: 1\n",
            struct = "",
        )

    /** `compile_env/4`'s read of [path] in `:other`, else `0`. */
    private fun compileEnv(path: String): String = legCompileEnv(":other", path, "0")

    /** A setting `Config` merges over one that isn't a keyword list is the new value, which may be the struct it read. */
    fun testAStringOfASettingMergedFromAnAppEnvTheFileWroteAStructToDeclines() =
        assertStructDeclines(
            CHARS,
            "Application.put_env(:other, :s, struct(S))\nconfig :app, s: 1\nconfig :app, s: Application.get_env(:other, :s)\n" +
                "[k: 0, s: s] = Config.read_config(:app)\nconfig :app, j: to_string(s), k1: 1\n",
        )

    /**
     * A setting that isn't a keyword list, overridden by a value not known, is that value: `Config`'s deep merge keeps the
     * value it replaces only where both are keyword lists.
     */
    fun testASettingOverriddenByAValueNotKnownDropsTheValueItReplaced() =
        assertRead(
            "j=Configured k1=1",
            keys(
                "config :app, name: nil\nconfig :app, name: System.get_env(\"NAME\", \"demo\")\n" +
                    "config :app, j: to_string(Config.read_config(:app)[:name]), k1: 1\n",
                "j",
                "k1",
            ),
        )

    /** A list holding a value not known, then a value not known: the setting reads as one not known. */
    fun testAListHoldingAValueNotKnownOverriddenByAValueNotKnownReadsTheLaterValue() =
        assertEquals(
            "hosts=Configured k1=1",
            keys(
                "config :app, hosts: [System.get_env(\"A\")]\nconfig :app, hosts: System.get_env(\"B\")\n" +
                    "config :app, k1: 1\n",
                "hosts",
                "k1",
            ),
        )

    /** A list of a pair whose key isn't known, then a value not known: the setting reads as one not known. */
    fun testAPairWithAKeyNotKnownOverriddenByAValueNotKnownReadsTheLaterValue() =
        assertEquals(
            "opts=Configured k1=1",
            keys(
                "config :app, opts: [{System.get_env(\"A\"), 1}]\nconfig :app, opts: System.get_env(\"B\")\n" +
                    "config :app, k1: 1\n",
                "opts",
                "k1",
            ),
        )

    /** A list holding a value not known, then a known list that isn't a keyword list: the setting reads the later. */
    fun testAListHoldingAValueNotKnownOverriddenByAListThatIsNoKeywordListReadsTheLaterValue() =
        assertEquals(
            "hosts=[\"b\"] k1=1",
            keys(
                "config :app, hosts: [System.get_env(\"A\")]\nconfig :app, hosts: [\"b\"]\nconfig :app, k1: 1\n",
                "hosts",
                "k1",
            ),
        )

    /** A clause that may match a value not known binds the struct given beside it. */
    fun testAStringOfAKnownArgumentBesideOneNotKnownThatMayBeAStructDeclines() =
        assertStructDeclines(
            CHARS,
            "defmodule H do\n  def f(nil, s), do: to_string(s)\n  def f(_, _), do: :ok\nend\n\n" +
                "H.f(System.get_env(\"X\"), %{__struct__: S})\nconfig :app, k1: 1\n",
        )

    /** A pass over a binary not known that holds a struct in the accumulator runs again, where a string of it may be taken. */
    fun testAStringOfAStructAnEarlierPassOfAReduceSetDeclines() =
        assertStructDeclines(
            CHARS,
            "for <<_ <- System.get_env(\"X\", \"ab\")>>, reduce: 0 do\n  acc ->\n    case acc do\n" +
                "      0 -> %{__struct__: S}\n      s -> to_string(s)\n    end\nend\n\nconfig :app, k1: 1\n",
        )

    /** The passes of a reduce over a binary not known meet the struct they hold again, and end. */
    fun testAReduceOverABinaryNotKnownWhoseAccumulatorHoldsAStructEnds() =
        assertEquals(
            "k=0 k1=1",
            keys(
                STRUCT + CHARS + "config :app, k: 0\n" +
                    "for <<_ <- System.get_env(\"X\", \"ab\")>>, reduce: 0 do\n  acc ->\n    case acc do\n" +
                    "      0 -> %{__struct__: S}\n      s -> s\n    end\nend\n\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** A reduce over a value not known whose body builds a tuple around its accumulator ends. */
    fun testAReduceOverAValueNotKnownThatBuildsATupleAroundItsAccumulatorEnds() =
        assertConfiguredBeside("(for {_, v} <- System.get_env(), reduce: {[], []} do {a, b} -> {[v | a], b} end)")

    /** A reduce over a value not known whose body puts a new function in its accumulator ends. */
    fun testAReduceOverAValueNotKnownThatPutsAFunctionInItsAccumulatorEnds() =
        assertConfiguredBeside("(for {_, v} <- System.get_env(), reduce: [] do acc -> [fn -> v end | acc] end)")

    /** A reduce over a value not known that configures a tuple around its accumulator ends. */
    fun testAReduceOverAValueNotKnownThatConfiguresATupleAroundItsAccumulatorEnds() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "for {_, v} <- System.get_env(), reduce: {[], []} do\n  {a, b} ->\n    config :app, k: {a}\n" +
                    "    {[v | a], b}\nend\n\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** A setting the arms set apart may be the struct one of them set. */
    fun testAStringOfASettingTheArmsJoinThatMayBeAStructDeclines() =
        assertStructDeclines(
            CHARS,
            "if System.get_env(\"X\") do\n  config :app, s: struct(S)\nelse\n  config :app, s: 1\nend\n\n" +
                "config :app, j: to_string(Config.read_config(:app)[:s]), k1: 1\n",
        )

    /**
     * Elixir reads `k: 2` from `__escape__/1`'s first release, where the escape runs it, and `k: 0` before. The read
     * declines, but on 1.18.4, where the plugin escapes the struct as a map.
     */
    fun testAnEscapeOfAStructHoldingAnUnknown() =
        assertEquals(
            if (ESCAPED_MAP_ENTRIES_QUOTED_APART.isSufficient(legLevel()) && !REGEX_ESCAPED_IN_FIXED_FIELD_ORDER.isSufficient(legLevel())) {
                "k=0 k1=1"
            } else {
                "declined"
            },
            keys(ESCAPE + "config :app, k: 0\nconfig :app, j: Macro.escape(struct(S, a: System.get_env(\"X\"))), k1: 1\n", "k", "k1"),
        )

    /** A struct whose module has no `__escape__/1` is escaped as a map, with no code of its own. */
    fun testAnEscapeOfAStructBesideAnUnknown() =
        assertEquals("k=Configured k1=1", keys(STRUCT + "config :app, k: Macro.escape({%{__struct__: S, a: nil}, System.get_env(\"X\")}), k1: 1\n", "k", "k1"))

    /** A struct with no `Inspect` implementation is inspected by Elixir's own. */
    fun testAnInspectionOfAStructWithNoImplementation() =
        assertEquals(
            "k=Configured k1=1",
            keys(STRUCT + "config :app, k: inspect(%{__struct__: S, a: nil}, limit: String.to_integer(System.get_env(\"X\", \"5\"))), k1: 1\n", "k", "k1"),
        )

    /** `Enum` enumerates a range itself. */
    fun testAMemberOfARangeOfAValueNotKnown() =
        assertConfiguredBeside("Enum.member?(1..2, String.to_integer(System.get_env(\"X\", \"1\")))")

    /** `String.Chars` has no implementation for a tuple, nor a fallback, so a string of one raises whatever it holds. */
    fun testAStringOfATupleRaises() = assertEquals("raised", keyBeside("to_string({System.get_env(\"X\")})"))

    /** Nor for a map that isn't a struct. */
    fun testAStringOfAMapRaises() = assertEquals("raised", keyBeside("to_string(%{a: System.get_env(\"X\")})"))

    /** Nor for a function. */
    fun testAStringOfAFunctionRaises() = assertEquals("raised", keyBeside("to_string(fn -> System.get_env(\"X\") end)"))

    /** A guard raises nothing, so the `if` over a system read leaves what follows certain, and its raise is Elixir's. */
    fun testARaiseAfterAnIfOverASystemReadRaises() =
        assertEquals(
            "raised",
            keys(
                "if System.get_env(\"X\") do\n  config :app, a: 1\nelse\n  config :app, a: 2\nend\n\n" +
                    "config :app, k: String.to_integer(\"x\")\n",
                "k",
            ),
        )

    /** Nor does a comparison in a clause's guard. */
    fun testARaiseAfterAGuardOverASystemReadRaises() =
        assertEquals(
            "raised",
            keys(
                "case System.get_env(\"X\") do\n  x when x == \"y\" -> config :app, a: 1\n  _ -> config :app, a: 2\nend\n\n" +
                    "config :app, k: String.to_integer(\"x\")\n",
                "k",
            ),
        )

    /** Nor does a binary built in a clause's guard from a value that may not be one. */
    fun testARaiseAfterAGuardBuildingABinaryOverASystemReadRaises() =
        assertEquals(
            "raised",
            keys(
                "case System.get_env(\"X\") do\n  x when <<x::binary, \"a\">> == \"ya\" -> config :app, a: 1\n  _ -> config :app, a: 2\nend\n\n" +
                    "config :app, k: String.to_integer(\"x\")\n",
                "k",
            ),
        )

    /** The raise is `Protocol.UndefinedError`, which a rescue of it takes. */
    fun testAStringOfATupleRaisesAnUndefinedProtocol() =
        assertEquals(
            "k=:u k1=1",
            keyBeside("(try do to_string({System.get_env(\"X\")}) rescue Protocol.UndefinedError -> :u end)"),
        )

    /** `IO.warn/1,2` makes a string of a message that isn't a list as `to_string/1` does. */
    fun testAWarningOfATupleRaises() {
        assertEquals("raised", keys("IO.warn({System.get_env(\"X\")})\nconfig :app, k1: 1\n", "k1"))
        assertEquals("raised", keys("IO.warn({System.get_env(\"X\")}, [])\nconfig :app, k1: 1\n", "k1"))
    }

    /** A project may implement `String.Chars` for a tuple, and then that implementation runs. */
    fun testAStringOfATupleAProjectImplementsDeclines() {
        assertStructDeclines(TUPLE_CHARS, "config :app, j: to_string({System.get_env(\"X\")}), k1: 1\n", struct = "")
        assertStructDeclines(TUPLE_CHARS, "IO.warn({System.get_env(\"X\")})\nconfig :app, k1: 1\n", struct = "")
    }

    /** And `Enumerable`, for a tuple or an atom, which Elixir doesn't enumerate. */
    fun testAnEnumerationOfATupleAProjectImplementsDeclines() {
        assertStructDeclines(TUPLE_ENUMERABLE, "config :app, j: Enum.member?({System.get_env(\"X\")}, 1), k1: 1\n", struct = "")
        assertStructDeclines(
            TUPLE_ENUMERABLE.replace("Tuple", "Atom"),
            "config :app, j: Enum.member?(:a, System.get_env(\"X\")), k1: 1\n",
            struct = "",
        )
    }

    /** A value not known may be of any type, a tuple among them. */
    fun testAStringOfAValueNotKnownAProjectImplementsDeclines() =
        assertStructDeclines(TUPLE_CHARS, "config :app, j: to_string(:inet.gethostname()), k1: 1\n", struct = "")

    /** And enumerated, by `Enum` or a generator; a binary not known is a `BitString`. */
    fun testAnEnumerationOfAValueNotKnownAProjectImplementsDeclines() {
        assertStructDeclines(TUPLE_ENUMERABLE, "config :app, j: Enum.member?(:inet.gethostname(), 1), k1: 1\n", struct = "")
        assertStructDeclines(TUPLE_ENUMERABLE, "config :app, j: (for x <- :inet.gethostname(), do: x), k1: 1\n", struct = "")
        assertStructDeclines(
            TUPLE_ENUMERABLE.replace("Tuple", "BitString"),
            "config :app, j: Enum.member?(inspect(System.get_env(\"X\")), 1), k1: 1\n",
            struct = "",
        )
    }

    /** `IO.warn/2` at an env makes a string of its message. */
    fun testAWarningOfATupleAtAnEnvRaises() = assertWarnedOfATupleAt("__ENV__")

    /** And at options, whether or not they name a file. */
    fun testAWarningOfATupleWithOptionsRaises() {
        assertWarnedOfATupleAt("file: \"a.exs\"")
        assertWarnedOfATupleAt("line: 1")
    }

    /** Before `IO.warn/2` takes an env, one raises `FunctionClauseError`, whatever the message. */
    fun testAWarningAtAnEnvNoClauseTakesRaises() {
        if (IO_WARN_TAKES_AN_ENV_OR_OPTIONS.isSufficient(legLevel())) return

        assertEquals("k=:fc k1=1", warned("System.get_env(\"X\")", "__ENV__"))
    }

    /** `IO.warn/2` matches its second argument first: one no clause takes raises `FunctionClauseError`, whatever the message. */
    fun testAWarningWithASecondArgumentNoClauseTakesRaisesFirst() =
        assertEquals(
            "k=:fc k1=1",
            warned("{System.get_env(\"X\")}", ":x", rescues = "rescue FunctionClauseError -> :fc end"),
        )

    /** Options that name no file warn as `[]` does, once `IO.warn/2` takes options. */
    fun testAWarningWithOptionsNamingNoFile() {
        val expected = if (IO_WARN_TAKES_AN_ENV_OR_OPTIONS.isSufficient(legLevel())) "k=:ok k1=1" else "k=:fc k1=1"

        assertEquals(expected, warned("\"w\"", "line: 1"))
        assertEquals(expected, warned("\"w\"", "file: nil, line: 1"))
    }

    /**
     * `IO.warn/2` reads options naming a file in Elixir's order: `:file`, `:module`, `:function` and `:line`, then the
     * message, before [IO_WARN_OPTIONS_TAKE_A_COLUMN]; `:file`, `:line` and `:column`, then the message, from it. An
     * option `Access.get/2` can't read raises `CaseClauseError` where it is read.
     */
    fun testAWarningReadsItsOptionsInElixirsOrder() {
        val (column, module) = when {
            IO_WARN_OPTIONS_TAKE_A_COLUMN.isSufficient(legLevel()) -> ":cc" to ":u"
            IO_WARN_TAKES_AN_ENV_OR_OPTIONS.isSufficient(legLevel()) -> ":u" to ":cc"
            else -> ":fc" to ":fc"
        }

        assertEquals("k=$column k1=1", warnedOfATupleWithOptions("{:column, 1, 2}"))
        assertEquals("k=$module k1=1", warnedOfATupleWithOptions("{:module, 1, 2}"))
    }

    /**
     * From [IO_WARN_READS_THE_MESSAGE_FIRST], `IO.warn/2` takes an env only with a `:line` and a `:file`, so a map
     * without them raises `FunctionClauseError`. On the versions before, the env clause takes it and
     * `Macro.Env.stacktrace/1` reads the keys it lacks, which declines.
     */
    fun testAWarningAtAnEnvWithoutALineOrFile() {
        val expected = when {
            IO_WARN_READS_THE_MESSAGE_FIRST.isSufficient(legLevel()) -> "k=:fc k1=1"
            IO_WARN_TAKES_AN_ENV_OR_OPTIONS.isSufficient(legLevel()) -> "declined"
            else -> "k=:fc k1=1"
        }

        assertEquals(expected, warned("\"w\"", "%{__struct__: Macro.Env}"))
    }

    /** A native given a function doesn't run it, though what it gives may be the function. */
    fun testAFunctionGivenANativeIsntRun() =
        assertEquals(
            "k=1 k1=1",
            keys("config :app, k: 1\n_ = max(System.get_env(\"X\"), fn -> Config.config(:app, k: 2) end)\nconfig :app, k1: 1\n", "k", "k1"),
        )

    /** A call of what a native gave, which may be the function it was given, declines. */
    fun testAFunctionGivenANativeCalledLaterDeclines() =
        assertEquals(
            "declined",
            keys(
                "config :app, k: 1\nf = max(System.get_env(\"X\"), fn -> Config.config(:app, k: 2) end)\nf.()\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** The variables a declined clause's patterns bind hold one value not known, but two of them may differ. */
    fun testTwoVariablesOfOneDeclinedClauseCompared() = assertComparedFromOneClause("a == b")

    fun testTwoVariablesOfOneDeclinedClauseComparedStrictly() = assertComparedFromOneClause("a === b")

    fun testTwoVariablesOfOneDeclinedClausePinned() = assertComparedFromOneClause("match?(^a, b)")

    /** An atom made in an arm that may not be taken may not exist past it, so a lookup of it may raise. */
    fun testAnAtomMadeInAnArmThatMayNotBeTakenMayNotExistPastIt() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "config :app, k: 0\n\ndefmodule H do\n  def f(name) do\n    if System.get_env(\"X\"), do: String.to_atom(name)\n" +
                    "    String.to_existing_atom(name)\n    Config.config(:app, k: 1)\n  end\nend\n\n" +
                    "try do\n  H.f(\"atom_made_in_an_arm\")\nrescue\n  _ -> :ok\nend\n\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** `0.0` and `-0.0` are two terms: Elixir writes one or the other. */
    fun testArmsGivingZerosOfTwoSigns() =
        assertConfiguredBeside("(if System.get_env(\"X\"), do: 0.0, else: -0.0)")

    /** `k`'s first value, then [statements], whose `inspect/2` declines. */
    private fun assertInspectionDeclines(statements: String) =
        assertEquals("declined", keys("config :app, k: 0\n$statements", "k", "k1"))

    /** [comparison] of `a` and `b`, the first bytes of a binary from the environment and of `"1"`. */
    private fun assertComparedFromOneClause(comparison: String) =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "f = fn <<a, _::binary>>, <<b, _::binary>> -> $comparison end\nconfig :app, k: f.(System.get_env(\"X\", \"1\"), \"1\"), k1: 1\n",
                "k",
                "k1",
            ),
        )

    /**
     * `IO.warn/2` of a tuple, which `String.Chars` doesn't take, at [location]: it raises `Protocol.UndefinedError`, and
     * `FunctionClauseError` before `IO.warn/2` takes an env or options.
     */
    private fun assertWarnedOfATupleAt(location: String) =
        assertEquals(
            if (IO_WARN_TAKES_AN_ENV_OR_OPTIONS.isSufficient(legLevel())) "k=:u k1=1" else "k=:fc k1=1",
            warned("{System.get_env(\"X\")}", location),
        )

    /** What `IO.warn/2` of a tuple raises at options naming a file and holding [option] after it, as [warned] answers. */
    private fun warnedOfATupleWithOptions(option: String) =
        warned(
            "{System.get_env(\"X\")}",
            "[{:file, \"a.exs\"}, $option]",
            rescues = "rescue Protocol.UndefinedError -> :u; FunctionClauseError -> :fc; CaseClauseError -> :cc end",
        )

    /** The answers for `k`, a `try` of `IO.warn` of [message] at [at] that [rescues] end, and `k1`, which is `1`. */
    private fun warned(message: String, at: String, rescues: String = RESCUES) =
        keyBeside("(try do IO.warn($message, $at) $rescues)")

    /** `k: 0`, then a `case` of [subject] whose [pattern] arm, which a known part fails, writes `k: 1`: `k` stays `0`. */
    private fun assertKnownPartFails(subject: String, pattern: String) =
        assertEquals(
            "k=0 k1=1",
            keys(
                "config :app, k: 0\n\ncase $subject do\n  $pattern -> config :app, k: 1\n  _ -> :ok\nend\n\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** Each arm builds a value of [depth] levels, each holding the level below twice, and they differ only at the leaf. */
    private fun assertSharedPartsJoin(depth: Int) {
        fun arm(leaf: Int) =
            "  v0 = [a: $leaf]\n" + (1..depth).joinToString("") { "  v$it = [p: v${it - 1}, q: v${it - 1}]\n" } + "  config :app, k: v$depth\n"

        assertEquals(
            "k=Configured k1=1",
            keys("if System.get_env(\"X\") do\n${arm(1)}else\n${arm(2)}end\nconfig :app, k1: 1\n", "k", "k1"),
        )
    }

    private companion object {
        /** An `:inspect_fun` that configures `k` 2. */
        const val INSPECT_FUN = "fn _, _ -> Config.config(:app, k: 2)\n  \"x\" end"

        const val FIELDLESS = "defmodule S do\n  defstruct []\nend\n\n"
        const val FETCH = "defmodule S do\n  defstruct []\n\n  def fetch(_, _) do\n    Config.config(:app, k: 2)\n    {:ok, 0}\n  end\nend\n\n"
        const val TUPLE_CHARS = "defimpl String.Chars, for: Tuple do\n  def to_string(_) do\n$WRITES"
        const val TUPLE_ENUMERABLE =
            "defimpl Enumerable, for: Tuple do\n  def count(_), do: {:error, __MODULE__}\n  def slice(_), do: {:error, __MODULE__}\n" +
                "  def reduce(_, {_, acc}, _) do\n    Config.config(:app, k: 2)\n    {:done, acc}\n  end\n\n  def member?(_, _) do\n    Config.config(:app, k: 2)\n    {:ok, true}\n  end\nend\n\n"
        const val INSPECT = "defimpl Inspect, for: S do\n  def inspect(_, _) do\n$WRITES"
        const val RESCUES = "rescue Protocol.UndefinedError -> :u; FunctionClauseError -> :fc end"
        const val ESCAPE =
            "defmodule S do\n  defstruct [:a]\n\n  def __escape__(s) do\n    Config.config(:app, k: 2)\n" +
                "    Macro.escape(Map.from_struct(s))\n  end\nend\n\n"
    }
}
