package org.elixir_lang.annotator

import com.intellij.openapi.editor.colors.TextAttributesKey
import org.elixir_lang.ElixirFileType
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.FUNCTION_CALL
import org.elixir_lang.ElixirSyntaxHighlighter.Companion.MACRO_CALL
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.declaration.DistinctKeyScheme

/**
 * A call is highlighted by what the called declaration can do, whichever form declares it: a clause, a delegation, an
 * exception, an EEx function or a `Mix.Generator` embed. A callback is a contract, so no call names it.
 */
class DeclarationHighlightingTest : PlatformTestCase() {
    private lateinit var scheme: DistinctKeyScheme

    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    override fun setUp() {
        super.setUp()
        scheme = DistinctKeyScheme(testRootDisposable)
    }

    fun testEveryFormHighlightsByItsKindWhereDeclaredAndWhereCalled() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.copyFileToProject("mix_generator.ex")
        myFixture.configureByText(ElixirFileType.INSTANCE, CODE)

        val usage = CODE.indexOf("def usage")

        assertEquals(
            mapOf(
                "function_clause" to setOf(FUNCTION_CALL),
                "macro_clause" to setOf(MACRO_CALL),
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
        private val CALLED = listOf(
            "function_clause",
            "macro_clause",
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
              defdelegate untargeted(a), to: Missing
              defdelegate targeted(a), to: Target, as: :delegated_target
              defexception [:message]
              EEx.function_from_string(:def, :rendered, "<%= a %>", [:a])
              Mix.Generator.embed_text(:banner, "Banner")

              def usage(x) do
                {
                  function_clause(x),
                  macro_clause(x),
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
