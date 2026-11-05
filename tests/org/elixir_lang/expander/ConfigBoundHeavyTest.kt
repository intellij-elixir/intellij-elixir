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

    /**
     * `f/1` given an unknown calls itself with it again once it has configured the step its arms read, so the second
     * call takes the other arm. Before `Config.read_config/1`, its call raises where `X` is set.
     */
    fun testAFunctionCallingItselfWithWhatItWasGivenPastAConfig() =
        assertRead(
            "k=Configured k1=1",
            keys(
                "config :app, step: 0, k: 1\n\ndefmodule H do\n  def f(v) do\n    case Config.read_config(:app)[:step] do\n" +
                    "      0 ->\n        Config.config(:app, step: 1)\n        f(v)\n        :ok\n\n      1 ->\n        Config.config(:app, k: 2)\n" +
                    "    end\n  end\nend\n\nif System.get_env(\"X\"), do: H.f(System.get_env(\"Y\"))\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
            before = "declined",
        )

    /**
     * A call over an unknown that repeats one running may give what that one's body builds: here a struct whose
     * implementation of `String.Chars` configures `k`, which the protocol's call over that value may run, so it declines.
     */
    fun testARepeatOfARunningCallMayGiveAStructItsBodyBuilds() =
        assertEquals(
            "declined",
            keys(
                CONFIGURING_STRUCT +
                    "defmodule H do\n  def f(0), do: %{__struct__: S}\n\n  def f(n) do\n    to_string(f(n - 1))\n    :ok\n  end\nend\n\n" +
                    "n = String.to_integer(System.get_env(\"N\", \"1\"))\n\nif System.get_env(\"X\"), do: H.f(n)\n\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /**
     * A protocol's call over that value declines, as the value may be a struct the body builds, though here it builds
     * none.
     */
    fun testAProtocolCallOverARepeatOfARunningCallDeclines() =
        assertEquals(
            "declined",
            keys(
                "defmodule H do\n  def f(0), do: System.get_env(\"Y\", \"a\")\n  def f(n), do: f(n - 1)\nend\n" +
                    "config :app, k: to_string(H.f(String.to_integer(System.get_env(\"X\", \"1\")))), k1: 1\n",
                "k",
                "k1",
            ),
        )

    /**
     * A pass of a `for` over a value not known that writes an app's env makes the next pass read it as written, so that
     * pass runs too: here its protocol's call over what it reads declines, where Elixir's second iteration configures
     * `k`.
     */
    fun testAPassOfAForThatWritesAnAppsEnvRunsTheNextAsItReadsIt() =
        assertEquals(
            "declined",
            keys(
                CONFIGURING_STRUCT + "for <<_ <- System.get_env(\"X\", \"ab\")>> do\n$READ_THEN_WRITE_ENV  to_string(v)\nend\n\n" +
                    "config :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** And a call over a value not known that writes an app's env repeats no call running before the write. */
    fun testACallPastAWriteOfAnAppsEnvRepeatsNoCallRunningBeforeIt() =
        assertEquals(
            "declined",
            keys(
                CONFIGURING_STRUCT + "defmodule H do\n  def f(n) do\n$READ_THEN_WRITE_ENV  if n > 0, do: f(n - 1)\n  to_string(v)\n" +
                    "  end\nend\n\n$F_WHERE_X",
                "k",
                "k1",
            ),
        )

    /**
     * A call over a value not known that repeats one running gives no write that one's body makes after it, where each
     * call Elixir makes writes for the next to read: here the running call reads an app's env, writes a struct there,
     * and its protocol's call over what it read configures `k` on Elixir's second call, so it declines.
     */
    fun testARepeatOfARunningCallBeforeItWritesAnAppsEnvDeclines() =
        assertEquals(
            "declined",
            keys(
                CONFIGURING_STRUCT + "defmodule H do\n  def f(n) do\n  if n > 0, do: f(n - 1)\n$READ_THEN_WRITE_ENV  to_string(v)\n" +
                    "  end\nend\n\n$F_WHERE_X",
                "k",
                "k1",
            ),
        )

    /** And where the call in the running one's tail writes, after that one has returned. */
    fun testARepeatOfARunningCallBeforeItsTailCallWritesAnAppsEnvDeclines() =
        assertEquals(
            "declined",
            keys(
                CONFIGURING_STRUCT + "defmodule H do\n  def f(n) do\n    if n > 0, do: f(n - 1)\n    g(Application.get_env(:other, :s))\n" +
                    "  end\n\n  def g(v) do\n    Application.put_env(:other, :s, %{__struct__: S})\n    to_string(v)\n  end\nend\n\n" +
                    F_WHERE_X,
                "k",
                "k1",
            ),
        )

    /**
     * And where it configures what the next call reads: `k` is 2 on Elixir's second call, which the call's second pass
     * finds. Before `Config.read_config/1`, its call raises where `X` is set.
     */
    fun testARepeatOfARunningCallBeforeItConfiguresRunsItAgainAsItReadsIt() =
        assertRead(
            "k=Configured k1=1",
            keys(
                "config :app, step: 0, k: 1\n\n$STEPPING_H" +
                    F_WHERE_X,
                "k",
                "k1",
            ),
            before = "declined",
        )

    /**
     * Each pass after the first may not run, as Elixir may make no call inside: where it makes none, `k` is 1 past the
     * call, and `j` 5.
     */
    fun testAPassOfARunningCallAfterARepeatMayNotRun() =
        assertRead(
            "j=Configured k1=1",
            keys(
                "config :app, j: 0, step: 0, k: 1\n\n$STEPPING_H${jPastF(2)}",
                "j",
                "k1",
            ),
            before = "declined",
        )

    /**
     * And where a pass configures `k` past `j`, which Elixir's merge moves to the end, the passes' ends hold `:app`'s keys
     * in another order than the end where no call is made, so `:app` has no shape and its read declines. Elixir gives
     * `j` 0 or 5, so `j=Configured k1=1`.
     */
    fun testAPassOfARunningCallAfterARepeatReorderingAnAppsKeysDeclines() =
        assertEquals(
            "declined",
            keys(
                "config :app, step: 0, k: 1, j: 0\n\n$STEPPING_H${jPastF(2)}",
                "j",
                "k1",
            ),
        )

    /**
     * Each call Elixir makes reads `k` before its call inside from where it was called, before any call has configured:
     * `v` is 0 on each, and `w` 1 past the innermost call only, so `k` is 2 past more than one call, and `j` 5.
     */
    fun testCodeBeforeARepeatOfARunningCallReadsTheConfigTheCallFound() =
        assertRead(
            "j=Configured k1=1",
            keys(
                "config :app, j: 0, k: 0\n\ndefmodule H do\n  def f(n) do\n    v = Config.read_config(:app)[:k]\n" +
                    "    if n > 0, do: f(n - 1)\n    w = Config.read_config(:app)[:k]\n" +
                    "    Config.config(:app, k: if(v == w, do: 1, else: 2))\n  end\nend\n\n${jPastF(1)}",
                "j",
                "k1",
            ),
            before = "declined",
        )

    /** And where each call configures from what it read before its call inside: `k` is 1 past any number of calls. */
    fun testCodeBeforeARepeatOfARunningCallReadsNoneOfWhatTheCallsInsideConfigure() =
        assertRead(
            "j=0 k1=1",
            keys(
                "config :app, j: 0, k: 0\n\ndefmodule H do\n  def f(n) do\n    v = Config.read_config(:app)[:k]\n" +
                    "    if n > 0, do: f(n - 1)\n    Config.config(:app, k: v + 1)\n  end\nend\n\n${jPastF(1)}",
                "j",
                "k1",
            ),
            before = "declined",
        )

    /** And where it reads an app's env that the calls inside write: each call reads it unwritten, `nil`. */
    fun testCodeBeforeARepeatOfARunningCallReadsAnAppsEnvTheCallsInsideWriteUnwritten() =
        assertEquals(
            "k=0 k1=1",
            keys(
                CONFIGURING_STRUCT + "defmodule H do\n  def f(n) do\n    v = Application.get_env(:other, :s)\n" +
                    "    if n > 0, do: f(n - 1)\n    Application.put_env(:other, :s, %{__struct__: S})\n    to_string(v)\n" +
                    "  end\nend\n\n$F_WHERE_X",
                "k",
                "k1",
            ),
        )

    /**
     * A repeat of a running call inside a `try` that call's body opened declines: Elixir's innermost call configures `k`
     * 2 and raises, and the `try` around it rescues that, configuring `j` 2, so `z` is `:two` past one call inside.
     */
    fun testARepeatOfARunningCallInATryItsBodyOpenedDeclines() =
        assertEquals(
            "declined",
            keys(
                "config :other, j: 0\nconfig :app, k: 1\n\ndefmodule H do\n  def f(n) do\n    m = n - 1\n\n    try do\n" +
                    "      if n > 0, do: f(m)\n    rescue\n      _ -> Config.config(:other, j: Config.read_config(:app)[:k])\n    else\n" +
                    "      _ -> Config.config(:other, j: 1)\n    end\n\n    Config.config(:app, k: 1)\n\n" +
                    "    if n == 0 do\n      Config.config(:app, k: 2)\n      raise \"x\"\n    end\n  end\nend\n\n" +
                    "if System.get_env(\"X\") do\n  H.f(String.to_integer(System.get_env(\"N\", \"1\")))\n" +
                    "  config :app, z: if(Config.read_config(:other)[:j] == 2, do: :two, else: :one)\n" +
                    "else\n  config :app, z: :one\nend\n\nconfig :app, k1: 1\n",
                "z",
                "k1",
            ),
        )

    /**
     * A repeat of a running call inside a `try` with `after` that call's body opened declines: Elixir's innermost call
     * configures `k` 2 and raises, the `after` around it configures `j` from `k` on the way out, and the `rescue` around
     * the first call takes that, so `z` is `:two` past one call inside.
     */
    fun testARepeatOfARunningCallInATryWithAfterItsBodyOpenedDeclines() =
        assertEquals(
            "declined",
            keys(
                "config :app, k: 0\nconfig :other, j: 0\n\ndefmodule H do\n  def f(n) do\n    m = n - 1\n\n    try do\n" +
                    "      if n > 0, do: f(m)\n    after\n      Config.config(:other, j: Config.read_config(:app)[:k])\n    end\n\n" +
                    "    Config.config(:app, k: 2)\n    Config.config(:other, j: Config.read_config(:other)[:j])\n" +
                    "    if n == 0, do: raise(\"x\")\n    Config.config(:app, k: 0)\n" +
                    "    Config.config(:other, j: Config.read_config(:other)[:j])\n  end\nend\n\n" +
                    "if System.get_env(\"X\") do\n  try do\n    H.f(String.to_integer(System.get_env(\"N\", \"1\")))\n" +
                    "  rescue\n    _ -> :ok\n  end\n\n" +
                    "  config :app, z: if(Config.read_config(:other)[:j] == 2, do: :two, else: :one)\n" +
                    "else\n  config :app, z: :one\nend\n\nconfig :app, k1: 1\n",
                "z",
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
        assertConfiguredBeside("Stream.iterate(System.schedulers(), &(&1 + 1)) |> Enum.take(2)")

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
        assertConfiguredBeside("System.get_env(\"X\") in ${(1..33).joinToString(", ", "[", "]") { "\"$it\"" }}")

    /** Twenty `case`s on twenty variables in turn, each arm's value [arm] of the last `case`'s. */
    private fun cases(arm: (String) -> String) =
        (0 until 20).joinToString("") { n ->
            val previous = if (n == 0) "0" else "v${n - 1}"
            "v$n =\n  case System.get_env(\"X$n\") do\n    nil -> $previous\n    _ -> ${arm(previous)}\n  end\n\n"
        } + "config :app, k: v19, k1: 1\n"

    private companion object {
        /** `k: 0`, then a struct `S` whose implementation of `String.Chars` configures `k`. */
        const val CONFIGURING_STRUCT = "config :app, k: 0\n\ndefmodule S do\nend\n\n$CHARS"

        /** A read of `:other`'s `:s`, bound to `v`, then a write of an `S` there. */
        const val READ_THEN_WRITE_ENV = "  v = Application.get_env(:other, :s)\n  Application.put_env(:other, :s, %{__struct__: S})\n"

        /** `H.f/1`, which calls itself while `n` is above 0, then configures `:app`'s `k` 1 where `step` is 0, else 2. */
        const val STEPPING_H =
            "defmodule H do\n  def f(n) do\n    if n > 0, do: f(n - 1)\n\n    case Config.read_config(:app)[:step] do\n" +
                "      0 -> Config.config(:app, step: 1, k: 1)\n      _ -> Config.config(:app, step: 1, k: 2)\n    end\n  end\nend\n\n"

        /** Where `X` is set, `H.f/1`'s call; then `k1`. */
        const val F_WHERE_X = "if System.get_env(\"X\"), do: H.f(String.to_integer(System.get_env(\"N\", \"1\")))\n\nconfig :app, k1: 1\n"

        /** Where `X` is set, `H.f/1`'s call, then `j` configured 0 where `k` is then [k], else 5; then `k1`. */
        fun jPastF(k: Int) =
            "if System.get_env(\"X\") do\n  H.f(String.to_integer(System.get_env(\"N\", \"1\")))\n" +
                "  config :app, j: if(Config.read_config(:app)[:k] == $k, do: 0, else: 5)\nend\n\nconfig :app, k1: 1\n"
    }
}
