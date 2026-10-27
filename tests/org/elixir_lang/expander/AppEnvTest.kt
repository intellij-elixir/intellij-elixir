package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangObject
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFileFactory
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.expander.ExpansionResult.Ended
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.psi.ElixirFile

/**
 * A module body's `Application` env reads answer from [AppEnv]: a key a config file sets isn't known, else the term the
 * app's `.app` declares, else the key is unset; where the `.app` can't be read nothing is known. Every answer it knows is
 * an assumption, so an error after it stops the module.
 */
class AppEnvTest : PlatformTestCase() {
    private lateinit var sdk: ModuleClauses.Lookup

    override fun setUp() {
        super.setUp()

        sdk = sdkClauses(project, testRootDisposable)
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
            "Application.fetch_env!(:dep, :missing)" to null,
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
            "Application.compile_env!(:dep, :missing)" to null,
            "Application.compile_env!(:dep, [:keyword, :missing])" to null,
            "Application.compile_env!(:own, :key)" to null,
        )

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
     * A write to an app's env, through `Application`'s writers or `:application`'s, leaves the app's later reads unknown,
     * and every app's where the app isn't known. Another app's write leaves them known, as does a write after the read.
     */
    fun testAWriteToAnAppsEnvLeavesItsLaterReadsUnknown() {
        val writes = listOf(
            "Application.put_env(:dep, :key, :other)" to null,
            "Application.put_env(:dep, :key, :other, persistent: true)" to null,
            "Application.delete_env(:dep, :key)" to null,
            "Application.put_all_env(dep: [key: :other])" to null,
            ":application.set_env(:dep, :key, :other)" to null,
            ":application.set_env(:dep, :key, :other, :infinity)" to null,
            ":application.set_env([dep: [key: :other]])" to null,
            ":application.unset_env(:dep, :key)" to null,
            ":application.unset_env(:dep, :key, 5000)" to null,
            ":application.set_env(Application.get_env(:dep, :configured), :key, :other)" to null,
            "Application.put_env(:other, :key, :other)" to "equal/0",
        )
        val read = "Application.get_env(:dep, :key)"

        assertEquals(
            writes.joinToString("\n") { (write, expected) -> "$write: ${expected ?: "different/0 equal/0"}" },
            writes.joinToString("\n") { (write, _) ->
                "$write: " + defined("defmodule A do\n  $write\n${defines(read, ":value")}end\n")
            },
        )
        assertEquals(
            "a write after the read",
            "equal/0",
            defined("defmodule A do\n${defines(read, ":value")}  Application.put_env(:dep, :key, :other)\nend\n"),
        )
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

    /** `equal/0` where [read] gives [value] and `different/0` where it doesn't, each a line of a module body. */
    private fun defines(read: String, value: String) =
        "  if $read == $value, do: def(equal, do: 1)\n  if $read != $value, do: def(different, do: 1)\n"

    /** What [code]'s one module defines, where it compiled, else how it ended. */
    private fun defined(code: String): String {
        val result = expand(code).modules.single()

        return if (result.ended == Ended.Compiled) {
            result.table.entries.map { "${it.key.name}/${it.key.arity}" }.sorted().joinToString(" ")
        } else {
            "${result.ended}"
        }
    }

    /**
     * Asserts that each read gives its value: the module defining `equal/0` where the read gives it and `different/0`
     * where it doesn't, or both where the read's value isn't known (`null`).
     */
    private fun assertAnswers(vararg reads: Pair<String, String?>) =
        assertEquals(
            reads.joinToString("\n") { (read, value) -> "$read: " + if (value == null) "different/0 equal/0" else "equal/0" },
            reads.joinToString("\n") { (read, value) ->
                val compared = value ?: ":unknowable"
                val code = "defmodule A do\n  require Application\n" +
                    "  if $read == $compared, do: def(equal, do: 1)\n" +
                    "  if $read != $compared, do: def(different, do: 1)\nend\n"
                val result = expand(code).modules.single()

                "$read: " + if (result.ended == Ended.Compiled) {
                    result.table.entries.map { "${it.key.name}/${it.key.arity}" }.sorted().joinToString(" ")
                } else {
                    "${result.ended}"
                }
            },
        )

    /** Asserts how [code]'s one module ended. */
    private fun assertFile(code: String, expected: String) {
        val result = expand(code).modules.single()
        val ended = when (val ended = result.ended) {
            Ended.Compiled -> "compiled"
            Ended.Tainted -> "tainted"
            is Ended.Raised -> "raised ${ended.error.kind} `${ended.error.at.meta.origin.substring(code)}`"
            is Ended.Crashed -> "crashed ${ended.exception}"
            is Ended.Stopped -> "stopped `${ended.at.meta.origin.substring(code)}`"
        }

        assertEquals(expected, "module ${result.module} $ended")
    }

    private fun expand(code: String): FileExpansion {
        val level = legLevel()
        val file = ReadAction.computeBlocking<ElixirFile, Throwable> {
            PsiFileFactory.getInstance(project).createFileFromText("app_env.ex", ElixirLanguage, code) as ElixirFile
        }
        val forms = ReadAction.computeBlocking<_, Throwable> { Lowering.lower(file, level) }

        return Expander.expandFile(forms, Env.empty(level, legKernel), level, legExports, legStructs, APP_ENV, clauses = sdk)
    }

    private companion object {
        /**
         * The leg's apps, `dep` declaring the keys the rows read, one of which a config file also sets, and `own`, whose
         * `.app` can't be read.
         */
        val APP_ENV = legAppEnv(
            apps = mapOf(
                "dep" to "{application, dep, [{vsn, \"0.1.0\"}, {env, [{key, value}, {charlist, \"abc\"}, " +
                    "{keyword, [{sub, 1}]}, {map, #{sub => 2}}, {configured, other}]}]}.\n",
                "own" to "{application, own, [{env, [{key, value}]}]}",
            ),
            configured = { app: String, key: OtpErlangObject -> app == "dep" && key == OtpErlangAtom("configured") },
        )
    }
}
