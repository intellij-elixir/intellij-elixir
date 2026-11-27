package org.elixir_lang.psi

/**
 * Where the decoded text of the heredoc line that starts at [lineStart] in [text] starts, as an offset from
 * [lineStart].
 *
 * Elixir drops at most [indentation] of a line's leading spaces and tabs (`elixir_tokenizer:trim_space/2`) and
 * keeps the rest, so a line indented less than the closing delimiter, a blank line included, starts at its first
 * character after those it has. Nothing else is indentation: a form feed is part of the text.
 */
fun heredocLineTextStart(text: CharSequence, lineStart: Int, indentation: Int): Int {
    val limit = minOf(indentation, text.length - lineStart)
    var offset = 0

    while (offset < limit && text[lineStart + offset].let { it == ' ' || it == '\t' }) offset++

    return offset
}

/**
 * Where this heredoc line's decoded text starts, as an offset into the line: [heredocLineTextStart] of its prefix, which
 * the lexer gives all of the line's leading spaces and tabs.
 */
fun HeredocLineable.decodedTextStart(indentation: Int): Int = heredocLineTextStart(heredocLinePrefix.node.chars, 0, indentation)
