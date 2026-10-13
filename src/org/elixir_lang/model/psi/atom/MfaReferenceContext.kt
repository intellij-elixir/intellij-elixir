package org.elixir_lang.model.psi.atom

import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager.getCachedValue
import com.intellij.psi.util.PsiModificationTracker
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
import org.elixir_lang.psi.impl.maybeModularNameToModulars
import org.elixir_lang.psi.impl.quotedAtomValue
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses
import org.elixir_lang.psi.impl.toBigInteger

internal data class MfaReferenceContext(
    val moduleElement: PsiElement,
    val arity: Int
)

/**
 * Whether an atom is a function name in a module, function and arguments position.
 *
 * [lookedUpModule] is set when the answer depends on another file, because a module lookup decided it.
 */
internal data class MfaReferenceDecision(val context: MfaReferenceContext?, val lookedUpModule: Boolean = false) {
    /** A module lookup reads other files, so what it decided is good only until the next PSI or root change. */
    fun <T> cachedResult(value: T, atom: ElixirAtom): CachedValueProvider.Result<T> =
        if (lookedUpModule) {
            CachedValueProvider.Result.create(value, atom, PsiModificationTracker.MODIFICATION_COUNT)
        } else {
            CachedValueProvider.Result.create(value, atom)
        }
}

@RequiresReadLock
internal fun ElixirAtom.mfaReferenceDecision(): MfaReferenceDecision =
    getCachedValue(this) {
        val decision = computeMfaReferenceDecision()
        decision.cachedResult(decision, this)
    }

@RequiresReadLock
private fun ElixirAtom.computeMfaReferenceDecision(): MfaReferenceDecision {
    val tuple = tupleContext()
    if (tuple.lookedUpModule) return tuple

    return MfaReferenceDecision(applyContext())
}

@RequiresReadLock
private fun ElixirAtom.tupleContext(): MfaReferenceDecision {
    val accessExpression = parent as? ElixirAccessExpression ?: return NOT_MFA

    return when (val grandparent = accessExpression.parent) {
        is ElixirTuple -> tupleContext(grandparent, accessExpression)
        is ElixirUnmatchedExpression -> {
            val tuple = grandparent.parent as? ElixirTuple ?: return NOT_MFA
            tupleContext(tuple, grandparent)
        }
        else -> NOT_MFA
    }
}

private val NOT_MFA = MfaReferenceDecision(null)

/** A tag such as `:error` names no module, so a tuple starting with one is data, not an MFA. */
@RequiresReadLock
private fun tupleContext(tuple: ElixirTuple, atomWrapper: PsiElement): MfaReferenceDecision {
    val tupleChildren = tuple.children.filter { it is ElixirAccessExpression || it is ElixirUnmatchedExpression }

    if (tupleChildren.size != 3 || tupleChildren[1] !== atomWrapper) return NOT_MFA

    val moduleElement = moduleElement(tupleChildren[0].stripAccessExpression()) ?: return NOT_MFA

    val namesModule = moduleElement
        .maybeModularNameToModulars(maxScope = tuple.containingFile, useCall = null, incompleteCode = false)
        .isNotEmpty()

    return MfaReferenceDecision(
        context = if (namesModule) {
            MfaReferenceContext(moduleElement, extractArity(tupleChildren[2].stripAccessExpression()))
        } else {
            null
        },
        lookedUpModule = true
    )
}

@RequiresReadLock
private fun ElixirAtom.applyContext(): MfaReferenceContext? {
    val applyCall = generateSequence(parent) { it.parent }
        .filterIsInstance<Call>()
        .firstOrNull { it.isCalling(KERNEL, Function.APPLY) }
        ?: return null
    val finalArguments = applyCall.finalArguments() ?: return null
    if (finalArguments.size != 3) return null
    if (finalArguments[1].stripAccessExpressionAndParentheses() !== this) return null

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
