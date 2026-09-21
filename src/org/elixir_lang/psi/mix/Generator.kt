package org.elixir_lang.psi.mix

import com.intellij.psi.ResolveState
import org.elixir_lang.psi.call.Call
import org.elixir_lang.resolvesToModularName

object Generator {
    fun isEmbed(call: Call, state: ResolveState): Boolean =
        call.functionName()?.let { functionName ->
            functionName in NAMES && call.resolvedFinalArity() == ARITY && resolvesTo(call, state)
        } ?: false

    private fun resolvesTo(call: Call, state: ResolveState): Boolean =
            resolvesToModularName(call, state, "Mix.Generator")

    internal val NAMES = arrayOf("embed_template", "embed_text")
    private const val ARITY = 2
}
