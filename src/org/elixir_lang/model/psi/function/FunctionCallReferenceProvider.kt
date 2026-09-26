package org.elixir_lang.model.psi.function

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiExternalReferenceHost
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.model.psi.PsiSymbolReferenceHints
import com.intellij.model.psi.PsiSymbolReferenceProvider
import com.intellij.model.search.SearchRequest
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.variable.VariableSymbol
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.isModuleAttributeNameElement
import org.elixir_lang.psi.operation.capture.NonNumeric
import org.elixir_lang.reference.Callable
import org.elixir_lang.reference.CaptureNameArity

/**
 * Attaches a [FunctionCallReference] to a call-site expression, so "Go To Declaration" (Ctrl+Click)
 * navigates to the `def`/`defmacro` that the call invokes.
 *
 * **Invariant:** must never fire on a declaration name, or the resulting (possibly empty) reference
 * would shadow the [FunctionSymbol] declaration via `referencedData ?: declaredData` and break Find
 * Usages. Guards:
 * - `Callable.isDefiner(element)` - definers (`def`, `defmodule`, `defimpl`, etc.) are declarations.
 * - `CallDefinitionClause.isHead(element)` - the head call inside a clause (e.g. `foo(args)` inside
 *   `def foo(args)`) is part of the declaration anchor, not a call site.
 *
 * [getSearchRequests] returns nothing on purpose: the reverse direction (function -> call sites) is
 * served by [org.elixir_lang.model.psi.ElixirSymbolUsageSearcher]'s direct word search.
 */
@Suppress("UnstableApiUsage")
internal class FunctionCallReferenceProvider : PsiSymbolReferenceProvider {
    @RequiresReadLock
    override fun getReferences(
        element: PsiExternalReferenceHost,
        hints: PsiSymbolReferenceHints
    ): Collection<PsiSymbolReference> {
        if (element !is Call) return emptyList()
        if (element.isModuleAttributeNameElement()) return emptyList()
        // Module attribute elements themselves (`@callback`, `@spec`, ...) are not call sites.
        if (element is AtUnqualifiedNoParenthesesCall<*>) return emptyList()
        // A call nested inside a module attribute (e.g. `perform()` in `@callback perform()`,
        // `foo/1` in `@spec foo/1`, `MyType.t` in `@type`) is a type spec, not a call site.
        // Any Symbol-API reference attached here would shadow declarations on the containing
        // attribute element (Callback, etc.), breaking Ctrl+Click and Find Usages for those symbols.
        if (generateSequence(element.parent) { it.parent }
                .takeWhile { it !is PsiFile }
                .any { it is AtUnqualifiedNoParenthesesCall<*> }) return emptyList()
        // Definers (def, defmodule, defprotocol, defimpl, defdelegate, ...) are declarations.
        if (Callable.isDefiner(element)) return emptyList()
        // The head call inside a definition clause (e.g. `foo(args)` in `def foo(args)`) is
        // part of the declaration anchor - a reference here would shadow the FunctionSymbol.
        if (CallDefinitionClause.isHead(element)) return emptyList()
        // So is a `defdelegate` head: Go To reaches its `to:` from a use of the delegation, not from the head.
        if (CallableDeclaration.delegationHeadedBy(element) != null) return emptyList()
        // Local variable/parameter identifiers are owned by VariableSymbol.
        if (VariableSymbol.classify(element) != null) return emptyList()
        // The captured name of `&name/arity` / `&Mod.name/arity` is owned by
        // CaptureFunctionReferenceProvider: the capture-aware arity lives on the capture operation,
        // while this provider would resolve the bare name call with arity 0 - an empty reference
        // that shadows the capture-hosted one at the same offset.
        if (isCaptureName(element)) return emptyList()
        val nameElement = element.functionNameElement() ?: return emptyList()
        val rangeInElement = nameElement.textRange.shiftLeft(element.textRange.startOffset)
        return listOf(FunctionCallReference(element, rangeInElement))
    }

    override fun getSearchRequests(project: Project, target: Symbol): Collection<SearchRequest> = emptyList()

    /** True when [call] is the captured name (`name` / `Mod.name`) of an enclosing `&name/arity`. */
    @RequiresReadLock
    private fun isCaptureName(call: Call): Boolean =
        generateSequence(call.parent) { it.parent }
            .takeWhile { it !is PsiFile }
            .filterIsInstance<NonNumeric>()
            .firstOrNull()
            ?.let { capture -> (capture.reference as? CaptureNameArity)?.nameElement === call } == true
}
