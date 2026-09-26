package org.elixir_lang.psi.impl

import com.intellij.psi.ResolveState
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.UnqualifiedNoParenthesesCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.call.macroChildCallSequence

/**
 * A public clause is exported and a private one is not. A public guard is exported as a macro is: Elixir lists it in
 * `__info__(:macros)`.
 */
class ClauseExportTest : PlatformTestCase() {
    fun testAPublicClauseIsExportedAtItsArity() {
        myFixture.configureByText(
            "exports.ex",
            """
            defmodule Exports do
              def public_function(a), do: a
              defp private_function(a), do: a
              defmacro public_macro(a), do: a
              defmacrop private_macro(a), do: a
              defguard public_guard(a) when a > 0
              defguardp private_guard(a) when a > 0
            end
            """.trimIndent()
        )

        val module = myFixture.file.children.filterIsInstance<Call>().single()
        val actual = module.macroChildCallSequence().joinToString("\n") { call ->
            val clause = call as UnqualifiedNoParenthesesCall<*>

            "${call.text.substringBefore("(")} -> ${ElixirPsiImplUtil.isExported(clause)} ${clause.exportedArity(ResolveState.initial())}"
        }

        assertEquals(
            """
            def public_function -> true 1
            defp private_function -> false -1
            defmacro public_macro -> true 1
            defmacrop private_macro -> false -1
            defguard public_guard -> true 1
            defguardp private_guard -> false -1
            """.trimIndent(),
            actual
        )
    }
}
