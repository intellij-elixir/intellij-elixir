package org.elixir_lang.code_insight

import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.NameArityInterval
import org.elixir_lang.beam.decompiler.generatedArguments
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Found
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.structure_view.element.CallDefinitionHead

/**
 * The parameters one definition of a function or macro takes, as text. The guard is not a parameter.
 */
data class Signature(val nameArityInterval: NameArityInterval, val parameters: List<String>) {
    /** What names a definition: its name and the arities it covers, by the largest, or by the least where open. */
    internal val definition: Pair<String, Pair<Int, Boolean>>
        get() = nameArityInterval.name to nameArityInterval.arityInterval.definition()

    /** How many default arguments it declares. */
    internal val defaults: Int
        get() = nameArityInterval.arityInterval.defaults()

    companion object {
        /** `null` for a declaration that is no definition to show, as a `@callback`. */
        @RequiresReadLock
        fun of(found: Found): Signature? {
            ThreadingAssertions.assertReadAccess()

            return when (val declared = found.candidate.declaration.declared) {
                is Declared.Source ->
                    when (declared.form) {
                        Form.CLAUSE -> (found.element as? Call)?.let { of(it) }
                        Form.DELEGATION -> (found.element as? Call)?.let(::delegation)
                        Form.CALLBACK,
                        Form.EXCEPTION,
                        Form.EEX_FUNCTION_FROM,
                        Form.GENERATOR_EMBED -> null
                    }
                is Declared.Compiled -> (found.element as? BeamCallDefinition)?.let { of(it) }
            }
        }

        private fun delegation(defdelegate: Call): Signature? {
            val head =
                defdelegate.finalArguments()?.firstOrNull()?.let { CallDefinitionHead.strip(it) } as? Call
                    ?: return null
            val nameArityInterval = CallDefinitionHead.nameArityInterval(head, ResolveState.initial()) ?: return null

            return Signature(nameArityInterval, head.finalArguments()?.map { it.text }.orEmpty())
        }

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

/** What names a definition among those of one name: the largest arity it covers, or the least where open, and whether it is open. */
internal fun ArityInterval.definition(): Pair<Int, Boolean> = (maximum ?: minimum) to (maximum == null)

/** How many arities below its largest the interval covers: the default arguments a definition declares. */
internal fun ArityInterval.defaults(): Int = definition().first - minimum
