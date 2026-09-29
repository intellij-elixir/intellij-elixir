package org.elixir_lang.psi.impl.declarations

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.*
import org.elixir_lang.psi.ModuleAttribute.isNonReferencing
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.*
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.impl.call.CallImpl.hasDoBlockOrKeyword
import org.elixir_lang.psi.impl.call.macroDefinitionClauseForArgument
import org.elixir_lang.psi.impl.moduleWithDependentsScope
import org.elixir_lang.psi.scope.variable.BindingPattern
import org.elixir_lang.psi.stub.type.call.Stub.isModular
import org.elixir_lang.reference.Callable.Companion.isBitStreamSegmentOption
import org.elixir_lang.reference.Callable.Companion.isVariable
import org.elixir_lang.reference.Callable.Companion.variableUseScope
import org.jetbrains.annotations.Contract

fun PsiElement.selfAndFollowingSiblingsSearchScope(): LocalSearchScope {
    val selfAndFollowingSiblingList = ArrayList<PsiElement>()
    selfAndFollowingSiblingList.add(this)

    var previousSibling = this
    var followingSibling: PsiElement? = previousSibling.nextSibling

    while (followingSibling != null) {
        // Does not exclude PsiComment as Search In Comments is an option
        if (followingSibling !is ElixirEndOfExpression && followingSibling !is PsiWhiteSpace) {
            selfAndFollowingSiblingList.add(followingSibling)
        }

        previousSibling = followingSibling
        followingSibling = previousSibling.nextSibling
    }

    return LocalSearchScope(selfAndFollowingSiblingList.toTypedArray())
}

object UseScopeImpl {
    enum class UseScopeSelector {
        PARENT,
        SELF,
        SELF_AND_FOLLOWING_SIBLINGS
    }

    /**
     * Returns the scope in which references to this element are searched.
     *
     * @return the search scope instance.
     * @see {@link com.intellij.psi.search.PsiSearchHelper.getUseScope
     */
    @Contract(pure = true)
    @JvmStatic
    fun get(atUnqualifiedNoParenthesesCall: AtUnqualifiedNoParenthesesCall<*>): SearchScope =
            if (isNonReferencing(atUnqualifiedNoParenthesesCall.atIdentifier)) {
                atUnqualifiedNoParenthesesCall.moduleWithDependentsScope()
            } else {
                atUnqualifiedNoParenthesesCall.selfAndFollowingSiblingsSearchScope()
            }

    /**
     * Returns the scope in which references to this element are searched.
     *
     * @return the search scope instance.
     * @see {@link com.intellij.psi.search.PsiSearchHelper.getUseScope
     */
    @RequiresReadLock
    @JvmStatic
    fun get(unqualifiedNoArgumentsCall: UnqualifiedNoArgumentsCall<*>): SearchScope {
        val useScope: SearchScope

        if (isBitStreamSegmentOption(unqualifiedNoArgumentsCall)) {
            // Bit Stream Segment Options aren't variables or even real functions, so no use scope
            useScope = LocalSearchScope.EMPTY
        } else if (BindingPattern.of(unqualifiedNoArgumentsCall) != null) {
            var ancestor: PsiElement = unqualifiedNoArgumentsCall.parent

            while (true) {
                ProgressManager.checkCanceled()
                if (ancestor is Call) {
                    val ancestorCall = ancestor

                    val headBindingForm = CallableDeclaration.headBindingFormOf(ancestorCall)

                    if (headBindingForm == CallableDeclaration.Form.CLAUSE) {
                        val macroDefinitionClause = ancestorCall.macroDefinitionClauseForArgument()

                        if (macroDefinitionClause != null) {
                            ancestor = macroDefinitionClause
                        }

                        break
                    } else if (headBindingForm == CallableDeclaration.Form.DELEGATION) {
                        break
                    } else if (ancestorCall.hasDoBlockOrKeyword()) {
                        break
                    }
                } else if (ancestor is ElixirStabOperation) {
                    break
                } else if (ancestor is ElixirAnonymousFunction) {
                    /* `Parameter.putParameterized` stops at an anonymous function and calls what it found a
                       parameter, so this walk has to recognise one too. A well-formed `fn` hides the omission
                       behind the `ElixirStabOperation` of its `->` clause, but code being typed reaches the `fn`
                       without one. */
                    break
                } else if (ancestor is PsiFile) {
                    // a pattern with nothing to scope it, `x <- list` alone at the top level while typing
                    return variableUseScope(unqualifiedNoArgumentsCall)
                }

                ancestor = ancestor.parent ?: return LocalSearchScope.EMPTY
            }

            useScope = LocalSearchScope(ancestor)
        } else if (isVariable(unqualifiedNoArgumentsCall)) {
            useScope = variableUseScope(unqualifiedNoArgumentsCall)
        } else {
            // if the type of callable isn't known, fallback to default scope
            useScope = unqualifiedNoArgumentsCall.moduleWithDependentsScope()
        }

        return useScope
    }

    @RequiresReadLock
    @JvmStatic
    fun selector(element: PsiElement): UseScopeSelector {
        var useScopeSelector = UseScopeSelector.PARENT

        if (element is AtUnqualifiedNoParenthesesCall<*>) {
            /* Module Attribute declarations */
            useScopeSelector = UseScopeSelector.SELF_AND_FOLLOWING_SIBLINGS
        } else if (element is ElixirAnonymousFunction) {
            useScopeSelector = UseScopeSelector.SELF
        } else if (element is Call) {
            if (element.isCalling(KERNEL, CASE) ||
                    element.isCalling(KERNEL, COND) ||
                    element.isCalling(KERNEL, IF) ||
                    element.isCalling(KERNEL, RECEIVE) ||
                    element.isCalling(KERNEL, UNLESS) ||
                    element.isCalling(KERNEL, VAR_BANG)) {
                useScopeSelector = UseScopeSelector.SELF_AND_FOLLOWING_SIBLINGS
            } else if (CallableDeclaration.headBindingFormOf(element) == CallableDeclaration.Form.CLAUSE ||
                    isModular(element) || hasDoBlockOrKeyword(element)) {
                // CLAUSE only, not any head-binding form: a `defdelegate` has no body to scope anything to.
                useScopeSelector = UseScopeSelector.SELF
            }
        }

        return useScopeSelector
    }
}
