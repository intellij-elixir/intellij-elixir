package org.elixir_lang.expander

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
        assertConfiguredBeside(
            "(try do System.fetch_env!(\"X\") rescue TryClauseError -> 1; _ -> 0 else :never -> 2 end)",
        )

    fun testARescueThatMayRaise() = assertRescueThatMayRaise("System.fetch_env!(\"X\")")

    fun testAnElseThatMayRaise() = assertElseThatMayRaise("System.fetch_env!(\"X\")")

    fun testAnImportOfAFileThatMayRaise() = assertImportOfAFileThatMayRaise("System.fetch_env!(\"X\")")

    /** The file raises at the read or past it, and which raise the rescue sees isn't known, so the read declines. */
    fun testARaiseInAnImportedFilePastOneThatMayComeFirstDeclines() =
        assertEquals(
            "declined",
            keys(
                IMPORT_IN_A_TRY,
                "k",
                "j",
                "k1",
                extra = arrayOf("config/o.exs" to "import Config\nSystem.fetch_env!(\"X\")\nString.to_integer(\"x\")\nconfig :app, j: 2\n"),
            ),
        )

    fun testABinarySegmentThatMayRaise() = assertRescued("_ = <<System.get_env(\"X\")::binary>>; :ok", rescued = ":err")

    fun testAStrictAndThatMayRaise() = assertRescued("_ = (System.get_env(\"X\") || false) and true; :ok", rescued = ":err")

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

    /** A raise between two configs of `k` leaves it as the first set it, though the body ends where it began. */
    fun testAConfigBetweenARaiseAndAConfigBackToTheStart() =
        assertEquals("k=Configured k1=1", afterBefore("  config :app, k: 1\n  System.fetch_env!(\"X\")"))

    /** What a `throw`'s argument configured stays configured where the throw is caught. */
    fun testAConfigInAThrowsArgumentThatMayBeCaught() = assertConfigInARaisesArgument("throw((config(:app, k: 2); :done))", "catch")

    /** What a `raise`'s argument configured stays configured where the raise is rescued. */
    fun testAConfigInARaisesArgumentThatMayBeRescued() = assertConfigInARaisesArgument("raise((config(:app, k: 2); \"x\"))", "rescue")

    /**
     * A file imported in a `try` whose arm configures in a `throw`'s argument: the arm is a unit the file's walk runs, and
     * a raise there ends the file's run, so the read declines.
     */
    fun testAConfigInAThrowsArgumentInAFileImportedInATryDeclines() =
        assertEquals(
            "declined",
            keys(
                "config :app, k: 1\n\ntry do\n  import_config \"o.exs\"\ncatch\n  _ -> :ok\nend\n\nconfig :app, k1: 1\n",
                "k",
                "k1",
                extra = arrayOf("config/o.exs" to "import Config\n\nif System.get_env(\"X\"), do: throw((config(:app, k: 2); :done))\n"),
            ),
        )

    /** A file imported in a `try` that raises between two configs of `k` leaves it as the first set it. */
    fun testAConfigBetweenARaiseAndAConfigBackToTheStartInAFileImportedInATry() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "config :app, k: 0\n\ntry do\n  import_config \"o.exs\"\nrescue\n  _ -> :ok\nend\n\nconfig :app, k1: 1\n",
                "k",
                "k1",
                extra = arrayOf("config/o.exs" to "import Config\n\nconfig :app, k: 1\nSystem.fetch_env!(\"X\")\nconfig :app, k: 0\n"),
            ),
        )

    /** A file imported in a `try` that may raise before it configures `k`, with `k` set back after it: `k` is as set. */
    fun testAConfigSetBackAfterAFileImportedInATryThatMayRaiseFirst() =
        assertEquals(
            "k=0 k1=1",
            afterBefore(
                "  import_config \"o.exs\"",
                extra = arrayOf("config/o.exs" to "import Config\n\nSystem.fetch_env!(\"X\")\nconfig :app, k: 1\n"),
            ),
        )

    /** Every arm raises, but which raise depends on the arm taken. */
    fun testArmsThrowingValuesApart() =
        assertEquals(
            "k=Configured k1=1",
            keyBound(
                "k =\n  try do\n    case System.get_env(\"X\") do\n      nil -> (v = 1; throw(v))\n      _ -> (v = 2; throw(v))\n    end\n" +
                    "  catch\n    v -> v\n  end\n\n",
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
            keyBound("k =\n  try do\n    _ = System.system_time(:second)\n    :ok\n  rescue\n    _ -> :err\n  end\n\n"),
        )

    fun testATimeInAUnitItRefuses() =
        assertEquals(
            "k=:err k1=1",
            keyBound("k =\n  try do\n    _ = :erlang.system_time(:fortnight)\n    :ok\n  rescue\n    _ -> :err\n  end\n\n"),
        )

    /** A binary generator over a binary not known takes whole bytes, so it can't raise. */
    fun testABinaryGeneratorOverABinaryNotKnown() =
        assertEquals(
            "k=:done k1=1",
            keyBound("k =\n  try do\n    for <<c <- System.get_env(\"X\", \"\")>>, do: c\n    :done\n  rescue\n    _ -> :err\n  end\n\n"),
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
            keyBound("k = try do\n  System.fetch_env!(\"X\")\n  raise \"x\"\nrescue\n  System.EnvError -> 1\n  RuntimeError -> 2\nend\n"),
        )

    fun testARaisePastARaiseInAnInnerTryRescuedByTypeDeclines() =
        assertEquals(
            "declined",
            keyBound(
                "k = try do\n  try do\n    System.fetch_env!(\"X\")\n    raise \"x\"\n  after\n    :ok\n  end\n" +
                    "rescue\n  System.EnvError -> 1\n  RuntimeError -> 2\nend\n",
            ),
        )

    /** A `for` over a binary not known, into a map, of a result that is no pair, raises where it runs at all. */
    fun testAConfigPastAForIntoAMapOfAResultThatIsNoPair() =
        assertConfigPastARaise("for <<_ <- System.get_env(\"X\", \"a\")>>, into: %{}, do: :bad")

    /** A `for` over a list not known, into a map, of results that may be no pair. */
    fun testAConfigPastAForOverAListIntoAMapOfAResultThatMayBeNoPair() =
        assertConfigPastARaise("for {_, v} <- System.get_env(), into: %{}, do: v")

    /** A `for` into a map of a result not known, which may be no pair. */
    fun testAConfigPastAForIntoAMapOfAResultNotKnown() =
        assertConfigPastARaise("for <<_ <- System.get_env(\"X\", \"a\")>>, into: %{}, do: System.get_env(\"Y\")")

    /** A `for` over a binary not known, into a binary, of a result that is no binary, raises where it runs at all. */
    fun testAConfigPastAForIntoABinaryOfAResultThatIsNoBinary() =
        assertConfigPastARaise("for <<_ <- System.get_env(\"X\", \"a\")>>, into: \"\", do: :bad")

    /** A `for` over a binary not known, into a map, of pairs only, raises nothing. */
    fun testAConfigPastAForIntoAMapOfPairs() =
        assertEquals("j=2 k1=1", configPast("for <<_ <- System.get_env(\"X\", \"a\")>>, into: %{}, do: {:a, 1}"))

    /** A `for` over a binary not known, into a map, of pairs whose key isn't known, raises nothing. */
    fun testAConfigPastAForIntoAMapOfPairsWhoseKeyIsNotKnown() =
        assertEquals("j=2 k1=1", configPast("_ = for <<c <- System.get_env(\"X\", \"a\")>>, into: %{}, do: {c, true}"))

    /** A `for` over a binary not known, into a map held in a variable, of pairs whose key isn't known, raises nothing. */
    fun testAConfigPastAForIntoAMapHeldInAVariableOfPairsWhoseKeyIsNotKnown() =
        assertEquals("j=2 k1=1", configPast("m = %{}\n  _ = for <<c <- System.get_env(\"X\", \"a\")>>, into: m, do: {c, true}"))

    /** A `for` over a binary not known, into a list, takes any result. */
    fun testAConfigPastAForIntoAListOfAResultThatIsNoPair() =
        assertEquals("j=2 k1=1", configPast("for <<_ <- System.get_env(\"X\", \"a\")>>, into: [], do: :bad"))

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

    /** A rescue of a raise that may come reads that raise's stacktrace. */
    fun testARescueReadsTheStacktraceOfARaiseThatMayCome() =
        assertEquals("k=Configured k1=1", keyBound("k =\n  try do\n    System.fetch_env!(\"X\")\n  rescue\n    _ -> __STACKTRACE__\n  end\n\n"))

    /** Inside a rescue, a rescue of a raise that may come reads that raise's stacktrace, not the outer one's. */
    fun testARescueInARescueReadsTheStacktraceOfARaiseThatMayCome() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "try do\n  raise \"outer\"\nrescue\n  _ ->\n    config :app, k: __STACKTRACE__\n\n" +
                    "    try do\n      System.fetch_env!(\"X\")\n    rescue\n      _ -> config :app, k: __STACKTRACE__\n    end\nend\n\n" +
                    "config :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** A `catch` clause whose guard can't hold is never taken, though a throw may come. */
    fun testACatchClauseWhoseGuardCantHold() =
        assertEquals(
            "enabled=false k1=1",
            keys(
                "config :app, enabled: false\nlimit = 0\n\ntry do\n  if System.get_env(\"X\"), do: throw(:t)\n  :ok\ncatch\n" +
                    "  :throw, _ when limit > 0 -> config(:app, enabled: true)\n  :throw, _ -> :ok\nend\n\nconfig :app, k1: 1\n",
                "enabled",
                "k1",
            ),
        )

    /** A `catch` of one variable twice catches only a raise whose kind is its value, so an error passes to the outer `try`. */
    fun testACatchOfOneVariableTwiceLetsAnErrorPass() = assertEquals("k=Configured k1=1", caughtBeside("x, x"))

    /** A `catch` of one underscored variable twice binds it, so it catches as `x, x` does. */
    fun testACatchOfOneUnderscoredVariableTwiceLetsAnErrorPass() = assertEquals("k=Configured k1=1", caughtBeside("_x, _x"))

    /** A `catch` of two variables catches every raise, so the write after the inner `try` runs. */
    fun testACatchOfTwoVariablesCatchesAnError() = assertEquals("k=1 k1=1", caughtBeside("kind, value"))

    /** An `after` runs between a raise that may come and the `try` that rescues it, so the rescue sees what it writes. */
    fun testAnAfterBetweenARaiseThatMayComeAndItsRescueWritesBeforeIt() =
        assertEquals(
            "k=Configured k1=1",
            afterBefore("  try do\n    System.fetch_env!(\"X\")\n  after\n    config :app, k: 1\n  end\n"),
        )

    /** An `after` whose `try` may let a raise pass runs before the raise passes, so the rescue sees what it writes. */
    fun testAnAfterOfATryThatMayLetARaisePassRunsBeforeItPasses() =
        assertEquals(
            "k=Configured k1=1",
            afterBefore("  try do\n    System.fetch_env!(\"X\")\n  rescue\n    KeyError -> :ok\n  after\n    config :app, k: 1\n  end\n"),
        )

    /** A raise that may come in `else` passes the `after`, so the rescue around sees what it writes. */
    fun testAnAfterPastARaiseThatMayComeInElseWritesBeforeItsRescue() =
        assertEquals(
            "k=Configured k1=1",
            afterBefore("  try do\n    :ok\n  else\n    _ -> System.fetch_env!(\"X\")\n  after\n    config :app, k: 1\n  end\n"),
        )

    /** A raise that may come in a rescue of a raise passes the `after`, so the rescue around sees what it writes. */
    fun testAnAfterPastARaiseThatMayComeInARescueWritesBeforeTheRescueAround() =
        assertEquals(
            "k=Configured k1=1",
            afterBefore("  try do\n    raise \"x\"\n  rescue\n    _ -> System.fetch_env!(\"X\")\n  after\n    config :app, k: 1\n  end\n"),
        )

    /**
     * A raise that may come in a `catch` of a raise that may come passes the `after`, though the `catch` takes every
     * raise of the body, so the rescue around sees what it writes.
     */
    fun testAnAfterPastARaiseThatMayComeInACatchOfEveryRaiseWritesBeforeTheRescueAround() =
        assertEquals(
            "k=Configured k1=1",
            afterBefore("  try do\n    System.fetch_env!(\"Y\")\n  catch\n    _, _ -> System.fetch_env!(\"X\")\n  after\n    config :app, k: 1\n  end\n"),
        )

    /** An `after` past a raise its own `try` may rescue doesn't decline. */
    fun testAnAfterPastARaiseItsOwnTryRescuesDoesNotDecline() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "config :app, k: 0\n\ntry do\n  try do\n    System.fetch_env!(\"X\")\n    config :app, k: 2\n  rescue\n    _ -> :ok\n" +
                    "  after\n    config :app, j: 1\n  end\nrescue\n  _ -> :ok\nend\n\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** A raise that may come before a write passes the `after` with the read as it was where it came. */
    fun testAnAfterPastARaiseLeavesTheReadAsItWasWhereTheRaiseCame() =
        assertEquals("k=0 k1=1", afterBefore("  try do\n    System.fetch_env!(\"X\")\n    config :app, k: 5\n  after\n    :ok\n  end\n"))

    /** A raise no clause may catch passes with the read as it was where it came. */
    fun testARaiseNoClauseMayCatchLeavesTheReadAsItWasWhereItCame() =
        assertEquals(
            "k=0 k1=1",
            afterBefore("  try do\n    System.fetch_env!(\"X\")\n    config :app, k: 5\n  rescue\n    ArgumentError -> :ok\n  end\n"),
        )

    /** A raise no clause may catch passes the `after` with the read as it was where it came. */
    fun testARaiseNoClauseMayCatchPastAnAfterLeavesTheReadAsItWasWhereItCame() =
        assertEquals(
            "k=0 k1=1",
            afterBefore(
                "  try do\n    System.fetch_env!(\"X\")\n    config :app, k: 5\n  rescue\n    ArgumentError -> :ok\n  after\n    :ok\n  end\n",
            ),
        )

    /** An `after` that a raise may pass reads the config as it was where the raise came. */
    fun testAnAfterPastARaiseReadsTheConfigAsItWasWhereTheRaiseCame() =
        assertEquals(
            "j=0 k1=1",
            keys(
                "config :app, k: 0, j: 0\n\ntry do\n  try do\n    System.fetch_env!(\"X\")\n    config :app, k: 5\n  after\n" +
                    "    config :app, j: config(:app, x: 0)[:app][:k]\n  end\n\n  config :app, j: 0\nrescue\n  _ -> :ok\nend\n\n" +
                    "config :app, k1: 1\n",
                "j",
                "k1",
            ),
        )

    /** What an `after` writes from the config differs between the raise's path and the body's. */
    fun testAnAfterPastARaiseWritesFromTheConfigOfEachPath() =
        assertEquals(
            "j=Configured k1=1",
            keys(
                "config :app, k: 0\n\ntry do\n  try do\n    System.fetch_env!(\"X\")\n    config :app, k: 5\n  after\n" +
                    "    config :app, j: config(:app, x: 0)[:app][:k]\n  end\nrescue\n  _ -> :ok\nend\n\nconfig :app, k1: 1\n",
                "j",
                "k1",
            ),
        )

    /**
     * A file the body imported before a raise that may pass the `after` is one the body read, so a second import of it
     * raises. Elixir gives `k: 0` where a raise comes, else `7`, as the second import raises then.
     */
    fun testAFileImportedBeforeARaiseThatMayPassAnAfterIsImportedAgainAsRead() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "config :app, k: 0\n\ntry do\n  try do\n    System.fetch_env!(\"X\")\n    import_config \"o.exs\"\n    System.fetch_env!(\"Y\")\n" +
                    "  after\n    :ok\n  end\n\n  try do\n    import_config \"o.exs\"\n  rescue\n    _ -> config :app, k: 7\n  end\n" +
                    "rescue\n  _ -> :ok\nend\n\nconfig :app, k1: 1\n",
                "k",
                "k1",
                extra = arrayOf(EMPTY_O_EXS),
            ),
        )

    /**
     * An `after` that defines a module, which a raise may pass, declines: it runs again from the read the raise passes
     * with, where what it defines may differ. Elixir gives `k=0 k1=1`.
     */
    fun testAnAfterThatDefinesAModulePastARaiseThatMayPassDeclines() =
        assertEquals(
            "declined",
            keys(
                "config :app, k: 0\n\ntry do\n  try do\n    System.fetch_env!(\"X\")\n  rescue\n    ArgumentError -> :ok\n  after\n" +
                    "    defmodule H do\n      def f, do: 1\n    end\n  end\n\n  config :app, k: 0\nrescue\n  _ -> :ok\nend\n\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /**
     * An `after` a raise may pass runs once more for it, so one reached in another's runs twice for each run of that one,
     * and a recursion through them doubles at each call: past `ARM_BUDGET` such runs, the read declines. `H.f(13)` makes
     * 2^13 − 1 of them. Elixir gives `k1=1`.
     */
    fun testARecursionThroughAnAfterThatARaiseMayPassDeclinesPastTheBudget() =
        assertEquals(
            "declined",
            keys(
                "defmodule H do\n  def f(0), do: :ok\n\n  def f(n) do\n    try do\n      System.fetch_env!(\"X\")\n    after\n      f(n - 1)\n" +
                    "    end\n  end\nend\n\ntry do\n  H.f(13)\nrescue\n  _ -> :ok\nend\n\nconfig :app, k1: 1\n",
                "k1",
            ),
        )

    /** A `catch` whose value's bitstring can't match takes no raise, so the raise passes to the enclosing `try`. */
    fun testACatchWhoseBitstringCantMatchLetsTheRaisePass() =
        assertEquals(
            "k=0 k1=1",
            keys(
                "config :app, k: 0\nn = -1\n\ntry do\n  try do\n    System.fetch_env!(\"X\")\n  catch\n    _kind, <<_::size(n)>> -> config(:app, k: 1)\n" +
                    "  end\nrescue\n  _ -> :ok\nend\n\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** An arm that raises passes the read as it was where it raised, not as the arms beside it leave it. */
    fun testAnArmThatRaisesPassesTheReadAsItWasWhereItRaised() =
        assertEquals("k=0 k1=1", afterBefore("  if System.get_env(\"X\"), do: config(:app, k: 5), else: raise(\"x\")\n"))

    /** A `case` arm that raises passes the read as it was where it raised. */
    fun testACaseArmThatRaisesPassesTheReadAsItWasWhereItRaised() =
        assertEquals("k=0 k1=1", afterBefore("  case System.get_env(\"X\") do\n    nil -> raise(\"x\")\n    _ -> config(:app, k: 5)\n  end\n"))

    /** An atom made past a raise a `try` may catch may not exist past the `try`, so a lookup of it may raise. */
    fun testAnAtomMadePastARaiseMayNotExistPastTheTry() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "config :app, k: 0\n\ndefmodule H do\n  def f(name) do\n    try do\n      System.fetch_env!(\"X\")\n" +
                    "      String.to_atom(name)\n    catch\n      _, _ -> :ok\n    end\n\n" +
                    "    String.to_existing_atom(name)\n    Config.config(:app, k: 1)\n  end\nend\n\n" +
                    "try do\n  H.f(\"atom_made_past_a_raise\")\nrescue\n  _ -> :ok\nend\n\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /**
     * An atom made in an arm that may not be taken and looked up in that arm exists there, as Elixir gives `k: 0`, but is
     * taken as one that may not, so the `try` around may take a raise and `k` reads unknown.
     */
    fun testAnAtomMadeAndLookedUpInAnArmThatMayNotBeTakenReadsAsMaybeMissing() =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "config :app, k: 0\n\ntry do\n  if System.get_env(\"X\") do\n    String.to_atom(\"made_and_found_here\")\n" +
                    "    String.to_existing_atom(\"made_and_found_here\")\n  end\nrescue\n  _ -> config :app, k: 2\nend\n\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** `k: 0`, then a `try` that rescues a raise of [inner], then writes `k: 0` past it, with the [extra] files beside the config. */
    private fun afterBefore(inner: String, extra: Array<Pair<String, String>> = emptyArray()) =
        keys("config :app, k: 0\n\ntry do\n$inner\n  config :app, k: 0\nrescue\n  _ -> :ok\nend\n\nconfig :app, k1: 1\n", "k", "k1", extra = extra)

    /** A `catch` of [head] around a raise that may come, inside a `try` that rescues it, beside a write after the inner `try`. */
    private fun caughtBeside(head: String) =
        keys(
            "config :app, k: 0\n\ntry do\n  try do\n    System.fetch_env!(\"X\")\n  catch\n    $head -> :ok\n  end\n\n" +
                "  config :app, k: 1\nrescue\n  _ -> :ok\nend\n\nconfig :app, k1: 1\n",
            "k",
            "k1",
        )

    /** [statements], then a read of what `H`, which they may create, gives. */
    private fun assertCreatedDeclines(statements: String) = assertEquals("declined", keys(statements + "config :app, k: H.v(), k1: 1\n", "k", "k1"))

    /** A `try` of [mayRaise], rescuing it with [rescued], then a key beside it. */
    private fun assertRescued(mayRaise: String, rescued: String = ":d") =
        assertConfiguredBeside("(try do $mayRaise rescue _ -> $rescued end)")

    /** `k: 1`, then a `try` whose body, in an arm that may be taken, [raises] with a config of `k: 2` in its argument, which [handles] takes. */
    private fun assertConfigInARaisesArgument(raises: String, handles: String) =
        assertEquals(
            "k=Configured k1=1",
            keys(
                "config :app, k: 1\n\ntry do\n  if System.get_env(\"X\"), do: $raises\n$handles\n  _ -> :ok\nend\n\nconfig :app, k1: 1\n",
                "k",
                "k1",
            ),
        )

    /** `o.exs`, imported past [mayRaise] in a `try`'s body, may have been read, so a later import of it may raise. */
    private fun assertImportedTwiceDeclines(mayRaise: String) =
        assertImportedAgainDeclines("try do\n  $mayRaise\n  import_config \"o.exs\"\nrescue\n  _ -> :ok\nend\n")

    /** A `try` of [mayRaise], rescuing it with `0`, whose `else` gives `1`, then a key beside it. */
    private fun assertElseAfterARescue(mayRaise: String) =
        assertConfiguredBeside("(try do $mayRaise rescue _ -> 0 else _ -> 1 end)")

    /** [mayRaise] in a `try`'s body, then a config of `j` past it, which may not run, then `k1`. */
    private fun assertConfigPastARaise(mayRaise: String) = assertEquals("j=Configured k1=1", configPast(mayRaise))

    /** The answers for `j` and `k1`, with [statements] in a `try`'s body, then a config of `j` past them. */
    private fun configPast(statements: String) =
        keys("try do\n  $statements\n  config :app, j: 2\nrescue\n  _ -> :ok\nend\nconfig :app, k1: 1\n", "j", "k1")

    /** A rescue that runs [mayRaise] again, in a `try` that rescues it, then a key beside it. */
    private fun assertRescueThatMayRaise(mayRaise: String) =
        assertConfiguredBeside("(try do (try do $mayRaise rescue _ -> $mayRaise end); :ok rescue _ -> :err end)")

    /** An `else` that runs [mayRaise], in a `try` that rescues it, then a key beside it. */
    private fun assertElseThatMayRaise(mayRaise: String) =
        assertConfiguredBeside("(try do (try do :ok rescue _ -> :no else _ -> $mayRaise end); :ok rescue _ -> :err end)")

    /** An import in a `try` of `o.exs`, which runs [mayRaise]. */
    private fun assertImportOfAFileThatMayRaise(mayRaise: String) =
        assertEquals("k=Configured k1=1", keys(IMPORT_IN_A_TRY, "k", "k1", extra = arrayOf("config/o.exs" to "import Config\n$mayRaise\n")))

    private companion object {
        const val CREATE = "Module.create(H, quote(do: def(v, do: 1)), Macro.Env.location(__ENV__))"
    }
}
