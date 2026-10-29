package org.elixir_lang.reference.callable

import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.PlatformTestCase

/**
 * https://github.com/intellij-elixir/intellij-elixir/issues/3405
 */
class Issue3405Test : PlatformTestCase() {
    override fun setUp() {
        super.setUp()

        // The cycle below is prevented rather than absent, so `mayCacheNow()` is false and the test
        // fixture's default `assertOnMissedCache` would fail before any assertion here ran.
        // `RecursionManager` documents this switch for "tests that check that the stack isn't
        // overflown on invalid code", which is what this is. Production sets neither flag: there,
        // `doPreventingRecursion` returns `null` and the reference simply resolves to nothing.
        RecursionManager.disableMissedCacheAssertions(testRootDisposable)
        RecursionManager.disableAssertOnRecursionPrevention(testRootDisposable)
    }

    /**
     * A `for` comprehension generating ExUnit `test` calls puts `ex_unit.Case.isChild` and
     * `For.treeWalkDown` on the same walk: deciding whether a generated `test` is an `ExUnit.Case`
     * child resolves that call, and resolving it walks back up into the `for` and down into its
     * children again.
     *
     * Neither hinge's `hasBeenVisited` guard closes this. Both read a `ResolveState`, and the
     * reference boundary between them - `PsiPolyVariantReference.multiResolve` - carries none, so
     * the far side re-enters at `LegacyWalkSource.resolveResults` and starts from
     * `ResolveState.initial()`. Seeding that fresh state with the entrance is enough while the `for`
     * body holds one generated `test`; with two, the walk reaches the sibling, which is on no
     * visited set, and the cycle closes. The reporter's file had two.
     *
     * What contains it is the element-keyed `RecursionManager` guard on
     * `LegacyWalkSource.resolveInScope`, which is scoped to the thread's stack rather
     * than to a `ResolveState` and so survives that boundary. Removing it puts this fixture back on
     * the reported errors.
     *
     * The plugin catches its own overflow and reports it through
     * [org.elixir_lang.errorreport.Logger], so termination is asserted on what was logged rather
     * than on a `StackOverflowError` reaching the test.
     */
    fun testResolvingInsideForGeneratedExUnitTestTerminates() {
        val (resolveResults, loggedErrors) = captureLoggedErrors {
            // `ex_unit_case.ex` has to be resolvable, or `Case.isChild` answers `false` off the
            // `ExUnit.Case` lookup and the walk this pins is never entered.
            val reference = myFixture
                .getReferenceAtCaretPosition("for_generated_ex_unit_tests.exs", "ex_unit_case.ex")
            assertInstanceOf(reference, PsiPolyVariantReference::class.java)

            (reference as PsiPolyVariantReference).multiResolve(false)
        }

        val overflows = loggedErrors.filter { loggedError ->
            "StackOverflow" in loggedError.message || "StackOverflow" in (loggedError.title ?: "")
        }

        assertEmpty("resolving through a `for`-generated ExUnit `test` overflowed the stack", overflows)
        assertNotNull(resolveResults)
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/reference/callable/issue_3405"
}
