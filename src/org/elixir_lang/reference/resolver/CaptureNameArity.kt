package org.elixir_lang.reference.resolver

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Use
import org.elixir_lang.reference.CaptureNameArity

object CaptureNameArity : ResolveCache.PolyVariantResolver<CaptureNameArity> {
    override fun resolve(reference: CaptureNameArity, incompleteCode: Boolean): Array<ResolveResult> {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        return answer(reference, incompleteCode).results().toTypedArray()
    }

    /** What [reference]'s name resolves to at the capture's arity, its declared part read from [feature]'s source. */
    @RequiresReadLock
    fun answer(
        reference: CaptureNameArity,
        incompleteCode: Boolean,
        feature: Feature = Feature.SYMBOL_REFERENCES
    ): Callable.Answer = Callable.answer(Use.capture(reference.nameElement, reference.arity), incompleteCode, feature)
}
