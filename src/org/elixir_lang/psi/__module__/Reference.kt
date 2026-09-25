package org.elixir_lang.psi.__module__

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import org.elixir_lang.psi.call.Call

class Reference(call: Call, val useCall: Call?) : PsiReferenceBase<Call>(call), PsiPolyVariantReference {
    override fun calculateDefaultRangeInElement(): TextRange = TextRange.create(0, element.textLength)

    /**
     * Returns the results of resolving the reference.
     *
     * @param incompleteCode if true, the code in the context of which the reference is
     * being resolved is considered incomplete, and the method may return additional
     * invalid results.
     * @return the array of results for resolving the reference.
     */
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
            ApplicationManager.getApplication().runReadAction(ResolveWithCachingComputable(myElement.project, this, incompleteCode))

    /**
     * Returns the element which is the target of the reference.
     *
     * @return the target element, or `null` if it was not possible to resolve the reference to a valid target.
     */
    override fun resolve(): PsiElement? = org.elixir_lang.reference.Resolver.resolved(myElement, multiResolve(false).toList())
}

private class ResolveWithCachingComputable(
        val project: Project,
        val reference: Reference,
        val incompleteCode: Boolean
) : Computable<Array<ResolveResult>> {
    override fun compute(): Array<ResolveResult> {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        return ResolveCache.getInstance(project).resolveWithCaching(reference, Resolver, false, incompleteCode)
    }
}
