package org.elixir_lang.model.psi.atom

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.scope.atom.Variants
import org.elixir_lang.reference.resolver.atom.Resolvable
import org.elixir_lang.reference.Resolver as ReferenceResolver

class GeneralAtomReference(atom: ElixirAtom) :
    PsiReferenceBase<ElixirAtom>(atom, TextRange.create(0, atom.textLength)),
    PsiPolyVariantReference {

    override fun getVariants(): Array<Any> {
        val lookupElements: List<LookupElement> = Variants.lookupElementList(myElement)
        return lookupElements.toTypedArray()
    }

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        ApplicationManager.getApplication().assertReadAccessAllowed()

        return ResolveCache
            .getInstance(myElement.project)
            .resolveWithCaching(this, Resolver, false, incompleteCode)
    }

    override fun resolve(): PsiElement? = ReferenceResolver.resolved(myElement, multiResolve(false).toList())

    private object Resolver : ResolveCache.PolyVariantResolver<GeneralAtomReference> {
        override fun resolve(reference: GeneralAtomReference, incompleteCode: Boolean): Array<ResolveResult> =
            Resolvable.resolvable(reference.myElement).resolve(reference.myElement)
    }
}
