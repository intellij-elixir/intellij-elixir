package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFileFactory
import com.intellij.util.system.OS
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.expander.ExpansionResult.Ended
import org.elixir_lang.facet.FacetModelFixtures
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.psi.ElixirFile
import java.io.File

/**
 * A module body's `Application` env reads answer from [AppEnv]: a key a config file sets, or may set where a setting of
 * its app can't be read, isn't known, else the term the app's `.app` declares, else the key is unset; where the `.app`
 * can't be read nothing is known. Every answer it knows is
 * an assumption, so an error after it stops the module.
 */
class AppEnvTest : PlatformTestCase() {
    private lateinit var sdk: ModuleClauses.Lookup

    override fun setUp() {
        super.setUp()

        sdk = sdkClauses(testRootDisposable)
    }

    fun testGetEnv() =
        assertAnswers(
            "Application.get_env(:dep, :key)" to ":value",
            "Application.get_env(:dep, :key, :default)" to ":value",
            "Application.get_env(:dep, :charlist)" to "[97, 98, 99]",
            "Application.get_env(:dep, :missing)" to "nil",
            "Application.get_env(:dep, :missing, :default)" to ":default",
            "Application.get_env(:dep, :configured)" to null,
            "Application.get_env(:own, :key)" to null,
            "Application.get_env(:ex_unit, :timeout)" to "60000",
            "Application.get_env(:unread, :key)" to null,
            "Application.get_env(:unread, :missing, :default)" to null,
            "match?({:ok, _}, Application.fetch_env(:unread, :configured))" to null,
        )

    fun testFetchEnv() =
        assertAnswers(
            "Application.fetch_env(:dep, :key)" to "{:ok, :value}",
            "Application.fetch_env(:dep, :missing)" to ":error",
            "match?({:ok, _}, Application.fetch_env(:dep, :configured))" to null,
            "Application.fetch_env(:dep, :configured)" to null,
            "Application.fetch_env(:own, :key)" to null,
        )

    fun testFetchEnvBang() =
        assertAnswers(
            "Application.fetch_env!(:dep, :key)" to ":value",
            "Application.fetch_env!(:dep, :configured)" to null,
            "Application.fetch_env!(:own, :key)" to null,
        )

    fun testCompileEnv() =
        assertAnswers(
            "Application.compile_env(:dep, :key)" to ":value",
            "Application.compile_env(:dep, :missing, :default)" to ":default",
            "Application.compile_env(:dep, [:keyword, :sub])" to "1",
            "Application.compile_env(:dep, [:map, :sub])" to "2",
            "Application.compile_env(:dep, [:keyword, :missing], :default)" to ":default",
            "Application.compile_env(:dep, [:missing, :sub], :default)" to ":default",
            "Application.compile_env(:dep, [:configured, :sub])" to null,
            "Application.compile_env(:own, :key)" to null,
        )

    fun testCompileEnvBang() =
        assertAnswers(
            "Application.compile_env!(:dep, :key)" to ":value",
            "Application.compile_env!(:dep, [:keyword, :sub])" to "1",
            "Application.compile_env!(:own, :key)" to null,
        )

    /** A bang read of a key the env doesn't hold raises `ArgumentError`, as Elixir does, from an answer that is assumed. */
    fun testABangReadOfAKeyTheEnvLacksStops() {
        for (read in listOf(
            "Application.fetch_env!(:dep, :missing)",
            "Application.compile_env!(:dep, :missing)",
            "Application.compile_env!(:dep, [:keyword, :missing])",
        )) {
            assertFile("defmodule A do\n  require Application\n  @x $read\nend", "module Elixir.A stopped `@x $read`")
        }
    }

    /** A path through a value that holds no keys raises in `Access.fetch/2`, from an answer that is assumed. */
    fun testAPathThroughAValueHoldingNoKeysStops() =
        assertFile(
            "defmodule A do\n  require Application\n  @x Application.compile_env(:dep, [:key, :sub])\nend",
            "module Elixir.A stopped `@x Application.compile_env(:dep, [:key, :sub])`",
        )

    /**
     * `traverse_env/2` takes a path's tail as a list, so a tail that isn't one raises where a value was fetched before
     * it, from an answer that is assumed, and reads nothing where none was.
     */
    fun testAPathWhoseTailIsntAListRaisesWhereAValueWasFetched() {
        assertFile(
            "defmodule A do\n  require Application\n  @x Application.compile_env(:dep, [:key | :bad])\nend",
            "module Elixir.A stopped `@x Application.compile_env(:dep, [:key | :bad])`",
        )
        assertFile(
            "defmodule A do\n  require Application\n  @x Application.compile_env(:dep, [:keyword, :sub | :bad])\nend",
            "module Elixir.A stopped `@x Application.compile_env(:dep, [:keyword, :sub | :bad])`",
        )
        assertAnswers(
            "Application.compile_env(:dep, [:missing | :bad], :default)" to ":default",
            "Application.compile_env(:dep, [:keyword, :missing | :bad], :default)" to ":default",
        )
    }

