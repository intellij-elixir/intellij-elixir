package org.elixir_lang.model.psi.atom

import com.intellij.codeInsight.lookup.Lookup
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

    fun testQuotedApplyAtomCompletionOffersModuleFunctions() {
        configureApplyCompletion(
            """
            def handle_call, do: :ok
            def handle_cast, do: :ok
            """.trimIndent(),
            "\"handle",
            "\""
        )
        assertApplyCompletionOffersExactly("handle_call", "handle_cast")
    }

    fun testEmptyQuotedApplyAtomCompletionOffersModuleFunctions() {
        configureApplyCompletion(
            """
            def handle_call, do: :ok
            def handle_cast, do: :ok
            """.trimIndent(),
            "\"",
            "\""
        )
        assertApplyCompletionOffersExactly("handle_call", "handle_cast", "run")
    }

    fun testQuotedApplyAtomCompletionInsertsTheNameBetweenTheQuotes() {
        val definitions = "def unique_target, do: :ok"
        configureApplyCompletion(definitions, "\"unique_t", "\"")
        myFixture.completeBasic()
        myFixture.checkResult(applyModule(definitions, "\"unique_target\""))
    }

    fun testQuotedApplyAtomCompletionEscapesTheQuote() {
        val definitions = "def unquote(:\"unique\\\"target\")(), do: :ok"
        configureApplyCompletion(definitions, "\"unique", "\"")
        myFixture.completeBasic()
        myFixture.checkResult(applyModule(definitions, "\"unique\\\"target\""))
    }

    fun testQuotedApplyAtomCompletionInsertsANameThatNeedsQuotesOnlyOnce() {
        val definitions = "def unquote(:\"unique target\")(), do: :ok"
        configureApplyCompletion(definitions, "\"unique", "\"")
        myFixture.completeBasic()
        myFixture.checkResult(applyModule(definitions, "\"unique target\""))
    }

    fun testQuotedApplyAtomCompletionKeepsTheElixirPrefixOfAnAliasName() {
        val definitions = "def unquote(:\"Elixir.Unique\")(), do: :ok"
        configureApplyCompletion(definitions, "\"Elixir", "\"")
        myFixture.completeBasic()
        myFixture.checkResult(applyModule(definitions, "\"Elixir.Unique\""))
    }

    fun testQuotedApplyAtomCompletionReplacingKeepsTheClosingQuote() {
        val definitions = """
            def unique_target, do: :ok
            def unique_tail, do: :ok
            """.trimIndent()
        configureApplyCompletion(definitions, "\"unique_ta", "xyz\"")
        myFixture.completeBasic()
        myFixture.lookup.currentItem = myFixture.lookupElements!!.single { it.lookupString == "unique_target" }
        myFixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
        myFixture.checkResult(applyModule(definitions, "\"unique_target\""))
    }

    fun testInterpolatedApplyAtomCompletionOffersNothing() {
        val definitions = "def unique_target, do: :ok"
        configureApplyCompletion(definitions, "\"", "#{x}\"")
        assertInterpolatedAtomCompletionOffersNothing(applyModule(definitions, "\"#{x}\""))
    }

    fun testInterpolatedMfaTupleAtomCompletionOffersNothing() {
        val definitions = "def unique_target, do: :ok"
        myFixture.configureByText("test.ex", tupleModule(definitions, "\"<caret>#{x}\""))
        assertInterpolatedAtomCompletionOffersNothing(tupleModule(definitions, "\"#{x}\""))
    }

    fun testCharListQuotedApplyAtomCompletionInsertsTheNameBetweenTheQuotes() {
        val definitions = "def unique_target, do: :ok"
        configureApplyCompletion(definitions, "'unique_t", "'")
        myFixture.completeBasic()
        myFixture.checkResult(applyModule(definitions, "'unique_target'"))
    }

    fun testCharListQuotedApplyAtomCompletionEscapesTheQuote() {
        val definitions = "def unquote(:\"unique'target\")(), do: :ok"
        configureApplyCompletion(definitions, "'unique", "'")
        myFixture.completeBasic()
        myFixture.checkResult(applyModule(definitions, "'unique\\'target'"))
    }

    fun testQuotedMfaTupleAtomCompletionInsertsTheNameBetweenTheQuotes() {
        val definitions = "def unique_target, do: :ok"
        myFixture.configureByText("test.ex", tupleModule(definitions, "\"unique_t<caret>\""))
        myFixture.completeBasic()
        myFixture.checkResult(tupleModule(definitions, "\"unique_target\""))
    }

    fun testQuotedMfaTupleAtomCompletionEscapesTheQuote() {
        val definitions = "def unquote(:\"unique\\\"target\")(), do: :ok"
        myFixture.configureByText("test.ex", tupleModule(definitions, "\"unique<caret>\""))
        myFixture.completeBasic()
        myFixture.checkResult(tupleModule(definitions, "\"unique\\\"target\""))
    }

    /*
     * Private Instance Methods
     */

    /**
     * Wraps [definitions] (one `def`/`defp` per line, no leading indent) in a module whose `run/0`
     * makes an `apply(Test, :[atom], [])` MFA call, so completion/insertion can be driven at [atom].
     */
    private fun applyModule(definitions: String, atom: String): String =
        module(definitions, "apply(Test, :$atom, [])")

    /** Like [applyModule], with the MFA tuple `{Test, :[atom], 0}` in place of the `apply/3` call. */
    private fun tupleModule(definitions: String, atom: String): String =
        module(definitions, "{Test, :$atom, 0}")

    private fun module(definitions: String, body: String): String {
        val indented = definitions.trimEnd().lines().joinToString("\n") { "  $it" }

        return "defmodule Test do\n" +
                indented +
                "\n\n  def run do\n    $body\n  end\nend"
    }

    private fun configureApplyCompletion(definitions: String, atomPrefix: String, atomSuffix: String = "") {
        myFixture.configureByText("test.ex", applyModule(definitions, "$atomPrefix<caret>$atomSuffix"))
    }

    private fun assertInterpolatedAtomCompletionOffersNothing(unchanged: String) {
        myFixture.completeBasic()
        assertDoesntContain(myFixture.lookupElementStrings.orEmpty(), "unique_target")
        myFixture.checkResult(unchanged)
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
