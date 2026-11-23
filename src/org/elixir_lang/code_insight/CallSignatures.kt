package org.elixir_lang.code_insight

import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.decompiler.ParameterText
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.declaration.Use
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.CallImpl
import org.elixir_lang.psi.impl.call.isCallSite

/** What a call may be calling, and which parameter each of its arguments fills: Parameter Info and the parameter name hint read both. */
object CallSignatures {
    /**
     * The signatures [call] may be calling. It is resolved as incomplete code, so that a call whose arguments are not
     * typed yet resolves at all: complete code keeps only the arity that fits what is typed.
     */
    @RequiresReadLock
    fun of(call: Call): List<Signature> {
        // What is written as a call but calls nothing, as a spec's head, describes no function.
        if (!call.isCallSite()) return emptyList()

        return when (val use = Use.of(call)) {
            is Use.Named -> signatures(Resolved.step(use, true, feature = Feature.PARAMETER_INFO))
            // A name-less use can be any function of the module, so it describes none.
            is Use.AnyName, null -> emptyList()
        }
    }

    /**
     * The position in [signature]'s parameters that each of [call]'s primary arguments fills, by the argument's index
     * among them; an index past the end fills none. The argument at [typing] counts as written, as the one at the caret
     * does before it is typed. `null` when no argument's position is known, as after an `unquote_splicing`, whose
     * arguments are not counted.
     */
    @RequiresReadLock
    fun positions(call: Call, signature: Signature, typing: Int? = null): List<Int>? {
        if (ArityInterval.fromArguments(call.primaryArguments()?.filterNotNull()?.toTypedArray()).maximum == null) return null
        val written = call.resolvedPrimaryArity() ?: return null
        // A pipe fills the first position.
        val piped = if (CallImpl.hasPrimaryPipe(call)) 1 else 0
        val arity = maxOf(written, typing?.let { piped + it + 1 } ?: 0)

        return ParameterText.positionsReaching(signature.binding, arity).drop(piped)
    }
}