    /** A known answer is assumed, so an error after it stops the module; one that isn't known assumes nothing. */
    fun testAnErrorAfterAKnownAnswerStops() {
        val error = "  :ok = :bad\nend"

        assertFile("defmodule A do\n  _ = Application.get_env(:dep, :key)\n$error", "module Elixir.A stopped `:ok = :bad`")
        assertFile("defmodule A do\n  _ = Application.get_env(:dep, :missing)\n$error", "module Elixir.A stopped `:ok = :bad`")
        assertFile(
            "defmodule A do\n  _ = Application.fetch_env(:dep, :configured)\n$error",
            "module Elixir.A raised Elixir.MatchError `:ok = :bad`",
        )
        assertFile(
            "defmodule A do\n  _ = Application.get_env(:own, :key)\n$error",
            "module Elixir.A raised Elixir.MatchError `:ok = :bad`",
        )
    }

    /**
     * A write to an app's env, through `Application`'s writers or `:application`'s, is what the app's later reads give;
     * one whose config can't be read leaves the app's later reads unknown, and every app's where the app isn't known.
     * Another app's write leaves them as they were, as does a write after the read.
     */
    fun testAWriteToAnAppsEnvIsWhatItsLaterReadsGive() {
        val writes = listOf(
            "Application.put_env(:dep, :key, :other)" to "different/0",
            "Application.put_env(:dep, :key, :other, persistent: true)" to "different/0",
            "Application.delete_env(:dep, :key)" to "different/0",
            "Application.put_all_env(dep: [key: :other])" to null,
            ":application.set_env(:dep, :key, :other)" to "different/0",
            ":application.set_env(:dep, :key, :other, :infinity)" to "different/0",
            ":application.set_env([dep: [key: :other]])" to null,
            ":application.unset_env(:dep, :key)" to "different/0",
            ":application.unset_env(:dep, :key, 5000)" to "different/0",
            ":application.set_env(Application.get_env(:dep, :configured), :key, :other)" to null,
            "Application.put_env(:other, :key, :other)" to "equal/0",
        )
        assertEquals(
            writes.joinToString("\n") { (write, expected) -> "$write: ${expected ?: "different/0 equal/0"}" },
            writes.joinToString("\n") { (write, _) ->
                "$write: " + defined("defmodule A do\n  $write\n${defines(READ, ":value")}end\n")
            },
        )
        assertEquals(
            "a write after the read",
            "equal/0",
            defined("defmodule A do\n${defines(READ, ":value")}  Application.put_env(:dep, :key, :other)\nend\n"),
        )
    }

    /**
     * A key the run wrote is read back as an assumption in a run beside others, as another module's compile may write it
     * before the read, so an error after it stops the module. A run alone, a script's, assumes nothing there.
     */
    fun testAWrittenKeyIsAssumedWhereAnotherModulesCompileMayWriteIt() {
        val error = "  :ok = :bad\nend"
        val written = "defmodule A do\n  Application.put_env(:dep, :new, :mine)\n  _ = Application.get_env(:dep, :new)\n$error"
        val deleted = "defmodule A do\n  Application.delete_env(:dep, :key)\n  _ = Application.fetch_env(:dep, :key)\n$error"

        assertFile(written, "module Elixir.A stopped `:ok = :bad`")
        assertFile(deleted, "module Elixir.A stopped `:ok = :bad`")
        assertFile(written, "module Elixir.A raised Elixir.MatchError `:ok = :bad`", "app_env.exs")
        assertFile(deleted, "module Elixir.A raised Elixir.MatchError `:ok = :bad`", "app_env.exs")
    }

