package org.elixir_lang.reference.resolver

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.declaration.Uncertain
import org.elixir_lang.declaration.Use as DeclarationUse
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.VisitedElementSetResolveResult
import org.elixir_lang.psi.scope.call_definition_clause.LegacyWalkSource

object Callable : ResolveCache.PolyVariantResolver<org.elixir_lang.reference.Callable> {
    override fun resolve(callable: org.elixir_lang.reference.Callable, incompleteCode: Boolean): Array<ResolveResult> {
        ApplicationManager.getApplication().assertReadAccessAllowed()

        return answer(callable.element, incompleteCode).results().toTypedArray()
    }

    /**
     * What a call, a capture or a `@spec` head resolves to before `multiResolve` gives one result per element; [bound]
     * is whether a valid variable binds its name. [use] is what the projection was read for, `null` where the call
     * names nothing, and [absence] why the projection's holding nothing valid may not prove the use reaches nothing.
     */
    class Answer(
        val variables: List<ResolveResult>,
        val bound: Boolean,
        val projection: List<Found>,
        val use: DeclarationUse?,
        val absence: Uncertain?,
    ) {
        /** The variables, then one result per declaring element. */
        @RequiresReadLock
        fun results(): List<ResolveResult> = variables + Resolved.results(projection)
    }

    /** What [call] resolves to, its declared part read from [feature]'s source. */
    @RequiresReadLock
    fun answer(call: Call, incompleteCode: Boolean, feature: Feature = Feature.SYMBOL_REFERENCES): Answer {
        val variables = LegacyWalkSource.variables(call, incompleteCode)
        val bound = bound(variables)

        return answer(DeclarationUse.of(call), incompleteCode, feature, variables, bound)
    }

    /** What [use] resolves to, its declared part read from [feature]'s source, beside [variables], which [bound] says bind its name. */
    @RequiresReadLock
    internal fun answer(
        use: DeclarationUse?,
        incompleteCode: Boolean,
        feature: Feature,
        variables: List<ResolveResult> = emptyList(),
        bound: Boolean = false,
    ): Answer {
        val step = use?.let { Resolved.answer(it, incompleteCode, bound, feature) }

        return Answer(variables, bound, Resolved.projection(step?.preferred.orEmpty(), feature), use, step?.absence)
    }

    /** Whether a valid variable binds [call]'s name, which then names the variable and no function. */
    @RequiresReadLock
    fun bound(call: Call, incompleteCode: Boolean): Boolean = bound(LegacyWalkSource.variables(call, incompleteCode))

    /** A stand-in binds nothing, so a bare name after `x = f` still calls `f`. */
    private fun bound(variables: List<VisitedElementSetResolveResult>): Boolean =
        variables.any { it.isValidResult && !it.isStandIn }
}
