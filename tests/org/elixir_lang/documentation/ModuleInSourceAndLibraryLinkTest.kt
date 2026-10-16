package org.elixir_lang.documentation

import org.elixir_lang.beam.BeamLibraryTestCase
import java.io.File

/** A link to a module that is both source and a library `.beam` is drawn as a link, and opens the source. */
class ModuleInSourceAndLibraryLinkTest : BeamLibraryTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/reference/mfa_tuple/stale_beam"

    override val ebinDirectory: File
        get() = File(testDataPath, "ebin").absoluteFile

    override fun setUp() {
        super.setUp()

        myFixture.addFileToProject(
            "lib/mod.ex",
            """
            defmodule Mod do
              @moduledoc "Source Mod."
            end
            """.trimIndent()
        )
        myFixture.configureByText(
            "docs.ex",
            """
            defmodule Do<caret>cs do
              @moduledoc "See `Mod`."
            end
            """.trimIndent()
        )
    }

    fun testTheLinkIsDrawn() {
        val documentation = myFixture.quickDocumentationAtCaret(project)

        assertTrue("Expected a link to Mod, got: $documentation", documentation?.contains("href=\"psi_element://Mod\"") == true)
    }

    fun testTheLinkOpensTheSource() {
        val followed = myFixture.followedLinkAtCaret(project, "psi_element://Mod")

        assertTrue("Expected the source module's docs, got: $followed", followed?.contains("Source Mod.") == true)
    }
}