    /** Each reader gives what the run wrote, a key deleted reading as unset, though no `.app` declares the key. */
    fun testAWrittenKeyIsReadBackByEachReader() =
        assertWritten(
            "Application.put_env(:dep, :new, :other)" to "Application.get_env(:dep, :new)" to ":other",
            "Application.put_env(:dep, :new, :other)" to "Application.fetch_env(:dep, :new)" to "{:ok, :other}",
            "Application.put_env(:dep, :new, :other)" to "Application.fetch_env!(:dep, :new)" to ":other",
            "Application.put_env(:dep, :new, :other)" to "Application.compile_env(:dep, :new)" to ":other",
            "Application.put_env(:dep, :new, [sub: 3])" to "Application.compile_env(:dep, [:new, :sub])" to "3",
            "Application.put_env(:own, :key, :other)" to "Application.get_env(:own, :key)" to ":other",
            "Application.put_env(:dep, :configured, :other)" to "Application.get_env(:dep, :configured)" to ":other",
            "Application.delete_env(:dep, :key)" to "Application.fetch_env(:dep, :key)" to ":error",
            "Application.delete_env(:dep, :key)" to "Application.get_env(:dep, :key, :default)" to ":default",
            "Application.put_env(:dep, :key, 1)\n  Application.put_env(:dep, :key, 2)" to READ to "2",
            "Application.put_env(:dep, :key, 1)\n  Application.delete_env(:dep, :key)" to READ to "nil",
            "Application.put_env(:other, :b, 1)\n  :application.set_env(:dep, Application.get_env(:dep, :configured), 2)" to
                "Application.get_env(:other, :b)" to "1",
        )

    /**
     * A write the run can't place leaves unknown the keys it may have written: a key not known its app's, an app not
     * known every app's, `put_all_env` its apps' or every app's, and code the walk went on past every written app's.
     */
    fun testAWriteTheRunCantPlaceLeavesTheKeysItMayHaveWrittenUnknown() =
        assertWritten(
            "Application.put_env(:dep, :key, 1)\n  :application.set_env(:dep, Application.get_env(:dep, :configured), 2)" to
                READ to null,
            "Application.put_env(:dep, :key, 1)\n  :application.set_env(Application.get_env(:dep, :configured), :key, 2)" to
                READ to null,
            "Application.put_env(:other, :b, 1)\n  :application.set_env(Application.get_env(:dep, :configured), :key, 2)" to
                "Application.get_env(:other, :b)" to null,
            "Application.put_env(:dep, :key, 1)\n  Application.put_all_env(dep: [other: 2])" to READ to null,
            "Application.put_env(:dep, :key, 1)\n  Application.put_all_env(other: [b: 2])" to READ to null,
            "Application.put_env(:dep, :key, 1)\n  :erlang.send(:erlang.self(), :decline)\n  receive do\n    :decline -> 1\n    after 0 -> 1\n  end" to READ to null,
        )

    /**
     * A write in code a function runs that may not run, an arm over a value not known or past a raise a `rescue` may
     * catch, leaves its key unknown, though every arm writes it alike; there the run reads what it would without the
     * writes, so a loop over a value not known reads no key one of its passes wrote.
     */
    fun testAWriteInCodeThatMayNotRunLeavesItsKeyUnknown() =
        assertWrittenInAFunction(
            "if Application.get_env(:dep, :configured), do: Application.put_env(:dep, :key, 1)" to READ to null,
            "if Application.get_env(:dep, :configured),\n    do: Application.put_env(:dep, :key, 1),\n" +
                "    else: Application.put_env(:dep, :key, 1)" to READ to null,
            "if Application.get_env(:dep, :configured), do: :ok\n  Application.put_env(:dep, :key, 1)" to READ to "1",
            "try do\n    if Application.get_env(:dep, :configured), do: raise(\"x\")\n" +
                "    Application.put_env(:dep, :key, 1)\n  rescue\n    _ -> :ok\n  end" to READ to null,
            "Application.put_env(:dep, :a, 0)\n  Application.put_env(:other, :b, 0)\n" +
                "  for _ <- Application.get_env(:unread, :configured) do\n" +
                "    if Application.get_env(:dep, :a) == 1, do: Application.put_env(:other, :b, 1)\n" +
                "    Application.put_env(:dep, :a, 1)\n  end" to "Application.get_env(:other, :b)" to null,
        )

    /**
     * A write in a module defined in an `fn` the body never calls, which Elixir may run any number of times or never,
     * leaves its key unknown. One a match binds runs only where a statement calls it.
     */
    fun testAWriteInAModuleAnUncalledFnDefinesLeavesItsKeyUnknown() =
        assertWritten(
            "Enum.map([], fn _ -> defmodule B do Application.put_env(:dep, :key, :other) end end)" to READ to null,
            "f = fn -> defmodule B do Application.put_env(:dep, :key, :other) end end\n  _ = f" to READ to ":value",
        )

