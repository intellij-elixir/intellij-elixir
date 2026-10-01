package org.elixir_lang.model.psi.module

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.renameTargetsAtCaret
import org.elixir_lang.code_insight.singleTargetPsiUsagesAtCaret
import org.elixir_lang.psi.call.Call

/** Find Usages and Rename from a module or protocol declared inside another module reach its uses, and no other module's. */
class NestedModuleUsagesTest : PlatformTestCase() {
    fun testQualifiedUseFromAnotherFile() = assertUsages(
        "defmodule A do\n  defmodule In<caret>ner do\n    def f, do: 1\n  end\nend\n\n" +
            "defmodule B do\n  defmodule Inner do\n    def f, do: 1\n  end\nend\n",
        "defmodule Caller do\n  def g, do: A.Inner.f()\n  def h, do: B.Inner.f()\nend\n",
        "caller.ex: A.Inner.f()",
    )

    fun testBareUseInsideTheEnclosingModule() = assertUsages(
        "defmodule A do\n  defmodule In<caret>ner do\n    def f, do: 1\n  end\n\n  def g, do: Inner.f()\nend\n\n" +
            "defmodule B do\n  defmodule Inner do\n    def f, do: 1\n  end\n\n  def h, do: Inner.f()\nend\n",
        "defmodule Caller do\n  def g, do: A.Inner.f()\nend\n",
        "caller.ex: A.Inner.f()",
        "declaration.ex: Inner.f()",
    )

    fun testNestedProtocol() = assertUsages(
        "defmodule A do\n  defprotocol <caret>P do\n    def f(x)\n  end\n\n  def g(x), do: P.f(x)\n  def h, do: 1\nend\n",
        "defmodule Caller do\n  def g(x), do: A.P.f(x)\n  def h, do: A.h()\nend\n",
        "caller.ex: A.P.f(x)",
        "declaration.ex: P.f(x)",
    )

    fun testRenameOffersTheNameAsDeclared() {
        myFixture.configureByText("declaration.ex", "defmodule A do\n  defmodule In<caret>ner do\n  end\nend\n")

        assertEquals(listOf("Inner"), myFixture.renameTargetsAtCaret().map { it.targetName })
    }

    private fun assertUsages(declaration: String, caller: String, vararg expected: String) {
        myFixture.addFileToProject("caller.ex", caller)
        myFixture.configureByText("declaration.ex", declaration)

        assertEquals(
            expected.sorted(),
            myFixture.singleTargetPsiUsagesAtCaret(project).filterNot { it.declaration }.map { usage ->
                val leaf = usage.file.findElementAt(usage.range.startOffset)!!
                val call = generateSequence(PsiTreeUtil.getParentOfType(leaf, Call::class.java, false)) { it.parent as? Call }.last()

                "${usage.file.name}: ${call.text}"
            }.sorted(),
        )
    }
}
