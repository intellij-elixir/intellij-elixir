package org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.attribute.spec

import com.ericsson.otp.erlang.*
import org.elixir_lang.Macro.ifTupleTo
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.AbstractCode
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.attribute.spec.value.Definition
import org.elixir_lang.beam.decompiler.Default
import org.elixir_lang.beam.decompiler.MacroNameArity
import org.elixir_lang.beam.decompiler.decompiler
import org.elixir_lang.psi.call.name.Function.DEF
import java.math.BigInteger

object Value {
    fun toStrings(value: OtpErlangObject): List<String> =
            when (value) {
                is OtpErlangTuple -> toStrings(value)
                else -> emptyList()
            }

    internal fun arityToBigInteger(arity: OtpErlangObject): BigInteger =
            when (arity) {
                is OtpErlangLong -> arity.bigIntegerValue()
                else -> BigInteger("-1")
            }

    internal fun nameToString(name: OtpErlangObject): String =
            when (name) {
                is OtpErlangAtom -> nameToString(name)
                else -> AbstractCode.unknown("name", "Attribute value name", name)
            }

    private fun toMacroNameArity(value: OtpErlangTuple): org.elixir_lang.beam.MacroNameArity? =
         toNameArity(value)?.let { nameArityTerm ->
            ifTupleTo(nameArityTerm, 2) { tuple ->
                tuple.elementAt(0).let { it as? OtpErlangAtom }?.atomValue()?.let { name ->
                    tuple.elementAt(1)?.let { it as? OtpErlangLong }?.intValue()?.let { arity ->
                        org.elixir_lang.beam.MacroNameArity(DEF, name, arity)
                    }
                }
            }
        }

    internal fun toNameArity(value: OtpErlangTuple): OtpErlangObject? = value.elementAt(0)

    private fun nameToString(name: OtpErlangAtom) = name.atomValue()

    private fun toDefinitions(value: OtpErlangTuple): OtpErlangObject? = value.elementAt(1)

    private fun toStrings(value: OtpErlangTuple) =
            toMacroNameArity(value)
                    ?.let { macroNameArity ->
                        val decompiler = decompiler("erlang", macroNameArity.toNameArity()) ?: Default.INSTANCE

                        toDefinitions(value)
                                ?.let { definitionsToStrings(it, decompiler, macroNameArity) }
                    }
                    .orEmpty()

    private fun definitionsToStrings(definitions: OtpErlangObject, decompiler: MacroNameArity, macroNameArity: org.elixir_lang.beam.MacroNameArity) =
            when (definitions) {
                is OtpErlangList -> definitionsToStrings(definitions, decompiler, macroNameArity)
                else -> emptyList()
            }

    private fun definitionsToStrings(definitions: OtpErlangList, decompiler: MacroNameArity, macroNameArity: org.elixir_lang.beam.MacroNameArity) =
            definitions.map {
                Definition.toString(it, decompiler, macroNameArity)
            }
}
