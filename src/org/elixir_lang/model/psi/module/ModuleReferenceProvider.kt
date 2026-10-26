package org.elixir_lang.model.psi.module

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiExternalReferenceHost
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.model.psi.PsiSymbolReferenceHints
import com.intellij.model.psi.PsiSymbolReferenceProvider
import com.intellij.model.search.SearchRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.QualifiedAlias
import org.elixir_lang.psi.impl.declaringModuleCall
import org.elixir_lang.psi.impl.isOutermostQualifiableAlias
import org.elixir_lang.psi.qualifier

@Suppress("UnstableApiUsage")
internal class ModuleReferenceProvider : PsiSymbolReferenceProvider {
    @RequiresReadLock
    override fun getReferences(
        element: PsiExternalReferenceHost,
        hints: PsiSymbolReferenceHints
    ): Collection<PsiSymbolReference> {
        if (element !is QualifiableAlias) return emptyList()
        // Only the outermost node of a chain hosts the references (the per-segment references below
        // cover the inner nodes); firing on every nested node would duplicate the reference set.
        if (!element.isOutermostQualifiableAlias()) return emptyList()
        // A module declaration's name is an anchor owned by ModuleSymbolDeclarationProvider; a reference
        // here - even an unresolving one - would shadow it.
        if (declaringModuleCall(element) != null) return emptyList()
        if (element.fullyQualifiedName() in UNDECLARED_MODULE_NAMES) return emptyList()

        val hostStartOffset = element.textRange.startOffset

        return chainNodes(element).map { node ->
            ModuleReference(element, node, segmentRange(node).shiftLeft(hostStartOffset))
        }
    }

    /**
     * Every [QualifiableAlias] node in the qualifier chain of [alias]: the full chain plus each prefix
     * reachable by descending through Alias qualifiers, e.g. `MyApp.Module.SubModule` yields
     * [`MyApp.Module.SubModule`, `MyApp.Module`, `MyApp`].
     */
    private fun chainNodes(alias: QualifiableAlias): List<QualifiableAlias> =
        generateSequence(alias) { node -> (node as? QualifiedAlias)?.qualifier() as? QualifiableAlias }
            .toList()

    override fun getSearchRequests(project: Project, target: Symbol): Collection<SearchRequest> = emptyList()
}

/**
 * Names no single module declares: `Elixir` is only the implicit shared namespace of all Aliases,
 * and `BitString` is the `defimpl ..., for: BitString` pseudo-module for bitstrings (`<<...>>`).
 */
private val UNDECLARED_MODULE_NAMES = arrayOf("Elixir", "BitString")

/**
 * The final Alias segment of [node] - `Module` in `MyApp.Module`, matching
 * [org.elixir_lang.reference.Module]'s range convention.
 *
 * Each chain node's reference must cover only its own segment, not the whole chain: goto-declaration
 * keeps only the minimal-range items containing the offset, and every segment word also carries a
 * platform-synthesized named-element declaration exactly that wide. A whole-chain range strictly
 * contains that declaration, so the reference would be discarded and Ctrl+Click on any segment word
 * would degrade to show-usages of the bogus declaration instead of navigating.
 */
internal fun segmentRange(node: QualifiableAlias): TextRange =
    when (node) {
        is QualifiedAlias -> node.getAlias().textRange
        else -> node.textRange
    }
