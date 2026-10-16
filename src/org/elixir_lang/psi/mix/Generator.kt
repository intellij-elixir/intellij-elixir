package org.elixir_lang.psi.mix

import com.intellij.psi.ResolveState
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.resolvesToModularName

object Generator {
    fun isEmbed(call: Call, state: ResolveState): Boolean = isEmbedShaped(SyntacticCall.of(call)) && resolvesTo(call, state)

    /** Named and called like an embed, before resolving whether it is `Mix.Generator`'s. */
    fun isEmbedShaped(call: SyntacticCall): Boolean = call.functionName() in NAMES && call.resolvedFinalArity() == ARITY

    private fun resolvesTo(call: Call, state: ResolveState): Boolean =
            resolvesToModularName(call, state, "Mix.Generator")

    private val NAMES = arrayOf("embed_template", "embed_text")
    private const val ARITY = 2
}
