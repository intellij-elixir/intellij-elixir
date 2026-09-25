package org.elixir_lang.psi

import com.intellij.openapi.application.WriteAction
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveState
import com.intellij.psi.impl.PsiModificationTrackerImpl
import com.intellij.testFramework.PlatformTestUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call

/** Which function each clause belongs to is worked out once per module, so a long module resolves quickly. */
class ClauseFunctionIndexTest : PlatformTestCase() {
    /** Which function a clause belongs to is asked of each clause on the walk; a long module must not make that deep. */
    fun testACallAfterManyClausesResolves() {
        val clauses = (1..5_000).joinToString("\n") { "  def f$it(x) do\n    x\n  end" }
        myFixture.configureByText("long.ex", "defmodule Long do\n$clauses\n\n  def calls, do: f1(1)\nend\n")

        assertEquals("f1", (referenceAt("f1(1)").resolve() as? Call)?.let {
            CallDefinitionClause.nameArityInterval(it, ResolveState.initial())?.name
        })
    }

    /** A lookup table of many clauses of one function resolves within budget, on the resolve walk. */
    fun testACallOfAFunctionWithManyClausesResolves() {
        val clauses = (1..CLAUSES).joinToString("\n") { "  def ext(\"e$it\"), do: $it" }
        myFixture.configureByText("table.ex", "defmodule Table do\n$clauses\n\n  def calls, do: ext(\"x\")\nend\n")
        val reference = referenceAt("ext(\"x\")")
        var resolved = 0

        // Measured locally: 13.9 s when each clause walked back over the ones before it, 0.5 s in one pass per module,
        // up to 0.8 s beside the other parallel test forks. The budget is for a standard machine and scaled by the
        // CPU's rating, to 1.65 s where these were measured: room for 0.8 s, not for 13.9 s.
        PlatformTestUtil.assertTiming("clauses of one function are worked out more than once per module", 15_000, 3) {
            // Each attempt must do the work: the resolve cache and the per-module index would serve the rest.
            WriteAction.run<RuntimeException> {
                (PsiManager.getInstance(project).modificationTracker as PsiModificationTrackerImpl).incCounter()
            }
            PsiManager.getInstance(project).dropResolveCaches()
            resolved = reference.multiResolve(false).count { it.isValidResult }
        }

        assertEquals(CLAUSES, resolved)
    }

    private companion object {
        const val CLAUSES = 4_000
    }

    private fun referenceAt(fragment: String): PsiPolyVariantReference =
        generateSequence(myFixture.file.findElementAt(myFixture.file.text.indexOf(fragment))) { it.parent }
            .mapNotNull { it.reference as? PsiPolyVariantReference }
            .first()
}
