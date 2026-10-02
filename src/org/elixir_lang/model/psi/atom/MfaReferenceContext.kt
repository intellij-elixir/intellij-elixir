package org.elixir_lang.model.psi.atom

import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.ElixirAccessExpression
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.ElixirTuple
import org.elixir_lang.psi.ElixirUnmatchedExpression
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.WholeNumber
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.quotedAtomValue
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.impl.toBigInteger

internal data class MfaReferenceContext(
    val moduleElement: PsiElement,
    val arity: Int
)

@RequiresReadLock
internal fun ElixirAtom.mfaReferenceContext(): MfaReferenceContext? {
    return tupleContext() ?: applyContext()
}

@RequiresReadLock
private fun ElixirAtom.tupleContext(): MfaReferenceContext? {
    val accessExpression = parent as? ElixirAccessExpression ?: return null

    return when (val grandparent = accessExpression.parent) {
        is ElixirTuple -> tupleContext(grandparent, accessExpression)
        is ElixirUnmatchedExpression -> {
            val tuple = grandparent.parent as? ElixirTuple ?: return null
            tupleContext(tuple, grandparent)
        }
        else -> null
    }
}

@RequiresReadLock
private fun tupleContext(tuple: ElixirTuple, atomWrapper: PsiElement): MfaReferenceContext? {
    val tupleChildren = tuple.children.filter { it is ElixirAccessExpression || it is ElixirUnmatchedExpression }

    if (tupleChildren.size != 3 || tupleChildren[1] !== atomWrapper) return null

    val moduleElement = moduleElement(tupleChildren[0].stripAccessExpression()) ?: return null

    return MfaReferenceContext(moduleElement, extractArity(tupleChildren[2].stripAccessExpression()))
}

@RequiresReadLock
private fun ElixirAtom.applyContext(): MfaReferenceContext? {
    val applyCall = generateSequence(parent) { it.parent }
        .filterIsInstance<Call>()
        .firstOrNull { it.isCalling(KERNEL, Function.APPLY) }
        ?: return null
    val finalArguments = applyCall.finalArguments() ?: return null
    if (finalArguments.size != 3) return null
    if (finalArguments[1].stripAccessExpression() !== this) return null

    val moduleElement = moduleElement(finalArguments[0].stripAccessExpression()) ?: return null

    return MfaReferenceContext(moduleElement, extractArity(finalArguments[2].stripAccessExpression()))
}

/** An atom with no value would resolve as a pattern over module names. */
@RequiresReadLock
private fun moduleElement(element: PsiElement): PsiElement? =
    when (element) {
        is QualifiableAlias -> element
        is ElixirAtom -> element.takeIf { quotedAtomValue(it) != null }
        else -> null
    }

private fun extractArity(element: PsiElement): Int = when (element) {
    is WholeNumber -> element.toBigInteger()?.toInt() ?: -1
    is ElixirList -> element.children.size
    else -> -1
}
