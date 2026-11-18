package org.elixir_lang.reference.resolver

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Use

object CallDefinitionClause : ResolveCache.PolyVariantResolver<org.elixir_lang.reference.CallDefinitionClause> {
    override fun resolve(callDefinitionClause: org.elixir_lang.reference.CallDefinitionClause,
                         incompleteCode: Boolean): Array<ResolveResult> {
        ApplicationManager.getApplication().assertReadAccessAllowed()

        return answer(callDefinitionClause, incompleteCode).results().toTypedArray()
    }

    /** What the `@spec` head [reference] specifies resolves to, its declared part read from [feature]'s source. */
    @RequiresReadLock
    fun answer(
        reference: org.elixir_lang.reference.CallDefinitionClause,
        incompleteCode: Boolean,
        feature: Feature = Feature.SYMBOL_REFERENCES
    ): Callable.Answer = Callable.answer(Use.specification(reference.element, reference.moduleAttribute), incompleteCode, feature)
}
