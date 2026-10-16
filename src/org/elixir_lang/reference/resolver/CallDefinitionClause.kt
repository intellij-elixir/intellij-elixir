package org.elixir_lang.reference.resolver

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.declaration.Use

object CallDefinitionClause : ResolveCache.PolyVariantResolver<org.elixir_lang.reference.CallDefinitionClause> {
    override fun resolve(callDefinitionClause: org.elixir_lang.reference.CallDefinitionClause,
                         incompleteCode: Boolean): Array<ResolveResult> {
        ApplicationManager.getApplication().assertReadAccessAllowed()

        return Use.specification(callDefinitionClause.element, callDefinitionClause.moduleAttribute)
            ?.let { Resolved.results(Resolved.projection(it, incompleteCode)).toTypedArray() }
            ?: ResolveResult.EMPTY_ARRAY
    }
}
