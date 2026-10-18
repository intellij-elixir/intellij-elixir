package org.elixir_lang.declaration

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import org.elixir_lang.ElixirSyntaxHighlighter
import java.awt.Color
import java.awt.Font

/**
 * Makes the global scheme give each key a distinct attribute, so attributes merged from several keys say which keys
 * made them. The shipped schemes cannot: `MACRO_DECLARATION` inherits `FUNCTION_DECLARATION`'s attributes.
 *
 * The scheme stays registered after [disposable] restores the previous one, as removing it needs the internal
 * `EditorColorsManagerImpl`; registering it again under the same name replaces it.
 */
class DistinctKeyScheme(disposable: Disposable) {
    init {
        val manager = EditorColorsManager.getInstance()
        val saved = manager.globalScheme
        val scheme = (saved.clone() as EditorColorsScheme).apply { name = NAME }

        FOREGROUND_KEYS.forEachIndexed { index, key ->
            scheme.setAttributes(key, TextAttributes(foreground(index), null, null, null, Font.PLAIN))
        }
        scheme.setAttributes(
            ElixirSyntaxHighlighter.PREDEFINED_CALL,
            TextAttributes(null, PREDEFINED_BACKGROUND, null, null, Font.PLAIN)
        )

        manager.addColorScheme(scheme)
        manager.setGlobalScheme(scheme)
        Disposer.register(disposable) { manager.setGlobalScheme(saved) }
    }

    /** The keys [attributes] were merged from, as far as this scheme can tell. */
    fun keys(attributes: TextAttributes?): Set<TextAttributesKey> =
        if (attributes == null) {
            emptySet()
        } else {
            setOfNotNull(
                FOREGROUND_KEYS.withIndex().firstOrNull { foreground(it.index) == attributes.foregroundColor }?.value,
                ElixirSyntaxHighlighter.PREDEFINED_CALL.takeIf { attributes.backgroundColor == PREDEFINED_BACKGROUND }
            )
        }

    companion object {
        private const val NAME = "Elixir DistinctKeyScheme"
        private val PREDEFINED_BACKGROUND = Color(1, 1, 250)

        private val FOREGROUND_KEYS = listOf(
            ElixirSyntaxHighlighter.FUNCTION_DECLARATION,
            ElixirSyntaxHighlighter.MACRO_DECLARATION,
            ElixirSyntaxHighlighter.FUNCTION_CALL,
            ElixirSyntaxHighlighter.MACRO_CALL,
            ElixirSyntaxHighlighter.PARAMETER,
            ElixirSyntaxHighlighter.VARIABLE,
            ElixirSyntaxHighlighter.IGNORED_VARIABLE,
            ElixirSyntaxHighlighter.TYPE,
            ElixirSyntaxHighlighter.GUARD_DECLARATION,
            ElixirSyntaxHighlighter.GUARD_CALL
        )

        private fun foreground(index: Int) = Color(1, 2, 10 + index)
    }
}
