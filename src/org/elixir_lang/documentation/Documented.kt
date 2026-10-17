package org.elixir_lang.documentation

import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.DelegationPrecedence
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Preferred
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.atItsHead
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
        val projection = Resolved.projection(
            Resolved.step(use, incompleteCode = false, bound, Feature.DOCUMENTATION)
                // The name at a `@spec` is the name a delegation's head writes, so a delegation comes with its targets.
                .map { preferred -> if (use is Use.Specified) withTargets(preferred) else preferred }
        )
        val valid = Resolved.results(projection).filter { it.isValidResult }.mapNotNull { it.element }.toSet()

        val element = documented(projection.filter { it.element in valid })
        val inside = element != null && held(use, projection.firstOrNull { it.element == element })

        return Choice(element, reached = projection, inside = inside)
    }

    /** As `RejectedCall` decides whether a private function is callable: an unqualified use of what the module holds. */
    fun held(use: Use, found: Found?): Boolean = !UseSymbols.remote(use) && found?.candidate?.reach?.held == true

    /** The delegation [preferred] is, with what it delegates to at its own head; any other as it is. */
    @RequiresReadLock
    private fun withTargets(preferred: Preferred): Preferred =
        (preferred.found.element as? Call)?.takeIf { preferred.found.isDelegation() }?.let { atItsHead(it) } ?: preferred

    /** [delegation] as the step prefers it at the use its own head is, for documentation. */
    @RequiresReadLock
    fun atItsHead(delegation: Call): Preferred? =
        UseSymbols.headUse(delegation)?.let { use -> atItsHead(delegation, use, Feature.DOCUMENTATION) }

    /**
     * Past a delegation that writes an `@doc`, the first source declaration that defines the function, else the first
     * compiled one, else the first delegation, which then has no target to show. A `@callback` defines nothing in its
     * own module.
     */
    @RequiresReadLock
    fun documented(found: List<Found>): PsiElement? =
        DelegationPrecedence.documented(
            found.map(Found::element),
            { it is Call && Delegation.`is`(it) },
            { it is Call && writesDoc(it) }
        ) {
            found.firstOrNull { defines(it.candidate.declaration.declared) }?.element
                ?: found.firstOrNull { it.candidate.declaration.declared is Declared.Compiled }?.element
                ?: found.firstOrNull { it.isDelegation() }?.element
        }

    private fun Found.isDelegation(): Boolean = (candidate.declaration.declared as? Declared.Source)?.form == Form.DELEGATION

    private fun defines(declared: Declared): Boolean =
        declared is Declared.Source &&
            when (declared.form) {
                Form.CLAUSE, Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED -> true
                Form.CALLBACK, Form.DELEGATION, Form.EXCEPTION -> false
            }
}
