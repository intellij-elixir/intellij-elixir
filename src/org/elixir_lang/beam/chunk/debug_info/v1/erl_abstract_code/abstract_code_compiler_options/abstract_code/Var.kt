package org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.AbstractCode
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.AbstractCode.ifTag
import org.elixir_lang.code.Identifier.inspectAsKey
import org.elixir_lang.code.sanitizeErlangVariableName

object Var {
    fun <T> ifTo(term: OtpErlangObject?, ifTrue: (OtpErlangTuple) -> T): T? = ifTag(term, TAG, ifTrue)
    fun ifToKey(term: OtpErlangObject?): String? = ifTo(term) { toKey(it) }

    fun ifToMacroStringDeclaredScope(term: OtpErlangObject?, scope: Scope): MacroStringDeclaredScope? =
            ifTo(term) { toMacroStringDeclaredScope(it, scope) }

    fun `is`(term: OtpErlangObject?): Boolean = ifTo(term) { true } ?: false

    fun toMacroStringDeclaredScope(term: OtpErlangTuple, scope: Scope): MacroStringDeclaredScope =
            nameMacroStringDeclaredScope(term, scope)

    private const val IGNORE = "_"
    private const val TAG = "var"

    private fun nameMacroStringDeclaredScope(term: OtpErlangTuple, scope: Scope) =
            toName(term)
                    ?.let{ nameToMacroStringDeclaredScope(it, scope) }
                    ?: MacroStringDeclaredScope.missing("name", "${TAG} name", term)

    private fun nameToKey(name: OtpErlangAtom) = name.let { nameToString(it) }.let { inspectAsKey(it) }

    private fun nameToKey(name: OtpErlangObject) =
            when (name) {
                is OtpErlangAtom -> nameToKey(name)
                else -> AbstractCode.error("unknown_name:", "var name is unknown", name)
            }

    fun nameToString(name: OtpErlangAtom) = sanitizeErlangVariableName(name)

    private fun nameToMacroStringDeclaredScope(name: OtpErlangAtom, scope: Scope): MacroStringDeclaredScope {
        val varName = nameToString(name)

        return when {
            varName == IGNORE ->
                MacroStringDeclaredScope(varName, doBlock = false, Scope.EMPTY)
            scope.varNameSet.contains(varName) -> {
                val pin = if (scope.pinning) {
                    "^"
                } else {
                    ""
                }

                MacroStringDeclaredScope("$pin$varName", doBlock = false, Scope.EMPTY)
            }
            else ->
                MacroStringDeclaredScope(varName, doBlock = false, Scope(setOf(varName)))
        }
    }

    private fun nameToMacroStringDeclaredScope(name: OtpErlangObject, scope: Scope): MacroStringDeclaredScope =
            when (name) {
                is OtpErlangAtom -> nameToMacroStringDeclaredScope(name, scope)
                else -> MacroStringDeclaredScope.unknown("name", "${TAG} name", name)
            }

    private fun toKey(term: OtpErlangTuple) =
            toName(term)
                    ?.let { nameToKey(it) }
                    ?: AbstractCode.error("missing_name:", "${TAG} name", term)

    internal fun toName(term: OtpErlangTuple): OtpErlangObject? = term.elementAt(2)
}
