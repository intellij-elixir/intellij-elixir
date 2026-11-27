package org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code

import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.NameArity
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.attribute.ExportType
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.attribute.MacroString
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.attribute.Type
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.attribute.Spec

class Attributes(private val attributes: List<Attribute>) {
    val macroStringAttributes by lazy { attributes.mapNotNull { MacroString.from(this, it) } }
    val exportTypeNameAritySet: Set<NameArity> by lazy {
        attributes.filter { ExportType.`is`(it) }.flatMapTo(HashSet()) { ExportType.nameAritySet(it) }
    }
    val file by lazy { attributes.find { it.name == "file" } }
    val module by lazy { attributes.find { it.name == "module" } }
    val types by lazy { macroStringAttributes.filterIsInstance<Type>() }

    fun spec(name: String, arity: Int): Spec? = specByNameArity[NameArity(name, arity)]

    private val specByNameArity by lazy { macroStringAttributes.filterIsInstance<Spec>().associateBy(Spec::nameArity) }

    companion object {
        /** The attributes among Erlang abstract [forms]. */
        fun from(forms: Iterable<OtpErlangObject>): Attributes =
            Attributes(forms.filterIsInstance<OtpErlangTuple>().mapNotNull(Attribute::from))
    }
}
