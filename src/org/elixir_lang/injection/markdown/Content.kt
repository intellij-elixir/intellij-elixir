package org.elixir_lang.injection.markdown

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.elixir_lang.injection.partsOutsideInterpolations
import org.elixir_lang.psi.HeredocLiteral
import java.util.regex.Pattern

internal const val CODE_BLOCK_INDENT = "    "
internal const val CODE_BLOCK_INDENT_LENGTH = CODE_BLOCK_INDENT.length
internal const val IEX_PROMPT = "iex> "
internal const val IEX_PROMPT_LENGTH = IEX_PROMPT.length
internal const val IEX_CONTINUATION = "...> "
internal const val IEX_CONTINUATION_LENGTH = IEX_CONTINUATION.length
internal const val EXCEPTION_PREFIX = "** ("
internal const val DEBUG_PREFIX = "*DBG* "

internal val LIST_START_PATTERN: Pattern = Pattern.compile("(?<indent>\\s*)([-*+]|\\d+\\.) \\S+.*\n")
internal val INDENTED_PATTERN: Pattern = Pattern.compile("(?<indent>\\s*).*\n")

/** One `addPlace` of Markdown: [prefix] and [suffix] are text that is not in the host at all. */
internal class MarkdownPlace(val prefix: String, val rangeInHost: TextRange, val suffix: String)

/**
 * How the Markdown in a documentation heredoc is injected: the [places] to register, and the host ranges
 * they take their text from.
 *
 * No place may overlap the Elixir injected into a code block, because the platform discards one of two
 * injected documents whose host ranges overlap, and a Markdown place reaching across a code block would
 * cost the whole documentation its Markdown. That is why a code block's indent and `iex> ` prompt ride on
 * the neighbouring place as synthetic text instead of taking a place of their own, and why the runs between
 * code blocks are as long as they are - each place is a shred, and validating the injected document walks
 * every shred on nearly every access.
 */
internal class MarkdownInjection(val places: List<MarkdownPlace>, val contentRanges: List<TextRange>)

/**
 * The range, relative to [documentation], covering every heredoc line.
 */
internal fun markdownRangeInHost(documentation: HeredocLiteral): TextRange? =
    documentation.heredocLineList.takeIf { it.isNotEmpty() }?.let { heredocLineList ->
        val hostStartOffset = documentation.textRange.startOffset

        TextRange(
            heredocLineList.first().textRange.startOffset - hostStartOffset,
            heredocLineList.last().textRange.endOffset - hostStartOffset
        )
    }

internal enum class LineKind { LIST_START, LIST_CONTINUATION, PROSE, BLANK, CODE }

/**
 * One heredoc line's split between Markdown and Elixir, relative to the host documentation heredoc.
 *
 * [markdownLength] is how much of [lineMarkdownText], from its start, Markdown keeps; the rest, when
 * [kind] is [LineKind.CODE] and it is shorter than [lineMarkdownText], is the Elixir a code block owns.
 * Every other kind keeps the whole line, so [markdownLength] equals [lineMarkdownText]'s length.
 */
internal class ClassifiedLine(
    val kind: LineKind,
    val markdownOffsetRelativeToQuote: Int,
    val lineMarkdownText: String,
    val markdownLength: Int
) {
    /**
     * The Elixir a code block owns on this line, relative to the host documentation heredoc - `null`
     * when Markdown keeps the whole line. The one place this is computed, so the Markdown and Elixir
     * injections can never disagree about where a code line's split falls.
     */
    val elixirRangeRelativeToQuote: TextRange?
        get() = if (markdownLength < lineMarkdownText.length) {
            TextRange.from(markdownOffsetRelativeToQuote + markdownLength, lineMarkdownText.length - markdownLength)
        } else {
            null
        }
}

private val CLASSIFIED_LINES: Key<CachedValue<List<ClassifiedLine>>> = Key.create("ELIXIR_MARKDOWN_CLASSIFIED_LINES")

