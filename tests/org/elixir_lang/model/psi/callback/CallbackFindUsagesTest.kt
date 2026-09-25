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

    /** The `defmacro __using__` a default `def` sits in is what makes it the behaviour's, with no `@behaviour`. */
    fun testDefaultImplementationInUsingIsFoundWithoutAnInjectedBehaviour() {
        assertTrue(
            "Expected the default def inside the behaviour's own __using__ among the callback's usages",
            implementationDefUsageCount("usages_default_impl_without_behaviour.ex", "kernel.ex") >= 1
        )
    }

    /** `use` calls `defmacro __using__/1`; a `def __using__` is an ordinary function, so what it quotes is not a default. */
    fun testADefNamedUsingIsNotWhatUseCalls() {
        myFixture.configureByText(
            "def_using.ex",
            """
            defmodule DefUsing do
              @callback per<caret>form() :: any

              def __using__(_) do
                quote do
                  def perform, do: :default
                end
              end
            end
            """.trimIndent()
        )

        assertEquals(0, implementationDefUsageCountInConfigured())
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
     * Runs Find Usages on the `@callback` at the caret in [files] and counts non-declaration usages
     * located on the name of a call-definition clause (`def`/`defmacro`) - i.e. implementations. The
     * callback's own self-declaration usage is excluded via [com.intellij.find.usages.api.PsiUsage.declaration]. We resolve each
     * usage by its `(file, range)` - the searcher models a [com.intellij.find.usages.api.PsiUsage] as file + absolute range (so its
     * `.element` is the file, not the def name), matching how the tool window navigates.
     */
    @Suppress("UnstableApiUsage")
    private fun implementationDefUsageCount(vararg files: String): Int {
        myFixture.configureByFiles(*files)
        return implementationDefUsageCountInConfigured()
    }

    @Suppress("UnstableApiUsage")
    private fun implementationDefUsageCountInConfigured(): Int =
        myFixture.singleTargetPsiUsagesAtCaret(project)
            .filterNot { it.declaration }
            .count { usage ->
                val element = usage.file.findElementAt(usage.range.startOffset)
                element != null && generateSequence(element) { it.parent }
                    .filterIsInstance<Call>()
                    .any { CallDefinitionClause.`is`(it) }
            }
}
