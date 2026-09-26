package org.elixir_lang.psi

import com.intellij.openapi.application.WriteAction
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.impl.PsiModificationTrackerImpl
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import org.elixir_lang.PlatformTestCase

/** What a module's body holds is listed once per change, however many uses in it ask. */
class ModuleScopeTimingTest : PlatformTestCase() {
    /** Every `@spec` of a long module resolves within budget, as highlighting it does. */
    fun testEverySpecOfAModuleWithManySpecsResolves() {
        val functions = (1..SPECS).joinToString("\n") { "  @spec f$it(term) :: term\n  def f$it(x), do: x" }
        myFixture.configureByText("specs.ex", "defmodule Specs do\n$functions\nend\n")
        val references = PsiTreeUtil
            .collectElements(myFixture.file) { it.reference is org.elixir_lang.reference.CallDefinitionClause }
            .map { it.reference as PsiPolyVariantReference }
        var resolved = 0

        // Measured locally: 124 s when each `@spec` listed the module and classified its calls again.
        PlatformTestUtil.assertTiming("the module is listed again for each @spec", 5_000, 3) {
            // Each attempt must do the work: the resolve cache and the per-module listing would serve the rest.
            WriteAction.run<RuntimeException> {
                (PsiManager.getInstance(project).modificationTracker as PsiModificationTrackerImpl).incCounter()
            }
            PsiManager.getInstance(project).dropResolveCaches()
            resolved = references.count { reference -> reference.multiResolve(false).any { it.isValidResult } }
        }

        assertEquals(SPECS, resolved)
    }

    private companion object {
        const val SPECS = 2_000
    }
}
