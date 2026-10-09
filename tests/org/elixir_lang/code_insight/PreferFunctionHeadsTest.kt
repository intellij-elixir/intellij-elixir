package org.elixir_lang.code_insight

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call

/** Completion shows a function by the same clause Parameter Info does. */
class PreferFunctionHeadsTest : PlatformTestCase() {
    fun testABodilessHeadSpeaksForTheClauses() =
        assertEquals("def f(list, acc \\\\ [])", chosen("def f(list, acc \\\\ [])\ndef f([], acc), do: acc\ndef f([x | xs], acc), do: f(xs, acc)"))

    /** With no head, the clause that declares the defaults speaks, not the first. */
    fun testTheClauseThatDeclaresTheDefaultsSpeaksWhereThereIsNoHead() =
        assertEquals("def f([], acc \\\\ [])", chosen("def f([x | xs], acc), do: f(xs, acc)\ndef f([], acc \\\\ []), do: acc"))

    fun testTheFirstClauseSpeaksWhereNoneDeclaresDefaults() =
        assertEquals("def f([x | xs], acc)", chosen("def f([x | xs], acc), do: f(xs, acc)\ndef f([], acc), do: acc"))

    /** Completion offers one entry per name, so a clause of another function does not speak for the first. */
    fun testAClauseOfAnotherArityDoesNotSpeakForTheFirst() =
        assertEquals("def f(a)", chosen("def f(a), do: a\ndef f(a, b, c \\\\ 1), do: a"))

    /** An open arity and a closed one are two definitions, as Parameter Info counts them. */
    fun testAnOpenArityDoesNotShareADefinitionWithAClosedOne() =
        assertEquals(
            "def f(a, b, unquote_splicing(rest))",
            chosen("def f(a, b, unquote_splicing(rest)), do: a\ndef f(a, b \\\\ 1), do: a")
        )

    private fun chosen(body: String): String {
        myFixture.configureByText("heads.ex", "defmodule M do\n$body\nend\n")

        val clauses = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).filter { it.functionName() == "def" }

        return preferFunctionHeads(clauses).getValue("f").text.substringBefore(", do:").trim()
    }
}