/**
 * Walks [documentation] once, classifying every heredoc line for both the Markdown and the Elixir
 * injection, so the two agree on where a code block starts and ends and where `** (`'s exception output
 * stops. `listIndent` and `inException` used to be tracked separately by each injection's own walk; kept
 * only here, they cannot drift out of step between them.
 *
 * A line contributes its text after the heredoc prefix, so that the heredoc's own indentation does not
 * read as an indented code block. A code block's lines contribute only the four-space indent that marks
 * them as code - plus the `iex> ` prompt, which is prose to Markdown - because their code is injected as
 * Elixir instead. Exception and `*DBG*` output is not code, so it is contributed whole.
 *
 * `listIndent` tracks list nesting by `!= -1`, not by sign, because a zero-indent list item's own indent
 * is `0` - a list can start at the left margin - and a `> 0` test would drop straight back out of it on
 * the very next line.
 *
 * Cached per [documentation]: `LiteralTextEscaper.decode` calls into this by way of [markdownInjection] on
 * nearly every access to the injected document - once per shred, per `DocumentWindowImpl.isValid()` call -
 * so an uncached walk costs O(shreds x lines) per access rather than O(lines) once.
 */
internal fun classifyLines(documentation: HeredocLiteral): List<ClassifiedLine> =
    CachedValuesManager.getCachedValue(documentation, CLASSIFIED_LINES) {
        CachedValueProvider.Result.create(computeClassifiedLines(documentation), documentation)
    }

private fun computeClassifiedLines(documentation: HeredocLiteral): List<ClassifiedLine> {
    val prefixLength = documentation.heredocPrefix.textLength
    val hostStartOffset = documentation.textRange.startOffset
    var listIndent = -1
    var inException = false

    val result = mutableListOf<ClassifiedLine>()

    for (line in documentation.heredocLineList) {
        val lineText = line.text

        // > to include newline
        if (lineText.length <= prefixLength) continue

        val lineMarkdownText = lineText.substring(prefixLength)
        val markdownOffsetRelativeToQuote = line.textRange.startOffset + prefixLength - hostStartOffset

        val listStartMatcher = LIST_START_PATTERN.matcher(lineMarkdownText)

        if (listStartMatcher.matches()) {
            listIndent = listStartMatcher.group("indent").length

            result.add(
                ClassifiedLine(LineKind.LIST_START, markdownOffsetRelativeToQuote, lineMarkdownText, lineMarkdownText.length)
            )
            continue
        }

        if (listIndent != -1) {
            val indentedMatcher = INDENTED_PATTERN.matcher(lineMarkdownText)

            if (indentedMatcher.matches() && indentedMatcher.group("indent").length < listIndent + 1) {
                listIndent = -1
            }
        }

        if (listIndent != -1) {
            result.add(
                ClassifiedLine(LineKind.LIST_CONTINUATION, markdownOffsetRelativeToQuote, lineMarkdownText, lineMarkdownText.length)
            )
            continue
        }

        if (!lineMarkdownText.startsWith(CODE_BLOCK_INDENT)) {
            // A line that is code-indented in neither this heredoc's own prefix nor CODE_BLOCK_INDENT, but
            // still blank, is not prose - it is a blank line *inside* a code block (CommonMark allows one
            // between two indented-code paragraphs) and must not end an open Elixir code block, the way a
            // real prose line does.
            if (lineMarkdownText.isBlank()) {
                result.add(ClassifiedLine(LineKind.BLANK, markdownOffsetRelativeToQuote, lineMarkdownText, lineMarkdownText.length))
                continue
            }

            inException = false

            result.add(ClassifiedLine(LineKind.PROSE, markdownOffsetRelativeToQuote, lineMarkdownText, lineMarkdownText.length))
            continue
        }

        val lineCodeText = lineMarkdownText.substring(CODE_BLOCK_INDENT_LENGTH)

        val markdownLength = when {
            lineCodeText.startsWith(IEX_PROMPT) -> {
                inException = false

                CODE_BLOCK_INDENT_LENGTH + IEX_PROMPT_LENGTH
            }

            lineCodeText.startsWith(IEX_CONTINUATION) -> {
                // Resetting here is a deliberate tradeoff, not an oversight: a plain line right after
                // `** (Error)` -> `...> code` now falls to the `else` branch below with `inException`
                // already false, so its own text (past the indent) goes to Elixir instead of staying
                // whole as exception prose - see exception_continuation_plain_line.ex and
                // WalkClassificationInjectionTest.testExceptionThenContinuationThenPlainLineIsCodeNotProse.
                // The alternative - not resetting here - is what caused Markdown and Elixir to disagree
                // about that same plain line's range in the first place (see the test just above that
                // one), which cost the whole documentation its Markdown or its Elixir. A stray syntax
                // squiggle on one sample line is the accepted price for that not happening.
                inException = false

                CODE_BLOCK_INDENT_LENGTH + IEX_CONTINUATION_LENGTH
            }

            lineCodeText.startsWith(DEBUG_PREFIX) -> {
                inException = false

                lineMarkdownText.length
            }

            lineCodeText.startsWith(EXCEPTION_PREFIX) -> {
                inException = true

                lineMarkdownText.length
            }

            else -> {
                if (inException) {
                    lineMarkdownText.length
                } else {
                    CODE_BLOCK_INDENT_LENGTH
                }
            }
        }

        result.add(ClassifiedLine(LineKind.CODE, markdownOffsetRelativeToQuote, lineMarkdownText, markdownLength))
    }

    return result
}

