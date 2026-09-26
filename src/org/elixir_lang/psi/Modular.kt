package org.elixir_lang.psi

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Name
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.psi.call.Call
import org.elixir_lang.util.AccumulatorContinue

object Modular {
    @RequiresReadLock
    @JvmStatic
    fun callDefinitionClauseCallSequence(modular: Call): Sequence<Call> =
        CallDefinitionClause.modularChildCalls(modular).asSequence().filter { CallDefinitionClause.`is`(it) }

    @RequiresReadLock
    fun callDefinitionClauseCallWhile(
        modular: PsiElement,
        resolveState: ResolveState,
        function: (Call, ResolveState) -> Boolean
    ): Boolean =
        when (modular) {
            is Call -> callDefinitionClauseCallWhile(modular, resolveState, function)
            is BeamModule -> callDefinitionClauseCallWhile(modular, resolveState, function)
            else -> true
        }

    @RequiresReadLock
    fun callDefinitionClauseCallWhile(
        modular: Call,
        resolveState: ResolveState,
        function: (Call, ResolveState) -> Boolean
    ): Boolean {
        val childCalls = CallDefinitionClause.modularChildCalls(modular)
        var keepProcessing = true

        for (childCall in childCalls) {
            ProgressManager.checkCanceled()
            if (!resolveState.hasBeenVisited(childCall) && CallDefinitionClause.`is`(childCall)) {
                val childResolveState = resolveState.putVisitedElement(childCall)

                if (!function(childCall, childResolveState)) {
                    keepProcessing = false

                    break
                }
            }
        }

        return keepProcessing
    }

    @RequiresReadLock
    @JvmStatic
    inline fun <R> callDefinitionClauseCallFoldWhile(
        modular: Call,
        initial: R,
        foldWhile: (Call, R) -> AccumulatorContinue<R>
    ): AccumulatorContinue<R> {
        val childCalls = CallDefinitionClause.modularChildCalls(modular)
        var accumulatorContinue = AccumulatorContinue(initial, true)

        for (childChild in childCalls) {
            ProgressManager.checkCanceled()
            if (CallDefinitionClause.`is`(childChild)) {
                accumulatorContinue = foldWhile(childChild, accumulatorContinue.accumulator)

                if (!accumulatorContinue.`continue`) {
                    break
                }
            }
        }

        return accumulatorContinue
    }

    @RequiresReadLock
    inline fun <R> callDefinitionClauseCallFoldWhile(
        modular: PsiElement,
        functionName: Name,
        initial: R,
        foldWhile: (PsiElement, Name, ArityInterval, R) -> AccumulatorContinue<R>
    ): AccumulatorContinue<R> =
        when (modular) {
            is Call -> callDefinitionClauseCallFoldWhile(modular, functionName, initial, foldWhile)
            is BeamModule -> callDefinitionClauseCallFoldWhile(modular, functionName, initial, foldWhile)
            else -> AccumulatorContinue(initial, true)
        }

    @RequiresReadLock
    inline fun <R> callDefinitionClauseCallFoldWhile(
        modular: Call,
        functionName: Name,
        initial: R,
        foldWhile: (PsiElement, Name, ArityInterval, R) -> AccumulatorContinue<R>
    ): AccumulatorContinue<R> =
        callDefinitionClauseCallFoldWhile(modular, initial) { callDefinitionClauseCall, acc ->
            CallDefinitionClause
                .nameArityInterval(callDefinitionClauseCall, ResolveState.initial())
                ?.let { (name, arityInterval) ->
                    if (name == functionName) {
                        foldWhile(callDefinitionClauseCall, name, arityInterval, acc)
                    } else {
                        null
                    }
                }
                ?: AccumulatorContinue(acc, true)
        }

    inline fun <R> callDefinitionClauseCallFoldWhile(
        modular: BeamModule,
        functionName: Name,
        initial: R,
        foldWhile: (PsiElement, Name, ArityInterval, R) -> AccumulatorContinue<R>
    ): AccumulatorContinue<R> =
        modular
            .callDefinitions()
            .fold(AccumulatorContinue(initial, true)) { acc, callDefinitionImpl ->
                val nameArityInterval = callDefinitionImpl.nameArityInterval
                val name = nameArityInterval.name

                if (name == functionName) {
                    foldWhile(callDefinitionImpl, name, nameArityInterval.arityInterval, acc.accumulator)
                } else {
                    acc
                }
            }
}
