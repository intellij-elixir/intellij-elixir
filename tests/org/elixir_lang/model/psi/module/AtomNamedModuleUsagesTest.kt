package org.elixir_lang.model.psi.module

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
import org.elixir_lang.code_insight.completionAttemptAtCaret
import org.elixir_lang.code_insight.gotoDeclarationDestinationAtCaret
import org.elixir_lang.code_insight.singleTargetPsiUsagesAtCaret
import org.elixir_lang.psi.call.Call

/** Find Usages and Ctrl+click from a module declared by its atom reach the uses of that module, however they spell it. */
class AtomNamedModuleUsagesTest : PlatformTestCase() {
    fun testAlias() = assertUsages("defmodule F<caret>oo do\n  def f, do: 1\nend\n")

    fun testElixirPrefixedAtom() = assertUsages("defmodule :\"Elixir.F<caret>oo\" do\n  def f, do: 1\nend\n")

    fun testCtrlClickOnAnAtomNameShowsUsages() {
        myFixture.configureByText("declaration.ex", "defmodule :\"Elixir.F<caret>oo\" do\n  def f, do: 1\nend\n")

        myFixture.assertShowUsagesChosenAtCaret()
    }

    fun testQualifyingAtomGoesToItsModule() {
        myFixture.addFileToProject("declaration.ex", "defmodule Foo do\n  def f, do: 1\nend\n")
        myFixture.configureByText("caller.ex", "defmodule Caller do\n  def g, do: :\"Elixir.F<caret>oo\".f()\nend\n")

        val destination = myFixture.gotoDeclarationDestinationAtCaret()!!
        assertEquals("declaration.ex", destination.containingFile.name)
        assertTrue(destination.textRange.startOffset < "defmodule Foo".length)
    }

    /** A declaration's name names a new module, as a declared alias does, so no existing one is offered. */
    fun testDeclarationAtomOffersNoModules() {
        myFixture.addFileToProject("foo.ex", "defmodule Foo do\nend\n")
        myFixture.configureByText("declaration.ex", "defmodule :\"Elixir.F<caret>\" do\nend\n")

        val attempt = myFixture.completionAttemptAtCaret()

        assertEquals(emptyList<String>(), attempt.candidates.orEmpty().filter { "Foo" in it })
        assertEquals("defmodule :\"Elixir.F\" do\nend\n", attempt.text)
    }

    private fun assertUsages(
        declaration: String,
        caller: String = "defmodule Caller do\n  def g, do: Foo.f()\nend\n",
        vararg expected: String = arrayOf("Foo.f()"),
    ) {
        myFixture.addFileToProject("caller.ex", caller)
        myFixture.configureByText("declaration.ex", declaration)

        assertEquals(
            expected.toList(),
            myFixture.singleTargetPsiUsagesAtCaret(project).filterNot { it.declaration }.map { usage ->
                val leaf = usage.file.findElementAt(usage.range.startOffset)!!
                generateSequence(PsiTreeUtil.getParentOfType(leaf, Call::class.java, false)) { it.parent as? Call }.last().text
            },
        )
    }
}
