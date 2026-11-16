package org.elixir_lang.psi.__module__

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.QuoteMacro
import org.elixir_lang.psi.Unquote
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.body
import org.elixir_lang.psi.stub.type.call.Stub

/**
 * The innermost `quote` or modular whose body holds [element]. A `__MODULE__` there names that modular, or, in a
 * `quote`, its caller's module. A module's or `quote`'s own arguments are evaluated outside it. What a `quote`
 * unquotes runs where the `quote` is, past any module the `quote` writes around it.
 */
@RequiresReadLock
fun enclosingQuoteOrModular(element: PsiElement): Call? {
    val runs = Unquote.escaped(element) ?: element

    return generateSequence(runs.parent) { it.parent }
        .takeWhile { it !is PsiFile }
        .filterIsInstance<Call>()
        .firstOrNull { call -> (QuoteMacro.`is`(call) || Stub.isModular(call)) && isInBody(call, runs) }
}

@RequiresReadLock
fun isInBody(enclosing: Call, element: PsiElement): Boolean = PsiTreeUtil.isAncestor(enclosing.body(), element, false)

/** The modular a `__MODULE__` at [element] names: none in a `quote`'s body, whose module is its caller's. */
@RequiresReadLock
fun currentModularAt(element: PsiElement): Call? =
    enclosingQuoteOrModular(element)?.takeUnless { QuoteMacro.`is`(it) }
