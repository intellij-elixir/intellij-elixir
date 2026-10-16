package org.elixir_lang.reference.callable

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.reference.Callable

/**
 * Two modules delegating to each other overflow the walk's stack, which the walk catches and reports. This pins that
 * report and what the use resolves to, not a fix.
 */
class MutualDelegationTest : PlatformTestCase() {
    fun testTwoModulesDelegatingToEachOther() {
        myFixture.addFileToProject("a.ex", "defmodule A do\n  defdelegate f(x), to: B\nend\n")
        myFixture.addFileToProject("b.ex", "defmodule B do\n  defdelegate f(x), to: A\nend\n")
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: A.<caret>f(1)\nend\n")

        val (resolved, errors) = captureLoggedErrors {
            ReadAction.computeBlocking<Pair<List<String>, String?>, Throwable> {
                val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference

                assertInstanceOf(reference, Callable::class.java)

                reference.multiResolve(false).map { it.element!!.text } to reference.resolve()?.text
            }
        }

        assertEquals(emptyList<String>() to null, resolved)
        assertEquals(
            listOf("org.elixir_lang.reference.resolver.Callable" to "StackOverflowError when annotating Call"),
            errors.map { it.category to it.title }
        )
    }
}
