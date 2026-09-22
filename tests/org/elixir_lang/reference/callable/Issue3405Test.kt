package org.elixir_lang.reference.callable

import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.PlatformTestCase

/**
 * https://github.com/intellij-elixir/intellij-elixir/issues/3405
 */
class Issue3405Test : PlatformTestCase() {
    /**
     * A `for` comprehension generating ExUnit `test` calls used to put `ex_unit.Case.isChild` and
     * `For.treeWalkDown` on the same walk: deciding whether a generated `test` is an `ExUnit.Case`
     * child resolved that call, and resolving it walked back up into the `for` and down into its
     * children again. `Case.isChild` now answers from [org.elixir_lang.psi.CallableTable] instead of
     * resolving the candidate call's own reference, so there is nothing left to resolve back into the
     * `for`. Neither `RecursionManager.disableMissedCacheAssertions` nor
     * `disableAssertOnRecursionPrevention` is set here, so a recursion the platform's own guard has to
     * prevent, or a `CachedValue` it leaves uncacheable, fails this test on its own; the resolution is
     * also checked to actually land on `random_pool_name/0`, not merely to terminate.
     */
    fun testResolvingInsideForGeneratedExUnitTestResolvesToRandomPoolName() {
        // `ex_unit_case.ex` has to be resolvable, or `Case.isChild` answers `false` off the
        // `ExUnit.Case` lookup and the walk this pins is never entered.
        val reference = myFixture
            .getReferenceAtCaretPosition("for_generated_ex_unit_tests.exs", "ex_unit_case.ex")
        assertInstanceOf(reference, PsiPolyVariantReference::class.java)

        val resolved = (reference as PsiPolyVariantReference)
            .multiResolve(false)
            .filter { it.isValidResult }
            .mapNotNull { it.element?.text }

        assertEquals(
            "`random_pool_name()` inside a `for`-generated ExUnit `test` must resolve to its `defp`",
            listOf("defp random_pool_name do\n    \"pool\"\n  end"),
            resolved
        )
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/reference/callable/issue_3405"
}
