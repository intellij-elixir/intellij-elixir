package org.elixir_lang.psi.scope.variable

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.ResolveResult
import com.intellij.psi.ResolveState
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.ex_unit.Assertions
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.previousSiblingExpression
import org.elixir_lang.psi.impl.ProcessDeclarationsImpl.DECLARING_SCOPE
import org.elixir_lang.psi.operation.Match
import org.elixir_lang.psi.scope.MultiResolve.keepProcessing
import org.elixir_lang.psi.scope.NameMatch
import org.elixir_lang.psi.scope.Variable
import org.elixir_lang.psi.scope.VisitedElementSetResolveResult
import org.elixir_lang.psi.stub.index.QuoteVariableName
import org.elixir_lang.reference.Callable
import org.jetbrains.annotations.Contract

class MultiResolve(private val name: String, private val incompleteCode: Boolean) : Variable() {
    private val resolveResultList = mutableListOf<VisitedElementSetResolveResult>()

    /**
     * Decides whether `match` matches the criteria being searched for.  All other [.executeOnVariable] methods
     * eventually end here.
     *
     * @param match
     * @param state
     */
    override fun executeOnVariable(match: PsiNamedElement, state: ResolveState): Boolean {
        addToResolveResultListIfMatchingName(match, state)
        return keepProcessing(incompleteCode, resolveResultList)
    }

    private fun addToResolveResultList(element: PsiElement, state: ResolveState, validResult: Boolean) {
        if (state.get(DECLARING_SCOPE) == false) return

        /* A pattern binds what it writes, so it answers for itself whatever match it sits under; the symbol model
           applies the same rule, so what the resolver files is what Find Usages and rename call a declaration. */
        if (!BindingPattern.isMatchRead(element)) {
            resolveResultList.add(VisitedElementSetResolveResult(element, validResult, state.visitedElementSet()))
            return
        }

        /* A read on the right of `=` declares its name only if nothing above binds it, so its earlier bindings are
           looked up: not when this is that lookup meeting the read again, and not for a prefix-named read, which is
           a candidate for incomplete code and never a declaration of the searched name. */
        val lastBinding = state.get(LAST_BINDING_KEY)
        val earlier = if (validResult && (lastBinding == null || !element.isEquivalentTo(lastBinding))) {
            earlierBindingResults(name, incompleteCode, element, state)
        } else {
            emptyList()
        }
        val bound = earlier.filter { it.isValidResult }

        if (bound.isNotEmpty()) {
            resolveResultList.addAll(bound)
        } else if (!element.isEquivalentTo(state.get(ENTRANCE))) {
            // an unbound read stands in as the declaration for the reads after it, but never for itself
            resolveResultList.add(VisitedElementSetResolveResult(element, validResult, state.visitedElementSet()))
        }
        // a prefix-named earlier variable is a candidate for incomplete code, whether or not the read is bound
        if (incompleteCode) {
            resolveResultList.addAll(earlier.filter { !it.isValidResult })
        }
    }

    /** The results of resolving [name] just before the match [element] sits on the right of; empty elsewhere. */
    private fun earlierBindingResults(
        name: String,
        incompleteCode: Boolean,
        element: PsiElement,
        state: ResolveState
    ): List<VisitedElementSetResolveResult> {
        val match = PsiTreeUtil.getContextOfType(element, Match::class.java) ?: return emptyList()
        val rightOperand = match.rightOperand() ?: return emptyList()

        return if (PsiTreeUtil.isAncestor(rightOperand, element, false)) {
            resolveBefore(name, incompleteCode, match, element, state)
        } else {
            emptyList()
        }
    }

    private fun addToResolveResultListIfMatchingName(match: PsiNamedElement, state: ResolveState) {
        match.name?.let { name ->
            val nameMatch = NameMatch.of(this.name, name, match)

            if (nameMatch != NameMatch.NONE) {
                addToResolveResultList(match, state, nameMatch == NameMatch.EXACT)
            }
        }
    }

