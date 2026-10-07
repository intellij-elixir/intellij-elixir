package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.lowering.inspect
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Fails on any leg whose `elixir_rewrite` inlines or rewrites, or whose Elixir and Erlang/OTP admit in a guard, other
 * than the manifests committed for its version, which [RewriteTableTest] checks the expander against.
 */
class RewriteManifestTest {
    @Test
    fun `inline table`() {
        LegManifest.assertMatchesLeg(RewriteManifestTest::class, INLINE, rows(INLINE_DUMP))
    }

    @Test
    fun `rewrite table`() {
        LegManifest.assertMatchesLeg(RewriteManifestTest::class, REWRITE, rows(REWRITE_DUMP))
    }

    @Test
    fun `guard functions`() {
        LegManifest.assertMatchesLeg(RewriteManifestTest::class, GUARD_FUNCTIONS, rows(GUARD_DUMP))
    }

    /** The rows [dump], a module body, sends as one list of tuples of atoms and integers, one row per line. */
    private fun rows(dump: String): String {
        // Test forks share one quoter node, where two compiles can't define one module at once.
        val token = "T" + UUID.randomUUID().toString().replace("-", "")
        val compiled = Quoter.compile(dump.replace(TOKEN, token), 30.seconds)

        assertEquals("compile status ${compiled.diagnostics.map(::inspect)}", OtpErlangAtom("ok"), compiled.status)

        return (compiled.messages.single() as OtpErlangList).elements().joinToString("") { row ->
            (row as OtpErlangTuple).elements().joinToString(" ", postfix = "\n") {
                when (it) {
                    is OtpErlangAtom -> it.atomValue()
                    is OtpErlangLong -> it.longValue().toString()
                    else -> throw AssertionError("not an atom or integer: ${inspect(it)}")
                }
            }
        }
    }

    companion object {
        const val INLINE = "inline.txt"
        const val REWRITE = "rewrite.txt"
        const val GUARD_FUNCTIONS = "guard-functions.txt"

        /** In each dump's module name, which must be unique to its compile. */
        private const val TOKEN = "@TOKEN@"

        /**
         * `elixir_rewrite:inline/3` over every function and macro of the modules its table names, as `module name
         * arity erlang_module erlang_name`.
         */
        private val INLINE_DUMP =
            """
            defmodule RewriteManifest@TOKEN@.Inline do
              modules = [
                Atom, Bitwise, Enum, Float, Function, Integer, IO, Kernel, Keyword, List, Map, Node, Port, Process,
                String, String.Chars, System, Tuple
              ]

              rows =
                for module <- modules,
                    Code.ensure_loaded?(module),
                    {name, arity} <- module.__info__(:functions) ++ module.__info__(:macros),
                    {erlang_module, erlang_name} <- [:elixir_rewrite.inline(module, name, arity)],
                    uniq: true,
                    do: {module, name, arity, erlang_module, erlang_name}

              IntellijElixir.Quoter.Probe.send(__ENV__, Enum.sort(rows))
            end
            """.trimIndent()

        /**
         * `elixir_rewrite:rewrite/5` of a call with variable arguments, over every function and macro of the modules
         * [INLINE_DUMP] reads, as `module name arity erlang_module erlang_name erlang_arity`, where the receiver or the
         * name changes.
         */
        private val REWRITE_DUMP =
            """
            defmodule RewriteManifest@TOKEN@.Rewrite do
              modules = [
                Atom, Bitwise, Enum, Float, Function, Integer, IO, Kernel, Keyword, List, Map, Node, Port, Process,
                String, String.Chars, System, Tuple
              ]

              rows =
                for module <- modules,
                    Code.ensure_loaded?(module),
                    {name, arity} <- module.__info__(:functions) ++ module.__info__(:macros),
                    arguments = Macro.generate_arguments(arity, __MODULE__),
                    {{:., _, [erlang_module, erlang_name]}, _, erlang_arguments} <-
                      [:elixir_rewrite.rewrite(module, [], name, [], arguments)],
                    {erlang_module, erlang_name} != {module, name},
                    uniq: true,
                    do: {module, name, arity, erlang_module, erlang_name, length(erlang_arguments)}

              IntellijElixir.Quoter.Probe.send(__ENV__, Enum.sort(rows))
            end
            """.trimIndent()

        /**
         * The `:erlang` functions `elixir_rewrite:allowed_guard/2` admits, as `name arity`: `erl_internal:guard_bif/2`
         * or `elixir_utils:guard_op/2`, less `is_record/2,3`, as `guard_bifs.exs` reads them, after the node's
         * Erlang/OTP release, as `otp major`.
         */
        private val GUARD_DUMP =
            """
            defmodule RewriteManifest@TOKEN@.Guards do
              rows =
                for {name, arity} <- :erlang.module_info(:exports) ++ [andalso: 2, orelse: 2],
                    {name, arity} not in [is_record: 2, is_record: 3],
                    :erl_internal.guard_bif(name, arity) or :elixir_utils.guard_op(name, arity),
                    uniq: true,
                    do: {name, arity}

              otp = :erlang.system_info(:otp_release) |> List.to_integer()

              IntellijElixir.Quoter.Probe.send(__ENV__, [{:otp, otp} | Enum.sort(rows)])
            end
            """.trimIndent()
    }
}
