package org.elixir_lang.annotator

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.Disposer
import org.elixir_lang.ElixirFileType
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.FUNCTION_CALL
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.FUNCTION_DECLARATION
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.GUARD_CALL
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.GUARD_DECLARATION
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.MACRO_CALL
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.MACRO_DECLARATION
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.declaration.DistinctKeyScheme
import org.elixir_lang.declaration.disableExpander
import org.elixir_lang.declaration.enableExpander
import org.elixir_lang.expander.LegSdk

/**
 * A call is highlighted by what the called declaration can do, whichever form declares it: a clause, a delegation, an
 * exception, an EEx function or a `Mix.Generator` embed, and a guard has its own key. A callback is a contract, so no call names it.
 */
class DeclarationHighlightingTest : PlatformTestCase() {
    private lateinit var scheme: DistinctKeyScheme

    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    override fun setUp() {
        super.setUp()
        LegSdk.setUp(module, testRootDisposable)
        Disposer.register(testRootDisposable) { LegSdk.tearDown(module) }
        scheme = DistinctKeyScheme(testRootDisposable)
    }

    /** The walk knows `EEx` and `Mix.Generator` only from source, so this copies stand-ins for them. */
    fun testEveryFormHighlightsByItsKindWhereDeclaredAndWhereCalled() {
        disableExpander(testRootDisposable)
        myFixture.copyFileToProject("eex.ex")
        myFixture.copyFileToProject("mix_generator.ex")

        assertEveryFormHighlightsByItsKind()
    }

    /** The expander runs the SDK's `EEx` and `Mix.Generator`, which a stand-in in the project would replace. */
    fun testEveryFormHighlightsByItsKindWithTheExpanderOn() {
        enableExpander(testRootDisposable)

        assertEveryFormHighlightsByItsKind()
    }

    private fun assertEveryFormHighlightsByItsKind() {
        myFixture.configureByText(ElixirFileType.INSTANCE, CODE)

        val usage = CODE.indexOf("def usage")

        assertEquals(
            mapOf(
                "function_clause" to setOf(FUNCTION_CALL),
                "macro_clause" to setOf(MACRO_CALL),
                "guard_clause" to setOf(GUARD_CALL),
                "private_guard_clause" to setOf(GUARD_CALL),
                "untargeted" to setOf(FUNCTION_CALL),
                "targeted" to setOf(FUNCTION_CALL),
                "exception" to setOf(FUNCTION_CALL),
                "message" to setOf(FUNCTION_CALL),
                "rendered" to setOf(FUNCTION_CALL),
                "banner_text" to setOf(FUNCTION_CALL)
            ),
            CALLED.associateWith { name -> keysAt(CODE.indexOf("\n      $name(", usage) + "\n      ".length, name.length) }
        )
    }

    /** With the expander on, each name a declaring call writes is a declaration, and a clause's own word stays one. */
    fun testEveryWrittenNameHighlightsAsADeclarationWithTheExpanderOn() {
        enableExpander(testRootDisposable)

        assertEquals(expected(setOf(FUNCTION_DECLARATION)), declarationKeys())
    }

    /** With the expander off only a clause's own word is a declaration. */
    fun testOnlyAClausesOwnWordHighlightsAsADeclarationWithTheExpanderOff() {
        disableExpander(testRootDisposable)
        myFixture.copyFileToProject("eex.ex")
        myFixture.copyFileToProject("mix_generator.ex")

        assertEquals(expected(emptySet()), declarationKeys())
    }

    /** The declaration keys on each name `Sample` declares, where its declaration writes it. */
    private fun declarationKeys(): kotlin.collections.Map<String, Set<TextAttributesKey>> {
        myFixture.configureByText(ElixirFileType.INSTANCE, CODE)
        val sample = CODE.indexOf("defmodule Sample")

        return (CLAUSE_KEYS.keys + WRITTEN).associateWith { name ->
            val offset = Regex("""(?<=[\s\[:])${Regex.escape(name)}(?=[(,)])""").find(CODE, sample)!!.range.first

            keysAt(offset, name.length).intersect(DECLARATION_KEYS)
        }
    }

