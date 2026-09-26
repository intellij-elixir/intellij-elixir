package org.elixir_lang.psi.mix

import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.call.Call
import org.elixir_lang.resolvesToModularName

object Generator {
    /** `Mix.Generator`'s two embedding macros, and the parameters of the `prefix_suffix` function each defines. */
    enum class Embed(val suffix: String, val parameters: List<String>) {
        TEMPLATE("template", listOf("assigns")),
        TEXT("text", emptyList());

        companion object {
            @RequiresReadLock
            fun of(call: Call): Embed? = call.functionName()?.let { name -> entries.firstOrNull { "embed_${it.suffix}" == name } }
        }
    }

    fun isEmbed(call: Call, state: ResolveState): Boolean =
        Embed.of(call) != null && call.resolvedFinalArity() == ARITY && resolvesTo(call, state)

    private fun resolvesTo(call: Call, state: ResolveState): Boolean =
            resolvesToModularName(call, state, "Mix.Generator")

    private const val ARITY = 2
}
