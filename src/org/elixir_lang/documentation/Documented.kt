package org.elixir_lang.documentation

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveResult
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.declaration.DelegationPrecedence
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.declaration.Use
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.Delegation

/** The one site that chooses what a use documents, from the candidates the documentation source gives it. */
internal object Documented {
    /** What a use documents, and every element it reaches, valid or not. */
    class Choice(val element: PsiElement?, val reached: List<PsiElement>)

    /** What [use] documents; [bound] is whether a valid variable binds its name. */
    @RequiresReadLock
    fun of(use: Use, bound: Boolean = false): Choice {
        val results = Resolved.results(Resolved.projection(use, incompleteCode = false, bound, Feature.DOCUMENTATION))

        return Choice(
            element = documented(results.filter(ResolveResult::isValidResult).mapNotNull(ResolveResult::getElement)),
            reached = results.mapNotNull(ResolveResult::getElement)
        )
    }

    /**
     * Past a delegation's own `@doc`, prefer source Call elements (CallDefinitionClause), falling back to BEAM stubs
     * (CallDefinitionImpl).
     */
    fun documented(elements: List<PsiElement>): PsiElement? =
        DelegationPrecedence.documented(
            elements,
            { it is Call && Delegation.`is`(it) },
            { SourceFileDocsHelper.fetchDocs(it) != null }
        ) {
            elements.filterIsInstance<Call>().firstOrNull { CallDefinitionClause.`is`(it) }
                ?: elements.filterIsInstance<BeamCallDefinition>().firstOrNull()
        }
}
