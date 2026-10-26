package org.elixir_lang.model.psi.config

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.atom.contentTextRange
import org.elixir_lang.model.psi.module.segmentRange
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.CallImpl
import org.elixir_lang.psi.operation.Infix

/** The setting or read, if any, whose key is written at an element, and the part of that element naming the key. */
internal object Keys {
    @RequiresReadLock
    fun setAt(element: PsiElement): Set? = call(element)?.let(Set::of)?.firstOrNull { it.element == element }

    @RequiresReadLock
    fun readAt(element: PsiElement): Read? = call(element)?.let(Read::of)?.takeIf { it.element == element }

    /** What [element], an argument of a `config` call or a read that may still be being written, names. */
    @RequiresReadLock
    fun slotAt(element: PsiElement): Slot? = Set.slotAt(element) ?: Read.slotAt(element)

    /** The range in [element], a key as written, that names the key: an atom's name, or an alias's last segment. */
    fun range(element: PsiElement): TextRange =
        when (element) {
            is ElixirAtom -> contentTextRange(element)
            is QualifiableAlias -> segmentRange(element).shiftLeft(element.textRange.startOffset)
            else -> TextRange(0, element.textLength)
        }

    /** The call whose argument [element] is: its nearest enclosing call, or the call a pipe's left side is piped into. */
    fun call(element: PsiElement): Call? {
        val call = generateSequence(element.parent) { it.parent }.filterIsInstance<Call>().firstOrNull()
        val pipe = call as? Infix ?: return call
        val piped = (pipe.rightOperand() as? Call)?.takeIf { CallImpl.isPipedInto(it) }

        return piped?.takeIf { pipe.leftOperand()?.let { left -> PsiTreeUtil.isAncestor(left, element, false) } == true } ?: call
    }
}

/** What an argument of a `config` call or a read names. */
sealed interface Slot {
    /** The app. */
    data object App : Slot

    /**
     * A first-level key of the literal [app]. [options]: it is written in `config/2`'s options, as a keyword key; [written]:
     * the other keys those options set.
     */
    data class Key(val app: String, val options: Boolean = false, val written: Collection<String> = emptyList()) : Slot
}
