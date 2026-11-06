package org.elixir_lang.beam

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.beam.chunk.DebugInfo
import org.elixir_lang.beam.chunk.beam_documentation.Docs
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.V1
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.v1.definitions.definition.DefaultArgumentClause
import org.elixir_lang.ifTaggedTuple
import org.elixir_lang.psi.call.name.Function.DEFMACRO
import org.elixir_lang.psi.call.name.Function.DEFMACROP

/**
 * Whether the compiled macro [macroNameArity] is a guard, which its `MACRO-` export cannot say. Docs says so unless
 * docs were off; debug info has the body `defguard` leaves unless the guard is a `defmacro` under `@doc guard: true`,
 * as `Kernel.is_struct/1` is. A function under `@doc guard: true`, such as `Kernel.abs/1`, is not a guard.
 */
internal fun isGuard(macroNameArity: MacroNameArity, docs: () -> Docs?, debugInfo: DebugInfo?): Boolean =
    (macroNameArity.macro == DEFMACRO || macroNameArity.macro == DEFMACROP) &&
        (docs()?.isGuard(macroNameArity) == true || hasDefguardBody(macroNameArity, debugInfo))

private fun hasDefguardBody(macroNameArity: MacroNameArity, debugInfo: DebugInfo?): Boolean {
    val definition = (debugInfo as? V1)?.definitions?.get(macroNameArity) ?: return false

    return DefaultArgumentClause.target(definition)?.clauses.orEmpty().any { isDefguardBody(it.block) }
}

/** `{:__block__, [], [Kernel.Utils, {:case, _, [Macro.Env.in_guard?(__CALLER__) | _]}]}`, from `Kernel.Utils.defguard/3`. */
private fun isDefguardBody(block: OtpErlangObject): Boolean {
    val expressions = arguments(block, "__block__") ?: return false
    if (!expressions.elementAt(0).isAtom("Elixir.Kernel.Utils")) return false
    val subject = arguments(expressions.elementAt(1), "case")?.elementAt(0) ?: return false
    val remote = arguments((subject as? OtpErlangTuple)?.elementAt(0), ".") ?: return false

    return remote.arity() == 2 && remote.elementAt(0).isAtom("Elixir.Macro.Env") && remote.elementAt(1).isAtom("in_guard?")
}

/** The arguments of [quoted] when it is the quoted call `{name, meta, arguments}`. */
private fun arguments(quoted: OtpErlangObject?, name: String): OtpErlangList? =
    ifTaggedTuple(quoted, 3, { it == name }) { it.elementAt(2) as? OtpErlangList }

private fun OtpErlangObject?.isAtom(value: String): Boolean = (this as? OtpErlangAtom)?.atomValue() == value
