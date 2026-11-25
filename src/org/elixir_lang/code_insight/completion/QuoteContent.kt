package org.elixir_lang.code_insight.completion

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirInterpolation
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.psi.impl.isInterpolatedWithin

/**
 * The stretch of [quote]'s content holding [offset]: from its opening delimiter or an interpolation to the next
 * interpolation or its closing delimiter. `null` where [offset] is in none: in a delimiter or an interpolation's code.
 */
internal fun contentAt(quote: PsiElement, offset: Int): TextRange? {
    val start = quote.node.findChildByType(PROMOTERS)?.textRange?.endOffset ?: return null
    val end = quote.node.findChildByType(TERMINATORS)?.startOffset ?: quote.textRange.endOffset
    val interpolations = SyntaxTraverser.psiTraverser(quote).expand { it !is ElixirInterpolation }
        .filter(ElixirInterpolation::class.java)
    var from = start

    for (interpolation in interpolations) {
        val range = interpolation.textRange
        // Every later stretch starts past [offset].
        if (offset <= range.startOffset) return TextRange(from, range.startOffset).takeIf { offset >= from }
        from = range.endOffset
    }

    return TextRange(from, end).takeIf { offset in from..end }
}

/** The atom [leaf] is in, outside any interpolation's code. */
internal fun atomAt(leaf: PsiElement?): ElixirAtom? =
    PsiTreeUtil.getParentOfType(leaf, ElixirAtom::class.java, false)
        ?.takeUnless { leaf?.isInterpolatedWithin(it) == true }

/**
 * The stretch of [atom]'s name holding [offset], which a name chosen there replaces: an unquoted atom's name after its
 * colon, or a quoted atom's content from its opening quote or an interpolation to the next interpolation or its closing
 * quote. `null` where [offset] is in none: before the opening quote, or in an interpolation's code.
 */
internal fun atomNameAt(atom: ElixirAtom, offset: Int): TextRange? {
    val line = atom.line

    return if (line == null) {
        atom.node.findChildByType(ElixirTypes.ATOM_FRAGMENT)?.textRange?.takeIf { offset in it.startOffset..it.endOffset }
    } else {
        contentAt(line, offset)
    }
}

private val PROMOTERS = TokenSet.create(ElixirTypes.LINE_PROMOTER, ElixirTypes.HEREDOC_PROMOTER)
private val TERMINATORS = TokenSet.create(ElixirTypes.LINE_TERMINATOR, ElixirTypes.HEREDOC_TERMINATOR)
