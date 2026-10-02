package org.elixir_lang.psi.scope.call_definition_clause

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call
import java.io.File

class VariantsTest : PlatformTestCase() {
    fun testIssue453() {
        myFixture.configureByFiles("defmodule.ex")
        myFixture.complete(CompletionType.BASIC)
        val strings = myFixture.lookupElementStrings
        assertNotNull("Completion not shown", strings)
        assertEquals("Wrong number of completions", 0, strings!!.size)
    }

    fun testIssue462() {
        myFixture.configureByFiles("self_completion.ex")
        myFixture.complete(CompletionType.BASIC)
        val variants = myFixture.lookupElementStrings.orEmpty()
        assertFalse(
            "Function currently being defined should not appear in completion variants",
            variants.contains("the_function_currently_being_defined")
        )
    }

    fun testIssue2073() {
        myFixture.configureByFile("callback.ex")
        val variants = myFixture.complete(CompletionType.BASIC).orEmpty().toList()

        assertRenderedVariant(
            variants,
            lookupString = "function_callback",
            expectedTailText = "/0 (callback.ex defmodule Behaviour)"
        )
        assertRenderedVariant(
            variants,
            lookupString = "macro_callback",
            expectedTailText = "/1 (callback.ex defmodule Behaviour)"
        )
    }

    /**
     * A guard defined with `defguard`/`defguardp` is offered where a guard is actually used - in the
     * `when` clause of a sibling definition in the same module.
     */
    fun testGuard() {
        myFixture.configureByFile("guard.ex")
        val variants = myFixture.complete(CompletionType.BASIC).orEmpty().toList()

        assertRenderedVariant(
            variants,
            lookupString = "is_even_number",
            expectedTailText = "(value) when rem(value, 2) == 0 (guard.ex defmodule GuardVariants)"
        )
        assertRenderedVariant(
            variants,
            lookupString = "is_even_tuple",
            expectedTailText = "(value) when rem(tuple_size(value), 2) == 0 (guard.ex defmodule GuardVariants)"
        )
    }

    /** A function Elixir can only call through its module is not offered where it would be called without one. */
    fun testNameThatCanOnlyBeCalledQuotedIsNotOffered() {
        myFixture.configureByText(
            "quoted_name.ex",
            """
            defmodule QuotedName do
              def unquote(:"foo bar")(), do: 1
              def foo_baz, do: 2
              def foo_qux, do: 3

              def run, do: foo<caret>
            end
            """.trimIndent()
        )

        val strings = myFixture.complete(CompletionType.BASIC).orEmpty().map { it.lookupString }

        assertTrue("Expected the controls among $strings", strings.containsAll(listOf("foo_baz", "foo_qux")))
        assertFalse("Expected `foo bar` not to be offered, got $strings", "foo bar" in strings)
    }

    fun testEExFunctionThatCanOnlyBeCalledQuotedIsNotOffered() {
        addDeclaringModule("eex.ex")
        myFixture.configureByText(
            "quoted_eex_name.ex",
            """
            defmodule QuotedEExName do
              require EEx

              EEx.function_from_string(:def, :"foo bar", "<%= a %>", [:a])
              EEx.function_from_string(:def, :foo_baz, "<%= a %>", [:a])
              EEx.function_from_string(:def, :foo_qux, "<%= a %>", [:a])

              def run, do: foo<caret>
            end
            """.trimIndent()
        )

        val strings = myFixture.complete(CompletionType.BASIC).orEmpty().map { it.lookupString }

        assertTrue("Expected the controls among $strings", strings.containsAll(listOf("foo_baz", "foo_qux")))
        assertFalse("Expected `foo bar` not to be offered, got $strings", "foo bar" in strings)
    }