    companion object {
        private val LAST_BINDING_KEY = Key<PsiElement>("LAST_BINDING_KEY")

        /** Resolves [name] from just before [match], as a read there would, on behalf of [element] inside it. */
        private fun resolveBefore(
            name: String,
            incompleteCode: Boolean,
            match: Match,
            element: PsiElement,
            state: ResolveState
        ): List<VisitedElementSetResolveResult> {
            val expression = previousExpression(match, state) ?: return emptyList()
            val searchState = ResolveState.initial()
                .put(ENTRANCE, match)
                .putInitialVisitedElement(match)
                .putVisitedElements(state)
                .put(LAST_BINDING_KEY, element)

            return resolveInScope(name, incompleteCode, expression, searchState)
        }

        fun resolveResultList(name: String,
                              incompleteCode: Boolean,
                              entrance: PsiElement): List<VisitedElementSetResolveResult> =
            if (name == Callable.IGNORED) {
                listOf<VisitedElementSetResolveResult>(VisitedElementSetResolveResult(entrance, true, emptySet()))
            } else {
                val resolveState = ResolveState.initial().put(ENTRANCE, entrance).putInitialVisitedElement(entrance)

                resolveInScope(name, incompleteCode, entrance, resolveState)
                        .takeIf { set -> set.any(ResolveResult::isValidResult) }
                        ?: nameInAnyQuote(entrance, name, incompleteCode)
            }

        /**
         * The declarations of [name] that a read placed just before [declaration]'s match resolves to: what an
         * assignment there rebinds. Empty when [declaration] is not bound by a match.
         */
        fun earlierBindings(name: String, declaration: PsiElement): List<PsiElement> {
            val match = PsiTreeUtil.getContextOfType(declaration, Match::class.java) ?: return emptyList()
            val state = ResolveState.initial().put(ENTRANCE, match).putInitialVisitedElement(match)

            return resolveBefore(name, false, match, declaration, state)
                .mapNotNull { result -> result.element.takeIf { result.isValidResult } }
        }

        /** @param name normalized here, once, so every entry into the walk compares the same way. */
        fun resolveInScope(name: String,
                           incompleteCode: Boolean,
                           entrance: PsiElement,
                           resolveState: ResolveState): List<VisitedElementSetResolveResult> {
            val multiResolve = MultiResolve(NameMatch.query(name, entrance), incompleteCode)

            val treeWalkUpResolveState = if (resolveState.get(ENTRANCE) == null) {
                resolveState.put(ENTRANCE, entrance)
            } else {
                resolveState
            }

            PsiTreeUtil.treeWalkUp(
                    multiResolve,
                    entrance,
                    entrance.containingFile,
                    treeWalkUpResolveState
            )

            return multiResolve.resolveResultList
        }

        private fun nameInAnyQuote(entrance: PsiElement,
                                   name: String,
                                   incompleteCode: Boolean): List<VisitedElementSetResolveResult> {
            val project = entrance.project
            val resolveResults = mutableListOf<VisitedElementSetResolveResult>()

            if (!DumbService.isDumb(project)) {
                val stubIndex = StubIndex.getInstance()
                val keys = mutableListOf<String>()
                val query = NameMatch.query(name, entrance)

                stubIndex.processAllKeys(QuoteVariableName.KEY, project) { key ->
                    // A key is a declared name with no element of its own yet, so it is normalized at the
                    // language level of the read looking for it.
                    val nameMatch = NameMatch.of(query, key, entrance)

                    if ((incompleteCode && nameMatch != NameMatch.NONE) || nameMatch == NameMatch.EXACT) {
                        keys.add(key)
                    }

                    true
                }

                val scope = GlobalSearchScope.allScope(project)
                // results are never valid because the qualifier is unknown
                val validResult = false

                for (key in keys) {
                    stubIndex
                            .processElements(QuoteVariableName.KEY, key, project, scope, NamedElement::class.java) { namedElement ->
                                resolveResults.add(VisitedElementSetResolveResult(namedElement, validResult, emptySet()))

                                true
                            }
                }
            }

            return resolveResults
        }

        @Contract(pure = true)
        private fun previousExpression(element: PsiElement, state: ResolveState): PsiElement? =
            previousSiblingExpression(element) ?: previousParentExpression(element, state)

        @Contract(pure = true)
        private fun previousParentExpression(element: PsiElement, state: ResolveState): PsiElement? {
            var expression = element
            do {
                expression = expression.parent
            } while (expression is Arguments ||
                    expression is ElixirDoBlock ||
                    expression is ElixirStab ||
                    expression is ElixirStabBody || (expression is Call && Assertions.isChild(expression, state)))
            return expression
        }
    }
}
