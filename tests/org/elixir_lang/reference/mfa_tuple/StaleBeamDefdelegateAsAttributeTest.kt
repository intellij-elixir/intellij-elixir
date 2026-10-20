package org.elixir_lang.reference.mfa_tuple

import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.code_insight.psiUsagesAtCaret
import java.io.File

/**
 * `Mod.f/2` is a `defdelegate` whose `as:` is a module attribute, and a compiled copy of `Mod` has an older
 * `f(old_q, old_x)`. Find Usages from the alias's `f` lists every use that reaches the source `f`, as it does with no
 * compiled copy: by the alias, by the module's own name, and by the `apply` and MFA tuple that name the module as an
 * atom, which reach the source delegation ahead of the compiled copy.
 */
class StaleBeamDefdelegateAsAttributeTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/reference/mfa_tuple/stale_beam_defdelegate_as_attribute"

    override val ebinDirectory: File
        get() = File(testDataPath, "ebin").absoluteFile

    fun testFindUsagesFromAliasListsEveryUseOfTheSourceDelegation() {
        myFixture.addFileToProject(
            "lib/mod.ex",
            """
            defmodule Mod do
              @target :delegated_f
              defdelegate f(q, x), to: Mod.Target, as: @target
            end

            defmodule Mod.Target do
              def delegated_f(q, x), do: {q, x}
            end
            """.trimIndent()
        )
        myFixture.configureByText(
            "caller.ex",
            """
            defmodule Caller do
              alias Mod, as: Aliased

              def at_aliased(a, b), do: Aliased.f<caret>(a, b)
              def at_qualified(a, b), do: Mod.f(a, b)
              def at_apply(a, b), do: apply(:"Elixir.Mod", :f, [a, b])
              def at_mfa(a, b), do: {{:"Elixir.Mod", :f, 2}, a, b}
            end
            """.trimIndent()
        )

        val text = myFixture.file.text
        val lines = myFixture.psiUsagesAtCaret(project)
            .filter { it.file == myFixture.file }
            .map { text.lineAt(it.range.startOffset) }
            .filter { it.contains("def at_") }
            .sorted()

        assertEquals(
            listOf("def at_aliased", "def at_apply", "def at_mfa", "def at_qualified"),
            lines.map { it.trim().substringBefore("(") }
        )
    }

    private fun String.lineAt(offset: Int): String =
        substring(lastIndexOf('\n', offset - 1) + 1, indexOf('\n', offset).takeIf { it >= 0 } ?: length)
}
