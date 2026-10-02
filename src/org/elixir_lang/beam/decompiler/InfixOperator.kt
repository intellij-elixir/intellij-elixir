package org.elixir_lang.beam.decompiler

import org.elixir_lang.NameArity
import java.lang.StringBuilder

object InfixOperator : Default() {

    /**
     * Whether the decompiler accepts the `macroNameArity`
     *
     * @return `true` if [.append] should be called with
     * `macroNameArity`.
     */
    override fun accept(beamLanguage: String, nameArity: NameArity): Boolean =
            nameArity.arity == 2 && isInfixOperator(nameArity.name) && when (beamLanguage) {
                "elixir" -> true
                else -> nameArity.name != "in"
            }

    override fun parameters(macroNameArity: org.elixir_lang.beam.MacroNameArity): Array<String> =
            arrayOf("left", "right")

    override fun appendSignature(decompiled: StringBuilder,
                                 macroNameArity: org.elixir_lang.beam.MacroNameArity,
                                 name: String,
                                 parameters: Array<String>) {
        decompiled.append(parameters[0])
        appendName(decompiled, macroNameArity.name)
        decompiled.append(parameters[1])
    }

    override fun appendName(decompiled: StringBuilder, name: String) {
        decompiled.append(' ').append(name).append(' ')
    }

    private val INFIX_OPERATOR_SET: Set<String> = setOf(
            "!=",
            "!==",
            "&&",
            "&&&",
            "*",
            "**",
            "+",
            "++",
            "-",
            "--",
            "..",
            "/",
            "::",
            "<",
            "<-",
            "<<<",
            "<<~",
            "<=",
            "<>",
            "<|>",
            "<~",
            "<~>",
            "=",
            "==",
            "===",
            "=~",
            ">",
            ">=",
            ">>>",
            "\\\\",
            "^^^",
            "and",
            "in",
            "or",
            "|>",
            "||",
            "|||",
            "~>",
            "~>>"
    )

    /**
     * @param name [org.elixir_lang.beam.MacroNameArity.name]
     */
    private fun isInfixOperator(name: String): Boolean = INFIX_OPERATOR_SET.contains(name)
}
