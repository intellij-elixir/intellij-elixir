package org.elixir_lang.declaration

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageViewTypeLocation
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.FUNCTION_CALL
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.FUNCTION_DECLARATION
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.GUARD_CALL
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.GUARD_DECLARATION
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.MACRO_CALL
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.MACRO_DECLARATION
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.PARAMETER
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.call.Visibility
import org.elixir_lang.documentation.ElixirRenderedDocSemanticHighlighter
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.ElixirIdentifier
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.MaybeExported
import org.elixir_lang.structure_view.Model
import org.elixir_lang.structure_view.element.CallDefinition
import org.elixir_lang.structure_view.element.Timed

/**
 * What a user sees of a source definition for each `def*`. A guard gets no parameter highlight, no usage-view type of
 * its own and no structure-view node.
 */
class SourcePresentationTest : PlatformTestCase() {
    private lateinit var scheme: DistinctKeyScheme

    override fun setUp() {
        super.setUp()
        scheme = DistinctKeyScheme(testRootDisposable)
        myFixture.configureByText("presentation.ex", CODE)
    }

    fun testDeclarationHighlighting() {
        assertEquals(
            mapOf(
                "public_function" to setOf(FUNCTION_DECLARATION),
                "private_function" to setOf(FUNCTION_DECLARATION),
                "public_macro" to setOf(MACRO_DECLARATION),
                "private_macro" to setOf(MACRO_DECLARATION),
                "public_guard" to setOf(GUARD_DECLARATION),
                "private_guard" to setOf(GUARD_DECLARATION)
            ),
            NAMES.associateWith { keysAt(declarationOffset(it), it.length) }
        )
    }

    fun testCallHighlighting() {
        assertEquals(
            mapOf(
                "public_function" to setOf(FUNCTION_CALL),
                "private_function" to setOf(FUNCTION_CALL),
                "public_macro" to setOf(MACRO_CALL),
                "private_macro" to setOf(MACRO_CALL),
                "public_guard" to setOf(GUARD_CALL),
                "private_guard" to setOf(GUARD_CALL)
            ),
            NAMES.associateWith { keysAt(callOffset(it), it.length) }
        )
    }

    fun testParameterHighlighting() {
        assertEquals(
            mapOf(
                "public_function" to setOf(PARAMETER),
                "private_function" to setOf(PARAMETER),
                "public_macro" to setOf(PARAMETER),
                "private_macro" to setOf(PARAMETER),
                "public_guard" to emptySet(),
                "private_guard" to emptySet()
            ),
            NAMES.associateWith { keysAt(declarationOffset(it) + it.length + "(".length, "a".length) }
        )
    }

    fun testUsageViewTypeOfTheDefinition() {
        assertEquals(
            mapOf(
                "public_function" to "function",
                "private_function" to "function",
                "public_macro" to "macro",
                "private_macro" to "macro",
                "public_guard" to UNDESCRIBED_CALL,
                "private_guard" to UNDESCRIBED_CALL
            ),
            NAMES.associateWith { name ->
                ElementDescriptionUtil.getElementDescription(clause(name), UsageViewTypeLocation.INSTANCE)
            }
        )
    }

    fun testUsageViewTypeOfTheName() {
        assertEquals(
            mapOf(
                "public_function" to "function",
                "private_function" to "function",
                "public_macro" to "macro",
                "private_macro" to "macro",
                "public_guard" to UNDESCRIBED_IDENTIFIER,
                "private_guard" to UNDESCRIBED_IDENTIFIER
            ),
            NAMES.associateWith { name ->
                ElementDescriptionUtil.getElementDescription(nameIdentifier(name), UsageViewTypeLocation.INSTANCE)
            }
        )
    }

    fun testExported() {
        assertEquals(
            mapOf(
                "public_function" to true,
                "private_function" to false,
                "public_macro" to true,
                "private_macro" to false,
                "public_guard" to false,
                "private_guard" to false
            ),
            NAMES.associateWith { (clause(it) as MaybeExported).isExported }
        )
    }

