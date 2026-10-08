package org.elixir_lang.model.psi.variable

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.psi.PsiPolyVariantReference
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.completionStringsAtCaret
import org.elixir_lang.psi.ElixirKeywordKey

/** A key of `quote`'s `bind_quoted:` list is a variable in the quote's body: the resolver finds it from a read there, and completion offers it. */
class BindQuotedVariableTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testReadInDoBlockResolvesToKey() =
        assertDeclaration("quote bind_quoted: [a: 1] do\n      <caret>a\n    end", "a")

    fun testReadInDoKeywordResolvesToKey() =
        assertDeclaration("quote bind_quoted: [a: 1], do: <caret>a", "a")

    fun testReadResolvesToItsOwnKey() =
        assertDeclaration("quote bind_quoted: [a: 1, b: 2] do\n      <caret>b\n    end", "b")

    fun testCompletionOffersTheKey() {
        configure("xb = 1\n    quote bind_quoted: [xa: 1] do\n      x<caret>\n    end")

        assertEquals(listOf("xa", "xb"), myFixture.completionStringsAtCaret().orEmpty().sorted())
    }

    /** A value runs where the `quote` is, not inside it, so the keys are not variables there. */
    fun testReadInAValueResolvesToTheEarlierAssignment() {
        configure("a = 1\n    quote bind_quoted: [a: <caret>a], do: a")
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference
        val declarations = reference.multiResolve(false).filter { it.isValidResult }.mapNotNull { it.element }

        assertEquals(declarations.toString(), 1, declarations.size)
        assertFalse("a bind_quoted key: $declarations", declarations.single() is ElixirKeywordKey)
        assertEquals("a = 1", declarations.single().parent.text)
    }

    fun testCompletionInAValueDoesNotOfferTheKey() {
        configure("xa = 1\n    xc = 2\n    quote bind_quoted: [xb: x<caret>] do\n      xb\n    end")

        assertEquals(listOf("xa", "xc"), myFixture.completionStringsAtCaret().orEmpty().sorted())
    }

    /** An enabled `unquote` runs where the `quote` is, outside its bindings. */
    fun testReadInAnEnabledUnquoteResolvesToTheEarlierAssignment() =
        assertEarlierAssignment("a = 1\n    quote bind_quoted: [a: 2], unquote: true do\n      unquote(<caret>a)\n    end")

    fun testReadInAnEnabledUnquoteSplicingResolvesToTheEarlierAssignment() =
        assertEarlierAssignment("a = [1]\n    quote bind_quoted: [a: [2]], unquote: true do\n      [unquote_splicing(<caret>a)]\n    end")

    fun testReadInAnEnabledUnquoteAskedForByAnAtomResolvesToTheEarlierAssignment() =
        assertEarlierAssignment("a = 1\n    quote bind_quoted: [a: 2], unquote: :true do\n      unquote(<caret>a)\n    end")

    fun testReadInAnEnabledRemoteUnquoteResolvesToTheEarlierAssignment() =
        assertEarlierAssignment("a = 1\n    quote bind_quoted: [a: 2], unquote: true do\n      Foo.unquote(<caret>a)\n    end")

    /** A nested `quote` leaves its `unquote` as code, which runs in the generated body, after the keys are bound. */
    fun testReadInAnUnquoteOfANestedQuoteResolvesToTheKey() =
        assertDeclaration("quote bind_quoted: [a: 1], unquote: true do\n      quote do\n        unquote(<caret>a)\n      end\n    end", "a")

    /** In `Left.unquote(name)(arguments)` only `name` runs outside the bindings. */
    fun testArgumentOfACallWithAnUnquotedNameResolvesToTheKey() =
        assertDeclaration("quote bind_quoted: [mod: 1], unquote: true do\n      mod.unquote(:f)(<caret>mod)\n    end", "mod")

    fun testReceiverOfACallWithAnUnquotedNameResolvesToTheKey() =
        assertDeclaration("quote bind_quoted: [mod: 1], unquote: true do\n      <caret>mod.unquote(:f)(mod)\n    end", "mod")

    fun testReceiverOfAnUnquotedNameResolvesToTheKey() =
        assertDeclaration("quote bind_quoted: [mod: 1], unquote: true do\n      <caret>mod.unquote(:f)\n    end", "mod")

    fun testNameOfACallWithAnUnquotedNameResolvesToTheEarlierAssignment() =
        assertEarlierAssignment("mod = 1\n    quote bind_quoted: [mod: 2], unquote: true do\n      mod.unquote(<caret>mod)(1)\n    end")

    /** Only `unquote/1` unquotes. */
    fun testReadInACallOfUnquoteWithTwoArgumentsResolvesToTheKey() =
        assertDeclaration("quote bind_quoted: [a: 1], unquote: true do\n      unquote(<caret>a, 1)\n    end", "a")

    /** `bind_quoted:` disables `unquote` unless it is asked for, so there the call is quoted code like any other. */
    fun testReadInADisabledUnquoteResolvesToTheKey() =
        assertDeclaration("quote bind_quoted: [a: 1] do\n      unquote(<caret>a)\n    end", "a")

    fun testReadOutsideAnUnquoteInAnUnquotingQuoteResolvesToTheKey() =
        assertDeclaration("quote bind_quoted: [a: 1], unquote: true do\n      <caret>a\n    end", "a")

    fun testCompletionInAnEnabledUnquoteDoesNotOfferTheKey() {
        configure("xa = 1\n    xc = 2\n    quote bind_quoted: [xb: 3], unquote: true do\n      unquote(x<caret>)\n    end")

        assertEquals(listOf("xa", "xc"), myFixture.completionStringsAtCaret().orEmpty().sorted())
    }

    /** Elixir assigns the keys in order, so the last one is the binding a read gets. */
    fun testDuplicateKeysResolveToTheLast() {
        configure("quote bind_quoted: [a: 1, a: 2] do\n      <caret>a\n    end")
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference
        val declarations = reference.multiResolve(false).filter { it.isValidResult }.mapNotNull { it.element }

        assertEquals(declarations.toString(), 1, declarations.size)
        assertEquals(myFixture.file.text.indexOf("a: 2"), declarations.single().textOffset)
    }

    private fun assertEarlierAssignment(body: String) {
        configure(body)
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference
        val declarations = reference.multiResolve(false).filter { it.isValidResult }.mapNotNull { it.element }

        assertEquals(declarations.toString(), 1, declarations.size)
        assertFalse("a bind_quoted key: $declarations", declarations.single() is ElixirKeywordKey)
    }

    private fun assertDeclaration(quote: String, expected: String) {
        configure(quote)
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) as PsiPolyVariantReference
        val declarations = reference.multiResolve(false).filter { it.isValidResult }.map { it.element }

        assertEquals(listOf(expected), declarations.map { it?.text })
        assertTrue("not a bind_quoted key: $declarations", declarations.single() is ElixirKeywordKey)
    }

    private fun configure(body: String) {
        myFixture.configureByText("bind_quoted.ex", "defmodule M do\n  defmacro m do\n    $body\n  end\nend\n")
    }
}
