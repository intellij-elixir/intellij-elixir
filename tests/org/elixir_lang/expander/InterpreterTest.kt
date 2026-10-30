package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangExternalFun
import com.ericsson.otp.erlang.OtpErlangFun
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangPid
import com.ericsson.otp.erlang.OtpErlangRef
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiFileFactory
import com.intellij.util.system.OS
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.expander.ExpansionResult.Ended
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.language_level.ElixirLanguageFeature.BITSTRING_SIZE_EXPANDED_AS_GUARD
import org.elixir_lang.language_level.ElixirLanguageFeature.COMPILE_ENV_TAKES_ENV_FIRST
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFIMPL_IMPL4
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFINER_REFUSED_IN_MATCH_OR_GUARD
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFINITION_ESCAPES_HEAD_AND_BODY_TOGETHER
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFMODULE_FAST_PATH
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFSTRUCT_ESCAPES_STRUCT
import org.elixir_lang.language_level.ElixirLanguageFeature.DUPLICATE_MATCH_UNPACKED_ONCE
import org.elixir_lang.language_level.ElixirLanguageFeature.ELLIPSIS_NULLARY_CALL
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_MAP_ENTRIES_QUOTED_APART
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_MAP_ENTRIES_SORTED
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_MAP_IN_VM_ORDER
import org.elixir_lang.language_level.ElixirLanguageFeature.FIELD_OF_NON_MAP_RAISES_BADKEY
import org.elixir_lang.language_level.ElixirLanguageFeature.FIELD_OF_NON_MAP_RAISES_BADMAP
import org.elixir_lang.language_level.ElixirLanguageFeature.FN_CLAUSE_ERROR_IS_FUNCTION_CLAUSE
import org.elixir_lang.language_level.ElixirLanguageFeature.FUNCTION_ERRORS_CONTINUE
import org.elixir_lang.language_level.ElixirLanguageFeature.GENERATED_HEADS_CHECKED
import org.elixir_lang.language_level.ElixirLanguageFeature.GENERATOR_RIGHT_SIDE_SCOPED
import org.elixir_lang.language_level.ElixirLanguageFeature.INTEGERS_UP_TO_4_MBIT
import org.elixir_lang.language_level.ElixirLanguageFeature.IN_REFUSES_UNESCAPED_RIGHT_SIDE
import org.elixir_lang.language_level.ElixirLanguageFeature.KEY_ERROR_MESSAGE_INSPECTED_WITH_OPTIONS
import org.elixir_lang.language_level.ElixirLanguageFeature.LISTS_CHECK_THE_FUN_FIRST
import org.elixir_lang.language_level.ElixirLanguageFeature.LISTS_SORT_KEEPS_EQUAL_ORDER
import org.elixir_lang.language_level.ElixirLanguageFeature.MACRO_ENV_VERSIONED_VARS
import org.elixir_lang.language_level.ElixirLanguageFeature.MACRO_ARGUMENTS_STOP_GENERATED
import org.elixir_lang.language_level.ElixirLanguageFeature.MACRO_ESCAPE_MARKS_GENERATED
import org.elixir_lang.language_level.ElixirLanguageFeature.MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER
import org.elixir_lang.language_level.ElixirLanguageFeature.MAP_UPDATE_RAISES_WHERE_DISCARDED
import org.elixir_lang.language_level.ElixirLanguageFeature.MODULE_ATTRIBUTES_IN
import org.elixir_lang.language_level.ElixirLanguageFeature.MODULE_DELETE_DEFINITION
import org.elixir_lang.language_level.ElixirLanguageFeature.MODULE_GET_LAST_ATTRIBUTE
import org.elixir_lang.language_level.ElixirLanguageFeature.MODULE_OVERRIDABLES_IN
import org.elixir_lang.language_level.ElixirLanguageFeature.NEGATIVE_ZERO_IS_DISTINCT
import org.elixir_lang.language_level.ElixirLanguageFeature.OVERRIDABLE_GUARDS_MODULE
import org.elixir_lang.language_level.ElixirLanguageFeature.PID_IN_FUNCTION_RAISES_MATCH_ERROR
import org.elixir_lang.language_level.ElixirLanguageFeature.PINNED_BINARY_SEGMENT_INFERS_SIZE
import org.elixir_lang.language_level.ElixirLanguageFeature.POST_MODULE_LOCAL_CHECKS_TYPED
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_BEFORE_COMPILE
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_IMPORTS_EVERY_ARITY
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_KEEPS_IMPORTS_META
import org.elixir_lang.language_level.ElixirLanguageFeature.REGEX_ESCAPED_IN_FIXED_FIELD_ORDER
import org.elixir_lang.language_level.ElixirLanguageFeature.RESCUE_CALL_EXPANDED_AS_MACRO
import org.elixir_lang.language_level.ElixirLanguageFeature.RESCUED_ERROR_MESSAGE_FROM_ERROR_INFO
import org.elixir_lang.language_level.ElixirLanguageFeature.RESCUE_EXCEPTION_KEY_PRESENT
import org.elixir_lang.language_level.ElixirLanguageFeature.SPLICE_EMPTY_BEFORE_TAIL_RAISES
import org.elixir_lang.language_level.ElixirLanguageFeature.STATIC_OPTIONS_IN_PERSISTENT_TERM
import org.elixir_lang.language_level.ElixirLanguageFeature.UNQUOTE_SHALLOW_VALIDATED
import org.elixir_lang.language_level.ElixirLanguageFeature.UNREQUIRED_MACRO_CALLED_AS_FUNCTION
import org.elixir_lang.language_level.ElixirLanguageFeature.WITH_OPTIONS_BEFORE_LAST_ARGUMENT
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Lowering
import org.elixir_lang.lowering.inspect
import org.elixir_lang.psi.ElixirFile
import java.io.File
import java.math.BigInteger
import kotlin.time.Duration.Companion.seconds

/**
 * A call to a macro with no summary, evaluated from its clauses, from source and from its `.beam`'s `Dbgi`, a row for
 * each clause the evaluator ports. Each row is judged three ways: its output against
 * Elixir's `Macro.expand_once/2` of the call, up to metadata and counters; the probes after the call against Elixir's;
 * and the definition table against what Elixir's `@before_compile` sees.
 */
class InterpreterTest : PlatformTestCase() {
    private val parse: (String) -> ElixirFile = { text ->
        ReadAction.computeBlocking<ElixirFile, Throwable> {
            PsiFileFactory.getInstance(project).createFileFromText("probe.ex", ElixirLanguage, text) as ElixirFile
        }
    }
    private val harness = ProbeHarness(parse)
    private val probes = ExpansionProbes(harness, definitions = true, parse = parse)
    private lateinit var sdk: ModuleClauses.Lookup

    /** [legExports], and where it has none, the module's [appBeams] `.beam`'s, as for `EEx`. */
    private val sdkExports: Exports = HashMap<String, ModuleExports>().let { read ->
        Exports { module ->
            legExports.of(module).takeUnless { it == ModuleExports.Absent }
                ?: read.getOrPut(module) {
                    appBeams[module]?.let { LoadedModules.exports(it.readBytes(), it.path) } ?: ModuleExports.Absent
                }
        }
    }

    override fun setUp() {
        super.setUp()

        sdk = sdkClauses(project, testRootDisposable)
        exUnitOnQuoter()
    }

    fun testFromSource() = withEex { assertRows(Source.SOURCE) }

    fun testFromDebugInfo() = withEex { assertRows(Source.DBGI) }

    /**
     * [block], with the leg's `EEx`, which the quoter's release doesn't hold, loaded from its [appBeams] `.beam`s for
     * Elixir's compiles, as [probeLoaded] loads a module, then unloaded, so no other test's compile sees it.
     */
    private fun withEex(block: () -> Unit) {
        val ebin = appBeams.getValue("Elixir.EEx").parentFile
        val beams = appBeams.filterValues { it.parentFile == ebin }
            .entries
            .joinToString(", ") { (module, beam) -> "{\"$module\", ${elixirString(beam.path)}}" }

        val loaded = harness.compileSource(
            """
            for {name, path} <- [$beams] do
              {:module, _} = :code.load_binary(String.to_atom(name), String.to_charlist(path), File.read!(path))
            end
            """.trimIndent(),
        )

        assertEquals("EEx's load", OtpErlangAtom("ok"), loaded.status)
        try {
            block()
        } finally {
            harness.compileSource(
                """
                for {name, _} <- [$beams] do
                  :code.delete(String.to_atom(name))
                  :code.purge(String.to_atom(name))
                end
                """.trimIndent(),
            )
        }
    }

    // A raise is the module's, or the file's, only where everything Elixir ran before it had an outcome the evaluator
    // produced. Each file's ending is Elixir's on 1.11.4 and 1.20.4, run.

    /** A block's statements before its last run before it, so a raise in one is the module's. */
    fun testARaiseInABlocksEarlierStatementIsTheModules() =
        assertFile(
            "defmodule A do\n  x = (raise ArgumentError; :ok)\n  @a raise(KeyError)\nend",
            "top expanded\nmodule Elixir.A raised Elixir.ArgumentError `raise ArgumentError`",
        )

    /** Elixir raises `KeyError` in the block, so the later raise stops. */
    fun testARaiseAfterABlocksEarlierStatementTheEvaluatorCannotRunStops() =
        assertFile(
            "defmodule A do\n  x = (receive do after 0 -> raise KeyError end; :ok)\n  @a raise(ArgumentError)\nend",
            "top unported `@a raise(ArgumentError)`\nmodule Elixir.A stopped `@a raise(ArgumentError)`",
        )

    /** A statement the evaluator can't run may have raised, as `Map.fetch!/2` does here. */
    fun testARaiseAfterAStatementTheEvaluatorCannotRunStops() =
        assertFile(
            "defmodule A do\n  receive do\n    after 0 -> Map.fetch!(%{}, :k)\n  end\n  @a raise(ArgumentError)\nend",
            "top unported `@a raise(ArgumentError)`\nmodule Elixir.A stopped `@a raise(ArgumentError)`",
        )

    /** A `case` the evaluator can't run may have raised, as its `Map.fetch!/2` does here. */
    fun testARaiseAfterACaseTheEvaluatorCannotRunStops() =
        assertFile(
            "defmodule A do\n  case (receive do after 0 -> %{} end) do\n    m -> Map.fetch!(m, :k)\n  end\n  @a raise(ArgumentError)\nend",
            "top unported `@a raise(ArgumentError)`\nmodule Elixir.A stopped `@a raise(ArgumentError)`",
        )

    /**
     * A module body's last expression is its value, which `defmodule` returns, so a map update there raises on every
     * OTP.
     */
    fun testAMapUpdateAsTheBodysLastExpressionIsTheModules() =
        assertFile(
            "defmodule A do\n  m = %{}\n  %{m | a: 1}\nend",
            "top expanded\nmodule Elixir.A raised Elixir.KeyError `%{m | a: 1}`",
        )

    /**
     * A map update at a file's top level whose value is discarded raises where its map lacks a key from OTP 25.3.1, so
     * the module after it isn't compiled; before it the compiler drops the update.
     */
    fun testADiscardedTopLevelMapUpdateRaisesFromOtp25_3_1() =
        assertFile(
            "m = %{}\n%{m | a: 1}\ndefmodule A do\nend",
            if (MAP_UPDATE_RAISES_WHERE_DISCARDED.isSufficient(legLevel())) {
                "top error Elixir.KeyError `%{m | a: 1}`"
            } else {
                "top expanded\nmodule Elixir.A compiled"
            },
        )

    /** A `case` the evaluator runs raises where Elixir does. */
    fun testACaseTheEvaluatorRunsThatRaisesIsTheModules() =
        assertFile(
            "defmodule A do\n  case Map.fetch!(%{}, :k) do\n    _ -> :ok\n  end\n  @a raise(ArgumentError)\nend",
            "top expanded\nmodule Elixir.A raised Elixir.KeyError `case Map.fetch!(%{}, :k) do\n    _ -> :ok\n  end`",
        )

    /** A statement no other unit runs is run by the evaluator where the body runs it. */
    fun testARaiseAfterAStatementTheEvaluatorRunsIsTheModules() =
        assertFile(
            "defmodule A do\n  f = fn _ -> 1 end\n  f.(1)\n  @a raise(ArgumentError)\nend",
            "top expanded\nmodule Elixir.A raised Elixir.ArgumentError `raise(ArgumentError)`",
        )

    fun testAStatementTheEvaluatorRunsThatRaisesIsTheModules() =
        assertFile(
            "defmodule A do\n  f = fn _ -> raise KeyError end\n  f.(1)\n  @a raise(ArgumentError)\nend",
            "top expanded\nmodule Elixir.A raised Elixir.KeyError `f.(1)`",
        )

    fun testAFileMatchThatRaisesAfterAStatementTheEvaluatorRunsIsTheFilesError() =
        assertFile("x = 0\nf = fn _e -> x = 1 end\nf.(1)\n^x = 1", "top error Elixir.MatchError `^x = 1`")

    /** A match whose block ends in a definition the walk ran as its own unit holds that definition's value. */
    fun testARaiseAfterAMatchOfABlockEndingInADefinitionIsTheModules() =
        assertFile(
            "defmodule A do\n  x = (:ok\n  def f(y), do: y)\n  @a raise(ArgumentError)\nend",
            "top expanded\nmodule Elixir.A raised Elixir.ArgumentError `raise(ArgumentError)`",
        )

    /** A `case` no clause of matches raises `CaseClauseError`, which `Exception.normalize/3` names from its clauses. */
    fun testACaseNoClauseMatchesIsTheModules() =
        assertFile(
            "defmodule A do\n  case 1 do\n    2 -> :ok\n  end\nend",
            "top expanded\nmodule Elixir.A raised Elixir.CaseClauseError `case 1 do\n    2 -> :ok\n  end`",
        )

    /** A statement whose value the expansion knows ran with that outcome. */
    fun testARaiseAfterStatementsWithKnownValuesIsTheModules() =
        assertFile(
            "defmodule A do\n  alias Foo.Bar\n  import Kernel\n  require Integer\n  :ok\n  @a raise(ArgumentError)\nend",
            "top expanded\nmodule Elixir.A raised Elixir.ArgumentError `raise(ArgumentError)`",
        )

    /** A port's output says where it raises, the macros it calls included, so a raise after it is the module's. */
    fun testARaiseAfterAPortsOutputIsTheModules() =
        assertFile(
            "defmodule A do\n  defexception [:reason]\n  @a raise(ArgumentError)\nend",
            "top expanded\nmodule Elixir.A raised Elixir.ArgumentError `raise(ArgumentError)`",
        )

    /** A directive's value is its module, which a match takes. */
    fun testADirectiveInAMatchIsItsModule() =
        assertFile(
            "defmodule A do\n  Foo.Bar = alias Foo.Bar\n  Integer = require Integer\n  Kernel = import Kernel\n  @a raise(ArgumentError)\nend",
            "top expanded\nmodule Elixir.A raised Elixir.ArgumentError `raise(ArgumentError)`",
        )

    /** A decline in a nested module's body is one in the body around it. */
    fun testARaiseAfterANestedModulesDeclineStops() =
        assertFile(
            """
            defmodule Outer do
              defmodule Inner do
                _ = receive do after 0 -> raise KeyError end
              end
              @a raise(ArgumentError)
            end
            """.trimIndent(),
            "top unported `@a raise(ArgumentError)`\nmodule Elixir.Outer stopped `@a raise(ArgumentError)`\n  module Elixir.Outer.Inner compiled",
        )

    /** A decline in a module's body is one in the file, for the modules after it, of only modules or not. */
    fun testARaiseAfterAnEarlierModulesDeclineStops() {
        val modules = """
            defmodule A do
              _ = receive do after 0 -> raise KeyError end
            end
            defmodule B do
              @a raise(ArgumentError)
            end
        """.trimIndent()
        val expected = "top unported `@a raise(ArgumentError)`\nmodule Elixir.A compiled\nmodule Elixir.B stopped `@a raise(ArgumentError)`"

        assertFile(modules, expected)
        assertFile("$modules\n:ok", expected)
    }

    /** A match of the file that raises is the file's error, and no module after it is compiled. */
    fun testAFileMatchThatRaisesIsTheFilesError() =
        assertFile("x = 1\n2 = x\ndefmodule M do\nend", "top error Elixir.MatchError `2 = x`")

    /** The checks after the body run after it, so after a decline they stop. */
    fun testAPostModuleCheckAfterADeclineStops() =
        assertFile(
            "defmodule A do\n  _ = receive do after 0 -> raise KeyError end\n  def f, do: u()\nend",
            "top unported `u()`\nmodule Elixir.A stopped `u()`",
        )

    /** A nested module's name, body and checks are run after the outer body's statements before it. */
    fun testANestedModuleAfterADeclineStops() {
        val decline = "_ = receive do after 0 -> raise KeyError end"

        assertFile(
            "defmodule O do\n  $decline\n  defmodule nil do\n  end\nend",
            "top unported `defmodule nil do\n  end`\nmodule Elixir.O stopped `defmodule nil do\n  end`\n  module nil stopped `defmodule nil do\n  end`",
        )
        assertFile(
            "defmodule O do\n  $decline\n  defmodule I do\n    @x 1, 2\n  end\nend",
            "top unported `@x 1, 2`\nmodule Elixir.O stopped `@x 1, 2`\n  module Elixir.O.I stopped `@x 1, 2`",
        )
        assertFile(
            "defmodule O do\n  $decline\n  defmodule I do\n    def f, do: u()\n  end\nend",
            "top unported `u()`\nmodule Elixir.O stopped `u()`\n  module Elixir.O.I stopped `u()`",
        )
    }

    /** Elixir raises for a module that isn't being compiled, or another file's process may be compiling it. */
    fun testAWriteToAModuleNotBeingCompiledStops() =
        assertFile(
            "defmodule A do\n  Module.put_attribute(String, :a, 1)\n  @b raise(KeyError)\nend",
            "top unported `Module.put_attribute(String, :a, 1)`\nmodule Elixir.A stopped `Module.put_attribute(String, :a, 1)`",
        )

    /** A nested module the body may not run may not have written the attribute, so the body's read of it stops. */
    fun testAWriteFromANestedModuleTheBodyMayNotRunIsUnknown() =
        assertFile(
            """
            defmodule Outer do
              if (receive do after 0 -> false end) do
                defmodule Inner do
                  Module.put_attribute(Outer, :a, 1)
                end
              end
              def unquote(:"f#{Module.get_attribute(__MODULE__, :a)}")(), do: 1
            end
            """.trimIndent(),
            """
            top expanded
            module Elixir.Outer stopped `unquote(:"f#{Module.get_attribute(__MODULE__, :a)}")`
              module Elixir.Outer.Inner compiled
            """.trimIndent(),
        )

    /** A nested module the body may not run may not raise: the body stops where the walk lost Elixir's path. */
    fun testARaiseInANestedModuleTheBodyMayNotRunStops() =
        assertFile(
            """
            defmodule Outer do
              if (receive do after 0 -> false end) do
                defmodule Inner do
                  @a raise(KeyError)
                end
              end
              @a 1
            end
            """.trimIndent(),
            """
            top expanded
            module Elixir.Outer stopped `if (receive do after 0 -> false end) do
                defmodule Inner do
                  @a raise(KeyError)
                end
              end`
              module Elixir.Outer.Inner stopped `@a raise(KeyError)`
            """.trimIndent(),
        )

    /** A match's right side runs its earlier statements before the match nested in it, so a decline in one stops it. */
    fun testARaiseInAMatchNestedAfterAStatementTheEvaluatorCannotRunStops() =
        assertFile(
            "defmodule M do\n  x = (receive do after 0 -> raise KeyError end; y = raise(ArgumentError))\nend",
            "top unported `y = raise(ArgumentError)`\nmodule Elixir.M stopped `y = raise(ArgumentError)`",
        )

    /** A list statement ending in `[h | t]` runs its head, so a decline there stops a later raise. */
    fun testARaiseAfterAnImproperListStatementStops() {
        val declines = "receive do after 0 -> raise ArgumentError end"

        assertFile(
            "defmodule A do\n  [$declines | []]\n  @a raise(ArgumentError)\nend",
            "top unported `@a raise(ArgumentError)`\nmodule Elixir.A stopped `@a raise(ArgumentError)`",
        )
        assertFile("x = 0\n[$declines | []]\n^x = 1", "top unported `^x = 1`")
    }

    /** `_ = for ...` runs its `for`, so a decline there stops a later raise. */
    fun testARaiseAfterADiscardedForStops() =
        assertFile(
            "defmodule A do\n  _ = for x <- [:k] do\n    receive do after 0 -> raise ArgumentError end\n  end\n  @a raise(ArgumentError)\nend",
            "top unported `@a raise(ArgumentError)`\nmodule Elixir.A stopped `@a raise(ArgumentError)`",
        )

    /**
     * Elixir raises in a module that stops, so no later raise is the file's for certain. It ends the file all the same:
     * Elixir ran nothing after it if it ran the file as the expander did, so no module after it is compiled.
     */
    fun testARaiseAfterAModuleThatStopsStopsAndEndsTheFile() {
        val stopped = "defmodule A1 do\n  Module.put_attribute(String, :a, 1)\nend"
        val a1 = "module Elixir.A1 stopped `Module.put_attribute(String, :a, 1)`"

        assertFile("$stopped\nx = raise KeyError\ndefmodule C do\n  def f, do: 1\nend", "top unported `x = raise KeyError`\n$a1")
        assertFile(
            "$stopped\ndefmodule B2 do\n  @a raise(KeyError)\nend\ndefmodule C do\n  def f, do: 1\nend",
            "top unported `@a raise(KeyError)`\n$a1\nmodule Elixir.B2 stopped `@a raise(KeyError)`",
        )
    }

    /**
     * A raise after a decline isn't the module's for certain, but it ends the module's run as a raise does, and the
     * file's, or the module's it is nested in as a statement: nothing after it ran if Elixir ran the body as the
     * expander did.
     */
    fun testARaiseThatMayNotBeTheModulesEndsItsRun() {
        val declines = "receive do after 0 -> :ok end"

        assertFile(
            "defmodule A do\n  $declines\n  raise ArgumentError\nend\ndefmodule C do\n  defstruct [:x]\nend",
            "top unported `raise ArgumentError`\nmodule Elixir.A stopped `raise ArgumentError`",
        )
        assertFile(
            "defmodule A do\n  def target(a), do: a\n  defmodule I do\n    $declines\n    raise ArgumentError\n  end\n  def after_it, do: 1\nend",
            "top unported `raise ArgumentError`\nmodule Elixir.A stopped `raise ArgumentError`\n  module Elixir.A.I stopped `raise ArgumentError`",
        )
        // A module that stopped before the nested raise keeps its first stop, and its run still ends.
        assertFile(
            "defmodule O5 do\n  Module.put_attribute(String, :a, 1)\n  defmodule I do\n    @a raise(ArgumentError)\n  end\n  def f, do: 1\nend\ndefmodule P do\nend",
            "top unported `Module.put_attribute(String, :a, 1)`\nmodule Elixir.O5 stopped `Module.put_attribute(String, :a, 1)`\n  module Elixir.O5.I stopped `@a raise(ArgumentError)`",
        )
    }

    /**
     * An error that reads what an unordered unit may have changed isn't the module's for certain: the table entry
     * Elixir never stored. The module stops there, and as the project is taken to compile, the run goes on: the module
     * that reads its struct, which isn't readable, stops.
     */
    fun testAnErrorAfterAnUnorderedUnitStopsAndTheRunGoesOn() {
        val b = "defmodule B do\n  def g, do: %A{}\nend"
        val stopsB = "\nmodule Elixir.B stopped `%A{}`"

        assertFile(
            "defmodule A do\n  defstruct [:x]\n  if $RECV_FALSE do\n    defmacro f, do: 1\n  end\n  def f, do: 2\nend\n$b",
            "top expanded\nmodule Elixir.A stopped `def f, do: 2`$stopsB",
        )
        assertFile(
            "defmodule A do\n  def f, do: 1\n  defoverridable f: 0\n  if $RECV_FALSE do\n    defmacro f, do: 1\n  end\n  def g, do: 2\nend\n$b",
            "top expanded\nmodule Elixir.A stopped `defmacro f, do: 1`$stopsB",
        )
        // From 1.15 the error in `g`'s body is reported, so the module raises once its body has run if Elixir stores
        // `g`. Before, it raises where it is, in a definition Elixir may not store, so the module only stops there.
        val stacktrace = "if $RECV_FALSE do\n    def g, do: __STACKTRACE__\n  end"

        assertFile(
            "defmodule A do\n  defstruct [:x]\n  $stacktrace\nend\n$b",
            if (FUNCTION_ERRORS_CONTINUE.isSufficient(legLevel())) {
                "top expanded\nmodule Elixir.A stopped `__STACKTRACE__`$stopsB"
            } else {
                "top expanded\nmodule Elixir.A stopped `$stacktrace`\nmodule Elixir.B stopped `%A{}`"
            },
        )
    }

    /** A function the module body calls writes its attribute, so a later read is what it wrote, as in Elixir. */
    fun testAnAttributeACalledFunctionWritesIsReadAfterIt() =
        assertFile(
            "defmodule H do\n  def set(m), do: Module.put_attribute(m, :x, :ok)\nend\n" +
                "defmodule A do\n  defstruct [:x]\n  @x :bad\n  H.set(__MODULE__)\n  :ok = @x\nend\n" +
                "defmodule B do\n  def g, do: %A{}\nend",
            "top expanded\nmodule Elixir.H compiled\nmodule Elixir.A compiled\nmodule Elixir.B compiled",
        )

    /** `:os.type/0` is the OS the file's SDK runs as. */
    fun testAnOsTypeIsTheOsTheSdkRunsAs() {
        val cases = mapOf(
            OS.Windows to "nt/0 def, win32/0 def",
            OS.macOS to "darwin/0 def, unix/0 def",
            OS.Linux to "linux/0 def, unix/0 def",
            OS.FreeBSD to "freebsd/0 def, unix/0 def",
        )

        for ((os, names) in cases) {
            assertEquals("$os", "top expanded\nmodule Elixir.A compiled [$names]", file(OS_TYPE, tables = true, run = RunInputs(os = os)))
        }
    }

    /** An OS no `os:type/0` clause names, and a run that has none, are not known, so the type is not either. */
    fun testAnOsTypeNobodyKnowsIsNotKnown() {
        for (os in listOf(OS.Other, null)) {
            assertEquals("$os", "top expanded\nmodule Elixir.A stopped `unquote(family)` []", file(OS_TYPE, tables = true, run = RunInputs(os = os)))
        }
    }

    /** `:file.get_cwd/0` is the directory the file is compiled in, which is not known without a file and its directory. */
    fun testTheWorkingDirectoryIsTheFilesCompileDirectory() {
        val code = "defmodule A do\n" +
            "  {:ok, cwd} = :file.get_cwd()\n" +
            "  def unquote(:erlang.binary_to_atom(:unicode.characters_to_binary(cwd), :utf8))(), do: 1\n" +
            "end"
        val stopped = "top expanded\nmodule Elixir.A stopped `unquote(:erlang.binary_to_atom(:unicode.characters_to_binary(cwd), :utf8))` []"

        assertEquals(
            "top expanded\nmodule Elixir.A compiled [/proj/0 def]",
            file(code, tables = true, run = RunInputs(OS.Linux, FILE, CompileDirectories { "/proj" })),
        )
        assertEquals(stopped, file(code, tables = true, run = RunInputs(OS.Linux, FILE)))
        assertEquals(stopped, file(code, tables = true, run = RunInputs(OS.Linux, null, CompileDirectories { "/proj" })))
    }

    /** A call that declined may have changed the directory, so the directory after it is not known. */
    fun testTheWorkingDirectoryAfterADeclinedCallIsUnknown() {
        val code = "defmodule A do\n" +
            "  :file.set_cwd(\"/other\")\n" +
            "  {:ok, cwd} = :file.get_cwd()\n" +
            "  def unquote(:erlang.binary_to_atom(:unicode.characters_to_binary(cwd), :utf8))(), do: 1\n" +
            "end"

        assertEquals(
            "top expanded\nmodule Elixir.A stopped `unquote(:erlang.binary_to_atom(:unicode.characters_to_binary(cwd), :utf8))` []",
            file(code, tables = true, run = RunInputs(OS.Linux, FILE, CompileDirectories { "/proj" })),
        )
    }

    /** A macro whose code declined may have changed the directory a later module reads. */
    fun testTheWorkingDirectoryAfterAMacroThatDeclinedIsUnknown() {
        val code = "defmodule M do\n  defmacro cd, do: (:file.set_cwd(\"/other\"); quote(do: :ok))\nend\n" +
            "defmodule A do\n  require M\n  M.cd()\nend\n" + CWD_MODULE

        assertEquals(
            "top expanded\nmodule Elixir.M compiled [cd/0 defmacro]\nmodule Elixir.A stopped `M.cd()` []\nmodule Elixir.B stopped `$CWD_NAME` []",
            file(code, tables = true, run = RunInputs(OS.Linux, FILE, CompileDirectories { "/proj" })),
        )
    }

    /** A module that stopped may have run code after the stop that changed the directory a later module reads. */
    fun testTheWorkingDirectoryAfterAStoppedModuleIsUnknown() {
        val code = "defmodule A do\n  quote(location: :other, do: 1)\nend\n" + CWD_MODULE

        assertEquals(
            "top expanded\nmodule Elixir.A stopped `quote(location: :other, do: 1)` []\nmodule Elixir.B stopped `$CWD_NAME` []",
            file(code, tables = true, run = RunInputs(OS.Linux, FILE, CompileDirectories { "/proj" })),
        )
    }

    /** An arm whose test declined and is passed over may have run code that changed the directory. */
    fun testTheWorkingDirectoryAfterAPassedOverArmIsUnknown() {
        val code = "defmodule A do\n  cond do\n    :file.set_cwd(\"/other\") == :ok -> :erlang.error(:x)\n    true -> :ok\n  end\n" +
            "  {:ok, cwd} = :file.get_cwd()\n  def $CWD_NAME(), do: 1\nend"

        assertEquals(
            "top expanded\nmodule Elixir.A stopped `$CWD_NAME` []",
            file(code, tables = true, run = RunInputs(OS.Linux, FILE, CompileDirectories { "/proj" })),
        )
    }

    /** A loop that goes on past an iteration that declined leaves the directory unknown too. */
    fun testTheWorkingDirectoryAfterALoopWhoseIterationDeclinedIsUnknown() {
        val code = "defmodule A do\n  x = for i <- [1, 2], do: (:file.set_cwd(\"/other\"); i)\n  y = Enum.map([1], fn i -> :file.set_cwd(\"/other\"); i end)\n  def f, do: unquote(x ++ y)\nend\n" + CWD_MODULE

        assertEquals(
            "top expanded\nmodule Elixir.A compiled [f/0 def]\nmodule Elixir.B stopped `$CWD_NAME` []",
            file(code, tables = true, run = RunInputs(OS.Linux, FILE, CompileDirectories { "/proj" })),
        )
    }

    /** `:file.get_cwd/0` answers a Windows drive lowercased, as OTP gives it, whatever the case the directory has. */
    fun testTheWorkingDirectoryOnWindowsHasALowercaseDrive() {
        val code = "defmodule A do\n  {:ok, cwd} = :file.get_cwd()\n  def $CWD_NAME(), do: 1\nend"

        assertEquals(
            "top expanded\nmodule Elixir.A compiled [c:/proj/0 def]",
            file(code, tables = true, run = RunInputs(OS.Windows, "c:/proj/lib/a.ex", CompileDirectories { "C:/proj" })),
        )
    }

    /**
     * A relative `@file` is made absolute against the directory the compile runs in now, so a definition that took one
     * is in the run's file where that directory is known, and moved after code that may have changed it.
     */
    fun testARelativeFileOfADefinitionIsMovedAfterTheDirectoryMayHaveChanged() {
        val run = RunInputs(OS.Linux, "/proj/lib/a.ex", CompileDirectories { "/proj" })
        val code = { cd: String ->
            "defmodule A do\n  $cd\n  @file \"lib/a.ex\"\n  defmacro n, do: __ENV__.file\nend\n" +
                "defmodule B do\n  require A\n  def unquote(:erlang.binary_to_atom(A.n(), :utf8))(), do: 1\nend"
        }

        assertEquals(
            "top expanded\nmodule Elixir.A compiled [n/0 defmacro]\nmodule Elixir.B compiled [/proj/lib/a.ex/0 def]",
            file(code(""), tables = true, run = run),
        )
        assertEquals(
            "top expanded\nmodule Elixir.A compiled [n/0 defmacro]\nmodule Elixir.B stopped `A.n()` []",
            file(code(":file.set_cwd(\"/other\")"), tables = true, run = run),
        )
    }

    /**
     * An absolute path expanded against an absolute directory reads the working directory only where Elixir does: from
     * 1.16.0-rc.1 not at all on a unix host, and on a Windows host always, as a path with no drive letter is relative to
     * the volume.
     */
    fun testAnAbsolutePathExpandedReadsTheWorkingDirectoryWhereElixirDoes() {
        val code = "defmodule A do\n  name = String.to_atom(Path.expand(\"/a/b.ex\", \"/c\"))\n  def unquote(name)(), do: 1\nend"
        val stopped = "top expanded\nmodule Elixir.A stopped `unquote(name)` []"
        val unknown = RunInputs(OS.Linux, FILE)

        assertEquals(
            if (legExpandsPathsLazily())"top expanded\nmodule Elixir.A compiled [/a/b.ex/0 def]" else stopped,
            file(code, tables = true, run = unknown),
        )
        assertEquals(stopped, file(code, tables = true, run = RunInputs(OS.Windows, FILE)))
    }

    /** The OS a file's SDK runs as decides where `:filename.split/1` reads a drive letter. */
    fun testASplitFollowsTheOsTheSdkRunsAs() {
        val code = "defmodule A do\n  [first | _] = :filename.split(\"C:/a\")\n  def unquote(:erlang.binary_to_atom(first, :utf8))(), do: 1\nend"

        assertEquals("top expanded\nmodule Elixir.A compiled [C:/0 def]", file(code, tables = true, run = RunInputs(OS.Linux)))
        assertEquals("top expanded\nmodule Elixir.A compiled [c://0 def]", file(code, tables = true, run = RunInputs(OS.Windows)))
    }

    /** `__DIR__` is `:filename.dirname/1` of the file's path, by the OS its SDK runs as, and unknown without either. */
    fun testTheDirIsTheDirectoryOfTheFilesPathByTheOsTheSdkRunsAs() {
        val code = "defmodule A do\n  def unquote(:erlang.binary_to_atom(__DIR__, :utf8))(), do: 1\nend"
        val stopped = "top expanded\nmodule Elixir.A stopped `unquote(:erlang.binary_to_atom(__DIR__, :utf8))` []"

        assertEquals("top expanded\nmodule Elixir.A compiled [/proj/lib/0 def]", file(code, tables = true, run = RunInputs(OS.Linux, "/proj/lib/a.ex")))
        assertEquals("top expanded\nmodule Elixir.A compiled [c:/proj/lib/0 def]", file(code, tables = true, run = RunInputs(OS.Windows, "c:/proj/lib\\a.ex")))
        assertEquals(stopped, file(code, tables = true, run = RunInputs(OS.Linux)))
        assertEquals(stopped, file(code, tables = true, run = RunInputs(null, "/proj/lib/a.ex")))
    }

    /** A definition that took an `@file` is compiled as in that file, so its `__ENV__.file` isn't the run's. */
    fun testTheEnvFileOfAMovedDefinitionIsUnknown() {
        val run = RunInputs(OS.Linux, "/proj/lib/a.ex")
        val code = { macros: String ->
            """
                defmodule A do
                  @file "/templates/view.ex"
                  $macros
                end
                defmodule B do
                  require A
                  def unquote(:erlang.binary_to_atom(A.n(), :utf8))(), do: 1
                end
            """.trimIndent()
        }

        assertEquals(
            "top expanded\nmodule Elixir.A compiled\nmodule Elixir.B stopped `A.n()`",
            file(code("defmacro n, do: __ENV__.file"), run = run),
        )
        assertEquals(
            "top expanded\nmodule Elixir.A compiled\nmodule Elixir.B compiled",
            file(code("defmacro m, do: 1\n  defmacro n, do: __ENV__.file"), run = run),
        )
    }

    /**
     * A statement's code runs in order, so a match, a definition or an attribute write in a later argument or element
     * reads what an earlier one wrote, as in Elixir.
     */
    fun testAUnitInALaterArgumentReadsAnEarlierArgumentsWrite() {
        val write = "Module.put_attribute(__MODULE__, :flag, :new)"
        val read = "Module.get_attribute(__MODULE__, :flag)"
        val units = listOf(
            "x = $read" to ":new = x",
            "def(unquote($read)(), do: 1)" to "true = Module.defines?(__MODULE__, {:new, 0})",
            "@copy $read" to ":new = @copy",
        )
        val statements = units.flatMap { (unit, check) ->
            val calls = listOf(":erlang.min($write, $unit)", "{$write, $unit}", "[$write, $unit]")
                .flatMap { listOf(it, "_ = $it") }
            // A whole statement that is a write of its own. A `def` in an `@`'s value declines, as the `@` copies it.
            val writes = listOfNotNull("@a {$write, $unit}".takeUnless { unit.startsWith("def") }, "Module.put_attribute(__MODULE__, :a, {$write, $unit})")

            (calls + writes).map { it to check }
        }

        assertEquals(
            statements.joinToString("\n") { (statement, _) -> "$statement: top expanded\nmodule Elixir.A compiled" },
            statements.joinToString("\n") { (statement, check) ->
                "$statement: " + file("defmodule A do\n  @flag :old\n  $statement\n  $check\nend")
            },
        )
    }

    /**
     * A `defimpl` or `defdelegate` whose value the code around it takes defines what it defines where that code runs it,
     * as where it is a statement of its own: on the statement path, and for `defimpl` in a body the walk follows.
     */
    fun testAMacroTheCodeAroundItTakesTheValueOfDefinesWhereThatCodeRunsIt() {
        val impl = "defimpl P, for: Integer do\n    def f(x), do: x\n  end"
        val delegate = "defdelegate(g(x), to: P, as: :f)"
        val taken = { form: String -> listOf(form, "_ = $form", "{$form, :ok}", "[$form]", ":erlang.min($form, :ok)") }
        val followed = listOf("if true do\n  $impl\nend", "_ = if true do\n  $impl\nend", "if Code.ensure_loaded?(String) do\n  $impl\nend")
        val shapes = (taken(impl) + followed).map { it to "module Elixir.A compiled\n  module Elixir.P.Integer compiled" } +
            taken(delegate).map { "$it\n  true = Module.defines?(__MODULE__, {:g, 1})" to "module Elixir.A compiled" }

        assertEquals(
            shapes.joinToString("\n") { (shape, ended) -> "$shape: top expanded\nmodule Elixir.P compiled\n$ended" },
            shapes.joinToString("\n") { (shape, _) ->
                "$shape: " + file("defprotocol P do\n  def f(x)\nend\ndefmodule A do\n  $shape\nend")
            },
        )
    }

    /** A module write whose argument holds a write in a body the walk doesn't follow writes both, as in Elixir. */
    fun testAModuleWriteWhoseArgumentHoldsAWriteTheWalkDoesntFollowWritesBoth() {
        val inner = "Module.put_attribute(__MODULE__, :b, 1)"
        val shapes = listOf(
            "cond do\n    true -> $inner\n  end" to ":ok = @a",
            "try do\n    $inner\n  after\n    nil\n  end" to ":ok = @a",
            "(try do\n    $inner\n  after\n    nil\n  end; 2)" to "2 = @a",
        )

        assertEquals(
            shapes.joinToString("\n") { (value, _) -> "$value: top expanded\nmodule Elixir.A compiled" },
            shapes.joinToString("\n") { (value, check) ->
                "$value: " + file("defmodule A do\n  Module.put_attribute(__MODULE__, :a, $value)\n  $check\n  1 = @b\nend")
            },
        )
    }

    /** A match in a `defdelegate`'s options binds as the options are bound, and the delegation reads them. */
    fun testAMatchInADefdelegatesOptionsBindsAsTheOptionsAreBound() {
        val shapes = listOf(
            "defdelegate(g(x), to: (m = P), as: :f)" to "P = m",
            "defdelegate(g(x), to: P, as: (n = :f))" to ":f = n",
        )

        assertEquals(
            shapes.joinToString("\n") { (shape, _) -> "$shape: top expanded\nmodule Elixir.P compiled\nmodule Elixir.A compiled" },
            shapes.joinToString("\n") { (shape, check) ->
                "$shape: " + file(
                    "defprotocol P do\n  def f(x)\nend\ndefmodule A do\n  $shape\n  true = Module.defines?(__MODULE__, {:g, 1})\n  $check\nend",
                )
            },
        )
    }

    /**
     * A definition that took an `@file` is compiled as in that file, so its `__DIR__` is that file's directory, which
     * isn't known; the module body and a definition after it are in the run's file.
     */
    fun testTheDirOfAMovedDefinitionIsUnknown() {
        val run = RunInputs(OS.Linux, "/proj/lib/a.ex")
        val moved = """
            defmodule A do
              @file "/templates/view.ex"
              defmacro m, do: __DIR__
            end
            defmodule B do
              require A
              def unquote(:erlang.binary_to_atom(A.m(), :utf8))(), do: 1
            end
        """.trimIndent()
        val own = """
            defmodule A do
              @file "/templates/view.ex"
              defmacro m, do: 1
              defmacro n, do: __DIR__
            end
            defmodule B do
              require A
              def unquote(:erlang.binary_to_atom(A.n(), :utf8))(), do: 1
            end
        """.trimIndent()

        assertEquals(
            "top expanded\nmodule Elixir.A compiled\nmodule Elixir.B stopped `A.m()`",
            file(moved, run = run),
        )
        assertEquals("top expanded\nmodule Elixir.A compiled\nmodule Elixir.B compiled", file(own, run = run))
    }

    /** `Macro.expand/2` of `__DIR__` is a binary whose bytes are unknown where the file's path or OS is, as `__CALLER__.file` is. */
    fun testMacroExpandOfTheDirIsABinaryWhereItIsUnknown() {
        val code = """
            defmodule A do
              defmacro m do
                if is_binary(Macro.expand(quote(do: __DIR__), __CALLER__)), do: quote(do: def(a, do: 1)), else: quote(do: def(b, do: 1))
              end
            end
            defmodule B do
              require A
              A.m()
            end
        """.trimIndent()
        val known = "top expanded\nmodule Elixir.A compiled\nmodule Elixir.B compiled"

        assertEquals(known, file(code, run = RunInputs(OS.Linux, "/proj/lib/a.ex")))
        assertEquals(known, file(code, run = RunInputs(OS.Linux)))
        assertEquals(known, file(code, run = RunInputs(null, "/proj/lib/a.ex")))
    }

    /**
     * A file of only modules stops at a name the fast path can't give, once the modules before it have compiled, and
     * ends at a raise before it, as any file ends at a raise.
     */
    fun testTheFastPathEndsAtARaise() {
        if (!DEFMODULE_FAST_PATH.isSufficient(legLevel())) return

        assertFile(
            "defmodule FA do\nend\ndefmodule x do\nend\ndefmodule FC do\nend",
            "top unported `x`\nmodule Elixir.FA compiled",
        )
        assertFile(
            "defmodule FA do\n  @a raise(ArgumentError)\nend\ndefmodule x do\nend\ndefmodule FC do\nend",
            "top expanded\nmodule Elixir.FA raised Elixir.ArgumentError `raise(ArgumentError)`",
        )
    }

    /**
     * A user `@on_definition` callback runs as each definition is stored, and what it writes is read after it: a match
     * of it, and a definition's name.
     */
    fun testAnOnDefinitionCallbacksWriteIsReadAfterTheDefinition() {
        val cb = "defmodule CB do\n  def on_def(env, _kind, _name, _args, _guards, _body), do: Module.put_attribute(env.module, :x, 2)\nend"
        val matches = "defmodule M do\n  @on_definition {CB, :on_def}\n  @x 1\n  def f, do: 1\n  2 = @x\nend"

        assertFile("$cb\n$matches", "top expanded\nmodule Elixir.CB compiled\nmodule Elixir.M compiled")
        assertFile(
            "$cb\ndefmodule M do\n  @on_definition {CB, :on_def}\n  @x 1\n  def f, do: 1\n  def unquote(:\"g#{Module.get_attribute(__MODULE__, :x)}\")(), do: 1\n  true = Module.defines?(__MODULE__, {:g2, 0})\nend",
            "top expanded\nmodule Elixir.CB compiled\nmodule Elixir.M compiled",
        )
    }

    /** `@on_definition` callbacks run newest first, so the first registered writes last. */
    fun testOnDefinitionCallbacksRunNewestFirst() {
        val cb = "defmodule CB do\n  def a(env, _, _, _, _, _), do: Module.put_attribute(env.module, :x, :a)\n  def b(env, _, _, _, _, _), do: Module.put_attribute(env.module, :x, :b)\nend"

        assertFile(
            "$cb\ndefmodule M do\n  @on_definition {CB, :a}\n  @on_definition {CB, :b}\n  def f, do: 1\n  :a = @x\nend",
            "top expanded\nmodule Elixir.CB compiled\nmodule Elixir.M compiled",
        )
    }

    /** An `@on_definition` callback that takes a definition out stops its module at the definition it was called for. */
    fun testAnOnDefinitionCallbacksTakeDefinitionStopsAtTheDefinition() {
        assertFile(
            "defmodule CB do\n  def take(env, _, _, _, _, _), do: :elixir_def.take_definition(env.module, {:g, 0})\nend\ndefmodule M do\n  @on_definition {CB, :take}\n  def f, do: 1\n  def r, do: 1\nend",
            "top expanded\nmodule Elixir.CB compiled\nmodule Elixir.M stopped `def f, do: 1`",
        )
    }

    /** An `@on_definition` entry that isn't a pair is skipped, so what the module holds stays known. */
    fun testAnOnDefinitionEntryThatIsntAPairIsSkipped() {
        assertFile(
            "defmodule M do\n  @x 1\n  @on_definition {M, :a, :b}\n  def f, do: 1\n  def unquote(:\"g#{@x}\")(), do: 1\nend",
            "top expanded\nmodule Elixir.M compiled",
        )
    }

    /**
     * A declined call given the module may write its attributes and remove its definitions, so a read of either after
     * it is unknown, and the definition whose name reads one stops.
     */
    fun testAStateReadAfterADeclinedCallGivenTheModuleIsUnknown() {
        val h = "defmodule H do\n  def set(m) do\n    receive(do: (x -> x), after: (0 -> :ok))\n    Module.put_attribute(m, :x, 2)\n  end\nend"
        val attribute = "unquote(:\"g#{Module.get_attribute(__MODULE__, :x)}\")"
        val defines = "unquote(if Module.defines?(__MODULE__, {:f, 0}), do: :y, else: :n)"

        for (read in listOf(attribute, defines)) {
            assertFile(
                "$h\ndefmodule M do\n  @x 1\n  def f, do: 1\n  H.set(__MODULE__)\n  def $read(), do: 1\nend",
                "top expanded\nmodule Elixir.H compiled\nmodule Elixir.M stopped `$read`",
            )
        }
    }

    /** A user `@on_definition` callback may write the module around its own, and that write is read after it too. */
    fun testAnOnDefinitionCallbacksWriteToAnEnclosingModuleIsReadAfterTheDefinition() {
        val inner = "defmodule In do\n    @on_definition {CB, :on_outer}\n    def f, do: 1\n    2 = Module.get_attribute(Outer, :x)\n  end"

        assertFile(
            "defmodule CB do\n  def on_outer(_env, _kind, _name, _args, _guards, _body), do: Module.put_attribute(Outer, :x, 2)\nend\n" +
                "defmodule Outer do\n  @x 1\n  $inner\nend",
            "top expanded\nmodule Elixir.CB compiled\nmodule Elixir.Outer compiled\n  module Elixir.Outer.In compiled",
        )
    }

    /**
     * Elixir expands a whole body before it runs any of it, so where the body's expansion stops, nothing in it is known
     * to have run: a module nested before the stop may never have been defined, and its raise never reached. The file's
     * top is a body too.
     */
    fun testAModuleRunAfterItsBodysExpansionStoppedMayNotHaveRun() {
        val m = "defmodule M do\n  defmacro m, do: receive(do: (x -> x), after: (0 -> raise ArgumentError))\nend\n"

        assertFile(
            "${m}defmodule O do\n  defmodule I do\n    raise KeyError\n  end\n  require M\n  M.m()\nend",
            "top unported `M.m()`\nmodule Elixir.M compiled\nmodule Elixir.O stopped `M.m()`\n  module Elixir.O.I stopped `raise KeyError`",
        )
        if (!DEFINER_REFUSED_IN_MATCH_OR_GUARD.isSufficient(legLevel())) {
            assertFile(
                "defmodule A do\n  raise KeyError\nend\ndefmodule(B, do: 1) = 1",
                "top unported `defmodule(B, do: 1)`\nmodule Elixir.A stopped `raise KeyError`",
            )
        }
    }

    /** `do_escape/3` lists an alias's segments, so an `unquote_splicing` among them is spliced, in a body or a head. */
    fun testASpliceInAnAliasSegmentIsSpliced() {
        assertFile("defmodule A do\n  xs = [Bar]\n  def f, do: unquote_splicing(xs).Baz\nend", "top expanded\nmodule Elixir.A compiled")
        assertFile("defmodule A do\n  xs = [Bar]\n  def f(unquote_splicing(xs).Baz), do: 1\nend", "top expanded\nmodule Elixir.A compiled")
    }

    /**
     * A splice as a call's callee escapes to a `{}` call `Kernel.def/2` rejects, which the port declines while the `def`
     * expands, before any statement of the body runs: the body's expansion ends there, so no statement before it ran
     * for certain, and a raise in one isn't the module's.
     */
    fun testAnEscapeThatDeclinesEndsTheBodysExpansion() {
        val def = "def f, do: unquote_splicing(xs)(1)"

        assertFile("defmodule A do\n  xs = [:foo]\n  def foo(x), do: x + 1\n  $def\nend", "top expanded\nmodule Elixir.A stopped `$def`")
        assertFile(
            "defmodule A do\n  _ = 1 + :a\n  xs = [:foo]\n  def foo(x), do: x + 1\n  $def\nend",
            "top unported `$def`\nmodule Elixir.A stopped `$def`",
        )

        // Code the port can't read after the splice doesn't hide it: the escape walks on to the splice. A string escaping
        // past the last code point is broken code on every release; `...` before an operand is left unlowered from v1.17.
        listOf(UNREAD, "...(2)").forEach { unread ->
            val block = "def f do\n    unquote_splicing(xs)(1)\n    $unread\n  end"

            assertFile("defmodule A do\n  xs = [:foo]\n  def foo(x), do: x + 1\n  $block\nend", "top expanded\nmodule Elixir.A stopped `$block`")
            assertFile(
                "defmodule A do\n  xs = [:foo]\n  _ = 1 + :a\n  $block\nend",
                "top unported `$block`\nmodule Elixir.A stopped `$block`",
            )
        }
    }

    /**
     * A macro-built definition whose head holds a value the evaluator doesn't know, then one Elixir can't escape: the
     * escape walks on past the unknown to the raise, which is the module's before any statement runs, so neither the
     * module nor the one nested before the definition is ever defined.
     */
    fun testAnUnknownInAMacroBuiltDefinitionDoesNotHideWhatElixirCantEscape() {
        listOf("__CALLER__.aliases" to "", "@a" to "  @a 1.5\n").forEach { (unknown, attribute) ->
            assertFile(
                "defmodule Mac do\n$attribute  defmacro m do\n    t = {$unknown, fn -> 1 end, $unknown, 0}\n" +
                    "    {:def, [context: Elixir, imports: [{1, Kernel}, {2, Kernel}]], [{:f, [], [t]}, [do: {:unquote, [], [1]}]]}\n  end\nend\n" +
                    "defmodule U do\n  defmodule I do\n    def g, do: 1\n  end\n  require Mac\n  Mac.m()\nend",
                "top expanded\nmodule Elixir.Mac compiled\nmodule Elixir.U raised Elixir.ArgumentError `Mac.m()`",
            )
        }
    }

    /**
     * A map's entries are escaped first to last but under `unquote: true`, where before v1.18.4 they are escaped last to
     * first, so a raise in an earlier entry is the escape's.
     */
    fun testAMapsEntriesAreEscapedInOrderWithoutUnquote() {
        val statement = "v =\n    try do\n      Macro.escape(%{a: {:x, [1 | 2], :y}, b: fn -> 1 end}, prune_metadata: true)\n" +
            "    rescue\n      ArgumentError -> :arg\n    end"

        assertFile(
            "defmodule A do\n  $statement\n\n  def unquote(v)(), do: 1\nend",
            "top expanded\nmodule Elixir.A raised Elixir.ErlangError `$statement`",
        )
    }

    /**
     * Under `unquote: true`, before [ESCAPED_MAP_ENTRIES_QUOTED_APART], a map's entries are escaped as one list, last to
     * first, so the raise is the last entry's, which the body rescues.
     */
    fun testAMapsEntriesAreEscapedLastFirstUnderUnquote() {
        val statement = "v =\n    try do\n      Macro.escape(%{a: {:x, [1 | 2], :y}, b: fn -> 1 end}, prune_metadata: true, unquote: true)\n" +
            "    rescue\n      ArgumentError -> :arg\n    end"

        assertFile(
            "defmodule A do\n  $statement\n\n  def unquote(v)(), do: 1\nend",
            if (ESCAPED_MAP_ENTRIES_QUOTED_APART.isSufficient(legLevel())) "top expanded\nmodule Elixir.A raised Elixir.ErlangError `$statement`"
            else "top expanded\nmodule Elixir.A compiled",
        )
    }

    /**
     * Code the port can't read may hold an `unquote`, whose expression Elixir expands with the body before any statement
     * runs. Whether Elixir runs the body isn't known, so no raise in it is the module's, wherever in a definition the code
     * is, and whether or not the definition has another `unquote`.
     */
    fun testAnUnreadDefinitionLeavesTheBodyUncertain() {
        // A string escaping past the last code point is broken code on every release; `...` before an operand is left
        // unlowered from v1.17, where it is an operator.
        val ellipsis = ELLIPSIS_NULLARY_CALL.isSufficient(legLevel())
        val definitions = listOfNotNull(
            "def f(unquote(:x), $UNREAD) do\n    y\n  end",
            "def f(unquote(:x)) do\n    unquote(:y)\n    $UNREAD\n  end",
            "def f(unquote(:x)) do\n    ...(unquote(y))\n  end".takeIf { ellipsis },
            "def f(unquote(:x)) do\n    ...(y)\n  end".takeIf { ellipsis },
            "def f do\n    $UNREAD\n  end",
            "def f do\n    ...(unquote(y))\n  end".takeIf { ellipsis },
            "def f do\n    ...(y)\n  end".takeIf { ellipsis },
        )

        definitions.forEach { definition ->
            assertFile(
                "defmodule A do\n  _ = 1 + :a\n  $definition\nend",
                "top unported `_ = 1 + :a`\nmodule Elixir.A stopped `_ = 1 + :a`",
            )
        }
    }

    /**
     * `Kernel.define/4` escapes a definition while the `def` expands, before any statement of the body runs, so what
     * the escape raises is the module's error ahead of a raise in an earlier statement. Before v1.15 the head is
     * escaped whether or not it has unquotes; from it, only a definition with unquotes is.
     */
    fun testAnEscapeThatRaisesIsTheModulesBeforeItsBodyRuns() {
        val together = DEFINITION_ESCAPES_HEAD_AND_BODY_TOGETHER.isSufficient(legLevel())

        listOf(
            "{:unquote, [], [:ok]}" to "raised Elixir.ArgumentError `Mac.m()`",
            "{:x, [], nil}" to if (together) "raised Elixir.ArithmeticError `_ = 1 + :a`" else "raised Elixir.ArgumentError `Mac.m()`",
        ).forEach { (body, u) ->
            assertFile(
                """
                defmodule Mac do
                  defmacro m do
                    head = {:g, [], [{:\\, [], [{:x, [], nil}, fn -> 1 end]}]}

                    {:def, [context: Elixir, imports: [{1, Kernel}, {2, Kernel}]], [head, [do: $body]]}
                  end
                end
                defmodule U do
                  require Mac
                  _ = 1 + :a
                  Mac.m()
                end
                """.trimIndent(),
                "top expanded\nmodule Elixir.Mac compiled\nmodule Elixir.U $u",
            )
        }
    }

    /**
     * `eval_form/7` stores the overridable definitions not overridden again after the `@before_compile` callbacks,
     * which may have defined one as the other kind.
     */
    fun testAnOverridableACallbackRedefinesAsAMacroRaises() =
        assertFile(
            """
            defmodule CB do
              defmacro __before_compile__(_env), do: quote(do: (defoverridable f: 0; defmacro f, do: 1))
            end
            defmodule A do @before_compile CB; def f, do: 1; defoverridable f: 0 end
            """.trimIndent(),
            "top expanded\nmodule Elixir.CB compiled\n" +
                "module Elixir.A raised bad_kind `defmodule A do @before_compile CB; def f, do: 1; defoverridable f: 0 end`",
        )

    /** Elixir raises for a write whose target or key isn't an atom, or whose receiver isn't known. */
    fun testAWriteElixirMayRejectStops() {
        listOf(
            "Module.put_attribute(1, :a, 1)",
            "Module.put_attribute(__MODULE__, \"a\", 1)",
        ).forEach { write ->
            assertFile("defmodule A do\n  $write\n  @b raise(KeyError)\nend", "top unported `$write`\nmodule Elixir.A stopped `$write`")
        }
        assertFile(
            "defmodule A do\n  mod = String\n  mod.put_attribute(__MODULE__, :a, 1)\n  @b raise(KeyError)\nend",
            "top unported `mod.put_attribute(__MODULE__, :a, 1)`\nmodule Elixir.A stopped `mod.put_attribute(__MODULE__, :a, 1)`",
        )
    }

    /**
     * A definition the body may not run may not be there for the module's checks: Elixir compiles this, and the
     * conflict the expander reports isn't the module's for certain.
     */
    fun testAPostModuleCheckAfterADefinitionTheBodyMayNotRunStops() =
        assertFile(
            "defmodule A do\n  if $RECV_FALSE do\n    def inspect(x), do: x\n  end\n  def f(x), do: inspect(x)\nend",
            "top expanded\nmodule Elixir.A stopped `def inspect(x), do: x`",
        )

    /** A module whose `defmodule` may not run may not be defined: Elixir raises `UndefinedFunctionError`. */
    fun testAModuleWhoseDefmoduleMayNotRunIsNotCalled() {
        assertFile(
            "case $RECV_B do\n  :a ->\n    defmodule A do\n      def f, do: raise(ArgumentError)\n    end\n  _ -> :ok\nend\nA.f()",
            "top expanded\nmodule Elixir.A compiled",
        )
        assertFile(
            "defmodule Outer do\n  fn ->\n    defmodule Inner do\n      def f, do: raise(ArgumentError)\n    end\n  end\n  Outer.Inner.f()\nend",
            "top expanded\nmodule Elixir.Outer compiled\n  module Elixir.Outer.Inner compiled",
        )
    }

    /** A raise in a module whose `defmodule` may not run may not have happened, so the modules after it compile. */
    fun testARaiseInAModuleWhoseDefmoduleMayNotRunDoesNotEndTheFile() =
        assertFile(
            "case $RECV_B do\n  :a ->\n    defmodule A do\n      @a raise(KeyError)\n    end\n  _ -> :ok\nend\ndefmodule C do\nend",
            "top expanded\nmodule Elixir.A stopped `@a raise(KeyError)`\nmodule Elixir.C compiled",
        )

    /** A struct whose `defmodule` may not run may not be defined. */
    fun testAStructWhoseDefmoduleMayNotRunIsNotRead() =
        assertFile(
            "defmodule S0 do\n  if $RECV_FALSE do\n    defmodule A do\n      defstruct [:a]\n    end\n  end\nend\ndefmodule B do\n  @a %S0.A{}\nend",
            "top expanded\nmodule Elixir.S0 compiled\n  module Elixir.S0.A compiled\nmodule Elixir.B stopped `%S0.A{}`",
        )

    /**
     * Before v1.15 `Kernel.define/4` escapes the head whether or not it has unquotes, so the escape rewrites a
     * `Left.unquote(name)` the head holds; from v1.15 a head with none reaches the compiler as it is.
     */
    fun testAHeadWithoutUnquotesIsEscaped() {
        val u =
            if (DEFINITION_ESCAPES_HEAD_AND_BODY_TOGETHER.isSufficient(legLevel())) "raised invalid_quoted_expr `Mac.m()`"
            else "compiled"

        assertFile(
            """
            defmodule Mac do
              defmacro m do
                arg = {:\\, [], [{:x, [], nil}, {{{:., [], [String, :unquote]}, [], [:upcase]}, [], nil}]}

                {:def, [context: Elixir, imports: [{1, Kernel}, {2, Kernel}]], [{:g, [], [arg]}, [do: {:x, [], nil}]]}
              end
            end
            defmodule U do
              require Mac
              Mac.m()
            end
            """.trimIndent(),
            "top expanded\nmodule Elixir.Mac compiled\nmodule Elixir.U $u",
        )
    }

    /**
     * From v1.15 the head and body are escaped as one pair, which `do_quote/2` quotes as `{:{}, ...}` around a splice,
     * whose value is checked as that call's argument: `list/2` raises for a value that isn't a list, and the store for
     * the call. Before it, the head is escaped alone, so a splice that is the whole head is a bodiless head of
     * `unquote_splicing/1`: a pattern argument raises, and a variable one is skipped before [GENERATED_HEADS_CHECKED].
     */
    fun testASplicingHeadRaisesWhereTheStoreDoes() {
        val together = DEFINITION_ESCAPES_HEAD_AND_BODY_TOGETHER.isSufficient(legLevel())
        val checksGenerated = GENERATED_HEADS_CHECKED.isSufficient(legLevel())

        listOf(
            Triple("def unquote_splicing(a, b), do: unquote(x)", null, "compiled"),
            Triple("def unquote_splicing(), do: unquote(x)", null, "compiled"),
            Triple("def unquote_splicing([x])", null, "raised invalid_function_head `def unquote_splicing([x])`"),
            Triple(
                "def unquote_splicing(x)",
                "unquote_splicing(x)",
                if (checksGenerated) "raised function_head `def unquote_splicing(x)`" else "compiled",
            ),
        ).forEach { (def, raisedAt, before) ->
            val ended = if (together) "raised Elixir.ArgumentError `${raisedAt ?: def}`" else before

            assertFile("defmodule B3 do\n  x = 1\n  $def\nend", "top expanded\nmodule Elixir.B3 $ended")
        }
    }

    /**
     * A body is escaped only where it has unquotes, or, from v1.15, where the head has. A dot-unquote call with no
     * arguments is no unquote, so a body holding one reaches the compiler as it is, which rejects it, unless the head's
     * unquotes have the pair escaped.
     */
    fun testABodyIsEscapedOnlyWithUnquotes() {
        val together = DEFINITION_ESCAPES_HEAD_AND_BODY_TOGETHER.isSufficient(legLevel())
        val raised = "raised invalid_quoted_expr `Mac.m()`"

        listOf(
            "{{:unquote, [], [:g]}, [], []}" to if (together) "compiled" else raised,
            "{:g, [], []}" to raised,
        ).forEach { (head, u) ->
            assertFile(
                """
                defmodule Mac do
                  defmacro m do
                    body = {{{:., [], [String, :unquote]}, [], [:upcase]}, [], nil}

                    {:def, [context: Elixir, imports: [{1, Kernel}, {2, Kernel}]], [$head, [do: body]]}
                  end
                end
                defmodule U do
                  require Mac
                  Mac.m()
                end
                """.trimIndent(),
                "top expanded\nmodule Elixir.Mac compiled\nmodule Elixir.U $u",
            )
        }
    }

    /**
     * Elixir runs an `@on_definition` callback as each definition is stored, and one that raises from certain inputs
     * raises there, so nothing after it runs.
     */
    fun testAnOnDefinitionCallbackThatRaisesEndsItsModuleAtTheDefinition() =
        assertFile(
            """
            defmodule CB do
              def on_def(_env, _kind, _name, _args, _guards, _body), do: Map.fetch!(%{}, :k)
            end
            defmodule M do
              @on_definition {CB, :on_def}
              def f, do: 1
              @a raise(ArgumentError)
            end
            """.trimIndent(),
            "top expanded\nmodule Elixir.CB compiled\nmodule Elixir.M raised Elixir.KeyError `def f, do: 1`",
        )

    /** A callback the evaluator declines may have raised, as Elixir's `ArgumentError` at `@a` can't show here. */
    fun testARaiseAfterADefinitionWhoseOnDefinitionCallbackDeclinesStops() =
        assertFile(
            """
            defmodule CB do
              def on_def(_env, _kind, _name, _args, _guards, _body), do: Process.get(:k)
            end
            defmodule M do
              @on_definition {CB, :on_def}
              def f, do: 1
              @a raise(ArgumentError)
            end
            """.trimIndent(),
            "top unported `@a raise(ArgumentError)`\nmodule Elixir.CB compiled\nmodule Elixir.M stopped `@a raise(ArgumentError)`",
        )

    /** A called function's `:elixir_def.take_definition/2` isn't followed, so its module stops at the body's call. */
    fun testACalledTakeDefinitionStopsItsModuleAtTheCall() =
        assertFile(
            """
            defmodule W do
              def take(m), do: :elixir_def.take_definition(m, {:f, 0})
            end
            defmodule U do
              def f, do: 1
              W.take(__MODULE__)
              def g, do: 1
            end
            """.trimIndent(),
            "top expanded\nmodule Elixir.W compiled\nmodule Elixir.U stopped `W.take(__MODULE__)`",
        )

    /** Elixir runs an `@after_compile` callback once the module compiles, and it may raise and end the file. */
    fun testARaiseAfterAModuleWithAnAfterCompileCallbackStops() =
        assertFile(
            """
            defmodule CB do
              def __after_compile__(_env, _bytecode), do: Map.fetch!(%{}, :k)
            end
            defmodule A do
              @after_compile CB
            end
            defmodule B do
              @a raise(ArgumentError)
            end
            """.trimIndent(),
            "top unported `@a raise(ArgumentError)`\nmodule Elixir.CB compiled\nmodule Elixir.A compiled\nmodule Elixir.B stopped `@a raise(ArgumentError)`",
        )

    /**
     * From 1.14 Elixir runs an `@after_verify` callback once the module is verified, which every compile entry point
     * does for the whole file at once, after every module has compiled, so a raise in a later module comes first and
     * is the file's. Before it, `@after_verify` is a plain attribute.
     */
    fun testARaiseAfterAModuleWithAnAfterVerifyCallbackIsTheFiles() =
        assertFile(
            """
            defmodule CB do
              def __after_verify__(_module), do: Map.fetch!(%{}, :k)
            end
            defmodule A do
              @after_verify CB
            end
            defmodule B do
              @a raise(ArgumentError)
            end
            """.trimIndent(),
            "top expanded\nmodule Elixir.CB compiled\nmodule Elixir.A compiled\nmodule Elixir.B raised Elixir.ArgumentError `raise(ArgumentError)`",
        )

    /** A `@before_compile` callback runs after the body, so after a decline its raise stops. */
    fun testABeforeCompileCallbackThatRaisesAfterADeclineStops() =
        assertFile(
            """
            defmodule CB do
              defmacro __before_compile__(_env), do: raise(KeyError)
            end
            defmodule A do
              @before_compile CB
              _ = receive do after 0 -> raise ArgumentError end
            end
            """.trimIndent(),
            "top unported `defmodule A do\n  @before_compile CB\n  _ = receive do after 0 -> raise ArgumentError end\nend`\nmodule Elixir.CB compiled\nmodule Elixir.A stopped `defmodule A do\n  @before_compile CB\n  _ = receive do after 0 -> raise ArgumentError end\nend`",
        )

    /** An overridable function defined again as a macro raises once the body has run, so after a decline it stops. */
    fun testAnOverridableRedefinedAsAMacroAfterADeclineStops() =
        assertFile(
            "defmodule A do\n  _ = receive do after 0 -> raise ArgumentError end\n  def f, do: 1\n  defoverridable f: 0\n  defmacro f, do: 1\nend",
            "top unported `defmacro f, do: 1`\nmodule Elixir.A stopped `defmacro f, do: 1`",
        )

    /** The errors these raise follow no statement the evaluator declines. */
    fun testRaisesWithNoDeclineBeforeThemAreTheModules() {
        assertFile(
            "defmodule A do\n  f = &IO.inspect(1, &1)\n  f.(1)\nend",
            "top expanded\nmodule Elixir.A raised Elixir.FunctionClauseError `f.(1)`",
        )
        assertFile(
            "defmodule A do\n  x = \"l\"\n  for e <- [1, 2], do: e + x\nend",
            "top expanded\nmodule Elixir.A raised Elixir.ArithmeticError `e + x`",
        )
    }

    /**
     * Whether a bang read of the application's environment finds its key isn't known, nor whether a path's value holds
     * its next key, nor whether `compile_env` is given an env, so each raise is assumed not to happen and the error after
     * it stops the module, or the file at its top level.
     */
    fun testAnErrorAfterAnAssumedApplicationReadStops() {
        val compileEnv =
            if (COMPILE_ENV_TAKES_ENV_FIRST.isSufficient(legLevel())) "Application.compile_env(env, :nope, :k, nil)"
            else "Application.__compile_env__(:nope, :k, nil, env)"
        val stopped = "top unported `:ok = :bad`\nmodule Elixir.A stopped `:ok = :bad`"

        assertFile("defmodule A do\n  @x Application.compile_env!(:nope, :k)\n  :ok = :bad\nend", stopped)
        assertFile("defmodule A do\n  @x Application.compile_env(:nope, [:k, :sub])\n  :ok = :bad\nend", stopped)
        assertFile("defmodule A do\n  _ = Application.fetch_env!(:nope, :k)\n  :ok = :bad\nend", stopped)
        assertFile("defmodule A do\n  env = Application.get_env(:nope, :env)\n  _ = $compileEnv\n  :ok = :bad\nend", stopped)
        assertFile("defmodule A do\n  read = fn k -> Application.fetch_env!(:nope, k) end\n  _ = read.(:k)\n  :ok = :bad\nend", stopped)
        assertFile("defmodule A do\n  read = &Application.fetch_env!(:nope, &1)\n  @a read.(:k)\n  :ok = :bad\nend", stopped)
        assertFile("_ = Application.fetch_env!(:nope, :k)\n:ok = :bad", "top unported `:ok = :bad`")
        assertFile("defmodule A do\n  _ = {Application.fetch_env!(:nope, :k), :ok = :bad}\nend", stopped)
    }

    /** The reads that raise only on a guard raise nothing here, so the error after them is the module's. */
    fun testAnErrorAfterAnApplicationReadIsTheModules() =
        assertFile(
            "defmodule A do\n  @x Application.compile_env(:nope, :k)\n  _ = Application.fetch_env(:nope, :k)\n  :ok = :bad\nend",
            "top expanded\nmodule Elixir.A raised Elixir.MatchError `:ok = :bad`",
        )

    /**
     * An `EEx` function as an implementation's last expression stops it at `EEx`'s `defp` branch from v1.14, a known
     * divergence: `Protocol.__impl__` puts the implementation's body on the right of `res = unquote(block)`, and a
     * declined call in a module-body `=`'s block runs the units after it unselected, so both branches define
     * `to_string/1`, where Elixir defines it once. Before v1.14 the body is a statement of its own, and is defined.
     */
    fun testAnEexFunctionEndingAnImplementationStopsAtItsDefpBranch() {
        val code = "$STRUCT_S\n\ndefimpl String.Chars, for: S do\n  require EEx\n  $TO_STRING\nend"
        val level = legLevel()
        val result = DEFIMPL_IMPL4.isSufficient(level)

        assertFile(
            code,
            "top expanded\nmodule Elixir.S compiled [__struct__/0 def, __struct__/1 def]\n" +
                "module Elixir.String.Chars.S ${if (result) "stopped `${code.substringAfter("\n\n")}`" else "compiled"} " +
                "[__impl__/1 def, to_string/1 def]",
            tables = true,
        )

        if (!result) return

        val implementation = Expander
            .expandFile(lowered(code, level), Env.empty(level, legKernel), level, sdkExports, legStructs, legAppEnv(), clauses = sdk)
            .modules
            .single { it.module == "Elixir.String.Chars.S" }

        assertEquals(
            ExpansionResult.Owner.Definition(DefinitionTable.Kind.DEFP, "to_string", 1),
            implementation.units.single { it.expansion is Expansion.Unported }.owner,
        )
    }

    /** An `EEx` function followed by another expression in an implementation is defined there, as Elixir defines it. */
    fun testAnEexFunctionBeforeAnImplementationsLastExpressionIsDefined() =
        assertFile(
            "$STRUCT_S\n\ndefimpl String.Chars, for: S do\n  require EEx\n  $TO_STRING\n  :ok\nend",
            "top expanded\nmodule Elixir.S compiled [__struct__/0 def, __struct__/1 def]\n" +
                "module Elixir.String.Chars.S compiled [__impl__/1 def, to_string/1 def]",
            tables = true,
        )

    /** An unknown body drops every error Elixir raises from it: here `File.Error`, as the template is missing. */
    fun testAnUnknownBodyDropsAMissingFile() =
        assertFile(
            "defmodule A do\n  require EEx\n  EEx.function_from_file(:def, :t, \"/nonexistent/t.eex\", [:assigns])\nend",
            "top expanded\nmodule Elixir.A compiled [t/1 def]",
            tables = true,
        )

    /** An unknown body drops every error Elixir raises from it: here `TokenMissingError`. */
    fun testAnUnknownBodyDropsABadTemplate() =
        assertFile(
            "defmodule A do\n  require EEx\n  EEx.function_from_string(:def, :w, \"<%= ( %>\")\n  def after_it, do: 1\nend",
            "top expanded\nmodule Elixir.A compiled [after_it/0 def, w/0 def]",
            tables = true,
        )

    /** An unknown body drops every error Elixir raises from it: here `CompileError`, for an undefined function. */
    fun testAnUnknownBodyDropsAnUndefinedLocal() =
        assertFile(
            "defmodule A do\n  require EEx\n  EEx.function_from_string(:def, :w, \"<%= helper() %>\")\nend",
            "top expanded\nmodule Elixir.A compiled [w/0 def]",
            tables = true,
        )

    /** An unknown body drops every error Elixir raises from it: here `CompileError`, for an undefined variable. */
    fun testAnUnknownBodyDropsAnUndefinedVariable() =
        assertFile(
            "defmodule A do\n  require EEx\n  EEx.function_from_string(:def, :v, \"<%= @name %>\", [])\nend",
            "top expanded\nmodule Elixir.A compiled [v/0 def]",
            tables = true,
        )

    /**
     * An unknown body drops every error Elixir raises from it: here `CompileError`, for an undefined function beside the
     * unknown fragment.
     */
    fun testAnUnknownBodyDropsAnUndefinedLocalBesideItsFragment() =
        assertFile(
            "defmodule A do\n  x = Application.get_env(:m2, :x, :ok)\n  def f, do: (nope(); unquote(x))\nend",
            "top expanded\nmodule Elixir.A compiled [f/0 def]",
            tables = true,
        )

    /**
     * [code], a file expanded at the leg's level with the leg's clauses, as [expected] shows its top and each module's end,
     * and with [tables] each module's definition table after it.
     */
    private fun assertFile(code: String, expected: String, tables: Boolean = false, run: RunInputs = RunInputs()) =
        assertEquals(expected, file(code, tables, run))

    /** The file, its compile directories and the OS its SDK runs as, where a test names them. */
    private class RunInputs(val os: OS? = null, val path: String? = null, val directories: CompileDirectories = CompileDirectories.UNKNOWN)

    /** How [code]'s file expanded and each module it compiled ended, one per line, with [tables] each one's table. */
    private fun file(code: String, tables: Boolean = false, run: RunInputs = RunInputs()): String {
        val level = legLevel()
        val file = Expander.expandFile(
            lowered(code, level),
            Env.empty(level, legKernel),
            level,
            sdkExports,
            legStructs,
            legAppEnv(),
            clauses = sdk,
            file = { run.path },
            directories = run.directories,
            os = run.os,
        )

        fun source(node: ElixirAst) = node.meta.origin.substring(code)

        fun ended(result: ExpansionResult, indent: String): List<String> {
            val ended = when (val ended = result.ended) {
                Ended.Compiled -> "compiled"
                Ended.Tainted -> "tainted"
                is Ended.Raised -> "raised ${ended.error.kind} `${source(ended.error.at)}`"
                is Ended.Crashed -> "crashed ${ended.exception}"
                is Ended.Stopped -> "stopped `${source(ended.at)}`"
            }

            val table = if (tables) " ${table(result)}" else ""

            return listOf("${indent}module ${result.module} $ended$table") + result.nested.flatMap { ended(it, "$indent  ") }
        }

        val top = when (val top = file.top) {
            is Expansion.Expanded -> "expanded"
            is Expansion.Error -> "error ${top.kind} `${source(top.at)}`"
            is Expansion.Unported -> "unported `${source(top.at)}`"
            is Expansion.Opaque -> "opaque `${source(top.at)}`"
        }

        return (listOf("top $top") + file.modules.flatMap { ended(it, "") }).joinToString("\n")
    }

    /**
     * Each row that runs from [source] on the leg: its output, how its case module ended and its definition table,
     * against Elixir's, then its probes.
     */
    private fun assertRows(source: Source) {
        val level = legLevel()
        val rows = ROWS.filter { it.runsFrom(source) && it.legs(level) }
        val token = harness.token()
        val oracle = oracle(rows, token)
        val copies = if (source == Source.DBGI) copies(oracle.binaries) else emptyMap()
        val outputs = outputs(rows, token, source, copies)
        val exports = Exports { module -> copies[module]?.exports ?: sdkExports.of(module) }
        val hook = ProbeHarness.Hook()
        val clauses = ModuleClauses.Lookup { module -> copies[module]?.clauses ?: sdk.of(module) }
        val tokens = mutableListOf(token)
        val ownEnds = mutableMapOf<Int, String>()
        // A raise ends its file, so each row has a file of its own, with only its own modules: Elixir takes longer
        // over each compile the more modules it has compiled and unloaded, and every row's would pass the timeout.
        val expansions = rows.mapIndexed { index, row ->
            val expansion = probes.expandAll(
                listOf(row.body(index, if (source == Source.DBGI) token else TOKEN)),
                if (source == Source.SOURCE) preamble(rows, TOKEN, only = listOf(index)) else "",
                exports,
                hook = hook,
                clauses = clauses,
                file = FILE,
                directories = DIRECTORIES,
                os = OS.CURRENT,
            )

            tokens += expansion.layout.token
            if (source == Source.SOURCE && row.ownEnd != null) {
                val module = "Elixir.${module(index, expansion.layout.token)}"

                ownEnds[index] = expansion.modules.single { it.module == module }.let(::ownEnd)
            }
            expansion.cases.single()
        }
        val expected = mutableListOf<String>()
        val actual = mutableListOf<String>()

        rows.forEachIndexed { index, row ->
            val pinned = row.pinned(source, level)
            val answer = oracle.answers.getValue(index)

            expected += "${row.id} output: " + when (pinned) {
                null -> rendered(answer, tokens, level, row.unordered)
                UNPORTED -> "unported at `${row.call(index, TOKEN)}`"
                else -> pinned
            }
            actual += "${row.id} output: ${outputs[index]}"
            expected += "${row.id} ended: " + when {
                pinned == UNPORTED -> "stopped at `require ${module(index, TOKEN)}`"
                pinned == OPAQUE || pinned == null && answer.isCaught() -> "stopped at `${row.stopText(index, TOKEN)}`"
                pinned == null && answer.raised() != null -> "raised ${answer.raised()}"
                row.ends(level) != null -> row.ends(level)?.replace(ROW, module(index, TOKEN))
                row.stopsAfter != null -> "stopped at ${row.stopsAfter}"
                else -> "compiled"
            }
            actual += "${row.id} ended: ${ended(expansions[index], row, tokens)}"
            ownEnds[index]?.let {
                expected += "${row.id} own module: ${row.ownEnd}"
                actual += "${row.id} own module: $it"
            }
            row.table?.let {
                expected += "${row.id} expander's table: $it"
                actual += "${row.id} expander's table: ${table(expansions[index].result)}"
            }
        }

        tables(rows, source, expansions, expected, actual)

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))

        probes.assertMatchesElixir(
            rows.indices
                .filter { expansions[it].ended.let { ended -> ended == Ended.Compiled || ended.raises } }
                .associate { rows[it].id to expansions[it] }
        )
    }

    /** Each compiled row's definition table against what the hook saw of its module, compiled [TABLES_BATCH] together. */
    private fun tables(
        rows: List<Row>,
        source: Source,
        expansions: List<ExpansionProbes.CaseExpansion>,
        expected: MutableList<String>,
        actual: MutableList<String>,
    ) {
        for (batch in rows.indices.filter { expansions[it].ended == Ended.Compiled }.chunked(TABLES_BATCH)) {
            val origin = expansions[batch.first()].origin
            val together = probes.expandAll(
                batch.map { expansions[it].origin.body },
                if (source == Source.SOURCE) preamble(rows, TOKEN, only = batch) else "",
                origin.exports,
                hook = origin.hook,
                clauses = origin.clauses,
                file = origin.file,
                directories = origin.directories,
                os = origin.os,
            )
            val attempt = harness.attempt(together.layout)

            batch.forEachIndexed { index, row ->
                val theirs = attempt.batch.hooks[together.layout.caseModule(index)]
                    ?.map { "${it.name}/${it.arity} ${it.kind}" }
                    ?.sorted()
                expected += "${rows[row].id} table: ${theirs ?: "the hook didn't run"}"
                actual += "${rows[row].id} table: ${table(together.cases[index].result)}"
            }
        }
    }

    /** [result]'s definition table, each entry as its name, arity and kind. */
    private fun table(result: ExpansionResult): List<String> =
        result.table.entries
            .map { (nameArity, entry) -> "${nameArity.name}/${nameArity.arity} ${entry.kind.name.lowercase()}" }
            .sorted()

    /** How a row's own module ended: a stop by its kind and the dispatch it stopped at. */
    private fun ownEnd(result: ExpansionResult): String =
        when (val ended = result.ended) {
            is Ended.Stopped -> {
                val opaque = result.opaque.singleOrNull { it.at === ended.at }

                if (opaque != null) "opaque at ${opaque.dispatch.receiver}.${opaque.dispatch.name}" else "stopped"
            }
            else -> ended.toString()
        }

    /** How [expansion]'s case module ended, a stop in the case body by its text. */
    private fun ended(expansion: ExpansionProbes.CaseExpansion, row: Row, tokens: List<String>): String =
        when (val ended = expansion.ended) {
            Ended.Compiled -> "compiled"
            is Ended.Raised -> "raised ${ended.error.kind}"
            is Ended.Crashed -> "crashed ${ended.exception}"
            is Ended.Stopped -> {
                // A callback's call, which no source holds, has an origin before the case body.
                val start = expansion.origin.layout.bodyStarts[expansion.origin.index]
                val range = ended.at.takeIf { it.meta.origin.startOffset >= start }?.let(expansion::range)
                val body = expansion.case.body
                val at = rendered(ended.at.toOtp(), tokens, legLevel(), row.unordered)
                val after = row.stopsAfter?.takeIf { at.startsWith(it) }

                when {
                    // A node of the macro's output has the call's origin.
                    after != null -> "stopped at $after"
                    range != null && range.endOffset <= body.length ->
                        "stopped at `${detokened(range.substring(body), tokens)}`"
                    else -> "stopped at $at"
                }
            }
            Ended.Tainted -> "tainted"
        }

    /**
     * Each of [rows]' calls expanded once as `Macro.expand_once/2` expands it, in a `{token}.Case` that requires the
     * row's modules, with those modules as [source] holds them: compiled from the preamble, each row's in a file of its
     * own, as [assertRows] compiles them, or [copies].
     */
    private fun outputs(rows: List<Row>, token: String, source: Source, copies: Map<String, Copy>): List<String> {
        val level = legLevel()
        val loaded = HashMap<String, ModuleExports>()
        val sourceClauses = HashMap<String, ModuleClauses>()

        if (source == Source.SOURCE) {
            val observer = object : ExpansionObserver {
                override fun entering(node: ElixirAst, state: ExState, env: Env) {}

                override fun loaded(result: ExpansionResult, exports: ModuleExports, struct: ModuleStruct) {
                    loaded[result.module] = exports
                }

                override fun clausesLoaded(module: String, clauses: ModuleClauses?) {
                    if (clauses == null) sourceClauses.remove(module) else sourceClauses[module] = clauses
                }
            }

            for (index in rows.indices) {
                val file = preamble(rows, token, only = listOf(index))

                if (file.isEmpty()) continue

                Expander.expandFile(
                    lowered(file, level),
                    Env.empty(level, legKernel),
                    level,
                    sdkExports,
                    legStructs,
                    legAppEnv(),
                    observer,
                    file = { FILE },
                    directories = DIRECTORIES,
                    clauses = sdk,
                    os = OS.CURRENT,
                )
            }
        }

        val exports = Exports { module -> loaded[module] ?: copies[module]?.exports ?: sdkExports.of(module) }
        val clauses = ModuleClauses.Lookup { module ->
            sourceClauses[module] ?: copies[module]?.clauses ?: sdk.of(module)
        }
        val module = "Elixir.$token.Case"
        val empty = Env.empty(level, legKernel)

        return rows.mapIndexed { index, row ->
            val env = empty.copy(
                requires = (empty.requires + row.requires(index, token).map { "Elixir.$it" }).distinct().sorted(),
                contextModules = listOf(module),
                module = module,
            )
            val run = Run(
                level,
                ExpansionObserver.NONE,
                exports,
                legStructs,
                legAppEnv(),
                file = { FILE },
                directories = DIRECTORIES,
                clauses = clauses,
                os = OS.CURRENT,
            )
            val call = lowered(row.call(index, token), level)

            when (val expanded = macroExpandOnce(call, ExState.empty(level), env, run)) {
                is MacroExpanded.Node ->
                    if (expanded.expanded) rendered(expanded.node.toOtp(), listOf(token), level, row.unordered) else "unexpanded"
                MacroExpanded.Dir -> "dir"
                is MacroExpanded.Stopped -> when (val stop = expanded.expansion) {
                    is Expansion.Opaque -> OPAQUE
                    is Expansion.Error ->
                        inspect(OtpErlangTuple(arrayOf(OtpErlangAtom("raise"), OtpErlangAtom(stop.kind))))
                    is Expansion.Unported ->
                        if (stop.at === call) {
                            "unported at `${detokened(row.call(index, token), listOf(token))}`"
                        } else {
                            "unported " + rendered(stop.at.toOtp(), listOf(token), level, row.unordered)
                        }
                    is Expansion.Expanded -> "expanded"
                }
            }
        }
    }

    private fun lowered(text: String, level: ElixirLanguageLevel): ElixirAst {
        val file = parse(text)

        return ReadAction.computeBlocking<ElixirAst, Throwable> { Lowering.lower(file, level) }
    }

    /**
     * Elixir's `Macro.expand_once/2` of each of [rows]' calls, in `{token}.Case`, once the rows' modules are compiled
     * with debug info and loaded, and each of those modules' binary, compiled [ORACLE_BATCH] rows together.
     */
    private fun oracle(rows: List<Row>, token: String): Oracle {
        val answers = HashMap<Int, OtpErlangObject>()
        val binaries = HashMap<String, ByteArray>()

        for (batch in rows.indices.chunked(ORACLE_BATCH)) {
            val requires = batch.flatMap { rows[it].requires(it, token) }.distinct()
            val expansions = batch.map { index ->
                """
                IntellijElixir.Quoter.Probe.send(__ENV__, {:row, $index, try do
                  Macro.expand_once(quote(do: ${rows[index].call(index, token)}), __ENV__)
                rescue
                  e -> {:raise, e.__struct__}
                catch
                  kind, _ -> {:caught, kind}
                end})
                """.trimIndent()
            }
            // The quoter unloads `{token}.Case` after each compile, so the next batch defines it again.
            val preamble = elixirString(preamble(rows, token, only = batch))
            val compiled = Quoter.compile(
                "for {module, binary} <- Code.compile_string($preamble) do\n" +
                    "  IntellijElixir.Quoter.Probe.send(__ENV__, {:module, module, binary})\n" +
                    "end\n" +
                    "defmodule $token.Case do\n" +
                    requires.joinToString("") { "require $it\n" } +
                    expansions.joinToString("\n") +
                    "\nend\n",
                COMPILE_TIMEOUT,
            )

            val raised = (compiled.status as? OtpErlangTuple)?.elements()?.lastOrNull() as? OtpErlangBinary

            assertEquals(
                "oracle: ${raised?.binaryValue()?.decodeToString()} ${compiled.diagnostics.map(::inspect)}",
                OtpErlangAtom("ok"),
                compiled.status,
            )

            for (message in compiled.messages) {
                val elements = (message as OtpErlangTuple).elements()

                if (elements[0] == OtpErlangAtom("row")) {
                    answers[(elements[1] as OtpErlangLong).intValue()] = elements[2]
                } else {
                    binaries[(elements[1] as OtpErlangAtom).atomValue()] =
                        (elements[2] as OtpErlangBinary).binaryValue()
                }
            }
        }

        return Oracle(answers, binaries)
    }

    /** Each of [binaries] written as a `.beam`, and read as its clauses and its exports. */
    private fun copies(binaries: Map<String, ByteArray>): Map<String, Copy> {
        val directory = FileUtil.createTempDirectory("interpreter", null)

        VfsRootAccess.allowRootAccess(testRootDisposable, directory.path)

        return binaries.mapValues { (module, binary) ->
            val beam = File(directory, "$module.beam").apply { writeBytes(binary) }

            Copy(
                clausesOf(project, beam) ?: throw AssertionError("$module's clauses aren't read"),
                LoadedModules.exports(binary, beam.path),
            )
        }
    }

    private enum class Source { SOURCE, DBGI }

    /** How a row runs from source. */
    private enum class SourceRun {
        ANSWERS,

        /** Its macro is the leg's, which only a `.beam` holds. */
        NONE,
    }

    private class Oracle(val answers: Map<Int, OtpErlangObject>, val binaries: Map<String, ByteArray>)

    private class Copy(val clauses: ModuleClauses, val exports: ModuleExports)

    /**
     * @property defs the body of the row's module, `{row}`; empty where its macro is the leg's
     * @property call the call `Macro.expand_once/2` expands
     * @property body the case body
     * @property stop where [body] stops when the call is declined: the call's text, by default
     * @property requires the modules the call's env requires
     * @property pins the output the evaluator gives where it isn't Elixir's, from a source on a leg: [OPAQUE] where it
     *   declines by one of its rules, [UNPORTED] where the call's module isn't loaded
     * @property stopsAfter where the case module stops after the call, at a gap outside the evaluator, as the start of
     *   the node rendered
     * @property unordered whether the output holds an escaped map, whose pairs Elixir lists in an order the VM keeps
     * @property ownEnd how the row's own module ends from source, where it doesn't compile
     * @property helper a module defined before the row's, for the row's module to require or import
     * @property ends how the case module ends on a leg, where it doesn't compile though `Macro.expand_once/2` returns
     * @property table the definitions the expander stores for the case module, for a row whose module raises
     */
    private class Row(
        val id: String,
        val defs: String,
        private val call: String,
        private val body: String,
        private val stop: String,
        private val requires: List<String>,
        private val source: SourceRun,
        val legs: (ElixirLanguageLevel) -> Boolean,
        private val pins: (Source, ElixirLanguageLevel) -> String?,
        val stopsAfter: String?,
        val unordered: Boolean = false,
        val ownEnd: String? = null,
        val helper: String = "",
        val ends: (ElixirLanguageLevel) -> String? = { null },
        val table: String? = null,
    ) {
        fun runsFrom(source: Source) = source == Source.DBGI || this.source != SourceRun.NONE

        fun pinned(source: Source, level: ElixirLanguageLevel): String? =
            pins(source, level)

        fun call(index: Int, token: String) = named(call, index, token)

        fun body(index: Int, token: String) = named(body, index, token)

        fun stopText(index: Int, token: String) = named(stop, index, token)

        fun requires(index: Int, token: String) = requires.map { named(it, index, token) }

        private fun named(text: String, index: Int, token: String) = text.replace(ROW, module(index, token))
    }

    /**
     * [term] with every node's metadata emptied, as Elixir's `inspect` prints it, each of [tokens] as `{token}`. Where
     * [ESCAPED_MAP_IN_VM_ORDER] holds and [unordered], as for a row that escapes a map, a map's pairs are sorted, as
     * Elixir's order is the atoms' index in the VM.
     */
    private fun rendered(term: OtpErlangObject, tokens: List<String>, level: ElixirLanguageLevel, unordered: Boolean): String =
        detokened(
            inspect(withoutMeta(term).let { if (unordered && ESCAPED_MAP_IN_VM_ORDER.isSufficient(level)) pairsSorted(it) else it }),
            tokens,
        )

    private fun pairsSorted(term: OtpErlangObject): OtpErlangObject =
        when (term) {
            is OtpErlangTuple -> {
                val elements = term.elements().map(::pairsSorted)
                val pairs = elements.getOrNull(2) as? OtpErlangList

                if (term.arity() == 3 && elements[0] == OtpErlangAtom("%{}") && pairs != null && pairs.lastTail == null) {
                    val sorted = pairs.elements().sortedBy { pair -> inspect((pair as? OtpErlangTuple)?.elementAt(0) ?: pair) }

                    OtpErlangTuple(arrayOf(elements[0], elements[1], OtpErlangList(sorted.toTypedArray())))
                } else {
                    OtpErlangTuple(elements.toTypedArray())
                }
            }
            is OtpErlangList -> {
                val elements = term.elements().map(::pairsSorted).toTypedArray()

                term.lastTail?.let { OtpErlangList(elements, pairsSorted(it)) } ?: OtpErlangList(elements)
            }
            else -> term
        }

    private fun detokened(text: String, tokens: List<String>): String =
        tokens.fold(text) { acc, token -> acc.replace(token, TOKEN) }

    private fun withoutMeta(term: OtpErlangObject): OtpErlangObject =
        when (term) {
            is OtpErlangTuple ->
                if (term.arity() == 3 && term.elementAt(1) is OtpErlangList) {
                    OtpErlangTuple(arrayOf(withoutMeta(term.elementAt(0)), OtpErlangList(), withoutMeta(term.elementAt(2))))
                } else {
                    OtpErlangTuple(term.elements().map(::withoutMeta).toTypedArray())
                }
            is OtpErlangList -> {
                val elements = term.elements().map(::withoutMeta).toTypedArray()

                term.lastTail?.let { OtpErlangList(elements, withoutMeta(it)) } ?: OtpErlangList(elements)
            }
            // As `elixir_quote:fun_to_quoted/1` gives it, which expanding a macro's result does.
            is OtpErlangExternalFun -> {
                val receiver = OtpErlangList(arrayOf(OtpErlangAtom(term.module), OtpErlangAtom(term.function)))
                val dot = OtpErlangTuple(arrayOf(OtpErlangAtom("."), OtpErlangList(), receiver))
                val callee = OtpErlangTuple(arrayOf(dot, OtpErlangList(), OtpErlangList()))
                val arguments = OtpErlangList(arrayOf(callee, OtpErlangLong(term.arity.toLong())))
                val slash = OtpErlangTuple(arrayOf(OtpErlangAtom("/"), OtpErlangList(), arguments))

                OtpErlangTuple(arrayOf(OtpErlangAtom("&"), OtpErlangList(), OtpErlangList(arrayOf(slash))))
            }
            is OtpErlangFun -> OtpErlangAtom("#Function")
            // A process or reference is one of the VM's own.
            is OtpErlangPid -> OtpErlangAtom("#PID")
            is OtpErlangRef -> OtpErlangAtom("#Reference")
            else -> term
        }

    private fun OtpErlangObject.isCaught(): Boolean =
        (this as? OtpErlangTuple)?.elementAt(0) == OtpErlangAtom("caught")

    /** The module of the exception Elixir's expansion raised, as atom text. */
    private fun OtpErlangObject.raised(): String? =
        (this as? OtpErlangTuple)
            ?.takeIf { it.arity() == 2 && it.elementAt(0) == OtpErlangAtom("raise") }
            ?.let { (it.elementAt(1) as OtpErlangAtom).atomValue() }

    private companion object {
        /** A subject the evaluator can't produce, so the branch it selects isn't known. */
        const val RECV_FALSE = "(receive do after 0 -> false end)"

        /** The name of a function made from the directory its compile runs in. */
        const val CWD_NAME = "unquote(:erlang.binary_to_atom(:unicode.characters_to_binary(cwd), :utf8))"

        /** A module that names a function by the directory its compile runs in. */
        const val CWD_MODULE = "defmodule B do\n  {:ok, cwd} = :file.get_cwd()\n  def $CWD_NAME(), do: 1\nend"
        const val RECV_B = "(receive do after 0 -> :b end)"

        const val TOKEN = "{token}"

        /** Broken code on every release, which the lowering leaves as a placeholder the port can't read. */
        const val UNREAD = "\"\\u{110000}\""

        /** The escape of U+0301, a combining acute accent: two bytes of UTF-8, which extend the cluster before them. */
        const val ACUTE = "\\u0301"
        const val ROW = "{row}"
        const val OPAQUE = "opaque"
        const val UNPORTED = "unported"
        const val FILE = "probe.ex"
        const val OS_TYPE = "defmodule A do\n  {family, name} = :os.type()\n  def unquote(family)(), do: 1\n  def unquote(name)(), do: 1\nend"
        const val STRUCT_S = "defmodule S do\n  defstruct []\nend"
        const val TO_STRING = "EEx.function_from_string(:def, :to_string, \"<%= t %>\", [:t])"

        /** A template's absolute path: the quoter's node has no working directory a relative one is read from. */
        val TEMPLATE: String = FileUtil.toSystemIndependentName(File("testData/org/elixir_lang/expander/eex/t.eex").absolutePath)
        val DIRECTORIES = CompileDirectories { "/" }
        val COMPILE_TIMEOUT = 60.seconds

        /**
         * The rows compiled together for their tables: on 1.13.4, 230 rows from source compiled in 15 s on a
         * workstation and in over 40 s on CI, past the quoter's bound.
         */
        const val TABLES_BATCH = 40

        /**
         * The rows the oracle compiles together: on 1.13.4, 437 rows took 29 s in one compile on a workstation and over
         * 60 s on CI, past the quoter's bound, and under 3 s for each 100 on a workstation.
         */
        const val ORACLE_BATCH = 100
        val EVERY_LEG: (ElixirLanguageLevel) -> Boolean = { true }
        val UNPINNED: (Source, ElixirLanguageLevel) -> String? = { _, _ -> null }
        val DECLINED: (Source, ElixirLanguageLevel) -> String? = { _, _ -> OPAQUE }

        /** From source the expander declines where [OPAQUE] says; `Dbgi` holds what Elixir compiled. */
        val DECLINED_FROM_SOURCE: (Source, ElixirLanguageLevel) -> String? = { source, _ ->
            OPAQUE.takeIf { source == Source.SOURCE }
        }

        /**
         * [DECLINED_FROM_SOURCE] for a row whose module calls a function of a helper module that stopped. Before
         * [UNREQUIRED_MACRO_CALLED_AS_FUNCTION] that call needs the stopped module's macros, so from source the row's
         * module stops at it ([UNPORTED]).
         */
        val CALLS_A_STOPPED_MODULE: (Source, ElixirLanguageLevel) -> String? = { source, level ->
            DECLINED_FROM_SOURCE(source, level)?.let { if (UNREQUIRED_MACRO_CALLED_AS_FUNCTION.isSufficient(level)) it else UNPORTED }
        }

        /**
         * A `%module{}` that doesn't name every field stops its module where the leg's debug info doesn't record which
         * fields are enforced, as on 1.18.
         */
        fun buildsPartOf(module: String): (Source, ElixirLanguageLevel) -> String? = { source, _ ->
            UNPORTED.takeIf { source == Source.SOURCE && (legStructs.of(module) as? ModuleStruct.Present)?.enforced == Enforced.Unknown }
        }

        /** A macro body reading `key` of `1`, `nil` and `true`, each exception rescued. */
        val FIELD_ON_NON_MAPS = """
            defmacro m do
              for x <- [1, nil, true] do
                try do
                  x.key
                rescue
                  e -> e.__struct__
                end
              end
            end
        """.trimIndent()

        /** The OTP 25 `lists` functions check their fun first, so below it an error is the VM's to say. */
        val LISTS_ERROR_BELOW_OTP_25: (Source, ElixirLanguageLevel) -> String? = { _, level ->
            OPAQUE.takeUnless { LISTS_CHECK_THE_FUN_FIRST.isSufficient(level) }
        }

        /**
         * Before `lists:sort/1`'s stability fix, the order it gives elements that compare equal, and the clause it raises
         * in for an improper list, are its old split's.
         */
        val SORT_BEFORE_ITS_STABILITY_FIX: (Source, ElixirLanguageLevel) -> String? = { _, level ->
            OPAQUE.takeUnless { LISTS_SORT_KEEPS_EQUAL_ORDER.isSufficient(level) }
        }

        /**
         * Below OTP 27, which of two keys `=:=` can't tell apart a map keeps depends on how the VM holds the map and on
         * what the compiler folded.
         */
        val KEY_KEPT_BELOW_OTP_27: (Source, ElixirLanguageLevel) -> String? = { _, level ->
            OPAQUE.takeUnless { NEGATIVE_ZERO_IS_DISTINCT.isSufficient(level) }
        }

        /** Below OTP 25, a fun with no clause matching raises as the compiler inlined it. */
        val FN_CLAUSE_BELOW_OTP_25: (Source, ElixirLanguageLevel) -> String? = { _, level ->
            OPAQUE.takeUnless { FN_CLAUSE_ERROR_IS_FUNCTION_CLAUSE.isSufficient(level) }
        }

        /** The test's `.beam`s are compiled in the quoter VM by the leg's Elixir, which holds no `Elixir.` prefix. */
        fun module(index: Int, token: String) = "$token.R$index"

        /** Each row's helper and module, in one file, or only those of the rows at [only]. */
        fun preamble(rows: List<Row>, token: String, only: List<Int>? = null): String =
            rows.withIndex().filter { (index, row) -> row.defs.isNotEmpty() && (only == null || index in only) }
                .joinToString("\n") { (index, row) ->
                    val module = module(index, token)

                    (if (row.helper.isEmpty()) "" else "${row.helper.replace(ROW, module)}\n") +
                        "defmodule $module do\n${row.defs.replace(ROW, module)}\nend"
                }

        /** A row whose case body binds the output of [call], a call of the row's module's. */
        fun macro(
            id: String,
            defs: String,
            call: String = "m()",
            source: SourceRun = SourceRun.ANSWERS,
            legs: (ElixirLanguageLevel) -> Boolean = EVERY_LEG,
            pins: (Source, ElixirLanguageLevel) -> String? = UNPINNED,
            stop: String = "$ROW.$call",
            unordered: Boolean = false,
            ownEnd: String? = null,
            helper: String = "",
            ends: (ElixirLanguageLevel) -> String? = { null },
        ) = Row(
            id,
            defs.trimIndent(),
            "$ROW.$call",
            "require $ROW\n_ = $ROW.$call",
            stop,
            listOf(ROW),
            source,
            legs,
            pins,
            null,
            unordered,
            ownEnd,
            helper.trimIndent(),
            ends,
        )

        /** A row whose case body `use`s the row's module with [options]. */
        fun use(
            id: String,
            defs: String,
            options: String = "",
            source: SourceRun = SourceRun.ANSWERS,
            stopsAfter: String? = null,
            legs: (ElixirLanguageLevel) -> Boolean = EVERY_LEG,
            ends: (ElixirLanguageLevel) -> String? = { null },
        ): Row {
            val use = "use $ROW" + if (options.isEmpty()) "" else ", $options"
            val call = "$ROW.__using__(${options.ifEmpty { "[]" }})"

            return Row(id, defs.trimIndent(), call, use, use, listOf(ROW), source, legs, UNPINNED, stopsAfter, ends = ends)
        }

        /** A row whose `use` runs [statements] in the case module once it requires `EEx`. */
        fun eex(id: String, statements: String, ends: (ElixirLanguageLevel) -> String? = { null }): Row =
            use(id, using("quote do", "require EEx\n${statements.trimIndent()}"), ends = ends)

        /** A row whose `use` runs [statements] in the case module, their `unquote`s the module body's. */
        fun fragments(id: String, statements: String, ends: (ElixirLanguageLevel) -> String? = { null }): Row =
            use(id, using("quote unquote: false do", statements), ends = ends)

        private fun using(quote: String, statements: String): String =
            "defmacro __using__(_) do\n  $quote\n${statements.trimIndent().prependIndent("    ")}\n  end\nend"

        /** A row whose case body runs [before], requires the row's module, then holds [statement], a call of its `m/0`. */
        fun caller(
            id: String,
            defs: String,
            before: String,
            statement: String = "$ROW.m()",
            legs: (ElixirLanguageLevel) -> Boolean = EVERY_LEG,
            ends: (ElixirLanguageLevel) -> String? = { null },
        ) = Row(
            id,
            defs.trimIndent(),
            "$ROW.m()",
            "$before\nrequire $ROW\n$statement",
            "$ROW.m()",
            listOf(ROW),
            SourceRun.ANSWERS,
            legs,
            UNPINNED,
            null,
            ends = ends,
        )

        /** Two variables a caller binds, the second reading the first. */
        const val VARIABLES = "x = 1\ny = x"

        /** The legs whose `Macro.Env` has `versioned_vars`, and those whose has the five fields it replaced. */
        val VERSIONED_VARS_LEGS: (ElixirLanguageLevel) -> Boolean = { MACRO_ENV_VERSIONED_VARS.isSufficient(it) }
        val UNVERSIONED_VARS_LEGS: (ElixirLanguageLevel) -> Boolean = { !MACRO_ENV_VERSIONED_VARS.isSufficient(it) }

        /** A row whose case body `use`s the leg's [module], then holds [after]. */
        fun leg(module: String, options: String = "", after: String = "", stopsAfter: String? = null): Row {
            val use = "use $module" + if (options.isEmpty()) "" else ", $options"
            val call = "$module.__using__(${options.ifEmpty { "[]" }})"

            return Row(
                "use:leg-" + module.lowercase(),
                "",
                call,
                if (after.isEmpty()) use else "$use\n$after",
                use,
                listOf(module),
                SourceRun.NONE,
                EVERY_LEG,
                UNPINNED,
                stopsAfter,
            )
        }

        /** The legs where `Module.delete_definition/2` and `Module.get_definition/2,3` exist. */
        val DELETES_DEFINITIONS: (ElixirLanguageLevel) -> Boolean = { MODULE_DELETE_DEFINITION.isSufficient(it) }

        /** A helper module whose macro answers `Module.defines?/2` of its caller's module, where it expands. */
        val DEFINES = """
            defmodule {row}.D do
              defmacro d(name, arity), do: Module.defines?(__CALLER__.module, {name, arity})
            end
        """.trimIndent()

        /** A helper module whose `@before_compile` defines `r/0` as whether its module defines `f/0`. */
        val DEFINES_AT_CALLBACK = """
            defmodule {row}.CC do
              defmacro __before_compile__(env) do
                quote(do: def(r, do: unquote(Module.defines?(env.module, {:f, 0}))))
              end
            end
        """.trimIndent()

        /** [DEFINES], then a module for each of [bodies], each defining `f/0` before them and `g/0` after. */
        fun definesAround(vararg bodies: Pair<String, String>): String =
            DEFINES + bodies.joinToString("") { (module, body) ->
                "\n\ndefmodule {row}.$module do\n  require {row}.D\n  def f, do: 1\n$body\n  def g, do: {row}.D.d(:f, 0)\nend"
            }

        /** A helper module whose macro doubles its argument into a pair. */
        val TWICE = """
            defmodule {row}.H do
              defmacro twice(x), do: quote(do: {unquote(x), unquote(x)})
            end
        """.trimIndent()

        /** A helper module whose macro returns a pair of `quote`s. */
        val PAIR = """
            defmodule {row}.H do
              defmacro pair, do: quote(do: {quote(do: 1), quote(do: 2)})
            end
        """.trimIndent()

        /** A helper struct whose `Access` fetch answers `1`. */
        val FETCHES = """
            defmodule {row}.S do
              defstruct [:a]
              def fetch(_, _), do: {:ok, 1}
            end
        """.trimIndent()

        /** A helper defining the macro `h/0`, which Elixir exports as `MACRO-h/1`. */
        val DEFINES_A_MACRO = """
            defmodule {row}.H do
              defmacro h, do: 1
            end
        """.trimIndent()

        /** A macro defining `a/0` where its argument is marked `stop_generated`, else `b/0`. */
        val STOP_GENERATED = """
            defmacro m(x) do
              if Keyword.get(elem(x, 1), :stop_generated) do
                quote(do: def(a, do: 1))
              else
                quote(do: def(b, do: 1))
              end
            end
        """.trimIndent()

        /**
         * `GenServer.__before_compile__/1`, which warns through functions the evaluator doesn't port where the module
         * doesn't define `init/1`.
         */
        const val GENSERVER_CALLBACK = "{{:., [], [GenServer, :__before_compile__]}"

        val ROWS: List<Row> = listOf(
            // `elixir_erl_pass:translate/3`
            macro(
                "form:wildcard",
                """
                defmacro m do
                  _ = :a
                  :b
                end
                """,
            ),
            macro(
                "form:match",
                """
                defmacro m(x) do
                  {a, b} = x
                  [b, a]
                end
                """,
                "m({1, 2})",
            ),
            macro(
                "form:match-badmatch",
                """
                defmacro m(x) do
                  {a, _} = x
                  a
                end
                """,
                "m(3)",
            ),
            macro("form:tuple", "defmacro m(x), do: Macro.escape({x, 2, 3})", "m(1)"),
            macro("form:map", "defmacro m(x), do: Macro.escape(%{a: x, b: 2})", "m(1)", unordered = true),
            macro(
                "form:map-update",
                """
                defmacro m do
                  x = %{a: 1, b: 2}
                  Macro.escape(%{x | a: 3})
                end
                """,
                unordered = true,
            ),
            macro(
                "form:map-update-badkey",
                """
                defmacro m do
                  x = %{a: 1}
                  Macro.escape(%{x | b: 2})
                end
                """,
            ),
            // Before OTP 25.3.1 the compiler drops an update whose value the macro's body discards, so it raises nowhere.
            macro(
                "form:map-update-discarded-badkey",
                """
                defmacro m do
                  x = %{a: 1}
                  %{x | b: 2}
                  :ok
                end
                """,
            ),
            // Whether anything reads a variable isn't known where it is bound, so before OTP 25.3.1 an update it binds
            // declines.
            macro(
                "form:map-update-bound-badkey",
                """
                defmacro m do
                  x = %{a: 1}
                  y = %{x | b: 2}
                  Macro.escape(y)
                end
                """,
                pins = { _, level -> OPAQUE.takeUnless { MAP_UPDATE_RAISES_WHERE_DISCARDED.isSufficient(level) } },
            ),
            macro(
                "form:struct",
                """defmacro m, do: Macro.escape(%URI{host: "h"})""",
                pins = buildsPartOf("Elixir.URI"),
                unordered = true,
            ),
            macro(
                "form:struct-update",
                """
                defmacro m do
                  u = %URI{host: "h"}
                  Macro.escape(%URI{u | port: 1})
                end
                """,
                pins = buildsPartOf("Elixir.URI"),
                unordered = true,
            ),
            macro(
                "form:struct-update-other",
                """
                defmacro m do
                  x = %{host: 1}
                  Macro.escape(%URI{x | host: "x"})
                end
                """,
            ),
            macro(
                "form:struct-pattern",
                """
                defmacro m do
                  %URI{host: h} = %URI{host: "h"}
                  h
                end
                """,
                pins = buildsPartOf("Elixir.URI"),
            ),
            macro(
                "form:struct-pattern-unknown-name",
                """
                @s (receive do after 0 -> URI end)
                defmacro m do
                  case %{__struct__: @s} do
                    %name{} -> name
                    _ -> :none
                  end
                end
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro("form:bitstring", """defmacro m(a), do: <<a::8, "b">>""", "m(97)"),
            macro("form:bitstring-utf8", "defmacro m(a), do: <<a::utf8>>", "m(233)"),
            macro(
                "form:bitstring-size",
                """
                defmacro m(a) do
                  n = 16
                  Macro.escape(<<a::size(n)>>)
                end
                """,
                "m(97)",
            ),
            macro(
                "form:bitstring-match-size-past-an-int",
                """
                defmacro m do
                  n = 4_294_967_296

                  case <<1>> do
                    <<x::size(n), _::bits>> -> {:matched, x}
                    _ -> :no_match
                  end
                end
                """,
            ),
            // A size that raises fails its pattern, as a guard that raises does, and the next clause is tried.
            macro(
                "form:bitstring-match-size-raises",
                """
                defmacro m do
                  n = 0

                  case <<1>> do
                    <<x::size(div(8, n))>> -> {:matched, x}
                    _ -> :no_match
                  end
                end
                """,
                legs = BITSTRING_SIZE_EXPANDED_AS_GUARD::isSufficient,
            ),
            // From 1.14 a size reads a field as a guard does: `map_get/2`, which a module's name fails.
            macro(
                "form:bitstring-match-size-field",
                """
                defmacro m do
                  module = __MODULE__

                  case <<1>> do
                    <<x::size(module.k)>> -> {:matched, x}
                    _ -> :no_match
                  end
                end

                def k, do: 8
                """,
                legs = BITSTRING_SIZE_EXPANDED_AS_GUARD::isSufficient,
            ),
            macro(
                "form:bitstring-binary-size-past-an-int",
                """
                defmacro m do
                  b = <<1>>
                  n = 4_294_967_296
                  <<b::binary-size(n)>>
                end
                """,
            ),
            macro(
                "form:bitstring-integer-size-past-a-long",
                """
                defmacro m do
                  n = 18_446_744_073_709_551_616
                  <<0::size(n)>>
                end
                """,
                pins = DECLINED,
            ),
            macro(
                "form:bitstring-binary-unit",
                """
                defmacro m do
                  b = <<1, 2>>
                  Macro.escape(<<b::binary-unit(16)>>)
                end
                """,
            ),
            macro(
                "form:bitstring-binary-unit-unaligned",
                """
                defmacro m do
                  b = <<1>>
                  <<b::binary-unit(16)>>
                end
                """,
            ),
            macro(
                "form:bitstring-match-binary-unit",
                """
                defmacro m do
                  case <<1>> do
                    <<x::binary-unit(16)>> -> {:matched, Macro.escape(x)}
                    _ -> :no_match
                  end
                end
                """,
            ),
            // A pinned segment followed by another takes its size from the pinned value.
            macro(
                "form:bitstring-match-pinned-binary",
                """
                defmacro m do
                  x = "cd"

                  case "cdef" do
                    <<^x::binary, rest::binary>> -> rest
                    _ -> :miss
                  end
                end
                """,
                legs = PINNED_BINARY_SEGMENT_INFERS_SIZE::isSufficient,
            ),
            macro(
                "form:bitstring-match-pinned-bitstring",
                """
                defmacro m do
                  x = <<1::3>>

                  case <<1::3, 5::5>> do
                    <<^x::bitstring, rest::bitstring>> -> Macro.escape(rest)
                    _ -> :miss
                  end
                end
                """,
                legs = PINNED_BINARY_SEGMENT_INFERS_SIZE::isSufficient,
            ),
            // A nested bitstring whose whole spec is `binary` or `bitstring` is spliced into the pattern it is in.
            macro(
                "form:bitstring-match-nested-binary",
                """
                defmacro m do
                  case <<1, 2>> do
                    <<(<<x::8>>)::binary, y::8>> -> {x, y}
                    _ -> :miss
                  end
                end
                """,
            ),
            macro(
                "form:bitstring-match-nested-bitstring",
                """
                defmacro m do
                  case <<1::3, 5::5>> do
                    <<(<<x::3>>)::bitstring, y::5>> -> {x, y}
                    _ -> :miss
                  end
                end
                """,
            ),
            macro(
                "form:bitstring-match-nested-empty",
                """
                defmacro m do
                  case <<1>> do
                    <<(<<>>)::binary, y::8>> -> y
                    _ -> :miss
                  end
                end
                """,
            ),
            // A nested bitstring a macro gives in a block of one expression is spliced in as that expression.
            macro(
                "form:bitstring-nested-block",
                """
                defmacro b, do: {:__block__, [], [quote(do: <<1>>)]}
                defmacro m, do: <<b()::binary, 2>>
                """,
            ),
            macro(
                "form:bitstring-match-nested-block",
                """
                defmacro bx, do: {:__block__, [], [quote(do: <<var!(x)::8>>)]}

                defmacro m do
                  case <<1, 2>> do
                    <<bx()::binary, y::8>> -> {x, y}
                    _ -> :miss
                  end
                end
                """,
            ),
            // A binary segment's unit, not a byte, is what its bits come in.
            macro(
                "form:bitstring-match-binary-sub-byte-unit",
                """
                defmacro m do
                  case <<15::4>> do
                    <<x::binary-size(4)-unit(1)>> -> bit_size(x)
                    _ -> :no_match
                  end
                end
                """,
            ),
            macro(
                "form:bitstring-match-binary-sub-byte-unit-tail",
                """
                defmacro m do
                  case <<15::4>> do
                    <<x::binary-unit(1)>> -> bit_size(x)
                    _ -> :no_match
                  end
                end
                """,
            ),
            macro(
                "form:block",
                """
                defmacro m do
                  :a
                  :b
                end
                """,
            ),
            macro("form:caller", "defmacro m, do: __CALLER__.module"),
            // A call another macro's `quote` returns has only the line linify gives it.
            macro(
                "form:caller-line-of-a-quoted-call",
                """
                defmacro l, do: quote(do: def(unquote(if(__CALLER__.line == 0, do: :zero, else: :line))(), do: 1))
                defmacro m, do: quote(do: unquote(__MODULE__).l())
                """,
            ),
            // A map whose `__exception__` isn't `true` is an exception from 1.20.0-rc.5 only.
            macro(
                "form:rescue-exception-key",
                """
                defmacro m do
                  try do
                    :erlang.error(%{__struct__: ArgumentError, __exception__: :yes, message: "m"})
                  rescue
                    ArgumentError -> :argument_error
                    e -> {:other, e.__struct__}
                  end
                end
                """,
            ),
            // Which Erlang error a record is, is its tag's to say.
            macro(
                "form:rescue-unknown-record-tag",
                """
                @r (receive do after 0 -> :badmatch end)
                defmacro m do
                  try do
                    :erlang.error({@r, 1})
                  rescue
                    MatchError -> :match
                    _ -> :other
                  end
                end
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro("form:caller-vars", "defmacro m, do: Macro.Env.has_var?(__CALLER__, {:x, nil})"),
            macro(
                "form:stacktrace",
                """
                defmacro m do
                  try do
                    :erlang.error(:x)
                  rescue
                    _ -> :erlang.is_list(__STACKTRACE__)
                  end
                end
                """,
            ),
            // Before 1.14, `System.stacktrace()` in a `rescue` is `__STACKTRACE__`.
            macro(
                "form:system-stacktrace",
                """
                defmacro m do
                  try do
                    :erlang.error(:x)
                  rescue
                    _ -> :erlang.is_list(System.stacktrace())
                  end
                end
                """,
            ),
            macro(
                "form:system-stacktrace-imported",
                """
                import System, only: [stacktrace: 0]

                defmacro m do
                  try do
                    :erlang.error(:x)
                  rescue
                    _ -> :erlang.is_list(stacktrace())
                  end
                end
                """,
            ),
            macro("form:capture-remote", "defmacro m, do: &Enum.map/2"),
            macro(
                "form:capture-local",
                """
                defmacro m do
                  f = &h/1
                  f.(2)
                end

                def h(x), do: :erlang.*(x, 3)
                """,
            ),
            // Two captures of one local function are one map key.
            macro(
                "form:capture-local-map-key",
                """
                defmacro m do
                  a = &h/1
                  b = &h/1
                  :erlang.map_size(%{a => 1, b => 2})
                end

                def h(x), do: x
                """,
                pins = DECLINED,
            ),
            macro(
                "form:fn",
                """
                defmacro m do
                  f = fn x -> :erlang.+(x, 1) end
                  f.(1)
                end
                """,
            ),
            macro(
                "form:fn-clauses",
                """
                defmacro m do
                  f = fn
                    :a -> 1
                    _ -> 2
                  end

                  [f.(:a), f.(:b)]
                end
                """,
            ),
            macro(
                "form:fn-call",
                """
                defmacro m do
                  f = fn x, y -> [y, x] end
                  f.(1, 2)
                end
                """,
            ),
            macro(
                "form:fn-badarity",
                """
                defmacro m do
                  f = fn x -> x end
                  f.(1, 2)
                end
                """,
            ),
            macro(
                "form:fn-clause",
                "defmacro m(x), do: Enum.map([x], fn :a -> 1 end)",
                "m(:b)",
                pins = FN_CLAUSE_BELOW_OTP_25,
            ),
            macro(
                "form:fn-clause-bound",
                """
                defmacro m do
                  f = fn :a -> 1 end
                  f.(:b)
                end
                """,
                pins = FN_CLAUSE_BELOW_OTP_25,
            ),
            // A fun's name is the compiler's, from its fun counter and what it inlines.
            macro(
                "form:fn-clause-name",
                """
                defmacro m do
                  f = fn :a -> 1 end
                  g = fn :b -> f.(:a) end

                  try do
                    g.(:c)
                  rescue
                    e in FunctionClauseError -> e.function
                  end
                end
                """,
                pins = DECLINED,
            ),
            macro(
                "form:cond",
                """
                defmacro m(x) do
                  cond do
                    :erlang.==(x, 1) -> :one
                    true -> :other
                  end
                end
                """,
                "m(2)",
            ),
            macro(
                "form:cond-truthy",
                """
                defmacro m do
                  cond do
                    nil -> 1
                    false -> 2
                    :yes -> 3
                  end
                end
                """,
            ),
            macro(
                "form:cond-no-clause",
                """
                defmacro m(x) do
                  cond do
                    :erlang.==(x, 1) -> :one
                  end
                end
                """,
                "m(2)",
            ),
            macro(
                "form:case",
                """
                defmacro m(x) do
                  case x do
                    {:a, v} -> v
                    _ -> :none
                  end
                end
                """,
                "m({:a, 1})",
            ),
            macro(
                "form:case-guard",
                """
                defmacro m(x) do
                  case x do
                    v when :erlang.is_integer(v) -> :int
                    _ -> :other
                  end
                end
                """,
                "m(1)",
            ),
            macro(
                "form:case-clause",
                """
                defmacro m(x) do
                  case x do
                    :a -> 1
                  end
                end
                """,
                "m(:b)",
            ),
            macro(
                "form:case-attribute-pattern",
                """
                @tag {:ok, [1]}

                defmacro m(x) do
                  case x do
                    @tag -> :yes
                    _ -> :no
                  end
                end
                """,
                "m({:ok, [1]})",
            ),
            macro(
                "form:case-attribute-pattern-unmatched",
                """
                @tag {:ok, [1]}

                defmacro m(x) do
                  case x do
                    @tag -> :yes
                    _ -> :no
                  end
                end
                """,
                "m({:ok, [1.0]})",
            ),
            // `expand_remote/8` folds a signed number in a pattern into the number.
            macro("form:case-negative-integer-pattern", "defmacro m, do: (case -1 do -1 -> :yes; _ -> :no end)"),
            macro("form:case-negative-integer-pattern-unmatched", "defmacro m, do: (case 1 do -1 -> :yes; _ -> :no end)"),
            macro("form:case-negative-float-pattern", "defmacro m, do: (case -1.5 do -1.5 -> :yes; _ -> :no end)"),
            macro("form:case-positive-integer-pattern", "defmacro m, do: (case 2 do +2 -> :yes; _ -> :no end)"),
            macro("form:case-negative-zero-pattern", "defmacro m, do: (case 0.0 do -0.0 -> :yes; _ -> :no end)"),
            macro(
                "form:try-rescue",
                """
                defmacro m do
                  try do
                    :erlang.error(:badarg)
                  rescue
                    e in ArgumentError -> Macro.escape(e)
                  end
                end
                """,
                unordered = true,
            ),
            macro(
                "form:try-rescue-erlang",
                """
                defmacro m do
                  try do
                    :erlang.binary_to_atom(1, :utf8)
                  rescue
                    ArgumentError -> :badarg
                  end
                end
                """,
            ),
            macro(
                "form:try-rescue-keyerror",
                """
                defmacro m do
                  try do
                    Keyword.fetch!([], :a)
                  rescue
                    e in KeyError -> e.key
                  end
                end
                """,
            ),
            macro(
                "form:try-rescue-erlang-error",
                """
                defmacro m do
                  try do
                    :erlang.error(:oops)
                  rescue
                    e in ErlangError -> e.original
                  end
                end
                """,
            ),
            macro(
                "form:try-rescue-variable",
                """
                defmacro m do
                  try do
                    :erlang.error(:oops)
                  rescue
                    e -> e.__struct__
                  end
                end
                """,
            ),
            macro(
                "form:try-rescue-unmatched",
                """
                defmacro m do
                  try do
                    :erlang.error(:oops)
                  rescue
                    KeyError -> :key
                  end
                end
                """,
            ),
            macro(
                "form:try-catch",
                """
                defmacro m do
                  try do
                    :erlang.throw(:t)
                  catch
                    :throw, v -> v
                  end
                end
                """,
            ),
            macro(
                "form:try-catch-value",
                """
                defmacro m do
                  try do
                    :erlang.throw(:t)
                  catch
                    v -> [v]
                  end
                end
                """,
            ),
            macro(
                "form:try-catch-error",
                """
                defmacro m do
                  try do
                    :erlang.error(:e)
                  catch
                    :error, v -> v
                  end
                end
                """,
            ),
            // `error/2` gives the raising function's frame the arguments it is given, where they are a list.
            macro(
                "pure:error-args",
                """
                def top(args) do
                  try do
                    :erlang.error(:e, args)
                  catch
                    :error, _ -> :erlang.element(3, :erlang.hd(__STACKTRACE__))
                  end
                end

                defmacro m, do: Macro.escape([top([:shown]), top([]), top([1 | 2]), top(:none), top(:bad)])
                """,
            ),
            // `error/3` also puts the last `error_info` in the frame's location, where every option is one with a map.
            macro(
                "pure:error-info",
                """
                def top(options) do
                  try do
                    :erlang.error(:e, [:shown], options)
                  catch
                    :error, _ ->
                      [frame | _] = __STACKTRACE__
                      {:erlang.element(3, frame), :lists.keyfind(:error_info, 1, :erlang.element(4, frame))}
                  end
                end

                defmacro m do
                  Macro.escape([
                    top(error_info: %{a: 1}),
                    top(error_info: %{a: 1}, error_info: %{b: 2}),
                    top([]),
                    top(error_info: :x),
                    top(error_info: %{a: 1}, other: 1),
                    top([{:error_info, %{a: 1}} | :t]),
                    top(:x)
                  ])
                end
                """,
            ),
            macro(
                "form:try-after",
                """
                defmacro m do
                  try do
                    :a
                  after
                    :b
                  end
                end
                """,
            ),
            macro(
                "form:try-after-raise",
                """
                defmacro m do
                  try do
                    :erlang.error(:x)
                  after
                    :b
                  end
                end
                """,
            ),
            macro(
                "form:try-else",
                """
                defmacro m do
                  try do
                    :a
                  rescue
                    _ -> :r
                  else
                    :a -> :else_a
                  end
                end
                """,
            ),
            macro(
                "form:try-else-clause",
                """
                defmacro m do
                  try do
                    :b
                  rescue
                    _ -> :r
                  else
                    :a -> 1
                  end
                end
                """,
            ),
            macro("form:throw", "defmacro m, do: :erlang.throw(:t)", pins = DECLINED),
            macro(
                "form:receive",
                """
                defmacro m do
                  receive do
                    x -> x
                  after
                    0 -> :none
                  end
                end
                """,
                pins = DECLINED,
            ),
            macro("form:for", "defmacro m, do: for(x <- [1, 2], do: :erlang.*(x, 2))"),
            macro("form:for-pattern", "defmacro m, do: for({:a, v} <- [{:a, 1}, :b, {:a, 2}], do: v)"),
            macro("form:for-filter", "defmacro m, do: for(x <- [1, 2, 3], :erlang.>(x, 1), do: x)"),
            macro("form:for-generators", "defmacro m, do: for(x <- [1, 2], y <- [:a], do: {x, y})"),
            macro("form:for-into", "defmacro m, do: Macro.escape(for(x <- [1, 2], into: %{}, do: {x, x}))"),
            macro("form:for-into-binary", """defmacro m, do: for(x <- ["a", "b"], into: "", do: x)"""),
            macro(
                "form:for-reduce",
                """
                defmacro m do
                  for x <- [1, 2], reduce: 0 do
                    acc -> :erlang.+(acc, x)
                  end
                end
                """,
            ),
            macro("form:for-uniq", "defmacro m, do: for(x <- [1, 1, 2], uniq: true, do: x)"),
            // `elixir_erl_for` reads a case as it expanded: from 1.18 a macro's `<-/2` is a generator.
            macro(
                "form:for-generated-generator",
                """
                defmacrop gen, do: quote(do: 1 <- [1, 2, 1])
                defmacro m, do: for(gen(), do: :ok)
                """,
                legs = POST_MODULE_LOCAL_CHECKS_TYPED::isSufficient,
            ),
            macro(
                "form:for-generated-filter",
                """
                defmacrop small(x), do: quote(do: :erlang.<(unquote(x), 2))
                defmacro m, do: for(x <- [1, 2, 3], small(x), do: x)
                """,
            ),
            // Not inline, as a list generator makes it, a bitstring generator over a non-bitstring raises `badarg`.
            macro(
                "form:for-generated-generator-bitstring-badarg",
                """
                defmacrop gen, do: quote(do: 1 <- [1])

                defmacro m do
                  b = :a

                  try do
                    for gen(), <<x <- b>>, into: "", do: <<x>>
                  rescue
                    e -> e.__struct__
                  end
                end
                """,
                legs = POST_MODULE_LOCAL_CHECKS_TYPED::isSufficient,
            ),
            macro(
                "form:for-bitstring-generator",
                """defmacro m, do: for(<<c <- "ab">>, do: c)""",
            ),
            macro(
                "form:for-bitstring-skip",
                "defmacro m, do: for(<<x::8, 0::8 <- <<1, 0, 2, 1, 3, 0>> >>, do: x)",
            ),
            // A reduce's skip clause, by the sizes each leg's `elixir_erl_for` keeps.
            macro(
                "form:for-bitstring-reduce-skip-integer",
                "defmacro m, do: for(<<x::8, 0::8 <- <<1, 0, 2, 1, 3, 0>> >>, uniq: true, do: x)",
            ),
            macro(
                "form:for-bitstring-reduce-skip-variable",
                """
                defmacro m do
                  n = 8
                  for <<x::size(n), 0::8 <- <<1, 0, 2, 1, 3, 0>> >>, uniq: true, do: x
                end
                """,
            ),
            macro(
                "form:for-bitstring-reduce-skip-rebound",
                """
                defmacro m do
                  n = 8
                  {n, for(<<n::8, x::size(n), 0::8 <- <<8, 1, 0, 8, 2, 1, 8, 3, 0>> >>, uniq: true, do: x)}
                end
                """,
            ),
            macro(
                "form:for-bitstring-reduce-skip-generator-rebound",
                """
                defmacro m do
                  n = 8
                  for n <- [8], <<x::size(n), 0::8 <- <<1, 0, 2, 1, 3, 0>> >>, do: x
                end
                """,
            ),
            macro(
                "form:for-bitstring-reduce-skip-bitstring-generator-rebound",
                """
                defmacro m do
                  n = 8
                  for <<n::8 <- <<8>> >>, _ <- [1], <<x::size(n), 0::8 <- <<1, 0, 2, 1, 3, 0>> >>, do: x
                end
                """,
            ),
            macro(
                "form:for-bitstring-reduce-skip-filter-rebound",
                """
                defmacro m do
                  n = 8
                  for _ <- [1], n = 8, <<x::size(n), 0::8 <- <<1, 0, 2, 1, 3, 0>> >>, do: x
                end
                """,
            ),
            macro(
                "form:for-bitstring-reduce-skip-attribute",
                """
                @n 8
                defmacro m, do: for(<<0::size(@n), x::8 <- <<0, 1, 255, 2, 0, 3, 0, 1>> >>, uniq: true, do: x)
                """,
            ),
            macro(
                "form:for-bitstring-reduce-skip-unit-attribute",
                """
                @u 8
                defmacro m, do: for(<<0::size(1)-unit(@u), x::8 <- <<0, 1, 255, 2, 0, 3, 0, 1>> >>, uniq: true, do: x)
                """,
            ),
            macro(
                "form:for-bitstring-reduce-skip-attribute-operator",
                """
                @n 1
                defmacro m, do: for(<<0::size(@n * 8), x::8 <- <<0, 1, 255, 2, 0, 3, 0, 1>> >>, uniq: true, do: x)
                """,
                legs = BITSTRING_SIZE_EXPANDED_AS_GUARD::isSufficient,
            ),
            macro(
                "form:for-bitstring-reduce-skip-macro",
                """
                defmacrop eight, do: 8
                defmacro m, do: for(<<0::size(eight()), x::8 <- <<0, 1, 255, 2, 0, 3, 0, 1>> >>, uniq: true, do: x)
                """,
            ),
            macro(
                "form:for-bitstring-reduce-skip-macro-variable",
                """
                defmacrop sz(v), do: v
                defmacro m do
                  n = 8
                  for <<0::size(sz(n)), x::8 <- <<0, 1, 255, 2, 0, 3, 0, 1>> >>, uniq: true, do: x
                end
                """,
            ),
            macro(
                "form:bitstring-size-var-macro",
                """
                defmacro m do
                  n = 8
                  <<x::size(var!(n))>> = <<1>>
                  x
                end
                """,
            ),
            // A size is expanded once, in the context `expand_spec_arg/4` gives it, which its macro's `__CALLER__` sees.
            macro(
                "form:bitstring-size-macro-caller-context",
                """
                defmacrop sz(v), do: if(__CALLER__.context == :match, do: quote(do: m), else: v)

                defmacro m do
                  n = 8
                  <<x::size(sz(n))>> = <<1>>
                  x
                end
                """,
            ),
            // Elixir compares the two sizes as the floats they expand to; from source the attribute's float isn't held.
            macro(
                "form:bitstring-size-float-attribute",
                """
                @f 8.0

                defmacro m do
                  <<x::size(@f)-size(8.0)>> = <<1>>
                  x
                end
                """,
                pins = { source, _ -> UNPORTED.takeIf { source == Source.SOURCE } },
                legs = BITSTRING_SIZE_EXPANDED_AS_GUARD::isSufficient,
            ),
            macro(
                "form:bitstring-size-repeated-attribute",
                """
                @n 8
                defmacro m do
                  <<x::size(@n)-size(8)>> = <<1>>
                  x
                end
                """,
            ),
            // A bare segment's value is evaluated as its expansion recorded it.
            macro(
                "form:bitstring-bare-attribute",
                """
                @n 65
                defmacro m, do: <<@n>>
                """,
            ),
            macro(
                "form:bitstring-bare-macro",
                """
                defmacrop n, do: 65
                defmacro m, do: <<n()>>
                """,
            ),
            // A UTF segment of a string literal is the string's code points, though an attribute spells it.
            macro(
                "form:bitstring-utf-attribute",
                """
                @text "ab"
                defmacro m, do: <<@text::utf8>>
                """,
            ),
            macro(
                "form:bitstring-match-utf-attribute",
                """
                @text "ab"
                defmacro m do
                  case "abc" do
                    <<@text::utf8, rest::binary>> -> rest
                    _ -> :miss
                  end
                end
                """,
            ),
            // A string literal's segment matches its own bytes, not the rest, though an attribute spells it.
            macro(
                "form:bitstring-match-binary-attribute",
                """
                @text "ab"
                defmacro m do
                  case "abc" do
                    <<@text, rest::binary>> -> rest
                    _ -> :miss
                  end
                end
                """,
            ),
            macro(
                "form:for-bitstring-reduce-skip-operator",
                """
                defmacro m do
                  s = 1
                  for <<0::size(s * 8), x::8 <- <<0, 1, 255, 2, 0, 3, 0, 1>> >>, uniq: true, do: x
                end
                """,
                legs = BITSTRING_SIZE_EXPANDED_AS_GUARD::isSufficient,
            ),
            macro("form:for-bitstring-not-bitstring", "defmacro m(v), do: for(<<x <- v>>, do: x)", "m(1)"),
            macro(
                "form:for-bitstring-not-bitstring-into",
                """defmacro m(v), do: for(<<x <- v>>, into: "", do: <<x>>)""",
                "m(1)",
            ),
            macro(
                "form:for-bitstring-not-bitstring-into-bitstring",
                "defmacro m(v), do: for(<<x <- v>>, into: <<>>, do: <<x>>)",
                "m(1)",
            ),
            macro(
                "form:for-into-first",
                """
                defmacro m do
                  try do
                    for x <- [1], into: :erlang.error(:into), do: :erlang.error(x)
                  rescue
                    e in ErlangError -> e.original
                  end
                end
                """,
            ),
            // `Collectable.into/1` raises for `1` before the body runs.
            macro(
                "form:for-into-unsupported-first",
                """
                defmacro m do
                  try do
                    for _ <- [1], into: 1, do: :erlang.div(1, 0)
                  rescue
                    e -> e.__struct__
                  end
                end
                """,
                pins = DECLINED,
            ),
            // The collector `Collectable.into/1` gives takes each result as its body gives it.
            macro(
                "form:for-into-collectable",
                """
                defmacro m do
                  out = %{}
                  Macro.escape(for(x <- [1, 2], into: out, do: {x, x}))
                end
                """,
            ),
            // So the first result it rejects raises before the next body runs.
            macro(
                "form:for-into-collectable-rejects-first",
                """
                defmacro m do
                  out = %{}

                  try do
                    for x <- [1, 2], into: out, do: if(x == 1, do: :bad, else: :erlang.div(1, 0))
                  rescue
                    e -> e.__struct__
                  end
                end
                """,
                pins = DECLINED,
            ),
            // `build_inline_each/7` checks each result `into: ""` takes as its body gives it.
            macro(
                "form:for-into-binary-rejects-first",
                """
                defmacro m do
                  try do
                    for x <- [1, 2], into: "", do: if(x == 1, do: :bad, else: :erlang.div(1, 0))
                  rescue
                    e -> e.__struct__
                  end
                end
                """,
            ),
            // `build_inline/7` reads `into:` as it expanded, so an attribute's `[]` is inline, where before 1.20.2 a
            // bitstring generator skips a run its pattern doesn't match.
            macro(
                "form:for-into-attribute-inline",
                """
                @out []
                defmacro m do
                  n = 8
                  for <<x::size(n), 0::8 <- <<1, 0, 2, 1, 3, 0>> >>, into: @out, do: x
                end
                """,
            ),
            // And `uniq: true` isn't, as it expanded.
            macro(
                "form:for-uniq-attribute",
                """
                @u true
                defmacro m do
                  n = 8
                  for <<x::size(n), 0::8 <- <<1, 0, 2, 1, 3, 0>> >>, uniq: @u, do: x
                end
                """,
            ),
            // `expand_for/4` expands the options first, in the scope the generators then start from, and from
            // `GENERATOR_RIGHT_SIDE_SCOPED` drops what a generator's right side binds.
            macro(
                "form:for-generator-right-side-scope",
                """
                defmacro m do
                  n = 8
                  for _ <- (n = 16; [1]), do: n
                end
                """,
            ),
            macro(
                "form:for-bitstring-generator-right-side-scope",
                """
                defmacro m do
                  n = 8
                  for _ <- [1], <<x::8 <- (n = 16; <<1>>)>>, do: {x, n}
                end
                """,
                // Before, Elixir doesn't compile it: `unbound variable '_n@2'`.
                legs = GENERATOR_RIGHT_SIDE_SCOPED::isSufficient,
            ),
            macro(
                "form:for-into-scope",
                """
                defmacro m do
                  n = 8
                  {for(_ <- [1], into: (n = 16; []), do: n), n}
                end
                """,
            ),
            macro(
                "form:for-reduce-scope",
                """
                defmacro m do
                  n = 8
                  {for(_ <- [1], reduce: (n = 16; 0), do: (acc -> acc + n)), n}
                end
                """,
            ),
            macro("form:with", "defmacro m(x), do: with({:ok, v} <- x, do: v)", "m({:ok, 1})"),
            macro("form:with-unmatched", "defmacro m(x), do: with({:ok, v} <- x, do: v)", "m(:error)"),
            macro("form:with-variable", "defmacro m(x), do: with(y <- x, do: [y])", "m(:a)"),
            macro(
                "form:with-else",
                """
                defmacro m(x) do
                  with {:ok, v} <- x do
                    v
                  else
                    :error -> :failed
                  end
                end
                """,
                "m(:error)",
            ),
            macro(
                "form:with-else-before-last",
                """
                defmacro m(x) do
                  with {:ok, v} <- x, else: (:error -> :failed) do
                    v
                  end
                end
                """,
                "m(:error)",
                legs = WITH_OPTIONS_BEFORE_LAST_ARGUMENT::isSufficient,
            ),
            macro(
                "form:with-clause-error",
                """
                defmacro m(x) do
                  with {:ok, v} <- x do
                    v
                  else
                    :error -> :failed
                  end
                end
                """,
                "m(:other)",
            ),
            macro(
                "form:pin",
                """
                defmacro m(x) do
                  y = 1

                  case x do
                    ^y -> :same
                    _ -> :different
                  end
                end
                """,
                "m(1)",
            ),
            macro("form:variable", "defmacro m(x), do: x", "m(:a)"),
            macro(
                "form:local",
                """
                defmacro m, do: h(2)

                defp h(x), do: [x]
                """,
            ),
            macro(
                "form:local-clauses",
                """
                defmacro m, do: [h(1), h(:a)]

                defp h(x) when :erlang.is_integer(x), do: :int
                defp h(_), do: :other
                """,
            ),
            macro(
                "form:guard-field",
                """
                defmacro m do
                  x = %{a: true}

                  case x do
                    v when v.a -> :yes
                    _ -> :no
                  end
                end
                """,
            ),
            macro(
                "form:guard-field-raises",
                """
                defmacro m do
                  x = 1

                  case x do
                    v when v.a -> :yes
                    _ -> :no
                  end
                end
                """,
            ),
            macro(
                "form:field",
                """
                defmacro m do
                  x = %{a: 1}
                  x.a
                end
                """,
            ),
            macro(
                "form:field-missing-key",
                """
                defmacro m do
                  x = %{a: 1}
                  x.b
                end
                """,
            ),
            // Before a non-map raises for its key, `1.key` calls `1:key()`.
            macro(
                "form:field-remote",
                """
                defmacro m do
                  x = 1
                  x.key
                end
                """,
                legs = { !FIELD_OF_NON_MAP_RAISES_BADKEY.isSufficient(it) },
            ),
            // Before a non-map raises for its key, `nil.key` and `true.key` call a module no file defines.
            macro(
                "form:field-opaque",
                """
                defmacro m do
                  x = nil
                  x.key
                end
                """,
                legs = { !FIELD_OF_NON_MAP_RAISES_BADKEY.isSufficient(it) },
                pins = DECLINED,
            ),
            macro(
                "form:field-opaque-true",
                """
                defmacro m do
                  x = true
                  x.key
                end
                """,
                legs = { !FIELD_OF_NON_MAP_RAISES_BADKEY.isSufficient(it) },
                pins = DECLINED,
            ),
            macro(
                "form:field-keyerror",
                FIELD_ON_NON_MAPS,
                pins = { _, level -> OPAQUE.takeIf { KEY_ERROR_MESSAGE_INSPECTED_WITH_OPTIONS.isSufficient(level) } },
                legs = { FIELD_OF_NON_MAP_RAISES_BADKEY.isSufficient(it) && !FIELD_OF_NON_MAP_RAISES_BADMAP.isSufficient(it) },
            ),
            macro("form:field-badmap", FIELD_ON_NON_MAPS, legs = FIELD_OF_NON_MAP_RAISES_BADMAP::isSufficient),
            macro("form:remote", "defmacro m, do: Keyword.keys([a: 1, b: 2])"),
            macro(
                "form:remote-own",
                """
                defmacro m, do: $ROW.h(1)

                def h(x), do: [x]
                """,
            ),
            macro("form:remote-unreadable", "defmacro m, do: :code.which(:absent_beam)", pins = DECLINED),
            // A module defined again, where the later definition stops, leaves none of the earlier one's clauses.
            macro(
                "form:remote-redefined-stopped",
                "defmacro m, do: $ROW.A.f()",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule $ROW.A do
                  def f, do: 1
                end

                defmodule $ROW.A do
                  def f, do: 2
                  defoverridable Application.get_env(:app, :ov, f: 0)
                end
                """,
            ),
            macro("form:pair", "defmacro m, do: {:a, :b}"),
            macro("form:list", "defmacro m, do: [1, :a]"),
            macro("form:improper-list", "defmacro m, do: Macro.escape([1 | 2])"),
            macro("form:literal", """defmacro m, do: [1.5, "b", :c]"""),
            macro(
                "form:defguard-source",
                "defguard is_even(x) when rem(x, 2) == 0",
                "is_even(4)",
            ),
            Row(
                "form:defguard-in-guard",
                "defguard is_even(x) when rem(x, 2) == 0",
                "$ROW.is_even(4)",
                "require $ROW\n_ = $ROW.is_even(4)\ndef f(y) when $ROW.is_even(y), do: y",
                "$ROW.is_even(4)",
                listOf(ROW),
                SourceRun.NONE,
                EVERY_LEG,
                UNPINNED,
                null,
            ),
            // `elixir_bitstring:expand_each_spec/6` unpacks the specs a macro expands to again, ahead of the rest.
            Row(
                "form:bitstring-spec-macro",
                "defmacro my_size, do: quote(do: size(16))",
                "$ROW.my_size()",
                "import $ROW\ndef f(x), do: <<x::my_size()>>",
                "my_size()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),
            Row(
                "form:bitstring-spec-macro-specs",
                "defmacro u8, do: quote(do: unsigned - size(8))",
                "$ROW.u8()",
                "import $ROW\ndef f(x), do: <<x::u8()-little>>",
                "u8()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),
            Row(
                "form:bitstring-spec-macro-again",
                "defmacro s8, do: quote(do: b8() - signed)\ndefmacro b8, do: quote(do: size(8))",
                "$ROW.s8()",
                "import $ROW\ndef f(x), do: <<x::s8()>>",
                "s8()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),
            // A spec written as a name is expanded as a call of it on every version.
            Row(
                "form:bitstring-spec-macro-bare-name",
                "defmacro s8, do: quote(do: b8 - signed)\ndefmacro b8, do: quote(do: size(8))",
                "$ROW.s8()",
                "import $ROW\ndef f(x), do: <<x::s8()>>",
                "s8()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),
            Row(
                "form:bitstring-spec-bare-name",
                "defmacro b8, do: quote(do: size(8))",
                "$ROW.b8()",
                "import $ROW\ndef f(x), do: <<x::b8>>",
                "b8",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),

            // `elixir_quote`'s run-time helpers
            macro(
                "quote:runtime-option",
                """
                defmacro m do
                  l = 7
                  quote line: l, do: node()
                end
                """,
            ),
            macro(
                "quote:runtime-option-invalid",
                """
                defmacro m do
                  l = :bad
                  quote line: l, do: node()
                end
                """,
            ),
            macro(
                "quote:validate",
                """
                defmacro m do
                  x = 1
                  quote do: unquote(x)
                end
                """,
            ),
            macro(
                "quote:dot",
                """
                defmacro m do
                  name = :max
                  quote do: Kernel.unquote(name)(1, 2)
                end
                """,
            ),
            macro(
                "quote:dot-alias",
                """
                defmacro m do
                  name = String
                  quote do: Kernel.unquote(name)
                end
                """,
            ),
            macro(
                "quote:dot-atom",
                """
                defmacro m do
                  name = :node
                  quote do: Kernel.unquote(name)
                end
                """,
            ),
            macro(
                "quote:dot-aliases",
                """
                defmacro m do
                  name = quote(do: Foo.Bar)
                  quote do: Kernel.unquote(name)
                end
                """,
            ),
            macro(
                "quote:dot-no-parens",
                """
                defmacro m do
                  name = quote(do: foo)
                  quote do: %{foo: 1}.unquote(name)
                end
                """,
            ),
            macro(
                "quote:dot-call",
                """
                defmacro m do
                  name = quote(do: reverse([1]))
                  quote do: :lists.unquote(name)
                end
                """,
            ),
            macro(
                "quote:dot-invalid",
                """
                defmacro m do
                  name = 1
                  quote do: Kernel.unquote(name)
                end
                """,
            ),
            macro(
                "quote:dot-args",
                """
                defmacro m do
                  name = quote(do: max)
                  quote do: :erlang.unquote(name)(1, 2)
                end
                """,
            ),
            macro(
                "quote:dot-args-invalid",
                """
                defmacro m do
                  name = 1
                  quote do: Kernel.unquote(name)(1)
                end
                """,
            ),
            macro(
                "quote:splice",
                """
                defmacro m do
                  xs = [1, 2]
                  quote do: [0, unquote_splicing(xs), 3]
                end
                """,
            ),
            macro(
                "quote:splice-call",
                """
                defmacro m do
                  xs = [1, 2]
                  quote do: max(unquote_splicing(xs))
                end
                """,
            ),
            macro(
                "quote:splice-tail",
                """
                defmacro m do
                  xs = [1, 2]
                  quote do: [0, unquote_splicing(xs) | [3]]
                end
                """,
            ),
            macro(
                "quote:splice-empty-before-tail",
                """
                defmacro m do
                  xs = []
                  quote do: [unquote_splicing(xs) | [3]]
                end
                """,
            ),
            macro(
                "quote:splice-invalid",
                """
                defmacro m do
                  xs = 1
                  quote do: [unquote_splicing(xs)]
                end
                """,
            ),
            macro(
                "quote:splice-invalid-element",
                """
                defmacro m do
                  xs = [{1, 2, 3}]
                  quote do: [unquote_splicing(xs)]
                end
                """,
                legs = UNQUOTE_SHALLOW_VALIDATED::isSufficient,
            ),
            macro(
                "quote:unquote-invalid",
                """
                defmacro m do
                  x = {1, 2, 3}
                  quote do: f(unquote(x))
                end
                """,
                legs = UNQUOTE_SHALLOW_VALIDATED::isSufficient,
            ),
            macro(
                "quote:unquote-invalid-unknown-call",
                """
                @c Application.compile_env(:nope, :c, :g)
                defmacro m do
                  x = {@c, [], 1}
                  quote do: f(unquote(x))
                end
                """,
                legs = UNQUOTE_SHALLOW_VALIDATED::isSufficient,
            ),
            macro(
                "quote:annotate-def",
                """
                defmacro m do
                  name = :def
                  quote do: Kernel.unquote(name)(f(x), do: x)
                end
                """,
            ),
            macro(
                "quote:annotate-def-when",
                """
                defmacro m do
                  name = :def
                  quote do: Kernel.unquote(name)(f(x) when x, do: x)
                end
                """,
            ),
            macro(
                "quote:annotate-lexical",
                """
                defmacro m do
                  name = :import
                  Macro.escape(quote do: Kernel.unquote(name)(List))
                end
                """,
            ),

            // `Macro.escape/2`
            macro("escape:prune", "defmacro m, do: Macro.escape({:a, [line: 1], nil}, prune_metadata: true)"),
            macro("escape:pair", "defmacro m, do: Macro.escape({:a, 1})"),
            macro("escape:tuple", "defmacro m, do: Macro.escape({:a, 1, 2})"),
            macro("escape:bitstring", "defmacro m, do: Macro.escape(<<1::3>>)"),
            macro("escape:map-order", "defmacro m, do: Macro.escape(%{b: 1, a: 2, c: 3})", unordered = true),
            macro("escape:map-nested", "defmacro m, do: Macro.escape(%{a: %{b: [1]}})"),
            // `1` and `1.0` tie as keys, so the sort compares their values, one of them a binary whose bytes aren't known.
            macro(
                "escape:map-unknown-value",
                "defmacro m, do: Macro.escape(%{1 => inspect(1), 1.0 => :ok})",
                pins = DECLINED,
                unordered = true,
            ),
            // In the window, Elixir lists atom keys in the order the VM made them, and the port keeps term order.
            macro(
                "escape:map-vm-order",
                "defmacro m, do: Macro.escape(%{zz_vm_first: 1, aa_vm_second: 2})",
                legs = ESCAPED_MAP_IN_VM_ORDER::isSufficient,
                pins = { _, _ -> "{:%{}, [], [aa_vm_second: 2, zz_vm_first: 1]}" },
            ),
            // `__escape__/1` returns code the escape doesn't run.
            macro(
                "escape:struct-escape",
                """
                defstruct [:ast]

                def __escape__(%{ast: ast}), do: ast

                defmacro m, do: Macro.escape(%{__struct__: __MODULE__, ast: :escaped})
                """,
                pins = { _, level -> OPAQUE.takeIf { REGEX_ESCAPED_IN_FIXED_FIELD_ORDER.isSufficient(level) } },
                unordered = true,
            ),
            macro("escape:struct", "defmacro m, do: Macro.escape(%URI{})", pins = buildsPartOf("Elixir.URI"), unordered = true),
            macro("escape:empty-list", "defmacro m, do: Macro.escape([])"),
            macro("escape:list-no-unquote", "defmacro m, do: Macro.escape([1, {:unquote, [], [:x]}])"),
            macro("escape:list", "defmacro m, do: Macro.escape([1, {:unquote, [], [:x]}], unquote: true)"),
            macro("escape:improper-list", "defmacro m, do: Macro.escape([1, 2 | 3])"),
            macro("escape:literal", """defmacro m, do: Macro.escape([1, 2.5, :a, "b"])"""),
            macro("escape:external", "defmacro m, do: Macro.escape(&Enum.map/2)"),
            macro("escape:closure", "defmacro m, do: Macro.escape(fn -> 1 end)"),

            // `elixir_dispatch`
            macro(
                "form:user-local",
                """
                defmacrop twice(x), do: quote(do: {unquote(x), unquote(x)})

                defmacro m, do: Macro.escape(twice(1))
                """,
            ),
            macro(
                "form:user-import",
                """
                import {row}.H

                defmacro m, do: Macro.escape(twice(1))
                """,
                helper = TWICE,
            ),
            macro(
                "form:user-remote",
                """
                require {row}.H

                defmacro m, do: Macro.escape({row}.H.twice(1))
                """,
                helper = TWICE,
            ),
            // The helper's output holds two `quote`s, which take its call's origin.
            macro(
                "form:quotes-sharing-an-origin",
                """
                require {row}.H

                defmacro m, do: {row}.H.pair()
                """,
                helper = PAIR,
            ),
            macro(
                "form:raise-in-macro",
                """defmacro m, do: raise(%ArgumentError{message: "no"})""",
                pins = buildsPartOf("Elixir.ArgumentError"),
            ),
            // `elixir_erl_pass:translate/3`'s `super`: the definition its `{super, {Kind, Name}}` names, a macro's
            // given `__CALLER__`.
            macro(
                "form:super-overridable",
                """
                defmacro m, do: Macro.escape({1, __CALLER__.module})
                defoverridable m: 0
                defmacro m, do: Macro.escape({super(), 2})
                """,
            ),
            macro(
                "form:super-overridable-function",
                """
                def f(x), do: x
                defoverridable f: 1
                def f(x), do: {super(x), 2}
                defmacro m, do: Macro.escape(f(1))
                """,
            ),
            macro(
                "form:super-capture",
                """
                def f(x), do: x
                defoverridable f: 1
                def f(x), do: {(&super/1).(x), 2}
                defmacro m, do: Macro.escape(f(1))
                """,
            ),
            macro(
                "form:super-capture-call",
                """
                def f(x), do: x
                defoverridable f: 1
                def f(x), do: {(&super(&1)).(x), 2}
                defmacro m, do: Macro.escape(f(1))
                """,
            ),
            macro(
                "form:super-capture-expression",
                """
                def f(x), do: x
                defoverridable f: 1
                def f(x), do: {(&super({&1})).(x), 2}
                defmacro m, do: Macro.escape(f(1))
                """,
            ),
            // `elixir_def:unpack_expanded/7`'s default arity calls the full one through `super`.
            macro("form:super-default", "defmacro m(x \\\\ 1), do: x"),
            // From 1.20 the store gives a default arity's head `context: :elixir_def`; the head as written has none.
            macro("form:default-arity-context", "defmacro m, do: {:def, [], [quote(do: g(x, y \\\\ 1)), [do: 1]]}"),
            macro(
                "form:super-default-function",
                """
                def f(x, y \\ 2), do: {x, y}
                defmacro m, do: Macro.escape(f(1))
                """,
            ),
            // Before 1.14 a default's own variables take the default arity's first versions, so `y` is `a`.
            macro(
                "form:super-default-shared-version",
                """
                def f(a, b \\ (y = 1; y + 1)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            // A `case` clause matches the shared version, as Erlang's does; a fun head and a generator bind afresh.
            macro(
                "form:super-default-shared-version-case",
                """
                def f(a, b \\ (case 7 do y -> y end)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            macro(
                "form:super-default-shared-version-fn",
                """
                def f(a, b \\ :lists.map(fn y -> y end, [1, 2])), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            macro(
                "form:super-default-shared-version-for",
                """
                def f(a, b \\ (for y <- [1, 2], do: y)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            macro(
                "form:super-default-shared-version-for-bitstring",
                """
                def f(a, b \\ (for <<y <- <<1, 2>> >>, do: y)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            macro(
                "form:super-default-shared-version-for-reduce",
                """
                def f(a, b \\ (for _ <- [1, 2], reduce: 0 do y -> y + 1 end)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            // The clause a bitstring generator skips through binds afresh too, and a variable repeated in a fresh
            // pattern matches what the pattern itself bound.
            macro(
                "form:super-default-shared-version-for-bitstring-skip",
                """
                def f(a, b \\ (for <<y, 0 <- <<1, 1, 2, 0>> >>, do: y)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            macro(
                "form:super-default-shared-version-fn-repeated",
                """
                def f(a, b \\ :lists.map(fn {y, y} -> :same; _ -> :diff end, [{1, 1}, {1, 2}])), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            macro(
                "form:super-default-shared-version-for-repeated",
                """
                def f(a, b \\ (for {y, y} <- [{1, 1}, {5, 5}, {1, 2}], do: y)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            // A `rescue` variable is bound by a `=` that heads the clause's body, a lone variable `else` is the last
            // clause of the `case`, and before 1.13 `with y <- e` is `y = e`, so a clash raises as each of those does.
            macro(
                "form:super-default-shared-version-rescue",
                """
                def f(a, b \\ (try do raise "x" rescue y -> y.message end)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            macro(
                "form:super-default-shared-version-with-else",
                """
                def f(a, b \\ (with :x <- 1 do 0 else y -> y end)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            macro(
                "form:super-default-shared-version-with-variable",
                """
                def f(a, b \\ (with y <- 1, do: y)), do: {a, b}
                defmacro m, do: Macro.escape(f(5))
                """,
            ),
            // A macro's argument is marked `stop_generated` where an import or `Macro.expand_once/2` calls it, not a
            // remote call.
            Row(
                "quote:stop-generated",
                STOP_GENERATED,
                "$ROW.m(f())",
                "import $ROW\nm(f())",
                "m(f())",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),
            // `Macro.expand_once/2` gives back a node it can't expand as it was given, its mark kept.
            Row(
                "quote:stop-generated-expanded",
                """
                defmacro m(x) do
                  e = Macro.expand_once(x, __CALLER__)

                  if e == x and Keyword.get(elem(e, 1), :stop_generated, false) do
                    quote(do: def(a, do: 1))
                  else
                    quote(do: def(b, do: 1))
                  end
                end
                """.trimIndent(),
                "$ROW.m(f())",
                "import $ROW\nm(f())",
                "m(f())",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),
            Row(
                "quote:stop-generated-remote",
                STOP_GENERATED,
                "$ROW.m(f())",
                "require $ROW\n$ROW.m(f())",
                "$ROW.m(f())",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),

            // The natives: `erlang`
            macro("pure:append", "defmacro m, do: :erlang.++([1], [2])"),
            macro("pure:append-improper", "defmacro m, do: :erlang.++([1 | 2], [3])"),
            macro("pure:subtract-exact", "defmacro m, do: :erlang.--([1, 1.0], [1])"),
            macro(
                "pure:eq-int-float",
                """defmacro m, do: [:erlang.==(1, 1.0), :erlang."=:="(1, 1.0), :erlang."/="(1, 2), :erlang."=/="(1, 1.0)]""",
            ),
            macro(
                "pure:eq-nested",
                """defmacro m, do: [:erlang.==({1, [2]}, {1, [2.0]}), :erlang."=:="({1, [2]}, {1, [2.0]})]""",
            ),
            macro(
                "pure:compare-term-order",
                """
                defmacro m do
                  [
                    :erlang.<(1, :a),
                    :erlang.<(:a, {1}),
                    :erlang.<({1}, %{}),
                    :erlang.<(%{}, []),
                    :erlang.<([], [1]),
                    :erlang.<([1], "a"),
                    :erlang.<(1, 1.0),
                    :erlang.>=(1.0, 1),
                    :erlang.>({2}, {1, 1}),
                    :erlang."=<"(:b, :a)
                  ]
                end
                """,
            ),
            macro("pure:bool", "defmacro m, do: [:erlang.not(true), :erlang.and(true, false), :erlang.or(false, true)]"),
            macro("pure:bool-badarg", "defmacro m, do: :erlang.and(1, true)"),
            macro(
                "pure:is",
                """
                defmacro m do
                  [
                    :erlang.is_atom(nil),
                    :erlang.is_binary("a"),
                    :erlang.is_integer(1.0),
                    :erlang.is_number(1.0),
                    :erlang.is_tuple({}),
                    :erlang.is_map(%{}),
                    :erlang.is_boolean(nil)
                  ]
                end
                """,
            ),
            macro("pure:is-list-charlist", "defmacro m, do: [:erlang.is_list([97, 98]), :erlang.is_list([1 | 2])]"),
            macro(
                "pure:is-function-2",
                "defmacro m, do: [:erlang.is_function(&Enum.map/2, 2), :erlang.is_function(&Enum.map/2, 1)]",
            ),
            macro(
                "pure:is-function-arity-past-a-long",
                "defmacro m, do: :erlang.is_function(&Enum.map/2, 18_446_744_073_709_551_618)",
            ),
            macro(
                "pure:element",
                "defmacro m, do: [:erlang.element(1, {:a}), :erlang.tuple_size({1, 2}), Macro.escape(:erlang.setelement(1, {:a, :b}, :c))]",
            ),
            macro("pure:element-badarg", "defmacro m, do: :erlang.element(3, {1, 2})"),
            macro("pure:hd-tl", "defmacro m, do: [:erlang.hd([1, 2]), :erlang.tl([1, 2]), :erlang.length([1, 2])]"),
            macro("pure:tl-improper", "defmacro m, do: :erlang.tl([1 | 2])"),
            macro("pure:length-improper", "defmacro m, do: :erlang.length([1 | 2])"),
            macro(
                "pure:map",
                "defmacro m, do: [:erlang.map_size(%{a: 1}), :erlang.map_get(:a, %{a: 1}), :erlang.is_map_key(:b, %{a: 1})]",
            ),
            macro("pure:badmap", "defmacro m, do: :erlang.map_get(:a, 1)"),
            macro("pure:badkey", "defmacro m, do: :erlang.map_get(:b, %{a: 1})"),
            macro(
                "pure:arith",
                "defmacro m, do: [:erlang.rem(7, 2), :erlang.div(7, 2), :erlang.-(1, 3), :erlang.-(2), :erlang.+(2), :erlang.abs(:erlang.-(3))]",
            ),
            macro("pure:arith-float", "defmacro m, do: [:erlang./(4, 2), :erlang.+(1, 2.0), :erlang.*(2, 1.5)]"),
            macro("pure:badarith", "defmacro m, do: :erlang.+(1, :a)"),
            macro("pure:badarith-division", "defmacro m, do: :erlang.div(1, 0)"),
            // `/` converts each integer to a float, which raises for one past the float range.
            macro("pure:badarith-float-conversion", "defmacro m, do: :erlang./(1, 0x1${"0".repeat(275)})"),
            macro(
                "pure:atom-binary",
                """
                defmacro m do
                  [
                    :erlang.atom_to_binary(:a, :utf8),
                    :erlang.binary_to_atom("a", :utf8),
                    :erlang.binary_to_existing_atom("a", :utf8)
                  ]
                end
                """,
            ),
            macro("pure:binary-to-atom-invalid", "defmacro m, do: :erlang.binary_to_atom(1, :utf8)"),
            macro(
                "pure:atom-binary-encoding",
                """
                defmacro m do
                  [
                    :erlang.atom_to_binary(:"é", :latin1),
                    :erlang.binary_to_atom("\\xE9", :latin1),
                    :erlang.atom_to_binary(:"é", :unicode),
                    try do
                      :erlang.atom_to_binary(:a, :foo)
                    rescue
                      ArgumentError -> :badarg
                    end
                  ]
                end
                """,
            ),
            macro(
                "pure:type-tests",
                """
                defmacro m do
                  [
                    :erlang.xor(true, false),
                    :erlang.is_bitstring(<<1::1>>),
                    :erlang.is_float(1.0),
                    :erlang.is_pid(1),
                    :erlang.binary_to_existing_atom("true"),
                    :erlang.byte_size(<<1, 2::4>>),
                    :erlang.bit_size(<<1, 2::4>>)
                  ]
                end
                """,
            ),
            macro("form:exit", "defmacro m, do: :erlang.exit(:x)", pins = DECLINED),
            macro("pure:raise-badarg", "defmacro m, do: [:erlang.raise(:foo, :a, []), :erlang.raise(:error, :a, :bad)]"),
            // `raise_3` takes `{Fun, Args}` and `{M, F, A}` frames, and never reads a frame's arity or arguments.
            macro(
                "pure:raise-frames",
                """
                defmacro m do
                  try do
                    :erlang.raise(:error, :x, [{:m, :f, 1}, {:m, :f, :any, []}, {&List.first/1, [[1]]}])
                  rescue
                    e in ErlangError -> e.original
                  end
                end
                """,
            ),
            macro("pure:raise-bad-location", "defmacro m, do: :erlang.raise(:error, :x, [{:m, :f, 1, :nowhere}])"),
            // `raise_3` stores a `{Fun, Args}` or `{M, F, A}` frame with an empty location, and the first 8 frames.
            macro(
                "pure:raise-short-frames",
                """
                defmacro m do
                  frames = [{:m, :f, 1} | for(arity <- [1, 2, 3, 4, 5, 6, 7, 8], do: {:m, :g, arity, []})]

                  short =
                    try do
                      :erlang.raise(:error, :x, frames)
                    rescue
                      _ -> Macro.escape(__STACKTRACE__)
                    end

                  fun =
                    try do
                      :erlang.raise(:error, :x, [{&List.first/1, [[1]]}])
                    rescue
                      _ -> :erlang.tuple_size(:erlang.hd(__STACKTRACE__))
                    end

                  [short, fun]
                end
                """,
            ),
            // A `fn`'s `function_clause` frame names a function the evaluator doesn't know.
            macro(
                "pure:raise-unknown-frame",
                """
                defmacro m do
                  f = fn :a -> 1 end

                  try do
                    f.(:b)
                  rescue
                    e -> :erlang.raise(:error, e, __STACKTRACE__)
                  end
                end
                """,
                pins = DECLINED,
            ),
            macro(
                "pure:tuple-list",
                "defmacro m, do: [:erlang.tuple_to_list({1, 2}), Macro.escape(:erlang.list_to_tuple([1, 2, 3]))]",
            ),
            macro("pure:list-to-tuple-improper", "defmacro m, do: :erlang.list_to_tuple([1 | 2])"),
            macro(
                "pure:function-exported",
                "defmacro m, do: [:erlang.function_exported(Enum, :map, 2), :erlang.function_exported(Enum, :nope, 0)]",
            ),
            // The VM reads an arity by its low 32 bits, and one past a small integer is no arity.
            macro("pure:function-exported-arity-past-an-int", "defmacro m, do: :erlang.function_exported(Enum, :map, 4_294_967_298)"),
            macro(
                "pure:function-exported-arity-past-a-small-integer",
                "defmacro m, do: :erlang.function_exported(Enum, :map, 576_460_752_303_423_490)",
            ),
            // A macro is exported as `MACRO-<name>`, taking the caller first.
            macro(
                "pure:function-exported-macro",
                """
                defmacro m do
                  [
                    :erlang.function_exported({row}.H, :"MACRO-h", 1),
                    :erlang.function_exported({row}.H, :h, 0),
                    :erlang.function_exported(Kernel, :"MACRO-if", 2)
                  ]
                end
                """,
                helper = DEFINES_A_MACRO,
            ),
            // Which the evaluator doesn't run.
            macro(
                "pure:remote-macro-function",
                """defmacro m, do: :erlang.apply({row}.H, :"MACRO-h", [__CALLER__])""",
                pins = DECLINED,
                helper = DEFINES_A_MACRO,
            ),
            macro(
                "pure:apply",
                "defmacro m, do: [:erlang.apply(Keyword, :get, [[a: 1], :a]), :erlang.apply(fn x -> [x] end, [1])]",
            ),
            macro("pure:apply-badarity", "defmacro m, do: :erlang.apply(fn -> 1 end, [1])"),

            // The natives: `lists`
            macro("pure:reverse", "defmacro m, do: [:lists.reverse([1, 2]), :lists.reverse([1], [2])]"),
            macro("pure:reverse-improper", "defmacro m, do: :lists.reverse([1 | 2])"),
            macro(
                "pure:member",
                "defmacro m, do: [:lists.member(1, [1.0]), :lists.member(1, [1]), :lists.keymember(:a, 1, [{:a, 1}])]",
            ),
            macro("pure:keyfind-int-float", "defmacro m, do: :lists.keyfind(1, 1, [{1.0, :a}])"),
            macro("pure:keydelete", "defmacro m, do: :lists.keydelete(:a, 1, [{:a, 1}, {:b, 2}])"),
            macro("pure:keydelete-badarg", "defmacro m, do: :lists.keydelete(:a, 0, [])"),
            macro("pure:keyfind-position-past-an-int", "defmacro m, do: :lists.keyfind(:a, 4_294_967_297, [{:a, 1}])"),
            macro(
                "pure:keyfind-position-past-a-small-integer",
                "defmacro m, do: :lists.keyfind(:a, 576_460_752_303_423_488, [{:a, 1}])",
            ),
            macro("pure:keydelete-position-past-an-int", "defmacro m, do: :lists.keydelete(:a, 4_294_967_297, [{:a, 1}])"),
            macro(
                "pure:keydelete-position-past-a-long",
                "defmacro m, do: :lists.keydelete(:a, 18_446_744_073_709_551_617, [{:a, 1}])",
            ),
            // From source, an attribute the walk can't read is a value the evaluator doesn't know, which no native takes
            // for a known value of another type.
            macro(
                "pure:keyfind-unknown-element",
                """
                @x (receive do after 0 -> {:a, :first} end)
                defmacro m, do: :lists.keyfind(:a, 1, [@x, {:a, :fallback}])
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "pure:keydelete-unknown-element",
                """
                @x (receive do after 0 -> {:a, :first} end)
                defmacro m, do: :erlang.length(:lists.keydelete(:a, 1, [@x, {:b, 1}]))
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "pure:range-member-unknown",
                """
                @x (receive do after 0 -> 2 end)
                defmacro m, do: Enum.member?(1..3, @x)
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "pure:keyword-merge-unknown-pair",
                """
                @x (receive do after 0 -> {:a, 1} end)
                defmacro m, do: Keyword.merge([@x], b: 2)
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "pure:keyword-merge-unknown-key",
                """
                @k (receive do after 0 -> :a end)
                defmacro m, do: Keyword.merge([{@k, 1}], b: 2)
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "pure:escape-generated-unknown",
                """
                @g (receive do after 0 -> true end)
                defmacro m, do: Macro.escape({:f, [], []}, generated: @g)
                """,
                pins = { source, level -> DECLINED_FROM_SOURCE(source, level)?.takeIf { MACRO_ESCAPE_MARKS_GENERATED.isSufficient(level) } },
            ),
            macro(
                "pure:escape-generated-unknown-list",
                """
                @g (receive do after 0 -> true end)
                defmacro m, do: Macro.escape([1, 2], generated: @g)
                """,
            ),
            // An element the evaluator can't produce may be a `{:generated, _, _}` that `Keyword.get/3` raises on; the
            // debug info holds the attribute's value.
            macro(
                "pure:escape-unknown-option-element",
                """
                @u (receive do after 0 -> {:x, 1} end)
                defmacro m, do: Macro.escape([1, 2], [{:unquote, false}, {:prune_metadata, false}, @u])
                """,
                pins = { source, level -> OPAQUE.takeIf { source == Source.SOURCE && MACRO_ESCAPE_MARKS_GENERATED.isSufficient(level) } },
            ),
            macro(
                "pure:escape-generated-triple",
                """
                defmacro m, do: Macro.escape([1, 2], [{:unquote, false}, {:prune_metadata, false}, {:generated, 1, 2}])
                """,
            ),
            macro(
                "pure:escape-improper-options",
                """
                defmacro m, do: Macro.escape([1, 2], [{:unquote, false}, {:prune_metadata, false} | :x])
                """,
            ),
            macro(
                "pure:env-in-match-unknown-struct",
                """
                @s (receive do after 0 -> Macro.Env end)
                defmacro m, do: Macro.Env.in_match?(%{__struct__: @s, context: :match})
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "pure:dot-unknown-name",
                """
                @n (receive do after 0 -> :b end)
                defmacro m do
                  x = {@n, [], nil}
                  Macro.escape(quote(do: :lists.unquote(x)))
                end
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "pure:access-unknown-struct",
                """
                @s (receive do after 0 -> {row}.S end)
                defmacro m, do: %{__struct__: @s, a: 2}[:a]
                """,
                pins = DECLINED_FROM_SOURCE,
                helper = FETCHES,
            ),
            // A list whose tail isn't known may be proper.
            macro(
                "pure:length-unknown-tail",
                """
                @t (receive do after 0 -> [2] end)
                defmacro m do
                  try do
                    length([1 | @t])
                  rescue
                    ArgumentError -> :bad
                  end
                end
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "pure:match-unknown-tail",
                """
                @t (receive do after 0 -> [2] end)
                defmacro m do
                  case [1 | @t] do
                    [_, b] -> b
                    _ -> :other
                  end
                end
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            // A segment whose value or size isn't known may raise.
            macro(
                "form:bitstring-unknown-segment",
                """
                @v (receive do after 0 -> :bad end)
                defmacro m do
                  v = @v
                  _ = <<v::8>>
                  :ok
                end
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "form:bitstring-unknown-size",
                """
                @s (receive do after 0 -> :bad end)
                defmacro m do
                  s = @s
                  _ = <<1::size(s)>>
                  :ok
                end
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            // `String.Chars`' implementations for a tuple aren't read.
            macro(
                "pure:to-string-unknown",
                """
                defmacro m do
                  w = :erlang.hd([{:a}])
                  _ = "#{w}"
                  :ok
                end
                """,
                pins = DECLINED,
            ),
            macro("pure:append-lists", "defmacro m, do: [:lists.append([[1], [2]]), :lists.append([1], [2])]"),
            macro("pure:append-last-improper", "defmacro m, do: Macro.escape(:lists.append([[1], 2]))"),
            macro("pure:flatten-charlist", "defmacro m, do: :lists.flatten([[97], [98, [99]]])"),
            macro(
                "pure:lists-higher-order",
                """
                defmacro m do
                  [
                    :lists.map(fn x -> [x] end, [1, 2]),
                    :lists.filter(fn x -> :erlang.>(x, 1) end, [1, 2]),
                    :lists.foldl(fn x, acc -> [x | acc] end, [], [1, 2]),
                    Macro.escape(:lists.mapfoldl(fn x, acc -> {[x], :erlang.+(acc, x)} end, 0, [1, 2]))
                  ]
                end
                """,
            ),
            macro("pure:filter-bad-filter", "defmacro m, do: :lists.filter(fn _ -> 1 end, [1])"),
            macro(
                "pure:filter-improper",
                """
                defmacro m do
                  for keep <- [:x, true] do
                    try do
                      :lists.filter(fn _ -> keep end, [1 | 2])
                    rescue
                      e in ErlangError -> e.original
                    end
                  end
                end
                """,
            ),
            macro(
                "pure:maps-update",
                """
                defmacro m do
                  [
                    Macro.escape(:maps.update(:a, 2, %{a: 1})),
                    try do
                      :maps.update(:b, 2, %{a: 1})
                    rescue
                      e in KeyError -> e.key
                    end
                  ]
                end
                """,
            ),
            macro("pure:mapfoldl-badmatch", "defmacro m, do: :lists.mapfoldl(fn x, _ -> x end, 0, [1])", pins = LISTS_ERROR_BELOW_OTP_25),
            macro("pure:mapfoldl-not-list", "defmacro m, do: :lists.mapfoldl(fn x, a -> {x, a} end, 0, :x)", pins = LISTS_ERROR_BELOW_OTP_25),
            macro("pure:lists-not-fun", "defmacro m, do: :lists.map(:x, [1])", pins = LISTS_ERROR_BELOW_OTP_25),
            macro("pure:foreach", "defmacro m, do: :lists.foreach(fn x -> x end, [1, 2])"),
            // Each iteration's definitions read the attribute as it is in that iteration.
            macro(
                "body:for-attribute-per-iteration",
                """
                for x <- [1, 2] do
                  @v x
                  defmacro unquote(:"m#{x}")(), do: @v
                  def unquote(:"f#{x}")(), do: @v
                end

                defmacro m, do: [m1(), m2(), f1()]
                """,
            ),
            macro("pure:foreach-not-a-fun", "defmacro m, do: :lists.foreach(:x, [1])", pins = LISTS_ERROR_BELOW_OTP_25),
            macro(
                "pure:lists-function-clause",
                """
                defmacro m do
                  try do
                    :lists.map(fn x -> x end, [1 | 2])
                  rescue
                    e in FunctionClauseError -> Macro.escape({e.module, e.function, e.arity})
                  end
                end
                """,
                pins = LISTS_ERROR_BELOW_OTP_25,
            ),
            macro(
                "pure:all",
                "defmacro m, do: [:lists.all(fn x -> :erlang.is_atom(x) end, [:a, :b]), :lists.all(fn x -> :erlang.is_atom(x) end, [:a, 1])]",
            ),
            macro("pure:all-case-clause", "defmacro m, do: :lists.all(fn _ -> 1 end, [1])", pins = LISTS_ERROR_BELOW_OTP_25),
            // Ascending and descending runs.
            macro(
                "pure:sort",
                """
                defmacro m do
                  for l <- [[], [5], [4, 1, 3, 2], [:a, 4, 3, :b, 9], [1, 1, 0], [3, 1, 2, 1, 5, 4, 0, 2, 7, 6],
                            [9, 8, 7, 1, 2, 3, 6, 5, 4, 4], [{:g, 2}, {:f, 1}, {:f, 0}]] do
                    :lists.sort(l)
                  end
                end
                """,
            ),
            // Elements that compare equal, which keep their order.
            macro(
                "pure:sort-equal",
                """
                defmacro m do
                  for l <- [[1.0, 1], [1, 1.0], [1, 1.0, 1, 0], [3, 1, 2, 1.0, 5, 4, 0, 2.0, 7, 6], [9, 8, 7, 1, 2, 3, 6, 5, 4.0, 4]] do
                    :lists.sort(l)
                  end
                end
                """,
                pins = SORT_BEFORE_ITS_STABILITY_FIX,
            ),
            // An improper tail raises in the helper that meets it, with the runs split so far.
            macro(
                "pure:sort-improper",
                """
                defmacro m do
                  for l <- [:x, [{:a, 1} | :x], [1, 2 | :x], [2, 1 | :x], [1, 1 | :x], [1, 1, 1 | :x], [1, 3, 2 | :x],
                            [3, 1, 2 | :x], [1, 2, 3, 0, 5 | :x], [5, 4, 3, 6, 1 | :x], [1, 2, 0, 3, 1.0 | :x]] do
                    try do
                      :lists.sort(l)
                    rescue
                      e in FunctionClauseError -> Macro.escape({e.module, e.function, e.arity, e.args})
                    end
                  end
                end
                """,
                pins = SORT_BEFORE_ITS_STABILITY_FIX,
            ),

            // The natives: `maps`
            macro(
                "pure:maps",
                """
                defmacro m do
                  [
                    :maps.get(:a, %{a: 1}),
                    :maps.get(:b, %{a: 1}, :d),
                    Macro.escape(:maps.find(:a, %{a: 1})),
                    :maps.find(:b, %{a: 1}),
                    :maps.is_key(:a, %{a: 1}),
                    Macro.escape(:maps.put(:b, 2, %{a: 1})),
                    Macro.escape(:maps.merge(%{a: 1}, %{a: 2, b: 3})),
                    Macro.escape(:maps.from_list([b: 1, a: 2])),
                    Macro.escape(:maps.to_list(%{2 => :b, 1 => :a}))
                  ]
                end
                """,
                unordered = true,
            ),
            macro(
                "pure:maps-to-list-atom-keys",
                "defmacro m, do: :maps.to_list(%{b: 1, a: 2})",
                legs = { !MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER.isSufficient(it) },
            ),
            // Elixir's order is the atoms' index in the VM, so only the set is compared.
            macro(
                "pure:maps-to-list-atom-keys-sorted",
                "defmacro m, do: :lists.sort(:maps.to_list(%{b: 1, a: 2}))",
                legs = { MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER.isSufficient(it) },
            ),
            macro("pure:maps-badmap", "defmacro m, do: :maps.get(:a, 1)"),
            macro("pure:maps-badkey", "defmacro m, do: :maps.get(:b, %{a: 1})"),
            macro("pure:maps-from-list-badarg", "defmacro m, do: :maps.from_list([1])"),

            // The natives: loading
            macro("native:ensure-loaded-loaded", "defmacro m, do: Macro.escape(:code.ensure_loaded(Enum))"),
            // The quoter's release runs in embedded mode, which answers `{:error, :embedded}`; a Mix compile is interactive.
            macro(
                "native:ensure-loaded-nofile",
                "defmacro m, do: Macro.escape(:code.ensure_loaded({row}.Missing))",
                pins = { _, _ -> "{:error, :nofile}" },
            ),
            macro("native:ensure-loaded-function-clause", "defmacro m, do: :code.ensure_loaded(1)"),
            macro("native:whereis-of-non-atom", "defmacro m, do: (_ = :erlang.whereis(123); :ok)"),
            // From source, a module whose definitions aren't known can't be read.
            macro(
                "native:ensure-loaded-unreadable",
                "defmacro m, do: Macro.escape(:code.ensure_loaded({row}.U))",
                pins = DECLINED_FROM_SOURCE,
                helper = """
                defmodule {row}.U do
                  def unquote(Application.get_env(:app, :name, :g))(), do: 1
                end
                """,
            ),
            // From source, a module the run compiled has no beam to read its attributes from.
            macro(
                "native:info-attributes",
                "defmacro m, do: Macro.escape({row}.A.__info__(:attributes))",
                pins = DECLINED_FROM_SOURCE,
                helper = """
                defmodule {row}.A do
                  Module.register_attribute(__MODULE__, :k, persist: true)
                  @k 1
                  def f, do: 1
                end
                """,
            ),
            // An Erlang module has no `__info__/1`.
            macro("native:info-attributes-erlang", "defmacro m, do: Macro.escape(:lists.__info__(:attributes))", pins = DECLINED),

            // The natives: warnings
            macro("native:io-warn-1", "defmacro m, do: IO.warn(\"w\")"),
            macro("native:io-warn-empty", "defmacro m, do: IO.warn(\"w\", [])"),
            macro("native:io-warn-env", "defmacro m, do: IO.warn(\"w\", __CALLER__)", pins = DECLINED),
            macro("native:io-warn-keyword", "defmacro m, do: IO.warn(\"w\", file: \"a.ex\", line: 1)", pins = DECLINED),
            macro("native:io-warn-chardata", "defmacro m, do: IO.warn([?w], [])", pins = DECLINED),
            macro("native:io-warn-function-clause", "defmacro m, do: IO.warn(\"w\", :x)"),

            // The natives: text
            macro("native:iolist-to-binary", "defmacro m, do: :erlang.iolist_to_binary([\"a\", [98, \"c\"], 255 | \"d\"])"),
            macro("native:iolist-to-binary-badarg", "defmacro m, do: :erlang.iolist_to_binary([256])"),
            macro(
                "native:characters-to-binary",
                "defmacro m, do: :unicode.characters_to_binary([\"a\", [128512, 98], \"\\u00e9\" | \"c\"])",
            ),
            macro(
                "native:characters-to-binary-error",
                "defmacro m, do: Macro.escape(:unicode.characters_to_binary([97, 55296]))",
                pins = DECLINED,
            ),
            macro(
                "native:characters-to-binary-incomplete",
                "defmacro m, do: Macro.escape(:unicode.characters_to_binary(<<97, 195>>))",
                pins = DECLINED,
            ),
            macro("native:characters-to-binary-badarg", "defmacro m, do: :unicode.characters_to_binary(:a)"),
            // The term's shape is checked before any of it is encoded.
            macro("native:characters-to-binary-badarg-after-error", "defmacro m, do: :unicode.characters_to_binary([<<255>>, :a])"),
            // The check stops at an integer too large for a small one, which gives an error whatever follows it.
            macro(
                "native:characters-to-binary-bignum-then-badarg",
                "defmacro m, do: Macro.escape(:unicode.characters_to_binary([:erlang.bsl(1, 64), :a]))",
                pins = DECLINED,
            ),
            macro("native:characters-to-binary-split", "defmacro m, do: :unicode.characters_to_binary([<<195>>, <<169>>])"),
            // Three parts that share one 8 MiB binary, and a list of 2^21 empty lists, whose parts share one another.
            macro(
                "native:iolist-to-binary-past-the-bitstring-limit",
                """
                defmacro m, do: byte_size(:erlang.iolist_to_binary(List.duplicate(grow("a", 23), 3)))

                defp grow(b, 0), do: b
                defp grow(b, n), do: grow(b <> b, n - 1)
                """,
                pins = DECLINED,
            ),
            macro(
                "native:characters-to-binary-past-the-bitstring-limit",
                """
                defmacro m, do: byte_size(:unicode.characters_to_binary(List.duplicate(grow("a", 23), 3)))

                defp grow(b, 0), do: b
                defp grow(b, n), do: grow(b <> b, n - 1)
                """,
                pins = DECLINED,
            ),
            macro(
                "native:iolist-to-binary-past-the-node-limit",
                """
                defmacro m, do: :erlang.iolist_to_binary(nest([], 21))

                defp nest(x, 0), do: x
                defp nest(x, n), do: nest([x, x], n - 1)
                """,
                pins = DECLINED,
            ),
            macro(
                "native:characters-to-binary-past-the-node-limit",
                """
                defmacro m, do: :unicode.characters_to_binary(nest([], 21))

                defp nest(x, 0), do: x
                defp nest(x, n), do: nest([x, x], n - 1)
                """,
                pins = DECLINED,
            ),
            macro(
                "native:bitwise",
                """
                defmacro m do
                  [
                    :erlang.band(12, -6),
                    :erlang.bor(12, 10),
                    :erlang.bxor(-12, 10),
                    :erlang.bnot(5),
                    :erlang.bsl(1, 70),
                    :erlang.bsl(-5, -1),
                    :erlang.bsr(-9, 1),
                    :erlang.bsr(-5, :erlang.bsl(1, 100)),
                    :erlang.bsl(0, :erlang.bsl(1, 100))
                  ]
                end
                """,
            ),
            macro("native:bitwise-badarith", "defmacro m, do: :erlang.band(1, 1.0)"),
            macro("native:bnot-badarith", "defmacro m, do: :erlang.bnot(:a)"),
            macro("native:bsl-system-limit", "defmacro m, do: :erlang.bsl(1, :erlang.bsl(1, 64))"),
            // Past the integer size limit from OTP 26.0.2, within the one before it; below 26.0.2 it depends on the patch release.
            macro(
                "native:bsl-between-limits",
                "defmacro m, do: :erlang.bsl(1, 5_000_000) > 0",
                pins = { _, level -> OPAQUE.takeUnless { INTEGERS_UP_TO_4_MBIT.isSufficient(level) } },
            ),
            macro("native:binary-part", """defmacro m, do: [:erlang.binary_part("abc", 3, -2), :erlang.binary_part("abc", 0, 0)]"""),
            macro("native:binary-part-badarg", """defmacro m, do: :erlang.binary_part("abc", 1, 3)"""),
            macro("native:binary-part-bitstring", "defmacro m, do: :erlang.binary_part(<<1, 2::4>>, 0, 1)"),
            macro("native:binary-part-bitstring-tail", "defmacro m, do: :erlang.binary_part(<<1, 2::4>>, 1, 1)"),
            macro(
                "native:gc-list",
                "defmacro m, do: Macro.escape(for l <- [[], [97], [97, 98], [13, 10, 120], [97, 769, 98]], do: :unicode_util.gc(l))",
            ),
            // A cluster that ends the binary: before OTP 25.0-rc3, an empty tail is dropped.
            macro(
                "native:gc-binary",
                """
                defmacro m do
                  for b <- [<<>>, "a", "ab", "a\u0301b", "\r\n", "\r\nx", "\u00e9a", "\u00e9", "p"] do
                    Macro.escape(:unicode_util.gc(b))
                  end
                end
                """,
            ),
            macro("native:gc-control", "defmacro m, do: Macro.escape(for b <- [\"\\n\", \"\\r\"], do: :unicode_util.gc(b))"),
            // U+0600, a Prepend, which ends only a cluster of Prepends.
            macro(
                "native:gc-prepend",
                """
                defmacro m do
                  for b <- [<<216, 128>>, <<216, 128, 216, 128>>, <<216, 128, 97>>, <<216, 128, 10>>] do
                    Macro.escape(:unicode_util.gc(b))
                  end
                end
                """,
            ),
            macro(
                "native:gc-invalid-first",
                """
                defmacro m do
                  for b <- [<<255>>, <<255, 97>>, <<195>>, <<237, 160, 128>>], do: Macro.escape(:unicode_util.gc(b))
                end
                """,
            ),
            macro("native:gc-invalid-later", "defmacro m, do: Macro.escape(:unicode_util.gc(<<97, 204, 129, 98, 255>>))"),
            // Clusters of more than 64 bytes, and one followed by a code point that straddles the 64th.
            macro(
                "native:gc-long-cluster",
                """
                defmacro m do
                  for b <- ["e${ACUTE.repeat(40)}x", "e${ACUTE.repeat(40)}", "e${ACUTE.repeat(31)}\u00e9"] do
                    Macro.escape(:unicode_util.gc(b))
                  end
                end
                """,
            ),
            macro(
                "native:gc-invalid-after-long-cluster",
                "defmacro m, do: Macro.escape(:unicode_util.gc(\"e${ACUTE.repeat(40)}\" <> <<255>>))",
                pins = DECLINED,
            ),
            macro(
                "native:gc-invalid-after-cluster",
                "defmacro m, do: Macro.escape(:unicode_util.gc(<<97, 204, 129, 255>>))",
                pins = DECLINED,
            ),
            macro(
                "native:gc-invalid-after-code-point",
                "defmacro m, do: Macro.escape(:unicode_util.gc(<<97, 255>>))",
                pins = DECLINED,
            ),
            macro("native:gc-nested", "defmacro m, do: Macro.escape(:unicode_util.gc([[97], 98]))", pins = DECLINED),
            macro("native:gc-binary-tail", "defmacro m, do: Macro.escape(:unicode_util.gc([13 | \"x\"]))", pins = DECLINED),
            macro("native:gc-binary-element", "defmacro m, do: Macro.escape(:unicode_util.gc([97, \"b\"]))", pins = DECLINED),
            macro("native:gc-surrogate", "defmacro m, do: Macro.escape(:unicode_util.gc([55296]))", pins = DECLINED),
            macro("native:gc-not-a-code-point", "defmacro m, do: Macro.escape(:unicode_util.gc([1114112]))", pins = DECLINED),

            // `Macro.expand/2` in the caller's env with only `:function` changed
            macro(
                "env:expand-function",
                """
                defmacro k, do: :k
                defmacro m, do: Macro.expand(quote(do: {row}.k()), %{__CALLER__ | function: {:g, 0}})
                """,
            ),
            macro(
                "env:expand-function-read",
                """
                defmacro f, do: Macro.escape(__CALLER__.function)
                defmacro m, do: Macro.expand(quote(do: {row}.f()), %{__CALLER__ | function: {:g, 0}})
                """,
            ),
            macro(
                "env:expand-function-nil",
                """
                defmacro f, do: Macro.escape(__CALLER__.function)
                defmacro m, do: Macro.expand(quote(do: {row}.f()), %{__CALLER__ | function: nil})
                """,
            ),
            macro(
                "env:expand-function-map-put",
                """
                defmacro f, do: Macro.escape(__CALLER__.function)
                defmacro m, do: Macro.expand(quote(do: {row}.f()), Map.put(__CALLER__, :function, {:g, 1}))
                """,
            ),
            macro(
                "env:expand-function-other-key",
                """
                defmacro f, do: Macro.escape(__CALLER__.function)
                defmacro m, do: Macro.expand(quote(do: {row}.f()), %{__CALLER__ | line: 7})
                """,
                pins = DECLINED,
            ),
            // The quoted call has no line: from 1.17 the macro runs at the env's, before at the call's.
            macro(
                "env:expand-caller-line",
                """
                defmacro l, do: __CALLER__.line == 0
                defmacro m, do: Macro.expand(quote(do: {row}.l()), __CALLER__)
                """,
            ),

            // The natives: paths, as the host's OS gives them, which is how the Elixir these rows are compared with answers;
            // the other OSes are pinned by `testAnOsType*` and `FilenameTest`.
            macro(
                "native:binary-split",
                """
                defmacro m do
                  Macro.escape([
                    :binary.split("/a//b/c", "/", [:global]),
                    :binary.split("a/b/c", "/", []),
                    :binary.split("a/b\\c", ["/", "\\"], [:global]),
                    :binary.split("abc", "x", [:global]),
                    :binary.split("", "/", [:global]),
                    :binary.split("aaa", "aa", [:global]),
                    :binary.split("abcd", ["ab", "abc"], [])
                  ])
                end
                """,
            ),
            macro("native:binary-split-trim", "defmacro m, do: Macro.escape(:binary.split(\"a/b/\", \"/\", [:trim]))", pins = DECLINED),
            macro("native:binary-split-list-subject", "defmacro m, do: Macro.escape(:binary.split(~c\"a/b\", \"/\", []))", pins = DECLINED),
            macro(
                "native:filename-dirname",
                """
                defmacro m do
                  Macro.escape([
                    :filename.dirname("/a/b/c.ex"),
                    :filename.dirname("c.ex"),
                    :filename.dirname("/c.ex"),
                    :filename.dirname("/a/b/"),
                    :filename.dirname("/a//b/c.ex"),
                    :filename.dirname("c:/a/c.ex"),
                    :filename.dirname(""),
                    :filename.dirname("/")
                  ])
                end
                """,
            ),
            macro("native:filename-dirname-list", "defmacro m, do: Macro.escape(:filename.dirname(~c\"/a/b\"))", pins = DECLINED),
            macro(
                "native:filename-basename",
                """
                defmacro m do
                  Macro.escape([
                    :filename.basename("/a/b/c.ex"),
                    :filename.basename("/a/b/"),
                    :filename.basename("c"),
                    :filename.basename(""),
                    :filename.basename("/"),
                    :filename.basename("/a/b/c.ex", ".ex"),
                    :filename.basename("/a/b/c.exs", ".ex"),
                    :filename.basename("/a/x", ".long"),
                    :filename.basename("/a/b/c.ex", ~c".ex"),
                    :filename.basename("/a/b/", ""),
                    :filename.basename(".ex", ".ex")
                  ])
                end
                """,
            ),
            macro("native:filename-basename-list", "defmacro m, do: Macro.escape(:filename.basename(~c\"/a/b\"))", pins = DECLINED),
            macro("native:filename-basename-list-name", "defmacro m, do: Macro.escape(:filename.basename(~c\"/a/b.ex\", \".ex\"))", pins = DECLINED),
            macro(
                "native:filename-split",
                """
                defmacro m do
                  Macro.escape([
                    :filename.split("/src/lib/t.eex"),
                    :filename.split("a//b/"),
                    :filename.split(""),
                    :filename.split("/"),
                    :filename.split("rel/x"),
                    :filename.split("c:/a\\b"),
                    :filename.split("//server/share/a.ex")
                  ])
                end
                """,
            ),
            macro("native:filename-split-list", "defmacro m, do: Macro.escape(:filename.split(~c\"/a/b\"))", pins = DECLINED),
            // The natives: Elixir's functions
            macro(
                "pure:keyword",
                """
                defmacro m do
                  k = [a: 1, b: 2, a: 3]

                  [
                    Keyword.get(k, :a),
                    Keyword.get(k, :c, :d),
                    Macro.escape(Keyword.fetch(k, :b)),
                    Keyword.fetch(k, :c),
                    Keyword.fetch!(k, :b),
                    Keyword.has_key?(k, :a),
                    Keyword.keyword?(k),
                    Keyword.keyword?([1]),
                    Keyword.delete(k, :a),
                    Keyword.put(k, :a, 4),
                    Keyword.put_new(k, :a, 4),
                    Keyword.put_new(k, :c, 4),
                    Keyword.merge(k, a: 5, d: 6),
                    Keyword.take(k, [:a]),
                    Keyword.drop(k, [:a]),
                    Macro.escape(Keyword.pop(k, :a)),
                    Macro.escape(Keyword.pop(k, :c, :d))
                  ]
                end
                """,
            ),
            macro("pure:keyword-has-key-1-tuple", "defmacro m, do: Keyword.has_key?([{:a}], :a)"),
            macro("pure:keyword-fetch-bang-keyerror", "defmacro m, do: Keyword.fetch!([], :a)"),
            macro(
                "pure:enum",
                """
                defmacro m do
                  [
                    Enum.map([1, 2], fn x -> [x] end),
                    Enum.flat_map([1, 2], fn x -> [x, x] end),
                    Enum.filter([1, 2], fn x -> :erlang.>(x, 1) end),
                    Enum.reject([1, 2], fn x -> :erlang.>(x, 1) end),
                    Enum.reduce([1, 2], 0, fn x, acc -> :erlang.+(x, acc) end),
                    Enum.any?([false, nil]),
                    Enum.any?([1, 2], fn x -> :erlang.>(x, 1) end),
                    Enum.all?([1, nil]),
                    Enum.all?([1, 2], fn x -> :erlang.>(x, 0) end),
                    Enum.member?([1, 2], 2),
                    Enum.concat([1], [2]),
                    Enum.reverse([1, 2])
                  ]
                end
                """,
            ),
            macro(
                "pure:enum-range",
                "defmacro m, do: [Enum.map(1..3, fn x -> x end), Enum.reverse(1..3), Enum.member?(1..3, 2)]",
            ),
            macro(
                "pure:enum-range-lazy",
                "defmacro m, do: [Enum.member?(1..1_000_000_000, 1), Enum.any?(1..1_000_000_000), Enum.all?(1..3)]",
            ),
            macro("pure:enum-map", "defmacro m, do: Enum.map(%{a: 1}, fn {k, _} -> k end)"),
            macro(
                "pure:function-clause-arity",
                """
                defmacro m do
                  for f <- [fn -> Module.concat([1]) end, fn -> Keyword.get(1, :a) end] do
                    try do
                      f.()
                    rescue
                      e in FunctionClauseError -> Macro.escape({e.module, e.function, e.arity})
                    end
                  end
                end
                """,
            ),
            macro(
                "pure:access",
                """
                defmacro m do
                  [
                    Access.get([a: 1], :a),
                    Access.get(%{a: 1}, :a),
                    Access.get(%{a: 1}, :b, :d),
                    Access.get(nil, :a)
                  ]
                end
                """,
            ),
            macro("pure:access-integer-key", "defmacro m, do: Access.get([a: 1], 0)"),
            macro(
                "pure:env-context",
                "defmacro m, do: [Macro.Env.in_guard?(__CALLER__), Macro.Env.in_match?(__CALLER__)]",
            ),
            macro(
                "pure:concat",
                """defmacro m, do: [Module.concat(Foo, Bar), Module.concat([Foo, "Bar"]), Module.concat("Foo", :bar)]""",
            ),
            macro("pure:concat-empty", "defmacro m, do: Module.concat([])"),
            macro(
                "pure:concat-elixir",
                """defmacro m, do: [Module.concat(Elixir, Foo), Module.concat(["Elixir.Foo", Bar])]""",
            ),
            macro("pure:concat-leading-dot", """defmacro m, do: Module.concat(["Foo", ".Bar"])"""),
            macro("pure:concat-invalid-utf8", """defmacro m, do: Module.concat(["\\xFF"])"""),
            macro("pure:to-string", "defmacro m, do: [String.Chars.to_string(:a), String.Chars.to_string(1)]"),
            // `inspect`'s output is `Inspect`'s implementations', which aren't read.
            macro(
                "pure:inspect",
                """defmacro m, do: [Kernel.inspect(:a), Kernel.inspect([1, "b"]), Kernel.inspect(:c, [])]""",
                pins = DECLINED,
            ),
            // `inspect/2`'s options must be a list, and a list of them declines, as `Inspect.Opts` isn't read.
            macro("pure:inspect-options-not-a-list", "defmacro m, do: :erlang.element(2, {Kernel.inspect(:a, 1), :ok})"),
            macro(
                "pure:inspect-options",
                "defmacro m, do: :erlang.element(2, {Kernel.inspect(:a, pretty: 1), :ok})",
                pins = DECLINED,
            ),
            macro("pure:inspect-compared", "defmacro m, do: :erlang.==(Kernel.inspect(:a), Kernel.inspect(:b))", pins = DECLINED),
            // A segment with no size takes any binary whole, whatever its bytes; with a unit past a byte, its bytes decide.
            macro(
                "form:bitstring-unknown-binary",
                """
                defmacro m do
                  _ = <<Kernel.inspect(:a)::binary, "!">>
                  _ = <<Kernel.inspect(:a)::bitstring, 1::1>>
                  :ok
                end
                """,
            ),
            macro(
                "form:bitstring-unknown-binary-unit",
                """
                defmacro m do
                  _ = <<Kernel.inspect(:a)::binary-unit(16)>>
                  :ok
                end
                """,
                pins = DECLINED,
            ),
            // An `unquote` checks that its value is code, which any binary is, whatever its bytes.
            macro(
                "quote:unquote-unknown-binary",
                """
                defmacro m do
                  _ = quote(do: unquote(Kernel.inspect(:a)))
                  _ = quote(do: [unquote_splicing([Kernel.inspect(:a)])])
                  :ok
                end
                """,
            ),
            // A binary whose bytes aren't known is no boolean, number or fun, and is a quote's file.
            macro(
                "pure:type-tests-unknown-binary",
                """
                defmacro m do
                  x = Kernel.inspect(:a)
                  raised = fn f ->
                    try do
                      f.()
                    rescue
                      e -> e.__struct__
                    end
                  end

                  [
                    is_boolean(x),
                    is_function(x),
                    is_function(x, 1),
                    quote(file: x, do: :ok),
                    raised.(fn -> not x end),
                    raised.(fn -> +x end),
                    raised.(fn -> :erlang.raise(:error, :x, [{x, []}]) end)
                  ]
                end
                """,
            ),
            macro("pure:to-string-interpolated", """defmacro m(name), do: :"#{name}?"""", "m(:a)"),
            macro(
                "pure:escape-generated",
                "defmacro m, do: [Macro.escape(1, generated: true), Kernel.elem(Macro.escape({:a, :b, :c}, generated: true), 1)]",
            ),
            macro("pure:atom-to-string", "defmacro m, do: [Atom.to_string(:a), Atom.to_string(Foo)]"),
            macro("pure:to-atom", """defmacro m, do: [String.to_atom("a"), String.to_existing_atom("a")]"""),
            // Whether an atom the evaluation hasn't seen exists is the VM's to say.
            macro(
                "pure:to-existing-atom",
                """defmacro m, do: String.to_existing_atom("interpreter_never_an_atom")""",
                pins = DECLINED,
            ),
            macro("pure:to-existing-atom-unseen", "defmacro m(s), do: String.to_existing_atom(s)", "m(\"ok\")", pins = DECLINED),
            macro(
                "pure:elem",
                "defmacro m, do: [Kernel.elem({:a, :b}, 1), Macro.escape(Kernel.put_elem({:a, :b}, 0, :c))]",
            ),
            macro("pure:elem-badarg", "defmacro m, do: Kernel.elem({1}, 1)"),
            macro("pure:info", "defmacro m, do: [String.__info__(:module), String.module_info(:module)]", pins = DECLINED),
            macro("pure:enum-info", "defmacro m, do: :erlang.length(Enum.module_info())", pins = DECLINED),
            macro(
                "pure:deep-recursion",
                """
                defmacro m, do: count(100_000)

                defp count(0), do: 0
                defp count(n), do: count(n - 1)
                """,
                pins = DECLINED,
            ),
            macro(
                "pure:binary-prefix",
                """
                defmacro m do
                  [
                    case Atom.to_string(Foo) do
                      "Elixir." <> rest -> rest
                      _ -> :no
                    end,
                    (<<"ab", r::binary>> = "abc"; r),
                    for(<<"a", c::8 <- "abac">>, do: c),
                    (<<"é"::utf8, x::8>> = <<195, 169, 1>>; x)
                  ]
                end
                """,
            ),
            macro("pure:utf-literal", """defmacro m, do: <<"é"::utf8, "a"::utf16>>"""),
            macro("pure:float-overflow", "defmacro m(x), do: <<x::32-float>>", "m(1.0e300)"),
            macro(
                "pure:float-overflow-bignum",
                "defmacro m(n), do: <<n::64-float>>",
                "m(${BigInteger.ONE.shiftLeft(1100)})",
            ),
            macro("pure:utf-overflow", "defmacro m(c), do: <<c::utf8>>", "m(4294967361)"),
            macro("pure:utf-negative", "defmacro m(c), do: <<-c::utf8>>", "m(1)"),
            macro(
                "pure:bitstring-little-odd",
                "defmacro m, do: Macro.escape([<<0x123::12-little>>, (<<x::12-little, _::4>> = <<35, 16>>; x)])",
            ),
            // A fun in a macro's result is converted where expansion reaches it, so a macro it is passed to gets the fun.
            Row(
                "escape:fun-in-result",
                """
                defmacro inner(f) do
                  if is_function(f), do: quote(do: def(a, do: 1)), else: quote(do: def(b, do: 1))
                end

                defmacro outer, do: {{:., [], [__MODULE__, :inner]}, [], [&String.upcase/1]}
                """.trimIndent(),
                "$ROW.outer()",
                "require $ROW\n$ROW.outer()",
                "$ROW.outer()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),
            macro(
                "form:macro-expand",
                "defmacro m(x, y), do: {Macro.expand_once(x, __CALLER__), Macro.expand(y, __CALLER__)}",
                "m(unless(false, do: :x), __MODULE__)",
            ),
            // The expanded call is on line 1, so its env is `__CALLER__`; the case's isn't, and an env with another line
            // declines.
            macro(
                "form:macro-expand-env",
                "defmacro m(x), do: Macro.expand(x, %{__CALLER__ | line: 1})",
                "m(__MODULE__)",
                ends = { "stopped at `$ROW.m(__MODULE__)`" },
            ),
            // The read's line is the caller's, which differs between Elixir's call and the case's.
            macro(
                "form:macro-expand-attribute",
                """
                defmacro m do
                  {call, meta, [module, name, _line | rest]} = Macro.expand(quote(do: @a), __CALLER__)
                  {call, meta, [module, name, 0 | rest]}
                end
                """,
            ),
            // `do_expand_once/2` reads only the top node, so a term inside it that isn't AST doesn't stop the expansion.
            macro(
                "form:macro-expand-holding-a-term",
                "defmacro m, do: elem(Macro.expand(quote(do: unless(unquote(&List.first/1), do: 1)), __CALLER__), 0)",
            ),
            // Each call-back's evaluation makes references no other has made.
            macro(
                "form:macro-expand-fresh-reference",
                """
                defmacro r, do: make_ref()

                defmacro m do
                  r = quote(do: {row}.r())
                  Macro.expand_once(r, __CALLER__) == Macro.expand_once(r, __CALLER__)
                end
                """,
            ),
            // `alias`, `import` and `require` are their module, and a later node is dispatched as their expansion saw it.
            macro(
                "lexical:alias",
                """
                defmacro m do
                  x = alias(Enum, as: E)
                  {x, E}
                end
                """,
            ),
            macro(
                "lexical:import",
                """
                defmacro m do
                  x = import(Enum, only: [count: 1])
                  {x, count([1, 2])}
                end
                """,
            ),
            macro(
                "lexical:require",
                """
                defmacro m do
                  require Integer
                end
                """,
            ),
            macro(
                "lexical:multi-alias",
                """
                defmacro m do
                  alias Elixir.{Enum, List}
                end
                """,
            ),
            macro("form:env-field", "defmacro m, do: __ENV__.function"),
            // A key the env lacks is the call on the escaped env, which raises `KeyError` when it runs.
            macro(
                "form:env-field-missing",
                """
                defmacro m do
                  __ENV__.foo
                  1
                end
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            // `elixir_fn:capture/4` leaves a capture of a function, or else the `fn` it rewrites the capture to.
            macro("form:capture-remote-of-arguments", "defmacro m, do: (&length(&1)).([1, 2])"),
            macro(
                "form:capture-local-of-arguments",
                """
                def f(x), do: x
                defmacro m, do: (&f(&1)).(2)
                """,
            ),
            macro("form:capture-expression", "defmacro m, do: (&(&1 + 1)).(1)"),
            macro("form:capture-tuple", "defmacro m, do: (&{&1, 2}).(1)"),
            macro("form:capture-list", "defmacro m, do: (&[&1 | &2]).(1, [2])"),
            macro(
                "form:capture-variable-module",
                """
                defmacro m do
                  mod = :lists
                  {(&mod.reverse/1).([1, 2]), (&mod.reverse(&1)).([1, 2])}
                end
                """,
            ),
            // The helper places its argument in two scopes, which dispatch its call differently: each placement is
            // dispatched as its own scope's.
            macro(
                "form:argument-in-two-scopes",
                """
                require {row}.H

                defmacro m, do: {{row}.H.pick(true, length([1, 2])), {row}.H.pick(false, length([1, 2]))}
                """,
                helper = """
                    defmodule {row}.Foo do
                      def length(_), do: :foo
                    end

                    defmodule {row}.H do
                      defmacro pick(c, a) do
                        quote do
                          if unquote(c) do
                            import Kernel, except: [length: 1]
                            import {row}.Foo
                            unquote(a)
                          else
                            unquote(a)
                          end
                        end
                      end
                    end
                """,
            ),
            macro(
                "form:argument-in-an-uncalled-scope",
                """
                require {row}.H

                defmacro m, do: elem({row}.H.pair(length([1, 2])), 1)
                """,
                helper = """
                    defmodule {row}.Foo do
                      def length(_), do: :foo
                    end

                    defmodule {row}.H do
                      defmacro pair(a) do
                        quote do
                          {fn ->
                             import Kernel, except: [length: 1]
                             import {row}.Foo
                             unquote(a)
                           end, unquote(a)}
                        end
                      end
                    end
                """,
            ),
            macro(
                "try:rescue-badarg-message",
                """
                defmacro m do
                  try do
                    :erlang.binary_to_atom(1, :utf8)
                  rescue
                    e -> Exception.message(e)
                  end
                end
                """,
                pins = { _, level ->
                    "\"argument error\"".takeIf { RESCUED_ERROR_MESSAGE_FROM_ERROR_INFO.isSufficient(level) }
                },
            ),
            macro(
                "try:rescue-list-stacktrace",
                """
                defmacro m do
                  try do
                    Access.get("foo", 0)
                  rescue
                    x in [FunctionClauseError, CaseClauseError] -> [x.module, x.function, x.arity]
                  end
                end
                """,
            ),

            // `use` of library macros
            use(
                "use:bind-quoted",
                """
                @callback validate_config(keyword) :: :ok
                def validate_config(_required, _config), do: :ok
                def validate_dependency(_deps), do: :ok

                defmacro __using__(opts) do
                  quote bind_quoted: [opts: opts] do
                    @required_config opts[:required_config] || []
                    @required_deps opts[:required_deps] || []

                    @behaviour {row}

                    def validate_config(config) do
                      {row}.validate_config(@required_config, config)
                    end

                    def validate_dependency do
                      {row}.validate_dependency(@required_deps)
                    end
                  end
                end
                """,
                "required_config: [:api_key]",
            ),
            use(
                "use:keep",
                """
                defmacro __using__(_) do
                  quote location: :keep do
                    @behaviour Application

                    @doc false
                    def stop(_state) do
                      :ok
                    end

                    defoverridable Application
                  end
                end
                """,
            ),
            use(
                "use:cond",
                """
                defmacro __using__(options) do
                  except =
                    cond do
                      Keyword.get(options, :only_operators) ->
                        [bnot: 1, band: 2, bor: 2, bxor: 2, bsl: 2, bsr: 2]

                      Keyword.get(options, :skip_operators) ->
                        ["~~~": 1, &&&: 2, |||: 2, "^^^": 2, <<<: 2, >>>: 2]

                      true ->
                        []
                    end

                  quote do
                    import Bitwise, except: unquote(except)
                  end
                end
                """,
                "only_operators: true",
            ),
            use(
                "use:apply",
                """
                def static_paths, do: ~w(assets fonts)

                def controller do
                  quote do
                    import Kernel, except: [to_string: 1]
                    unquote(verified_routes())
                  end
                end

                def html do
                  quote do
                    import String, only: [upcase: 1]
                    unquote(html_helpers())
                  end
                end

                defp html_helpers do
                  quote do
                    alias String.Chars
                    unquote(verified_routes())
                  end
                end

                def verified_routes do
                  quote do
                    @statics {row}.static_paths()
                  end
                end

                defmacro __using__(which) when is_atom(which) do
                  apply(__MODULE__, which, [])
                end
                """,
                ":html",
            ),
            use(
                "use:caller",
                """
                def __setup__(_module, opts), do: [{"phx-", Keyword.get(opts, :global, true)}]

                defmacro __using__(opts \\ []) do
                  conditional =
                    if __CALLER__.module != {row}.Helpers do
                      quote do: import({row}, only: [__setup__: 2])
                    end

                  imports =
                    quote bind_quoted: [opts: opts] do
                      import Kernel, except: [to_string: 1]

                      for {prefix_match, value} <- {row}.__setup__(__MODULE__, opts) do
                        @doc false
                        def __global__?(unquote(prefix_match)), do: unquote(value)
                      end
                    end

                  [conditional, imports]
                end
                """,
            ),
            use(
                "use:helpers",
                """
                @callback config(atom) :: term

                defmacro __using__(opts) do
                  quote do
                    @behaviour {row}

                    unquote(config(opts))
                    unquote(pubsub())
                    unquote(server())
                  end
                end

                defp config(opts) do
                  quote do
                    @otp_app unquote(opts)[:otp_app] || raise("endpoint expects :otp_app to be given")
                    var!(code_reloading?) = false
                    _ = var!(code_reloading?)
                  end
                end

                defp pubsub() do
                  quote generated: true do
                    def subscribe(topic, opts \\ []) when is_binary(topic) do
                      {topic, opts}
                    end
                  end
                end

                defp server() do
                  quote location: :keep, unquote: false do
                    def child_spec(opts) do
                      %{id: __MODULE__, start: {__MODULE__, :start_link, [opts]}, type: :supervisor}
                    end

                    def config(key), do: key
                  end
                end
                """,
                "otp_app: :demo",
            ),
            use(
                "use:genserver",
                """
                defmacro __using__(opts) do
                  quote location: :keep, bind_quoted: [opts: opts] do
                    @behaviour GenServer

                    if not Module.has_attribute?(__MODULE__, :doc) do
                      @doc ${"\"\"\""}
                      Returns a specification to start this module under a supervisor.
                      ${"\"\"\""}
                    end

                    def child_spec(init_arg) do
                      default = %{
                        id: __MODULE__,
                        start: {__MODULE__, :start_link, [init_arg]}
                      }

                      Supervisor.child_spec(default, unquote(Macro.escape(opts)))
                    end

                    defoverridable child_spec: 1

                    @before_compile GenServer

                    @doc false
                    def terminate(_reason, _state) do
                      :ok
                    end

                    defoverridable terminate: 2
                  end
                end
                """,
                "restart: :transient",
                stopsAfter = GENSERVER_CALLBACK,
            ),
            use(
                "use:backend",
                """
                defmacro __using__(opts) do
                  env_fun = if function_exported?(Module, :attributes_in, 1), do: :compile_env, else: :get_env

                  quote do
                    opts = unquote(opts)
                    otp_app = Keyword.fetch!(opts, :otp_app)
                    @gettext_opts Keyword.merge(opts, Application.unquote(env_fun)(otp_app, __MODULE__, []))
                  end
                end
                """,
                "otp_app: :demo",
            ),
            leg("GenServer", after = "def init(x), do: {:ok, x}"),
            leg("Supervisor"),
            leg("Agent"),
            leg("Task"),
            leg("DynamicSupervisor"),
            leg("Application"),
            leg("Bitwise", "only_operators: true"),
            // A result's term that isn't AST reaches `elixir_expand`'s value clauses where expansion meets it.
            macro("head:fun-anonymous", "defmacro m, do: fn -> 1 end", ends = { "raised invalid_quoted_expr" }),
            macro("head:pid-module-body", "defmacro m, do: self()"),
            Row(
                "head:pid-def-body",
                "defmacro m, do: self()",
                "$ROW.m()",
                "require $ROW\ndef f, do: $ROW.m()",
                "$ROW.m()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
                ends = { level ->
                    "crashed $MATCH_ERROR".takeIf { PID_IN_FUNCTION_RAISES_MATCH_ERROR.isSufficient(level) }
                },
            ),
            // An argument's pid takes `expand_arg`'s fast path, so it never reaches the pid head.
            Row(
                "head:pid-in-list-def-body",
                "defmacro m, do: [self()]",
                "$ROW.m()",
                "require $ROW\ndef f, do: $ROW.m()",
                "$ROW.m()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),
            macro("head:reference", "defmacro m, do: make_ref()", ends = { "raised invalid_quoted_expr" }),
            // A quote puts a term that isn't AST in its output as it is, which expanding then raises at.
            macro("head:empty-tuple-in-quote", "defmacro m, do: {:quote, [], [[do: {}]]}", ends = { "raised invalid_quoted_expr" }),
            macro("head:map-in-quote", "defmacro m, do: {:quote, [], [[do: %{a: 1}]]}", ends = { "raised invalid_quoted_expr" }),
            macro(
                "head:four-tuple-in-quote",
                "defmacro m, do: {:quote, [], [[do: {1, 2, 3, 4}]]}",
                ends = { "raised invalid_quoted_expr" },
            ),
            // `Macro.pipe/3` builds a call of any 3-tuple with list arguments, and expanding it raises.
            macro("head:pipe-into-value", "defmacro m, do: {:|>, [], [1, {1, 2, [3]}]}", ends = { "raised invalid_quoted_expr" }),
            // Jinterface reads a list of bytes as a string.
            macro(
                "head:pipe-into-value-of-bytes",
                "defmacro m(x), do: {:|>, [], [1, {1, 2, x}]}",
                "m([3])",
                ends = { "raised invalid_quoted_expr" },
            ),
            macro("head:bad-tuple", "defmacro m, do: {1, 2, 3}", ends = { "raised invalid_quoted_expr" }),
            macro("head:legacy", "defmacro m, do: {1, [], nil}", ends = { "raised invalid_quoted_expr" }),
            macro("head:improper-list-in-map", "defmacro m, do: %{a: [1 | 2]}", ends = { "raised invalid_quoted_expr" }),
            // Elixir takes each for a form or a call, whose metadata can't be read.
            macro("head:unreadable-meta", "defmacro m, do: {:+, [x: 1.0], [1, 2]}", pins = DECLINED),
            macro("head:non-list-meta-form", "defmacro m, do: {:__block__, :x, [1]}", pins = DECLINED),
            macro("head:list-meta-call", "defmacro m, do: {1, [1], []}", pins = DECLINED),
            // Linifying the result raises before anything expands.
            macro("head:improper-list", "defmacro m, do: [1 | 2]"),
            macro("head:linify-lexical-meta", "defmacro m, do: {:alias, :x, [1]}"),
            // `Macro.expand_once/2`'s call has no line, so before 1.20 Elixir's linify doesn't read the meta there.
            macro(
                "head:improper-meta",
                "defmacro m, do: {:foo, [1 | 2], []}",
                pins = { _, level ->
                    "{:raise, ArgumentError}".takeUnless { MACRO_ARGUMENTS_STOP_GENERATED.isSufficient(level) }
                },
                ends = { "raised Elixir.ArgumentError" },
            ),
            // The `def` before the reference is stored, though the module raises before `@before_compile` runs.
            Row(
                "head:reference-after-def",
                "defmacro m, do: {:__block__, [], [quote(do: def(f, do: 1)), make_ref()]}",
                "$ROW.m()",
                "require $ROW\n$ROW.m()",
                "$ROW.m()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
                ends = { "raised invalid_quoted_expr" },
                table = "[f/0 def]",
            ),
            // Two bitstrings matched in parallel raise, but not the one side of a match against itself.
            Row(
                "head:duplicate-match",
                """
                defmacro m do
                  b = quote(do: <<a>>)
                  quote(do: unquote(b) = unquote(b))
                end
                """.trimIndent(),
                "$ROW.m()",
                "require $ROW\ndef f($ROW.m()), do: :ok",
                "$ROW.m()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
                ends = { level ->
                    "raised parallel_bitstring_match".takeUnless { DUPLICATE_MATCH_UNPACKED_ONCE.isSufficient(level) }
                },
            ),
            // An unknown that decides whether a 3-tuple is a form declines, since Elixir decides on the real value.
            macro("head:unknown-meta", "defmacro m, do: {1, __CALLER__.lexical_tracker, []}", pins = DECLINED),
            macro("head:unknown-head-and-meta", "defmacro m, do: {__CALLER__.lexical_tracker, __CALLER__.tracers, []}", pins = DECLINED),
            // `Macro.expand/2` reads each as the expander does, before any result is linified.
            macro("head:unknown-meta-expanded", "defmacro m, do: (_ = Macro.expand({1, __CALLER__.lexical_tracker, []}, __CALLER__); :ok)", pins = DECLINED),
            macro(
                "head:unknown-head-and-meta-expanded",
                "defmacro m, do: (_ = Macro.expand({__CALLER__.lexical_tracker, __CALLER__.tracers, []}, __CALLER__); :ok)",
                pins = DECLINED,
            ),
            macro(
                "head:pipe-into-unknown",
                """
                defmacro m do
                  t = {__CALLER__.lexical_tracker, __CALLER__.lexical_tracker, __CALLER__.tracers}
                  {:|>, [], [1, t]}
                end
                """,
                pins = DECLINED,
            ),
            // A rescue pattern that is a 3-tuple with metadata that isn't a list.
            macro(
                "rescue:value-pattern",
                "defmacro m, do: {:try, [], [[do: :ok, rescue: [{:->, [], [[{1, :x, 2}], :ok]}]]]}",
                ends = { level ->
                    if (RESCUE_CALL_EXPANDED_AS_MACRO.isSufficient(level)) "crashed Elixir.FunctionClauseError" else "raised invalid_quoted_expr"
                },
            ),
            // Linifying reads an unknown metadata as Elixir reads the list it may be.
            macro(
                "head:linify-unknown-meta",
                "defmacro m, do: [{:a, __CALLER__.lexical_tracker, [1 | 2]}, {:b, [{:line, 1} | 3], []}]",
                pins = DECLINED,
            ),
            // `rescue x in Alias` before Elixir reads only the key's presence.
            macro(
                "rescue:unknown-exception-flag",
                """
                @flag (receive do after 0 -> true end)
                defmacro m do
                  try do
                    :erlang.error(%{__struct__: ArgumentError, __exception__: @flag, message: "x"})
                  rescue
                    ArgumentError -> :a
                    _ -> :b
                  end
                end
                """,
                pins = { source, level -> DECLINED_FROM_SOURCE(source, level)?.takeUnless { RESCUE_EXCEPTION_KEY_PRESENT.isSufficient(level) } },
            ),
            // `-0.0` and `0.0` are two terms from OTP 27.
            macro("pure:negative-zero-exact", "defmacro m, do: (if -0.0 === 0.0, do: :same, else: :different)"),
            // A map's keys compare as `=:=` does, under `==` and `<` too.
            macro("pure:negative-zero-map-key", "defmacro m, do: {%{-0.0 => 1} == %{0.0 => 1}, %{-0.0 => 1} < %{0.0 => 1}}"),
            // A map holds one key for each term `=:=` tells apart, however it is built or read.
            macro("pure:negative-zero-map-put", "defmacro m, do: map_size(Map.put(%{0.0 => 1}, -0.0, 2))", pins = KEY_KEPT_BELOW_OTP_27),
            macro("pure:negative-zero-map-literal", "defmacro m, do: map_size(%{0.0 => 1, -0.0 => 2})", pins = KEY_KEPT_BELOW_OTP_27),
            macro("pure:negative-zero-map-get", "defmacro m, do: Map.get(%{0.0 => :a}, -0.0)"),
            macro("pure:negative-zero-map-pattern", "defmacro m, do: (case %{0.0 => :a} do %{-0.0 => v} -> v; _ -> :none end)"),
            macro(
                "pure:negative-zero-map-merge",
                "defmacro m, do: map_size(Map.merge(%{0.0 => :a}, %{-0.0 => :b}))",
                pins = KEY_KEPT_BELOW_OTP_27,
            ),
            macro(
                "pure:negative-zero-map-from-list",
                "defmacro m, do: map_size(:maps.from_list([{0.0, :a}, {-0.0, :b}]))",
                pins = KEY_KEPT_BELOW_OTP_27,
            ),
            // A repeated key is one key, though only the VM orders the references it holds.
            macro(
                "pure:map-key-repeated-holding-references",
                """
                defmacro m do
                  k = %{make_ref() => 1, make_ref() => 2}
                  map_size(%{k => :first, k => :second})
                end
                """,
            ),
            macro(
                "pure:map-key-rebuilt-holding-references",
                """
                defmacro m do
                  a = make_ref()
                  b = make_ref()
                  map_size(%{%{a => 1, b => 2} => :first, %{a => 1, b => 2} => :second})
                end
                """,
            ),
            macro(
                "pure:map-key-repeated-holding-references-and-a-float",
                """
                defmacro m do
                  k = %{make_ref() => 1.0, make_ref() => 2}
                  map_size(%{k => :first, k => :second})
                end
                """,
            ),
            macro(
                "pure:access-fetch-unknown-ok",
                """
                defmacro m do
                  try do
                    Access.get(%{__struct__: {row}.S}, :a)
                  catch
                    :error, reason -> elem(reason, 0)
                  end
                end
                """,
                pins = DECLINED_FROM_SOURCE,
                helper = """
                defmodule {row}.S do
                  @ok (receive do after 0 -> :ok end)
                  def fetch(_, _), do: {@ok, 1}
                end
                """,
            ),
            // `elixir_quote:annotate/2` replaces a definition head's `context`, wherever it is in the metadata.
            macro(
                "quote:def-head-unknown-context",
                """
                @c (receive do after 0 -> {:context, Foo} end)
                defmacro m do
                  {_, _, [{:f, meta, _} | _]} =
                    quote context: Bar, do: unquote(Kernel).unquote(:def)(unquote({:f, [@c], []}), do: 1)
                  length(meta)
                end
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            // `lists:filter/2` calls its fun on each element before it reaches an improper tail.
            macro(
                "pure:keyword-take-improper",
                """
                defmacro m do
                  try do
                    Keyword.take([1 | :x], [:a])
                  rescue
                    e -> e.__struct__
                  end
                end
                """,
            ),
            macro(
                "pure:keyword-merge-improper",
                """
                defmacro m do
                  try do
                    Keyword.merge([1 | :x], [b: 1])
                  rescue
                    e -> e.__struct__
                  end
                end
                """,
            ),
            macro("pure:escape-unquote-not-true", "defmacro m, do: Macro.escape({:unquote, [], [{:x, [], nil}]}, unquote: :yes)"),
            // The VM's largest integer is 65,535 words; before OTP 26.0.2 it depends on the patch release.
            macro(
                "pure:integer-past-system-limit",
                """
                defmacro m do
                  _ = Enum.reduce(1..22, 2, fn _, a -> a * a end)
                  :ok
                end
                """,
                pins = { _, level -> OPAQUE.takeUnless { INTEGERS_UP_TO_4_MBIT.isSufficient(level) } },
            ),
            // Every segment's value is evaluated before any is written.
            macro(
                "bits:segment-values-first",
                """
                defmacro m do
                  a = __CALLER__.module
                  <<a, Keyword.fetch!([], :missing)>>
                end
                """,
            ),
            macro(
                "pure:flatten-improper-args",
                """
                defmacro m do
                  try do
                    :lists.flatten([[1 | :a], 2])
                  rescue
                    _ ->
                      [{m, f, args, _} | _] = __STACKTRACE__
                      Macro.escape({m, f, args})
                  end
                end
                """,
            ),
            macro(
                "pure:keyword-delete-args",
                """
                defmacro m do
                  try do
                    Keyword.delete([{:a, 1}, 3], :a)
                  rescue
                    _ ->
                      [{m, f, args, _} | _] = __STACKTRACE__
                      Macro.escape({m, f, args})
                  end
                end
                """,
            ),
            // A struct whose module isn't known may escape itself, so its module is needed before `A:`, which sorts
            // first. Before structs escape themselves, the `fn` raises whatever the module is.
            macro(
                "escape:unknown-struct-escapes-itself",
                """
                @mod Application.compile_env(:app, :mod, {row}.W)
                defmacro m, do: Macro.escape(%{A: fn -> :ok end, __struct__: @mod})
                """,
                pins = { _, level -> OPAQUE.takeIf { REGEX_ESCAPED_IN_FIXED_FIELD_ORDER.isSufficient(level) } },
                helper = """
                defmodule {row}.W do
                  defstruct [:f]
                  def __escape__(_), do: :escaped
                end
                """,
            ),
            // Before Elixir sorts them, a large map's entries are escaped in the order of their hashes.
            macro(
                "escape:large-map-raises-in-hash-order",
                """
                @v [{:unquote_splicing, [], [[1]]} | :t]
                defmacro m do
                  Macro.escape(
                    %{
                      a1: 1, a2: 2, a3: 3, a4: 4, a5: 5, a6: 6, a7: 7, a8: 8, a9: 9, a10: 10, a11: 11, a12: 12, a13: 13,
                      a14: 14, a15: 15, a16: 16, a17: 17, a18: 18, a19: 19, a20: 20, a21: 21, a22: 22, a23: 23, a24: 24,
                      a25: 25, a26: 26, a27: 27, a28: 28, a29: 29, a30: 30, a31: 31, f: fn -> :ok end, v: @v
                    },
                    unquote: true
                  )
                end
                """,
                legs = { !ESCAPED_MAP_ENTRIES_SORTED.isSufficient(it) },
                pins = DECLINED,
            ),
            // An entry that isn't known may raise before the one that does, in an order no term decides. From `Dbgi`
            // it is known.
            macro(
                "escape:large-map-unknown-entry",
                """
                @u Application.compile_env(:app, :u, 1)
                defmacro m do
                  Macro.escape(%{
                    a1: 1, a2: 2, a3: 3, a4: 4, a5: 5, a6: 6, a7: 7, a8: 8, a9: 9, a10: 10, a11: 11, a12: 12, a13: 13,
                    a14: 14, a15: 15, a16: 16, a17: 17, a18: 18, a19: 19, a20: 20, a21: 21, a22: 22, a23: 23, a24: 24,
                    a25: 25, a26: 26, a27: 27, a28: 28, a29: 29, a30: 30, a31: 31, f: fn -> :ok end, u: @u
                  })
                end
                """,
                legs = { !ESCAPED_MAP_ENTRIES_SORTED.isSufficient(it) },
                pins = DECLINED_FROM_SOURCE,
            ),
            // A quote keeps the imports a node of a macro's result already holds.
            macro(
                "quote:kept-imports",
                """
                import {row}.U, only: [upcase: 2]
                require {row}.H
                defmacro n, do: {row}.H.h()
                """,
                "n()",
                helper = """
                defmodule {row}.U do
                  def upcase(a, b), do: {a, b}
                end

                defmodule {row}.H do
                  defmacro h, do: {:quote, [], [[do: {:upcase, [imports: [{1, String}], context: String], ["a"]}]]}
                end
                """,
                // Before 1.17 a quote reads `import:`, so `upcase/1` is a function the module doesn't define.
                ends = { level -> "raised undefined_function".takeUnless { QUOTE_KEEPS_IMPORTS_META.isSufficient(level) } },
            ),
            // From 1.14 to 1.16 a quote writes `imports: false` beside a node's `import:`, and its dispatch raises.
            macro(
                "quote:kept-import",
                """
                require {row}.H
                defmacro n, do: {row}.H.h()
                """,
                "n()",
                helper = """
                defmodule {row}.H do
                  defmacro h, do: {:quote, [], [[do: {:upcase, [import: String], ["a"]}]]}
                end
                """,
                legs = { QUOTE_IMPORTS_EVERY_ARITY.isSufficient(it) && !QUOTE_KEEPS_IMPORTS_META.isSufficient(it) },
                ends = { "stopped at `$ROW.n()`" },
            ),
            // A node's line is its first `line` entry, an integer of any size, or 0.
            Row(
                "meta:repeated-line-kept",
                """
                require {row}.G
                defmacro n do
                  ast = Macro.expand(quote(do: {row}.G.m()), __CALLER__)
                  Macro.escape(Keyword.get_values(elem(ast, 1), :line))
                end
                """.trimIndent(),
                "$ROW.n()",
                "require $ROW\nrequire $ROW.G\n_ = $ROW.n()",
                "$ROW.n()",
                listOf(ROW, "$ROW.G"),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
                helper = """
                defmodule {row}.G do
                  defmacro m, do: {:foo, [line: 1, line: :x], []}
                end
                """.trimIndent(),
            ),
            macro(
                "meta:line-past-int",
                """
                require {row}.G
                defmacro n, do: {row}.G.m()
                """,
                "n()",
                helper = """
                defmodule {row}.G do
                  defmacro m, do: {{:., [], [{:__ENV__, [line: 3_000_000_000], nil}, :line]}, [], []}
                end
                """,
                // A node's line is an `Int`, so from source the module stops at `n`'s body; `Dbgi` holds the line it expanded to.
                pins = { source, _ -> UNPORTED.takeIf { source == Source.SOURCE } },
            ),
            macro(
                "meta:line-not-an-integer",
                """
                require {row}.G
                defmacro n, do: {row}.G.m()
                """,
                "n()",
                helper = """
                defmodule {row}.G do
                  defmacro m, do: {{:., [], [{:__ENV__, [line: :x, line: 5], nil}, :line]}, [], []}
                end
                """,
            ),
            // `{:{}, meta, [a, b]}` is expanded as the pair it builds.
            macro("head:tuple-call-pair", "defmacro m, do: {:{}, [], [1, 2]}"),
            // An alias of no segments has no clause in `elixir_aliases:expand`.
            macro("head:empty-alias", "defmacro m, do: {:__aliases__, [], []}", pins = DECLINED),
            macro("form:macro-expand-empty-alias", "defmacro m, do: Macro.expand({:__aliases__, [], []}, __CALLER__)", pins = DECLINED),
            // `elixir_aliases:expand/4` raises `system_limit` on an alias past an atom's length.
            macro(
                "form:macro-expand-alias-past-an-atom",
                "defmacro m, do: (try do Macro.expand({:__aliases__, [], [:${"A".repeat(250)}]}, __CALLER__) rescue e -> e.__struct__ end)",
                pins = DECLINED,
            ),
            macro(
                "head:defimpl-empty-alias",
                "defmacro m, do: {:defimpl, [], [{:__aliases__, [], []}, [for: Integer], [do: nil]]}",
                pins = DECLINED,
            ),
            macro(
                "in:unescaped-map",
                "defmacro m(x), do: {:in, [], [x, %{a: 1}]}",
                "m(1)",
                ends = { level ->
                    if (IN_REFUSES_UNESCAPED_RIGHT_SIDE.isSufficient(level)) "raised Elixir.ArgumentError" else "raised invalid_quoted_expr"
                },
            ),
            // `for` checks its first case after expanding it. From 1.18 the type checker reads the `<-/2` it expands to
            // as a generator; before, it is a call of a local function the module doesn't define.
            Row(
                "for:generator-from-macro",
                "defmacro gen, do: quote(do: 1 <- [1])",
                "$ROW.gen()",
                "require $ROW\ndef f, do: for($ROW.gen(), do: 1)",
                "$ROW.gen()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
                ends = { level ->
                    when {
                        POST_MODULE_LOCAL_CHECKS_TYPED.isSufficient(level) -> null
                        FUNCTION_ERRORS_CONTINUE.isSufficient(level) -> "tainted"
                        else -> "raised undefined_function"
                    }
                },
            ),
            // An expanded bitstring's segments are each typed, so it isn't a generator.
            Row(
                "for:bitstring-generator-from-macro",
                "defmacro gen, do: quote(do: <<1 <- \"a\">>)",
                "$ROW.gen()",
                "require $ROW\ndef f, do: for($ROW.gen(), do: 1)",
                "$ROW.gen()",
                listOf(ROW),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
                ends = { "raised for_generator_start" },
            ),
            // What a `@before_compile` callback defines is the module's, and `Module.defines?/2,3` answers from the
            // table wherever the module compiles, declining where Elixir's table may differ.
            macro(
                "before_compile:defines",
                "defmacro m, do: Macro.escape({row}.U.f())",
                helper = """
                defmodule {row}.CB do
                  defmacro __before_compile__(_env), do: quote(do: def(f, do: 1))
                end

                defmodule {row}.U do
                  @before_compile {row}.CB
                end
                """,
            ),
            // The callback's output is a statement of the module body, so its attribute writes are the module's.
            macro(
                "before_compile:attribute",
                "defmacro m, do: Macro.escape({row}.UA.r())",
                helper = """
                defmodule {row}.CA do
                  defmacro __before_compile__(_env) do
                    quote do
                      @x 1
                      def r, do: @x
                    end
                  end
                end

                defmodule {row}.UA do
                  @before_compile {row}.CA
                end
                """,
            ),
            macro(
                "before_compile:defines-kind",
                "defmacro m, do: Macro.escape({row}.K.r())",
                helper = """
                defmodule {row}.CK do
                  defmacro __before_compile__(env) do
                    t = {
                      Module.defines?(env.module, {:f, 0}, :def),
                      Module.defines?(env.module, {:f, 0}, :defp),
                      Module.defines?(env.module, {:g, 0}, :def)
                    }

                    quote(do: def(r, do: unquote(Macro.escape(t))))
                  end
                end

                defmodule {row}.K do
                  @before_compile {row}.CK
                  def f, do: 1
                end
                """,
            ),
            // The first callback's env is its caller's, so `Macro.expand/2` expands in it.
            macro(
                "before_compile:expands-in-env",
                "defmacro m, do: Macro.escape({row}.UE.r())",
                helper = """
                defmodule {row}.CE do
                  defmacro __before_compile__(env) do
                    expanded = Macro.expand(quote(do: __MODULE__), env)

                    quote(do: def(r, do: unquote(expanded)))
                  end
                end

                defmodule {row}.UE do
                  @before_compile {row}.CE
                end
                """,
            ),
            // A body's macro sees what was stored before the body; one at the module body's top level, an empty table.
            macro(
                "before_compile:defines-at-expansion",
                "defmacro m, do: Macro.escape({{row}.X.in_def(), {row}.XB.at_body()})",
                helper = DEFINES + "\n\n" + """
                defmodule {row}.X do
                  require {row}.D
                  def f, do: 1
                  def in_def, do: {row}.D.d(:f, 0)
                end

                defmodule {row}.XB do
                  require {row}.D
                  def f, do: 1
                  @at_body {row}.D.d(:f, 0)
                  def at_body, do: @at_body
                end
                """.trimIndent(),
            ),
            macro(
                "before_compile:defines-branch",
                "defmacro m, do: Macro.escape({{row}.N.r(), {row}.T.r()})",
                helper = DEFINES_AT_CALLBACK + "\n\n" + """
                defmodule {row}.N do
                  @before_compile {row}.CC
                  if false do
                    def f, do: 1
                  end
                end

                defmodule {row}.T do
                  @before_compile {row}.CC
                  if true do
                    def f, do: 1
                  end
                end
                """.trimIndent(),
            ),
            // A stop can hide a removal, so after one nothing is answered.
            macro(
                "before_compile:defines-after-stop",
                "defmacro m, do: Macro.escape({{row}.Y.g(), {row}.Y.in_def()})",
                pins = CALLS_A_STOPPED_MODULE,
                helper = DEFINES + "\n\n" + """
                defmodule {row}.Y do
                  require {row}.D
                  def unquote(Application.get_env(:app, :name, :g))(), do: 1
                  def in_def, do: {row}.D.d(:f, 0)
                end
                """.trimIndent(),
            ),
            macro(
                "before_compile:defines-overridable-branch",
                "defmacro m, do: Macro.escape({{row}.O.g(), {row}.O2.g()})",
                helper = definesAround(
                    "O" to "  if false do\n    defoverridable f: 0\n  end",
                    "O2" to "  if true do\n    defoverridable f: 0\n  end",
                ),
            ),
            macro(
                "before_compile:defines-after-overridable",
                "defmacro m, do: Macro.escape({row}.OV.g())",
                helper = definesAround("OV" to "  defoverridable f: 0"),
            ),
            macro(
                "before_compile:defines-after-overridable-stop",
                "defmacro m, do: Macro.escape({row}.V.g())",
                pins = CALLS_A_STOPPED_MODULE,
                helper = definesAround("V" to "  defoverridable Application.get_env(:app, :ov, f: 0)"),
            ),
            // Each call of a remover by name reaches the table or stops the module.
            macro(
                "before_compile:defines-after-delete",
                "defmacro m, do: Macro.escape({row}.DD.g())",
                legs = DELETES_DEFINITIONS,
                helper = definesAround("DD" to "  Module.delete_definition(__MODULE__, {:f, 0})"),
            ),
            macro(
                "before_compile:defines-after-nested-delete",
                "defmacro m, do: Macro.escape({{row}.ND.g(), {row}.NO.g()})",
                legs = DELETES_DEFINITIONS,
                pins = CALLS_A_STOPPED_MODULE,
                helper = definesAround(
                    "ND" to "  defmodule Inner do\n    Module.delete_definition({row}.ND, {:f, 0})\n  end",
                    "NO" to "  defmodule Inner do\n    Module.make_overridable({row}.NO, f: 0)\n  end",
                ),
            ),
            // A nested module's stop is its enclosing module's, so its definitions asking after the enclosing module
            // decline too.
            macro(
                "before_compile:defines-in-nested-after-stop",
                "defmacro m, do: Macro.escape({row}.NS.Inner.g())",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule {row}.DM do
                  defmacro dm(module, name, arity), do: Module.defines?(Macro.expand(module, __CALLER__), {name, arity})
                end

                defmodule {row}.NS do
                  def f, do: 1

                  defmodule Inner do
                    require {row}.DM
                    Module.make_overridable({row}.NS, f: 0)
                    def g, do: {row}.DM.dm({row}.NS, :f, 0)
                  end
                end
                """.trimIndent(),
            ),
            macro(
                "before_compile:defines-after-unknown-delete",
                "defmacro m, do: Macro.escape({{row}.UD.g(), {row}.UT.g()})",
                legs = DELETES_DEFINITIONS,
                pins = CALLS_A_STOPPED_MODULE,
                helper = definesAround(
                    "UD" to "  m = Application.get_env(:app, :mod, Module)\n  m.delete_definition(__MODULE__, {:f, 0})",
                    "UT" to "  Module.delete_definition(Application.get_env(:app, :t, __MODULE__), {:f, 0})",
                ),
            ),
            macro(
                "before_compile:defines-after-unknown-overridable",
                "defmacro m, do: Macro.escape({{row}.UO.g(), {row}.UM.g()})",
                pins = CALLS_A_STOPPED_MODULE,
                helper = definesAround(
                    "UO" to "  Module.make_overridable(Application.get_env(:app, :t, __MODULE__), f: 0)",
                    "UM" to "  m = Application.get_env(:app, :mod, Module)\n  m.make_overridable(__MODULE__, f: 0)",
                ),
            ),
            macro(
                "before_compile:defines-after-take",
                "defmacro m, do: Macro.escape({{row}.ED.g(), {row}.TU.g()})",
                pins = CALLS_A_STOPPED_MODULE,
                helper = definesAround(
                    "ED" to "  :elixir_def.take_definition(__MODULE__, {:f, 0})",
                    "TU" to "  m = Application.get_env(:app, :mod, :elixir_def)\n  m.take_definition(__MODULE__, {:f, 0})",
                ),
            ),
            // `GenServer.__before_compile__` asks `Module.defines?/2` whether the module defines `init/1`.
            macro(
                "before_compile:genserver",
                "defmacro m, do: Macro.escape({row}.B.init(1))",
                helper = """
                defmodule {row}.B do
                  @before_compile GenServer
                  def init(x), do: {:ok, {:mine, x}}
                end
                """,
            ),
            // Without `init/1` the callback warns through functions the evaluator doesn't port.
            macro(
                "before_compile:genserver-without-init",
                "defmacro m, do: Macro.escape(function_exported?({row}.A, :init, 1))",
                pins = DECLINED_FROM_SOURCE,
                helper = """
                defmodule {row}.A do
                  @before_compile GenServer
                end
                """,
            ),
            macro(
                "body:bind-quoted",
                "defmacro m, do: Macro.escape({row}.BQ.r())",
                helper = """
                defmodule {row}.U do
                  defmacro __using__(opts) do
                    quote bind_quoted: [opts: opts] do
                      @v opts
                      def r, do: @v
                    end
                  end
                end

                defmodule {row}.BQ do
                  use {row}.U, v: 7
                end
                """,
            ),
            macro(
                "body:match",
                "defmacro m, do: Macro.escape({row}.MA.r())",
                helper = """
                defmodule {row}.MA do
                  x = {1, [2, 3]}
                  {a, [b | c]} = x
                  @r {a, b, c}
                  def r, do: @r
                end
                """,
            ),
            // The evaluator doesn't read the application environment, so `x` has no value.
            macro(
                "body:match-unknown",
                "defmacro m, do: Macro.escape({row}.MU.r())",
                pins = DECLINED_FROM_SOURCE,
                helper = """
                defmodule {row}.MU do
                  x = Application.get_env(:app, :k, 1)
                  @r x
                  def r, do: @r
                end
                """,
            ),
            macro(
                "body:attribute",
                "defmacro m, do: Macro.escape({row}.AT.r())",
                helper = """
                defmodule {row}.AT do
                  @a 1
                  @b {@a, 2}
                  @a 3
                  def r, do: {@a, @b}
                end
                """,
            ),
            macro(
                "body:attribute-doc",
                "defmacro m, do: Macro.escape({row}.AD.r())",
                helper = """
                defmodule {row}.AD do
                  @moduledoc "m"
                  @c @moduledoc
                  @doc "d"
                  @b @doc
                  def f, do: 1
                  def r, do: {@b, @c}
                end
                """,
            ),
            macro(
                "body:attribute-accumulate",
                "defmacro m, do: Macro.escape({row}.AA.r())",
                helper = """
                defmodule {row}.AA do
                  Module.register_attribute(__MODULE__, :a, accumulate: true)
                  @a 1
                  @a 2
                  @b @a
                  def r, do: @b
                end
                """,
            ),
            macro(
                "body:attribute-unset",
                "defmacro m, do: Macro.escape({row}.AU.r())",
                helper = """
                defmodule {row}.AU do
                  Module.register_attribute(__MODULE__, :a, [])
                  @b @a
                  def r, do: @b
                end
                """,
            ),
            macro(
                "body:attribute-default",
                "defmacro m, do: Macro.escape({row}.AF.r())",
                helper = """
                defmodule {row}.AF do
                  @k1 1
                  Module.register_attribute(__MODULE__, :k3, [])
                  @r {
                    Module.get_attribute(__MODULE__, :k1, ""),
                    Module.get_attribute(__MODULE__, :k2, ""),
                    Module.get_attribute(__MODULE__, :k3, "")
                  }
                  def r, do: @r
                end
                """,
            ),
            // A module starts with rows for `@moduledoc` and the attributes that always accumulate, and `@doc` has one
            // until the next definition takes it.
            macro(
                "body:has-attribute",
                "defmacro m, do: Macro.escape({row}.HA.r())",
                helper = """
                defmodule {row}.HA do
                  @start {
                    Module.has_attribute?(__MODULE__, :moduledoc),
                    Module.has_attribute?(__MODULE__, :after_compile),
                    Module.has_attribute?(__MODULE__, :doc)
                  }
                  @k1 1
                  Module.register_attribute(__MODULE__, :k3, [])
                  @doc "f"
                  @doc_before Module.has_attribute?(__MODULE__, :doc)
                  def f, do: 1
                  @r {
                    @start,
                    Module.has_attribute?(__MODULE__, :k1),
                    Module.has_attribute?(__MODULE__, :k2),
                    Module.has_attribute?(__MODULE__, :k3),
                    @doc_before,
                    Module.has_attribute?(__MODULE__, :doc)
                  }
                  def r, do: @r
                end
                """,
            ),
            macro(
                "body:has-attribute-guard",
                """
                defmacro m do
                  try do
                    Module.has_attribute?(__CALLER__.module, "k")
                  rescue
                    e in FunctionClauseError -> {e.function, e.arity}
                  end
                end
                """,
            ),
            // A type takes `@typedoc`, and a callback `@doc` the first time its name and arity is written; a spec takes
            // neither.
            macro(
                "body:has-attribute-after-typespec",
                "defmacro m, do: Macro.escape({row}.HT.r())",
                helper = """
                defmodule {row}.HT do
                  @typedoc "t"
                  @type t :: integer
                  @t Module.has_attribute?(__MODULE__, :typedoc)
                  @typedoc "p"
                  @typep p :: integer
                  @p Module.has_attribute?(__MODULE__, :typedoc)
                  @typedoc "o"
                  @opaque o :: integer
                  @o Module.has_attribute?(__MODULE__, :typedoc)
                  @doc "c"
                  @callback c() :: any
                  @c Module.has_attribute?(__MODULE__, :doc)
                  @doc "again"
                  @callback c() :: any
                  @again Module.has_attribute?(__MODULE__, :doc)
                  @macrocallback mc() :: any
                  @mc Module.has_attribute?(__MODULE__, :doc)
                  @typedoc "s"
                  @spec r() :: p
                  @s Module.has_attribute?(__MODULE__, :typedoc)
                  def r, do: {@t, @p, @o, @c, @again, @mc, @s}
                end
                """,
            ),
            // Elixir gives `false` in each: the key was deleted where the expander doesn't know it was, so it declines.
            macro(
                "body:has-attribute-after-stop",
                "defmacro m, do: Macro.escape({row}.HS.r())",
                helper = """
                defmodule {row}.HS do
                  @c 1
                  d = Application.compile_env(:nope, :k2, :c)
                  Module.delete_attribute(__MODULE__, d)
                  v = Module.has_attribute?(__MODULE__, :c)
                  def r, do: unquote(v)
                end
                """,
                pins = CALLS_A_STOPPED_MODULE,
            ),
            macro(
                "body:has-attribute-conditional-delete",
                "defmacro m, do: Macro.escape({row}.HU.r())",
                helper = """
                defmodule {row}.HU do
                  @u 1
                  x = Application.compile_env(:nope, :k3, true)

                  if x do
                    Module.delete_attribute(__MODULE__, :u)
                  end

                  v = Module.has_attribute?(__MODULE__, :u)
                  def r, do: unquote(v)
                end
                """,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "body:has-attribute-given-module",
                "defmacro m, do: Macro.escape({row}.HG.r())",
                helper = """
                defmodule {row}.H do
                  def drop(m), do: Module.delete_attribute(m, :e)
                end

                defmodule {row}.HG do
                  @e 1
                  {row}.H.drop(__MODULE__)
                  v = Module.has_attribute?(__MODULE__, :e)
                  def r, do: unquote(v)
                end
                """,
            ),
            // A function the module body calls writes the module it is given through `Module`, as the body's own `@`
            // would. The using module reads what was written through functions it defines.
            macro(
                "called:put",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def put(mod), do: Module.put_attribute(mod, :g_put, 1)
                end

                defmodule {row}.U do
                  {row}.W.put(__MODULE__)
                  def r, do: @g_put
                end
                """,
            ),
            macro(
                "called:register",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def register(mod) do
                    Module.register_attribute(mod, :g_reg, [])
                    Module.put_attribute(mod, :g_reg, :registered)
                  end
                end

                defmodule {row}.U do
                  {row}.W.register(__MODULE__)
                  def r, do: @g_reg
                end
                """,
            ),
            macro(
                "called:accumulate",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def accumulate(mod) do
                    Module.register_attribute(mod, :g_acc, accumulate: true)
                    Module.put_attribute(mod, :g_acc, :first)
                    Module.put_attribute(mod, :g_acc, :second)
                  end
                end

                defmodule {row}.U do
                  {row}.W.accumulate(__MODULE__)
                  @g_acc :third
                  def r, do: @g_acc
                end
                """,
            ),
            macro(
                "called:persist",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def persist(mod) do
                    Module.register_attribute(mod, :g_persist, persist: true)
                    Module.put_attribute(mod, :g_persist, :kept)
                  end
                end

                defmodule {row}.U do
                  {row}.W.persist(__MODULE__)
                  def r, do: @g_persist
                end
                """,
            ),
            macro(
                "called:delete",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def delete(mod), do: Module.delete_attribute(mod, :g_del)
                end

                defmodule {row}.U do
                  @g_del 1
                  {row}.W.delete(__MODULE__)
                  @r Module.has_attribute?(__MODULE__, :g_del)
                  def r, do: @r
                end
                """,
            ),
            // `delete_attribute/2` gives the value it removed.
            macro(
                "called:delete-value",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def delete(mod), do: Module.put_attribute(mod, :g_was, Module.delete_attribute(mod, :g_del))
                end

                defmodule {row}.U do
                  @g_del 1
                  {row}.W.delete(__MODULE__)
                  def r, do: @g_was
                end
                """,
            ),
            macro(
                "called:overridable",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def overridable(mod), do: Module.make_overridable(mod, g_over: 0)
                end

                defmodule {row}.U do
                  def g_over, do: 1
                  {row}.W.overridable(__MODULE__)
                  def g_over, do: super() + 1
                  @o Module.overridable?(__MODULE__, {:g_over, 0})
                  def r, do: {@o, g_over()}
                end
                """,
            ),
            macro(
                "called:overridable-behaviour",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.B do
                  @callback g_beh() :: any
                end

                defmodule {row}.W do
                  def overridable(mod), do: Module.make_overridable(mod, {row}.B)
                end

                defmodule {row}.U do
                  @behaviour {row}.B
                  def g_beh, do: 1
                  {row}.W.overridable(__MODULE__)
                  @o Module.overridable?(__MODULE__, {:g_beh, 0})
                  def r, do: @o
                end
                """,
            ),
            macro(
                "called:delete-definition",
                "defmacro m, do: Macro.escape({row}.U.r())",
                legs = DELETES_DEFINITIONS,
                helper = """
                defmodule {row}.W do
                  def delete(mod), do: Module.delete_definition(mod, {:g_deleted, 0})
                end

                defmodule {row}.U do
                  def g_deleted, do: 1
                  {row}.W.delete(__MODULE__)
                  def kept, do: 1
                  @d {Module.defines?(__MODULE__, {:g_deleted, 0}), Module.defines?(__MODULE__, {:kept, 0})}
                  def r, do: @d
                end
                """,
            ),
            // A removed definition's calls count for nothing: `g` is unused, and `h` is never reported undefined.
            macro(
                "called:delete-definition-locals",
                "defmacro m, do: Macro.escape({row}.U.r())",
                legs = DELETES_DEFINITIONS,
                helper = """
                defmodule {row}.W do
                  def delete(mod), do: Module.delete_definition(mod, {:f, 0})
                end

                defmodule {row}.U do
                  defp g, do: 1
                  def f, do: g() + h()
                  {row}.W.delete(__MODULE__)
                  def r, do: 1
                end
                """,
            ),
            macro(
                "called:apply-delete",
                "defmacro m, do: Macro.escape({row}.U.r())",
                legs = DELETES_DEFINITIONS,
                helper = """
                defmodule {row}.W do
                  def delete(mod), do: apply(Module, :delete_definition, [mod, {:g_deleted, 0}])
                end

                defmodule {row}.U do
                  def g_deleted, do: 1
                  {row}.W.delete(__MODULE__)
                  def kept, do: 1
                  @d {Module.defines?(__MODULE__, {:g_deleted, 0}), Module.defines?(__MODULE__, {:kept, 0})}
                  def r, do: @d
                end
                """,
            ),
            macro(
                "called:body-apply-delete",
                "defmacro m, do: Macro.escape({row}.U.r())",
                legs = DELETES_DEFINITIONS,
                helper = """
                defmodule {row}.U do
                  def g_deleted, do: 1
                  apply(Module, :delete_definition, [__MODULE__, {:g_deleted, 0}])
                  def kept, do: 1
                  @d {Module.defines?(__MODULE__, {:g_deleted, 0}), Module.defines?(__MODULE__, {:kept, 0})}
                  def r, do: @d
                end
                """,
            ),
            // `:elixir_def.take_definition/2` isn't followed, so the module stops where the body calls it.
            macro(
                "called:take-definition",
                "defmacro m, do: Macro.escape({row}.U.r())",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule {row}.W do
                  def take(mod), do: :elixir_def.take_definition(mod, {:g_taken, 0})
                end

                defmodule {row}.U do
                  def g_taken, do: 1
                  {row}.W.take(__MODULE__)
                  def r, do: 1
                end
                """,
            ),
            macro(
                "called:add-doc",
                "defmacro m, do: Macro.escape({row}.U.r())",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule {row}.W do
                  def doc(mod), do: Module.add_doc(mod, 1, :def, {:g_doc, 0}, [], "documented")
                end

                defmodule {row}.U do
                  @a 1
                  {row}.W.doc(__MODULE__)
                  def g_doc, do: 1
                  def r, do: @a
                end
                """,
            ),
            macro(
                "called:eval-quoted",
                "defmacro m, do: Macro.escape({row}.U.r())",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule {row}.W do
                  def eval(mod), do: Module.eval_quoted(mod, quote(do: @g_eval(7)))
                end

                defmodule {row}.U do
                  {row}.W.eval(__MODULE__)
                  def r, do: @g_eval
                end
                """,
            ),
            macro(
                "called:eval-quoted-env",
                "defmacro m, do: Macro.escape({row}.U.r())",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule {row}.W do
                  def eval(env), do: Module.eval_quoted(env, quote(do: @g_eval_env(8)))
                end

                defmodule {row}.U do
                  {row}.W.eval(__ENV__)
                  def r, do: @g_eval_env
                end
                """,
            ),
            macro(
                "called:outer",
                "defmacro m, do: Macro.escape({row}.O.r())",
                helper = """
                defmodule {row}.W do
                  def target(mod), do: Module.put_attribute(mod, :g_target, :from_inner)
                end

                defmodule {row}.O do
                  defmodule Inner do
                    {row}.W.target({row}.O)
                  end

                  def r, do: @g_target
                end
                """,
            ),
            macro(
                "called:unknown-target",
                "defmacro m, do: Macro.escape({row}.U.r())",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule {row}.W do
                  def put(mod), do: Module.put_attribute(Process.get(:gen_dep_target, mod), :g_unknown, 1)
                end

                defmodule {row}.U do
                  {row}.W.put(__MODULE__)
                  def r, do: @g_unknown
                end
                """,
            ),
            macro(
                "called:function-value",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def put(mod) do
                    put = &Module.put_attribute/3
                    put.(mod, :g_fun_value, 1)
                  end
                end

                defmodule {row}.U do
                  {row}.W.put(__MODULE__)
                  def r, do: @g_fun_value
                end
                """,
            ),
            macro(
                "called:body-function-value",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.U do
                  put = &Module.put_attribute/3
                  put.(__MODULE__, :g_body_fun_value, 1)
                  def r, do: @g_body_fun_value
                end
                """,
            ),
            macro(
                "called:apply",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def put(mod), do: apply(Module, :put_attribute, [mod, :g_apply, 1])
                end

                defmodule {row}.U do
                  {row}.W.put(__MODULE__)
                  def r, do: @g_apply
                end
                """,
            ),
            macro(
                "called:body-apply",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.U do
                  apply(Module, :put_attribute, [__MODULE__, :g_body_apply, 1])
                  def r, do: @g_body_apply
                end
                """,
            ),
            // `open?/1` of a module compiled earlier in the file is `false`.
            macro(
                "called:readers",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def read(mod) do
                    Module.put_attribute(mod, :r_get, Module.get_attribute(mod, :g_put))
                    Module.put_attribute(mod, :r_get_default, Module.get_attribute(mod, :g_missing, :default))
                    Module.put_attribute(mod, :r_has, Module.has_attribute?(mod, :g_put))
                    Module.put_attribute(mod, :r_defines, Module.defines?(mod, {:defined, 0}))
                    Module.put_attribute(mod, :r_defines_kind, Module.defines?(mod, {:defined, 0}, :defp))
                    Module.put_attribute(mod, :r_open, Module.open?(mod))
                    Module.put_attribute(mod, :r_open_closed, Module.open?({row}.W))
                    Module.put_attribute(mod, :r_definitions, Module.definitions_in(mod))
                    Module.put_attribute(mod, :r_definitions_kind, Module.definitions_in(mod, :def))
                    Module.put_attribute(mod, :r_overridable, Module.overridable?(mod, {:defined, 0}))
                  end
                end

                defmodule {row}.U do
                  @g_put 5
                  @type t :: integer
                  @spec defined() :: integer
                  def defined, do: 1
                  {row}.W.read(__MODULE__)

                  def r do
                    {@r_get, @r_get_default, @r_has, @r_defines, @r_defines_kind, @r_open, @r_open_closed,
                     @r_definitions, @r_definitions_kind, @r_overridable}
                  end
                end
                """,
            ),
            macro(
                "called:readers-each",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def get(mod), do: Module.put_attribute(mod, :r_get, Module.get_attribute(mod, :g_put))
                  def has(mod), do: Module.put_attribute(mod, :r_has, Module.has_attribute?(mod, :g_put))
                  def defines(mod), do: Module.put_attribute(mod, :r_defines, Module.defines?(mod, {:defined, 0}))
                  def open(mod), do: Module.put_attribute(mod, :r_open, Module.open?(mod))
                  def definitions(mod), do: Module.put_attribute(mod, :r_definitions, Module.definitions_in(mod))
                  def overridable(mod), do: Module.put_attribute(mod, :r_overridable, Module.overridable?(mod, {:defined, 0}))
                end

                defmodule {row}.U do
                  @g_put 5
                  def defined, do: 1
                  {row}.W.get(__MODULE__)
                  {row}.W.has(__MODULE__)
                  {row}.W.defines(__MODULE__)
                  {row}.W.open(__MODULE__)
                  {row}.W.definitions(__MODULE__)
                  {row}.W.overridable(__MODULE__)
                  def r, do: {@r_get, @r_has, @r_defines, @r_open, @r_definitions, @r_overridable}
                end
                """,
            ),
            macro(
                "called:readers-gaps",
                "defmacro m, do: Macro.escape({row}.U.r())",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule {row}.W do
                  def type(mod), do: Module.put_attribute(mod, :r_type, Module.defines_type?(mod, {:t, 0}))
                  def callback(mod), do: Module.put_attribute(mod, :r_callback, Module.spec_to_callback(mod, {:defined, 0}))
                end

                defmodule {row}.U do
                  @type t :: integer
                  @spec defined() :: integer
                  def defined, do: 1
                  {row}.W.type(__MODULE__)
                  {row}.W.callback(__MODULE__)
                  def r, do: {@r_type, @r_callback}
                end
                """,
            ),
            macro(
                "called:readers-get-definition",
                "defmacro m, do: Macro.escape({row}.U.r())",
                legs = DELETES_DEFINITIONS,
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule {row}.W do
                  def get(mod), do: Module.put_attribute(mod, :r_get, elem(Module.get_definition(mod, {:defined, 0}), 0))
                end

                defmodule {row}.U do
                  def defined, do: 1
                  {row}.W.get(__MODULE__)
                  def r, do: @r_get
                end
                """,
            ),
            // A reader's answer comes back as the called function's value. `definitions_in/2` reads the set table,
            // whose order Elixir doesn't fix.
            macro(
                "called:readers-value",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def read(mod) do
                    {Module.open?(mod), Module.open?({row}.W), Module.definitions_in(mod),
                     Enum.sort(Module.definitions_in(mod, :def)), Module.overridable?(mod, {:b, 0}),
                     Module.overridable?(mod, {:c, 1})}
                  end
                end

                defmodule {row}.U do
                  def b, do: a()
                  defp a, do: 1
                  def c(x \\ 1), do: x
                  defoverridable b: 0
                  def b, do: 2
                  v = {row}.W.read(__MODULE__)
                  def r, do: unquote(Macro.escape(v))
                end
                """,
            ),
            // `attributes_in/1` reads the set table, whose order Elixir doesn't fix.
            macro(
                "called:readers-listed",
                "defmacro m, do: Macro.escape({row}.U.r())",
                legs = { MODULE_ATTRIBUTES_IN.isSufficient(it) && MODULE_OVERRIDABLES_IN.isSufficient(it) },
                helper = """
                defmodule {row}.W do
                  def read(mod), do: {Enum.sort(Module.attributes_in(mod)), Module.overridables_in(mod)}
                end

                defmodule {row}.U do
                  @a 1
                  Module.register_attribute(__MODULE__, :reg, [])
                  def f, do: @a
                  def g, do: 1
                  defoverridable g: 0, f: 0
                  def f, do: 2
                  v = {row}.W.read(__MODULE__)
                  def r, do: unquote(Macro.escape(v))
                end
                """,
            ),
            macro(
                "called:readers-last",
                "defmacro m, do: Macro.escape({row}.U.r())",
                legs = { MODULE_GET_LAST_ATTRIBUTE.isSufficient(it) },
                helper = """
                defmodule {row}.W do
                  def read(mod) do
                    {Module.get_last_attribute(mod, :acc), Module.get_last_attribute(mod, :empty),
                     Module.get_last_attribute(mod, :empty, :d), Module.get_last_attribute(mod, :set),
                     Module.get_last_attribute(mod, :reg, :d), Module.get_last_attribute(mod, :missing, :d)}
                  end
                end

                defmodule {row}.U do
                  Module.register_attribute(__MODULE__, :acc, accumulate: true)
                  @acc 1
                  @acc 2
                  Module.register_attribute(__MODULE__, :empty, accumulate: true)
                  Module.register_attribute(__MODULE__, :reg, [])
                  @set [3]
                  v = {row}.W.read(__MODULE__)
                  def r, do: unquote(Macro.escape(v))
                end
                """,
            ),
            // A reader's guards raise as its clauses do.
            macro(
                "called:readers-guards",
                """
                defmacro m do
                  raised = fn f ->
                    try do
                      f.()
                    rescue
                      e in FunctionClauseError -> {e.function, e.arity}
                    end
                  end

                  [
                    raised.(fn -> Module.open?("m") end),
                    raised.(fn -> Module.definitions_in("m") end),
                    raised.(fn -> Module.definitions_in(__CALLER__.module, :other) end),
                    raised.(fn -> Module.overridable?(__CALLER__.module, {:f, 256}) end),
                    raised.(fn -> Module.overridable?(__CALLER__.module, {"f", 0}) end)
                  ]
                end
                """,
            ),
            macro(
                "called:readers-guards-listed",
                """
                defmacro m do
                  raised = fn f ->
                    try do
                      f.()
                    rescue
                      e in FunctionClauseError -> {e.function, e.arity}
                    end
                  end

                  [raised.(fn -> Module.attributes_in("m") end), raised.(fn -> Module.overridables_in("m") end)]
                end
                """,
                legs = { MODULE_ATTRIBUTES_IN.isSufficient(it) && MODULE_OVERRIDABLES_IN.isSufficient(it) },
            ),
            macro(
                "called:readers-guards-last",
                """
                defmacro m do
                  try do
                    Module.get_last_attribute(__CALLER__.module, "k")
                  rescue
                    e in FunctionClauseError -> {e.function, e.arity}
                  end
                end
                """,
                legs = { MODULE_GET_LAST_ATTRIBUTE.isSufficient(it) },
            ),
            // From 1.20 `overridable?/2` guards its module, before which one that isn't an atom raises from its tables.
            macro(
                "called:overridable-module-guard",
                """
                defmacro m do
                  try do
                    Module.overridable?("m", {:f, 0})
                  rescue
                    e in FunctionClauseError -> {e.function, e.arity}
                  end
                end
                """,
                pins = { _, level -> OPAQUE.takeUnless { OVERRIDABLE_GUARDS_MODULE.isSufficient(level) } },
            ),
            // A macro's body writes the module it expands in.
            macro(
                "called:macro",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def put(mod), do: Module.put_attribute(mod, :g_put, 1)

                  def accumulate(mod) do
                    Module.register_attribute(mod, :g_acc, accumulate: true)
                    Module.put_attribute(mod, :g_acc, :first)
                    Module.put_attribute(mod, :g_acc, :second)
                  end
                end

                defmodule {row}.Mac do
                  defmacro write_in_macro do
                    {row}.W.put(__CALLER__.module)
                    {row}.W.accumulate(__CALLER__.module)
                    :ok
                  end
                end

                defmodule {row}.U do
                  require {row}.Mac
                  {row}.Mac.write_in_macro()
                  def r, do: {@g_put, @g_acc}
                end
                """,
            ),
            macro(
                "called:in-def",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.Mac do
                  defmacro write_in_def do
                    Module.put_attribute(__CALLER__.module, :g_in_def, elem(__CALLER__.function, 0))
                    :ok
                  end

                  defmacro __before_compile__(env) do
                    seen = Module.get_attribute(env.module, :g_in_def)
                    quote(do: def(before_compile_saw, do: unquote(seen)))
                  end
                end

                defmodule {row}.U do
                  require {row}.Mac
                  Module.register_attribute(__MODULE__, :g_in_def, accumulate: true)
                  @before_compile {row}.Mac
                  def a, do: {row}.Mac.write_in_def()
                  def b, do: {row}.Mac.write_in_def()
                  def r, do: before_compile_saw()
                end
                """,
            ),
            macro(
                "called:before-compile",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.Mac do
                  defmacro __before_compile__(env) do
                    Module.put_attribute(env.module, :g_before_compile, :written)
                    quote(do: def(before_compile_saw, do: @g_before_compile))
                  end
                end

                defmodule {row}.U do
                  @before_compile {row}.Mac
                  def r, do: before_compile_saw()
                end
                """,
            ),
            // Each `@on_definition` callback runs as its definition is stored, before the next.
            macro(
                "called:on-definition",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.Mac do
                  def __on_definition__(env, kind, name, args, _guards, _body) do
                    Module.put_attribute(env.module, :g_on_definition, {kind, name, length(args)})
                  end
                end

                defmodule {row}.U do
                  Module.register_attribute(__MODULE__, :g_on_definition, accumulate: true)
                  @on_definition {row}.Mac
                  def a, do: 1
                  def seen_after_a, do: @g_on_definition
                  defp b(x), do: x
                  defmacro c, do: 1
                  def seen_after_c, do: @g_on_definition
                  def r, do: {seen_after_a(), seen_after_c(), b(1)}
                end
                """,
            ),
            macro(
                "called:routes",
                "defmacro m, do: Macro.escape({row}.Router.routes())",
                helper = """
                defmodule {row}.Routes do
                  def setup(module) do
                    Module.register_attribute(module, :routes, accumulate: true)
                    Module.put_attribute(module, :before_compile, {row}.Routes)
                  end

                  def add(module, name), do: Module.put_attribute(module, :routes, name)

                  defmacro __before_compile__(env) do
                    routes = env.module |> Module.get_attribute(:routes) |> Enum.reverse()

                    quote do
                      def routes, do: unquote(routes)
                    end
                  end
                end

                defmodule {row}.Router do
                  {row}.Routes.setup(__MODULE__)
                  {row}.Routes.add(__MODULE__, :home)
                  {row}.Routes.add(__MODULE__, :about)

                  def home, do: :ok
                end
                """,
            ),
            macro(
                "called:closed-target",
                "defmacro m, do: Macro.escape({row}.W.put({row}.C))",
                pins = DECLINED,
                helper = """
                defmodule {row}.C do
                end

                defmodule {row}.W do
                  def put(mod), do: Module.put_attribute(mod, :a, 1)
                end
                """,
            ),
            macro(
                "called:closed-target-has",
                "defmacro m, do: Macro.escape(Module.has_attribute?({row}.C, :a))",
                pins = DECLINED,
                helper = """
                defmodule {row}.C do
                  @a 1
                end
                """,
            ),
            macro(
                "called:in-match",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def put(mod) do
                    Module.put_attribute(mod, :g_acc, :one)
                    :returned
                  end
                end

                defmodule {row}.U do
                  Module.register_attribute(__MODULE__, :g_acc, accumulate: true)
                  x = {row}.W.put(__MODULE__)
                  @x x
                  def r, do: {@g_acc, @x}
                end
                """,
            ),
            macro(
                "called:in-match-declines",
                "defmacro m, do: Macro.escape({row}.U.r())",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule {row}.W do
                  def set(mod) do
                    Module.put_attribute(mod, :a, Process.get(:k, 2))
                    :ok
                  end
                end

                defmodule {row}.U do
                  @a 1
                  x = {row}.W.set(__MODULE__)
                  @x x
                  def r, do: @a
                end
                """,
            ),
            macro(
                "called:direct-writer-in-match-declines",
                "defmacro m, do: Macro.escape({row}.U.r())",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule {row}.U do
                  @a 1
                  x = Module.put_attribute(__MODULE__, :a, Process.get(:k, 2))
                  @x x
                  def r, do: @a
                end
                """,
            ),
            macro(
                "called:nested-argument",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def add(mod, name), do: Module.put_attribute(mod, :g_acc, name)

                  def name(mod) do
                    Module.put_attribute(mod, :g_acc, :inner)
                    :outer
                  end
                end

                defmodule {row}.U do
                  Module.register_attribute(__MODULE__, :g_acc, accumulate: true)
                  {row}.W.add(__MODULE__, {row}.W.name(__MODULE__))
                  def r, do: @g_acc
                end
                """,
            ),
            macro(
                "called:attribute-value",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def compute(mod) do
                    Module.put_attribute(mod, :g_acc, :computed)
                    42
                  end
                end

                defmodule {row}.U do
                  Module.register_attribute(__MODULE__, :g_acc, accumulate: true)
                  @x {row}.W.compute(__MODULE__)
                  def r, do: {@x, @g_acc}
                end
                """,
            ),
            macro(
                "called:attribute-value-declines",
                "defmacro m, do: Macro.escape({row}.U.r())",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule {row}.W do
                  def set(mod) do
                    Module.put_attribute(mod, :a, Process.get(:k, 2))
                    :ok
                  end
                end

                defmodule {row}.U do
                  @a 1
                  @x {row}.W.set(__MODULE__)
                  def r, do: {@a, @x}
                end
                """,
            ),
            macro(
                "called:in-if",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def a(mod), do: Module.put_attribute(mod, :g_if, :a)
                  def b(mod), do: Module.put_attribute(mod, :g_if, :b)
                end

                defmodule {row}.U do
                  flag = length([1]) == 1

                  if flag do
                    {row}.W.a(__MODULE__)
                  else
                    {row}.W.b(__MODULE__)
                  end

                  def r, do: @g_if
                end
                """,
            ),
            macro(
                "called:in-for",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def setup(mod) do
                    Module.register_attribute(mod, :g_acc, accumulate: true)
                    [:x, :y]
                  end

                  def add(mod, x), do: Module.put_attribute(mod, :g_acc, x)
                end

                defmodule {row}.U do
                  for x <- {row}.W.setup(__MODULE__), do: {row}.W.add(__MODULE__, x)
                  def r, do: @g_acc
                end
                """,
            ),
            macro(
                "called:in-cond",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def a(mod), do: Module.put_attribute(mod, :g_if, :a)
                  def b(mod), do: Module.put_attribute(mod, :g_if, :b)
                end

                defmodule {row}.U do
                  cond do
                    length([1]) == 2 -> {row}.W.a(__MODULE__)
                    true -> {row}.W.b(__MODULE__)
                  end

                  def r, do: @g_if
                end
                """,
            ),
            macro(
                "called:in-cond-take",
                "defmacro m, do: Macro.escape({row}.U.r())",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule {row}.W do
                  def take(mod), do: :elixir_def.take_definition(mod, {:g_taken, 0})
                end

                defmodule {row}.U do
                  def g_taken, do: 1

                  cond do
                    length([1]) == 1 -> {row}.W.take(__MODULE__)
                    true -> :ok
                  end

                  def r, do: 1
                end
                """,
            ),
            // A remover in a match is the native's call alone: the row doesn't take the definition out again.
            macro(
                "called:remover-in-match",
                "defmacro m, do: Macro.escape({row}.U.r())",
                legs = DELETES_DEFINITIONS,
                helper = """
                defmodule {row}.U do
                  def f, do: 1
                  x = Module.delete_definition(__MODULE__, {:f, 0})
                  @x x
                  @d Module.defines?(__MODULE__, {:f, 0})
                  def r, do: {@x, @d}
                end
                """,
            ),
            macro(
                "called:declined-goes-on",
                "defmacro m, do: Macro.escape({{row}.U.fa(), {row}.U.g()})",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule {row}.W do
                  def a(mod), do: Module.put_attribute(mod, :a, 1)

                  def b(mod) do
                    Module.put_attribute(mod, :c, 3)
                    Module.put_attribute(mod, :b, Process.get(:k, 2))
                  end
                end

                defmodule {row}.U do
                  {row}.W.a(__MODULE__)
                  def fa, do: @a
                  {row}.W.b(__MODULE__)
                  def fb, do: @a
                  def g, do: 1
                end
                """,
            ),
            macro(
                "called:on-definition-declines",
                "defmacro m, do: Macro.escape({{row}.U.f(), {row}.U.g()})",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule {row}.Mac do
                  def __on_definition__(_env, _kind, _name, _args, _guards, _body), do: Process.get(:k)
                end

                defmodule {row}.U do
                  @on_definition {row}.Mac
                  def f, do: 1
                  def g, do: 2
                end
                """,
            ),
            // A value `Import.Term` can't hold is read as Elixir has it, in a function and through `Module`.
            macro(
                "attribute:map-value",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def get(mod), do: Module.get_attribute(mod, :m)
                end

                defmodule {row}.U do
                  @m %{a: 1}
                  def f, do: @m
                  @n {row}.W.get(__MODULE__)
                  def r, do: {f(), @n}
                end
                """,
            ),
            macro(
                "attribute:held-element-atom",
                "defmacro m, do: Macro.escape({row}.U.r())",
                helper = """
                defmodule {row}.W do
                  def get(mod) do
                    [_, _] = Module.get_attribute(mod, :acc)
                    String.to_existing_atom("held_element_atom")
                  end
                end

                defmodule {row}.U do
                  Module.register_attribute(__MODULE__, :acc, accumulate: true)
                  @acc %{}
                  @acc :held_element_atom
                  @n {row}.W.get(__MODULE__)
                  def r, do: @n
                end
                """,
            ),
            macro(
                "attribute:in-guard-range",
                "defmacro m, do: Macro.escape({{row}.U.f(2), {row}.U.f(5)})",
                helper = """
                defmodule {row}.U do
                  @r 1..3
                  def f(x) when x in @r, do: :in
                  def f(_), do: :out
                end
                """,
            ),
            // The application's environment isn't known, so neither is what these read, and whether `compile_env!` or
            // `fetch_env!` finds the key isn't either: none stops the module, which loads as compiled.
            macro(
                "body:compile-env",
                "defmacro m, do: Macro.escape({row}.CE.f())",
                helper = """
                defmodule {row}.CE do
                  _ = Application.compile_env(:nope, :k, :d)
                  _ = Application.compile_env!(:elixir, :check_endianness)
                  def f, do: 1
                end
                """,
            ),
            macro(
                "body:fetch-env",
                "defmacro m, do: Macro.escape({row}.FE.f())",
                helper = """
                defmodule {row}.FE do
                  _ = Application.get_env(:nope, __MODULE__)
                  _ = Application.fetch_env(:nope, __MODULE__)
                  def f, do: 1
                end
                """,
            ),
            // Named for a key the `:elixir` application sets, so that the call is given the module and Elixir doesn't
            // raise.
            macro(
                "body:fetch-env-bang",
                "defmacro m, do: Macro.escape(:check_endianness.f())",
                helper = """
                defmodule :check_endianness do
                  _ = Application.fetch_env!(:elixir, __MODULE__)
                  def f, do: 1
                end
                """,
            ),
            macro(
                "body:compile-env-guard",
                "defmacro m, do: Macro.escape({row}.CG.r())",
                helper = """
                defmodule {row}.CG do
                  @e (try do
                    Application.compile_env(:kernel, "k")
                  rescue
                    e in FunctionClauseError -> {e.function, e.arity}
                  end)
                  def r, do: @e
                end
                """,
            ),
            // `get_env/2` calls `get_env/3`, whose clause the app fails.
            macro(
                "body:env-app-guard",
                "defmacro m, do: Macro.escape({row}.AG.r())",
                helper = """
                defmodule {row}.AG do
                  @g (try do
                    Application.get_env("app", :k)
                  rescue
                    e in FunctionClauseError -> {e.module, e.function, e.arity}
                  end)
                  @f (try do
                    Application.fetch_env("app", :k)
                  rescue
                    e in FunctionClauseError -> {e.module, e.function, e.arity}
                  end)
                  def r, do: {@g, @f}
                end
                """,
            ),
            // `fetch_compile_env` reads the app through `fetch_env/2`, and from 1.14 `compile_env/4` matches its env.
            macro(
                "body:compile-env-app-and-env-guards",
                "defmacro m, do: Macro.escape({row}.CA.r())",
                helper = """
                defmodule {row}.CA do
                  @a (try do
                    Application.compile_env("app", :k)
                  rescue
                    e in FunctionClauseError -> {e.module, e.function, e.arity}
                  end)
                  @e (try do
                    Application.compile_env(__ENV__.file, :app, :k, nil)
                  rescue
                    e in FunctionClauseError -> {e.module, e.function, e.arity}
                  end)
                  def r, do: {@a, @e}
                end
                """,
                legs = COMPILE_ENV_TAKES_ENV_FIRST::isSufficient,
            ),
            // Before 1.14 `__compile_env__/4` reads the app through `fetch_env/2`, then the env's tracers.
            macro(
                "body:compile-env-app-and-env-guards-env-last",
                "defmacro m, do: Macro.escape({row}.CA.r())",
                helper = """
                defmodule {row}.CA do
                  @a (try do
                    Application.__compile_env__("app", :k, nil, __ENV__)
                  rescue
                    e in FunctionClauseError -> {e.module, e.function, e.arity}
                  end)
                  @e (try do
                    Application.__compile_env__(:app, :k, nil, __ENV__.file)
                  rescue
                    ArgumentError -> :argument_error
                  end)
                  def r, do: {@a, @e}
                end
                """,
                legs = { !COMPILE_ENV_TAKES_ENV_FIRST.isSufficient(it) },
            ),
            macro(
                "body:attribute-point",
                "defmacro m, do: Macro.escape({{row}.A.f(), {row}.P.__protocol__(:functions), " +
                    "{row}.Q.__protocol__(:functions), {row}.P.Any.g(1)})",
                helper = """
                defmodule {row}.A do
                  @x 1
                  @x 2
                  def f, do: unquote(@x)
                  @x 3
                end

                defprotocol {row}.P do
                  @fallback_to_any true
                  def g(t)
                end

                defimpl {row}.P, for: Any do
                  def g(_), do: :any
                end

                defprotocol {row}.Q do
                  def h(t)
                  def k(t, u)
                end
                """,
            ),
            // Every `defimpl` body is `res = (...)` from v1.14, so the body's own `=` runs once, inside it.
            macro(
                "body:impl-match",
                "defmacro m, do: Macro.escape({row}.P.Atom.f(:a))",
                helper = """
                defprotocol {row}.P do
                  def f(a)
                end

                defimpl {row}.P, for: Atom do
                  y = Module.get_attribute(__MODULE__, :x)
                  @x {y}
                  def f(_), do: @x
                end
                """,
            ),
            // `get_attribute/2` is `get_attribute/3` with its default, whose guard is the one that fails.
            macro(
                "body:get-attribute-guard",
                """
                defmacro m do
                  try do
                    Module.get_attribute(__CALLER__.module, "k")
                  rescue
                    e in FunctionClauseError -> {e.function, e.arity}
                  end
                end
                """,
            ),
            macro(
                "body:attribute-from-binding",
                "defmacro m, do: Macro.escape({{row}.AB.f(), {row}.AB.g()})",
                helper = """
                defmodule {row}.AB do
                  x = 1
                  @a x
                  def f, do: unquote(@a)
                  def g, do: @a
                end
                """,
            ),
            // A file's variables are seen by every module after them in the file, so the name is the row's own.
            macro(
                "body:outer-binding",
                "defmacro m, do: Macro.escape({row}.OB.f())",
                helper = """
                outer_binding = :top

                defmodule {row}.OB do
                  def f, do: unquote(outer_binding)
                end
                """,
            ),
            macro(
                "body:nested-binding",
                "defmacro m, do: Macro.escape({row}.NB.C.g())",
                helper = """
                defmodule {row}.NB do
                  y = :outer

                  defmodule C do
                    def g, do: unquote(y)
                  end
                end
                """,
            ),
            // A macro's output at the file's top level binds there: `defimpl`'s `for`, which `__impl__/1` unquotes.
            macro(
                "body:file-macro-match",
                "defmacro m, do: Macro.escape(String.Chars.{row}.FM.__impl__(:for))",
                helper = """
                defimpl String.Chars, for: {row}.FM do
                  def to_string(_), do: "fm"
                end
                """,
            ),
            macro(
                "body:defines-value",
                "defmacro m, do: Macro.escape({row}.W.r())",
                helper = """
                defmodule {row}.W do
                  @pre Module.defines?(__MODULE__, {:f, 0})
                  def f, do: 1
                  @post Module.defines?(__MODULE__, {:f, 0})
                  def r, do: {@pre, @post}
                end
                """,
            ),
            macro(
                "body:overridable-from-binding",
                "defmacro m, do: Macro.escape({row}.OV.f())",
                helper = """
                defmodule {row}.OV do
                  def f, do: 1
                  fs = [f: 0]
                  defoverridable fs
                  def f, do: super() + 1
                end
                """,
            ),
            // Before `__struct__/0` returns the escaped struct, it reads the struct from an attribute, a map the
            // expander holds no term for.
            macro(
                "body:defstruct-binding",
                "defmacro m, do: Macro.escape({{row}.S.__struct__(), {row}.E.exception([]).code, " +
                    "Exception.message({row}.E.exception([]))})",
                pins = { source, level ->
                    DECLINED_FROM_SOURCE(source, level)?.takeUnless { DEFSTRUCT_ESCAPES_STRUCT.isSufficient(level) }
                },
                unordered = true,
                helper = """
                defmodule {row}.S do
                  fields = [a: 1, b: 2]
                  defstruct fields
                end

                defmodule {row}.E do
                  f = [message: "boom", code: 7]
                  defexception f
                end
                """,
            ),
            // `defdelegate`'s options are read where the body runs it: `as: @a` at its point, and a variable's list.
            macro(
                "defdelegate:as-attribute-point",
                "defmacro m, do: Macro.escape({{row}.DA.f(1), {row}.DA.g(2)})",
                helper = """
                defmodule {row}.DM do
                  def foo(x), do: {:foo, x}
                  def bar(x), do: {:bar, x}
                end

                defmodule {row}.DA do
                  @a :foo
                  defdelegate f(x), to: {row}.DM, as: @a
                  @a :bar
                  opts = [to: {row}.DM, as: :bar]
                  defdelegate g(x), opts
                end
                """,
            ),
            macro(
                "fragment:name",
                "defmacro m, do: Macro.escape({row}.FN.f())",
                helper = """
                defmodule {row}.FN do
                  n = :f
                  def unquote(n)(), do: 1
                end
                """,
            ),
            macro(
                "fragment:head",
                "defmacro m, do: Macro.escape({row}.FH.g(3))",
                helper = """
                defmodule {row}.FH do
                  h = quote(do: g(_))
                  def unquote(h), do: 2
                end
                """,
            ),
            macro(
                "fragment:args",
                "defmacro m, do: Macro.escape({{row}.FA.f(2), {row}.FA.f(3)})",
                helper = """
                defmodule {row}.FA do
                  n = 2
                  def f(unquote(n)), do: :two
                  def f(_), do: :other
                end
                """,
            ),
            macro(
                "fragment:body",
                "defmacro m, do: Macro.escape({row}.FB.b())",
                helper = """
                defmodule {row}.FB do
                  x = {1, [:a]}
                  def b, do: unquote(Macro.escape(x))
                end
                """,
            ),
            macro(
                "fragment:splice",
                "defmacro m, do: Macro.escape({{row}.FS.f(1, 2), {row}.FS.g(), {row}.FS.h(), {row}.FS.k([4]), {row}.FS.l([4]), {row}.FS.p([0, 1, 2, 3])})",
                helper = """
                defmodule {row}.FS do
                  args = [{:a, [], nil}, {:b, [], nil}]
                  xs = [1, 2]
                  ys = [7]
                  def f(unquote_splicing(args)), do: {a, b}
                  def g, do: [0, unquote_splicing(xs), 3]
                  def h, do: [0, unquote_splicing(xs) | [3]]
                  def k(t), do: [0, unquote_splicing(xs) | t]
                  def l(t), do: [unquote_splicing(ys), 9, unquote_splicing(xs) | t]
                  def p([0, unquote_splicing(xs) | [3]]), do: :ok
                end
                """,
            ),
            // A call's arguments, a called name's, a `{}` tuple's elements, a splicing pair's and a block's expressions
            // splice before a tail as a list does.
            macro(
                "fragment:splice-before-tail-elements",
                "defmacro m, do: Macro.escape({{row}.FT.g(), {row}.FT.t(), {row}.FT.f(1, 2, 3), {row}.FT.b(), {row}.FT.sp(), {row}.FT.one(), {row}.FT.cn()})",
                helper = """
                defmodule {row}.FT do
                  xs = [1, 2]
                  vs = [{:a, [], nil}, {:b, [], nil}]
                  def foo(a, b, c), do: a + b + c
                  def g, do: foo(unquote_splicing(xs) | [3])
                  def t, do: {0, 9, unquote_splicing(xs) | [3]}
                  def f(unquote_splicing(vs) | [c]), do: {a, b, c}
                  def b, do: (0; unquote_splicing(xs) | [3])
                  def sp, do: {unquote_splicing([0]), unquote_splicing(xs) | [3]}
                  def one, do: {unquote_splicing(xs) | [3]}
                  def cn, do: :erlang.unquote(:+)(unquote_splicing(xs) | [])
                end
                """,
            ),
            // A pair splices as a `{}` tuple, a pair that doesn't still holds fragments, and so does an alias.
            macro(
                "fragment:splice-pair-and-alias",
                "defmacro m, do: Macro.escape({{row}.FP.p(), {row}.FP.q(), {row}.FP.r(), {row}.FP.a(), {row}.FP.n()})",
                helper = """
                defmodule {row}.FP do
                  xs = [1, 2]
                  m = Enum
                  def p, do: {unquote_splicing(xs), 3}
                  def q, do: {0, unquote_splicing(xs)}
                  def r, do: {unquote_splicing([1]), 2}
                  def a, do: unquote(m).Bar
                  def n, do: {[unquote_splicing(xs)], 9}
                end
                """,
            ),
            // A `{}` tuple whose splices leave two elements is quoted as a `{}` call, not a pair, so a macro reading
            // it as a keyword list doesn't find the key.
            macro(
                "fragment:splice-to-two",
                "defmacro m, do: Macro.escape({{row}.FK.g(), {row}.FK.h(), {row}.FK.i()})",
                helper = """
                defmodule {row}.KW do
                  defmacro k(opts), do: Keyword.get(opts, :k, :no)
                end

                defmodule {row}.FK do
                  require {row}.KW
                  ys = []
                  zs = [:k]
                  ws = [:k, 3]
                  def g, do: {row}.KW.k([{:k, unquote_splicing(ys), 1}])
                  def h, do: {row}.KW.k([{unquote_splicing(zs), 2}])
                  def i, do: {row}.KW.k([{unquote_splicing(ws)}])
                end
                """,
            ),
            // A `{}` call of two expands as that node, not as a pair, so a directive's options don't read it as one.
            macro(
                "fragment:splice-to-two-option",
                "defmacro m, do: Macro.escape({{row}.FI.l(), {row}.FI.l2(), {row}.FI.l3()})",
                helper = """
                defmodule {row}.FI do
                  x = [Foo]
                  y = [:as, Bar]

                  def l do
                    alias List, [{:as, unquote_splicing(x)}]
                    Foo
                  end

                  def l2 do
                    alias List, [{unquote_splicing(y)}]
                    Bar
                  end

                  def l3 do
                    import List, [{:only, unquote_splicing([[first: 1]])}]
                    last([1, 2])
                  end
                end
                """,
            ),
            macro(
                "fragment:call-name",
                "defmacro m, do: Macro.escape({{row}.FC.f(:a), {row}.FC.f(1)})",
                helper = """
                defmodule {row}.FC do
                  guard = :is_atom
                  def f(x) when :erlang.unquote(guard)(x), do: :atom
                  def f(_), do: :other
                end
                """,
            ),
            // `in/2` expands its right side, so `@list` in a function is the attribute's value escaped.
            macro(
                "attribute:in-guard",
                "defmacro m, do: Macro.escape({{row}.AG.f(1), {row}.AG.f(9)})",
                helper = """
                defmodule {row}.AG do
                  @list [1, 2]
                  def f(x) when x in @list, do: {:list, x}
                  def f(x), do: {:other, x}
                end
                """,
            ),
            // `@tuple` in a function is the attribute's value escaped, and a three-tuple's node carries the escape's own
            // metadata, marked as the escape marks a call's.
            macro(
                "attribute:in-function-tuple-meta",
                "defmacro m, do: Macro.escape({row}.TG.f())",
                helper = """
                defmodule {row}.TGM do
                  defmacro meta(x), do: Macro.escape(elem(Macro.expand(x, __CALLER__), 1))
                end

                defmodule {row}.TG do
                  require {row}.TGM
                  @tuple {1, 2, 3}
                  def f, do: {row}.TGM.meta(@tuple)
                end
                """,
            ),
            // The rows from here to `fragment:splice-in-tuple` write their module-body code into the case module by
            // `use`, so the run from `Dbgi` evaluates the body as the run from source does.
            use(
                "body:use-match-fragment",
                """
                defmacro __using__(_) do
                  quote unquote: false do
                    x = {1, [:a]}
                    {a, [b]} = x
                    def unquote(elem({:f, :g}, a))(), do: unquote(Macro.escape({x, b}))
                  end
                end
                """,
            ),
            use(
                "body:use-attribute-point",
                """
                defmacro __using__(_) do
                  quote unquote: false do
                    @x 1
                    @x 2
                    def unquote(elem({:f0, :f1, :f2, :f3}, @x))(), do: 1
                    @x 3
                    def unquote(elem({:g0, :g1, :g2, :g3}, @x))(), do: 1
                  end
                end
                """,
            ),
            use(
                "body:use-defdelegate-options",
                """
                defmacro __using__(_) do
                  quote unquote: false do
                    opts = [to: Kernel, as: :abs]
                    defdelegate g(x), opts
                  end
                end
                """,
            ),
            // Below `DEFSTRUCT_ESCAPES_STRUCT` the struct is read from a map the expander holds no term for.
            use(
                "body:use-defstruct-binding",
                """
                defmacro __using__(_) do
                  quote unquote: false do
                    fields = [a: 1, b: 2]
                    defstruct fields
                  end
                end
                """,
                legs = { DEFSTRUCT_ESCAPES_STRUCT.isSufficient(it) },
            ),
            // A `for` in the module body defines once per element, each `def` with that element's bindings.
            use(
                "body:for-defines",
                """
                defmacro __using__(fields) do
                  quote bind_quoted: [fields: fields] do
                    for {name, default} <- fields do
                      def unquote(name)(), do: unquote(default)
                      def unquote(:"#{name}_or")(value), do: value || unquote(default)
                    end
                  end
                end
                """,
                options = "port: 4000, host: \"localhost\"",
            ),
            // A `for` over a call that writes the module runs the call once, and defines what Elixir does.
            use(
                "body:for-setup",
                """
                def __setup__(module, opts) do
                  Module.register_attribute(module, :names, accumulate: true)
                  Keyword.keys(opts)
                end

                defmacro __using__(opts) do
                  quote bind_quoted: [opts: opts] do
                    for name <- {row}.__setup__(__MODULE__, opts) do
                      def unquote(name)(), do: unquote(name)
                    end
                  end
                end
                """,
                options = "a: 1, b: 2",
            ),
            // A `def` in a module-body `fn` is stored each time the `fn` is called, with that call's bindings.
            use(
                "body:enum-each-defines",
                """
                defmacro __using__(_) do
                  quote unquote: false do
                    Enum.each([:a, :b], fn n -> def unquote(n)(), do: unquote(n) end)
                  end
                end
                """,
            ),
            use(
                "body:foreach-defines",
                """
                defmacro __using__(_) do
                  quote unquote: false do
                    :lists.foreach(fn n -> def unquote(n)(), do: unquote(n) end, [:a, :b])
                  end
                end
                """,
            ),
            // A definition the module body evaluates is stored wherever it is: as an element, a value, an argument or a
            // match's right side. A quoted one held in a tuple is data.
            *listOf(
                "tuple" to "{:a, def(f, do: 1)}",
                "list" to "[def(f, do: 1)]",
                "map-value" to "%{a: def(f, do: 1)}",
                "map-update-value" to "m = %{a: 0}\n%{m | a: def(f, do: 1)}",
                "call-argument" to "List.wrap(def(f, do: 1))",
                "match" to "x = def(f, do: 1)",
                "quoted-in-tuple" to "code = quote(do: def(f, do: 1))\n{true, code}",
            )
                .map { (position, statement) -> caller("body:def-in-$position", "defmacro m, do: :ok", statement) }
                .toTypedArray(),
            // A map update raises where its map lacks a key it updates. Before OTP 25.3.1 the compiler drops one whose
            // value nothing reads, as a statement, an element of one, a statement of a `case` clause or bound to a
            // variable nothing reads, so it raises only where its value is read.
            *listOf(
                "statement" to "%{m | a: 1}",
                "in-tuple" to "{%{m | a: 1}, :ok}",
                "in-case-clause" to "case :a do\n  :a ->\n    %{m | a: 1}\n    :ok\nend",
                "bound" to "_x = %{m | a: 1}",
            )
                .map { (position, statement) ->
                    caller(
                        "body:map-update-$position-raises",
                        "defmacro m, do: :ok",
                        "m = %{}\n$statement\ndef f, do: 1",
                        ends = { level -> "raised Elixir.KeyError".takeIf { MAP_UPDATE_RAISES_WHERE_DISCARDED.isSufficient(level) } },
                    )
                }
                .toTypedArray(),
            caller(
                "body:map-update-argument-raises",
                "defmacro m, do: :ok",
                "m = %{}\nList.wrap(%{m | a: 1})\ndef f, do: 1",
                ends = { "raised Elixir.KeyError" },
            ),
            caller("body:map-update-of-non-map-raises", "defmacro m, do: :ok", "m = 1\n%{m | a: 1}\ndef f, do: 1", ends = { "raised Elixir.BadMapError" }),
            // Nothing reads a `try`'s `after`, so before OTP 25.3.1 the compiler drops an update there, wherever the
            // `try` is.
            caller(
                "body:map-update-in-after-raises",
                "defmacro m, do: :ok",
                "m = %{}\nList.wrap(try do :ok after %{m | a: 1} end)\ndef f, do: 1",
                ends = { level -> "raised Elixir.KeyError".takeIf { MAP_UPDATE_RAISES_WHERE_DISCARDED.isSufficient(level) } },
            ),
            // A raise after a statement whose elements or arguments are literals or a variable is the body's: neither
            // raises.
            *listOf(
                "literal-tuple" to "{:ok, :ok}",
                "literal-three-tuple" to "{:ok, :ok, :ok}",
                "literal-map" to "%{a: :ok}",
                "literal-list" to "[:ok, :ok]",
                "tuple-with-variable" to "m = 1\n{m, :ok}",
                "tuple-with-negative" to "{-1, :ok}",
                "negative" to "-1",
                "call-of-literal" to "List.wrap(:ok)",
            )
                .map { (shape, statement) ->
                    caller(
                        "body:raise-after-$shape",
                        "defmacro m, do: :ok",
                        "$statement\n:erlang.error(:x)\ndef f, do: 1",
                        ends = { "raised Elixir.ErlangError" },
                    )
                }
                .toTypedArray(),
            // A definition held as a tuple's element in a callback's output is defined, and the struct is the next
            // module's. Before `__struct__/1` returns the escaped struct, it reads it from an attribute, a map the expander
            // holds no term for, so from source `%A{}` declines.
            macro(
                "body:def-in-callback-output",
                "defmacro m, do: {{row}.A.f(), {row}.B.s().__struct__}",
                pins = { source, level ->
                    DECLINED_FROM_SOURCE(source, level)?.takeUnless { DEFSTRUCT_ESCAPES_STRUCT.isSufficient(level) }
                },
                helper = """
                defmodule {row}.T do
                  defmacro __before_compile__(_), do: {true, quote(do: def(f, do: 1))}
                end
                defmodule {row}.A do
                  @before_compile {row}.T
                  defstruct [:x]
                end
                defmodule {row}.B do
                  def s, do: %{row}.A{}
                end
                """,
            ),
            // The right side of `and` and `or` runs only where the left doesn't decide, so a match there that would
            // raise never runs.
            *listOf(
                "and" to "false and (:ok = :bad)",
                "or" to "true or (:ok = :bad)",
                "andalso" to ":erlang.andalso(false, :ok = :bad)",
            )
                .map { (operator, statement) ->
                    macro(
                        "body:unrun-right-of-$operator",
                        "defmacro m, do: {row}.Short.r()",
                        helper = """
                        defmodule {row}.Short do
                          $statement
                          def r, do: :defined
                        end
                        """,
                    )
                }
                .toTypedArray(),
            // A match the walk ran inside an element, a value, an argument or a `case` subject runs once, not again with
            // what holds it.
            *listOf(
                "tuple" to "y = {x = Module.put_attribute(__MODULE__, :a, 1), 2}",
                "map-value" to "y = %{k: x = Module.put_attribute(__MODULE__, :a, 1)}",
                "call-argument" to "y = List.wrap(x = Module.put_attribute(__MODULE__, :a, 1))",
                "andalso-left" to "y = :erlang.andalso(x = Module.put_attribute(__MODULE__, :a, 1) == :ok, true)",
                "orelse-left" to "y = :erlang.orelse(x = Module.put_attribute(__MODULE__, :a, 1) == :error, false)",
                "and-left" to "y = (x = Module.put_attribute(__MODULE__, :a, 1) == :ok) and true",
                "case-subject" to "y = case x = Module.put_attribute(__MODULE__, :a, 1), do: (:ok -> 1)",
                "match-right" to "y = x = Module.put_attribute(__MODULE__, :a, 1)",
                "attribute-value" to "@b x = Module.put_attribute(__MODULE__, :a, 1)\ny = @b",
                "writer-argument" to "Module.put_attribute(__MODULE__, :b, x = Module.put_attribute(__MODULE__, :a, 1))\ny = @b",
                "struct-default" to "defstruct b: (x = Module.put_attribute(__MODULE__, :a, 1))\ny = 1",
            )
                .map { (position, statement) ->
                    macro(
                        "body:match-in-$position-runs-once",
                        "defmacro m, do: {row}.Once.r()",
                        helper = """
                        defmodule {row}.Once do
                          Module.register_attribute(__MODULE__, :a, accumulate: true)
                          $statement
                          _ = {x, y}
                          def r, do: @a
                        end
                        """,
                    )
                }
                .toTypedArray(),
            // A macro's output the walk ran inside a tuple runs once, not again with the tuple.
            macro(
                "body:match-in-macro-output-runs-once",
                "defmacro m, do: {row}.Once.r()",
                helper = """
                defmodule {row}.Quoted do
                  defmacro w do
                    quote do
                      z = Module.put_attribute(__MODULE__, :a, 1)
                    end
                  end
                end
                defmodule {row}.Once do
                  require {row}.Quoted
                  Module.register_attribute(__MODULE__, :a, accumulate: true)
                  y = {{row}.Quoted.w(), 2}
                  _ = y
                  def r, do: @a
                end
                """,
            ),
            macro(
                "body:match-in-imported-macro-output-runs-once",
                "defmacro m, do: {row}.Once.r()",
                helper = """
                defmodule {row}.Quoted do
                  defmacro w do
                    quote do
                      z = Module.put_attribute(__MODULE__, :a, 1)
                    end
                  end
                end
                defmodule {row}.Once do
                  import {row}.Quoted
                  Module.register_attribute(__MODULE__, :a, accumulate: true)
                  y = {w(), 2}
                  _ = y
                  def r, do: @a
                end
                """,
            ),
            // A raise taken not to happen in an `fn`'s code, under a `try` that may catch it around the call running the
            // `fn` or in the function it is passed to, declines.
            macro(
                "body:assumed-raise-in-fn-rescued",
                "defmacro m, do: {row}.Caught.r()",
                helper = """
                defmodule {row}.Caught do
                  @a :before
                  try do
                    :lists.foreach(fn _ ->
                      _ = Application.fetch_env!(:elixir, :unset_key)
                      Module.put_attribute(__MODULE__, :a, :after)
                    end, [1])
                  rescue
                    _ -> :ok
                  end
                  def r, do: @a
                end
                """,
                pins = CALLS_A_STOPPED_MODULE,
            ),
            macro(
                "body:assumed-raise-in-fn-rescued-by-callee",
                "defmacro m, do: {row}.Caught.r()",
                helper = """
                defmodule {row}.Safely do
                  def run(f) do
                    try do
                      f.()
                    rescue
                      _ -> :ok
                    end
                  end
                end
                defmodule {row}.Caught do
                  @a :before
                  {row}.Safely.run(fn ->
                    _ = Application.fetch_env!(:elixir, :unset_key)
                    Module.put_attribute(__MODULE__, :a, :after)
                  end)
                  def r, do: @a
                end
                """,
                pins = CALLS_A_STOPPED_MODULE,
            ),
            // So does one in a module the `fn` defines.
            macro(
                "body:assumed-raise-in-fn-module-rescued-by-callee",
                "defmacro m, do: {row}.Caught.r()",
                helper = """
                defmodule {row}.Safely do
                  def run(f) do
                    try do
                      f.()
                    rescue
                      _ -> :ok
                    end
                  end
                end
                defmodule {row}.Caught do
                  @a :before
                  {row}.Safely.run(fn ->
                    defmodule Inner do
                      _ = Application.fetch_env!(:elixir, :unset_key)
                    end
                    Module.put_attribute(__MODULE__, :a, :after)
                  end)
                  def r, do: @a
                end
                """,
                pins = CALLS_A_STOPPED_MODULE,
            ),
            // At the file's top level too: whether the first module raised isn't known, so neither is whether the module
            // after it in the `fn` is defined.
            macro(
                "top:assumed-raise-in-fn-module-rescued-by-callee",
                "defmacro m, do: {row}.Later.r()",
                helper = """
                defmodule {row}.Safely do
                  def run(f) do
                    try do
                      f.()
                    rescue
                      _ -> :ok
                    end
                  end
                end
                {row}.Safely.run(fn ->
                  defmodule {row}.Inner do
                    _ = Application.fetch_env!(:elixir, :unset_key)
                  end
                  defmodule {row}.Later do
                    def r, do: :defined
                  end
                end)
                """,
                pins = { source, level -> CALLS_A_STOPPED_MODULE(source, level) ?: OPAQUE },
            ),
            // An `fn` a native calls reads the arguments of each call, not what the walk gave its body.
            *listOf(
                "write" to "Module.put_attribute(__MODULE__, :a, :lists.foldl(fn x, acc -> {x, acc} end, nil, [1, 2]))",
                "match" to "y = :lists.foldl(fn x, acc -> {x, acc} end, nil, [1, 2])\nModule.put_attribute(__MODULE__, :a, y)",
                "block" to "@a nil\n:lists.foreach(fn x -> Module.put_attribute(__MODULE__, :a, {x, @a}); :ok end, [1, 2])",
                "attribute-block" to "@a nil\n:lists.foreach(fn x -> @a {x, @a}; :ok end, [1, 2])",
            )
                .map { (position, statement) ->
                    macro(
                        "body:fn-called-by-native-in-$position",
                        "defmacro m, do: {row}.Called.r()",
                        helper = """
                        defmodule {row}.Called do
                          $statement
                          def r, do: @a
                        end
                        """,
                    )
                }
                .toTypedArray(),
            // A write in a `for`'s element, block or match runs once each iteration, not again with what holds it.
            *listOf(
                "tuple" to "_ = for x <- [1, 2], do: {Module.put_attribute(__MODULE__, :a, x), 2}",
                "block" to "_ = for x <- [1, 2], do: (Module.put_attribute(__MODULE__, :a, x); 2)",
                "match" to "_ = for x <- [1, 2], do: (y = {Module.put_attribute(__MODULE__, :a, x), x}; y)",
            )
                .map { (position, statement) ->
                    macro(
                        "body:write-in-for-$position-runs-once",
                        "defmacro m, do: {row}.Each.r()",
                        helper = """
                        defmodule {row}.Each do
                          Module.register_attribute(__MODULE__, :a, accumulate: true)
                          $statement
                          def r, do: @a
                        end
                        """,
                    )
                }
                .toTypedArray(),
            // A write in a unit the walk steps out of order, as the body of an `fn` a call is given and never calls,
            // may run any number of times.
            macro(
                "body:write-in-unordered-unit",
                "defmacro m, do: {row}.Unordered.r()",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule {row}.Unordered do
                  Module.register_attribute(__MODULE__, :a, accumulate: true)
                  Enum.each([], fn _ -> Module.put_attribute(__MODULE__, :a, 1) end)
                  def r, do: @a
                end
                """,
            ),
            // A write whose value a match takes, in a unit stepped out of order because the statement holding it
            // declined, may run any number of times.
            macro(
                "body:taken-write-in-unordered-for",
                "defmacro m, do: {row}.UnorderedFor.r()",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule {row}.UnorderedFor do
                  Module.register_attribute(__MODULE__, :a, accumulate: true)

                  for _ <- String.to_charlist(inspect(self())) do
                    for y <- [1] do
                      _ = Module.put_attribute(__MODULE__, :a, y)
                    end
                  end

                  def r, do: @a
                end
                """,
            ),
            // A write whose value a match takes, in an `fn` a call is given and never calls: Elixir never runs it.
            macro(
                "body:taken-write-in-uncalled-fn",
                "defmacro m, do: {row}.Uncalled.r()",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule {row}.Uncalled do
                  Module.register_attribute(__MODULE__, :a, accumulate: true)

                  List.wrap(fn ->
                    y = {x = Module.put_attribute(__MODULE__, :a, 1), 2}
                    {x, y}
                  end)

                  def r, do: @a
                end
                """,
            ),
            // So does one in such an `fn` in a `case` clause the module body runs.
            macro(
                "body:taken-write-in-uncalled-fn-in-case",
                "defmacro m, do: {row}.UncalledInCase.r()",
                pins = CALLS_A_STOPPED_MODULE,
                helper = """
                defmodule {row}.UncalledInCase do
                  Module.register_attribute(__MODULE__, :a, accumulate: true)

                  case :ok do
                    :ok ->
                      List.wrap(fn ->
                        y = {x = Module.put_attribute(__MODULE__, :a, 1), 2}
                        {x, y}
                      end)
                  end

                  def r, do: @a
                end
                """,
            ),
            // A module-body `case`, `if` or `unless` whose subject is produced runs only its selected branch, with the
            // bindings its clause's pattern makes. The helper's function is called first, so it is loaded when its exports
            // are read.
            macro(
                "body:case-selects",
                "defmacro m, do: Macro.escape({{row}.C.g(), function_exported?({row}.C, :f, 0)})",
                helper = """
                defmodule {row}.C do
                  kind = :defp
                  case kind do
                    :def -> def f, do: 1
                    :defp -> defp f, do: 2
                  end
                  def g, do: f()
                end
                """,
            ),
            macro(
                "body:if-selects",
                "defmacro m, do: Macro.escape({{row}.I.g(), function_exported?({row}.I, :f, 0), function_exported?({row}.I, :f, 1)})",
                helper = """
                defmodule {row}.I do
                  x = true
                  if x do
                    def f, do: 1
                  else
                    def f(a), do: a
                  end
                  def g, do: :ok
                end
                """,
            ),
            macro(
                "body:if-selects-attribute",
                "defmacro m, do: Macro.escape({row}.AB.f())",
                helper = """
                defmodule {row}.AB do
                  x = false
                  if x do
                    @a 1
                  else
                    @a 2
                  end
                  def f, do: @a
                end
                """,
            ),
            macro(
                "body:case-selects-in-match",
                "defmacro m, do: Macro.escape({row}.N.f())",
                helper = """
                defmodule {row}.N do
                  x = 2
                  v =
                    case x do
                      2 ->
                        @a x
                        :two
                      _ ->
                        @a 0
                        :other
                    end
                  def f, do: {@a, unquote(v)}
                end
                """,
            ),
            macro(
                "body:case-selects-pattern-binding",
                "defmacro m, do: Macro.escape({row}.N2.f())",
                helper = """
                defmodule {row}.N2 do
                  s = {:k, 2}
                  v =
                    case s do
                      {:k, n} ->
                        @a n
                        :two
                      _ ->
                        @a 0
                        :other
                    end
                  def f, do: {@a, unquote(v)}
                end
                """,
            ),
            // The selected branch, and each followed `fn` call, runs with the bindings its own `=` makes.
            macro(
                "body:if-selects-binding",
                "defmacro m, do: Macro.escape({row}.IB.f())",
                helper = """
                defmodule {row}.IB do
                  x = true
                  if x do
                    y = 1
                    def f, do: unquote(y)
                  end
                end
                """,
            ),
            macro(
                "body:each-selects-binding",
                "defmacro m, do: Macro.escape({row}.EB.f())",
                helper = """
                defmodule {row}.EB do
                  Enum.each([2], fn n ->
                    y = n
                    def f, do: unquote(y)
                  end)
                end
                """,
            ),
            // A nested `if`, and a macro's output in a selected branch, are reached through it.
            macro(
                "body:if-selects-nested",
                "defmacro m, do: Macro.escape({row}.NI.f())",
                helper = """
                defmodule {row}.NI do
                  x = true
                  if x do
                    if x do
                      y = 1
                      def f, do: unquote(y)
                    end
                  end
                end
                """,
            ),
            macro(
                "body:if-selects-nested-defstruct",
                "defmacro m, do: Macro.escape({row}.NS.__struct__())",
                pins = { source, level ->
                    DECLINED_FROM_SOURCE(source, level)?.takeUnless { DEFSTRUCT_ESCAPES_STRUCT.isSufficient(level) }
                },
                unordered = true,
                helper = """
                defmodule {row}.NS do
                  x = true
                  if x do
                    unless false do
                      defstruct [:a]
                    end
                  end
                end
                """,
            ),
            macro(
                "body:if-selects-defstruct",
                "defmacro m, do: Macro.escape({row}.SB.__struct__())",
                pins = { source, level ->
                    DECLINED_FROM_SOURCE(source, level)?.takeUnless { DEFSTRUCT_ESCAPES_STRUCT.isSufficient(level) }
                },
                unordered = true,
                helper = """
                defmodule {row}.SB do
                  x = true
                  if x do
                    defstruct [:a]
                  end
                end
                """,
            ),
            // A definition in the selected branch takes the attributes, as a statement's does.
            macro(
                "body:if-selects-file",
                "defmacro m, do: Macro.escape({row}.FD.g())",
                helper = """
                defmodule {row}.FD do
                  x = true
                  if x do
                    def f, do: 1
                  end
                  def g, do: @file
                end
                """,
            ),
            macro(
                "body:if-selects-doc",
                "defmacro m, do: Macro.escape({row}.DD.g())",
                helper = """
                defmodule {row}.DD do
                  x = true
                  if x do
                    @doc "a"
                    def f, do: 1
                  end
                  def g, do: @doc
                end
                """,
            ),
            // `elixir_quote:dot/5` joins the receiver, whose fragments are evaluated, to the evaluated name.
            use(
                "fragment:call-name-receiver",
                """
                defmacro __using__(_) do
                  quote unquote: false do
                    mod = String
                    fun = :upcase
                    def f(x), do: unquote(mod).unquote(fun)(x)
                    def g, do: f("a")
                  end
                end
                """,
            ),
            // A splice is checked by `list/2` once every fragment after it in its list is evaluated.
            use(
                "fragment:splice-before-raise",
                """
                defmacro __using__(_) do
                  quote unquote: false do
                    args = :not_a_list
                    def foo(unquote_splicing(args), unquote(raise "boom")), do: 1
                  end
                end
                """,
                ends = { "raised Elixir.RuntimeError" },
            ),
            // An unquoted call name is checked by `dot/5` once the call's arguments are evaluated.
            use(
                "fragment:call-name-before-raise",
                """
                defmacro __using__(_) do
                  quote unquote: false do
                    def foo, do: :erlang.unquote("bad")(unquote(raise "boom"))
                  end
                end
                """,
                ends = { "raised Elixir.RuntimeError" },
            ),
            // `raise` is `:erlang.error/2,3` on some legs, which names the caller's frame: here the body's own.
            use(
                "body:raise",
                """
                defmacro __using__(_) do
                  quote unquote: false do
                    @a raise(ArgumentError)
                  end
                end
                """,
                ends = { "raised Elixir.ArgumentError" },
            ),
            // Elixir may raise at a value the evaluator can't produce, before any later raise.
            use(
                "body:raise-after-declined-argument",
                """
                defmacro __using__(_) do
                  quote unquote: false do
                    Module.put_attribute(__MODULE__, (receive do after 0 -> raise KeyError end), raise(ArgumentError))
                  end
                end
                """,
                ends = { "stopped at `use {row}`" },
            ),
            use(
                "body:raise-after-declined-match",
                """
                defmacro __using__(_) do
                  quote unquote: false do
                    _ = receive do after 0 -> raise KeyError end
                    @a raise(ArgumentError)
                  end
                end
                """,
                ends = { "stopped at `use {row}`" },
            ),
            // The head's splice is checked before the body's fragments are evaluated.
            use(
                "fragment:head-splice-before-body",
                """
                defmacro __using__(_) do
                  quote unquote: false do
                    bad = :bad
                    def foo(unquote_splicing(bad)), do: unquote(raise "body")
                  end
                end
                """,
                ends = { "raised Elixir.ArgumentError" },
            ),
            // `do: unquote_splicing(x)` is `[{:do, ...}]`, a pair holding a splice, which Elixir quotes as `{:{}, [], [:do, 1]}`.
            use(
                "fragment:splice-in-tuple",
                """
                defmacro __using__(_) do
                  quote unquote: false do
                    x = [1]
                    def foo, do: unquote_splicing(x)
                  end
                end
                """,
                ends = { "raised missing_option" },
            ),
            // `[..., unquote_splicing(xs) | tail]` is `tail_list/3`, spliced where it is.
            use(
                "fragment:splice-before-tail",
                """
                defmacro __using__(_) do
                  quote unquote: false do
                    xs = [1, 2]
                    def foo, do: [0, unquote_splicing(xs) | [3]]
                  end
                end
                """,
            ),
            // `tail_list/3` needs an element to pair with the tail.
            use(
                "fragment:splice-empty-before-tail",
                """
                defmacro __using__(_) do
                  quote unquote: false do
                    xs = []
                    def foo(t), do: [unquote_splicing(xs) | t]
                  end
                end
                """,
                ends = { if (SPLICE_EMPTY_BEFORE_TAIL_RAISES.isSufficient(it)) "raised Elixir.ArgumentError" else "raised Elixir.MatchError" },
            ),
            // `tail_list/3` has no clause for a value that isn't a list, where `list/2` raises `ArgumentError`.
            use(
                "fragment:splice-non-list-before-tail",
                """
                defmacro __using__(_) do
                  quote unquote: false do
                    xs = 1
                    def foo(t), do: [0, unquote_splicing(xs) | t]
                  end
                end
                """,
                ends = { "raised Elixir.FunctionClauseError" },
            ),
            // `do_quote/2` quotes a pair as `{:{}, [], [...]}` when either element is a call to `unquote_splicing` of
            // any arity. Before v1.15 the body is the call, which is undefined.
            use(
                "fragment:pair-with-splicing-call",
                """
                defmacro __using__(_) do
                  quote unquote: false do
                    x = 1
                    def foo(unquote(x)), do: unquote_splicing(1, 2)
                  end
                end
                """,
                ends = { if (FUNCTION_ERRORS_CONTINUE.isSufficient(it)) "raised missing_option" else "raised undefined_function" },
            ),
            // A call written without parentheses has no arguments: `elixir_quote:dot/5` of `nil` builds one with none.
            use(
                "fragment:call-name-without-parentheses",
                """
                defmacro __using__(_) do
                  {:def, [], [{:g, [], []}, [do: {{:unquote, [], [:a]}, {{{:., [], [String, :unquote]}, [], [:upcase]}, [], nil}}]]}
                end
                """,
            ),
            // `dot/5` of `nil` reads a quoted call as the name and its arguments.
            use(
                "fragment:call-name-quoted-call",
                """
                defmacro __using__(_) do
                  {:def, [], [{:g, [], []}, [do: {{:unquote, [], [:a]}, {{{:., [], [String, :unquote]}, [], [{:{}, [], [:upcase, [], [1]]}]}, [], nil}}]]}
                end
                """,
            ),
            // `EEx.compile_string/2` and `EEx.compile_file/2` aren't evaluated, so the body each defines is unknown.
            eex("eex:string", "EEx.function_from_string(:def, :f, \"<%= a %>\", [:a])"),
            eex("eex:defp", "EEx.function_from_string(:defp, :f, \"<%= a %>\", [:a])\ndef call(a), do: f(a)"),
            eex("eex:args-attribute", "@args [:a, :b]\nEEx.function_from_string(:def, :g, \"x\", @args)"),
            eex("eex:name-variable", "name = :h\nEEx.function_from_string(:def, name, \"x\")"),
            eex("eex:kind-variable", "kind = :def\nEEx.function_from_string(kind, :k, \"x\")"),
            eex(
                "eex:three-arity",
                "EEx.function_from_string(:def, :a1, \"x\")\nEEx.function_from_file(:def, :a2, \"$TEMPLATE\")",
            ),
            eex(
                "eex:options",
                """
                EEx.function_from_string(:def, :a1, "x", [:a], trim: true)
                EEx.function_from_string(:def, :a2, "x", [:a], engine: EEx.SmartEngine)
                EEx.function_from_file(:def, :a3, "$TEMPLATE", [:a], trim: true)
                """,
            ),
            eex("eex:for", "for n <- [:p, :q], do: EEx.function_from_string(:def, n, \"x\")"),
            eex("eex:enum-each", "Enum.each([:p, :q], fn n -> EEx.function_from_string(:def, n, \"x\") end)"),
            eex("eex:import", "import EEx\nfunction_from_string(:def, :u, \"x\")"),
            eex("eex:file", "EEx.function_from_file(:def, :t, \"$TEMPLATE\", [:assigns])"),
            // Elixir raises `CaseClauseError`, but the declined `EEx.compile_string/2` before it leaves whether it is
            // reached unknown.
            eex("eex:defmacro", "EEx.function_from_string(:defmacro, :m, \"x\")", ends = { "stopped at `use {row}`" }),
            // Elixir defines `call/0` and `k/0`: the kind isn't known, so neither branch's definition is certain.
            eex(
                "eex:kind-unknown",
                "kind = Application.get_env(:m2, :kind, :defp)\nEEx.function_from_string(kind, :k, \"x\")\ndef call, do: k()",
                ends = { "stopped at `use {row}`" },
            ),
            // A declined call before a module-body `case` leaves the branch to the subject.
            eex(
                "eex:declined-before-case",
                "kind = :def\n_ = EEx.compile_string(\"x\")\ncase kind do\n  :def -> def f, do: 1\n  :defp -> defp f, do: 2\nend",
            ),
            // A body fragment that can't be produced leaves the body unknown, and the module runs on.
            fragments("body:unknown-fragment", "x = Application.get_env(:m2, :x, :ok)\ndef f, do: unquote(x)"),
            fragments("body:unknown-splice", "l = Application.get_env(:m2, :l, [2])\ndef f, do: [1, unquote_splicing(l)]"),
            fragments("body:unknown-call-name", "g = Application.get_env(:m2, :g, :abs)\ndef f, do: unquote(g)(-1)"),
            fragments(
                "body:unknown-rescue",
                "x = Application.get_env(:m2, :x, :ok)\ndef f do\n  raise \"x\"\nrescue\n  _ -> unquote(x)\nend",
            ),
            fragments("body:unknown-uncalled-name", "n = Application.get_env(:m2, :n, :node)\ndef f, do: Kernel.unquote(n)"),
            // A helper whose input an unknown fragment gives is unknown too.
            fragments(
                "body:unknown-splice-then-splice",
                "l = Application.get_env(:m2, :l, [2])\nm = [3]\ndef f, do: [unquote_splicing(l), unquote_splicing(m)]",
            ),
            fragments(
                "body:declined-splice-then-splice",
                "m = [3]\ndef f, do: [unquote_splicing(receive(do: (x -> x), after: (0 -> [2]))), unquote_splicing(m)]",
            ),
            fragments(
                "body:splice-then-unknown-splice",
                "m = [1]\nl = Application.get_env(:m2, :l, [2])\ndef f, do: [unquote_splicing(m), unquote_splicing(l)]",
            ),
            // A splice's own value is checked before a later splice's unknown result: Elixir raises there whatever it holds.
            fragments(
                "body:invalid-splice-then-unknown-splice",
                "m = 1\nl = Application.get_env(:m2, :l, [2])\ndef f, do: [unquote_splicing(m), unquote_splicing(l)]",
                ends = { "raised Elixir.ArgumentError" },
            ),
            fragments(
                "body:splice-then-declined-splice",
                "m = [1]\ndef f, do: [unquote_splicing(m), unquote_splicing(receive(do: (x -> x), after: (0 -> [2])))]",
            ),
            fragments(
                "body:unknown-left-call-name",
                "m = Application.get_env(:m2, :m, Kernel)\ndef f, do: unquote(m).unquote(:abs)(-1)",
            ),
            fragments(
                "body:unknown-then-invalid-fragment",
                "l = Application.get_env(:m2, :l, [2])\ndef f, do: [unquote_splicing(l), unquote({:a, :b, :c})]",
                ends = { "stopped at `use {row}`" },
            ),
            // The port doesn't have the body Elixir gives an `@on_definition` callback.
            use(
                "body:unknown-on-definition",
                """
                def on_def(env, _kind, _name, _args, _guards, body), do: Module.put_attribute(env.module, :n, length(body))

                defmacro __using__(_) do
                  quote unquote: false do
                    @on_definition {{row}, :on_def}
                    l = Application.get_env(:m2, :l, [2, 3])
                    def f, do: [1, unquote_splicing(l)]
                    if @n == 2, do: def(two, do: 2)
                  end
                end
                """,
                // A callback that declines stops the module whole.
                stopsAfter = "{:defmodule, [], [{:__aliases__, [], [:{token}, :Case0]}",
            ),
            // A head fragment that can't be produced still stops the definition, where Elixir defines it.
            fragments(
                "body:unknown-head",
                "name = Application.get_env(:m2, :n, :g)\ndef unquote(name)(), do: 1",
                ends = { "stopped at `use {row}`" },
            ),
            fragments(
                "body:unknown-guard",
                "v = Application.get_env(:m2, :v, 1)\ndef f(x) when x == unquote(v), do: x",
                ends = { "stopped at `use {row}`" },
            ),
            fragments(
                "body:unknown-guard-call-name",
                "g = Application.get_env(:m2, :g, :is_integer)\ndef f(x) when unquote(g)(x), do: x",
                ends = { "stopped at `use {row}`" },
            ),
            // A value the evaluator produces that isn't code still stops, where Elixir raises `ArgumentError`.
            fragments("body:invalid-fragment", "def f, do: unquote({:a, :b, :c})", ends = { "stopped at `use {row}`" }),
            // An implementation's body is the user's code where a macro's output carries it: where the evaluator can't
            // run it, Elixir may have raised, so a later raise stops.
            use(
                "impl:raise-after-declined-body",
                """
                defmacro __using__(_) do
                  quote unquote: false do
                    defimpl Inspect, for: {row}.X do
                      _ = receive do after 0 -> raise KeyError end
                      @a raise(ArgumentError)
                      def inspect(_, _), do: ""
                    end
                  end
                end
                """,
                ends = { "stopped at `use {row}`" },
            ),
            // Before v1.13 the body can write `@functions`, which `__protocol__(:functions)` unquotes sorted, and
            // `:lists.sort/1` of an improper list before OTP 29 declines.
            macro(
                "protocol:functions-improper",
                """
                defmacro m do
                  quote do
                    defprotocol P do
                      @functions [{:a, 1} | :x]
                      def f(x)
                    end
                  end
                end
                """,
                legs = { !PROTOCOL_BEFORE_COMPILE.isSufficient(it) },
                ends = { "stopped at `$ROW.m()`" },
            ),
            // A raising arm whose test the evaluator can't decide is passed over, as Elixir is taken to compile, and the
            // next arm is tried. `Process.whereis/1` declines in `nillify/1`, so its arm's test does; `:erlang.whereis/1`
            // is an unknown value, so a clause given it is tried.
            macro(
                "branch:raising-arm-unless",
                """
                defmacro m do
                  unless Process.whereis(:init), do: raise("no init")
                  :ok
                end
                """,
            ),
            macro("branch:raising-arm-if", "defmacro m, do: if(Process.whereis(:init), do: :ok, else: raise(ArgumentError, \"no init\"))"),
            macro(
                "branch:raising-arm-case",
                """
                defmacro m do
                  case Process.whereis(:init) do
                    nil -> exit(:no_init)
                    _ -> :ok
                  end
                end
                """,
            ),
            macro(
                "branch:raising-arm-cond",
                """
                defmacro m do
                  cond do
                    Process.whereis(:init) == nil -> throw(:no_init)
                    true -> :ok
                  end
                end
                """,
            ),
            macro(
                "branch:raising-arm-def",
                """
                defp check(nil), do: :erlang.error(:no_init, [nil])
                defp check(_), do: :ok
                defmacro m, do: check(:erlang.whereis(:init))
                """,
            ),
            macro("branch:raising-arm-fn", "defmacro m, do: (fn nil -> raise \"no init\"; _ -> :ok end).(:erlang.whereis(:init))"),
            macro(
                "branch:raising-arm-with",
                """
                defmacro m do
                  with {:ok, _} <- {:error, :erlang.whereis(:init)} do
                    :bad
                  else
                    {:error, :undefined} -> raise ArgumentError, "no init"
                    {:error, _} -> :ok
                  end
                end
                """,
            ),
            // While a `try` that can catch the raise runs, a raising arm may be taken, so the loop declines. A
            // `try` with only `after`, or one that has ended, catches nothing.
            macro(
                "branch:raising-arm-caught",
                """
                defmacro m do
                  try do
                    case :erlang.whereis(:init) do
                      :undefined -> throw(:no_init)
                      _ -> :ok
                    end
                  catch
                    :no_init -> :caught
                  end
                end
                """,
                pins = DECLINED,
            ),
            // A raise taken not to happen, under a `try` that may catch it, declines: the build goes on either way.
            macro(
                "branch:assumed-raise-rescued",
                """
                defmacro m do
                  try do
                    Application.fetch_env!(:elixir, :unset_key)
                    :body
                  rescue
                    ArgumentError -> :rescued
                  end
                end
                """,
                pins = DECLINED,
            ),
            // A read that can't raise is answered there.
            macro(
                "branch:declared-read-in-try",
                """
                defmacro m do
                  try do
                    {Application.fetch_env!(:elixir, :check_endianness), Application.get_env(:elixir, :check_endianness)}
                  rescue
                    ArgumentError -> :rescued
                  end
                end
                """,
            ),
            macro(
                "branch:raising-arm-rescued-in-call",
                """
                defp check(:undefined), do: raise("no init")
                defp check(_), do: :ok
                defmacro m do
                  try do
                    check(:erlang.whereis(:init))
                  rescue
                    _ -> :rescued
                  end
                end
                """,
                pins = DECLINED,
            ),
            macro(
                "branch:raising-arm-in-try-after",
                """
                defmacro m do
                  try do
                    case :erlang.whereis(:init) do
                      :undefined -> exit(:no_init)
                      _ -> :ok
                    end
                  after
                    :done
                  end
                end
                """,
            ),
            macro(
                "branch:raising-arm-after-try",
                """
                defmacro m do
                  _ = try(do: :ok, catch: (_ -> :caught))
                  case :erlang.whereis(:init) do
                    :undefined -> exit(:no_init)
                    _ -> :ok
                  end
                end
                """,
            ),
            // A later arm whose test is unknown, no raising arm, an operand chosen by no arm, and a declined argument
            // still decline.
            macro(
                "branch:two-arms-left",
                """
                defmacro m do
                  case Process.whereis(:init) do
                    nil -> raise "no init"
                    :a -> :a
                    _ -> :ok
                  end
                end
                """,
                pins = DECLINED,
            ),
            macro("branch:no-raising-arm-single-clause", "defmacro m, do: if(Process.whereis(:init), do: :ok)", pins = DECLINED),
            macro("branch:andalso-unknown-left", "defmacro m, do: Process.whereis(:init) == nil and raise(\"no init\")", pins = DECLINED),
            macro(
                "branch:raising-arm-call-argument",
                """
                defp check(nil), do: raise("no init")
                defp check(_), do: :ok
                defmacro m, do: check(Process.whereis(:init))
                """,
                pins = DECLINED,
            ),
            // A subject or head whose decline ended in a raise, or was given a module being compiled, isn't passed
            // over.
            macro(
                "branch:raising-arm-subject-ended",
                """
                defp f, do: Process.whereis(:init)

                defmacro m do
                  case {f(), Map.fetch!(%{}, :k)} do
                    {nil, _} -> raise "no init"
                    _ -> :ok
                  end
                end
                """,
                pins = DECLINED,
            ),
            // A later read sees what a passed-over subject or head wrote: here, nothing the evaluator knows.
            *listOf(
                "subject-given" to "case g(__CALLER__.module) do\n    nil -> raise \"no init\"\n    _ -> :ok\n  end",
                "cond-given" to "cond do\n    g(__CALLER__.module) == nil -> raise \"no init\"\n    true -> :ok\n  end",
            )
                .map { (id, choice) ->
                    Row(
                        "branch:raising-arm-$id",
                        "defp g(module) do\n  text = inspect(Process.whereis(:init))\n  Module.put_attribute(module, :a, 2)\n  text\nend\n\ndefmacro w do\n  $choice\nend\n\ndefmacro m, do: Module.get_attribute(__CALLER__.module, :a)",
                        "$ROW.m()",
                        "require $ROW\n$ROW.w()\n_ = $ROW.m()",
                        "$ROW.w()",
                        listOf(ROW),
                        SourceRun.ANSWERS,
                        EVERY_LEG,
                        DECLINED,
                        null,
                    )
                }
                .toTypedArray(),
            // `:erlang.raise/3` returns `badarg` where its arguments aren't valid, so it isn't declared never to
            // return.
            macro(
                "branch:raising-arm-reraise",
                """
                defmacro m do
                  case Process.whereis(:init) do
                    nil -> :erlang.raise(:error, :no_init, [])
                    _ -> :ok
                  end
                end
                """,
                pins = DECLINED,
            ),
            // `ExUnit.Case.__using__/1`'s check before its `quote`, up to v1.13.4.
            use(
                "branch:whereis-unless",
                """
                defmacro __using__(_) do
                  unless Process.whereis(:init) do
                    raise "no init"
                  end

                  quote(do: def(f, do: 1))
                end
                """,
            ),
            // `assert pat = expr` reads `Macro.Env.to_match/1` of the caller, and binds the pattern's variables.
            Row(
                "branch:assert-match",
                "defmacro m, do: quote(do: ExUnit.Assertions.assert({:ok, var!(x)} = {:ok, 1}))",
                "$ROW.m()",
                "require ExUnit.Assertions\nrequire $ROW\n$ROW.m()\ndef unquote(:\"f#{x}\")(), do: 1",
                "$ROW.m()",
                listOf(ROW, "ExUnit.Assertions"),
                SourceRun.ANSWERS,
                EVERY_LEG,
                UNPINNED,
                null,
            ),
            // Each field of `__CALLER__`, the env at the call. The file and line are the oracle's own, and its requires
            // hold every row's module, so only the defaults and this row's are compared.
            *listOf("aliases", "context", "context_modules", "function", "functions", "macro_aliases", "macros", "module")
                .map { field -> macro("env:caller-$field", "defmacro m, do: Macro.escape(__CALLER__.$field)") }
                .toTypedArray(),
            // A macro's quoted `alias` adds the alias to the env's `macro_aliases`, with the quote's counter.
            macro(
                "env:env-macro_aliases-held",
                """
                require {row}.Aliasing
                {row}.Aliasing.a()
                @held Macro.escape(__ENV__.macro_aliases)
                defmacro m, do: @held
                """,
                helper = """
                defmodule {row}.Aliasing do
                  defmacro a, do: quote(do: alias(List.Chars, as: C))
                end
                """,
            ),
            macro("env:caller-requires", "defmacro m, do: Enum.filter(__CALLER__.requires, &(&1 in [Application, Kernel, __MODULE__]))"),
            macro("env:caller-file", "defmacro m, do: is_binary(__CALLER__.file)"),
            macro("env:caller-line", "defmacro m, do: is_integer(__CALLER__.line)"),
            // A module body's are the compiler's process state.
            macro("env:caller-lexical_tracker", "defmacro m, do: Macro.escape(__CALLER__.lexical_tracker)", pins = DECLINED),
            macro("env:caller-tracers", "defmacro m, do: Macro.escape(__CALLER__.tracers)", pins = DECLINED),
            // The caller's variables, each a definition named from it, so the table shows them.
            caller(
                "env:caller-versioned_vars",
                "defmacro m, do: for({{n, _}, v} <- __CALLER__.versioned_vars, do: quote(do: def(unquote(:\"#{n}_#{v}\")(), do: 1)))",
                VARIABLES,
                legs = VERSIONED_VARS_LEGS,
            ),
            caller(
                "env:caller-current_vars",
                "defmacro m, do: for({{n, _}, v} <- elem(__CALLER__.current_vars, 0), do: quote(do: def(unquote(:\"#{n}_#{v}\")(), do: 1)))",
                VARIABLES,
                legs = UNVERSIONED_VARS_LEGS,
            ),
            // The oracle's `Macro.expand_once/2` runs in its own batch's state, so only the caller, which binds `y`, defines.
            caller(
                "env:caller-current_vars-write",
                """
                defmacro m do
                  case __CALLER__.current_vars do
                    {%{{:y, nil} => _}, false} -> quote(do: def(w_false, do: 1))
                    {%{{:y, nil} => _}, write} -> for({{n, _}, v} <- write, do: quote(do: def(unquote(:"w_#{n}_#{v}")(), do: 1)))
                    _ -> :ok
                  end
                end
                """,
                VARIABLES,
                legs = UNVERSIONED_VARS_LEGS,
            ),
            caller(
                "env:caller-unused_vars",
                """
                defmacro m do
                  case __CALLER__.current_vars do
                    {%{{:y, nil} => _}, _} -> quote(do: def(unquote(:"v#{elem(__CALLER__.unused_vars, 1)}")(), do: 1))
                    _ -> :ok
                  end
                end
                """,
                VARIABLES,
                legs = UNVERSIONED_VARS_LEGS,
            ),
            // `ExState` holds no unused variables, nor the variables Elixir adds to `vars` outside a match.
            macro(
                "env:caller-unused_vars-map",
                "defmacro m, do: Macro.escape(elem(__CALLER__.unused_vars, 0))",
                legs = UNVERSIONED_VARS_LEGS,
                pins = DECLINED,
            ),
            macro("env:caller-vars", "defmacro m, do: Macro.escape(__CALLER__.vars)", legs = UNVERSIONED_VARS_LEGS, pins = DECLINED),
            macro("env:caller-prematch_vars", "defmacro m, do: Macro.escape(__CALLER__.prematch_vars)", legs = UNVERSIONED_VARS_LEGS),
            macro("env:caller-contextual_vars", "defmacro m, do: Macro.escape(__CALLER__.contextual_vars)", legs = UNVERSIONED_VARS_LEGS),
            // In a match, the variables read before it.
            caller(
                "env:caller-prematch_vars-match",
                """
                defmacro m do
                  case __CALLER__.prematch_vars do
                    {read, _} -> Module.put_attribute(__CALLER__.module, :prematch, :"p#{Enum.reduce(read, "", fn {{n, _}, v}, acc -> acc <> "_#{n}_#{v}" end)}")
                    _ -> :ok
                  end

                  quote(do: _)
                end
                """,
                VARIABLES,
                statement = "$ROW.m() = 2\ndef unquote(@prematch || :none)(), do: 1",
                legs = UNVERSIONED_VARS_LEGS,
            ),
            caller(
                "env:caller-Macro.Env.vars",
                "defmacro m, do: for({n, _} <- Macro.Env.vars(__CALLER__), do: quote(do: def(unquote(n)(), do: 1)))",
                VARIABLES,
            ),
            caller(
                "env:caller-to_match",
                """
                defmacro m do
                  e = Macro.Env.to_match(__CALLER__)

                  names =
                    case Map.get(e, :prematch_vars) do
                      {read, _} -> for {{n, _}, v} <- read, do: :"#{n}_#{v}"
                      _ -> []
                    end

                  defs = for n <- names, do: quote(do: def(unquote(n)(), do: 1))
                  {:__block__, [], [quote(do: def(context, do: unquote(e.context))) | defs]}
                end
                """,
                VARIABLES,
            ),
            // Each field of `__ENV__` in the macro's body, which has a variable bound. The file and line are the oracle's,
            // and before 1.13 its context modules hold every row module before this one.
            *listOf(
                "aliases", "context", "function", "functions", "lexical_tracker", "macro_aliases", "macros", "module",
                "requires", "tracers",
            )
                .map { field -> macro("env:env-$field", "defmacro m do\n  x = 1\n  _ = x\n  Macro.escape(__ENV__.$field)\nend") }
                .toTypedArray(),
            macro("env:env-context_modules", "defmacro m, do: Enum.take(__ENV__.context_modules, 1)"),
            macro("env:env-file", "defmacro m, do: is_binary(__ENV__.file)"),
            macro("env:env-line", "defmacro m, do: is_integer(__ENV__.line)"),
            macro("env:env-versioned_vars", "defmacro m do\n  x = 1\n  _ = x\n  Macro.escape(__ENV__.versioned_vars)\nend", legs = VERSIONED_VARS_LEGS),
            *listOf("contextual_vars", "current_vars", "prematch_vars")
                .map { field ->
                    macro("env:env-$field", "defmacro m do\n  x = 1\n  _ = x\n  Macro.escape(__ENV__.$field)\nend", legs = UNVERSIONED_VARS_LEGS)
                }
                .toTypedArray(),
            // Each `rescue` around the code adds its own `:__STACKTRACE__`.
            macro(
                "env:env-contextual_vars-nested-rescue",
                """
                defmacro m do
                  try do
                    raise "a"
                  rescue
                    _ ->
                      try do
                        raise "b"
                      rescue
                        _ -> __ENV__.contextual_vars
                      end
                  end
                end
                """,
                legs = UNVERSIONED_VARS_LEGS,
            ),
            macro(
                "env:env-unused_vars",
                "defmacro m do\n  x = 1\n  _ = x\n  elem(__ENV__.unused_vars, 1)\nend",
                legs = UNVERSIONED_VARS_LEGS,
            ),
            macro(
                "env:env-unused_vars-map",
                "defmacro m do\n  x = 1\n  _ = x\n  Macro.escape(elem(__ENV__.unused_vars, 0))\nend",
                legs = UNVERSIONED_VARS_LEGS,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "env:env-vars",
                "defmacro m do\n  x = 1\n  _ = x\n  Macro.escape(__ENV__.vars)\nend",
                legs = UNVERSIONED_VARS_LEGS,
                pins = DECLINED_FROM_SOURCE,
            ),
            macro(
                "native:ordsets-is_element",
                "defmacro m, do: [:ordsets.is_element(2, [1, 3]), :ordsets.is_element(0, [1]), :ordsets.is_element(1, [1]), :ordsets.is_element(1, [])]",
            ),
            macro("native:md5", "defmacro m, do: :erlang.md5(\"abc\")"),
            macro("native:md5-iolist", "defmacro m, do: :erlang.md5([\"a\", [98, \"c\"], 255 | \"d\"])"),
            macro("native:md5-badarg", "defmacro m, do: :erlang.md5([256])"),
            // A list of 2^21 empty lists, whose parts share one another, as `iolist_to_binary/1` reads it.
            macro(
                "native:md5-past-the-node-limit",
                """
                defmacro m, do: :erlang.md5(nest([], 21))

                defp nest(x, 0), do: x
                defp nest(x, n), do: nest([x, x], n - 1)
                """,
                pins = DECLINED,
            ),
            macro("native:maps-keys", "defmacro m, do: :maps.keys(%{\"b\" => 1, \"a\" => 2})"),
            // A struct is a map to `maps:keys/1`, whose atom keys' order from OTP 26 the VM keeps.
            macro("native:maps-keys-struct", "defmacro m, do: :maps.keys(1..2)", legs = { !MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER.isSufficient(it) }),
            // Elixir's order is the atoms' index in the VM, so only the set is compared.
            macro("native:maps-keys-struct-sorted", "defmacro m, do: :lists.sort(:maps.keys(1..2))", legs = { MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER.isSufficient(it) }),
            macro("native:atom_to_list", "defmacro m, do: :erlang.atom_to_list(:abc)"),
            macro("native:binary-at", """defmacro m, do: [:binary.at("abc", 0), :binary.at("abc", 2)]"""),
            macro("native:binary-at-badarg", """defmacro m, do: :binary.at("abc", 3)"""),
            macro("native:binary-at-bitstring", "defmacro m, do: :binary.at(<<1, 2::4>>, 0)"),
            // Terms that compare equal give the first.
            macro("native:min", "defmacro m, do: [:erlang.min(1, 2), :erlang.min(2, 1), :erlang.min(1, 1.0), :erlang.min(1.0, 1), :erlang.min(:a, 1)]"),
            // Elixir's static options, which its boot sets.
            macro("native:identifier_tokenizer", "defmacro m, do: :elixir_config.identifier_tokenizer()", legs = STATIC_OPTIONS_IN_PERSISTENT_TERM::isSufficient),
            macro("native:is_bootstrap", "defmacro m, do: :elixir_config.is_bootstrap()", legs = STATIC_OPTIONS_IN_PERSISTENT_TERM::isSufficient),
            macro(
                "native:elixir_config-get",
                "defmacro m, do: [:elixir_config.get(:identifier_tokenizer, :x), :elixir_config.get(:bootstrap)]",
                legs = { !STATIC_OPTIONS_IN_PERSISTENT_TERM.isSufficient(it) },
            ),
            // Every other key varies with how Mix runs Elixir; from v1.13 the static options aren't in the table.
            macro("native:elixir_config-get-other-key", "defmacro m, do: :elixir_config.get(:docs, :x)", pins = DECLINED),
            macro(
                "native:elixir_config-get-static-key",
                "defmacro m, do: :elixir_config.get(:bootstrap, :x)",
                legs = STATIC_OPTIONS_IN_PERSISTENT_TERM::isSufficient,
                pins = DECLINED,
            ),
            macro("native:special_form", "defmacro m, do: [:elixir_import.special_form(:case, 2), :elixir_import.special_form(:case, 1), :elixir_import.special_form(:fn, :x), :elixir_import.special_form(:foo, 1)]"),
            // `Enumerable.Map` reduces `:maps.to_list/1` of the map; a struct reduces through its own implementation.
            macro("enumerate:map-binary-keys", "defmacro m, do: for({k, v} <- %{\"b\" => 1, \"a\" => 2}, do: {k, v})"),
            macro("enumerate:map-one-atom-key", "defmacro m, do: for({k, v} <- %{a: 1}, do: {k, v})"),
            macro("enumerate:map-two-atom-keys", "defmacro m, do: for({k, v} <- %{a: 1, b: 2}, do: {k, v})", legs = { !MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER.isSufficient(it) }),
            macro("enumerate:map-two-atom-keys-sorted", "defmacro m, do: :lists.sort(for({k, v} <- %{a: 1, b: 2}, do: {k, v}))", legs = { MAP_ATOM_KEYS_IN_ATOM_INDEX_ORDER.isSufficient(it) }),
            macro(
                "enumerate:map-33-keys",
                "defmacro m, do: for({k, _} <- %{${(1..33).joinToString(", ") { "$it => $it" }}}, do: k)",
                pins = DECLINED,
            ),
            macro("enumerate:struct", "defmacro m, do: for(x <- MapSet.new([1]), do: x)", pins = DECLINED),
            // A module-body `fn` is expanded once, with its parameter a variable of its body, however often it is called.
            use(
                "body:fn-params-bound",
                """
                defmacro names do
                  module = __CALLER__.module
                  Module.put_attribute(module, :expanded, (Module.get_attribute(module, :expanded) || 0) + 1)
                  Module.put_attribute(module, :names, for({n, _} <- Macro.Env.vars(__CALLER__), do: n))
                  :ok
                end

                defmacro __using__(_) do
                  quote unquote: false do
                    require {row}
                    f = fn param -> {row}.names() end
                    f.(1)
                    f.(2)
                    def unquote(:"expanded_#{@expanded}")(), do: 1
                    for n <- @names, do: def(unquote(n)(), do: 1)
                  end
                end
                """,
            ),
        )
    }
}
