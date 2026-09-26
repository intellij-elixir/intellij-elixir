package org.elixir_lang.model.psi

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.model.psi.atom.AtomSymbol
import org.elixir_lang.model.psi.function.FunctionSymbol
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.macroChildCallSequence

/** A clause's Symbols name its kind; a guard is a macro, as Elixir defines it with `defmacro`. */
class ClauseKindTest : PlatformTestCase() {
    fun testEveryClauseFormNamesItsKind() {
        myFixture.configureByText(
            "kinds.ex",
            """
            defmodule Kinds do
              def public_function(a), do: a
              defmacro public_macro(a), do: a
              defguard public_guard(a) when a > 0
              defguardp private_guard(a) when a > 0
            end
            """.trimIndent()
        )

        val module = myFixture.file.children.filterIsInstance<Call>().single()
        val actual = module.macroChildCallSequence().joinToString("\n") { clause ->
            val functionSymbol = FunctionSymbol.fromClause(clause).single()
            val atomSymbol = AtomSymbol.fromClause(clause).single()

            "${functionSymbol.name}: FunctionSymbol macro=${functionSymbol.macro}, AtomSymbol macro=${atomSymbol.macro}"
        }

        assertEquals(
            """
            public_function: FunctionSymbol macro=false, AtomSymbol macro=false
            public_macro: FunctionSymbol macro=true, AtomSymbol macro=true
            public_guard: FunctionSymbol macro=true, AtomSymbol macro=true
            private_guard: FunctionSymbol macro=true, AtomSymbol macro=true
            """.trimIndent(),
            actual
        )
    }
}
