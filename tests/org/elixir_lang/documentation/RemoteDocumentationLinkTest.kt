package org.elixir_lang.documentation

import org.elixir_lang.PlatformTestCase

/** A `Mod.fun/arity` link in docs reaches what a call `Mod.fun(...)` does: what the module exports. */
class RemoteDocumentationLinkTest : PlatformTestCase() {
    fun testALinkReachesOnlyWhatTheModuleExports() {
        myFixture.configureByText(
            "linked.ex",
            """
            defmodule Linked do
              def shown(a), do: helper(a)
              defp helper(a), do: a
            end
            """.trimIndent()
        )

        val provider = ElixirDocumentationProvider()
        val reached = listOf("Linked.shown/1", "Linked.helper/1", "Linked.is_nil/1").associateWith { link ->
            provider.getDocumentationElementForLink(psiManager, link, myFixture.file)?.text?.lineSequence()?.first()
        }

        assertEquals(
            mapOf("Linked.shown/1" to "def shown(a), do: helper(a)", "Linked.helper/1" to null, "Linked.is_nil/1" to null),
            reached
        )
    }
}
