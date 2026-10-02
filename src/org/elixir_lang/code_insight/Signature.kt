package org.elixir_lang.code_insight

import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.NameArityInterval
import org.elixir_lang.beam.decompiler.generatedArguments
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.structure_view.element.CallDefinitionHead

/**
 * The parameters one definition of a function or macro takes, as text. The guard is not a parameter.
 */
data class Signature(val nameArityInterval: NameArityInterval, val parameters: List<String>) {
    companion object {
        /** `null` when [clause] is not a call definition clause. */
        @RequiresReadLock
        fun of(clause: Call): Signature? {
            ThreadingAssertions.assertReadAccess()

            if (!CallDefinitionClause.`is`(clause)) return null

            val nameArityInterval = CallDefinitionClause.nameArityInterval(clause, ResolveState.initial()) ?: return null
            val head = CallDefinitionClause.head(clause)?.let { CallDefinitionHead.strip(it) } as? Call
            val parameters = head?.finalArguments()?.map { it.text }.orEmpty()

            return Signature(nameArityInterval, parameters)
        }

        /** A stub stores no parameters for a definition the decompiler did not render, so those get generated names. */
        fun of(definition: BeamCallDefinition): Signature {
            val arity = definition.nameArityInterval.arityInterval.minimum
            val parameters = definition.parameters.takeIf { it.size == arity } ?: generatedArguments(arity)

            return Signature(definition.nameArityInterval, parameters)
        }
    }
}
