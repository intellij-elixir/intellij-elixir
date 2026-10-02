package org.elixir_lang.model.psi.atom

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.completionStringsAtCaret

/**
 * Completion for the function atom in an MFA `apply/3` call: `apply(Mod, :<prefix>, args)` should
 * offer `Mod`'s **public** functions, inserting only the bare name (an atom is never followed by
 * `()`). A remote/MFA dispatch can never reach a private function, so `defp`s must not be offered.
 *
 * These tests drive the real completion popup / insertion and assert the correct, user-visible
 * behaviour.
 */
class AtomCompletionTest : PlatformTestCase() {
    fun testMfaApplyAtomCompletionOffersModuleFunctions() {
        configureApplyCompletion(
            """
            def handle_call, do: :ok
            def handle_cast, do: :ok
            """.trimIndent(),
            "handle"
        )
        assertApplyCompletionOffersExactly("handle_call", "handle_cast")
    }

    fun testMfaApplyAtomCompletionExcludesPrivateFunctions() {
        configureApplyCompletion(
            """
            def pub_one, do: :ok
            def pub_two, do: :ok
            defp priv_one, do: :ok
            """.trimIndent(),
            "p"
        )
        // priv_one matches the prefix but is private; an MFA/remote dispatch can never reach it.
        assertApplyCompletionOffersExactly("pub_one", "pub_two")
    }

    fun testMfaApplyAtomCompletionCollapsesSameNameDifferentArity() {
        configureApplyCompletion(
            """
            def dup(a), do: a
            def dup(a, b), do: {a, b}
            def dup_other, do: :ok
            """.trimIndent(),
            "du"
        )
        // dup/1 and dup/2 collapse to a single "dup" entry.
        assertApplyCompletionOffersExactly("dup", "dup_other")
    }

    fun testMfaApplyAtomCompletionInsertsBareNameWithoutParentheses() {
        val definitions = "def unique_target, do: :ok"
        configureApplyCompletion(definitions, "unique_t")
        myFixture.completeBasic()
        myFixture.checkResult(applyModule(definitions, "unique_target"))
    }

    fun testMfaApplyAtomCompletionQuotesANameThatNeedsIt() {
        val definitions = "def unquote(:\"unique target\")(), do: :ok"
        configureApplyCompletion(definitions, "unique")
        myFixture.completeBasic()
        myFixture.checkResult(applyModule(definitions, "\"unique target\""))
    }

    fun testMfaApplyAtomCompletionKeepsTheElixirPrefixOfAnAliasName() {
        val definitions = "def unquote(:\"Elixir.Unique\")(), do: :ok"
        configureApplyCompletion(definitions, "Elixir")
        myFixture.completeBasic()
        myFixture.checkResult(applyModule(definitions, "\"Elixir.Unique\""))
    }

    /*
     * Private Instance Methods
     */

    /**
     * Wraps [definitions] (one `def`/`defp` per line, no leading indent) in a module whose `run/0`
     * makes an `apply(Test, :[atom], [])` MFA call, so completion/insertion can be driven at [atom].
     */
    private fun applyModule(definitions: String, atom: String): String {
        val indented = definitions.trimEnd().lines().joinToString("\n") { "  $it" }

        return "defmodule Test do\n" +
                indented +
                "\n\n  def run do\n    apply(Test, :$atom, [])\n  end\nend"
    }

    private fun configureApplyCompletion(definitions: String, atomPrefix: String) {
        myFixture.configureByText("test.ex", applyModule(definitions, "$atomPrefix<caret>"))
    }

    /**
     * Asserts the `apply/3` atom completion offers *exactly* [expected] - no missing entries, no
     * extras (e.g. private functions), and no duplicates (same-name/different-arity must collapse).
     */
    private fun assertApplyCompletionOffersExactly(vararg expected: String) {
        val strings = myFixture.completionStringsAtCaret()
        assertNotNull("No completion shown for apply/3 atom", strings)
        assertEquals(
            "apply/3 atom completion should offer exactly these public functions (collapsed by name)",
            expected.sorted(),
            strings!!.sorted()
        )
    }
}
