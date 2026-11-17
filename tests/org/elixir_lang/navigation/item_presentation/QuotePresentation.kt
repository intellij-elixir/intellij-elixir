package org.elixir_lang.navigation.item_presentation

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.QUOTE
import org.elixir_lang.psi.call.name.Module.KERNEL

/** The item presentation's text for [quote], written in a function body. */
internal fun CodeInsightTestFixture.quotePresentation(quote: String): String? {
    configureByText("quote.ex", "defmodule M do\n  def f do\n    $quote\n  end\nend\n")

    val call = PsiTreeUtil
        .findChildrenOfType(file, Call::class.java)
        .single { it.isCalling(KERNEL, QUOTE) }

    return Quote(null, call).presentableText
}
