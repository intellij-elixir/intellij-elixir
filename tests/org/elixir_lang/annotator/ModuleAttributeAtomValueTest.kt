package org.elixir_lang.annotator

import com.intellij.openapi.editor.colors.EditorColorsManager
import org.elixir_lang.ElixirSyntaxHighlighter
import org.elixir_lang.PlatformTestCase

/** The annotator reads `false` and `nil` as the atoms they are, so `:false` and `:"false"` are `false`. */
class ModuleAttributeAtomValueTest : PlatformTestCase() {
    fun testDocumentationHiddenWithFalseIsWarnedAboutHoweverItIsWritten() {
        for (hidden in listOf("false", ":false", ":\"false\"", ":\"fals\\x65\"")) {
            myFixture.configureByText("doc.ex", "defmodule M do\n  @doc $hidden\n  def f, do: 1\nend\n")

            assertTrue("`@doc $hidden` should be warned about", descriptions().contains(HIDDEN))
        }
    }

    fun testDocumentationThatIsNotHiddenIsNotWarnedAbout() {
        for (shown in listOf("nil", ":nil", ":true", ":\"x\"", "\"x\"")) {
            myFixture.configureByText("doc.ex", "defmodule M do\n  @doc $shown\n  def f, do: 1\nend\n")

            assertFalse("`@doc $shown` should not be warned about", descriptions().contains(HIDDEN))
        }
    }

    /** `{a, [], nil}` is the AST of the variable `a`, which the annotator takes for a type parameter. */
    fun testTypeParameterWrittenAsAstIsFoundWhateverWayNilIsWritten() {
        val expected = EditorColorsManager.getInstance().globalScheme.getAttributes(ElixirSyntaxHighlighter.TYPE_PARAMETER)

        for (nil in listOf("nil", ":nil", ":\"nil\"")) {
            val text = "@type t({a, [], $nil}) :: a\n"

            myFixture.configureByText("type.ex", text)

            val use = text.lastIndexOf("a")
            val covering = myFixture.doHighlighting().filter { it.startOffset <= use && it.endOffset > use }

            assertTrue(
                "`a` should be a type parameter with `$nil`; ${covering.map { it.forcedTextAttributes }}",
                covering.any { expected == it.forcedTextAttributes }
            )
        }
    }

    private fun descriptions(): List<String?> = myFixture.doHighlighting().map { it.description }

    private companion object {
        const val HIDDEN = "Will make documented invisible to the documentation extraction tools like ExDoc."
    }
}
