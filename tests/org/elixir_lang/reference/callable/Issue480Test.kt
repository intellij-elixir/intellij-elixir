package org.elixir_lang.reference.callable

import org.elixir_lang.psi.CallDefinitionClause.`is`
import org.elixir_lang.psi.CallDefinitionClause.nameArityInterval
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.NameArityInterval
import org.elixir_lang.psi.ArityInterval
import com.intellij.psi.ResolveState
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.call.Call

class Issue480Test : PlatformTestCase() {
    fun testLocal() {
        myFixture.configureByFiles("local.ex", "referenced.ex")
        assertReferenceAndResolvedNameArityRange("changeset", 2)
    }

    /** The piped call is `changeset/1`, which the `import` brings in; the `import` is how, not what, it resolves to. */
    fun testRemote() {
        myFixture.configureByFiles("remote.ex", "referenced.ex")
        assertReferenceAndResolvedNameArityRange("changeset", 1)
    }

    fun testDirectModuleQualifier() {
        myFixture.configureByFiles("direct_module_qualifier.ex", "referenced.ex")
        assertReferenceAndResolvedNameArityRange("changeset", 1)
    }

    fun testAliasedModuleQualifier() {
        myFixture.configureByFiles("aliased_module_qualifier.ex", "referenced.ex")
        assertReferenceAndResolvedNameArityRange("changeset", 1)
    }

    fun testDoubleAliasesModuleQualifier() {
        myFixture.configureByFiles("double_aliased_module_qualifier.ex", "referenced.ex")
        assertReferenceAndResolvedNameArityRange("changeset", 1)
    }

    fun testMapAccessQualifier() {
        myFixture.configureByFiles("map_access_qualifier.ex", "referenced.ex")
        assertUnresolvableReferenceNameArityRange("__struct__", 1)
    }

    fun testUnresolvedAliasQualifier() {
        myFixture.configureByFiles("unresolved_alias_qualifier.ex", "referenced.ex")
        assertUnresolvableReferenceNameArityRange("changeset", 1)
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/reference/callable/issue_480"

    private fun assertUnresolvableReferenceNameArityRange(name: String, arity: Int) {
        val elementAtCaret = myFixture
                .file
                .findElementAt(myFixture.caretOffset)
        assertNotNull(elementAtCaret)

        val grandParent = elementAtCaret!!.parent.parent
        assertNotNull(grandParent)
        assertInstanceOf(grandParent, Call::class.java)
        val grandParentCall = grandParent as Call

        assertEquals(name, grandParentCall.functionName())
        assertEquals(arity, grandParentCall.resolvedFinalArity())

        val reference = grandParent.getReference()
        assertNotNull(reference)

        val resolved = reference!!.resolve()
        assertNull(resolved)
    }

    private fun assertReferenceAndResolvedNameArityRange(name: String, arity: Int) {
        val elementAtCaret = myFixture
                .file
                .findElementAt(myFixture.caretOffset)
        assertNotNull(elementAtCaret)

        val grandParent = elementAtCaret!!.parent.parent
        assertNotNull(grandParent)
        assertInstanceOf(grandParent, Call::class.java)
        val grandParentCall = grandParent as Call

        assertEquals(name, grandParentCall.functionName())
        assertEquals(arity, grandParentCall.resolvedFinalArity())
        assertResolvedNameArityRange(grandParentCall, name, arity)
    }

    private fun assertResolvedNameArityRange(element: PsiElement, name: String, arity: Int) {
        val reference = element.reference
        assertNotNull(reference)
        assertInstanceOf(reference, PsiPolyVariantReference::class.java)
        val psiPolyVariantReference = reference as PsiPolyVariantReference

        val resolveResults = psiPolyVariantReference.multiResolve(false)
        val validResults = resolveResults.filter { it.isValidResult }
        assertTrue("Expected at least one valid resolve result, but got none", validResults.isNotEmpty())

        for (resolveResult in validResults) {
            val resolved = resolveResult.element
            assertInstanceOf(resolved, Call::class.java)
            val maybeDefCall = resolved as Call?
            assertTrue(`is`(maybeDefCall!!))
            assertEquals(
                    NameArityInterval(name, ArityInterval(arity, arity)),
                    nameArityInterval(maybeDefCall, ResolveState.initial())
            )
        }
    }
}
