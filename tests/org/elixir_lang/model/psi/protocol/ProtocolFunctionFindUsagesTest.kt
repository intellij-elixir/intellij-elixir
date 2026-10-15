package org.elixir_lang.model.psi.protocol

import com.intellij.psi.search.GlobalSearchScope
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.assertShowUsagesChosenAtCaret
import org.elixir_lang.code_insight.singleTargetPsiUsagesAtCaret
import org.elixir_lang.code_insight.singleTargetPsiUsagesAtCaretInScope
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call

/**
 * Behavior-level tests for forward Find/Show Usages: invoking Find Usages on a `defprotocol def` lists the
 * **call sites** that dispatch to it (`Protocol.function(args)`) as well as its `defimpl` implementations
 * and the uses of those (what rename touches, consistent with how `@callback` usages include their
 * implementations). Implementations are additionally reachable via "Go To Implementation"
 * (`Ctrl+Alt+B`) / the gutter marker. The helper below counts only call sites (implementation `def` clauses
 * are filtered out via [CallDefinitionClause]).
 *
 * These drive the SAME production search pipeline the Find Usages action uses - the `SearchTarget`
 * resolved at the caret (via [com.intellij.find.usages.impl.searchTargets], i.e. our `ProtocolFunction` symbol) fed through [com.intellij.find.usages.impl.buildQuery]
 * (which runs `ElixirSymbolUsageSearcher` and yields the very [com.intellij.find.usages.api.PsiUsage]s the tool window would display). We deliberately do NOT go through
 * `CodeInsightTestFixture.testFindUsagesUsingAction`: that wrapper fires the async, EDT-/modality-bound
 * `FindUsagesAction` and then polls for a `UsageView`, which is unreliable headless (the view is never
 * surfaced even though target arbitration is correct - verified separately: exactly one `SEARCH_TARGET`
 * = `perform/1`, no ambiguity popup). Asserting on the query's usages is deterministic and still
 * behavior-level: real caret -> real symbol -> real searcher -> real usages. Assertions never touch
 * internal resolver classes, so they survive refactoring.
 */
class ProtocolFunctionFindUsagesTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/model/psi/protocol"

    fun testProtocolCallSiteIsFound() {
        assertTrue(
            "Expected the qualified call site `Protocol.perform(value)` among the protocol function's usages",
            callSiteUsageCount("usages_use_injected.ex") >= 1
        )
    }

    fun testLiteralProtocolCallSiteIsFound() {
        assertTrue(
            "Expected the qualified call site among the protocol function's usages (literal defimpl)",
            callSiteUsageCount("usages_literal_protocol.ex") >= 1
        )
    }

    /**
     * Ctrl-Click decision (the original thrust): on a protocol function definition - a declaration/`SearchTarget` - the
     * "Go To Declaration or Usages" handler that Ctrl-Click uses
     * (`GotoDeclarationAction implements CtrlMouseAction` -> `GotoDeclarationOrUsageHandler2`) chooses
     * **Show Usages** rather than doing nothing or navigating to itself.
     */
    fun testCtrlClickOnProtocolFunctionChoosesShowUsages() {
        myFixture.configureByFiles("usages_use_injected.ex")
        myFixture.assertShowUsagesChosenAtCaret()
    }

    fun testCallToDifferentModuleIsNotListed() {
        assertEquals(
            "A same-named call qualified by a different module must not be a protocol function usage",
            0,
            callSiteUsageCount("usages_non_implementing.ex")
        )
    }

    /**
     * The calls to an implementation are uses of the protocol function, as a rename of it renames them: the
     * implementation's local call and capture, and a call qualified by the implementing module, besides its `@spec`.
     * The protocol's `def` and the implementing head are declarations.
     */
    fun testCallToImplementationIsListed() {
        myFixture.configureByFiles("usages_implementation_calls.ex")

        assertEquals(
            listOf(
                "def size(data)" to true,
                "@spec size(list) :: integer" to false,
                "def size(list), do: length(list)" to true,
                "def twice(list), do: size(list) * 2" to false,
                "def capture, do: &size/1" to false,
                "def by_implementation(data), do: UsagesImplementationCalls.List.size(data)" to false
            ),
            usageEntriesAtCaret()
        )
    }

    /**
     * Find Usages limited to a client's file lists its call of an implementation, though the implementing `defimpl`
     * is outside that scope. The protocol's own declaration is always listed.
     */
    fun testCallToImplementationIsListedInAScopeWithoutTheImplementation() {
        myFixture.configureByFiles("usages_scoped_implementation_calls.ex", "usages_scoped_implementation_calls_client.ex")
        val client = myFixture.findFileInTempDir("usages_scoped_implementation_calls_client.ex")

        val usages = myFixture.singleTargetPsiUsagesAtCaretInScope(project, GlobalSearchScope.fileScope(project, client))

        assertEquals(
            listOf("def size(data)", "def by_implementation(data), do: UsagesScopedImplementationCalls.List.size(data)"),
            usages.sortedBy { it.range.startOffset }.map { usage ->
                val text = usage.file.text
                text.lines()[text.take(usage.range.startOffset).count { it == '\n' }].trim()
            }
        )
    }

    /**
     * Every implementation of a protocol function shares one function, but a `defp` of the same name in another
     * `defimpl` implements nothing: its local call is not a use of the implementations.
     */
    fun testCallToAPrivateHelperIsNotAUseOfTheImplementations() {
        myFixture.configureByFiles("usages_private_helper_call.ex")

        assertEquals(
            listOf("def size(data)", "def size(list), do: length(list)", "def twice(list), do: size(list) * 2"),
            usageLinesAtCaret()
        )
    }

    /** A `defp` in a `defimpl` implements nothing, so a use of it is not a use of another implementation. */
    fun testCallToAPrivateHelperIsNotAUseOfAnImplementation() {
        myFixture.configureByFiles("usages_private_helper_own_call.ex")

        assertEquals(
            listOf("def count(map), do: size(map)", "defp size(map), do: map_size(map)"),
            usageLinesAtCaret()
        )
    }

    /** An atom naming a `defp` in another `defimpl` is a use of that `defp`, not of the implementations. */
    fun testAtomNamingAPrivateHelperIsNotAUseOfTheImplementations() {
        myFixture.configureByFiles("usages_private_helper_atom.ex")

        assertEquals(
            listOf("def size(data)", "def size(list), do: length(list)", "def twice(list), do: size(list) * 2"),
            usageLinesAtCaret()
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

    private fun usageLinesAtCaret(): List<String> =
        myFixture.singleTargetPsiUsagesAtCaret(project)
            .sortedBy { it.range.startOffset }
            .map { usage ->
                val text = usage.file.text
                text.lines()[text.take(usage.range.startOffset).count { it == '\n' }].trim()
            }

    /**
     * Runs Find Usages on the `defprotocol def` at the caret in [files] and counts non-declaration usages
     * located on a **call site** - the function name of a `Call` that is not a call-definition clause
     * (`def`/`defmacro`). The protocol function's own self-declaration usage is excluded via
     * [com.intellij.find.usages.api.PsiUsage.declaration]. We resolve each usage by its `(file, range)` - the searcher models a
     * [com.intellij.find.usages.api.PsiUsage] as file + absolute range (so its `.element` is the file, not the call name), matching how
     * the tool window navigates.
     */
    @Suppress("UnstableApiUsage")
    private fun callSiteUsageCount(vararg files: String): Int {
        myFixture.configureByFiles(*files)
        return myFixture.singleTargetPsiUsagesAtCaret(project)
            .filterNot { it.declaration }
            .count { usage ->
                val element = usage.file.findElementAt(usage.range.startOffset)
                val enclosingCall = element?.let {
                    generateSequence(it) { e -> e.parent }
                        .filterIsInstance<Call>()
                        .firstOrNull()
                }
                enclosingCall != null && !CallDefinitionClause.`is`(enclosingCall)
            }
    }
}
