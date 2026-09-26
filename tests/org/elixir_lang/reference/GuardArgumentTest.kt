package org.elixir_lang.reference

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.model.psi.variable.VariableSymbol
import org.elixir_lang.psi.call.Call

/**
 * A guard evaluates its arguments as a function does, so a name passed to one is read as a function's argument is,
 * where a `defmacro`'s argument, received unevaluated, may be a new variable. The function and macro rows are the
 * controls; the guard rows must match the function rows.
 */
class GuardArgumentTest : PlatformTestCase() {
    fun testANamePassedToAGuardIsAUseAsItIsForAFunction() {
        myFixture.configureByText(
            "guard_argument.ex",
            """
            defmodule GuardArgument do
              defguard is_even(n) when rem(n, 2) == 0
              defmacro binds(name), do: name
              def plain(n), do: n

              def guarded(guard_variable) do
                {is_even(guard_variable), is_even(unbound_guard_name)}
              end

              def called(plain_variable) do
                {plain(plain_variable), plain(unbound_plain_name)}
              end

              def bound(macro_variable) do
                {binds(macro_variable), binds(new_macro_name)}
              end
            end
            """.trimIndent()
        )

        val text = myFixture.file.text
        val names = listOf(
            "guard_variable", "unbound_guard_name",
            "plain_variable", "unbound_plain_name",
            "macro_variable", "new_macro_name"
        )
        val actual = names.joinToString("\n") { name ->
            val use = text.lastIndexOf("($name)") + 1
            val call = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(use), Call::class.java)!!
            val targets = (call.reference as? PsiPolyVariantReference)
                ?.multiResolve(false)
                ?.filter { it.isValidResult }
                ?.mapNotNull { it.element?.textOffset }
                .orEmpty()
            val resolution = when {
                targets.isEmpty() -> "unresolved"
                targets == listOf(use) -> "binds here"
                else -> "resolves to its binding"
            }

            "$name -> $resolution, ${VariableSymbol.classify(call)}"
        }

        assertEquals(
            """
            guard_variable -> resolves to its binding, null
            unbound_guard_name -> unresolved, null
            plain_variable -> resolves to its binding, null
            unbound_plain_name -> unresolved, null
            macro_variable -> resolves to its binding, VARIABLE
            new_macro_name -> unresolved, VARIABLE
            """.trimIndent(),
            actual
        )
    }
}