    fun testEmbeddedTemplateThatCanOnlyBeCalledQuotedIsNotOffered() {
        addDeclaringModule("mix_generator.ex")
        myFixture.configureByText(
            "quoted_embed_name.ex",
            """
            defmodule QuotedEmbedName do
              require Mix.Generator

              Mix.Generator.embed_template(:"foo bar", "<%= @a %>")
              Mix.Generator.embed_template(:foo_baz, "<%= @a %>")
              Mix.Generator.embed_template(:foo_qux, "<%= @a %>")

              def run, do: foo<caret>
            end
            """.trimIndent()
        )

        val strings = myFixture.complete(CompletionType.BASIC).orEmpty().map { it.lookupString }

        assertTrue(
            "Expected the controls among $strings",
            strings.containsAll(listOf("foo_baz_template", "foo_qux_template"))
        )
        assertFalse("Expected `foo bar_template` not to be offered, got $strings", "foo bar_template" in strings)
    }

    fun testEExFunctionWithNoStaticNameIsNotOffered() {
        addDeclaringModule("eex.ex")
        myFixture.configureByText(
            "interpolated_eex_name.ex",
            """
            defmodule InterpolatedEExName do
              require EEx

              EEx.function_from_string(:def, :"foo_#{:bar}", "<%= a %>", [:a])
              EEx.function_from_string(:def, :foo_baz, "<%= a %>", [:a])
              EEx.function_from_string(:def, :foo_qux, "<%= a %>", [:a])

              def run, do: foo<caret>
            end
            """.trimIndent()
        )

        val strings = lookupStringsAtCaret()

        assertTrue("Expected the controls among $strings", strings.containsAll(listOf("foo_baz", "foo_qux")))
        assertEquals("Expected no interpolated name, got $strings", emptyList<String>(), strings.filter { "#{" in it })
    }

    fun testEmbeddedTemplateWithNoStaticNameIsNotOffered() {
        addDeclaringModule("mix_generator.ex")
        myFixture.configureByText(
            "interpolated_embed_name.ex",
            """
            defmodule InterpolatedEmbedName do
              require Mix.Generator

              Mix.Generator.embed_template(:"foo_#{:bar}", "<%= @a %>")
              Mix.Generator.embed_template(:foo_baz, "<%= @a %>")
              Mix.Generator.embed_template(:foo_qux, "<%= @a %>")

              def run, do: foo<caret>
            end
            """.trimIndent()
        )

        val strings = lookupStringsAtCaret()

        assertTrue(
            "Expected the controls among $strings",
            strings.containsAll(listOf("foo_baz_template", "foo_qux_template"))
        )
        assertEquals("Expected no interpolated name, got $strings", emptyList<String>(), strings.filter { "#{" in it })
    }

    /** Every lookup the walk builds at the caret's call, without the popup's prefix matching. */
    private fun lookupStringsAtCaret(): List<String> {
        val element = myFixture.file.findElementAt(myFixture.caretOffset - 1)
        val call = PsiTreeUtil.getParentOfType(element, Call::class.java)!!

        return Variants.lookupElementList(call).map { it.lookupString }
    }

    /** Adds the decompiled module that declares the macro, so the call is recognised as defining a function. */
    private fun addDeclaringModule(name: String) {
        myFixture.addFileToProject(name, File(DECLARING_MODULES, name).readText())
    }

    /**
     * Finds the completion [LookupElement] whose lookup string is [lookupString], renders it, and
     * asserts its item text equals [lookupString] and its tail text equals [expectedTailText].
     */
    private fun assertRenderedVariant(
        variants: List<LookupElement>,
        lookupString: String,
        expectedTailText: String
    ) {
        val variant = variants.find { it.lookupString == lookupString }
        assertNotNull("Completion variant '$lookupString' not offered", variant)

        val presentation = LookupElementPresentation()
        variant!!.renderElement(presentation)
        assertEquals(lookupString, presentation.itemText)
        assertEquals(expectedTailText, presentation.tailText)
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/scope/call_definition_clause/variants"

    private companion object {
        const val DECLARING_MODULES =
            "testData/org/elixir_lang/code_insight/completion/contributor/call_definition_clause"
    }
}
