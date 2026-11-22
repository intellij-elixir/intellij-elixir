package org.elixir_lang.reference.callable

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.reference.Callable

/**
 * Two modules delegating to each other overflow the walk's stack, which the walk catches and reports. This pins that
 * report and what the use resolves to, not a fix.
 */
class MutualDelegationTest : ExpanderSettingTestCase() {
    fun testTwoModulesDelegatingToEachOther() {
        assertEquals(emptyList<String>() to null, resolveAMutualDelegation())
    }

    /**
     * The expander resolves the use as it does a delegation to a module that defines the function: to the delegation,
     * with its target, here the other module's delegation, beside it. The walk for the variables the call may name
     * still runs, and still overflows.
     */
    fun testTwoModulesDelegatingToEachOtherWithTheExpanderOn() = withTheExpanderOn {
        assertEquals(
            listOf("defdelegate f(x), to: B", "defdelegate f(x), to: A") to "defdelegate f(x), to: B",
            resolveAMutualDelegation(),
        )
    }

    /**
     * What `A.f(1)` resolves to, with `A.f/1` and `B.f/1` delegating to each other, once the walk's overflow is asserted
     * as the one error resolving logged.
     */
    private fun resolveAMutualDelegation(): Pair<List<String>, String?> {
        val (resolved, errors) = captureLoggedErrors {
            myFixture.addFileToProject("a.ex", "defmodule A do\n  defdelegate f(x), to: B\nend\n")
            myFixture.addFileToProject("b.ex", "defmodule B do\n  defdelegate f(x), to: A\nend\n")
            myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: A.<caret>f(1)\nend\n")

            ReadAction.computeBlocking<Pair<List<String>, String?>, Throwable> {
                val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference

                assertInstanceOf(reference, Callable::class.java)

                reference.multiResolve(false).map { it.element!!.text } to reference.resolve()?.text
            }
        }

        assertEquals(
            listOf("org.elixir_lang.reference.resolver.Callable" to "StackOverflowError when annotating Call"),
            errors.map { it.category to it.title }
        )

        return resolved
    }
}
