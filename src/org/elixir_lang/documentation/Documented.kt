package org.elixir_lang.documentation

import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.code_insight.Signature
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.DelegationPrecedence
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Preferred
import org.elixir_lang.declaration.RejectedCall
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.declaration.Uncertain
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.atItsHead
import org.elixir_lang.declaration.declaringModuleName
import org.elixir_lang.declaration.isDelegation
import org.elixir_lang.model.psi.function.UseSymbols
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.Delegation

/** The one site that chooses what a use documents, from the candidates the documentation source gives it. */
internal object Documented {
    /**
     * What a use documents and its [found], the delegation it reached that element through when it did, whether each
     * is [inside]: held by the module the use is in, which can then call every function it defines, and where the use
     * reached [found] in one of a module's several copies, what each other copy documents, [copies].
     */
    class Choice(
        val element: PsiElement?,
        val found: Found?,
        val delegation: Found?,
        val inside: Boolean = false,
        val insideDelegation: Boolean = false,
        val copies: List<Found> = emptyList(),
    ) {
        /**
         * Whether the expander could not confirm that [found] is the definition the use reaches. A module's several
         * copies, each shown as its own entry where [everyCopyShown], leave none unconfirmed.
         */
        fun unconfirmed(everyCopyShown: Boolean): Boolean =
            when (found?.uncertain) {
                Uncertain.STOP, Uncertain.OPAQUE, Uncertain.UNREACHED, Uncertain.INDEX -> true
                Uncertain.SEVERAL -> copies.isEmpty() || !everyCopyShown
                // An absence's marks, which no find carries.
                Uncertain.MODULE, Uncertain.NO_EXPANSION, null -> false
            }
    }

    /** What [use] documents; [bound] is whether a valid variable binds its name. A use of an unknown name documents nothing. */
    @RequiresReadLock
    fun of(use: Use, bound: Boolean = false): Choice {
        if (use is Use.AnyName) return Choice(element = null, found = null, delegation = null)

        val step = Resolved.step(use, incompleteCode = false, bound, Feature.DOCUMENTATION)
            // The name at a `@spec` is the name a delegation's head writes, so a delegation comes with its targets.
            .map { preferred -> if (use is Use.Specified) withTargets(preferred) else preferred }
        val projection = Resolved.projection(step, Feature.DOCUMENTATION)
        val documented = documentedValid(projection)
        if (documented != null) return choice(step, documented, use)

        val nearMiss = nearMiss(use, projection)

        return choice(nearMiss, documented(Resolved.projection(nearMiss, Feature.DOCUMENTATION)), use)
    }

    /**
     * [element] as what [step] documents at [use], `null` for a link, which is seen from outside its module: its find,
     * the delegation reaching it, and its other copies.
     */
    @RequiresReadLock
    fun choice(step: List<Preferred>, element: PsiElement?, use: Use?): Choice {
        val found = element?.let { Resolved.projection(step, Feature.DOCUMENTATION).firstOrNull { it.element == element } }
        val delegation = step.firstOrNull { reaches(it, element) }?.found

        return Choice(
            element,
            found,
            delegation,
            inside = held(use, found),
            insideDelegation = held(use, delegation),
            copies = copies(step, element),
        )
    }

    /** What a step's [projection] documents among its valid finds; `null` where it has none. */
    @RequiresReadLock
    private fun documentedValid(projection: List<Found>): PsiElement? {
        val valid = Resolved.results(projection).filter { it.isValidResult }.mapNotNull { it.element }.toSet()

        return documented(projection.filter { it.element in valid })
    }

    /**
     * Where [step] reached [element] in one of a module's several copies, what each other copy documents of its name:
     * the find of what [documentedValid] gives in that copy, else of what [documented] gives.
     */
    @RequiresReadLock
    private fun copies(step: List<Preferred>, element: PsiElement?): List<Found> {
        val reached = step.firstOrNull { it.found.element == element || reaches(it, element) }?.found
            ?.takeIf { it.uncertain == Uncertain.SEVERAL }
            ?: return emptyList()
        val own = copy(reached)

        return step
            .filter {
                it.found.uncertain == Uncertain.SEVERAL &&
                    it.found.declaringModuleName() == reached.declaringModuleName() &&
                    it.found.candidate.declaration.name == reached.candidate.declaration.name
            }
            .groupBy { copy(it.found) }
            .filterKeys { it != own }
            .values
            .mapNotNull { inCopy ->
                val shown = Resolved.projection(inCopy, Feature.DOCUMENTATION)
                val documented = documentedValid(shown) ?: documented(shown)

                shown.firstOrNull { it.element == documented }
            }
    }

    /** Those of [candidates] declared in [found]'s module, in the copy of it that [found] is in. */
    @RequiresReadLock
    fun inCopyOf(found: Found, candidates: List<Found>): List<Found> {
        val module = found.declaringModuleName()
        val copy = copy(found)

        return candidates.filter { it.declaringModuleName() == module && copy(it) == copy }
    }

    /**
     * The copy of its module [found] is in: the `defmodule` that holds the call placing it there, a `use` for what the
     * `use` injects, else its file.
     */
    fun copy(found: Found): PsiElement? =
        (found.head.placedBy ?: found.element as? Call)?.let(::enclosingModularMacroCall) ?: found.element.containingFile

    /** As `RejectedCall` decides whether a private function is callable: an unqualified use of what the module holds. */
    private fun held(use: Use?, found: Found?): Boolean =
        use != null && !UseSymbols.remote(use) && found?.candidate?.reach?.held == true

    /** Whether [preferred] is a delegation that [element] is one of the targets of. */
    @RequiresReadLock
    fun reaches(preferred: Preferred, element: PsiElement?): Boolean =
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

    /** The delegation [written] heads, as the step prefers it at the use [written] is, for documentation. */
    @RequiresReadLock
    fun headed(written: PsiElement?): Preferred? =
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
            found.firstOrNull(::defines)?.element
                ?: found.firstOrNull { it.candidate.declaration.declared is Declared.Compiled }?.element
                ?: found.firstOrNull { it.isDelegation() }?.element
        }

    /** Whether [found] is a source declaration that defines its function: `defexception`'s where Elixir records one. */
    @RequiresReadLock
    private fun defines(found: Found): Boolean {
        val declaration = found.candidate.declaration
        val declared = declaration.declared as? Declared.Source ?: return false

        return when (declared.form) {
            Form.CLAUSE, Form.EEX_FUNCTION_FROM, Form.GENERATOR_EMBED -> true
            Form.EXCEPTION -> (found.element as? Call)?.let { Signature.declaredBy(it, declaration) } != null
            Form.CALLBACK, Form.DELEGATION -> false
        }
    }
}
