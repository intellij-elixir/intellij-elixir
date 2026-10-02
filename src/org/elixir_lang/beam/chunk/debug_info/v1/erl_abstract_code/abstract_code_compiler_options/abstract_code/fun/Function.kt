package org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.`fun`

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.AbstractCode
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.AbstractCode.ifTag
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Atom
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.MacroString
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Scope
import org.elixir_lang.code.Identifier.inspectAsFunction

object Function {
    fun ifToMacroString(term: OtpErlangObject, scope: Scope): MacroString? =
            ifTag(term, TAG) { toMacroString(it, scope) }

    fun toMacroString(term: OtpErlangTuple, scope: Scope): MacroString {
        val string = when (val arity = term.arity()) {
            3 -> nameArityToString(term.elementAt(1), term.elementAt(2), scope)
            4 -> moduleNameArityToString(term.elementAt(1), term.elementAt(2), term.elementAt(3), scope)
            else -> AbstractCode.unknown("fun_function_arity($arity)", "fun function arity $arity", term)
        }

        return MacroString(string, doBlock = false)
    }

    private const val TAG = "function"

    private fun arityToString(arity: OtpErlangObject) =
            when (arity) {
                is OtpErlangLong -> arity.longValue().toString()
                else -> AbstractCode.toString(arity)
            }

    private fun moduleNameArityToString(
            module: OtpErlangObject,
            name: OtpErlangObject,
            arity: OtpErlangObject,
            scope: Scope
    ): String {
        val moduleString = AbstractCode.toString(module, scope)
        val nameString = nameToString(name, scope, local = false)
        val arityString = arityToString(arity)

        return "&$moduleString.$nameString/$arityString"
    }

    private fun nameArityToString(name: OtpErlangObject, arity: OtpErlangObject, scope: Scope): String {
        val nameString = nameToString(name, scope, local = true)
        val arityString = arityToString(arity)

        return "&$nameString/$arityString"
    }

    private fun nameToString(term: OtpErlangObject, scope: Scope, local: Boolean): String =
            when (term) {
                is OtpErlangAtom -> inspectAsFunction(term, local)
                else -> Atom.toElixirAtom(term)
                        ?.let { nameToString(it, scope, local) }
                        ?: AbstractCode.toString(term, scope)
            }
}
