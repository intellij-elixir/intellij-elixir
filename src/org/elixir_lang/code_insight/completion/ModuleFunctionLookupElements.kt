package org.elixir_lang.code_insight.completion

import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.code_insight.eachNameOnce
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.Form
import org.elixir_lang.declaration.Visible
import org.elixir_lang.psi.call.Call
import com.intellij.psi.ResolveState
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.scope.call_definition_clause.Declarations
import org.elixir_lang.structure_view.element.CallDefinitionHead
import org.elixir_lang.structure_view.element.Delegation
import org.elixir_lang.psi.CallDefinitionClause as CallDefinitionClausePsi
import org.elixir_lang.Arity
import org.elixir_lang.NameArityInterval

/**
 * The functions a set of [modulars] offers when completing a **remote** reference, each entry with what it declares:
 * one entry per public function name, preferring bare function heads. Only public (exported) functions are offered
 * because a remote / MFA dispatch (`Mod.fun(...)`, `apply(Mod, :fun, args)`, `{Mod, :fun, arity}`) can never reach a
 * private function, which is callable only through a local unqualified call inside its own module. Source modules
 * ([Call]) and BEAM-decompiled modules ([BeamModule]) are handled; any other element type yields nothing.
 *
 * @param arity when not `null`, only names with a definition of that arity are offered, as a capture `&Mod.name/arity`
 *   needs.
 */
@RequiresReadLock
fun callDefinitionClauseVisible(modulars: Collection<PsiElement>, arity: Arity? = null): List<Visible> {
    ThreadingAssertions.assertReadAccess()

    return offers(modulars, arity).map(Offer::visible)
}

/** One function name a modular offers, from the [element] that declares it: [form] is `null` for a `.beam` export. */
private class Offer(val name: String, val element: PsiElement, val form: Form?) {
    @RequiresReadLock
    fun visible(): Visible {
        ThreadingAssertions.assertReadAccess()

        val declaration: Declaration? = when (form) {
            null -> (element as BeamCallDefinition).declaration()
            else -> Declarations.named(form, element as Call, name)
        }

        return Visible(name, declaration?.name, declaration, element, null)
    }
}

private fun offers(modulars: Collection<PsiElement>, arity: Arity? = null): List<Offer> =
    modulars.flatMap { offers(it, arity) }

private fun offers(scope: PsiElement, arity: Arity? = null): List<Offer> = when (scope) {
    is Call -> offers(scope, arity)
    is BeamModule -> offers(scope, arity)
    else -> emptyList()
}

/** Whether [arity] is unconstrained or within [nameArityInterval]'s arities; an unknown interval is kept. */
private fun ofArity(nameArityInterval: NameArityInterval?, arity: Arity?): Boolean =
    arity == null || nameArityInterval == null || arity in nameArityInterval.arityInterval

private fun offers(scope: Call, arity: Arity?): List<Offer> {
    val childCalls = CallDefinitionClausePsi.modularChildCalls(scope)

    val publicClauses = childCalls
        .filter { CallDefinitionClausePsi.`is`(it) }
        .filter { CallDefinitionClausePsi.capabilities(it)?.public == true }
        .filter { ofArity(CallDefinitionClausePsi.nameArityInterval(it, ResolveState.initial()), arity) }

    return eachNameOnce(publicClauses, delegationOffers(childCalls, arity)) { name, clause -> Offer(name, clause, Form.CLAUSE) }
}

/**
 * The functions this module declares with `defdelegate`, each by its name.
 *
 * `Delegation.is` and `CallDefinitionClause.is` are disjoint, so delegates need their own pass or they
 * are never offered. Visibility is not filtered because there is no `defdelegatep`.
 */
private fun delegationOffers(childCalls: List<Call>, arity: Arity?): List<Pair<String, Offer>> =
    childCalls
        .filter { Delegation.`is`(it) }
        .mapNotNull { delegation ->
            delegation
                .finalArguments()
                ?.takeIf { it.size == 2 }
                ?.let { arguments -> CallDefinitionHead.nameArityInterval(arguments[0], ResolveState.initial()) }
                ?.takeIf { ofArity(it, arity) }
                ?.name
                ?.let { name -> name to Offer(name, delegation, Form.DELEGATION) }
        }

private fun offers(moduleImpl: BeamModule, arity: Arity?): List<Offer> =
    moduleImpl.callDefinitions()
        .filter { it.isExported && ofArity(it.nameArityInterval, arity) }
        .mapNotNull { callDefinition ->
            // MaybeExported documents exportedName() as null only when isExported() is false.
            callDefinition.exportedName()?.let { Offer(it, callDefinition, null) }
        }
