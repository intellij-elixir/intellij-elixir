package org.elixir_lang.documentation

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.ElixirSyntaxHighlighter
import java.awt.Color

/**
 * A code block in rendered documentation highlights a declared name by its presentation; a guard has its own key.
 * The macro and guard keys fall back to the function key, so each gets its own foreground here.
 */
class RenderedDocDeclarationHighlightingTest : BasePlatformTestCase() {
    private val keys = listOf(
        ElixirSyntaxHighlighter.FUNCTION_DECLARATION,
        ElixirSyntaxHighlighter.MACRO_DECLARATION,
        ElixirSyntaxHighlighter.GUARD_DECLARATION
    )
    private val original = mutableMapOf<TextAttributesKey, TextAttributes?>()

    override fun setUp() {
        super.setUp()

        val scheme = EditorColorsManager.getInstance().globalScheme

        keys.forEachIndexed { index, key ->
            original[key] = scheme.getAttributes(key)
            scheme.setAttributes(key, TextAttributes().apply { foregroundColor = Color(4, 5, 6 + index) })
        }
    }

    override fun tearDown() {
        try {
            val scheme = EditorColorsManager.getInstance().globalScheme

            original.forEach { (key, attributes) -> scheme.setAttributes(key, attributes) }
        } finally {
            super.tearDown()
        }
    }

    fun testADeclaredNameHighlightsByItsKind() {
        val code = """
            defmodule Sample do
              def function_clause(a), do: a
              defmacro macro_clause(a), do: a
              defguard guard_clause(a) when a > 0
              defguardp private_guard_clause(a) when a > 0
            end
        """.trimIndent()
        val iterator = ElixirRenderedDocSemanticHighlighter.additionalIterator(project, code)!!
        val ranges = mutableListOf<Triple<Int, Int, Color?>>()

        while (!iterator.atEnd()) {
            iterator.advance()
            ranges.add(Triple(iterator.rangeStart, iterator.rangeEnd, iterator.textAttributes.foregroundColor))
        }

        val scheme = EditorColorsManager.getInstance().globalScheme
        val actual = listOf("function_clause", "macro_clause", "guard_clause", "private_guard_clause").joinToString("\n") { name ->
            val start = code.indexOf("$name(")
            val key = ranges
                .filter { (rangeStart, rangeEnd) -> rangeStart <= start && rangeEnd >= start + name.length }
                .firstNotNullOfOrNull { (_, _, color) -> keys.firstOrNull { scheme.getAttributes(it).foregroundColor == color } }

            "$name -> ${key?.externalName ?: "none"}"
        }

        assertEquals(
            """
            function_clause -> ELIXIR_FUNCTION_DECLARATION
            macro_clause -> ELIXIR_MACRO_DECLARATION
            guard_clause -> ELIXIR_GUARD_DECLARATION
            private_guard_clause -> ELIXIR_GUARD_DECLARATION
            """.trimIndent(),
            actual
        )
    }
}
