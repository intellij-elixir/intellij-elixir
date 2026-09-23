package org.elixir_lang.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.QUOTE
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.impl.call.macroChildCallSequence
import org.elixir_lang.psi.scope.WhileIn.whileIn

object QuoteMacro {
    @RequiresReadLock
    fun treeWalkUp(quoteCall: Call, resolveState: ResolveState, keepProcessing: (PsiElement, ResolveState) -> Boolean): Boolean =
            if (!resolveState.containsAncestorUnquote(quoteCall)) {
                treeWalkUp(quoteCall.macroChildCallSequence(), resolveState.putVisitedElement(quoteCall), keepProcessing)
            } else {
                true
            }

    /**
     * [childCalls] as the code a quote puts them in runs them. What each holds in module scope is walked
     * [Import.definitionsOnly], as an `import` inside an `if` or a function reaches only its own block.
     */
    @RequiresReadLock
    fun treeWalkUp(childCalls: Sequence<Call>,
                   resolveState: ResolveState,
                   keepProcessing: (PsiElement, ResolveState) -> Boolean): Boolean =
        whileIn(childCalls.filterNot { resolveState.hasBeenVisited(it) }) { childCall ->
            val (self, inside) = CallDefinitionClause.moduleScopeParts(childCall)

            (self == null || inPlace(self, resolveState, keepProcessing)) &&
                whileIn(inside) { inPlace(it, Import.definitionsOnly(resolveState), keepProcessing) }
        }

    private fun inPlace(call: Call, resolveState: ResolveState, keepProcessing: (PsiElement, ResolveState) -> Boolean): Boolean =
        when {
            Import.`is`(call) -> Import.treeWalkUp(call, resolveState, keepProcessing)
            Unquote.`is`(call) -> Unquote.treeWalkUp(call, resolveState, keepProcessing)
            Use.`is`(call) -> Use.treeWalkUp(call, resolveState, keepProcessing)
            else -> keepProcessing(call, resolveState)
        }

    @JvmStatic
    fun `is`(call: Call): Boolean {
        // TODO change Elixir.Kernel to Elixir.Kernel.SpecialForms when resolving works
        return call.isCallingMacro(KERNEL, QUOTE, 1) || // without keyword arguments
                call.isCallingMacro(KERNEL, QUOTE, 2) // with keyword arguments
    }

}
