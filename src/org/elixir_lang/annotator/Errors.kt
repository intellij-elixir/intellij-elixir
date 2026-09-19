package org.elixir_lang.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange

internal fun AnnotationHolder.error(range: TextRange, message: String, tooltip: String? = null) {
    newAnnotation(HighlightSeverity.ERROR, message)
        .range(range)
        .apply { tooltip?.let { tooltip(it) } }
        .create()
}
