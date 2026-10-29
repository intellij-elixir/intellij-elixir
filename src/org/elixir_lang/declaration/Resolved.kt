package org.elixir_lang.declaration

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveResult
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.structure_view.element.Delegation

/**
 * What a reference resolves to: the step's survivors at its use, of which the valid ones may be its target. The others
 * stay candidates, offered by `multiResolve` to explain why a use does not compile, but are never its target.
 */
object Resolved {
    /**
     * The step's answer at [use] from [feature]'s source: each candidate with what it delegates to. Only the candidates
     * the use [reaches] are preferred, so one it cannot reach does not displace the arity it can.
     */
    @RequiresReadLock
    fun step(
        use: Use,
        incompleteCode: Boolean,
        bound: Boolean = false,
        feature: Feature = Feature.SYMBOL_REFERENCES
    ): List<Preferred> =
        preferred(
            use,
            sourceFor(feature).candidates(use, incompleteCode).filter { reaches(use, it) },
            incompleteCode,
            bound
        )

    /** An MFA is called at runtime from another module, so it reaches only a function that module exports. */
    private fun reaches(use: Use, found: Found): Boolean =
        use !is Use.Remote ||
            found.candidate.reach.remotelyReaches(found.candidate.declaration.capabilities, runtime = true)

    /** [step] as one list: each [Preferred]'s found, then those of its admittedDelegatedTo [feature] follows. */
    @RequiresReadLock
    fun projection(step: List<Preferred>, feature: Feature): List<Found> =
        step.flatMap { preferred ->
            listOf(preferred.found) + preferred.admittedDelegatedTo.filter { follows(feature, it) }
        }

    /**
     * Whether [feature] follows a use to [target], reached through the delegations in its `via`. Find Usages and rename
     * follow only delegations whose head calls the target by its own name: through `as:`, a use names the delegation
     * alone.
     */
    @RequiresReadLock
    private fun follows(feature: Feature, target: Found): Boolean =
        when (feature) {
            Feature.FIND_USAGES_AND_RENAME ->
                target.via.filter { Delegation.`is`(it) }.all(Delegation::headKeepsTargetName)
            Feature.SYMBOL_REFERENCES,
            Feature.DOCUMENTATION,
            Feature.PARAMETER_INFO,
            Feature.HIGHLIGHTING,
            Feature.REFERENCES_INSPECTION,
            Feature.COMPLETION,
            Feature.STRUCTURE_VIEW,
            Feature.HEEX_COMPONENTS -> true
        }

    /** [step] at [use] from [feature]'s source as one list. */
    @RequiresReadLock
    fun projection(
        use: Use,
        incompleteCode: Boolean,
        bound: Boolean = false,
        feature: Feature = Feature.SYMBOL_REFERENCES
    ): List<Found> = projection(step(use, incompleteCode, bound, feature), feature)

    /**
     * [projection] as `multiResolve` gives it: a result per element, valid when one of its candidates is admitted and
     * no contract, and [Opaque] when each such candidate is `OPAQUE`.
     */
    @RequiresReadLock
    fun results(projection: List<Found>): List<ResolveResult> =
        projection
            .groupBy { it.element }
            .map { (element, found) ->
                result(element, found.filter { it.candidate.applicability.admitted && !isContract(it) })
            }

    /** [element]'s result from its [admitted] candidates: valid when one is `VALID`, [Opaque] when each is `OPAQUE`. */
    internal fun result(element: PsiElement, admitted: List<Found>): ResolveResult =
        when {
            admitted.any { it.candidate.applicability == Applicability.VALID } -> PsiElementResolveResult(element, true)
            admitted.isNotEmpty() -> Opaque(element)
            else -> PsiElementResolveResult(element, false)
        }

    /**
     * [reference]'s target: the first valid of its results that is not [Opaque], a delegation the use names before what
     * it delegates to; else its only valid result.
     */
    @JvmStatic
    fun target(reference: PsiPolyVariantReference): PsiElement? = target(reference.multiResolve(false).asList())

    /** The target [results] give, by the rule [target] applies to a reference's. */
    @JvmStatic
    fun target(results: List<ResolveResult>): PsiElement? {
        val valid = results.filter(ResolveResult::isValidResult)

        return (valid.firstOrNull { it !is Opaque } ?: valid.singleOrNull())?.element
    }

    /** A valid result only because its name or arity is unknown at the use, so one of several is no target. */
    private class Opaque(element: PsiElement) : PsiElementResolveResult(element, true)
}
