package org.elixir_lang.annotator

import com.intellij.openapi.util.text.StringUtil

/** Elixir's column: code points since the start of the line, a tab counting as one, from 1. */
internal fun column(text: CharSequence, offset: Int): Int {
    var start = offset

    while (start > 0 && text[start - 1] != '\n') start--

    return Character.codePointCount(text, start, offset) + 1
}

/** Elixir's line, from 1. */
internal fun line(text: CharSequence, offset: Int): Int = StringUtil.offsetToLineNumber(text, offset) + 1

/** [text] with each line continuation, a `\` ending a line, replaced by [replacement]. */
internal fun withoutLineContinuations(text: CharSequence, replacement: String = ""): String =
    text.toString().replace("\\\r\n", replacement).replace("\\\n", replacement)
