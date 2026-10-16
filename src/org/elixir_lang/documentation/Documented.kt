package org.elixir_lang.documentation

import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.DelegationPrecedence
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.declaration.Use
import org.elixir_lang.model.psi.function.UseSymbols
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.Delegation

/** The one site that chooses what a use documents, from the candidates the documentation source gives it. */
internal object Documented {
    /**
     * What a use documents, and every candidate it reaches, valid or not. It is [inside] when the element is held by the
     * module the use is in, which can then call every function it defines.
     */
    class Choice(val element: PsiElement?, val reached: List<Found>, val inside: Boolean)

    /** What [use] documents; [bound] is whether a valid variable binds its name. */
    @RequiresReadLock
    fun of(use: Use, bound: Boolean = false): Choice {
        val projection = Resolved.projection(use, incompleteCode = false, bound, Feature.DOCUMENTATION)
        val valid = Resolved.results(projection).filter { it.isValidResult }.mapNotNull { it.element }.toSet()

        val element = documented(projection.filter { it.element in valid })
        // As `RejectedCall` decides whether a private function is callable: an unqualified use of what the module holds.
        val inside = element != null && !UseSymbols.remote(use) &&
            projection.firstOrNull { it.element == element }?.candidate?.reach?.held == true

        return Choice(element, reached = projection, inside = inside)
    }

    /**
     * Past a delegation's own `@doc`, the first source declaration that defines the function, else the first compiled
     * one. A `@callback` defines nothing in its own module.
     */
    @RequiresReadLock
    fun documented(found: List<Found>): PsiElement? =
        DelegationPrecedence.documented(
            found.map(Found::element),
            { it is Call && Delegation.`is`(it) },
            { SourceFileDocsHelper.fetchDocs(it, remote = true) != null }
        ) {
            found.firstOrNull { defines(it.candidate.declaration.declared) }?.element
                ?: found.firstOrNull { it.candidate.declaration.declared is Declared.Compiled }?.element
        }

    private fun defines(declared: Declared): Boolean =
        declared is Declared.Source &&
            when (declared.form) {
                Form.CLAUSE, Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED -> true
                Form.CALLBACK, Form.DELEGATION, Form.EXCEPTION -> false
            }
}
