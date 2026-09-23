package org.elixir_lang.model.psi.callback

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.Use
import org.elixir_lang.psi.Using
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.ElixirPsiImplUtil
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
 *
 * Injected `@behaviour` is found by scanning the used module's `__using__` definer quote directly -
 * `Use`/`Using.treeWalkUp` only surface injected call-definition clauses, not module attributes.
 */
object BehaviourMembership {
    /** Behaviour module names [module] implements (literal + `use`-injected, transitive). */
    @RequiresReadLock
    fun namesImplementedBy(module: Call): Set<String> {
        val names = linkedSetOf<String>()
        collectModule(module, names, hashSetOf())
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
        collectFromDefiner(definer, definingModule, names, hashSetOf())
        return names
    }

    /** The canonical module name of a `defmodule`/`defimpl`/`defprotocol` [call], or `null`. */
    @RequiresReadLock
    fun moduleName(call: Call): String? =
        runCatching { org.elixir_lang.psi.Module.name(call) }
            .getOrElse { if (it is ProcessCanceledException) throw it else null }

    @RequiresReadLock
    private fun collectModule(module: Call, out: MutableSet<String>, visited: MutableSet<PsiElement>) {
        if (!visited.add(module)) return
        org.elixir_lang.psi.CallDefinitionClause.modularChildCalls(module)
            .asSequence()
            .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
            .filter { ElixirPsiImplUtil.moduleAttributeName(it) == "@behaviour" }
            .forEach { out += namesFromAttr(it, module) }
        org.elixir_lang.psi.CallDefinitionClause.modularChildCalls(module)
            .asSequence()
            .filter { Use.`is`(it) }
            .forEach { useCall ->
                Use.modulars(useCall).filterIsInstance<Call>().forEach { used -> collectUseInjected(used, out, visited) }
            }
    }

    @RequiresReadLock
    private fun collectUseInjected(usedModule: Call, out: MutableSet<String>, visited: MutableSet<PsiElement>) {
        if (!visited.add(usedModule)) return
        Using.definers(usedModule).forEach { definer -> collectFromDefiner(definer, usedModule, out, visited) }
    }

    @RequiresReadLock
    private fun collectFromDefiner(
        definer: Call,
        definingModule: Call,
        out: MutableSet<String>,
        visited: MutableSet<PsiElement>
    ) {
        PsiTreeUtil
            .findChildrenOfType(definer, AtUnqualifiedNoParenthesesCall::class.java)
            .filter { ElixirPsiImplUtil.moduleAttributeName(it) == "@behaviour" }
            .forEach { out += namesFromAttr(it, definingModule) }
        PsiTreeUtil
            .findChildrenOfType(definer, Call::class.java)
            .filter { Use.`is`(it) }
            .forEach { nestedUse ->
                Use.modulars(nestedUse).filterIsInstance<Call>().forEach { used -> collectUseInjected(used, out, visited) }
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
