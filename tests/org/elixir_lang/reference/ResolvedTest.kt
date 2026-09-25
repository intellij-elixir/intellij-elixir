package org.elixir_lang.reference

import com.intellij.openapi.application.WriteAction
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.impl.PsiModificationTrackerImpl
import com.intellij.testFramework.PlatformTestUtil
import org.elixir_lang.PlatformTestCase

/** A reference with several valid results resolves to the first preferred of them, not to nothing. */
class ResolvedTest : PlatformTestCase() {
    fun testAModuleDefinedInTwoFilesResolves() {
        myFixture.addFileToProject("first.ex", "defmodule Twice do\nend\n")
        myFixture.addFileToProject("second.ex", "defmodule Twice do\nend\n")
        myFixture.configureByText("use.ex", "defmodule User do\n  def calls, do: Twice\nend\n")

        val reference = referenceAt("Twice")

        assertTrue("Expected two valid results", reference.multiResolve(false).count { it.isValidResult } >= 2)
        assertNotNull(reference.resolve())
    }

    fun testACaptureOfAMultiClauseFunctionResolves() {
        myFixture.configureByText(
            "capture.ex",
            "defmodule Definer do\n  def snoc(q, nil), do: q\n  def snoc(q, x), do: {q, x}\nend\n\n" +
                "defmodule Caller do\n  def capture, do: &Definer.snoc/2\nend\n"
        )

        val reference = referenceAt("snoc/2")

        assertTrue("Expected two valid results", reference.multiResolve(false).count { it.isValidResult } >= 2)
        assertNotNull(reference.resolve())
    }

    /** Which function a clause belongs to is asked of each clause on the walk; a long module must not make that deep. */
    fun testACallAfterManyClausesResolves() {
        val clauses = (1..5_000).joinToString("\n") { "  def f$it(x) do\n    x\n  end" }
        myFixture.configureByText("long.ex", "defmodule Long do\n$clauses\n\n  def calls, do: f1(1)\nend\n")

        assertEquals("f1", (referenceAt("f1(1)").resolve() as? org.elixir_lang.psi.call.Call)?.let {
            org.elixir_lang.psi.CallDefinitionClause.nameArityInterval(it, com.intellij.psi.ResolveState.initial())?.name
        })
    }

    /** A lookup table of many clauses of one function resolves within budget, on the resolve walk. */
    fun testACallOfAFunctionWithManyClausesResolves() {
        val clauses = (1..CLAUSES).joinToString("\n") { "  def ext(\"e$it\"), do: $it" }
        myFixture.configureByText("table.ex", "defmodule Table do\n$clauses\n\n  def calls, do: ext(\"x\")\nend\n")
        val reference = referenceAt("ext(\"x\")")
        var resolved = 0

        // Measured locally: 13.9 s when each clause walked back over the ones before it, 0.5 s in one pass per module,
        // up to 0.8 s beside the other parallel test forks; the budget leaves room for that, not for the 13.9 s.
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
