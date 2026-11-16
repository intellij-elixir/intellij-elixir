package org.elixir_lang.psi

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.call.name.Function.QUOTE
import org.elixir_lang.psi.call.name.Function.TRY
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.call.keywordList
import org.elixir_lang.psi.impl.call.macroChildCallSequence
import org.elixir_lang.psi.impl.call.whileInStabBodyChildExpressions
import org.elixir_lang.psi.impl.keywordValue
import org.elixir_lang.psi.scope.Recording
import org.elixir_lang.psi.scope.WhileIn.whileIn

object QuoteMacro {
    /** [unvisited]: also skip [quoteCall] when it has been visited, as the walk's dispatcher does. */
    @RequiresReadLock
    @JvmOverloads
    fun treeWalkUp(
        quoteCall: Call,
        resolveState: ResolveState,
        keepProcessing: (PsiElement, ResolveState) -> Boolean,
        unvisited: Boolean = false
    ): Boolean =
            if (walks(quoteCall, resolveState, unvisited)) {
                quoteCall
                        .macroChildCallSequence()
                        .filter { walksChild(resolveState, it) }
                        .let {
                            treeWalkUp(
                                it,
                                Recording
                                    .enter(
                                        resolveState, "QUOTE", quoteCall, stops = true, absorbs = false,
                                        gate = { state -> walks(quoteCall, state, unvisited) }, childGate = ::walksChild
                                    )
                                    .putVisitedElement(quoteCall),
                                keepProcessing
                            )
                        }
            } else {
                true
            }

    private fun walks(quoteCall: Call, resolveState: ResolveState, unvisited: Boolean): Boolean =
        !(unvisited && resolveState.hasBeenVisited(quoteCall)) && !resolveState.containsAncestorUnquote(quoteCall)

    private fun walksChild(resolveState: ResolveState, child: PsiElement): Boolean = !resolveState.hasBeenVisited(child)

    @RequiresReadLock
    fun treeWalkUp(childCallSequence: Sequence<Call>,
                   resolveState: ResolveState,
                   keepProcessing: (PsiElement, ResolveState) -> Boolean): Boolean {
        var accumulatorKeepProcessing = true

        for (childCall in childCallSequence) {
            ProgressManager.checkCanceled()
            accumulatorKeepProcessing = when {
                If.`is`(childCall) || Unless.`is`(childCall) -> {
                    val branches = Branches(childCall)
                    // Both branches run, then `primary && alternative`; each branch stops at its own `false`.
                    val ifResolveState = Recording.enter(resolveState, "QUOTE_IF", childCall, stops = false, absorbs = false)
                    val primaryResolveState = Recording.enter(ifResolveState, "BRANCH", childCall, stops = true, absorbs = false)
                    val alternativeResolveState = Recording.enter(ifResolveState, "BRANCH", childCall, stops = true, absorbs = false)

                    val primaryKeepProcessing = whileIn(branches.primaryChildExpressions) {
                        keepProcessing(it, primaryResolveState)
                    }

                    val alternativeKeepProcessing = whileIn(branches.alternativeChildExpressions) {
                        keepProcessing(it, alternativeResolveState)
                    }

                    primaryKeepProcessing && alternativeKeepProcessing
                }

                Import.`is`(childCall) -> Import.treeWalkUp(childCall, resolveState, keepProcessing)
                Unquote.`is`(childCall) -> Unquote.treeWalkUp(childCall, resolveState, keepProcessing)
                Use.`is`(childCall) -> Use.treeWalkUp(childCall, resolveState, keepProcessing)
                childCall.isCalling(KERNEL, TRY) -> {
                    childCall.whileInStabBodyChildExpressions { grandChildExpression ->
                        keepProcessing(grandChildExpression, resolveState)
                    }
                }

                else -> keepProcessing(childCall, resolveState)
            }

            if (!accumulatorKeepProcessing) {
                break
            }
        }

        return accumulatorKeepProcessing
    }

    /**
     * The value of [quote]'s option [key], in its first final argument: `opts` of `quote(opts, do: x)`, otherwise the
     * list beside its `do`.
     */
    @JvmStatic
    @RequiresReadLock
    fun option(quote: Call, key: String): PsiElement? =
        quote.finalArguments()
            ?.firstOrNull()
            ?.keywordList()
            ?.keywordValue(key)

    @JvmStatic
    fun `is`(call: Call): Boolean = `is`(SyntacticCall.of(call))

    @JvmStatic
    fun `is`(call: SyntacticCall): Boolean {
        // TODO change Elixir.Kernel to Elixir.Kernel.SpecialForms when resolving works
        return call.isCallingMacro(KERNEL, QUOTE, 1) || // without keyword arguments
                call.isCallingMacro(KERNEL, QUOTE, 2) // with keyword arguments
    }

}
