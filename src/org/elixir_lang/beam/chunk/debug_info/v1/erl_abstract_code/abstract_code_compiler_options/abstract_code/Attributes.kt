package org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code

import org.elixir_lang.NameArity
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.attribute.ExportType
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.attribute.MacroString
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.attribute.Spec

class Attributes(private val attributes: List<Attribute>) {
    val macroStringAttributes by lazy { attributes.mapNotNull { MacroString.from(this, it) } }
    val exportTypeNameAritySet by lazy {
        attributes
                .filter { ExportType.`is`(it) }
                .fold(setOf<NameArity>()) { acc, attribute ->  acc.union(ExportType.nameAritySet(attribute)) }
    }
    val file by lazy { attributes.find { it.name == "file" } }
    val module by lazy { attributes.find { it.name == "module" } }

    fun spec(name: String, arity: Int): Spec? = specByNameArity[NameArity(name, arity)]

    private val specByNameArity by lazy { macroStringAttributes.filterIsInstance<Spec>().associateBy(Spec::nameArity) }
}
