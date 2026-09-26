package org.elixir_lang.psi.impl

import com.intellij.psi.*
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager.getCachedValue
import org.elixir_lang.model.psi.atom.AtomReference
import org.elixir_lang.model.psi.atom.GeneralAtomReference
import org.elixir_lang.model.psi.atom.mfaReferenceContext
import org.elixir_lang.psi.*


private fun ElixirAtom.computeReference(): PsiReference =
    mfaReferenceContext()?.let { context ->
        AtomReference(this, context.moduleElement, nameRangeInAtom(), context.arity)
    } ?: GeneralAtomReference(this)

fun getReference(atom: ElixirAtom): PsiReference? =
        getCachedValue(atom) { CachedValueProvider.Result.create(atom.computeReference(), atom) }

fun ElixirAtom.maybeModularNameToModulars(incompleteCode: Boolean): Set<PsiNamedElement> =
    reference?.maybeModularNameToModulars(incompleteCode) ?: emptySet()

fun PsiReference.maybeModularNameToModulars(incompleteCode: Boolean): Set<PsiNamedElement> {
    val resolveResults = when (this) {
        is PsiPolyVariantReference -> multiResolve(incompleteCode)
        else -> resolve()
                ?.let { arrayOf(PsiElementResolveResult(it, true)) }
                ?: emptyArray()
    }

    return resolveResults.mapNotNull { it.element }.filterIsInstance<PsiNamedElement>().toSet()
}
