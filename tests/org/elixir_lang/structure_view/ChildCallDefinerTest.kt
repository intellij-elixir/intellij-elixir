package org.elixir_lang.structure_view

import com.intellij.psi.ResolveState
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.Modular

/** The [ChildCall] entry each `def*` clause reaches first: a guard is listed among the macros. */
class ChildCallDefinerTest : PlatformTestCase() {
    fun testEachDefinersEntry() {
        val file = myFixture.configureByText(
            "definers.ex",
            """
            defmodule Definers do
              def public_function(a), do: a
              defp private_function(a), do: a
              defmacro public_macro(a), do: a
              defmacrop private_macro(a), do: a
              defguard public_guard(a) when is_integer(a)
              defguardp private_guard(a) when is_integer(a)
            end
            """.trimIndent(),
        ) as ElixirFile
        val entries = Modular.callDefinitionClauseCallSequence(file.modulars().single()).associate { call ->
            CallDefinitionClause.definer(call)!!.keyword to
                ChildCall.ENTRIES.firstOrNull { it.matches(call, ResolveState.initial()) }?.name
        }

        assertEquals(
            mapOf(
                "def" to "function",
                "defp" to "function",
                "defmacro" to "macro",
                "defmacrop" to "macro",
                "defguard" to "macro",
                "defguardp" to "macro",
            ),
            entries,
        )
    }
}
