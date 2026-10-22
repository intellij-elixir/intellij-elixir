package org.elixir_lang.documentation

import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.DelegationPrecedence
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Preferred
import org.elixir_lang.declaration.RejectedCall
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.atItsHead
import org.elixir_lang.model.psi.function.UseSymbols
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.Delegation

/** The one site that chooses what a use documents, from the candidates the documentation source gives it. */
internal object Documented {
    /**
     * What a use documents, the delegation it reached that element through when it did, and whether each is [inside]: held
     * by the module the use is in, which can then call every function it defines.
     */
    class Choice(val element: PsiElement?, val delegation: Found?, val inside: Boolean, val insideDelegation: Boolean)

    /** What [use] documents; [bound] is whether a valid variable binds its name. A use of an unknown name documents nothing. */
    @RequiresReadLock
    fun of(use: Use, bound: Boolean = false): Choice {
        if (use is Use.AnyName) return Choice(element = null, delegation = null, inside = false, insideDelegation = false)

        val step = Resolved.step(use, incompleteCode = false, bound, Feature.DOCUMENTATION)
            // The name at a `@spec` is the name a delegation's head writes, so a delegation comes with its targets.
            .map { preferred -> if (use is Use.Specified) withTargets(preferred) else preferred }
        val projection = Resolved.projection(step, Feature.DOCUMENTATION)
        val valid = Resolved.results(projection).filter { it.isValidResult }.mapNotNull { it.element }.toSet()

        val documented = documented(projection.filter { it.element in valid })
        val chosen = if (documented != null) step else nearMiss(use, projection)
        val shown = if (documented != null) projection else Resolved.projection(chosen, Feature.DOCUMENTATION)
        val element = documented ?: documented(shown)
        val delegation = chosen.firstOrNull { reaches(it, element) }?.found

        return Choice(
            element,
            delegation,
            inside = element != null && held(use, shown.firstOrNull { it.element == element }),
            insideDelegation = delegation != null && held(use, delegation)
        )
    }

    /** As `RejectedCall` decides whether a private function is callable: an unqualified use of what the module holds. */
    private fun held(use: Use, found: Found?): Boolean = !UseSymbols.remote(use) && found?.candidate?.reach?.held == true

    /** Whether [preferred] is a delegation that [element] is one of the targets of. */
    @RequiresReadLock
    private fun reaches(preferred: Preferred, element: PsiElement?): Boolean =
        element != null && preferred.found.isDelegation() && preferred.admittedDelegatedTo.any { it.element == element }

    /** With no valid target, what [use] is a near miss for: each head [RejectedCall.named] gives, a delegation with its targets. */
    @RequiresReadLock
    private fun nearMiss(use: Use, projection: List<Found>): List<Preferred> {
        return RejectedCall.named(projection, Found::candidate, UseSymbols.remote(use), UseSymbols::admits)
            ?.map { withTargets(Preferred(it.candidate, emptyList())) }
            .orEmpty()
    }

    /** The delegation [preferred] is, with what it delegates to at its own head; any other as it is. */
    @RequiresReadLock
    private fun withTargets(preferred: Preferred): Preferred =
        preferred.found.head.written?.takeIf { preferred.found.isDelegation() }?.let(::headed) ?: preferred

    /** [delegation] as the step prefers it at the use its own head is, for documentation. */
    @RequiresReadLock
    fun atItsHead(delegation: Call): Preferred? = headed(Delegation.head(delegation))

    /** The delegation [written] heads, as the step prefers it at the use [written] is, for documentation. */
    @RequiresReadLock
    private fun headed(written: PsiElement?): Preferred? =
        UseSymbols.headUse(written)?.let { (head, use) -> atItsHead(head, use, Feature.DOCUMENTATION) }

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
