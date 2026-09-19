package org.elixir_lang.annotator

import com.intellij.lang.ASTNode
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import org.elixir_lang.psi.ElixirTypes

/** Whether something other than spaces or tabs follows the heredoc's opening on its line. */
internal fun hasContentAfterOpening(heredoc: PsiElement): Boolean {
    val promoter = heredoc.node.findChildByType(ElixirTypes.HEREDOC_PROMOTER) ?: return false
    val next = generateSequence(promoter.treeNext) { it.treeNext }
        .firstOrNull { it.elementType != TokenType.WHITE_SPACE }

    return next?.elementType != ElixirTypes.EOL
}

/**
 * Where the terminator line's terminator starts, or the first misplaced terminator; neither when the file ends first.
 */
internal class HeredocScan(val terminatorAt: Int?, val misplaced: TextRange?)

/**
 * Elixir's reading before 1.12: line by line after the opening, a backslash taking a backslash or quote after it, until
 * a line starting with the terminator; a terminator anywhere else on a line is misplaced.
 */
internal fun scanHeredocLines(promoter: ASTNode): HeredocScan {
    val terminator = promoter.text
    val text = promoter.psi.containingFile.viewProvider.contents
    var index = StringUtil.indexOf(text, '\n', promoter.textRange.endOffset)

    while (index >= 0 && index < text.length) {
        ProgressManager.checkCanceled()
        index++

        while (index < text.length && (text[index] == ' ' || text[index] == '\t')) index++
        if (StringUtil.startsWith(text, index, terminator)) return HeredocScan(index, null)

        while (index < text.length && text[index] != '\n') {
            when {
                text[index] == '\\' &&
                    index + 1 < text.length &&
                    (text[index + 1] == '\\' || text[index + 1] == terminator[0]) ->
                    index += 2
                StringUtil.startsWith(text, index, terminator) ->
                    return HeredocScan(null, TextRange.from(index, terminator.length))
                else -> index++
            }
        }
    }

    return HeredocScan(null, null)
}

/** The first terminator after content in the heredoc, as Elixir reads it before 1.12. */
internal fun misplacedHeredocTerminator(heredoc: PsiElement): TextRange? =
    heredoc.node.findChildByType(ElixirTypes.HEREDOC_PROMOTER)
        ?.let { scanHeredocLines(it).misplaced }
        ?.takeIf { heredoc.textRange.contains(it) }
