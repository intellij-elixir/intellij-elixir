package org.elixir_lang.model.psi.config

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiFile

/** Each usage as `file:line text`, sorted. */
internal fun labels(usages: List<Pair<PsiFile, TextRange>>): List<String> =
    usages.map { (file, range) ->
        val text = file.text
        val line = StringUtil.offsetToLineNumber(text, range.startOffset) + 1

        "${file.name}:$line ${range.substring(text)}"
    }.sorted()
