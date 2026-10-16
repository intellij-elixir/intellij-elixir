package org.elixir_lang.declaration

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveResult
import com.intellij.util.concurrency.annotations.RequiresReadLock

/**
 * What a reference resolves to: the step's survivors at its use, of which the valid ones may be its target. The others
 * stay candidates, offered by `multiResolve` to explain why a use does not compile, but are never its target.
 */
object Resolved {
    /** The step's answer at [use] as one list: each [Preferred]'s found, then its admittedDelegatedTo. */
    @RequiresReadLock
    fun projection(use: Use, incompleteCode: Boolean, bound: Boolean = false): List<Found> =
        preferred(use, sourceFor(Feature.SYMBOL_REFERENCES).candidates(use, incompleteCode), incompleteCode, bound)
            .flatMap { listOf(it.found) + it.admittedDelegatedTo }

    /**
     * [projection] as `multiResolve` gives it: a result per element, valid when one of its candidates is admitted and
     * no contract, and [Opaque] when each such candidate is `OPAQUE`.
     */
    @RequiresReadLock
    fun results(projection: List<Found>): List<ResolveResult> =
        projection
            .groupBy { it.element }
            .map { (element, found) ->
                val admitted = found.filter { it.candidate.applicability.admitted && !isContract(it) }

                when {
                    admitted.any { it.candidate.applicability == Applicability.VALID } ->
                        PsiElementResolveResult(element, true)
                    admitted.isNotEmpty() -> Opaque(element)
                    else -> PsiElementResolveResult(element, false)
                }
            }

    /**
     * [reference]'s target: the first valid of its results that is not [Opaque], a delegation the use names before what
     * it delegates to; else its only valid result.
     */
    @JvmStatic
    fun target(reference: PsiPolyVariantReference): PsiElement? {
        val valid = reference.multiResolve(false).filter(ResolveResult::isValidResult)

        return (valid.firstOrNull { it !is Opaque } ?: valid.singleOrNull())?.element
    }

    /** A valid result only because its name or arity is unknown at the use, so one of several is no target. */
    private class Opaque(element: PsiElement) : PsiElementResolveResult(element, true)
}