    /**
     * On a small IDE, an app's `.app` in the SDK the module's Elixir facet names is read, though no order entry has it.
     */
    fun testASmallIdesFacetModelsAppFileIsRead() {
        val models = FacetModelFixtures()
        models.onSmallIde(project, testRootDisposable)
        val roots = models.roots("AppEnvTestLogger", testRootDisposable)
        File(roots.ebin.path, "logger.app")
            .writeText("{application, logger, [{vsn, \"1.0.0\"}, {env, [{level, info}]}]}.\n")
        roots.ebin.refresh(false, true)
        FacetModelFixtures.nameFacet(module, models.register("App Env Test Logger", roots).name)
        FacetModelFixtures.settle(project)
        val asker = myFixture.addFileToProject("lib/asker.ex", "")

        val level = ReadAction.computeBlocking<AppEnvValue, Throwable> {
            ProjectModules.getInstance(project).appEnv(ProjectModules.Inputs(legLevel(), legKernel, OS.CURRENT), asker)
                .of("logger", OtpErlangAtom("level"))
        }

        assertEquals(AppEnvValue.Declared(OtpErlangAtom("info")), level)
    }

    /** `:application`'s writers raise where their options aren't a timeout or a list. */
    fun testAWriteWhoseOptionsArentATimeoutOrAListRaises() {
        assertFile(
            "defmodule A do\n  :application.set_env(:dep, :key, :other, :bad)\nend",
            "module Elixir.A raised Elixir.FunctionClauseError `:application.set_env(:dep, :key, :other, :bad)`",
        )
        assertFile(
            "defmodule A do\n  :application.unset_env(:dep, :key, -1)\nend",
            "module Elixir.A raised Elixir.FunctionClauseError `:application.unset_env(:dep, :key, -1)`",
        )
    }

    /**
     * A timeout of `0`, which `gen:do_call/4`'s `receive` runs out at once, or past what a `receive` waits, which raises
     * there: either is reached after the request is sent, so whether the call returns, and when the key is written, isn't
     * known.
     */
    fun testAWriteWhoseTimeoutMayRunOutIsntKnown() {
        assertWritten(
            "try do Application.put_env(:dep, :key, :other, timeout: 0) catch _, _ -> :ok end" to READ to null,
            "try do :application.set_env(:dep, :key, :other, 4294967296) catch _, _ -> :ok end" to READ to null,
        )
    }

    /**
     * A write's options name the timeout `application_controller` calls with, `proplists:get_value(timeout, Opts, 5000)`:
     * one `gen:call` doesn't take exits before anything is written, an improper list raises in `proplists` before it is
     * read, and one not known leaves the key unknown.
     */
    fun testAWriteWhoseTimeoutIsntOneExitsBeforeItWrites() {
        val exits = "catch :exit, {{:function_clause, _}, {:gen_server, :call, [:application_controller, request, timeout]}} ->"
        assertWritten(
            "try do Application.put_env(:dep, :key, :other, timeout: :bad) catch :exit, _ -> :ok end" to READ to ":value",
            "try do :application.unset_env(:dep, :key, [{:timeout, -1}]) catch :exit, _ -> :ok end" to READ to ":value",
            "try do :application.set_env(:dep, :key, :other, [:timeout]) catch :exit, _ -> :ok end" to READ to ":value",
            "try do :application.set_env(:dep, :key, :other, [:a | :b]) rescue FunctionClauseError -> :ok end" to READ to
                ":value",
            ":application.set_env(:dep, :key, :other, [{:timeout}, {:timeout, :bad}])" to READ to ":other",
            "Application.put_env(:dep, :key, :other, timeout: Application.get_env(:dep, :configured))" to READ to null,
            "try do :application.set_env(:dep, :key, :other, Application.get_env(:dep, :configured)) catch _, _ -> :ok end" to
                READ to null,
            "r = try do Application.put_env(:dep, :key, :other, timeout: :bad) $exits {request, timeout} end" to "r" to
                "{{:set_env, :dep, :key, :other, [timeout: :bad]}, :bad}",
            "r = try do :application.unset_env(:dep, :key, [:timeout]) $exits {request, timeout} end" to "r" to
                "{{:unset_env, :dep, :key, [:timeout]}, true}",
        )
    }

    /** `equal/0` where [read] gives [value] and `different/0` where it doesn't, each a line of a module body. */
    private fun defines(read: String, value: String) =
        "  if $read == $value, do: def(equal, do: 1)\n  if $read != $value, do: def(different, do: 1)\n"

    /** What [code]'s module `A` defines, where it compiled, else how it ended. */
    private fun defined(code: String): String {
        val result = expand(code).modules.single { it.module == "Elixir.A" }

        return if (result.ended == Ended.Compiled) {
            result.table.entries.map { "${it.key.name}/${it.key.arity}" }.sorted().joinToString(" ")
        } else {
            "${result.ended}"
        }
    }

