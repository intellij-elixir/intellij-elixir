package org.elixir_lang.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.stub.type.call.Stub.isModular
import org.elixir_lang.resolvesToModularCalls
import org.elixir_lang.resolvesToModularName

open class ModuleWalker(val name: String, vararg nameArityRangeWalkers: NameArityRangeWalker) {
    private val nameArityRangeWalkerByName = nameArityRangeWalkers.associateBy { it.nameArityRange.name }

    fun isChild(call: Call, state: ResolveState): Boolean =
            hasChildNameArityRange(call) && matches(call, state)

    private fun hasChildNameArityRange(call: Call): Boolean =
            walkerWithName(call)?.hasArity(call) ?: false

    /** [tableMatch], falling back to the live [resolvesTo] only when it answers `null`. `Case`'s
     *  `isDescribe`/`isTest` share this rather than each re-deriving the fallback. */
    protected fun matches(call: Call, state: ResolveState): Boolean =
        tableMatch(call)?.let { it.calls.isNotEmpty() || it.matchedCompiled } ?: resolvesTo(call, state)

    /**
     * Every macro clause [call] invokes as this DSL's - answered structurally from each enclosing module's
     * [CallableTable] outward (real Elixir: `import`/`use`/`alias`/`require` written in an outer module's
     * body do apply within a nested `defmodule` written textually inside it, unlike a function definition).
     * Falls back to [resolvesToModularCalls] only when [tableMatch] answers `null` - no enclosing table
     * could be consulted for any scope, mid-build (see [CallableTable.ofOrNull]). A compiled (`.beam`)
     * target is recognized by [matches] but never appears here - it has no decompiled body for a caller like
     * `Schema.walkChild` to walk into (see [CallableTable.matchesCompiledIn]'s doc).
     */
    protected fun definers(call: Call, state: ResolveState): List<Call> =
        tableMatch(call)?.calls ?: resolvesToModularCalls(call, state, name)

    private data class ScopeMatch(val calls: List<Call>, val matchedCompiled: Boolean)

    /**
     * `null` when some enclosing scope's table could not be consulted (mid-build) *and* no other scope
     * answered first - distinct from a match with no source [Call]s and `matchedCompiled == false`, which
     * means every enclosing table was checked and none declared [call]'s name/arity. A scope skipped
     * mid-build no longer aborts the whole walk (as an earlier version did): farther-out scopes are still
     * checked, and only the fully-exhausted "nothing found, but something was skipped" case falls back live.
     */
    private fun tableMatch(call: Call): ScopeMatch? {
        val functionName = call.functionName() ?: return null
        val arity = call.resolvedFinalArity()

        var scope = call.enclosingModularAncestor()
        var anySkipped = false

        while (scope != null) {
            val table = CallableTable.ofOrNull(scope)

            if (table == null) {
                anySkipped = true
            } else {
                val calls = table.definersIn(functionName, arity, name, call)

                if (calls.isNotEmpty()) {
                    return ScopeMatch(calls, false)
                }

                if (table.matchesCompiledIn(functionName, arity, name)) {
                    return ScopeMatch(emptyList(), true)
                }
            }

            scope = scope.enclosingModularAncestor()
        }

        return if (anySkipped) null else ScopeMatch(emptyList(), false)
    }

    private fun PsiElement.enclosingModularAncestor(): Call? {
        var ancestor = parent

        while (ancestor != null) {
            if (ancestor is Call && isModular(ancestor)) {
                return ancestor
            }

            ancestor = ancestor.parent
        }

        return null
    }

    protected fun resolvesTo(call: Call, state: ResolveState) =
        if (state.hasBeenVisited(call)) {
            false
        } else {
            val updatedState = state.putVisitedElement(call)
            resolvesToModularName(call, updatedState, name)
        }

    open fun walkChild(call: Call, state: ResolveState, keepProcessing: (element: PsiElement, state: ResolveState) -> Boolean): Boolean =
            walkerWithName(call)?.let { nameArityRangeWalkerByName ->
                if (nameArityRangeWalkerByName.hasArity(call)) {
                    nameArityRangeWalkerByName.walk(call, state, keepProcessing)
                } else {
                    true
                }
            } ?: true

    private fun walkerWithName(call: Call): NameArityRangeWalker? =
            call.functionName()?.let {
                nameArityRangeWalkerByName[it]
            }
}
