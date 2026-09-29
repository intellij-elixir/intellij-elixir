package org.elixir_lang.psi

import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.PlatformTestCase

/** A `quote` inside an expression in a module body is a value the module never runs, so it defines nothing there. */
class QuotedExpressionScopeTest : PlatformTestCase() {
    fun testADefinitionInAQuotedValueIsNotTheModules() {
        myFixture.configureByText(
            "quoted_value.ex",
            """
            defmodule QuotedValue do
              IO.inspect(quote do
                def injected, do: 1
              end)

              def f, do: inj<caret>ected()
            end
            """.trimIndent()
        )

        val reference = generateSequence(myFixture.file.findElementAt(myFixture.caretOffset)) { it.parent }
            .mapNotNull { it.reference as? PsiPolyVariantReference }
            .first()

        assertEquals(emptyList<Any>(), reference.multiResolve(false).filter { it.isValidResult })
    }
}
