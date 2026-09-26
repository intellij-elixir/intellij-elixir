package org.elixir_lang.model.psi.function

import com.intellij.model.psi.PsiSymbolReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.model.psi.atom.AtomSymbol
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.call.Call

/**
 * A function reads as its declaration's head wherever the platform labels it - a Go To chooser, the Find Usages and
 * Rename titles - whichever form declares it and whether it is reached as a call or an `apply/3` atom.
 */
class DeclarationLabelTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/callable_declaration"

    fun testEachFormIsLabelledByItsHead() {
        myFixture.copyFileToProject("eex.ex")
        myFixture.configureByText(
            "labels.ex",
            """
            defmodule Labelled do
              require EEx

              def clause(a, b), do: {a, b}
              defdelegate delegated(q, x), to: Missing
              EEx.function_from_string(:def, :rendered, "", [:assigns])
            end

            defmodule Caller do
              def applies, do: {apply(Labelled, :clause, [1, 2]), apply(Labelled, :delegated, [1, 2]), apply(Labelled, :rendered, [1])}
            end
            """.trimIndent()
        )

        val calls = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java)
        val functionLabels = listOf("def clause", "defdelegate delegated", "EEx.function_from_string").map { start ->
            FunctionSymbol.fromDeclaration(calls.first { it.text.startsWith(start) }).single().presentation().presentableText
        }
        val atomLabels = listOf(":clause", ":delegated", ":rendered").map { text ->
            val atom = PsiTreeUtil.findChildrenOfType(myFixture.file, ElixirAtom::class.java).first { it.text == text && it.textOffset > myFixture.file.text.indexOf("defmodule Caller") }
            (atom.reference as PsiSymbolReference).resolveReference().filterIsInstance<AtomSymbol>().single().presentation().presentableText
        }
        val expected = listOf("def clause(a, b)", "defdelegate delegated(q, x)", "def rendered(assigns)")

        assertEquals(expected + expected, functionLabels + atomLabels)
    }
}
