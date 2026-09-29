package org.elixir_lang.reference.callable

import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call

/**
 * A `defdelegate` whose target is an `EEx` function with a non-literal `args` list still reaches that
 * function: its arity cannot be known, but it is still what the delegation points at. The call is
 * wrong-arity so that nothing is valid; a valid head would otherwise hide every invalid result.
 */
class DelegatedEExFunctionTest : PlatformTestCase() {
    fun testDelegationReachesAnEExFunctionWithNonLiteralArguments() {
        myFixture.configureByFiles("delegated_eex_function.ex", "eex_stub.ex")

        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference
        val functionNames = reference.multiResolve(false).mapNotNull { (it.element as? Call)?.functionName() }

        assertTrue(
            "Expected the delegated EEx function among the results, got $functionNames",
            "function_from_string" in functionNames
        )
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/reference/callable/delegated_eex_function"
}
