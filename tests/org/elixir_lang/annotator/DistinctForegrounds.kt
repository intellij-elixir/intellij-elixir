package org.elixir_lang.annotator

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color

/**
 * Gives each of [keys] its own foreground in the global scheme, and each of [blanked] no attributes, so a highlight
 * names its key by colour even where one key falls back to another; [restore] puts the scheme back.
 */
class DistinctForegrounds(private val keys: List<TextAttributesKey>, blanked: List<TextAttributesKey> = emptyList()) {
    private val scheme = EditorColorsManager.getInstance().globalScheme
    private val original = (blanked + keys).associateWith { scheme.getAttributes(it) }

    init {
        blanked.forEach { scheme.setAttributes(it, TextAttributes()) }
        keys.forEachIndexed { index, key ->
            scheme.setAttributes(key, TextAttributes().apply { foregroundColor = Color(1, 2, 3 + index) })
        }
    }

    /** The one of [keys] whose foreground [foreground] is, or `null`. */
    fun keyOf(foreground: Color?): TextAttributesKey? = keys.firstOrNull { scheme.getAttributes(it).foregroundColor == foreground }

    fun restore() = original.forEach { (key, attributes) -> scheme.setAttributes(key, attributes) }
}
