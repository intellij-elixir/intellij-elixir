package org.elixir_lang.lowering

import com.intellij.lang.ASTNode
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.tree.TokenSet
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.language_level.ElixirLanguageFeature.CLUSTER_COLUMNS_IN_QUOTED_TEXT
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_INTERPOLATION_COLUMNS
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_NEWLINE_COUNTED_IN_LITERAL_SIGIL_LINE
import org.elixir_lang.language_level.ElixirLanguageFeature.HEREDOC_INDENTATION_IN_COLUMNS
import org.elixir_lang.language_level.ElixirLanguageFeature.NEWLINE_COUNTED_IN_CHARACTER
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.Body
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.psi.HeredocLiteral
import org.elixir_lang.psi.Interpolated
import org.elixir_lang.psi.SigilLine
import org.elixir_lang.unicode_util.Graphemes

/** Where one Elixir release's tokenizer counts lines and columns differently from the text's own lines and code points. */
internal object Tokenization {
    private val TERMINATORS = TokenSet.create(ElixirTypes.LINE_TERMINATOR, ElixirTypes.HEREDOC_TERMINATOR)

    @RequiresReadLock
    fun lines(file: ElixirFile, text: CharSequence, languageLevel: ElixirLanguageLevel): Lines {
        ThreadingAssertions.assertReadAccess()
        LoweringCounters.count(LoweringCounters.tokenizations)

        val scan = scan(file.node, text, languageLevel)

        return Lines(text, Graphemes.of(languageLevel), scan.uncountedNewlines, scan.quotedTexts, scan.zeroWidthRanges)
    }

    /** What [scan] finds under a node. */
    class Scan(val uncountedNewlines: List<Int>, val quotedTexts: List<Lines.QuotedText>, val zeroWidthRanges: List<TextRange>)

    /**
     * The newlines [languageLevel]'s tokenizer leaves uncounted under [node] when [newlines], and its quoted text and
     * zero-width ranges when [columns].
     */
    @RequiresReadLock
    fun scan(
        node: ASTNode,
        text: CharSequence,
        languageLevel: ElixirLanguageLevel,
        newlines: Boolean = true,
        columns: Boolean = true,
    ): Scan {
        ThreadingAssertions.assertReadAccess()

        val newlineCountedInCharacter = NEWLINE_COUNTED_IN_CHARACTER.isSufficient(languageLevel)
        val newlineCountedInLiteralSigilLine = ESCAPED_NEWLINE_COUNTED_IN_LITERAL_SIGIL_LINE.isSufficient(languageLevel)
        val clusters = CLUSTER_COLUMNS_IN_QUOTED_TEXT.isSufficient(languageLevel)
        val escapedInterpolationColumns = ESCAPED_INTERPOLATION_COLUMNS.isSufficient(languageLevel)
        val heredocIndentationInColumns = HEREDOC_INDENTATION_IN_COLUMNS.isSufficient(languageLevel)

        val uncountedNewlines = mutableListOf<Int>()
        val quotedTexts = mutableListOf<Lines.QuotedText>()
        val zeroWidthRanges = mutableListOf<TextRange>()

        fun visit(node: ASTNode) {
            ProgressManager.checkCanceled()

            if (newlines) when (node.elementType) {
                ElixirTypes.ESCAPED_EOL -> {
                    val parent = node.treeParent
                    val uncounted =
                        if (parent?.elementType == ElixirTypes.CHAR_TOKEN) {
                            !newlineCountedInCharacter
                        } else {
                            !newlineCountedInLiteralSigilLine &&
                                parent?.treeParent?.psi.let { it is SigilLine && it !is Interpolated }
                        }

                    if (uncounted) uncountedNewlines.add(node.startOffset + node.text.indexOf('\n'))
                }
                ElixirTypes.CHAR_TOKEN ->
                    if (!newlineCountedInCharacter && node.lastChildNode.elementType != ElixirTypes.ESCAPED_EOL) {
                        node.text.indexOf('\n').takeIf { it >= 0 }?.let { uncountedNewlines.add(node.startOffset + it) }
                    }
            }

            if (columns) when (node.elementType) {
                ElixirTypes.ESCAPED_CHARACTER ->
                    if (!escapedInterpolationColumns && node.text == "\\#" &&
                        node.treeNext?.text?.startsWith("{") == true
                    ) {
                        zeroWidthRanges.add(TextRange(node.startOffset + 1, node.startOffset + 3))
                    }
            }

            val psi = if (columns) node.psi else null

            if (columns && clusters && psi is Body) {
                val quote = generateSequence(node.treeParent) { it.treeParent }
                    .firstOrNull { it.findChildByType(TERMINATORS) != null }
                val terminator = quote?.findChildByType(TERMINATORS)?.text
                val interpolates = quote?.psi is Interpolated
                var start = node.startOffset

                fun add(end: Int) {
                    if (end > start) quotedTexts.add(Lines.QuotedText(TextRange(start, end), terminator, interpolates))
                }

                for (child in node.getChildren(null)) {
                    ProgressManager.checkCanceled()

                    if (child.elementType == ElixirTypes.INTERPOLATION) {
                        add(child.startOffset)
                        start = child.textRange.endOffset
                    }
                }

                add(node.textRange.endOffset)
            }

            // 1.11 strips the indentation from every line of the body, those inside an interpolation too, before
            // tokenizing it.
            if (columns && !heredocIndentationInColumns && psi is HeredocLiteral) {
                val indentation = psi.heredocPrefix.textLength
                val bodyEnd = psi.heredocPrefix.textRange.startOffset
                var lineStart = psi.heredocLineList.firstOrNull()?.textRange?.startOffset ?: bodyEnd

                while (indentation > 0 && lineStart < bodyEnd) {
                    ProgressManager.checkCanceled()

                    val indentationEnd = lineStart + indentation
                    val blankEnd = (lineStart until indentationEnd).firstOrNull { text[it] != ' ' && text[it] != '\t' }
                        ?: indentationEnd
                    if (blankEnd > lineStart) zeroWidthRanges.add(TextRange(lineStart, blankEnd))

                    lineStart = text.indexOf('\n', lineStart).let { if (it in 0 until bodyEnd) it + 1 else bodyEnd }
                }
            }

            var child = node.firstChildNode

            while (child != null) {
                ProgressManager.checkCanceled()
                visit(child)
                child = child.treeNext
            }
        }

        visit(node)

        return Scan(uncountedNewlines, quotedTexts, zeroWidthRanges)
    }
}
