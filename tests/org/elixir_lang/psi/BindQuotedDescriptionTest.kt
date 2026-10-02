package org.elixir_lang.psi

import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageViewTypeLocation
import org.elixir_lang.PlatformTestCase

/** A key inside `quote`'s `bind_quoted:` list binds a variable in the quote, whichever way `bind_quoted` is spelled. */
class BindQuotedDescriptionTest : PlatformTestCase() {
    fun testBindQuotedKey() = assertQuoteBoundVariable("quote bind_quoted: [<caret>a: 1], do: a")

    fun testQuotedBindQuotedKey() = assertQuoteBoundVariable("quote \"bind_quoted\": [<caret>a: 1], do: a")

    private fun assertQuoteBoundVariable(quote: String) {
        myFixture.configureByText("bind_quoted.ex", "defmodule M do\n  def f do\n    $quote\n  end\nend\n")
        val key = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), ElixirKeywordKey::class.java)!!

        assertEquals("quote bound variable", ElementDescriptionUtil.getElementDescription(key, UsageViewTypeLocation.INSTANCE))
    }
}
