package org.elixir_lang.model.psi.callback

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiExternalReferenceHost
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.model.psi.PsiSymbolReferenceHints
import com.intellij.model.psi.PsiSymbolReferenceProvider
import com.intellij.model.search.SearchRequest
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.Form
import org.elixir_lang.model.psi.function.UseSymbols
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm

/**
 * Attaches a [CallbackImplReference] to an implementing `def`/`defmacro` clause's name, so
 * "Go To Declaration" navigates to the `@callback` it implements. A `defdelegate` head gets none: it declares its own
 * `FunctionSymbol`.
 *
 * Only creates a reference for a clause that implements a callback. A reference that resolves to
 * nothing would shadow the `FunctionSymbol` declaration via `referencedData ?: declaredData`,
 * breaking Ctrl+Click / Find Usages on ordinary functions.
 *
 * [getSearchRequests] returns nothing on purpose: callback -> implementations (reverse Find Usages) is
 * served by [org.elixir_lang.model.psi.ElixirSymbolUsageSearcher]'s direct query, so these references
 * are not text-searched during Find Usages - avoiding double-listing each implementation.
 */
@Suppress("UnstableApiUsage")
internal class CallbackImplReferenceProvider : PsiSymbolReferenceProvider {
    @RequiresReadLock
    override fun getReferences(element: PsiExternalReferenceHost, hints: PsiSymbolReferenceHints): Collection<PsiSymbolReference> {
        if (element !is Call || !CallDefinitionClause.`is`(element)) return emptyList()
        // A `def` directly inside a `defprotocol` declares a protocol function, not a callback implementation.
        val enclosingModular = CallDefinitionClause.enclosingModularMacroCall(element)
        if (enclosingModular == null || Protocol.`is`(enclosingModular)) return emptyList()
        if (CallbackImplementation.implementedCallbacks(element).isEmpty()) return emptyList()
        return DeclaringForm.nameElements(element, Form.CLAUSE).map { nameElement ->
            CallbackImplReference(element, UseSymbols.nameRange(nameElement).shiftLeft(element.textRange.startOffset))
        }
    }

    override fun getSearchRequests(project: Project, target: Symbol): Collection<SearchRequest> = emptyList()
}
