package org.elixir_lang.psi.scope.call_definition_clause

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveResult
import org.elixir_lang.NameArityInterval
import org.elixir_lang.psi.scope.NameMatch
import org.elixir_lang.psi.scope.VisitedElementSetResolveResult

/**
 * A call definition the walk found, carrying what the walk decided about it, so nothing downstream re-derives a
 * name comparison or an arity interval the walk already made - and made with context, like the special forms'
 * real arities, that a caller holding only the element no longer has.
 */
class CallDefinitionResolveResult(
    element: PsiElement,
    validResult: Boolean,
    visitedElementSet: Set<PsiElement>,
    /** The interval the call's arity was checked against. */
    val nameArityInterval: NameArityInterval,
    val nameMatch: NameMatch
) : VisitedElementSetResolveResult(element, validResult, visitedElementSet) {
    /** The same decisions, without the path the walk took, for a result that outlives the walk. */
    fun withoutVisitedElementSet(): CallDefinitionResolveResult =
        CallDefinitionResolveResult(element, isValidResult, emptySet(), nameArityInterval, nameMatch)
}

/**
 * Whether this is a definition of exactly the name resolved. Only resolution of incomplete code returns anything
 * else - definitions the name only starts, for completion - so only its readers need to ask.
 */
fun ResolveResult.isExactName(): Boolean = (this as? CallDefinitionResolveResult)?.nameMatch == NameMatch.EXACT
