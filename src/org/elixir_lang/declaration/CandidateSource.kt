package org.elixir_lang.declaration

import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.Unquote
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.functionNameAtomValue

/** What a use can reach, and what is visible at a position: the one place features ask. */
interface CandidateSource {
    /**
     * Every candidate at [use], by declaring element in the order the source first found each, and each element's
     * candidates in the order found. [incompleteCode] says the use is being typed, so candidates otherwise dropped may
     * be returned.
     */
    @RequiresReadLock
    fun candidates(use: Use, incompleteCode: Boolean): List<Found>

    /** What completion offers at [position]. */
    @RequiresReadLock
    fun visible(position: Call): List<Visible>
}

/**
 * A [candidate] at a use, the PSI [element] that declares it, and [via], the `import` and `use` calls and then the
 * `defdelegate` calls it was reached through.
 */
data class Found(val candidate: Candidate, val element: PsiElement, val via: List<Call>)

/**
 * The name of the module that declares [this], so two copies of one module are one. What a `use` injects is declared
 * by the module that holds the `use`, the first call [via] crossed.
 */
@RequiresReadLock
fun Found.declaringModuleName(): String? =
    when (val declared = candidate.declaration.declared) {
        is Declared.Compiled -> declared.origin.module
        is Declared.Source ->
            (via.firstOrNull().takeIf { candidate.reach == Reach.USE } ?: element as? Call)
                ?.let { CallDefinitionClause.enclosingModularMacroCall(it) }
                ?.let { SyntacticCall.of(it).canonicalName() }
    }

/**
 * What completion offers as [lookupName] at a position. [name] and [declaration] are `null` exactly when the name has
 * no atom value.
 */
data class Visible(val lookupName: String, val name: String?, val declaration: Declaration?, val element: PsiElement)

/** A use of a callable. */
sealed class Use {
    abstract val entrance: Call
    abstract val arity: Int

    /** A use that names [name], an atom value. */
    @ConsistentCopyVisibility
    data class Named internal constructor(override val entrance: Call, val name: String, override val arity: Int) : Use()

    /** A qualified `unquote`, which names no declaration but can reach any in the qualifier's module. */
    @ConsistentCopyVisibility
    data class AnyName internal constructor(override val entrance: Call, override val arity: Int) : Use()

    companion object {
        /** `null` when [call] names nothing, as when its name has no atom value. */
        @RequiresReadLock
        fun of(call: Call): Use? {
            ThreadingAssertions.assertReadAccess()

            val functionName = call.functionName() ?: return null
            val arity = call.resolvedPrimaryArity() ?: 0

            return if (call is Qualified && Unquote.isQualified(call, functionName)) {
                AnyName(call, arity)
            } else {
                functionNameAtomValue(call)?.let { Named(call, it, arity) }
            }
        }
    }
}
