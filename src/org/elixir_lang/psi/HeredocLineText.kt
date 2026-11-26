package org.elixir_lang.psi

/**
 * Where this heredoc line's decoded text starts, as an offset into the line.
 *
 * Elixir drops at most [indentation] of a line's leading spaces and tabs (`elixir_tokenizer:trim_space/2`) and
 * keeps the rest, so a line indented less than the closing delimiter, a blank line included, starts at its first
 * character after those it has.
 */
fun HeredocLineable.decodedTextStart(indentation: Int): Int = minOf(indentation, heredocLinePrefix.textLength)
