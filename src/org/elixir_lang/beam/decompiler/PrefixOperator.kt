package org.elixir_lang.beam.decompiler

import org.elixir_lang.NameArity
import java.lang.StringBuilder

object PrefixOperator : Default() {
    /**
     * Whether the decompiler accepts the `macroNameArity`
     *
     * @return `true` if [.append] should be called with
     * `macroNameArity`.
     */
    override fun accept(beamLanguage: String, nameArity: NameArity): Boolean =
            nameArity.arity == 1 && isPrefixOperator(nameArity.name)

    override fun parameters(macroNameArity: org.elixir_lang.beam.MacroNameArity): Array<String> = arrayOf("value")

    override fun appendSignature(decompiled: StringBuilder,
                                 macroNameArity: org.elixir_lang.beam.MacroNameArity,
                                 name: String,
                                 parameters: Array<String>) {
        decompiled.append('(')
        appendName(decompiled, macroNameArity.name)
        decompiled.append(parameters[0]).append(')')
    }

    private val PREFIX_OPERATOR_SET: Set<String> = setOf("+", "-")

    /**
     * @parma name [org.elixir_lang.beam.MacroNameArity.name]
     */
    private fun isPrefixOperator(name: String): Boolean = PREFIX_OPERATOR_SET.contains(name)

}