    /**
     * Asserts that each read gives its value: the module defining `equal/0` where the read gives it and `different/0`
     * where it doesn't, or both where the read's value isn't known (`null`), compared with `true`, which a value not
     * known, a boolean among them, may be.
     */
    private fun assertAnswers(vararg reads: Pair<String, String?>) =
        assertEquals(
            reads.joinToString("\n") { (read, value) -> "$read: " + if (value == null) "different/0 equal/0" else "equal/0" },
            reads.joinToString("\n") { (read, value) ->
                "$read: " + defined("defmodule A do\n  require Application\n${defines(read, value ?: "true")}end\n")
            },
        )

    /**
     * Asserts that each read, after its writes, gives its value as [assertAnswers] does: `equal/0` where it gives the
     * value, `different/0 equal/0` where it isn't known (`null`).
     */
    private fun assertWritten(vararg rows: Pair<Pair<String, String>, String?>) =
        assertWritten(rows) { writes, defines -> "defmodule A do\n  require Application\n  $writes\n${defines}end\n" }

    /**
     * Asserts that each read, after a function of another module runs its writes, gives its value as [assertWritten]
     * does.
     */
    private fun assertWrittenInAFunction(vararg rows: Pair<Pair<String, String>, String?>) =
        assertWritten(rows) { writes, defines ->
            "defmodule W do\n  def f do\n  $writes\n  end\nend\ndefmodule A do\n  W.f()\n${defines}end\n"
        }

    /** Asserts [rows] as [assertWritten] does, each in the file [code] makes of its writes and the read's [defines]. */
    private fun assertWritten(
        rows: Array<out Pair<Pair<String, String>, String?>>,
        code: (writes: String, defines: String) -> String,
    ) =
        assertEquals(
            rows.joinToString("\n") { (row, value) ->
                "${row.first} | ${row.second}: " + if (value == null) "different/0 equal/0" else "equal/0"
            },
            rows.joinToString("\n") { (row, value) ->
                val (writes, read) = row
                "$writes | $read: " + defined(code(writes, defines(read, value ?: "true")))
            },
        )

    /** Asserts how [code]'s one module ended, in a file named [name]. */
    private fun assertFile(code: String, expected: String, name: String = "app_env.ex") {
        val result = expand(code, name).modules.single()
        val ended = when (val ended = result.ended) {
            Ended.Compiled -> "compiled"
            Ended.Tainted -> "tainted"
            is Ended.Raised -> "raised ${ended.error.kind} `${ended.error.at.meta.origin.substring(code)}`"
            is Ended.Crashed -> "crashed ${ended.exception}"
            is Ended.Stopped -> "stopped `${ended.at.meta.origin.substring(code)}`"
        }

        assertEquals(expected, "module ${result.module} $ended")
    }

    private fun expand(code: String, name: String = "app_env.ex"): FileExpansion {
        val level = legLevel()
        val file = ReadAction.computeBlocking<ElixirFile, Throwable> {
            PsiFileFactory.getInstance(project).createFileFromText(name, ElixirLanguage, code) as ElixirFile
        }
        val forms = ReadAction.computeBlocking<_, Throwable> { Lowering.lower(file, level) }

        return Expander.expandFile(
            forms,
            Env.empty(level, legKernel),
            level,
            legExports,
            legStructs,
            APP_ENV,
            clauses = sdk,
            virtualFile = file.viewProvider.virtualFile,
        )
    }

    private companion object {
        /** The read of `dep`'s `key`, which the writes' rows test. */
        const val READ = "Application.get_env(:dep, :key)"

        /**
         * The leg's apps, `dep` declaring the keys the rows read, one of which a config file also sets, `own`, whose
         * `.app` can't be read, and `unread`, which a config file sets in a way that can't be read besides setting one
         * key.
         */
        val APP_ENV = legAppEnv(
            apps = mapOf(
                "dep" to "{application, dep, [{vsn, \"0.1.0\"}, {env, [{key, value}, {charlist, \"abc\"}, " +
                    "{keyword, [{sub, 1}]}, {map, #{sub => 2}}, {configured, other}]}]}.\n",
                "own" to "{application, own, [{env, [{key, value}]}]}",
                "unread" to "{application, unread, [{vsn, \"0.1.0\"}, {env, [{key, value}]}]}.\n",
            ),
            configured = { app: String, key: OtpErlangObject ->
                app in setOf("dep", "unread") && key == OtpErlangAtom("configured")
            },
            unread = { app -> app == "unread" },
        )
    }
}
