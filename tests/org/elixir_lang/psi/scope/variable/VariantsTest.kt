package org.elixir_lang.psi.scope.variable

import com.intellij.codeInsight.completion.CompletionType
import org.elixir_lang.PlatformTestCase

/**
 * Behavioural characterisation of *variable* completion: drives the real completion popup at the
 * caret (as a user would) and asserts the exact lookup set, rather than reaching through
 * `element.reference.variants`.
 *
 * The key guarantee pinned here is **dedup of rebound / shadowed bindings**: a variable that is
 * bound more than once (rebinding in the same scope, or shadowing across nested scopes) must appear
 * exactly once in completion. This locks the multi-binding collapse behaviour (`Callable.getVariants`
 * feeding `psi/scope/variable`) that a resolver refactor could silently regress into duplicate
 * lookup entries.
 */
class VariantsTest : PlatformTestCase() {
    fun testReboundVariableInSameScopeIsOfferedOnce() {
        myFixture.configureByFile("same_scope_rebinding.ex")
        myFixture.complete(CompletionType.BASIC)
        val strings = myFixture.lookupElementStrings
        assertNotNull("Completion not shown", strings)

        assertEquals(
            "A variable rebound in the same scope must be offered exactly once, got: $strings",
            1,
            strings!!.count { it == "total" }
        )
        assertEquals(
            "The other in-scope variable must be offered exactly once, got: $strings",
            1,
            strings.count { it == "tally" }
        )
    }

    fun testShadowedVariableAcrossNestedScopesIsOfferedOnce() {
        myFixture.configureByFile("nested_scope_shadowing.ex")
        myFixture.complete(CompletionType.BASIC)
        val strings = myFixture.lookupElementStrings
        assertNotNull("Completion not shown", strings)

        assertEquals(
            "A variable shadowed across nested scopes must be offered exactly once, got: $strings",
            1,
            strings!!.count { it == "total" }
        )
        assertEquals(
            "The other in-scope variable must be offered exactly once, got: $strings",
            1,
            strings.count { it == "tally" }
        )
    }

    fun testUnderscoreIsNotOfferedInTheBodyOfAClauseMatchingIt() {
        myFixture.configureByText(
            "underscore.ex",
            "defmodule Underscore do\n  def f(x) do\n    case x do\n      _ ->\n        <caret>\n    end\n  end\nend\n"
        )
        myFixture.complete(CompletionType.BASIC)
        val strings = myFixture.lookupElementStrings
        assertNotNull("Completion not shown", strings)

        assertContainsElements(strings!!, "x")
        assertDoesntContain(strings, "_")
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang/psi/scope/variable/variants"
}
