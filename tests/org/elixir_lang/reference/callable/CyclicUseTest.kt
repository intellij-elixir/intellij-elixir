package org.elixir_lang.reference.callable

import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.psi.UnqualifiedNoArgumentsCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.reference.Callable

/**
 * Resolving an unqualified, argument-less call from a module whose `use` chain contains a cycle.
 *
 * `resolveInScope` catches [StackOverflowError] and reports it through
 * [org.elixir_lang.errorreport.Logger], so an overflow here never surfaces as a thrown error: the
 * reference silently resolves to nothing and a "StackOverflowError when annotating Call" error is
 * logged. That logged title is what these tests assert on - it is the exact string the crash reports
 * carry, and the only observable the catch leaves behind.
 */
class CyclicUseTest : ExpanderSettingTestCase() {
    /**
     * The cycle is reached through the call-definition-clause scope walk that `resolveUnqualified`
     * drives: `view/1` has a `:cyclic` clause calling `admin_view/1`, which calls `view/1` back.
     * Clause bodies are not evaluated, so both clauses are walked and the pair closes a loop.
     * Terminating depends on the walk checking its visited-element record before recursing, not
     * merely recording it.
     */
    fun testUnqualifiedNoArgumentsCallResolvesThroughCyclicUse() {
        myFixture.configureByFiles("injected.ex", "cyclic_web.ex")

        val (resolveResults, loggedErrors) = captureLoggedErrors {
            callableReferenceAtCaret().multiResolve(false)
        }

        assertEmpty(
            "resolving through a cyclic `use` chain overflowed the stack",
            loggedErrors.filter { it.title == STACK_OVERFLOW_TITLE }
        )
        assertNotEmpty(resolveResults.toList())
    }

    /**
     * The control for the test above: a module with no `use` at all, so it shares nothing with the
     * cycle and stays green under any change to the cycle-breaking guards. That is what makes a red
     * result there evidence about recursion rather than about resolution having been broken outright.
     */
    fun testUnqualifiedNoArgumentsCallWithoutUseIsUnaffected() {
        myFixture.configureByFiles("plain.ex")

        val (resolveResults, loggedErrors) = captureLoggedErrors {
            callableReferenceAtCaret().multiResolve(false)
        }

        assertEmpty(loggedErrors.filter { it.title == STACK_OVERFLOW_TITLE })
        assertEquals(1, resolveResults.size)
        assertEquals("def plain do\n    :ok\n  end", resolveResults.single().element!!.text)
    }

    /**
     * The same cycle reached the way the crash reports were: `plugin.xml` registers
     * [org.elixir_lang.annotator.Callable] as an Elixir annotator and it resolves every plain call it
     * visits, so running the highlighting pass drives the annotator door above the reference that
     * calling [Callable.multiResolve] directly cannot exercise.
     */
    fun testAnnotatingACallThroughCyclicUseTerminates() {
        myFixture.configureByFiles("injected.ex", "cyclic_web.ex")

        val (_, loggedErrors) = captureLoggedErrors { myFixture.doHighlighting() }

        assertEmpty(
            "annotating through a cyclic `use` chain overflowed the stack",
            loggedErrors.filter { it.title == STACK_OVERFLOW_TITLE }
        )
    }

    /**
     * The [Callable] reference of the call left of the caret, which the fixtures place immediately
     * after the call's name.
     *
     * Both assertions are the point rather than precautions. The crash reports are raised while
     * resolving an unqualified, argument-less call, and the frame that catches the overflow is
     * reached from [Callable.multiResolve] - so a fixture that drifted into producing a different
     * call shape, or an element carrying some other reference, would pin a path other than the
     * reported one. The two reports of this crash name different implementations - one the matched
     * call, one the unmatched - and these fixtures produce the unmatched one. That difference is
     * parse context above the reference, which is the same class for both, so it does not change
     * which resolve path is exercised.
     */
    private fun callableReferenceAtCaret(): Callable {
        val identifier = myFixture.file.findElementAt(myFixture.caretOffset - 1)!!
        val call = PsiTreeUtil.getParentOfType(identifier, Call::class.java)!!

        assertInstanceOf(call, UnqualifiedNoArgumentsCall::class.java)

        return assertInstanceOf(call.reference, Callable::class.java)
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/reference/callable/cyclic_use"

    companion object {
        private const val STACK_OVERFLOW_TITLE = "StackOverflowError when annotating Call"
    }
}
