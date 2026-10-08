package org.elixir_lang.psi.impl.call

import com.intellij.openapi.application.runReadAction
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.lowering.LoweringCounters
import org.elixir_lang.psi.call.Call

/**
 * A call is named by the atom its callee quotes to, as Elixir reads it: `Kernel."def"(...)` calls `def`. What that
 * name resolves to is in [QuotedCallNameResolutionTest].
 */
class CallNameTest : PlatformTestCase() {
    fun testQuotedRemoteNameIsCalledOnItsModule() {
        configure("Kernel.\"def\"(foo, do: 1)\nKernel.def(foo, do: 1)\nKernel.\"defp\"(foo, do: 1)")

        assertEquals(
            listOf(true, true, false),
            runReadAction { calls().map { it.isCalling("Kernel", "def") } }
        )
    }

    fun testQuotedRemoteNameIsCalledWithItsArity() {
        configure("Kernel.\"def\"(foo, do: 1)")

        assertTrue(runReadAction { calls().single().isCalling("Kernel", "def", 2) })
    }

    /** A file whose names are all unquoted ASCII is named from its text, with no lowering. */
    fun testAsciiNamesLowerNothing() {
        configure("defmodule M do\n  def f(a), do: a + g(a)\n  def g(a), do: Enum.map([a], &h/1)\nend\n")

        LoweringCounters.reset()
        LoweringCounters.counting = true
        val names = try {
            runReadAction { allCalls().map { it.functionName() } }
        } finally {
            LoweringCounters.counting = false
        }

        assertTrue(names.toString(), names.containsAll(listOf("defmodule", "def", "+", "g", "map")))
        assertEquals("lowering requests", 0L, LoweringCounters.requests.sum())
    }

    private fun configure(text: String) {
        myFixture.configureByText("call.ex", text)
    }

    /** The outermost call of each top-level expression. */
    private fun calls(): List<Call> =
        allCalls().filter { PsiTreeUtil.getParentOfType(it, Call::class.java) == null }

    private fun allCalls(): List<Call> =
        PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).sortedBy { it.textOffset }
}
