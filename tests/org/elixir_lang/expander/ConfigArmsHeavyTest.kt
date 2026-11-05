package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.BITSTRING_SIZE_EXPANDED_AS_GUARD

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
        assertEquals(
            "declined",
            keys(
                "config :app, step: 0\n\nfor <<_ <- System.get_env(\"X\", \"ab\")>> do\n" +
                    "  if Config.read_config(:app)[:step] == 0, do: config(:app, step: 1), else: config(:app, enabled: true)\nend\n\n" +
                    "config :app, k1: 1\n",
                "enabled",
                "k1",
            ),
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

    /**
     * An atom made in an arm that may not be taken may not exist past it, so a lookup of it may raise: under a `try`,
     * the read declines.
     */
    fun testAnAtomMadeInAnArmThatMayNotBeTakenMayNotExistPastIt() =
        assertEquals(
            "declined",
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
}
