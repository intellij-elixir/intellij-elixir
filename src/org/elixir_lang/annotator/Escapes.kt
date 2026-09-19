package org.elixir_lang.annotator

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace

internal const val INVALID_HEX_ESCAPE_WHEN_COMPILED =
    "invalid hex escape character, expected \\xHH where H is a hexadecimal digit"
internal const val INVALID_HEX_ESCAPE = "$INVALID_HEX_ESCAPE_WHEN_COMPILED. Syntax error after: \\x"

internal const val INVALID_UNICODE_ESCAPE_WHEN_COMPILED =
    "invalid Unicode escape character, expected \\uHHHH or \\u{H*} where H is a hexadecimal digit"
internal const val INVALID_UNICODE_ESCAPE = "$INVALID_UNICODE_ESCAPE_WHEN_COMPILED. Syntax error after: \\u"

internal const val INVALID_ESCAPE_AT_END = "invalid escape \\ at end of file"

/** Elixir's tokenizer stops first at a backslash, with or without a newline, that ends the file. */
internal fun endsWithBackslash(element: PsiElement): Boolean {
    val file = element.containingFile
    val text = file.viewProvider.contents
    val backslash = when {
        text.endsWith("\\\r\n") -> text.length - 3
        text.endsWith("\\\n") -> text.length - 2
        text.endsWith("\\") -> text.length - 1
        else -> return false
    }
    val leaf = file.findElementAt(backslash)

    return leaf is PsiWhiteSpace || isFinalBackslash(leaf)
}

internal fun isFinalBackslash(leaf: PsiElement?): Boolean =
    leaf != null && leaf.text == "\\" && leaf.textRange.endOffset == leaf.containingFile.textLength

internal fun isUnicodeScalarValue(codePoint: Long): Boolean = codePoint !in 0xD800..0xDFFF && codePoint <= 0x10FFFF
