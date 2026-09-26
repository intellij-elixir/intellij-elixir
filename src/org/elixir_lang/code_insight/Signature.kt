package org.elixir_lang.code_insight

import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.NameArityInterval
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.structure_view.element.CallDefinitionHead

/**
 * The parameters one definition of a function or macro takes, as text. The guard is not a parameter.
 */
data class Signature(val nameArityInterval: NameArityInterval, val parameters: List<String>) {
    companion object {
        /** The signature of a clause's or a `defdelegate`'s head; `null` for any other [declaration]. */
        @RequiresReadLock
        fun of(declaration: Call): Signature? {
            ThreadingAssertions.assertReadAccess()

            val head = when (CallableDeclaration.headBindingFormOf(declaration)) {
                CallableDeclaration.Form.CLAUSE -> CallDefinitionClause.head(declaration)
                CallableDeclaration.Form.DELEGATION -> CallableDeclaration.delegationHead(declaration)
                else -> null
            } ?: return null
            val nameArityInterval = CallDefinitionHead.nameArityInterval(head, ResolveState.initial()) ?: return null
            val parameters = (CallDefinitionHead.strip(head) as? Call)?.finalArguments()?.map { it.text }.orEmpty()

            return Signature(nameArityInterval, parameters)
        }

        /** A stub stores no parameters for a definition the decompiler did not render, so those get its `pN` names. */
        fun of(definition: BeamCallDefinition): Signature {
            val arity = definition.nameArityInterval.arityInterval.minimum
            val parameters = definition.parameters.takeIf { it.size == arity } ?: List(arity) { "p$it" }

            return Signature(definition.nameArityInterval, parameters)
        }
    }
}
