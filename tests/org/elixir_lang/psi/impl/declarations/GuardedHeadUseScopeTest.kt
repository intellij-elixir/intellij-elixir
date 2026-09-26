package org.elixir_lang.psi.impl.declarations

import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call

/**
 * In `def defp(x) when is_atom(x), do: x` every `x` is one variable: the head binds it, and the guard and the body
 * use it. The head is itself shaped like a clause - a function named `defp` - so its parameter must still be scoped
 * to the whole outer clause, as it was when the outer clause was a `defmacro`.
 */
class GuardedHeadUseScopeTest : PlatformTestCase() {
    fun testAClauseShapedHeadsParameterIsInScopeInTheBody() {
        myFixture.configureByText(
            "clause_shaped_head.ex",
            """
            defmodule Scopes do
              def defp(function_parameter) when is_atom(function_parameter), do: function_parameter
              defmacro defp(macro_parameter) when is_atom(macro_parameter), do: macro_parameter
            end
            """.trimIndent()
        )

        val text = myFixture.file.text

        fun callAt(offset: Int): Call = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(offset), Call::class.java)!!

        val actual = listOf("function_parameter", "macro_parameter").joinToString("\n") { name ->
            val declaration = callAt(text.indexOf(name))
            val bodyUse = callAt(text.lastIndexOf(name))

            "$name -> ${PsiSearchScopeUtil.isInScope(declaration.useScope, bodyUse)}"
        }

        assertEquals("function_parameter -> true\nmacro_parameter -> true", actual)
    }
}
