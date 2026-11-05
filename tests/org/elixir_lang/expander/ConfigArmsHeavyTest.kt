package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.IO_WARN_OPTIONS_TAKE_A_COLUMN
import org.elixir_lang.language_level.ElixirLanguageFeature.IO_WARN_READS_THE_MESSAGE_FIRST
import org.elixir_lang.language_level.ElixirLanguageFeature.IO_WARN_TAKES_AN_ENV_OR_OPTIONS

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

    fun testAWith() =
        assertEquals(
            "k=Configured k1=1",
            keys("k = with {:ok, v} <- System.fetch_env(\"X\"), do: v\nconfig :app, k: k, k1: 1\n", "k", "k1"),
        )

    fun testAWithElse() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "k =\n  with {:ok, v} <- System.fetch_env(\"X\") do\n    v\n  else\n    :error -> \"e\"\n  end\n\nconfig :app, k: k, k1: 1\n",
                "k",
                "k1",
            ),
        )

    fun testAWithWritingInItsDo() =
        assertEquals(
            "k=Configured k1=1",
            keys("config :app, k: 1\nwith {:ok, _} <- System.fetch_env(\"X\"), do: config(:app, k: 2)\nconfig :app, k1: 1\n", "k", "k1"),
        )

    fun testAMatch() =
        assertEquals("k=Configured k1=1", keys("{:ok, v} = System.fetch_env(\"X\")\nconfig :app, k: v, k1: 1\n", "k", "k1"))

    fun testAFor() =
        assertEquals(
            "k=Configured k1=1",
            keyBeside("(for {\"X\", v} <- System.get_env(), do: v)"),
        )

    fun testABitstringFor() =
        assertEquals(
            "k=Configured k1=1",
            keyBeside("(for <<c <- System.get_env(\"X\", \"\")>>, do: c)"),
        )

    fun testAForWritingInItsBody() =
        assertEquals(
            "k=Configured k1=1",
            keys("config :app, k: 1\nfor {\"X\", _} <- System.get_env(), do: config(:app, k: 2)\nconfig :app, k1: 1\n", "k", "k1"),
        )

    fun testABinarySegmentThatMayNotBeABinary() =
        assertEquals("k=Configured k1=1", keyBeside("<<System.get_env(\"X\")::binary>>"))

    /** A bitstring segment of a value not known may be a bitstring that isn't a binary. */
    fun testABitstringSegmentThatMayNotBeABinary() =
        assertEquals(
            "k=Configured k1=1",
            keyBeside("(case <<(if System.get_env(\"X\"), do: <<1::3>>, else: \"a\")::bitstring>> do\n  b when is_binary(b) -> :binary\n  _ -> :bits\nend)"),
        )

    fun testArmsSettingValuesThatShareTheirPartsTwelveDeep() = assertSharedPartsJoin(12)

    fun testArmsSettingValuesThatShareTheirPartsSixteenDeep() = assertSharedPartsJoin(16)

    fun testArmsSettingValuesThatShareTheirPartsTwentyDeep() = assertSharedPartsJoin(20)

    fun testAnIntegerOfAnOrDefault() =
        assertEquals(
            "k=Configured k1=1",
            keyBeside("String.to_integer(System.get_env(\"X\") || \"4000\")"),
        )

    fun testAnExistingAtomThroughAPipe() =
        assertEquals(
            "k=Configured k1=1",
            keyBeside("System.get_env(\"X\", \"ok\") |> String.to_existing_atom()"),
        )

    fun testAnExistingAtom() =
        assertEquals("k=Configured k1=1", keyBeside("String.to_existing_atom(System.get_env(\"X\", \"ok\"))"))

    fun testAnAtom() =
        assertEquals("k=Configured k1=1", keyBeside("String.to_atom(System.get_env(\"X\", \"ok\"))"))

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
        assertEquals("k=Configured k1=1", keyBeside("System.get_env(\"X\", \"1\") |> String.to_integer()"))

    fun testAnInterpolation() =
        assertEquals("k=Configured k1=1", keyBeside("\"#{System.get_env(\"X\")}/y\""))

    fun testAString() =
        assertEquals("k=Configured k1=1", keyBeside("to_string(System.get_env(\"X\"))"))

    fun testAComparison() =
        assertEquals("k=Configured k1=1", keyBeside("System.get_env(\"X\") == \"true\""))

    fun testAnIn() = assertEquals("k=Configured k1=1", keyBeside("System.get_env(\"X\") in [\"a\"]"))

    fun testAStrictAnd() =
        assertEquals("k=Configured k1=1", keyBeside("(System.get_env(\"X\") == \"1\") and true"))

    fun testAPutUnderAnUnknownKey() =
        assertEquals("k=Configured k1=1", keyBeside("map_size(:maps.put(System.get_env(\"X\"), 1, %{nil => 0}))"))

    fun testAWarningOfAnUnknown() = assertEquals("k1=1", keys("IO.warn(System.get_env(\"X\", \"w\"))\nconfig :app, k1: 1\n", "k1"))

    /** `Access.get/2` runs a struct's `fetch/2`, which may write, so given a value not known it declines. */
    fun testAGetFromAStructOverAnUnknownDeclines() =
        assertEquals(
            "declined",
            keys(
                "defmodule S do\n  defstruct []\n\n  def fetch(_, k) do\n    Config.config(:app, k: 2)\n" +
                    "    Config.config(:app, [{String.to_atom(k), 1}])\n    {:ok, 0}\n  end\nend\n\n" +
                    "config :app, k: 0\nconfig :app, j: Access.get(struct(S), System.get_env(\"X\", \"j\")), k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** A value not known that may be a struct of a module the file defines may run its `fetch/2`. */
    fun testAGetFromAValueThatMayBeAStructDeclines() =
        assertEquals(
            "declined",
            keys(
                "defmodule S do\n  defstruct []\n\n  def fetch(_, _) do\n    Config.config(:app, k: 2)\n    {:ok, 0}\n  end\nend\n\n" +
                    "config :app, k: 0\nconfig :app, j: Access.get(max(System.get_env(\"X\"), struct(S)), :j), k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** A struct whose module isn't known may be one the file defines, whose `fetch/2` it runs. */
    fun testAGetFromAStructOfAModuleNotKnownDeclines() =
        assertEquals(
            "declined",
            keys(
                "defmodule S do\n  defstruct []\n\n  def fetch(_, _) do\n    Config.config(:app, k: 2)\n    {:ok, 0}\n  end\nend\n\n" +
                    "config :app, k: 0\n" +
                    "config :app, j: Access.get(%{__struct__: String.to_atom(System.get_env(\"X\", \"Elixir.S\"))}, :j), k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** `to_string/1` dispatches on a struct, so of a value that may be a struct of a module the file defines it declines. */
    fun testAStringOfAValueThatMayBeAStructDeclines() =
        assertEquals(
            "declined",
            keys(
                "defmodule S do\n  defstruct []\nend\n\ndefmodule String.Chars.S do\n  def to_string(_) do\n" +
                    "    Config.config(:app, k: 2)\n    \"s\"\n  end\nend\n\nconfig :app, k: 0\n" +
                    "config :app, j: to_string(max(System.get_env(\"X\"), struct(S))), k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** `Enum.member?/2` dispatches on a struct too. */
    fun testAMemberOfAValueThatMayBeAStructDeclines() =
        assertEquals(
            "declined",
            keys(
                "defmodule S do\n  defstruct []\nend\n\ndefmodule Enumerable.S do\n  def member?(_, _) do\n" +
                    "    Config.config(:app, k: 2)\n    {:ok, true}\n  end\n\n  def count(_), do: {:error, __MODULE__}\n" +
                    "  def slice(_), do: {:error, __MODULE__}\n  def reduce(_, acc, _), do: acc\nend\n\nconfig :app, k: 0\n" +
                    "config :app, j: Enum.member?(max(System.get_env(\"X\"), struct(S)), 1), k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** A generator calls its enumerable's `Enumerable` implementation too. */
    fun testAForOverAValueThatMayBeAStructDeclines() =
        assertEquals(
            "declined",
            keys(
                "defmodule S do\n  defstruct []\nend\n\ndefmodule Enumerable.S do\n  def reduce(_, {:cont, acc}, _) do\n" +
                    "    Config.config(:app, k: 2)\n    {:done, acc}\n  end\nend\n\nconfig :app, k: 0\n" +
                    "config :app, j: (for _ <- max(System.get_env(\"X\"), struct(S)), do: :ok), k1: 1\n",
                "k",
                "k1",
            ),
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

    /** From 1.19 `Macro.escape/1` runs a struct's `__escape__/1`, though a field isn't known. */
    fun testAnEscapeOfAStructHoldingAnUnknownDeclines() =
        assertStructDeclines(ESCAPE, "config :app, j: Macro.escape(struct(S, a: System.get_env(\"X\"))), k1: 1\n", struct = "")

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
        assertEquals("k=Configured k1=1", keyBeside("Enum.member?(1..2, String.to_integer(System.get_env(\"X\", \"1\")))"))

    /** `String.Chars` has no implementation for a tuple, nor a fallback, so a string of one raises whatever it holds. */
    fun testAStringOfATupleRaises() = assertEquals("raised", keyBeside("to_string({System.get_env(\"X\")})"))

    /** Nor for a map that isn't a struct. */
    fun testAStringOfAMapRaises() = assertEquals("raised", keyBeside("to_string(%{a: System.get_env(\"X\")})"))

    /** Nor for a function. */
    fun testAStringOfAFunctionRaises() = assertEquals("raised", keyBeside("to_string(fn -> System.get_env(\"X\") end)"))

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

    /** `0.0` and `-0.0` are two terms: Elixir writes one or the other. */
    fun testArmsGivingZerosOfTwoSigns() =
        assertEquals("k=Configured k1=1", keyBeside("(if System.get_env(\"X\"), do: 0.0, else: -0.0)"))

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

    /** [struct], then [implementation], which writes `k`, then `k`'s first value, then [statements], which run it. */
    private fun assertStructDeclines(implementation: String, statements: String, struct: String = STRUCT) =
        assertEquals("declined", keys(struct + implementation + "config :app, k: 0\n" + statements, "k", "k1"))

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
        const val STRUCT = "defmodule S do\n  defstruct [:a]\nend\n\n"
        const val WRITES = "    Config.config(:app, k: 2)\n    \"s\"\n  end\nend\n\n"
        const val CHARS = "defimpl String.Chars, for: S do\n  def to_string(_) do\n$WRITES"
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
