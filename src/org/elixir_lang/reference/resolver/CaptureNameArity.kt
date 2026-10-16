package org.elixir_lang.reference.resolver

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.declaration.Use
import org.elixir_lang.reference.CaptureNameArity

object CaptureNameArity : ResolveCache.PolyVariantResolver<CaptureNameArity> {
    override fun resolve(reference: CaptureNameArity, incompleteCode: Boolean): Array<ResolveResult> {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        return Use.capture(reference.nameElement, reference.arity)
            ?.let { Resolved.results(Resolved.projection(it, incompleteCode)).toTypedArray() }
            ?: ResolveResult.EMPTY_ARRAY
    }
}
