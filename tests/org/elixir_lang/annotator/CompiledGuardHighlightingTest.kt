package org.elixir_lang.annotator

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import org.elixir_lang.ElixirSyntaxHighlighter
import org.elixir_lang.beam.BeamLibraryTestCase
import java.awt.Color
import java.io.File

/**
 * A call to a compiled guard highlights as a guard: a `.beam` tells one apart only by its docs chunk's `guard: true`,
 * which `Kernel.is_nil/1`, a macro, and `Kernel.is_atom/1`, a function, both carry. `Kernel.to_string/1`, a macro,
 * and `Kernel.inspect/1`, a function, are the controls.
 *
 * Each key gets its own foreground, since the macro and guard keys fall back to the function key; the predefined key
 * a `Kernel` call merges in gets none, so it does not mask them.
 */
class CompiledGuardHighlightingTest : BeamLibraryTestCase() {
    private val keys = listOf(
        ElixirSyntaxHighlighter.FUNCTION_CALL,
        ElixirSyntaxHighlighter.MACRO_CALL,
        ElixirSyntaxHighlighter.GUARD_CALL
    )
    private val original = mutableMapOf<TextAttributesKey, TextAttributes?>()

    override fun getTestDataPath(): String = "testData/org/elixir_lang/beam/parser"

    override val ebinDirectory: File
        get() = File("testData/org/elixir_lang/beam/parser/elixir-1.19.5-otp-28").absoluteFile

    override fun setUp() {
        super.setUp()

        val scheme = EditorColorsManager.getInstance().globalScheme

        original[ElixirSyntaxHighlighter.PREDEFINED_CALL] = scheme.getAttributes(ElixirSyntaxHighlighter.PREDEFINED_CALL)
        scheme.setAttributes(ElixirSyntaxHighlighter.PREDEFINED_CALL, TextAttributes())

        keys.forEachIndexed { index, key ->
            original[key] = scheme.getAttributes(key)
            scheme.setAttributes(key, TextAttributes().apply { foregroundColor = Color(7, 8, 9 + index) })
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

    fun testACallToACompiledGuardHighlightsAsAGuard() {
        myFixture.configureByText(
            "compiled_guard.ex",
            """
            defmodule CompiledGuard do
              def f(x) when is_nil(x), do: {to_string(x), is_atom(x), inspect(x)}
            end
            """.trimIndent()
        )

        val infos = myFixture.doHighlighting()
        val text = myFixture.file.text
        val scheme = EditorColorsManager.getInstance().globalScheme

        val actual = listOf("is_nil", "to_string", "is_atom", "inspect").joinToString("\n") { name ->
            val offset = text.indexOf("$name(")

            "$name -> " + infos
                .filter { it.startOffset <= offset && it.endOffset >= offset + name.length }
                .mapNotNull { info -> keys.firstOrNull { scheme.getAttributes(it).foregroundColor == info.forcedTextAttributes?.foregroundColor } }
                .joinToString { it.externalName }
                .ifEmpty { "none" }
        }

        assertEquals(
            """
            is_nil -> ELIXIR_GUARD_CALL
            to_string -> ELIXIR_MACRO_CALL
            is_atom -> ELIXIR_GUARD_CALL
            inspect -> ELIXIR_FUNCTION_CALL
            """.trimIndent(),
            actual
        )
    }
}
