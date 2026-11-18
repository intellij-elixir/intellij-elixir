package org.elixir_lang.model.psi.variable

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiElement
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.ElixirVariable
import org.elixir_lang.psi.call.Call

/**
 * Locks the Symbol-API variable resolution path ([VariableReference.resolveSymbols]) so that a
 * variable usage resolves to its declaration through the Symbol providers alone, independent of the
 * legacy `Call.reference` fallback.
 */
class VariableReferenceResolutionTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/variable"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testResolveVariableUsageToDeclaration() {
        myFixture.configureByFiles("goto_declaration_variable_usage.ex")
        val file = myFixture.file
        val offset = myFixture.caretOffset

        val symbols = ApplicationManager.getApplication().runReadAction(Computable {
            val leaf = file.findElementAt(offset)!!
            VariableReference.resolveSymbols(hostOf(leaf)).toList()
        })

        assertEquals(
            "Variable usage should resolve to exactly one declaration symbol via the Symbol API",
            1,
            symbols.size
        )
        val symbol = symbols.single()
        assertEquals("variable", symbol.name)
        assertEquals(VariableSymbol.Kind.VARIABLE, symbol.kind)
        assertTrue(
            "Resolved declaration should precede the usage (the `variable = 1` binding)",
            symbol.range.startOffset < offset
        )
    }

    fun testResolveParameterUsageToDeclaration() {
        myFixture.configureByFiles("goto_declaration_parameter_usage.ex")
        val file = myFixture.file
        val offset = myFixture.caretOffset

        val symbols = ApplicationManager.getApplication().runReadAction(Computable {
            val leaf = file.findElementAt(offset)!!
            VariableReference.resolveSymbols(hostOf(leaf)).toList()
        })

        assertEquals(
            "Parameter usage should resolve to exactly one declaration symbol via the Symbol API",
            1,
            symbols.size
        )
        assertEquals(VariableSymbol.Kind.PARAMETER, symbols.single().kind)
    }

    /** A `do:` keyword in a head is a pattern, so the variable it binds is a parameter. */
    fun testResolveBlockKeywordParameterUsageToItsParameter() {
        val text = "defmodule M do\n  defmacro unless(condition, do: block) do\n    block\n  end\nend\n"
        myFixture.configureByText("m.ex", text)
        val file = myFixture.file
        val use = text.lastIndexOf("block")

        val symbols = ApplicationManager.getApplication().runReadAction(Computable {
            VariableReference.resolveSymbols(hostOf(file.findElementAt(use)!!)).toList()
        })

        assertEquals(listOf(VariableSymbol.Kind.PARAMETER to text.indexOf("block")), symbols.map { it.kind to it.range.startOffset })
    }

    /**
     * A binding made inside a `quote` block resolves from a use in the same block. Macro bodies are
     * the one place resolution has to look through quoting, and nothing else asserts that it does.
     */
    fun testPlainBindingInsideQuoteIsResolvedFromUsage() {
        myFixture.configureByFiles("plain_binding_inside_quote.ex")
        val file = myFixture.file
        val offset = myFixture.caretOffset

        val symbols = ApplicationManager.getApplication().runReadAction(Computable {
            val leaf = file.findElementAt(offset)!!
            VariableReference.resolveSymbols(hostOf(leaf)).toList()
        })

        assertEquals(
            "A plain `bar = 1` inside a quote should resolve to its binding",
            1,
            symbols.size
        )
        assertEquals("bar", symbols.single().name)
    }

    private fun hostOf(leaf: PsiElement): PsiElement =
        generateSequence(leaf) { it.parent }
            .firstOrNull { it is Call || it is ElixirVariable }
            ?: leaf
}
