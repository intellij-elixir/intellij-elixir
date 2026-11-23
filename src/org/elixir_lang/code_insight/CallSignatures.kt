package org.elixir_lang.code_insight

import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.decompiler.ParameterText
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.declaration.Use
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.CallImpl

/** What a call may be calling, and which parameter each of its arguments fills: Parameter Info and the parameter name hint read both. */
object CallSignatures {
    /**
     * The signatures [call] may be calling. It is resolved as incomplete code, so that a call whose arguments are not
     * typed yet resolves at all: complete code keeps only the arity that fits what is typed.
     */
    @RequiresReadLock
    fun of(call: Call): List<Signature> =
        when (val use = Use.of(call)) {
            is Use.Named -> signatures(Resolved.step(use, true, feature = Feature.PARAMETER_INFO))
            // A name-less use can be any function of the module, so it describes none.
            is Use.AnyName, null -> emptyList()
        }

    /**
     * The position in [signature]'s parameters that each of [call]'s primary arguments fills, by the argument's index
     * among them; an index past the end fills none. `null` when no argument's position is known, as after an
     * `unquote_splicing`, whose arguments are not counted.
     */
    @RequiresReadLock
    fun positions(call: Call, signature: Signature): List<Int>? {
        if (ArityInterval.fromArguments(call.primaryArguments()?.filterNotNull()?.toTypedArray()).maximum == null) return null
        val arity = call.resolvedPrimaryArity() ?: return null

        // A pipe fills the first position.
        return ParameterText.positionsReaching(signature.binding, arity).drop(if (CallImpl.hasPrimaryPipe(call)) 1 else 0)
    }
}
