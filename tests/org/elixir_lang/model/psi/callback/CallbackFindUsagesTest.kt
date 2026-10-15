package org.elixir_lang.model.psi.callback

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
import org.elixir_lang.code_insight.singleTargetPsiUsagesAtCaret
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call

/**
 * Behavior-level tests for forward Find/Show Usages: invoking Find Usages on a `@callback` lists the
 * implementing `def`/`defmacro` clauses.
 *
 * These drive the SAME production search pipeline the Find Usages action uses - the `SearchTarget`
 * resolved at the caret (via [com.intellij.find.usages.impl.searchTargets], i.e. our `Callback` symbol) fed through [com.intellij.find.usages.impl.buildQuery]
 * (which runs `ElixirSymbolUsageSearcher` and yields the very [com.intellij.find.usages.api.PsiUsage]s the tool window would
 * display). We deliberately do NOT go through
 * `CodeInsightTestFixture.testFindUsagesUsingAction`: that wrapper fires the async, EDT-/modality-bound
 * `FindUsagesAction` and then polls for a `UsageView`, which is unreliable headless (the view is never
 * surfaced even though target arbitration is correct - verified separately: exactly one `SEARCH_TARGET`
 * = `perform/0`, no ambiguity popup). Asserting on the query's usages is deterministic and still
 * behavior-level: real caret -> real symbol -> real searcher -> real usages. Assertions never touch
 * internal resolver classes, so they survive refactoring.
 */
class CallbackFindUsagesTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/callback"

    fun testUseInjectedImplementationIsFound() {
        assertTrue(
            "Expected the implementing def (via `use`-injected @behaviour) among the callback's usages",
            implementationDefUsageCount("usages_use_injected.ex", "kernel.ex") >= 1
        )
    }

    fun testLiteralBehaviourImplementationIsFound() {
        assertTrue(
            "Expected the implementing def (via literal @behaviour) among the callback's usages",
            implementationDefUsageCount("usages_literal_behaviour.ex", "kernel.ex") >= 1
        )
    }

    fun testDefaultImplementationInUsingIsFound() {
        assertTrue(
            "Expected the default def inside __using__ among the callback's usages",
            implementationDefUsageCount("usages_default_impl.ex", "kernel.ex") >= 1
        )
    }

    fun testAtomBehaviourWrittenUnquotedImplementationIsFound() {
        assertTrue(
            "Expected the implementing def (via @behaviour :usages_requoted) among the callback's usages",
            implementationDefUsageCount("usages_requoted_atom_behaviour.ex", "kernel.ex") >= 1
        )
    }

    /**
     * Ctrl-Click decision (the original thrust): on a `@callback` - a declaration/`SearchTarget` - the
     * "Go To Declaration or Usages" handler that Ctrl-Click uses
     * (`GotoDeclarationAction implements CtrlMouseAction` -> `GotoDeclarationOrUsageHandler2`) chooses
     * **Show Usages** rather than doing nothing or navigating to itself.
     */
    fun testCtrlClickOnCallbackChoosesShowUsages() {
        myFixture.configureByFiles("usages_use_injected.ex", "kernel.ex")
        myFixture.assertShowUsagesChosenAtCaret()
    }

    fun testNonImplementingDefIsNotListed() {
        assertEquals(
            "A same-named def in a module that implements no behaviour must not be a callback usage",
            0,
            implementationDefUsageCount("usages_non_implementing.ex", "kernel.ex")
        )
    }

    /**
     * The calls to an implementation are uses of the callback, as a rename of it renames them: the implementing
     * function's local call and capture, and a call qualified by the implementing module, besides its `@spec`. The
     * `@callback` and the implementing head are declarations.
     */
    fun testCallToImplementationIsListed() {
        myFixture.configureByFiles("usages_implementation_calls.ex")

        assertEquals(
            listOf(
                "@callback size(term) :: integer" to true,
                "@spec size(list) :: integer" to false,
                "def size(list), do: length(list)" to true,
                "def twice(list), do: size(list) * 2" to false,
                "def capture, do: &size/1" to false,
                "def by_implementation(data), do: UsagesImplementationCallsImpl.size(data)" to false
            ),
            usageEntriesAtCaret()
        )
    }

    /**
     * A default argument's header implements the callback, and its separate clauses of the longer arity belong to the
     * same function, so they are listed with it, as declarations, and so is the call to it.
     */
    fun testSeparateClausesOfADefaultArgumentHeaderAreListed() {
        myFixture.configureByFiles("usages_implementation_defaults.ex")

        assertEquals(
            listOf(
                "@callback run(term) :: term" to true,
                "def run(x, option \\\\ nil)" to true,
                "def run(x, :a), do: x" to true,
                "def run(x, option), do: {x, option}" to true,
                "def twice(x), do: run(x, :a)" to false
            ),
            usageEntriesAtCaret()
        )
    }

    /** Each usage's line and whether it is a declaration. */
    private fun usageEntriesAtCaret(): List<Pair<String, Boolean>> =
        myFixture.singleTargetPsiUsagesAtCaret(project)
            .sortedBy { it.range.startOffset }
            .map { usage ->
                val text = usage.file.text
                text.lines()[text.take(usage.range.startOffset).count { it == '\n' }].trim() to usage.declaration
            }

    /**
     * Runs Find Usages on the `@callback` at the caret in [files] and counts usages located on the name of a
     * call-definition clause (`def`/`defmacro`) - i.e. implementations, which are declarations of the function. The
     * callback's own self-declaration usage is on the `@callback`, not on a clause. We resolve each
     * usage by its `(file, range)` - the searcher models a [com.intellij.find.usages.api.PsiUsage] as file + absolute range (so its
     * `.element` is the file, not the def name), matching how the tool window navigates.
     */
    @Suppress("UnstableApiUsage")
    private fun implementationDefUsageCount(vararg files: String): Int {
        myFixture.configureByFiles(*files)
        return myFixture.singleTargetPsiUsagesAtCaret(project)
            .count { usage ->
                val element = usage.file.findElementAt(usage.range.startOffset)
                element != null && generateSequence(element) { it.parent }
                    .filterIsInstance<Call>()
                    .any { CallDefinitionClause.`is`(it) }
            }
    }
}
