package org.elixir_lang.documentation

import com.intellij.openapi.editor.colors.TextAttributesKey
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.FUNCTION_DECLARATION
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.GUARD_DECLARATION
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.MACRO_DECLARATION
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.declaration.DistinctKeyScheme

/** A code block in rendered documentation highlights a declared name by its presentation, a guard by its own key. */
class RenderedDocDeclarationHighlightingTest : PlatformTestCase() {
    private lateinit var scheme: DistinctKeyScheme

    override fun setUp() {
        super.setUp()
        scheme = DistinctKeyScheme(testRootDisposable)
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
        val declarationKeys = setOf(FUNCTION_DECLARATION, MACRO_DECLARATION, GUARD_DECLARATION)
        val keysByStart = mutableMapOf<Int, MutableSet<TextAttributesKey>>()

        while (!iterator.atEnd()) {
            iterator.advance()
            keysByStart.getOrPut(iterator.rangeStart) { mutableSetOf() }
                .addAll(scheme.keys(iterator.textAttributes).intersect(declarationKeys))
        }

        assertEquals(
            mapOf(
                "function_clause" to setOf(FUNCTION_DECLARATION),
                "macro_clause" to setOf(MACRO_DECLARATION),
                "guard_clause" to setOf(GUARD_DECLARATION),
                "private_guard_clause" to setOf(GUARD_DECLARATION)
            ),
            listOf("function_clause", "macro_clause", "guard_clause", "private_guard_clause")
                .associateWith { keysByStart[code.indexOf("$it(")] }
        )
    }
}
