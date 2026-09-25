package org.elixir_lang.reference

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import org.elixir_lang.psi.ElementFactory
import org.elixir_lang.psi.ElixirAlias
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.QualifiedAlias
import org.elixir_lang.psi.scope.module.Variants

class Module(qualifiableAlias: QualifiableAlias) :
        PsiReferenceBase<QualifiableAlias>(qualifiableAlias, textRange(qualifiableAlias)),
        PsiPolyVariantReference {
    @Throws(IncorrectOperationException::class)
    override fun handleElementRename(newElementName: String): PsiElement {
        val replacementAlias = PsiTreeUtil.findChildOfType(
            ElementFactory.createFile(myElement.project, newElementName),
            QualifiableAlias::class.java
        ) ?: throw IncorrectOperationException("Unable to parse module alias name: $newElementName")

        return myElement.replace(replacementAlias)
    }

    override fun getVariants(): Array<LookupElement> = Variants.lookupElements(myElement).toTypedArray()

    override fun isReferenceTo(element: PsiElement): Boolean {
        val manager = this.element.manager

        return multiResolve(false).any {
            it.isValidResult && manager.areElementsEquivalent(it.element, element)
        }
    }

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        return ResolveCache
            .getInstance(this.myElement.project)
            .resolveWithCaching(
                this,
                org.elixir_lang.reference.resolver.Module,
                false,
                incompleteCode
            )
    }

    override fun resolve(): PsiElement? = Resolver.resolved(myElement, multiResolve(false).toList())
}

private fun textRange(qualifiableAlias: QualifiableAlias): TextRange = when (qualifiableAlias) {
    is ElixirAlias -> TextRange.create(0, qualifiableAlias.textLength)
    is QualifiedAlias -> qualifiableAlias.getAlias().textRangeInParent
    else -> TODO()
}
