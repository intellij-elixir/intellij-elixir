package org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.type.`fun`

import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.AbstractCode
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.MacroString
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Scope
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Sequence
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Type
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.type.Product
import org.elixir_lang.beam.decompiler.Default
import org.elixir_lang.beam.decompiler.MacroNameArity

object ParameterReturn {
    fun toString(parameterReturn: OtpErlangList): String = when (val arity = parameterReturn.arity()) {
        0 -> "function()"
        2 -> toString(parameterReturn.elementAt(0), parameterReturn.elementAt(1))
        else -> AbstractCode.unknown("parameter_return_arity", "fun parameter and return arity $arity", parameterReturn)
    }

    fun toString(parameterReturn: OtpErlangList,
                 decompiler: MacroNameArity,
                 macroNameArity: org.elixir_lang.beam.MacroNameArity): String {
        val parameterString = parameterString(parameterReturn, decompiler, macroNameArity)
        val returnString = returnString(parameterReturn)

        return "$parameterString :: $returnString"
    }

    fun toString(parameter: OtpErlangObject, `return`: OtpErlangObject): String {
        val parameterString = parameterToString(parameter)
        val returnString = returnToString(`return`)

        val fnParameterString = when (parameterString) {
            "" -> "()"
            else -> parameterString
        }

        return "($fnParameterString -> $returnString)"
    }

    private fun parameterString(parameterReturn: OtpErlangList,
                                decompiler: MacroNameArity,
                                macroNameArity: org.elixir_lang.beam.MacroNameArity) =
            toParameter(parameterReturn)
                    ?.let { parameterToString(it, decompiler, macroNameArity) }
                    ?: AbstractCode.missing("parameter", "fun parameter", parameterReturn)

    private fun parameterToString(parameter: OtpErlangObject) = AbstractCode.toString(parameter)

    private fun parameterToString(parameter: OtpErlangObject,
                                  decompiler: MacroNameArity,
                                  macroNameArity: org.elixir_lang.beam.MacroNameArity): String =
        Type.ifTo(parameter) { type ->
            Product.ifTo(type) {
                Product.toOperands(type)
                        ?.let { Sequence.toMacroStringDeclaredScope(it, decompiler, macroNameArity) }
                        ?.macroString
                        ?.string
            }
        } ?: fallback(decompiler, macroNameArity)

    private fun returnString(parameterReturn: OtpErlangList) =
            toReturn(parameterReturn)
                    ?.let { returnToString(it) }
                    ?: AbstractCode.missing("return", "fun return", parameterReturn)

    private fun returnToString(return_: OtpErlangObject): String = AbstractCode.toString(return_)

    internal fun toParameter(parameterReturn: OtpErlangList): OtpErlangObject? = parameterReturn.elementAt(0)
    private fun toReturn(parameterReturn: OtpErlangList): OtpErlangObject? = parameterReturn.elementAt(1)

    private fun fallback(decompiler: MacroNameArity, macroNameArity: org.elixir_lang.beam.MacroNameArity): String =
        StringBuilder().let { fallback(it, decompiler, macroNameArity) }.toString()

    fun fallback(macroStringBuilder: StringBuilder, decompiler: MacroNameArity, macroNameArity: org.elixir_lang.beam.MacroNameArity): StringBuilder {
        val parameters = Default.INSTANCE.parameters(macroNameArity)
        decompiler.appendSignature(macroStringBuilder, macroNameArity, macroNameArity.name, parameters)

        return macroStringBuilder
    }
}
