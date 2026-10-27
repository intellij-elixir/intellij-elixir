package org.elixir_lang.model.psi.atom

import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.code_insight.completion.callDefinitionClauseLookupElements
import org.elixir_lang.code_insight.completion.insert_handler.QualifiedName
import org.elixir_lang.declaration.Resolved
import org.elixir_lang.declaration.Use
import org.elixir_lang.model.psi.function.UseSymbols
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.impl.maybeModularNameToModulars
import org.elixir_lang.psi.impl.quotedAtomValue

@Suppress("UnstableApiUsage")
class AtomReference(
    atom: ElixirAtom,
    /**
     * The module element from the MFA tuple or apply/3 - either:
     * - a [org.elixir_lang.psi.QualifiableAlias] for Elixir-style modules (`Enum`, `MyApp.Worker`)
     * - an [ElixirAtom] (`:lists`, `:"Elixir.Enum"`)
     */
    private val moduleElement: PsiElement,
    private val rangeInElement: TextRange = contentTextRange(atom)
) : PsiReferenceBase<ElixirAtom>(atom, contentTextRange(atom)), PsiPolyVariantReference, PsiSymbolReference {
    private val functionName: String?
        get() = quotedAtomValue(myElement)

    override fun getVariants(): Array<Any> {
        if (functionName == null) return emptyArray()

        val modulars = moduleElement.maybeModularNameToModulars(
            maxScope = myElement.containingFile,
            useCall = null,
            incompleteCode = true
        )

        return callDefinitionClauseLookupElements(modulars, QualifiedName.forAtom(myElement)).toTypedArray()
    }

    override fun getAbsoluteRange(): TextRange =
        rangeInElement.shiftRight(myElement.textRange.startOffset)

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        ApplicationManager.getApplication().assertReadAccessAllowed()

        return ResolveCache
            .getInstance(myElement.project)
            .resolveWithCaching(this, Resolver, false, incompleteCode)
    }

    override fun resolve(): PsiElement? = Resolved.target(this)

    override fun isSoft(): Boolean = true

    @RequiresReadLock
    override fun resolveReference(): Collection<Symbol> = UseSymbols.of(Use.mfa(myElement))

    private object Resolver : ResolveCache.PolyVariantResolver<AtomReference> {
        override fun resolve(reference: AtomReference, incompleteCode: Boolean): Array<ResolveResult> =
            Use.mfa(reference.myElement)
                ?.let { Resolved.results(Resolved.projection(it, incompleteCode)).toTypedArray() }
                ?: ResolveResult.EMPTY_ARRAY
    }
}

internal fun contentTextRange(atom: ElixirAtom): TextRange {
    val atomNode = atom.node
    val nameRange = atom.line?.body?.textRange
        ?: atomNode.lastChildNode?.textRange
        ?: return TextRange(0, atom.textLength)

    return nameRange.shiftLeft(atomNode.startOffset)
}