    fun testStructureView() {
        assertEquals(
            mapOf(
                "public_function" to (Timed.Time.RUN to Visibility.PUBLIC),
                "private_function" to (Timed.Time.RUN to Visibility.PRIVATE),
                "public_macro" to (Timed.Time.COMPILE to Visibility.PUBLIC),
                "private_macro" to (Timed.Time.COMPILE to Visibility.PRIVATE),
                "calls" to (Timed.Time.RUN to Visibility.PUBLIC)
            ),
            structureViewCallDefinitions()
        )
    }

    fun testQuickDocumentationHighlighting() {
        val iterator = ElixirRenderedDocSemanticHighlighter.additionalIterator(project, CODE)!!
        val keysByStart = mutableMapOf<Int, MutableSet<Set<TextAttributesKey>>>()

        while (!iterator.atEnd()) {
            iterator.advance()
            keysByStart.getOrPut(iterator.rangeStart) { mutableSetOf() }.add(scheme.keys(iterator.textAttributes))
        }

        // The head is also a call, so its name carries a call range too.
        assertEquals(
            mapOf(
                "public_function" to setOf(setOf(FUNCTION_DECLARATION), setOf(FUNCTION_CALL)),
                "private_function" to setOf(setOf(FUNCTION_DECLARATION), setOf(FUNCTION_CALL)),
                "public_macro" to setOf(setOf(MACRO_DECLARATION), setOf(FUNCTION_CALL)),
                "private_macro" to setOf(setOf(MACRO_DECLARATION), setOf(FUNCTION_CALL)),
                "public_guard" to setOf(setOf(GUARD_DECLARATION), setOf(FUNCTION_CALL)),
                "private_guard" to setOf(setOf(GUARD_DECLARATION), setOf(FUNCTION_CALL))
            ),
            NAMES.associateWith { keysByStart[declarationOffset(it)] }
        )
    }

    private fun declarationOffset(name: String): Int = CODE.indexOf(" $name(") + 1

    private fun callOffset(name: String): Int = CODE.indexOf("    $name(b)") + "    ".length

    private fun keysAt(offset: Int, length: Int): Set<TextAttributesKey> =
        myFixture.doHighlighting()
            .filter { it.forcedTextAttributes != null && it.startOffset == offset && it.endOffset == offset + length }
            .flatMap { scheme.keys(it.forcedTextAttributes) }
            .toSet()

    private fun nameIdentifier(name: String): ElixirIdentifier =
        PsiTreeUtil.getParentOfType(
            myFixture.file.findElementAt(declarationOffset(name)),
            ElixirIdentifier::class.java,
            false
        )!!

    private fun clause(name: String): Call =
        generateSequence(myFixture.file.findElementAt(declarationOffset(name))) { it.parent }
            .filterIsInstance<Call>()
            .first { it.functionName()?.startsWith("def") == true }

    private fun structureViewCallDefinitions(): Map<String, Pair<Timed.Time, Visibility?>> {
        val found = mutableMapOf<String, Pair<Timed.Time, Visibility?>>()

        fun walk(element: StructureViewTreeElement) {
            if (element is CallDefinition) {
                val name = element.presentation.presentableText!!.substringBefore("/")
                found[name] = element.time() to element.visibility()
            }

            element.children.filterIsInstance<StructureViewTreeElement>().forEach(::walk)
        }

        walk(Model(myFixture.file as ElixirFile, null).root)

        return found
    }

    companion object {
        /** The platform's fallback when no provider describes the element: its class's name. */
        private const val UNDESCRIBED_CALL = "Elixir Unmatched Unqualified No Parentheses Call Impl"
        private const val UNDESCRIBED_IDENTIFIER = "Elixir Identifier Impl"

        private val NAMES = listOf(
            "public_function",
            "private_function",
            "public_macro",
            "private_macro",
            "public_guard",
            "private_guard"
        )

        private val CODE = """
            defmodule Presentation do
              def public_function(a), do: a
              defp private_function(a), do: a
              defmacro public_macro(a), do: a
              defmacrop private_macro(a), do: a
              defguard public_guard(a) when is_integer(a)
              defguardp private_guard(a) when is_integer(a)

              def calls(b) do
                public_function(b)
                private_function(b)
                public_macro(b)
                private_macro(b)
                public_guard(b)
                private_guard(b)
              end
            end
        """.trimIndent() + "\n"
    }
}
