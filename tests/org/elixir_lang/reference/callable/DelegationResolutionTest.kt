package org.elixir_lang.reference.callable

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.ResolveState
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call

/** What a call resolves to through a `defdelegate`: the head decides what this module declares. */
class DelegationResolutionTest : PlatformTestCase() {
    /** `as:` renames the target, so the target's own name does not match the call, but it is still where the call goes. */
    fun testRenamedTargetIsKept() {
        myFixture.configureByFile("delegate_as.ex")

        val declaredNames = resolveResultsAtCaret().filter { it.isValidResult }.mapNotNull { declaredName(it) }

        assertTrue("Expected the renamed target `fetch!` among the valid results, got $declaredNames", "fetch!" in declaredNames)
    }

    /** `fo(1)` names nothing: the `foo` head only starts with it, so what `foo` delegates to cannot make it valid. */
    fun testPrefixOfTheHeadResolvesToNothingValid() {
        myFixture.configureByFile("delegate_prefix.ex")

        val valid = resolveResultsAtCaret().filter { it.isValidResult }.mapNotNull { declaredName(it) }

        assertTrue("Expected nothing valid for `fo(1)`, got $valid", valid.isEmpty())
    }

    /** `defdelegate foo(x)` forwards only `foo/1`, even when the target's default argument also accepts `foo/2`. */
    fun testArityTheHeadDoesNotForwardResolvesToNothingValid() {
        myFixture.configureByFile("delegate_default_arguments.ex")

        val valid = resolveResultsAtCaret().filter { it.isValidResult }.mapNotNull { declaredName(it) }

        assertTrue("Expected nothing valid for `foo(1, 2)`, got $valid", valid.isEmpty())
    }

    private fun resolveResultsAtCaret(): List<ResolveResult> =
        (myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference).multiResolve(false).toList()

    private fun declaredName(resolveResult: ResolveResult): String? =
        (resolveResult.element as? Call)
            ?.takeIf { CallDefinitionClause.`is`(it) }
            ?.let { CallDefinitionClause.nameArityInterval(it, ResolveState.initial())?.name }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/reference/callable/delegation_resolution"
}
