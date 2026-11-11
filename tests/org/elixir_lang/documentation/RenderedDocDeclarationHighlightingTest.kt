package org.elixir_lang.documentation

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Disposer
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.FUNCTION_DECLARATION
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.GUARD_DECLARATION
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.MACRO_DECLARATION
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.declaration.DistinctKeyScheme
import org.elixir_lang.declaration.disableExpander
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.expander.LegSdk
import java.awt.Color
import java.awt.Font

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
        val keysByStart = keysByStart(code)

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

    /** On a project SDK with the expander on, each name a declaring call writes is a declaration, and a clause's word stays one. */
    fun testEveryWrittenNameHighlightsAsADeclarationWithTheExpanderOn() {
        projectSdk()
        enableExpander(testRootDisposable)

        assertEquals(expected(setOf(FUNCTION_DECLARATION)), declarationKeys())
    }

    /** With the expander off only a clause's own word is a declaration. */
    fun testOnlyAClausesOwnWordHighlightsAsADeclarationWithTheExpanderOff() {
        projectSdk()
        disableExpander(testRootDisposable)

        assertEquals(expected(emptySet()), declarationKeys())
    }

    /**
     * A code block is in no module, so with only a module SDK no env is reached, and the name index, which holds no code
     * block, names none of its written names; a clause's own word stays a declaration.
     */
    fun testWithOnlyAModuleSdkOnlyAClausesOwnWordHighlightsAsADeclarationWithTheExpanderOn() {
        LegSdk.setUp(module, testRootDisposable)
        Disposer.register(testRootDisposable) { LegSdk.tearDown(module) }
        enableExpander(testRootDisposable)

        assertEquals(expected(emptySet()), declarationKeys())
    }

    /** The rendered block shows a written name in its declaration's colour, as it does a clause's word. */
    fun testTheRenderedBlockColoursAWrittenNameAsADeclarationWithTheExpanderOn() {
        projectSdk()
        enableExpander(testRootDisposable)
        val html = RenderedDocCodeBlockRenderer.render(project, ElixirLanguage, WRITING)

        assertEquals(expected(setOf(FUNCTION_DECLARATION)), NAMES.associateWith { keysOf(html, it) })
    }

    /** The leg's SDK as the project's, which a code block, in no module, is expanded with. */
    private fun projectSdk() {
        val sdk = LegSdk.setUp(module, testRootDisposable)
        WriteAction.runAndWait<Throwable> { ProjectRootManager.getInstance(project).projectSdk = sdk }
        Disposer.register(testRootDisposable) {
            WriteAction.runAndWait<Throwable> { ProjectRootManager.getInstance(project).projectSdk = null }
            LegSdk.tearDown(module)
        }
    }

    /** The declaration keys of the semantic ranges starting at each name [WRITING] declares, where its declaration writes it. */
    private fun declarationKeys(): Map<String, Set<TextAttributesKey>> {
        val keysByStart = keysByStart(WRITING)

        return NAMES.associateWith { keysByStart[declaredAt(it)].orEmpty() }
    }

    /** The declaration keys of [code]'s semantic ranges, by where each starts. */
    private fun keysByStart(code: String): Map<Int, Set<TextAttributesKey>> {
        val iterator = ElixirRenderedDocSemanticHighlighter.additionalIterator(project, code)!!
        val keysByStart = mutableMapOf<Int, MutableSet<TextAttributesKey>>()

        while (!iterator.atEnd()) {
            iterator.advance()
            keysByStart.getOrPut(iterator.rangeStart) { mutableSetOf() }
                .addAll(scheme.keys(iterator.textAttributes).intersect(DECLARATION_KEYS))
        }

        return keysByStart
    }

    /** The keys of the colour [html] shows [name] in, where a span holds it alone, a space written `&#32;`. */
    private fun keysOf(html: String, name: String): Set<TextAttributesKey>? =
        Regex("""<span style="color:#([0-9a-f]{6})[^"]*">${Regex.escape(name.replace(" ", "&#32;"))}<""").find(html)
            ?.groupValues?.get(1)
            ?.let { scheme.keys(TextAttributes(Color(it.toInt(16)), null, null, null, Font.PLAIN)) }

    /** The semantic ranges are only half the result: a head is also a call, and the block shows the two merged. */
    fun testTheRenderedBlockColoursADeclaredNameByItsKind() {
        val code = """
            defmodule Sample do
              def function_clause(a), do: a
              defmacro macro_clause(a), do: a
              defguard guard_clause(a) when a > 0
              defguardp private_guard_clause(a) when a > 0
            end
        """.trimIndent()
        val html = RenderedDocCodeBlockRenderer.render(project, ElixirLanguage, code)

        assertEquals(
            mapOf(
                "function_clause" to setOf(FUNCTION_DECLARATION),
                "macro_clause" to setOf(MACRO_DECLARATION),
                "guard_clause" to setOf(GUARD_DECLARATION),
                "private_guard_clause" to setOf(GUARD_DECLARATION)
            ),
            listOf("function_clause", "macro_clause", "guard_clause", "private_guard_clause")
                .associateWith { keysOf(html, it) }
        )
    }

    companion object {
        private val DECLARATION_KEYS = setOf(FUNCTION_DECLARATION, MACRO_DECLARATION, GUARD_DECLARATION)

        private val CLAUSE_KEYS = mapOf(
            "function_clause" to setOf(FUNCTION_DECLARATION),
            "macro_clause" to setOf(MACRO_DECLARATION),
            "guard_clause" to setOf(GUARD_DECLARATION),
            "private_guard_clause" to setOf(GUARD_DECLARATION)
        )

        /** The names declaring calls other than a plain clause write: a delegation's, a list's, an `unquote`d atom, an EEx's and an embed's atoms. */
        private val WRITTEN = listOf("single", "listed", "also_listed", "quoted", "\"a b\"", "rendered", "page")

        private val NAMES = CLAUSE_KEYS.keys + WRITTEN

        /** [CLAUSE_KEYS], and [written] on each of [WRITTEN]. */
        private fun expected(written: Set<TextAttributesKey>): Map<String, Set<TextAttributesKey>> =
            CLAUSE_KEYS + WRITTEN.associateWith { written }

        private val WRITING = """
            defmodule Sample do
              require EEx
              require Mix.Generator

              def function_clause(a), do: a
              defmacro macro_clause(a), do: a
              defguard guard_clause(a) when a > 0
              defguardp private_guard_clause(a) when a > 0
              defdelegate single(a), to: Enum, as: :reverse
              defdelegate [listed(a), also_listed(a)], to: Enum, as: :reverse
              def unquote(:quoted)(a), do: a
              def unquote(:"a b")(x), do: x
              EEx.function_from_string(:def, :rendered, "<%= a %>", [:a])
              Mix.Generator.embed_template(:page, "<%= @a %>")
            end
        """.trimIndent()

        /** Where [WRITING]'s declaration writes [name]: after a space, a `[` or an atom's colon, before `(`, `,` or `)`. */
        private fun declaredAt(name: String): Int =
            Regex("""(?<=[\s\[:])${Regex.escape(name)}(?=[(,)])""").find(WRITING)!!.range.first
    }
}
