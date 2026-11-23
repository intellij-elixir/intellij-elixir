package org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code

import com.ericsson.otp.erlang.*
import org.elixir_lang.beam.MacroNameArity
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.function.Clause
import org.elixir_lang.beam.decompiler.Default
import org.elixir_lang.beam.decompiler.Options
import org.elixir_lang.beam.decompiler.decompiler
import org.elixir_lang.psi.call.name.Function.DEF

private const val TAG = "function"

class Function(term: OtpErlangTuple, attributes: Attributes): Node(term) {
    val name by lazy { term.elementAt(2) as? OtpErlangAtom }
    val arity by lazy { (term.elementAt(3) as? OtpErlangLong)?.intValue() }
    val clauses by lazy {
        (term.elementAt(4) as? OtpErlangList)?.let {
            it
                    .asSequence()
                    .filterIsInstance<OtpErlangTuple>()
                    .mapNotNull { Clause.from(it, attributes, this) }
                    .toList()
        } ?: emptyList()
    }
    val macroNameArity by lazy {
        val nameString = name?.atomValue() ?: "unknown_name"
        val arity = this.arity ?: 0

        MacroNameArity(DEF, nameString, arity)
    }
    val decompiler by lazy { decompiler("erlang", macroNameArity.toNameArity()) ?: Default.INSTANCE }
    val spec by lazy { attributes.spec(macroNameArity.name, macroNameArity.arity) }

    override fun toMacroString(options: Options): String =
            clauses.joinToString("\n\n") { it.toMacroString(options) }

    companion object {
        fun from(term: OtpErlangObject, attributes: Attributes): Function? =
                ifTag(term, TAG) { Function(it, attributes) }

    }
}
