package org.elixir_lang.psi

import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.PlatformTestCase

/**
 * A qualified call reaches only what its module exports: what it declares or a `use` injects, never what it
 * `import`s. [CallableTable] collects those declarations once and replays them, so it has to replay the path each was
 * reached through: without it a `use`-injected declaration replays as another module's, and is not reached.
 */
class CallableTableReachTest : PlatformTestCase() {
    fun testAQualifiedCallDoesNotReachWhatTheModuleImports() {
        assertEquals(emptyList<String>(), resolvedTexts("help"))
    }

    fun testAQualifiedCallReachesWhatTheModuleDeclares() {
        assertEquals(listOf("def own, do: :ok"), resolvedTexts("own"))
    }

    fun testAQualifiedCallReachesWhatAUseInjectsIntoTheModule() {
        assertTrue("def injected, do: :ok" in resolvedTexts("injected"))
    }

    private fun resolvedTexts(function: String): List<String> {
        myFixture.configureByText(
            "lib.ex",
            """
            defmodule Helper do
              def help, do: :ok
            end

            defmodule Injector do
              defmacro __using__(_) do
                quote do
                  def injected, do: :ok
                end
              end
            end

            defmodule Lib do
              import Helper
              use Injector

              def own, do: :ok
            end

            defmodule User do
              def go, do: Lib.<caret>$function()
            end
            """.trimIndent()
        )

        val leaf = myFixture.file.findElementAt(myFixture.caretOffset)!!
        val reference = generateSequence(leaf) { it.parent }.mapNotNull { it.reference }.first()

        return (reference as PsiPolyVariantReference)
            .multiResolve(false)
            .filter { it.isValidResult }
            .mapNotNull { it.element?.text }
    }
}
