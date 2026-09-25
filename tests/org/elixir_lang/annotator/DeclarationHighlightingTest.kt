package org.elixir_lang.annotator

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.elixir_lang.ElixirFileType
import org.elixir_lang.ElixirSyntaxHighlighter

/**
 * Every declaring form highlights where it spells the declared name, and every call to what it declares, by the
 * presentation [org.elixir_lang.psi.CallableDeclaration] gives it: a guard is expanded at compile time but
 * evaluates its arguments, so it has its own keys. `defexception` and an embed's prefix atom do not spell the names
 * they declare, and an interpolated EEx name declares nothing.
 *
 * The macro and guard keys fall back to the function keys, so the default scheme cannot tell them apart; each gets
 * its own foreground here, and a highlight is named by its foreground.
 */
class DeclarationHighlightingTest : BasePlatformTestCase() {
    private val keys = listOf(
        ElixirSyntaxHighlighter.FUNCTION_DECLARATION,
        ElixirSyntaxHighlighter.MACRO_DECLARATION,
        ElixirSyntaxHighlighter.FUNCTION_CALL,
        ElixirSyntaxHighlighter.MACRO_CALL,
        ElixirSyntaxHighlighter.GUARD_DECLARATION,
        ElixirSyntaxHighlighter.GUARD_CALL
    )
    private lateinit var foregrounds: DistinctForegrounds

    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    override fun setUp() {
        super.setUp()

        foregrounds = DistinctForegrounds(keys)
    }

    override fun tearDown() {
        try {
            foregrounds.restore()
        } finally {
            super.tearDown()
        }
    }

    fun testEveryFormHighlightsByItsKindWhereDeclaredAndWhereCalled() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.copyFileToProject("mix_generator.ex")
        myFixture.configureByText(
            ElixirFileType.INSTANCE,
            """
            defmodule Sample do
              require EEx
              require Mix.Generator

              def function_clause(a), do: a
              defp private_function_clause(a), do: a
              defmacro macro_clause(a), do: a
              defmacrop private_macro_clause(a), do: a
              defguard guard_clause(a) when a > 0
              defguardp private_guard_clause(a) when a > 0
              defdelegate delegated(a), to: Target
              defexception [:message]
              EEx.function_from_string(:def, :rendered, "<%= a %>", [:a])
              EEx.function_from_string(:def, :"#{:interpolated}_page", "")
              Mix.Generator.embed_text(:banner, "Banner")

              def usage(x) when guard_clause(x) and private_guard_clause(x) do
                {
                  function_clause(x),
                  private_function_clause(x),
                  macro_clause(x),
                  private_macro_clause(x),
                  delegated(x),
                  exception(x),
                  message(x),
                  rendered(x),
                  banner_text()
                }
              end
            end
            """.trimIndent()
        )

        val infos = myFixture.doHighlighting()
        val text = myFixture.file.text
        val usage = text.indexOf("def usage")

        fun keyAt(offset: Int, name: String): String =
            infos
                .filter { it.startOffset <= offset && it.endOffset >= offset + name.length }
                .mapNotNull { info -> foregrounds.keyOf(info.forcedTextAttributes?.foregroundColor) }
                .joinToString { it.externalName }
                .ifEmpty { "none" }

        val declared = listOf(
            "function_clause", "private_function_clause", "macro_clause", "private_macro_clause", "guard_clause",
            "private_guard_clause", "delegated"
        ).map { name -> name to text.indexOf("$name(") } +
            listOf("rendered", "banner").map { name -> name to text.indexOf(":$name") + 1 } +
            listOf("_page" to text.indexOf("_page"))
        val called = listOf(
            "function_clause", "private_function_clause", "macro_clause", "private_macro_clause", "guard_clause",
            "private_guard_clause", "delegated", "exception", "message", "rendered", "banner_text"
        )

        val actual = declared.joinToString("\n") { (name, offset) -> "$name declared -> ${keyAt(offset, name)}" } +
            "\n" +
            called.joinToString("\n") { name -> "$name called -> ${keyAt(text.indexOf("$name(", usage), name)}" }

        assertEquals(
            """
            function_clause declared -> ELIXIR_FUNCTION_DECLARATION
            private_function_clause declared -> ELIXIR_FUNCTION_DECLARATION
            macro_clause declared -> ELIXIR_MACRO_DECLARATION
            private_macro_clause declared -> ELIXIR_MACRO_DECLARATION
            guard_clause declared -> ELIXIR_GUARD_DECLARATION
            private_guard_clause declared -> ELIXIR_GUARD_DECLARATION
            delegated declared -> ELIXIR_FUNCTION_DECLARATION
            rendered declared -> ELIXIR_FUNCTION_DECLARATION
            banner declared -> none
            _page declared -> none
            function_clause called -> ELIXIR_FUNCTION_CALL
            private_function_clause called -> ELIXIR_FUNCTION_CALL
            macro_clause called -> ELIXIR_MACRO_CALL
            private_macro_clause called -> ELIXIR_MACRO_CALL
            guard_clause called -> ELIXIR_GUARD_CALL
            private_guard_clause called -> ELIXIR_GUARD_CALL
            delegated called -> ELIXIR_FUNCTION_CALL
            exception called -> ELIXIR_FUNCTION_CALL
            message called -> ELIXIR_FUNCTION_CALL
            rendered called -> ELIXIR_FUNCTION_CALL
            banner_text called -> ELIXIR_FUNCTION_CALL
            """.trimIndent(),
            actual
        )
    }
}
