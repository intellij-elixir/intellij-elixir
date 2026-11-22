package org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.type

import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.AbstractCode
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Type.ifSubtypeTo
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.type.`fun`.ParameterReturn
import org.elixir_lang.beam.decompiler.MacroNameArity

object Fun {
    fun ifToString(type: OtpErlangTuple): String? = ifTo(type) { toString(type) }

    fun ifToString(type: OtpErlangTuple,
                   decompiler: MacroNameArity,
                   macroNameArity: org.elixir_lang.beam.MacroNameArity): String? =
            ifTo(type) { toString(type, decompiler, macroNameArity) }

    private const val SUBTYPE = "fun"

    internal fun <T> ifTo(type: OtpErlangTuple, ifTrue: (OtpErlangTuple) -> T): T? = ifSubtypeTo(type, SUBTYPE, ifTrue)

    private fun toString(type: OtpErlangTuple) = toString(type) { ParameterReturn.toString(it) }

    private fun toString(type: OtpErlangTuple,
                         decompiler: MacroNameArity,
                         macroNameArity: org.elixir_lang.beam.MacroNameArity) =
            toString(type) { ParameterReturn.toString(it, decompiler, macroNameArity) }

    private fun toString(
            type: OtpErlangTuple,
            parameterReturnToString: (OtpErlangList) -> String
    ): String = when (val parameterReturn = toParameterReturn(type)) {
        is OtpErlangList -> parameterReturnToString(parameterReturn)
        else -> AbstractCode.unknown("parameter_return", "${SUBTYPE} parameter and return", type)
    }

    internal fun toParameterReturn(type: OtpErlangTuple): OtpErlangObject? = type.elementAt(3)
}
