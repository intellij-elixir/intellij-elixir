package org.elixir_lang.psi.scope

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Reach
import org.elixir_lang.psi.call.Call

/** @property reach `null` for a result of a walk that records none: variables, modules, types and attributes. */
class VisitedElementSetResolveResult(
    element: PsiElement,
    validResult: Boolean,
    val visitedElementSet: Set<PsiElement>,
    val reach: Reach? = null,
    reached: List<ReachedDeclaration> = emptyList()
) : PsiElementResolveResult(element, validResult) {
    constructor(element: PsiElement) : this(element, true, emptySet())

    /** Each declaration the walk reached at the element, in the order it reached them. */
    var reached: List<ReachedDeclaration> = reached
        private set

    internal fun addReached(more: List<ReachedDeclaration>) {
        if (more.isNotEmpty()) {
            reached = (reached + more).distinct()
        }
    }
}

/**
 * A declaration the walk reached, and how.
 *
 * @property searchedAtom the atom of the name searched for where [declaration] was found, carried unchanged through
 *   the delegations that reached it; `null` when that name has no atom value, as for a qualified `unquote`, or a
 *   `defdelegate` whose `as:` or head names no atom.
 * @property headNamed whether the use named every `defdelegate` head crossed exactly, rather than a prefix of it.
 * @property valid whether name and arity fit, through every head crossed.
 * @property admitted whether each `import` crossed, through every head crossed, brings in what it reaches at the arity
 *   reaching it.
 * @property via the `defdelegate` calls crossed, outermost first, which the visited set never holds.
 * @property reach how the route that reached [declaration] reached it, which for an element reached by more than one
 *   route need not be its result's [VisitedElementSetResolveResult.reach]; `null` until the walk adds it to a result.
 * @property visitedElementSet what that route visited.
 */
data class ReachedDeclaration(
    val declaration: Declaration,
    val searchedAtom: String?,
    val headNamed: Boolean,
    val valid: Boolean,
    val admitted: Boolean = true,
    val via: List<Call> = emptyList(),
    val reach: Reach? = null,
    val visitedElementSet: Set<PsiElement> = emptySet()
)
