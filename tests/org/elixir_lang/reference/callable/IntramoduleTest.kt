package org.elixir_lang.reference.callable

import com.intellij.openapi.util.Disposer
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.psi.ElixirIdentifier

class IntramoduleTest : ExpanderSettingTestCase() {
    fun testAmbiguousBackReference() {
        myFixture.configureByFiles("ambiguous_back.ex")
        val ambiguous = myFixture
                .file
                .findElementAt(myFixture.caretOffset)!!
                .prevSibling
        assertInstanceOf(ambiguous.firstChild, ElixirIdentifier::class.java)

        val reference = ambiguous.reference
        assertNotNull("`referenced` has no reference", reference)

        val resolved = reference!!.resolve()
        assertNotNull("`referenced` not resolved", resolved)
        assertEquals(
                "ambiguous reference does not resolve to previous function declaration",
                "def referenced do\n\n  end",
                resolved!!.text
        )
    }

    fun testAmbiguousForwardReference() {
        myFixture.configureByFiles("ambiguous_forward.ex")
        val ambiguous = myFixture
                .file
                .findElementAt(myFixture.caretOffset)!!
                .prevSibling
        assertInstanceOf(ambiguous.firstChild, ElixirIdentifier::class.java)

        val reference = ambiguous.reference
        assertNotNull("`referenced` has no reference", reference)

        val resolved = reference!!.resolve()
        assertNotNull("`referenced` not resolved", resolved)
        assertEquals(
                "ambiguous reference does not resolve to forward function declaration",
                "def referenced do\n\n  end",
                resolved!!.text
        )
    }

    fun testAmbiguousRecursiveReference() {
        myFixture.configureByFiles("ambiguous_recursive.ex")
        val ambiguous = myFixture
                .file
                .findElementAt(myFixture.caretOffset)!!
                .prevSibling
        assertInstanceOf(ambiguous.firstChild, ElixirIdentifier::class.java)

        val reference = ambiguous.reference
        assertNotNull("`referenced` has no reference", reference)

        val resolved = reference!!.resolve()
        assertNotNull("`referenced` not resolved", resolved)
        assertEquals(
                "ambiguous reference does not resolve to recursive function declaration",
                "def referenced do\n    referenced\n\n    a = 1\n  end",
                resolved!!.text
        )
    }

    fun testFunctionNameMultipleSameArity() {
        myFixture.configureByFiles("function_name_multiple_same_arity.ex")
        val declarationHead = myFixture
                .file
                .findElementAt(myFixture.caretOffset)!!
                .parent
                .parent
                .parent
        assertInstanceOf(declarationHead.firstChild, ElixirIdentifier::class.java)
        // Under the Symbol API, a call-definition head is a declaration site, not a reference.
        assertNull("Call definition head should not expose a legacy reference", declarationHead.reference)
    }

    fun testParenthesesRecursiveReference() {
        myFixture.configureByFiles("parentheses_recursive.ex")
        val parenthesesCall = myFixture
                .file
                .findElementAt(myFixture.caretOffset)!!
                .parent
                .parent
                .parent
        assertInstanceOf(parenthesesCall.firstChild, ElixirIdentifier::class.java)

        val reference = parenthesesCall.reference
        assertNotNull("`referenced` has no reference", reference)

        val resolved = reference!!.resolve()
        assertNotNull("`referenced` not resolved", resolved)
        assertEquals(
                "ambiguous reference does not resolve to recursive function declaration",
                "def referenced do\n    referenced()\n\n    a = 1\n  end",
                resolved!!.text
        )
    }

    fun testParenthesesMultipleCorrectArityReference() {
        myFixture.configureByFiles("parentheses_multiple_correct_arity.ex")
        val parenthesesCall = myFixture
                .file
                .findElementAt(myFixture.caretOffset)!!
                .parent
                .parent
                .parent
        assertInstanceOf(parenthesesCall.firstChild, ElixirIdentifier::class.java)

        val reference = parenthesesCall.reference
        assertNotNull("`referenced` has no reference", reference)
        assertInstanceOf(reference, PsiPolyVariantReference::class.java)
        val polyVariantReference = reference as PsiPolyVariantReference?

        val resolveResults = polyVariantReference!!.multiResolve(false)
        assertEquals("Did not resolve to all clauses", 2, resolveResults.size)

        val firstResolveResult = resolveResults[0]
        val firstResolved = firstResolveResult.element
        assertEquals(
                "first ResolveResult is not the true clause",
                "def referenced(true) do\n  end",
                firstResolved!!.text
        )
        assertInstanceOf(firstResolved, NavigatablePsiElement::class.java)
        val navigatablefirstResolved = firstResolved as NavigatablePsiElement?
        val firstPresentation = navigatablefirstResolved!!.presentation
        assertNotNull("first ResolveResult element has no presentation", firstPresentation)
        assertEquals("/src/parentheses_multiple_correct_arity.ex defmodule A", firstPresentation!!.locationString)
        assertEquals("referenced(true)", firstPresentation.presentableText)

        val secondResolveResult = resolveResults[1]
        val secondResolved = secondResolveResult.element
        assertEquals(
                "second ResolveResult is not the false clause",
                "def referenced(false) do\n  end",
                secondResolved!!.text
        )
        assertInstanceOf(secondResolved, NavigatablePsiElement::class.java)
        val navigatablesecondResolved = secondResolved as NavigatablePsiElement?
        val secondPresentation = navigatablesecondResolved!!.presentation
        assertNotNull("second ResolveResult element has no presentation", secondPresentation)
        assertEquals("/src/parentheses_multiple_correct_arity.ex defmodule A", secondPresentation!!.locationString)
        assertEquals("referenced(false)", secondPresentation.presentableText)
    }

    fun testParenthesesSingleCorrectArityReference() {
        myFixture.configureByFiles("parentheses_single_correct_arity.ex")
        val parenthesesCall = myFixture
                .file
                .findElementAt(myFixture.caretOffset)!!
                .parent
                .parent
                .parent
        assertInstanceOf(parenthesesCall.firstChild, ElixirIdentifier::class.java)

        val reference = parenthesesCall.reference
        assertNotNull("`referenced` has no reference", reference)
        assertInstanceOf(reference, PsiPolyVariantReference::class.java)
        val polyVariantReference = reference as PsiPolyVariantReference

        val resolveResults = polyVariantReference.multiResolve(false)
        assertEquals("Did not resolve to all clauses", 1, resolveResults.size)

        val firstResolveResult = resolveResults[0]
        assertTrue("first ResolveResult is not a valid result", firstResolveResult.isValidResult)
        val firstResolved = firstResolveResult.element
        assertEquals(
                "first ResolveResult is not 1-arity clause",
                """def referenced(_) do
  end""",
                firstResolved!!.text
        )
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/reference/callable/intramodule"

    @Throws(Exception::class)
    override fun tearDown() {
        super.tearDown()
        if (myFixture != null) {
            val project = project
            if (project != null && !project.isDisposed) {
                Disposer.dispose(project)
            }
        }
    }
}
