package org.elixir_lang.ecto.query

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import org.elixir_lang.ArityRange
import org.elixir_lang.declaration.Reach
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.scope.CallDefinitionClause
import org.elixir_lang.psi.scope.WhileIn
import org.elixir_lang.psi.scope.reachedThrough
import org.elixir_lang.reference.resolver.Module as ModuleResolver

open class Nested(val name: String, private val arityRangesByName: Map<String, ArityRange>) {
    fun `is`(call: Call, state: ResolveState): Boolean =
            hasNameArity(call) && state.get(org.elixir_lang.ecto.Query.CALL) != null

    private fun hasNameArity(call: Call): Boolean =
            call.functionName()
                    ?.let { functionName ->
                        hasNameArity(call, functionName)
                    }
                    ?: false

    protected open fun hasNameArity(call: Call, functionName: String): Boolean =
                arityRangesByName[functionName]?.let { arityRange ->
                    call.resolvedFinalArity() in arityRange
                } == true

    fun treeWalkUp(call: Call,
                   state: ResolveState,
                   keepProcessing: (element: PsiElement, state: ResolveState) -> Boolean): Boolean {
        val (modulars, modularsState) = modulars(call, state)

        return treeWalkUp(modulars, call, modularsState, keepProcessing)
    }

    private fun treeWalkUp(modulars: List<Call>,
                           call: Call,
                           state: ResolveState,
                           keepProcessing: (element: PsiElement, state: ResolveState) -> Boolean): Boolean {
        val childState = state.reachedThrough(Reach.IMPORT).put(CallDefinitionClause.MODULAR_CANONICAL_NAME, name)

        val checkArguments = WhileIn.whileIn(modulars) { modular ->
            WhileIn.whileIn(CallDefinitionClause.modularCallsToExecute(modular)) { childCall ->
                keepProcessing(childCall, childState)
            }
        }

        return checkArguments && walkArguments(call, state, keepProcessing)
    }

    private fun walkArguments(call: Call,
                              state: ResolveState,
                              keepProcessing: (element: PsiElement, state: ResolveState) -> Boolean): Boolean =
            call.finalArguments()?.let { arguments -> walk(arguments, state, keepProcessing) } ?: true


    private fun walk(arguments: Array<PsiElement>,
                     state: ResolveState,
                     keepProcessing: (element: PsiElement, state: ResolveState) -> Boolean): Boolean =
            WhileIn.whileIn(arguments) { argument ->
                if (argument is Call) {
                    if (API.`is`(argument, state)) {
                        API.treeWalkUp(argument, state, keepProcessing)
                    } else if (WindowAPI.`is`(argument, state)) {
                        WindowAPI.treeWalkUp(argument, state, keepProcessing)
                    } else {
                        true
                    }
                } else {
                    true
                }
            }

    private fun modulars(call: Call, initialState: ResolveState): Pair<List<Call>, ResolveState> {
        val initialModularsByName = initialState.get(MODULARS_BY_NAME) ?: emptyMap()
        val cachedModulars = initialModularsByName[name]

        return if (cachedModulars != null) {
            cachedModulars to initialState
        } else {
            val computedModulars = modulars(call)
            val finalModularsByName = initialModularsByName + (name to computedModulars)
            val finalState = initialState.put(MODULARS_BY_NAME, finalModularsByName)

            computedModulars to finalState
        }
    }

    private fun modulars(call: Call): List<Call> =
            ModuleResolver
                    .resolvePreferred(call, name, incompleteCode = false, inScope = false)
                    .mapNotNull { it.element as? Call }

    private val MODULARS_BY_NAME = Key<Map<String, List<Call>>>("MODULARS_BY_NAME")
}
