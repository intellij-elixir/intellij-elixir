package org.elixir_lang.model.psi.config

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.model.psi.atom.contentTextRange
import org.elixir_lang.model.psi.module.segmentRange
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.call.Call

/** The setting or read, if any, whose key is written at an element, and the part of that element naming the key. */
internal object Keys {
    @RequiresReadLock
    fun setAt(element: PsiElement): Set? = call(element)?.let(Set::of)?.firstOrNull { it.element == element }

    @RequiresReadLock
    fun readAt(element: PsiElement): Read? = call(element)?.let(Read::of)?.takeIf { it.element == element }

    /** The range in [element], a key as written, that names the key: an atom's name, or an alias's last segment. */
    fun range(element: PsiElement): TextRange =
        when (element) {
            is ElixirAtom -> contentTextRange(element)
            is QualifiableAlias -> segmentRange(element).shiftLeft(element.textRange.startOffset)
            else -> TextRange(0, element.textLength)
        }

    private fun call(element: PsiElement): Call? = generateSequence(element.parent) { it.parent }.filterIsInstance<Call>().firstOrNull()
}