    fun testAGuardsKeysFallBackToTheMacroKeys() {
        assertEquals(
            listOf(MACRO_CALL, MACRO_DECLARATION),
            listOf(GUARD_CALL.fallbackAttributeKey, GUARD_DECLARATION.fallbackAttributeKey)
        )
    }

    fun testADelegationWithoutATargetIsHighlightedFromOtherModules() {
        myFixture.configureByText(ElixirFileType.INSTANCE, OTHER_MODULES)

        assertEquals(
            mapOf(
                "qualified" to setOf(FUNCTION_CALL),
                "aliased" to setOf(FUNCTION_CALL),
                "imported" to setOf(FUNCTION_CALL),
                "piped" to setOf(FUNCTION_CALL)
            ),
            listOf("qualified", "aliased", "imported", "piped").associateWith { use ->
                val offset = OTHER_MODULES.lastIndexOf("untargeted", OTHER_MODULES.indexOf("# $use\n"))

                keysAt(offset, "untargeted".length)
            }
        )
    }

    private fun keysAt(offset: Int, length: Int): Set<TextAttributesKey> =
        myFixture.doHighlighting()
            .filter { it.forcedTextAttributes != null && it.startOffset == offset && it.endOffset == offset + length }
            .flatMap { scheme.keys(it.forcedTextAttributes) }
            .toSet()

    companion object {
        private val DECLARATION_KEYS = setOf(FUNCTION_DECLARATION, MACRO_DECLARATION, GUARD_DECLARATION)

        private val CLAUSE_KEYS = mapOf(
            "function_clause" to setOf(FUNCTION_DECLARATION),
            "macro_clause" to setOf(MACRO_DECLARATION),
            "guard_clause" to setOf(GUARD_DECLARATION),
            "private_guard_clause" to setOf(GUARD_DECLARATION)
        )

        /** The names declaring calls other than a plain clause write: a delegation's, a list's, an `unquote`d atom, an EEx's and an embed's atoms. */
        private val WRITTEN = listOf("untargeted", "targeted", "listed", "also_listed", "quoted", "\"a b\"", "rendered", "banner")

        /** [CLAUSE_KEYS], and [written] on each of [WRITTEN]. */
        private fun expected(written: Set<TextAttributesKey>): kotlin.collections.Map<String, Set<TextAttributesKey>> =
            CLAUSE_KEYS + WRITTEN.associateWith { written }

        private val CALLED = listOf(
            "function_clause",
            "macro_clause",
            "guard_clause",
            "private_guard_clause",
            "untargeted",
            "targeted",
            "exception",
            "message",
            "rendered",
            "banner_text"
        )

        private val OTHER_MODULES = """
            defmodule Sample do
              defdelegate untargeted(a), to: Missing
            end

            defmodule Qualified do
              def use(x), do: Sample.untargeted(x) # qualified
            end

            defmodule Aliased do
              alias Sample, as: S

              def use(x), do: S.untargeted(x) # aliased
            end

            defmodule Imported do
              import Sample

              def use(x), do: untargeted(x) # imported
            end

            defmodule Piped do
              def use(x), do: x |> Sample.untargeted() # piped
            end
        """.trimIndent() + "\n"

        private val CODE = """
            defmodule Target do
              def delegated_target(a), do: a
            end

            defmodule Sample do
              require EEx
              require Mix.Generator

              def function_clause(a), do: a
              defmacro macro_clause(a), do: a
              defguard guard_clause(a) when is_integer(a)
              defguardp private_guard_clause(a) when is_integer(a)
              defdelegate untargeted(a), to: Missing
              defdelegate targeted(a), to: Target, as: :delegated_target
              defdelegate [listed(a), also_listed(a)], to: Target, as: :delegated_target
              def unquote(:quoted)(a), do: a
              def unquote(:"a b")(x), do: x
              defexception [:message]
              EEx.function_from_string(:def, :rendered, "<%= a %>", [:a])
              Mix.Generator.embed_text(:banner, "Banner")

              def usage(x) do
                {
                  function_clause(x),
                  macro_clause(x),
                  guard_clause(x),
                  private_guard_clause(x),
                  untargeted(x),
                  targeted(x),
                  exception(x),
                  message(x),
                  rendered(x),
                  banner_text()
                }
              end
            end
        """.trimIndent() + "\n"
    }
}
