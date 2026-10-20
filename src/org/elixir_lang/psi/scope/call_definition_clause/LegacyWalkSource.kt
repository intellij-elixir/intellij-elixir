package org.elixir_lang.psi.scope.call_definition_clause

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.code_insight.completion.callDefinitionClauseVisible
import org.elixir_lang.declaration.Applicability
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.Candidate
import org.elixir_lang.declaration.CandidateSource
import org.elixir_lang.declaration.Declared
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Reach
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.Visible
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.maybeModularNameToModulars
import org.elixir_lang.psi.impl.qualifierModulars
import org.elixir_lang.psi.qualification.Unqualified
import org.elixir_lang.psi.scope.ReachedDeclaration
import org.elixir_lang.psi.scope.VisitedElementSetResolveResult
import org.elixir_lang.reference.resolver.Callable
import org.elixir_lang.structure_view.element.Delegation

/** The candidates today's call walk finds, read before `Resolver.preferred` narrows them for `multiResolve`. */
object LegacyWalkSource : CandidateSource {
    @RequiresReadLock
    override fun candidates(use: Use, incompleteCode: Boolean): List<Found> {
        ThreadingAssertions.assertReadAccess()

        val found = when (use) {
            is Use.OfCall -> Callable.walk(use.entrance, use.arity, incompleteCode).flatMap { found(it, use) }
            // A `@spec` is read in its module's body, so it names what the module declares wherever it declares it.
            is Use.Specified ->
                walk(use.modular, use.name, use.arity, incompleteCode)
                    .flatMap { found(it, use) }
                    .filter { it.candidate.reach == Reach.OWN }
            is Use.Remote ->
                use.module
                    .maybeModularNameToModulars(use.atom.containingFile, useCall = null, incompleteCode = false)
                    .flatMap { modular -> walk(modular, use.name, use.arity, incompleteCode) }
                    .flatMap { found(it, use) }
        }

        return delegatedThrough(found)
    }

    /**
     * [found] with each delegation target's [Found.delegatedThrough]: the head of the delegation the use reached it
     * through, the first `defdelegate` in its `via`, as the walk found that delegation. The walk finds one candidate
     * for each declaring element, so the first found at that element is the delegation.
     */
    private fun delegatedThrough(found: List<Found>): List<Found> {
        val heads = found.filterNot { it.candidate.reach.delegationTarget }.distinctBy(Found::element).associate { it.element to it.head }

        return found.map { target ->
            if (target.candidate.reach.delegationTarget) {
                target.copy(delegatedThrough = target.via.firstOrNull { Delegation.`is`(it) }?.let { heads[it] })
            } else {
                target
            }
        }
    }

    private fun walk(modular: PsiElement, name: String, arity: Int?, incompleteCode: Boolean): List<VisitedElementSetResolveResult> =
        MultiResolve.resolveResults(name, arity, incompleteCode, modular, ResolveState.initial(), atom = true)

    @RequiresReadLock
    override fun visible(position: Call): List<Visible> {
        ThreadingAssertions.assertReadAccess()

        // Completion offers the walk's lookups only at an unqualified call; after a qualifier it offers that module's.
        return when (position) {
            is Unqualified -> Variants.lookupElementsAndVisible(position).second
            is Qualified -> position.qualifier().qualifierModulars()?.let(::callDefinitionClauseVisible).orEmpty()
            else -> emptyList()
        }
    }

    private fun found(result: VisitedElementSetResolveResult, use: Use): List<Found> =
        result.reached.mapNotNull { reached ->
            val reach = reached.reach ?: return@mapNotNull null

            applicability(reached, use)?.let { applicability ->
                val paths = reached.visitedElementSet.filterIsInstance<Call>().filter(Callable::isPath)

                val via = paths + reached.via

                Found(
                    Candidate(reached.declaration, reach, applicability),
                    result.element,
                    via,
                    DeclaringForm.writtenHead(
                        result.element,
                        reached.declaration,
                        declaringModule(reached, reach, result.element, via),
                    ),
                    delegatedThrough = null,
                )
            }
        }

    /**
     * The name of the module that declares [reached]: a compiled one's own, and for a source one the module enclosing
     * its [element], or, for what a `use` injects, the module that holds the `use`, the first call [via] crossed.
     */
    private fun declaringModule(reached: ReachedDeclaration, reach: Reach, element: PsiElement, via: List<Call>): String? =
        when (val declared = reached.declaration.declared) {
            is Declared.Compiled -> declared.origin.module
            is Declared.Source ->
                (via.firstOrNull().takeIf { reach == Reach.USE } ?: element as? Call)
                    ?.let { CallDefinitionClause.enclosingModularMacroCall(it) }
                    ?.let { SyntacticCall.of(it).canonicalName() }
        }

    private fun applicability(reached: ReachedDeclaration, use: Use): Applicability? =
        when (use) {
            is Use.AnyName ->
                Applicability.ofAnyName(reached.declaration, reached.searchedAtom, use.arity, reached.via.map(::headArity))
            is Use.Named -> named(reached, use.arity)
            is Use.Specified -> named(reached, use.arity)
            is Use.Remote -> named(reached, use.arity)
        }

    /** A `null` [arity] is any arity: every declaration of the name it reaches is a candidate. */
    private fun named(reached: ReachedDeclaration, arity: Int?): Applicability? =
        if (reached.headNamed) {
            reached.searchedAtom?.let {
                val named = reached.declaration.name == it && reached.admitted
                val crossed = reached.via.map(::headArity)

                if (arity == null) {
                    Applicability.ofAnyArity(reached.declaration, named, crossed)
                } else {
                    Applicability.of(reached.declaration, it, named, arity, crossed)
                }
            }
        } else {
            null
        }

    /** The arity [delegation]'s head declares. */
    private fun headArity(delegation: Call): ArityKnowledge =
        Delegation.declaration(delegation, ResolveState.initial())?.arity ?: ArityKnowledge.Unknown
}