private val MARKDOWN_INJECTION: Key<CachedValue<MarkdownInjection>> = Key.create("ELIXIR_MARKDOWN_INJECTION")

/**
 * Splits [documentation] into the Markdown to inject and the [MarkdownPlace]s that carry it, from the
 * shared [classifyLines] walk.
 *
 * Cached per [documentation] for the same reason [classifyLines] is: [LiteralTextEscaper.decode] calls
 * this once per shred on nearly every access to the injected document.
 */
internal fun markdownInjection(documentation: HeredocLiteral): MarkdownInjection =
    CachedValuesManager.getCachedValue(documentation, MARKDOWN_INJECTION) {
        CachedValueProvider.Result.create(computeMarkdownInjection(documentation), documentation)
    }

private fun computeMarkdownInjection(documentation: HeredocLiteral): MarkdownInjection {
    val places = mutableListOf<MarkdownPlace>()
    val contentRanges = mutableListOf<TextRange>()
    val pending = StringBuilder()
    var runPrefix = ""
    var runStartOffset = -1
    var runEndOffset = -1

    // A run with no part outside the interpolations adds no place, so its prefix passes on as `pending` does
    fun closeRun() {
        val parts = partsOutsideInterpolations(documentation, TextRange(runStartOffset, runEndOffset))

        if (parts.isEmpty()) {
            pending.insert(0, runPrefix)
        } else {
            parts.forEachIndexed { index, part -> places.add(MarkdownPlace(if (index == 0) runPrefix else "", part, "")) }
        }

        runStartOffset = -1
        runPrefix = ""
    }

    for (classifiedLine in classifyLines(documentation)) {
        val lineMarkdownText = classifiedLine.lineMarkdownText
        val markdownOffsetRelativeToQuote = classifiedLine.markdownOffsetRelativeToQuote
        val lineMarkdownTextLength = classifiedLine.markdownLength

        // A non-null elixirRangeRelativeToQuote is what marks a code line: the rest of it is the Elixir
        if (classifiedLine.elixirRangeRelativeToQuote != null) {
            if (runStartOffset >= 0) closeRun()

            pending.append(lineMarkdownText, 0, lineMarkdownTextLength)
        } else {
            contentRanges.addAll(
                partsOutsideInterpolations(documentation, TextRange.from(markdownOffsetRelativeToQuote, lineMarkdownTextLength))
            )

            if (runStartOffset < 0) {
                runStartOffset = markdownOffsetRelativeToQuote
                runPrefix = pending.toString()
                pending.setLength(0)
            }

            runEndOffset = markdownOffsetRelativeToQuote + lineMarkdownTextLength
        }
    }

    if (runStartOffset >= 0) closeRun()

    if (pending.isNotEmpty()) {
        if (places.isNotEmpty()) {
            // Documentation ending in a code block: its indent has no following place to ride on
            val last = places.removeAt(places.size - 1)

            places.add(MarkdownPlace(last.prefix, last.rangeInHost, last.suffix + pending))
        } else {
            // Every line is a code line: no run ever opened for the pending indent to ride on as a
            // prefix. Anchor one empty place at the body's start and let it carry that indent alone,
            // the same role `pending` plays for the first real run when one exists.
            markdownRangeInHost(documentation)?.startOffset?.let { start ->
                places.add(MarkdownPlace(pending.toString(), TextRange(start, start), ""))
            }
        }
    }

    return MarkdownInjection(places, contentRanges)
}
