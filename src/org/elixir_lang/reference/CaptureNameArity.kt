package org.elixir_lang.reference

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
import com.intellij.psi.ResolveState
import com.intellij.psi.impl.source.resolve.ResolveCache
import org.elixir_lang.code_insight.completion.callDefinitionClauseLookupElements
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.maybeModularNameToModulars
import org.elixir_lang.psi.operation.Prefix
import org.elixir_lang.psi.operation.capture.NonNumeric
import org.elixir_lang.psi.qualification.Qualified
import org.elixir_lang.psi.qualification.Unqualified
import org.elixir_lang.psi.scope.call_definition_clause.Variants
import org.elixir_lang.reference.resolver.CaptureNameArity as CaptureNameArityResolver

typealias Arity = Int

class CaptureNameArity(element: NonNumeric, val nameElement: Call, val arity: Arity) :
        PsiReferenceBase<Prefix>(element), PsiPolyVariantReference {
    init {
        // Anchor the reference on the captured name only (not the whole `&name/arity`, and for a
        // qualified capture only the relative identifier, not `Mod.name`), so completion replaces just
        // the name and rename targets the name.
        val nameRange = when (nameElement) {
            is Qualified -> (nameElement as Qualified).relativeIdentifier.textRange
            else -> nameElement.textRange
        }
        rangeInElement = nameRange.shiftLeft(element.textRange.startOffset)
    }

    /**
     * The callables that could complete the captured name at [nameElement]. A capture names a
     * *function* of a specific [arity], written bare (the `/arity` follows), so names are offered
     * without parentheses and only names with a clause of the requested arity are kept:
     * - unqualified `&name/arity` - every function clause in scope (public and private, whole
     *   enclosing module, including forward-declared ones), the same scope a plain unqualified call
     *   sees;
     * - qualified `&Mod.name/arity` - the public functions of the module(s) `Mod` resolves to, the
     *   same set remote `Mod.<caret>` completion offers.
     */
    override fun getVariants(): Array<Any> =
        when (nameElement) {
            is Unqualified ->
                Variants.lookupElementList(nameElement, appendParentheses = false).ofRequestedArity().toTypedArray()

            is Qualified ->
                (nameElement as Qualified).qualifier()
                    .maybeModularNameToModulars(
                        maxScope = nameElement.containingFile,
                        useCall = null,
                        incompleteCode = true
                    )
                    .let { modulars ->
                        callDefinitionClauseLookupElements(modulars, appendParentheses = false).ofRequestedArity()
                            .toTypedArray()
                    }

            else -> emptyArray()
        }

    /**
     * Keeps only the completion candidates that define the name at the requested [arity] (a capture must name an
     * existing `name/arity`); candidates whose definitions cannot be read are kept, matching the resolver's lenient
     * behaviour.
     */
    private fun Iterable<LookupElement>.ofRequestedArity(): List<LookupElement> = filter { lookupElement ->
        val state = ResolveState.initial()
        val definitions = lookupElement.psiElement
            ?.let { CallableDeclaration.declaredOf(it, state) }
            ?.definitions(state)
            ?.filter { it.name == lookupElement.lookupString }

        definitions.isNullOrEmpty() || definitions.any { it.accepts(arity) }
    }

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
       resolveWithCaching(myElement.project, this, incompleteCode)

    override fun resolve(): PsiElement? = Resolver.resolved(myElement, multiResolve(false).toList())

    private fun resolveWithCaching(
            project: Project,
            captureNameArity: CaptureNameArity,
            incompleteCode: Boolean
    ): Array<ResolveResult> {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        return ResolveCache
            .getInstance(project)
            .resolveWithCaching(captureNameArity, CaptureNameArityResolver, false, incompleteCode)
    }
}
