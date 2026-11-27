package org.elixir_lang.documentation

import com.intellij.psi.PsiElement
import org.elixir_lang.psi.ElixirLine
import org.elixir_lang.psi.HeredocLiteral
import org.elixir_lang.psi.decodedTextStart

/**
 * Extracts the documentation markdown text from a module attribute value element
 * (the quoted content of `@doc`, `@moduledoc`, or `@typedoc`).
 *
 * For [HeredocLiteral] values, strips the heredoc indentation prefix from each line.
 * For [ElixirLine] values (single-line `@doc "..."`), returns the body text directly.
 *
 * @return the raw markdown text suitable for parsing by a markdown engine, or `null`
 *         if the element type is not a recognized documentation quote form.
 */
fun PsiElement.documentationMarkdownText(): String? =
    when (this) {
        is HeredocLiteral -> dedentedDocumentationMarkdownText()
        is ElixirLine -> body?.text
        else -> null
    }

/**
 * Extracts markdown text from a documentation heredoc, stripping the heredoc
 * indentation prefix (determined by the closing `"""` position) from each line.
 *
 * For example, given a heredoc indented 2 spaces:
 * ```
 *   @doc """
 *   Hello world.
 *
 *       code_example()
 *   """
 * ```
 * The heredoc lines in the PSI contain the 2-space prefix on each line.
 * This function strips that prefix, or as much of it as a line less indented than the closing `"""` has,
 * producing the raw markdown:
 * ```
 * Hello world.
 *
 *     code_example()
 * ```
 */
fun HeredocLiteral.dedentedDocumentationMarkdownText(): String =
    heredocLineList.joinToString(separator = "") { line -> line.text.substring(line.decodedTextStart(heredocPrefix.textLength)) }
