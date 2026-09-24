package org.elixir_lang.psi

import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.call.Call

/** A function's symbol, which a use can name at an arity and which can be marked to have Go To follow a delegation. */
interface DelegationSymbol<out S> {
    val arity: Int

    /** This symbol, with Go To following the `defdelegate`'s `to:` from it. */
    fun followingDelegation(): S
}

/**
 * Which of a `defdelegate` and what it delegates to a use means, where resolution reaches both. A use names the
 * delegation, which rename and Find Usages act on, and Go To follows it to what it delegates to; navigation lands on
 * what it delegates to first; quick documentation shows the delegation's own `@doc` where it has one, as that says what
 * the function means there. Every site asks this, for its purpose.
 */
object DelegationPrecedence {
    @RequiresReadLock
    fun isDelegation(element: PsiElement?): Boolean =
        element is Call && CallableDeclaration.isForm(element, CallableDeclaration.Form.DELEGATION)

    /**
     * What a use reaching [reached] names at [arity]: the delegations' symbols, from [symbolsOf], marked
     * [DelegationSymbol.followingDelegation]; where there are none, [others].
     */
    @RequiresReadLock
    fun <R> named(
        reached: List<PsiElement>,
        arity: Int,
        symbolsOf: (Call) -> List<DelegationSymbol<R>>,
        others: () -> List<R>
    ): List<R> =
        reached
            .filterIsInstance<Call>()
            .filter(::isDelegation)
            .flatMap(symbolsOf)
            .filter { it.arity == arity }
            .map { it.followingDelegation() }
            .ifEmpty { others() }

    /** [reached] in the order navigation lands on them: what the delegations delegate to, then the delegations. */
    @RequiresReadLock
    fun <T> navigated(reached: List<T>, element: (T) -> PsiElement?): List<T> =
        reached.partition { isDelegation(element(it)) }.let { (delegations, others) -> others + delegations }

    /** Whether [narrowed] can stand in for what was reached: a list of delegations alone names no definition. */
    @RequiresReadLock
    fun <T> standsIn(narrowed: List<T>, element: (T) -> PsiElement?): Boolean =
        narrowed.any { !isDelegation(element(it)) }

    /** What quick documentation shows for [reached]: a delegation with its own `@doc`, else the first of [others]. */
    @RequiresReadLock
    fun documented(reached: List<PsiElement>, hasOwnDoc: (Call) -> Boolean, others: List<PsiElement>): PsiElement? =
        reached.filterIsInstance<Call>().firstOrNull { isDelegation(it) && hasOwnDoc(it) } ?: others.firstOrNull()
}
