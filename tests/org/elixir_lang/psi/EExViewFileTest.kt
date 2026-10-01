package org.elixir_lang.psi

import org.elixir_lang.ElixirLanguage
import org.elixir_lang.PlatformTestCase

/** An `.eex` template's view module is the one compiling it with `EEx.function_from_file(..., Path.expand(...))`. */
class EExViewFileTest : PlatformTestCase() {
    fun testAliases() = assertViewFile("EEx", "Path")

    fun testElixirPrefixedEEx() = assertViewFile("Elixir.EEx", "Path")

    fun testQuotedEEx() = assertViewFile(":\"Elixir.EEx\"", "Path")

    fun testElixirPrefixedPath() = assertViewFile("EEx", "Elixir.Path")

    private fun assertViewFile(eex: String, path: String) {
        myFixture.addFileToProject(
            "x.ex",
            "defmodule X do\n  require EEx\n\n  $eex.function_from_file(:def, :render, $path.expand(\"t.eex\", __DIR__))\nend\n"
        )
        myFixture.configureByText("t.eex", "<%= 1 %>")

        val elixirRoot = myFixture.file.viewProvider.getPsi(ElixirLanguage) as ElixirFile

        assertEquals("x.ex", elixirRoot.viewFile()?.name)
    }
}
