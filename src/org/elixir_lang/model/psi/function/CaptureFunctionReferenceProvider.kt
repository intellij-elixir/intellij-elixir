package org.elixir_lang.model.psi.function

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiExternalReferenceHost
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.model.psi.PsiSymbolReferenceHints
import com.intellij.model.psi.PsiSymbolReferenceProvider
import com.intellij.model.search.SearchRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.protocol.ProtocolFunction
import org.elixir_lang.psi.operation.capture.NonNumeric
import org.elixir_lang.reference.CaptureNameArity

/**
 * Attaches a [CaptureFunctionReference] to a `&name/arity` / `&Mod.name/arity` capture, so
 * Ctrl+Click and Rename on the captured NAME resolve to the [FunctionSymbol] it captures.
 *
 * Hosted on the capture operation itself (not the inner name call) because the bare name inside a
 * capture classifies as a *variable* to the legacy scope-walker - [FunctionCallReferenceProvider]
 * deliberately bails there - while the capture-aware name/arity resolution already lives on the
 * capture element's own [CaptureNameArity] reference, which this bridges into the Symbol API the
 * same way [FunctionCallReference] bridges [org.elixir_lang.reference.Callable].
 */
@Suppress("UnstableApiUsage")
internal class CaptureFunctionReferenceProvider : PsiSymbolReferenceProvider {
    @RequiresReadLock
    override fun getReferences(
        element: PsiExternalReferenceHost,
        hints: PsiSymbolReferenceHints
    ): Collection<PsiSymbolReference> {
        if (element !is NonNumeric) return emptyList()
        val legacyReference = element.reference as? CaptureNameArity ?: return emptyList()
        return listOf(CaptureFunctionReference(element, legacyReference))
    }

    override fun getSearchRequests(project: Project, target: Symbol): Collection<SearchRequest> = emptyList()
}

/**
 * Symbol reference from the captured name of `&name/arity` to the [FunctionSymbol] (or
 * [ProtocolFunction]) of that name and arity. Resolution delegates to the legacy capture-aware
 * [CaptureNameArity] reference and ranks what it resolves to with [functionSymbolsReached], as a call does.
 */
@Suppress("UnstableApiUsage")
class CaptureFunctionReference(
    private val capture: NonNumeric,
    private val legacyReference: CaptureNameArity
) : PsiSymbolReference {
    override fun getElement(): PsiElement = capture

    // The captured name alone - not the `&`, the `/arity`, or a `Mod.` qualifier.
    override fun getRangeInElement(): TextRange = legacyReference.rangeInElement

    @RequiresReadLock
    override fun resolveReference(): Collection<Symbol> {
        val resolved = legacyReference.multiResolve(false).filter { it.isValidResult }

        return functionSymbolsReached(resolved, legacyReference.arity)
    }
}
