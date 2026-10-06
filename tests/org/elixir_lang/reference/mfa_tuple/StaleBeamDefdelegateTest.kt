package org.elixir_lang.reference.mfa_tuple

import org.elixir_lang.beam.BeamLibraryTestCase
import org.elixir_lang.code_insight.searchTargetCountAtCaret
import java.io.File

/**
 * `Mod.f/2` is a `defdelegate` to `Mod.Target`, and a compiled copy of `Mod` has an older `f(old_q, old_x)`. Find Usages
 * at `:"Elixir.Mod"`'s `f` has one search target, as it has at the alias's `f`.
 */
class StaleBeamDefdelegateTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/reference/mfa_tuple/stale_beam_defdelegate"

    override val ebinDirectory: File
        get() = File(testDataPath, "ebin").absoluteFile

    fun testFindUsagesFromAtomApplyHasOneSearchTarget() {
        myFixture.addFileToProject(
            "lib/mod.ex",
            """
            defmodule Mod do
              defdelegate f(q, x), to: Mod.Target
            end

            defmodule Mod.Target do
              def f(q, x), do: {q, x}
            end
            """.trimIndent()
        )
        myFixture.configureByText(
            "caller.ex",
            """
            defmodule Caller do
              def run(q, x), do: apply(:"Elixir.Mod", :f<caret>, [q, x])
            end
            """.trimIndent()
        )

        assertEquals(1, myFixture.searchTargetCountAtCaret())
    }
}
