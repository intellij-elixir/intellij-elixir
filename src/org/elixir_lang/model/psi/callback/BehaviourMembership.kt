package org.elixir_lang.model.psi.callback

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.QuoteMacro
import org.elixir_lang.psi.Unquote
import org.elixir_lang.psi.Use
import org.elixir_lang.psi.Using
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.ElixirPsiImplUtil
import org.elixir_lang.psi.impl.indexName
import org.elixir_lang.psi.impl.maybeModularNameToModulars
import org.elixir_lang.psi.impl.moduleName as quotedModuleName

/**
 * Resolves which behaviour modules a module implements, per Elixir semantics: `@behaviour B` must be
 * present in the module's *expanded* form - a literal `@behaviour B`, or an `@behaviour B` injected
 * by a `use` (via the used module's `__using__` quote), transitively. `use B` alone is NOT enough.
 *
 * Read through [CallbackImplementation] by both the forward search ([org.elixir_lang.model.psi.ElixirSymbolUsageSearcher]:
 * callback -> implementations) and the reverse reference ([CallbackImplReference]: implementing `def` ->
 * `@callback`), so both directions stay consistent.
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

    /** Behaviour names injected by a `__using__` [definer] defined in [definingModule]. */
    @RequiresReadLock
    fun namesInjectedByDefiner(definer: Call, definingModule: Call): Set<String> {
        val names = linkedSetOf<String>()
        collectFromDefiner(definer, definingModule, names, hashSetOf())
        return names
    }

    /** Whether [quote] is one of the quotes the `__using__` [definer] injects, rather than data it returns. */
    @RequiresReadLock
    fun injects(definer: Call, quote: Call): Boolean = quote in quotesOf(definer)

    /**
     * The name a `defmodule`/`defimpl`/`defprotocol` [call] is matched and looked up by as a behaviour, or `null`.
     */
    @RequiresReadLock
    fun moduleName(call: Call): String? =
        runCatching { org.elixir_lang.psi.Module.name(call) }
            .getOrElse { if (it is ProcessCanceledException) throw it else null }

    @RequiresReadLock
    private fun collectModule(module: Call, out: MutableSet<String>, visited: MutableSet<PsiElement>) {
        if (!visited.add(module)) return
        collectOwn(module, module, out, visited)
    }

    /** The `@behaviour`s and `use`s written directly in [scope], a module or a quote, with `__MODULE__` meaning [contextModule]. */
    @RequiresReadLock
    private fun collectOwn(scope: Call, contextModule: Call, out: MutableSet<String>, visited: MutableSet<PsiElement>) {
        val calls = CallDefinitionClause.modularChildCalls(scope)

        calls
            .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
            .filter { ElixirPsiImplUtil.moduleAttributeName(it) == "@behaviour" }
            .forEach { out += namesFromAttr(it, contextModule) }
        calls
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
        quotesOf(definer).forEach { quote -> collectOwn(quote, definingModule, out, visited) }
    }

    /**
     * The quotes written in [definer]'s own body, however they are bound or returned, such as `ast = quote do ... end`,
     * and the quotes `unquote` splices into them. One inside a nested module or function is that scope's, so none is
     * descended into.
     */
    @RequiresReadLock
    private fun quotesOf(definer: Call): Set<Call> {
        val quotes = linkedSetOf<Call>()

        definer.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is Call && element !== definer) {
                    if (QuoteMacro.`is`(element)) {
                        quotes += element
                        quotes += splicedQuotes(element)
                        return
                    }
                    if (CallDefinitionClause.startsNewScope(element)) return
                }
                super.visitElement(element)
            }
        })

        return quotes
    }

    /**
     * The quotes an `unquote` in [quote] splices in, and those spliced into them. A bare nested quote is data, and one
     * written in a spliced quote is that quote's own, so neither is descended into.
     */
    @RequiresReadLock
    private fun splicedQuotes(quote: Call): List<Call> {
        val spliced = mutableListOf<Call>()

        quote.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is Call && element !== quote) {
                    if (QuoteMacro.`is`(element)) {
                        if (Unquote.isUnquotedIn(quote, element)) {
                            spliced += element
                            spliced += splicedQuotes(element)
                        }
                        return
                    }
                    if (CallDefinitionClause.startsNewScope(element)) return
                }
                super.visitElement(element)
            }
        })

        return spliced
    }

    /**
     * Behaviour module name(s) that `@behaviour` attribute [attr] refers to. Uses the value's
     * qualified-name **text** (robust when the alias is unresolved inside a `__using__` quote) plus
     * any resolved names; `__MODULE__`/`unquote(__MODULE__)` maps to [contextModule].
     */
    @RequiresReadLock
    private fun namesFromAttr(attr: AtUnqualifiedNoParenthesesCall<*>, contextModule: Call): Set<String> {
        val value = attr.argument ?: return emptySet()
        val valueText = value.text.trim()
        if (valueText.contains("__MODULE__")) return setOfNotNull(moduleName(contextModule))

        val names = when (value) {
            is ElixirAtom -> value.indexName()?.let { linkedSetOf(it) } ?: linkedSetOf()
            else -> linkedSetOf(quotedModuleName(value)?.name ?: valueText)
        }
        value
            .maybeModularNameToModulars(maxScope = value.containingFile, useCall = null, incompleteCode = false)
            .forEach { modular -> (modular as? Call)?.let { moduleName(it) }?.let { names += it } }
        return names
    }
}
