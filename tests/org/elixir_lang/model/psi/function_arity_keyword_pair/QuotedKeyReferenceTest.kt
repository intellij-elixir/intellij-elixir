package org.elixir_lang.model.psi.function_arity_keyword_pair

import com.intellij.model.psi.PsiSymbolReference
import com.intellij.model.psi.PsiSymbolReferenceService
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.enclosingCallAtCaret
import org.elixir_lang.model.psi.function.FunctionSymbol

/** An `import :only` key names the function its atom names, however the key is written. */
@Suppress("UnstableApiUsage")
class QuotedKeyReferenceTest : PlatformTestCase() {
    fun testQuotedKeyResolvesToFunction() = assertResolvesToPerform("\"per<caret>form\": 0")

    fun testEscapedKeyResolvesToFunction() = assertResolvesToPerform("\"\\x70er<caret>form\": 0")

    /** A key that spells no atom until the code runs names no function. */
    fun testInterpolatedKeyHasNoReference() = assertEmpty(referencesAtCaret("\"<caret>#{x}\": 0"))

    private fun assertResolvesToPerform(pair: String) {
        val symbols = referencesAtCaret(pair).flatMap { it.resolveReference() }

        assertTrue(
            "Expected `$pair` to resolve to Exporter.perform/0, got $symbols",
            symbols.filterIsInstance<FunctionSymbol>().any { it.name == "perform" && it.moduleName == "Exporter" }
        )
    }

    private fun referencesAtCaret(pair: String): List<PsiSymbolReference> {
        myFixture.configureByText(
            "import.ex",
            "defmodule Exporter do\n  def perform, do: :ok\nend\n\n" +
                "defmodule Importer do\n  import Exporter, only: [$pair]\nend\n"
        )
        val offset = myFixture.caretOffset

        return PsiSymbolReferenceService.getService()
            .getReferences(myFixture.enclosingCallAtCaret()!!)
            .filter { it.absoluteRange.containsOffset(offset) }
    }
}
