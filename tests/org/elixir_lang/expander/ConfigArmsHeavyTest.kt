package org.elixir_lang.expander

/**
 * A value from the environment, unknown to the plugin, passing through code that tests it. Each arm that may be taken runs
 * from the state before the test, and the arms join: a key the arms set alike keeps its value, one they set apart is
 * unknown, and a key nothing unknown reaches stays known. Each expected answer is checked against Elixir's reads of the
 * same file with `X` unset, empty, `123`, `abc`, `true` and `ok`, on 1.20.4 and 1.11.4: a value is known only where every
 * run that doesn't raise gives it.
 */
class ConfigArmsHeavyTest : ConfigHeavyTestCase() {
    fun testAnOrDefault() =
        assertEquals("k=Configured k1=1", keyBeside("System.get_env(\"X\") || \"4000\""))

    fun testAnIfsValue() =
        assertEquals("k=Configured k1=1", keyBeside("if(System.get_env(\"X\"), do: \"a\", else: \"b\")"))

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
            keys(
                "k =\n  try do\n    case System.get_env(\"X\") do\n      nil -> (v = 1; throw(v))\n      _ -> (v = 1; throw(v))\n    end\n" +
                    "  catch\n    v -> v\n  end\n\nconfig :app, k: k, k1: 1\n",
                "k",
                "k1",
            ),
        )

    fun testAConcatenation() =
        assertEquals("k=Configured k1=1", keyBeside("\"a\" <> System.get_env(\"X\", \"\")"))

    fun testABinarySegment() =
        assertEquals("k=Configured k1=1", keyBeside("<<System.get_env(\"X\", \"\")::binary>>"))

    fun testANot() = assertEquals("k=Configured k1=1", keyBeside("!System.get_env(\"X\")"))

    fun testAnAndAnd() = assertEquals("k=Configured k1=1", keyBeside("System.get_env(\"X\") && 1"))

    fun testAnUnless() =
        assertEquals("k=Configured k1=1", keyBeside("unless(System.get_env(\"X\"), do: 1)"))

    fun testAFieldOfAnUnknownDeclines() =
        assertEquals("declined", keys("v = System.get_env(\"X\")\nconfig :app, k: v.y, k1: 1\n", "k", "k1"))

    fun testAMapGet() =
        assertEquals("k=Configured k1=1", keyBeside("Map.get(%{\"1\" => 2}, System.get_env(\"X\"))"))

    fun testAKeywordGet() =
        assertEquals("k=Configured k1=1", keyBeside("Keyword.get([a: System.get_env(\"X\")], :a)"))

    fun testAnAnonymousFunctionCalled() =
        assertEquals(
            "k=Configured k1=1",
            keys("f = fn nil -> 1; v -> v end\nconfig :app, k: f.(System.get_env(\"X\")), k1: 1\n", "k", "k1"),
        )

    fun testACase() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "k =\n  case System.get_env(\"X\") do\n    nil -> 1\n    \"\" -> 2\n    _ -> 3\n  end\n\nconfig :app, k: k, k1: 1\n",
                "k",
                "k1",
            ),
        )

    fun testACaseWhoseArmsGiveOneValue() =
        assertEquals(
            "k=1 k1=1",
            keys("k =\n  case System.get_env(\"X\") do\n    nil -> 1\n    _ -> 1\n  end\n\nconfig :app, k: k, k1: 1\n", "k", "k1"),
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
            keys("k =\n  cond do\n    System.get_env(\"X\") -> 1\n    true -> 2\n  end\n\nconfig :app, k: k, k1: 1\n", "k", "k1"),
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

    fun testArmsSettingValuesThatShareTheirPartsTwelveDeep() = assertSharedPartsJoin(12)

    fun testArmsSettingValuesThatShareTheirPartsSixteenDeep() = assertSharedPartsJoin(16)

    fun testArmsSettingValuesThatShareTheirPartsTwentyDeep() = assertSharedPartsJoin(20)

    /** `0.0` and `-0.0` are two terms: Elixir writes one or the other. */
    fun testArmsGivingZerosOfTwoSigns() =
        assertEquals("k=Configured k1=1", keyBeside("(if System.get_env(\"X\"), do: 0.0, else: -0.0)"))

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
