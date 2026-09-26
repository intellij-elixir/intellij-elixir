package org.elixir_lang.psi.impl

import com.intellij.openapi.util.TextRange
import com.ericsson.otp.erlang.*
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirLine
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.javaString
import org.elixir_lang.psi.impl.QuotableImpl.childNodes
import org.elixir_lang.psi.impl.QuotableImpl.metadata
import org.elixir_lang.psi.impl.QuotableImpl.quotedChildNodes
import org.elixir_lang.psi.impl.QuotableImpl.quotedFunctionCall
import org.jetbrains.annotations.Contract

private val UTF_8 = OtpErlangAtom("utf8")

/** Where the atom's name sits within it, after the `:` - what a reference to the name covers, and a rename rewrites. */
@RequiresReadLock
fun ElixirAtom.nameRangeInAtom(): TextRange {
    val atomNode = node
    val lastChildNode = atomNode.lastChildNode ?: return TextRange(0, textLength)
    val start = lastChildNode.startOffset - atomNode.startOffset

    return TextRange(start, start + lastChildNode.textLength)
}

/**
 * The document range of the name [nameElement] spells: inside the `:` for an atom, the element itself otherwise. The
 * one way a Symbol's range is made from its name.
 */
@RequiresReadLock
fun nameTextRange(nameElement: PsiElement): TextRange =
    (nameElement as? ElixirAtom)?.let { atom -> atom.nameRangeInAtom().shiftRight(atom.textRange.startOffset) }
        ?: nameElement.textRange

/**
 * The atom's name as Elixir reads it - `:"b"` is `:b` - or `null` when interpolation leaves it unknown until runtime,
 * or when it is longer than Elixir allows an atom to be.
 */
@RequiresReadLock
fun ElixirAtom.literalName(): String? =
    when (val line = line) {
        null -> node.lastChildNode.text
        else -> if (line.lineBody == null) "" else quotedName(line)
    }

private fun quotedName(line: ElixirLine): String? =
    try {
        (line.quoteLineAsAtom() as? OtpErlangAtom)?.atomValue()
    } catch (_: IllegalArgumentException) {
        // `OtpErlangAtom` refuses more than 255 characters, as the BEAM does.
        null
    }

@Contract(pure = true)
fun ElixirLine.quoteLineAsAtom(): OtpErlangObject {
    val quotedChildNodes = quotedChildNodes(AtomableParent(this), *childNodes(lineBody!!))

    return quotedToAtom(quotedChildNodes, this)
}

private fun quotedToAtom(quoted: OtpErlangObject, element: PsiElement): OtpErlangObject = when (quoted) {
    is OtpErlangBinary -> {
        val atomText = javaString(quoted)
        OtpErlangAtom(atomText)
    }
    is OtpErlangString -> {
        val atomText = quoted.stringValue()
        OtpErlangAtom(atomText)
    }
    else -> quotedFunctionCall(
            "erlang",
            "binary_to_atom",
            metadata(element),
            quoted,
            UTF_8
    )
}
