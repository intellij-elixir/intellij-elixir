package org.elixir_lang.reference.resolver

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Found
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.declaration.Use as DeclarationUse
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.call_definition_clause.LegacyWalkSource

object Callable : ResolveCache.PolyVariantResolver<org.elixir_lang.reference.Callable> {
    override fun resolve(callable: org.elixir_lang.reference.Callable, incompleteCode: Boolean): Array<ResolveResult> {
        ApplicationManager.getApplication().assertReadAccessAllowed()

        return answer(callable.element, incompleteCode).results().toTypedArray()
    }

    /**
     * What a call, a capture or a `@spec` head resolves to before `multiResolve` gives one result per element; [bound]
     * is whether a valid variable binds its name. [use] is what the projection was read for, `null` where the call
     * names nothing.
     */
    class Answer(
        val variables: List<ResolveResult>,
        val bound: Boolean,
        val projection: List<Found>,
        val use: DeclarationUse?
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
        val use = DeclarationUse.of(call)
        val projection = use?.let { Resolved.projection(it, incompleteCode, bound, feature) }.orEmpty()

        return Answer(variables, bound, projection, use)
    }

    /** Whether a valid variable binds [call]'s name, which then names the variable and no function. */
    @RequiresReadLock
    fun bound(call: Call, incompleteCode: Boolean): Boolean = bound(LegacyWalkSource.variables(call, incompleteCode))

    private fun bound(variables: List<ResolveResult>): Boolean = variables.any { it.isValidResult }
}
