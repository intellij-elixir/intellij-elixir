package org.elixir_lang.inlay_hints

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder
import com.intellij.psi.PsiElement

/** The platform cuts a longer text node short (`PresentationTreeBuilderImpl.MAX_SEGMENT_TEXT_LENGTH`). */
private const val MAX_TEXT_NODE_LENGTH = 30

/** Adds a hint of [text] right after [element], clicked as [actionData]. */
internal fun InlayTreeSink.addTextAfter(element: PsiElement, text: String, actionData: InlayActionData? = null) {
    addPresentation(InlineInlayPosition(element.textRange.endOffset, relatedToPrevious = true), hintFormat = HintFormat.default) {
        textNodes(text, actionData)
    }
}

/**
 * Adds [text] as text nodes of at most [MAX_TEXT_NODE_LENGTH] UTF-16 chars, the platform's unit, never splitting a
 * surrogate pair, each clicked as [actionData].
 */
private fun PresentationTreeBuilder.textNodes(text: String, actionData: InlayActionData?) {
    var start = 0

    while (start < text.length) {
        var end = minOf(start + MAX_TEXT_NODE_LENGTH, text.length)
        if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
        text(text.substring(start, end), actionData)
        start = end
    }
}
