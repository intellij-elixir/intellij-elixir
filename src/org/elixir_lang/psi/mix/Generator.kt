package org.elixir_lang.psi.mix

import com.intellij.psi.ResolveState
import org.elixir_lang.psi.call.Call
import org.elixir_lang.resolvesToQualifiedModularName

object Generator {
    fun isEmbed(call: Call, state: ResolveState): Boolean =
        call.functionName()?.let { functionName ->
            functionName in NAMES && call.resolvedFinalArity() == ARITY &&
                    resolvesToQualifiedModularName(call, state, "Mix.Generator")
        } ?: false

    internal val NAMES = arrayOf("embed_template", "embed_text")
    private const val ARITY = 2
}
