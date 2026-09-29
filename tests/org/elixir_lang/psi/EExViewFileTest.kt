package org.elixir_lang.psi

import org.elixir_lang.ElixirLanguage
import org.elixir_lang.PlatformTestCase

/**
 * An `.eex` template's view is the module in its directory that compiles it with `EEx.function_from_file`, however
 * written. A template's context is asked for while resolving, so finding its view does not need `EEx` to resolve.
 */
class EExViewFileTest : PlatformTestCase() {
    fun testTheViewIsTheModuleThatCompilesTheTemplate() {
        assertEquals(
            mapOf("qualified" to "page_view.ex", "imported" to "page_view.ex", "other" to null),
            views(
                "qualified" to "require EEx\n  EEx.function_from_file(:def, :index, Path.expand(\"index.html.eex\", __DIR__), [:assigns])",
                "imported" to "import EEx\n  function_from_file(:def, :index, Path.expand(\"index.html.eex\", __DIR__), [:assigns])",
                "other" to "Other.function_from_file(:def, :index, Path.expand(\"index.html.eex\", __DIR__), [:assigns])"
            )
        )
    }

    private fun views(vararg bodies: Pair<String, String>): Map<String, String?> =
        bodies.associate { (spelling, body) ->
            myFixture.addFileToProject("$spelling/page_view.ex", "defmodule ${spelling.replaceFirstChar(Char::uppercase)}View do\n  $body\nend\n")
            val template = myFixture.addFileToProject("$spelling/index.html.eex", "<%= @title %>\n")
            val elixirFile = template.viewProvider.getPsi(ElixirLanguage) as ElixirFile

            spelling to elixirFile.viewFile()?.name
        }
}
