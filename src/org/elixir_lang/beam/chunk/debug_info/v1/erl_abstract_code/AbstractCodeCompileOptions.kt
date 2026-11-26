package org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.beam.chunk.DebugInfo
import org.elixir_lang.beam.chunk.debug_info.v1.ErlAbstractCode
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Attributes
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Functions
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Function
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.compiler_options.PropertyList
import org.elixir_lang.beam.term.inspect

class AbstractCodeCompileOptions(val v1: ErlAbstractCode, abstractCode: OtpErlangObject, compilerOptions: OtpErlangObject) : DebugInfo {
    val abstractCode by lazy { abstractCode as? OtpErlangList }
    val attributes by lazy { Attributes.from(forms) }
    val compilerOptions by lazy { PropertyList.from(compilerOptions) }
    private val forms by lazy {
        this.abstractCode?.filterIsInstance<OtpErlangTuple>() ?: emptyList()
    }
    val functions by lazy {
        forms.mapNotNull {
            Function.from(it, attributes)
        }.let(::Functions)
    }
    val inspectedModule by lazy { (attributes.module?.value as? OtpErlangAtom)?.let(::inspect) }
}
