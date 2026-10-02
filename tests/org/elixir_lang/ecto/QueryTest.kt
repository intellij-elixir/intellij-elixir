package org.elixir_lang.ecto

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.UnqualifiedNoArgumentsCall

/**
 * Variable resolution walks into an `Ecto.Query` macro call to find the bindings the query DSL
 * declares. The walker names the binding shapes it declares from; a shape it does not name declares
 * nothing, which is the ordinary case for a pinned option list or a query part-way through being
 * typed, and is not an error. The `from/2` keyword keys are different: Ecto's set is finite and
 * documented, and a new key may declare bindings, so an unlisted key is the one thing reported.
 *
 * The walker only runs when the call resolves to a macro in a module named `Ecto.Query`, so a stub
 * of that module is added to the project first.
 */
class QueryTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "lib/ecto/query.ex",
            """
            defmodule Ecto.Query do
              defmacro from(expr, kw \\ []), do: nil
              defmacro where(query, binding \\ [], expr), do: nil
              defmacro with_cte(query, name, list), do: nil
            end
            """.trimIndent()
        )
    }

    fun testBindingDeclaredByInResolvesFromSelect() {
        val results = resolveSilently("from(p in Post, select: <caret>p)")

        assertTrue(
            "`p` in `select:` should resolve to the `p` bound by `in`",
            results.any { it.isValidResult && it.element?.text == "p" }
        )
    }

    fun testUnlistedKeywordKeyIsReported() {
        val (_, errors) = resolve("from(p in Post, foo: <caret>q)")

        assertEquals(
            listOf("Don't know how to find reference variables for keyword key foo"),
            errors.map { it.title }.distinct()
        )
    }

    /** A quoted key is the atom it spells, so `"select":` is a listed key. */
    fun testQuotedListedKeywordKeyIsNotReported() {
        val results = resolveSilently("from(p in Post, \"select\": <caret>p)")

        assertTrue(results.any { it.isValidResult && it.element?.text == "p" })
    }

    /** And `"join":` declares the binding of its `in`, as `join:` does. */
    fun testBindingDeclaredByQuotedJoinResolvesFromSelect() {
        val results = resolveSilently("from(p in Post, \"join\": c in Comment, select: <caret>c)")

        assertTrue(
            "`c` in `select:` should resolve to the `c` bound by `\"join\":`",
            results.any { it.isValidResult && it.element?.text == "c" }
        )
    }

    fun testBindingDeclaredByJoinResolvesFromSelect() {
        val results = resolveSilently("from(p in Post, join: c in Comment, select: <caret>c)")

        assertTrue(
            "`c` in `select:` should resolve to the `c` bound by `join:`",
            results.any { it.isValidResult && it.element?.text == "c" }
        )
    }

    fun testPinnedOptionsDeclareNothing() {
        resolveSilently("from(p in Post, ^<caret>q)")
    }

    fun testPinnedSourceDeclaresNothing() {
        resolveSilently("from(^<caret>q in Post)")
    }

    fun testBracketedWithCteListDeclaresNothing() {
        resolveSilently("q |> with_cte(\"c\", [as: ^<caret>cte])")
    }

    fun testBindingTupleOfUnexpectedArityDeclaresNothing() {
        resolveSilently("where(q, [{p, 1, 2}], <caret>x)")
    }

    fun testUnpinnedUnaryBindingDeclaresNothing() {
        resolveSilently("where(q, [!p], <caret>x)")
    }

    fun testLiteralBindingDeclaresNothing() {
        resolveSilently("where(q, [\"p\"], <caret>x)")
    }

    private fun resolveSilently(query: String): Array<ResolveResult> {
        val (results, errors) = resolve(query)

        assertEmpty("a query shape the walker does not declare from is not an error", errors)

        return results
    }

    private fun resolve(query: String): Pair<Array<ResolveResult>, List<LoggedError>> {
        myFixture.configureByText(
            "query_test.ex",
            """
            defmodule QueryTest do
              import Ecto.Query

              def run(q) do
                $query
              end
            end
            """.trimIndent()
        )
        val usage = PsiTreeUtil.getParentOfType(
            myFixture.file.findElementAt(myFixture.caretOffset),
            UnqualifiedNoArgumentsCall::class.java
        )
        assertNotNull("caret is not on a variable usage", usage)
        val reference = usage!!.reference as PsiPolyVariantReference

        return captureLoggedErrors { reference.multiResolve(false) }
    }
}
