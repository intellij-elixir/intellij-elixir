package org.elixir_lang.model.psi.callback

import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.Use
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.ElixirPsiImplUtil
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.maybeModularNameToModulars

/**
 * Resolves which behaviour modules a module implements, per Elixir semantics: `@behaviour B` must be
 * present in the module's *expanded* form - a literal `@behaviour B`, or an `@behaviour B` injected
 * by a `use` (via the used module's `__using__` quote), transitively. `use B` alone is NOT enough.
 *
 * Shared by the forward search ([org.elixir_lang.model.psi.ElixirSymbolUsageSearcher]: callback ->
 * implementations) and the reverse reference ([CallbackImplReference]: implementing `def` ->
 * `@callback`) so both directions stay consistent.
 */
object BehaviourMembership {
    /** Behaviour module names [module] implements (literal + `use`-injected, transitive). */
    @RequiresReadLock
    fun namesImplementedBy(module: Call): Set<String> {
        val names = linkedSetOf<String>()
        val childCalls = CallDefinitionClause.modularChildCalls(module)

        childCalls.forEach { collect(it, module, names) }
        childCalls
            .filter { Use.`is`(it) }
            .forEach { useCall -> Use.treeWalkUpInjected(useCall, initialState(module)) { injected, _ -> collectInjected(injected, names) } }

        return names
    }

    /** `true` if [module] implements behaviour [behaviourModuleName]. */
    @RequiresReadLock
    fun implements(module: Call, behaviourModuleName: String): Boolean =
        behaviourModuleName in namesImplementedBy(module)

    /** Behaviour names injected by a `__using__` [definer] defined in [definingModule]. */
    @RequiresReadLock
    fun namesInjectedByDefiner(definer: Call, definingModule: Call): Set<String> {
        val names = linkedSetOf<String>()

        Use.treeWalkInjectedBy(definer, initialState(definingModule)) { injected, _ -> collectInjected(injected, names) }

        return names
    }

    /** The canonical module name of a `defmodule`/`defimpl`/`defprotocol` [call], or `null`. */
    @RequiresReadLock
    fun moduleName(call: Call): String? =
        org.elixir_lang.psi.Module.nameOrNull(call)

    private fun initialState(module: Call): ResolveState = ResolveState.initial().put(ENTRANCE, module.containingFile)

    /** An injected `@behaviour` names its module in the module that wrote the quote. */
    @RequiresReadLock
    private fun collectInjected(injected: Call, out: MutableSet<String>): Boolean {
        CallDefinitionClause.enclosingModular(injected)?.let { collect(injected, it, out) }

        return true
    }

    @RequiresReadLock
    private fun collect(call: Call, contextModule: Call, out: MutableSet<String>) {
        if (call is AtUnqualifiedNoParenthesesCall<*> && ElixirPsiImplUtil.moduleAttributeName(call) == "@behaviour") {
            out += namesFromAttr(call, contextModule)
        }
    }

    /**
     * Behaviour module name(s) that `@behaviour` attribute [attr] refers to. Uses the value's
     * qualified-name **text** (robust when the alias is unresolved inside a `__using__` quote) plus
     * any resolved names; `__MODULE__`/`unquote(__MODULE__)` maps to [contextModule].
     */
    @RequiresReadLock
    private fun namesFromAttr(attr: AtUnqualifiedNoParenthesesCall<*>, contextModule: Call): Set<String> {
        val value = attr.finalArguments()?.firstOrNull() ?: return emptySet()
        val valueText = value.text.trim()
        if (valueText.contains("__MODULE__")) return setOfNotNull(moduleName(contextModule))

        val names = linkedSetOf(valueText)
        value
            .maybeModularNameToModulars(maxScope = value.containingFile, useCall = null, incompleteCode = false)
            .forEach { modular -> (modular as? Call)?.let { moduleName(it) }?.let { names += it } }
        return names
    }
}
