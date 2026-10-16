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
    fun projection(use: Use, incompleteCode: Boolean): List<Found> =
        preferred(use, sourceFor(Feature.SYMBOL_REFERENCES).candidates(use, incompleteCode), incompleteCode)
            .flatMap { listOf(it.found) + it.admittedDelegatedTo }

    /**
     * [projection] as `multiResolve` gives it: one result per element, valid when any of its candidates is and no
     * contract.
     */
    @RequiresReadLock
    fun results(projection: List<Found>): List<ResolveResult> =
        projection
            .groupBy { it.element }
            .map { (element, found) ->
                PsiElementResolveResult(
                    element,
                    found.any { it.candidate.applicability == Applicability.VALID && !isContract(it) }
                )
            }

    /** [reference]'s target: the first valid of its results, a delegation the use names before what it delegates to. */
    @JvmStatic
    fun target(reference: PsiPolyVariantReference): PsiElement? =
        reference.multiResolve(false).firstOrNull(ResolveResult::isValidResult)?.element
}
