package org.elixir_lang.reference.callable

import com.intellij.ide.impl.HeadlessDataManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertGotoDeclarationLandsIn
import org.elixir_lang.code_insight.assertNoNavigationAtCaret
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call

/**
 * https://github.com/intellij-elixir/intellij-elixir/issues/305
 *
 * A macro's arguments are quoted, so in a match they can bind - `session(id, user) = raw` declares
 * both `id` and `user`, the way `Record.defrecord`'s generated accessors are used to destructure an
 * Erlang record. A function's arguments are values and bind nothing. The two are indistinguishable
 * syntactically, so deciding between them means resolving the call and asking whether it is a
 * `defmacro`.
 *
 * Asserted through the real Ctrl+Click gesture rather than [com.intellij.psi.PsiPolyVariantReference.multiResolve],
 * because the gesture has three outcomes where `multiResolve` has one bit: navigating to the
 * declaration, offering Show Usages (the caret is *on* a declaration), and doing nothing. Reading
 * only "did it resolve" cannot tell the second from the first, which is exactly the confusion this
 * issue invites.
 */
@Suppress("UnstableApiUsage")
class Issue305Test : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/reference/callable/issue_305"

    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    /** The issue itself: a use of `id` navigates back to the `id` bound by the macro call. */
    fun testUseOfMacroBoundVariableNavigatesToTheMacroCall() {
        myFixture.configureByFile("macro_in_match.ex")

        myFixture.assertGotoDeclarationLandsIn("id", "the `session(id, user)` macro call") {
            it.functionName() == "session"
        }
    }

    /**
     * The other half of the same claim. If `id` in `session(id, user)` is a declaration, the gesture
     * on it offers Show Usages rather than navigating - so this pins the direction of navigation, not
     * just that something resolves.
     */
    fun testMacroBoundVariableIsItselfADeclaration() {
        myFixture.configureByFile("macro_in_match_declaration.ex")

        myFixture.assertShowUsagesChosenAtCaret(
            "`id` in `session(id, user)` is bound by the macro, so it is a declaration"
        )
    }

    /** A call resolving to a `@macrocallback` binds as one resolving to a `defmacro` does, as its highlight says. */
    fun testUseOfMacrocallbackBoundVariableNavigatesToTheCall() {
        myFixture.configureByText(
            "macrocallback_in_match.ex",
            """
            defmodule Sessions do
              @macrocallback session(term, term) :: Macro.t()

              def describe(raw) do
                session(id, user) = raw

                <caret>id
              end
            end
            """.trimIndent()
        )

        myFixture.assertGotoDeclarationLandsIn("id", "the `session(id, user)` call") { it.functionName() == "session" }
    }

    /**
     * The guard on the fix. `session/2` here is a `def`, so its arguments are values and declare
     * nothing - `id` below must stay unresolved. A fix that treated every parenthesised call in a
     * match as binding would light this up, which is why the two fixtures are identical apart from
     * `def` versus `defmacro`.
     */
    fun testUseOfFunctionArgumentDoesNotNavigate() {
        myFixture.configureByFile("function_in_match.ex")

        myFixture.assertNoNavigationAtCaret(
            "a `def` call's arguments are values, so `id` must not resolve to `session(id, user)`"
        )
    }

    /**
     * Control. A tuple pattern in the same match position binds `id` without any macro involved, so
     * this passes with or without the fix - it proves the fixture shape and the gesture assertions
     * are sound, which is what makes the macro cases above statements about macros.
     */
    fun testPlainMatchNavigatesToItsPattern() {
        myFixture.configureByFile("plain_match.ex")

        myFixture.assertGotoDeclarationLandsIn("id", "the `describe/1` clause") { call: Call ->
            CallDefinitionClause.`is`(call)
        }
    }

}
