package org.elixir_lang.model.psi.function

import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.model.search.SearchService
import com.intellij.model.search.SearchWordQueryBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.replaceService
import com.intellij.util.concurrency.AppExecutorUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret
import org.elixir_lang.code_insight.renameTargetDirectly
import org.elixir_lang.code_insight.renameTargetsAtCaret
import org.elixir_lang.model.psi.ElixirRenameTarget
import org.elixir_lang.model.psi.RenameClosure
import java.lang.management.ManagementFactory
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Without `as:`, a `defdelegate`'s head also names the function it delegates to, so the two are one function to rename
 * and to find: from the head, the target's declaration or a use of either. With `as:` the head's name is the
 * delegation's own.
 */
@Suppress("UnstableApiUsage")
class DelegationRenameTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        HeadlessDataManager.fallbackToProductionDataManager(myFixture.testRootDisposable)
    }

    fun testRenameWithoutAs() =
        assertEveryOccurrence { caret ->
            myFixture.renameTargetAtCaret("fresh")
            assertEquals(caret, WITHOUT_AS.replace("renamee", "fresh"), myFixture.file.text)
        }

    fun testFindUsagesWithoutAs() =
        assertEveryOccurrence { caret -> assertEquals(caret, listOf(2, 3, 7, 8), usageLines()) }

    /** No call of a list of heads resolves, so a caret on one of its heads is on no function to rename or find. */
    fun testAOneHeadListDeclaresNothingAtItsHead() {
        myFixture.configureByText("m.ex", ONE_HEAD_LIST)
        myFixture.editor.caretModel.moveToOffset(ONE_HEAD_LIST.indexOf("[renamee") + 2)

        assertEquals(emptyList<Any>(), myFixture.renameTargetsAtCaret())
        assertEquals(emptyList<Int>(), usageLines())
    }

    /** A keyword naming a function a list of heads declares is on nothing to rename, as a caret on the head is. */
    fun testAKeywordNamingAListHeadDeclaresNothing() {
        myFixture.configureByText("m.ex", LIST_HEAD_KEYWORD)
        myFixture.editor.caretModel.moveToOffset(LIST_HEAD_KEYWORD.indexOf("inline, renamee") + "inline, ".length + 2)

        assertEquals(emptyList<Any>(), myFixture.renameTargetsAtCaret())
    }

    /** A target with defaults is declared at its lowest arity, and the head without `as:` still names it. */
    fun testFindUsagesFromATargetWithDefaults() {
        myFixture.configureByText("m.ex", DEFAULTS)
        myFixture.editor.caretModel.moveToOffset(DEFAULTS.indexOf("def renamee") + 5)

        assertEquals(listOf(2, 6, 7), usageLines())
    }

    fun testRenameFromATargetWithDefaults() {
        myFixture.configureByText("m.ex", DEFAULTS)
        myFixture.editor.caretModel.moveToOffset(DEFAULTS.indexOf("def renamee") + 5)

        myFixture.renameTargetAtCaret("fresh")

        assertEquals(DEFAULTS.replace("renamee", "fresh"), myFixture.file.text)
    }

    /** From the delegation's head or a call of it, the target with defaults renames with it, and no `as:` is added. */
    fun testRenameFromADelegationToATargetWithDefaults() =
        assertRenamesFrom(DEFAULTS, DEFAULTS.replace("renamee", "cons"), "defdelegate ren|amee", "M.ren|amee")

    /** A delegation to a protocol function without `as:` renames with it, from the protocol, the head or a use of either. */
    fun testRenameWithoutAsToAProtocolFunction() =
        assertRenamesFrom(
            PROTOCOL,
            PROTOCOL.replace("snoc", "cons"),
            "def sn|oc(t)\nend",
            "defdelegate sn|oc",
            "P.sn|oc",
            "Facade.sn|oc"
        )

    /**
     * Without `as:`, an import key and a capture of the delegation name its target by the same name, so they rename with
     * it, from the target, the head or any use, for a function and a protocol function alike.
     */
    fun testRenameWithoutAsRenamesTheDelegationsImportKeyAndCapture() {
        for ((text, target) in listOf(KEY_AND_CAPTURE to "T", KEY_AND_CAPTURE_PROTOCOL to "P")) {
            assertRenamesFrom(
                text,
                text.replace("snoc", "cons"),
                "def sn|oc(t)",
                "defdelegate sn|oc",
                "[sn|oc: 1]",
                "do: sn|oc(x)",
                "&Facade.sn|oc/1",
                "&$target.sn|oc/1"
            )
        }
    }

    /** From a function, a delegation's import key and capture are uses, as its head and calls are. */
    fun testFindUsagesFromAFunctionListsTheDelegationsImportKeyAndCapture() {
        myFixture.configureByText("t.ex", KEY_AND_CAPTURE)
        myFixture.editor.caretModel.moveToOffset(KEY_AND_CAPTURE.indexOf("def snoc") + 5)

        assertEquals(listOf(2, 6, 10, 11, 12, 13), usageLines())
    }

    /** From a protocol function, its own capture and a delegation's import key and capture are uses. */
    fun testFindUsagesFromAProtocolFunctionListsTheDelegationsImportKeyAndCaptures() {
        myFixture.configureByText("p.ex", KEY_AND_CAPTURE_PROTOCOL)
        myFixture.editor.caretModel.moveToOffset(KEY_AND_CAPTURE_PROTOCOL.indexOf("def snoc") + 5)

        assertEquals(listOf(2, 6, 10, 14, 15, 16, 17), usageLines())
    }

    /** From the protocol function, the head of a delegation to it without `as:` is a use, as it is of a function. */
    fun testFindUsagesFromAProtocolFunctionListsTheDelegationsHead() {
        myFixture.configureByText("p.ex", PROTOCOL)
        myFixture.editor.caretModel.moveToOffset(PROTOCOL.indexOf("def snoc") + 5)

        assertEquals(listOf(2, 6, 10, 11, 12), usageLines())
    }

    /**
     * A call of a delegation with `as:` names the delegation alone, even where `as:` gives the target's own name, so a
     * rename from the target leaves the delegation and its calls, for a function and a protocol function alike.
     */
    fun testRenameFromATargetLeavesACallThroughAs() {
        assertTargetRenameLeavesFacade(SAME_NAME_AS)
        assertTargetRenameLeavesFacade(SAME_NAME_AS_PROTOCOL)
    }

    /** An MFA atom naming a delegation with `as:` names the delegation alone, as a call does. */
    fun testRenameFromATargetLeavesAnMfaAtomThroughAs() =
        assertTargetRenameLeavesFacade(
            SAME_NAME_AS.replace("  def facade_use", "  def mfa(x), do: apply(Facade, :snoc, [x])\n  def facade_use")
        )

    fun testRenameFromTheHeadWithAs() {
        myFixture.configureByText("m.ex", WITH_AS)
        myFixture.editor.caretModel.moveToOffset(WITH_AS.indexOf("renamee") + 1)

        myFixture.renameTargetAtCaret("fresh")

        assertEquals(WITH_AS.replace("renamee", "fresh"), myFixture.file.text)
    }

    /** A use names the delegation, so with `as:` the rename leaves the target and `as:` as they are. */
    fun testRenameFromTheUseWithAs() {
        myFixture.configureByText("m.ex", WITH_AS)
        myFixture.editor.caretModel.moveToOffset(WITH_AS.indexOf("M.renamee") + 3)

        myFixture.renameTargetAtCaret("fresh")

        assertEquals(WITH_AS.replace("renamee", "fresh"), myFixture.file.text)
    }

    fun testFindUsagesFromTheUseWithAs() {
        myFixture.configureByText("m.ex", WITH_AS)
        myFixture.editor.caretModel.moveToOffset(WITH_AS.indexOf("M.renamee") + 3)

        assertEquals(listOf(6, 7), usageLines())
    }

    /**
     * Without `as:`, a delegation whose target is not found calls it by the head's name, so renaming the head and its
     * calls adds `as:` with the old name, from the head and from a use.
     */
    fun testRenameAddsAsAtAnUnresolvableTarget() =
        assertRenamesFrom(UNRESOLVABLE, UNRESOLVABLE.replace("snoc", "cons").replace("to: T", "to: T, as: :snoc"))

    fun testRenameAddsAsAtAWrongArityTarget() =
        assertRenamesFrom(
            WRONG_ARITY,
            WRONG_ARITY.replace("defdelegate snoc(q), to: T", "defdelegate cons(q), to: T, as: :snoc")
                .replace("M.snoc(1)", "M.cons(1)")
        )

    /** A target declared by `EEx.function_from_string` cannot be renamed, so it keeps its name and the head gains `as:`. */
    fun testRenameAddsAsAtAnEExTarget() =
        assertRenamesFrom(
            EEX,
            EEX.replace("defdelegate snoc(q), to: T", "defdelegate cons(q), to: T, as: :snoc")
                .replace("M.snoc(1)", "M.cons(1)")
        )

    /** Along a chain, only the delegation whose target is not found gains `as:`; the one before it renames with it. */
    fun testRenameAddsAsAtTheEndOfAChain() =
        assertRenamesFrom(
            CHAIN,
            CHAIN.replace("x(", "y(").replace("to: Nowhere", "to: Nowhere, as: :x"),
            "defdelegate x|(a), to: N",
            "M.x|(1)"
        )

    /** Options written as a bracketed keyword list take the `as:` inside the brackets. */
    fun testRenameAddsAsInsideBracketedOptions() =
        assertRenamesFrom(BRACKETED, BRACKETED.replace("snoc", "cons").replace("[to: T]", "[to: T, as: :snoc]"))

    /** Options on their own line take the `as:` after the last of them. */
    fun testRenameAddsAsAfterOptionsOnTheirOwnLine() =
        assertRenamesFrom(OWN_LINE, OWN_LINE.replace("snoc", "cons").replace("to: T\n", "to: T, as: :snoc\n"))

    /** A rename searched in a scope without the delegation still renames its head, so the head still gains its `as:`. */
    fun testRenameInAScopeWithoutTheDelegationStillAddsAs() {
        val m = myFixture.addFileToProject("m.ex", UNRESOLVABLE)
        val caller = myFixture.configureByText("caller.ex", "defmodule Caller do\n  def run, do: M.sn<caret>oc(1)\nend\n")

        myFixture.renameTargetDirectly(myFixture.renameTargetsAtCaret().single(), "cons", GlobalSearchScope.fileScope(caller))

        assertEquals(UNRESOLVABLE.replace("defdelegate snoc(q), to: T", "defdelegate cons(q), to: T, as: :snoc"), m.text)
    }

    fun testRenameAddsAsAtTheEndOfAChainToAnEExTarget() =
        assertRenamesFrom(
            CHAIN_TO_EEX,
            CHAIN_TO_EEX.replace("defdelegate x(a), to: N", "defdelegate y(a), to: N").replace("M.x(1)", "M.y(1)")
                .replace("defdelegate x(a), to: T", "defdelegate y(a), to: T, as: :x"),
            "defdelegate x|(a), to: N",
            "M.x|(1)"
        )

    /** With options that are not a keyword list, there is no `as:` to keep the delegation calling its target by. */
    fun testRenameIsRefusedWhenTheOptionsAreNotAKeywordList() {
        val reason = "its options are not a keyword list, so `as:` cannot be added"

        assertRefused(OPTIONS, "d(a), @opts", reason)
        assertRefused(OPTIONS, "d(1)\nend\n", reason)
    }

    fun testRenameIsRefusedAlongAChainEndingInOptionsThatAreNotAKeywordList() {
        val reason =
            "a function renamed with it delegates without `as:`, and its options are not a keyword list, so `as:` cannot be added"

        assertRefused(CHAIN_TO_OPTIONS, "x(a), to: N", reason)
        assertRefused(CHAIN_TO_OPTIONS, "x(1)\nend\n", reason)
    }

    /**
     * Of two implementations of one protocol function by a delegation, the refusal for the one whose options are not a
     * keyword list is its own from its head, and another's from the other's head.
     */
    fun testRenameIsRefusedForTheImplementationWhoseOptionsAreNotAKeywordList() {
        assertRefused(
            IMPLEMENTATIONS_WITH_OPTIONS,
            "snoc(list), to: Missing",
            "a function renamed with it delegates without `as:`, and its options are not a keyword list, so `as:` cannot be added"
        )
        assertRefused(
            IMPLEMENTATIONS_WITH_OPTIONS,
            "snoc(map), @opts",
            "its options are not a keyword list, so `as:` cannot be added"
        )
    }

    /** A delegation a `quote` injects is called in modules not known until the `quote` is used, even with a found target. */
    fun testRenameIsRefusedAtAnInjectedHeadWithAFoundTarget() {
        assertRefused(INJECTED, "d(a), to:", "declared by `quote`")
        assertRefused(INJECTED, "d(1)\nend\n", "declared by `quote`")
        assertRefused(INJECTED, "d(a), do: a", "declared by `quote`")
    }

    /**
     * A delegation whose target's own rename is refused, here by a delegation a `quote` injects that renames with the
     * target, gains `as:` instead of being refused.
     */
    fun testRenameAddsAsWhereTheTargetIsRefusedThroughAnInjectedDelegation() {
        val text = "$INJECTED\ndefmodule F do\n  defdelegate d(a), to: T\n  def f_use, do: F.d(1)\nend\n"

        assertRenamesFrom(
            text,
            text.replace(
                "defdelegate d(a), to: T\n  def f_use, do: F.d(1)",
                "defdelegate cons(a), to: T, as: :d\n  def f_use, do: F.cons(1)"
            ),
            "defdelegate d|(a), to: T\n  def f_use",
            "F.d|(1)"
        )
    }

    /**
     * In a chain to a target whose own rename is refused, only the delegation calling that target gains `as:`; one
     * delegating to it renames with it, from every caret.
     */
    fun testRenameAddsAsOnlyWhereTheChainReachesTheRefusedTarget() {
        val text =
            "$INJECTED\ndefmodule F do\n  defdelegate d(a), to: T\n  def f_use, do: F.d(1)\nend\n\n" +
                "defmodule Y do\n  defdelegate d(a), to: F\n  def y_use, do: Y.d(1)\nend\n"

        assertRenamesFrom(
            text,
            text.replace(
                "defdelegate d(a), to: T\n  def f_use, do: F.d(1)",
                "defdelegate cons(a), to: T, as: :d\n  def f_use, do: F.cons(1)"
            ).replace(
                "defdelegate d(a), to: F\n  def y_use, do: Y.d(1)",
                "defdelegate cons(a), to: F\n  def y_use, do: Y.cons(1)"
            ),
            "defdelegate d|(a), to: T\n  def f_use",
            "F.d|(1)",
            "defdelegate d|(a), to: F",
            "Y.d|(1)"
        )
    }

    /** Renaming from a use along a long chain renames every head and the target. */
    fun testRenameAlongALongChain() {
        val chain = longChain(LONG_CHAIN_LENGTH)

        assertRenamesFrom(chain, chain.replace("x(", "y("), "M.x|(1)")
    }

    /**
     * Doubling the chain multiplies the rename closure's cost by less than [CLOSURE_GROWTH_BOUND]. The closure is not
     * linear in the chain (5 to 6 times per doubling), and one more factor of the chain's length in its work
     * doubles that again. A ratio of two costs on this run holds on a slow runner where a time would not.
     */
    fun testRenameClosureGrowsBoundedlyAlongAChain() {
        val short = closureCost(LONG_CHAIN_LENGTH)
        val long = closureCost(2 * LONG_CHAIN_LENGTH)

        assertTrue(
            "the closure took ${long / 1_000_000} ms along ${2 * LONG_CHAIN_LENGTH} delegations, over " +
                "$CLOSURE_GROWTH_BOUND times its ${short / 1_000_000} ms along $LONG_CHAIN_LENGTH",
            long < CLOSURE_GROWTH_BOUND * short
        )
    }

    /** A chain's name is searched for once for the whole rename closure, not once for each delegation along it. */
    fun testRenameClosureSearchesAChainsNameOnce() {
        val text = longChain(LONG_CHAIN_LENGTH)
        myFixture.configureByText("chain.ex", text)
        val virtualFile = myFixture.file.virtualFile
        myFixture.editor.caretModel.moveToOffset(text.indexOf("M.x(1)") + 3)
        val searched = mutableListOf<String>()

        try {
            val target = myFixture.renameTargetsAtCaret().single() as ElixirRenameTarget
            ApplicationManager.getApplication().replaceService(
                SearchService::class.java,
                WordRecordingSearchService(SearchService.getInstance(), searched),
                myFixture.testRootDisposable
            )

            closureWithinCeiling(target)

            assertEquals(listOf("x"), searched.filter { it == "x" })
        } finally {
            WriteCommandAction.runWriteCommandAction(project) { virtualFile.delete(this) }
        }
    }

    /** [target]'s rename closure, which fails the test rather than running on if it has not finished within [CLOSURE_CEILING]. */
    private fun closureWithinCeiling(target: ElixirRenameTarget): RenameClosure {
        val indicator = EmptyProgressIndicator()
        val ceiling = AppExecutorUtil.getAppScheduledExecutorService()
            .schedule({ indicator.cancel() }, CLOSURE_CEILING.toMillis(), TimeUnit.MILLISECONDS)

        try {
            return ProgressManager.getInstance().runProcess(
                Computable { runReadActionBlocking { target.renameClosure() } },
                indicator
            )
        } catch (e: ProcessCanceledException) {
            throw AssertionError("the closure was still running after $CLOSURE_CEILING", e)
        } finally {
            ceiling.cancel(false)
        }
    }

    /** The CPU time of the best of three computations of the closure of `M.x/1` along [length] delegations, once warm. */
    private fun closureCost(length: Int): Long {
        val text = longChain(length)
        myFixture.configureByText("chain$length.ex", text)
        val virtualFile = myFixture.file.virtualFile
        myFixture.editor.caretModel.moveToOffset(text.indexOf("M.x(1)") + 3)

        try {
            val target = myFixture.renameTargetsAtCaret().single() as ElixirRenameTarget
            val thread = ManagementFactory.getThreadMXBean()

            fun once(): Long {
                val start = thread.currentThreadCpuTime
                val closure = closureWithinCeiling(target)
                val cost = thread.currentThreadCpuTime - start

                assertEquals(length + 1, closure.members.size)
                return cost
            }

            once()
            return (1..3).minOf { once() }
        } finally {
            WriteCommandAction.runWriteCommandAction(project) { virtualFile.delete(this) }
        }
    }

    /**
     * A `defimpl` implementing a protocol function by a delegation without `as:` keeps implementing it, whichever of
     * the target, the delegation, the protocol function or a call of it the rename starts from.
     */
    fun testRenameAProtocolImplementationByDelegation() =
        assertRenamesFrom(
            IMPLEMENTED_BY_DELEGATION,
            IMPLEMENTED_BY_DELEGATION.replace("snoc", "cons"),
            "def sn|oc(x)",
            "defdelegate sn|oc(list), to: T",
            "def sn|oc(t)",
            "P.sn|oc"
        )

    /** Each of two implementations by a delegation whose target is not found gains its own `as:`. */
    fun testRenameAddsAsToEachProtocolImplementationByDelegation() =
        assertRenamesFrom(
            TWO_IMPLEMENTATIONS_BY_DELEGATION,
            TWO_IMPLEMENTATIONS_BY_DELEGATION.replace("snoc", "cons")
                .replace("to: Missing\n", "to: Missing, as: :snoc\n")
                .replace("to: Absent\n", "to: Absent, as: :snoc\n"),
            "def sn|oc(t)",
            "defdelegate sn|oc(list), to: Missing",
            "defdelegate sn|oc(map), to: Absent",
            "P.sn|oc"
        )

    /**
     * Of two implementations by a delegation, the one whose target is found renames with it and the one whose target is
     * not found gains `as:`.
     */
    fun testRenameDecidesAsForEachProtocolImplementationByDelegation() =
        assertRenamesFrom(
            FOUND_AND_MISSING_IMPLEMENTATIONS,
            FOUND_AND_MISSING_IMPLEMENTATIONS.replace("snoc", "cons").replace("to: Missing\n", "to: Missing, as: :snoc\n"),
            "def sn|oc(t)",
            "defdelegate sn|oc(list), to: T",
            "defdelegate sn|oc(map), to: Missing",
            "P.sn|oc"
        )

    /**
     * Of two implementations by a delegation, the one another protocol's implementation delegates to takes that
     * implementation and its protocol into the rename.
     */
    fun testRenameTakesTheProtocolWhoseImplementationDelegatesToOneImplementation() =
        assertRenamesFrom(
            IMPLEMENTATION_DELEGATED_TO,
            IMPLEMENTATION_DELEGATED_TO.replace("size", "cons")
                .replace("to: Missing\n", "to: Missing, as: :size\n")
                .replace("to: Absent\n", "to: Absent, as: :size\n"),
            "def si|ze(t)\nend\n\ndefimpl P, for: Tuple",
            "defdelegate si|ze(t), to: Missing",
            "defdelegate si|ze(t), to: Absent",
            "P.si|ze(x)",
            "Q.si|ze(x)"
        )

    /** As [testRenameAProtocolImplementationByDelegation], through a facade whose target is not found, which gains `as:`. */
    fun testRenameAProtocolImplementationThroughAFacade() =
        assertRenamesFrom(
            IMPLEMENTED_THROUGH_FACADE,
            IMPLEMENTED_THROUGH_FACADE.replace("snoc", "cons").replace("to: Missing\n", "to: Missing, as: :snoc\n"),
            "defdelegate sn|oc(list), to: Missing",
            "defdelegate sn|oc(list), to: Facade",
            "def sn|oc(t)",
            "P.sn|oc"
        )

    /** As [testRenameAProtocolImplementationByDelegation], for a callback a delegation without `as:` implements. */
    fun testRenameACallbackImplementationByDelegation() =
        assertRenamesFrom(
            CALLBACK_BY_DELEGATION,
            CALLBACK_BY_DELEGATION.replace("snoc", "cons"),
            "def sn|oc(x)",
            "defdelegate sn|oc(list), to: T",
            "@callback sn|oc",
            "Impl.sn|oc"
        )

    /** The rename with the caret at [caret] in [text] is refused with a reason containing [reason], and the file is unchanged. */
    private fun assertRefused(text: String, caret: String, reason: String) {
        myFixture.configureByText("refused.ex", text)
        myFixture.editor.caretModel.moveToOffset(text.lastIndexOf(caret))
        val targets = myFixture.renameTargetsAtCaret()

        assertNotEmpty(targets)

        for (target in targets) {
            val failure = runCatching { myFixture.renameTarget(target, "fresh") }.exceptionOrNull()

            assertTrue(
                "Renaming $target at `$caret` should be refused as $reason, but " +
                    (failure?.let { "failed with $it" } ?: "renamed it"),
                generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains(reason) }
            )
        }

        assertEquals(text, myFixture.file.text)
    }

    /**
     * Renames from each caret in a fresh copy of [text], to `y` at a caret on `x` and to `cons` otherwise, and asserts
     * the result is [after]. A caret is written as the text around it, with `|` where it goes.
     */
    private fun assertRenamesFrom(
        text: String,
        after: String,
        vararg carets: String = arrayOf("defdelegate sn|oc", "M.sn|oc")
    ) {
        carets.forEachIndexed { index, caret ->
            myFixture.configureByText("from$index.ex", text)
            val virtualFile = myFixture.file.virtualFile
            myFixture.editor.caretModel.moveToOffset(text.indexOf(caret.replace("|", "")) + caret.indexOf('|'))

            try {
                myFixture.renameTargetAtCaret(if ("x|" in caret) "y" else "cons")
                assertEquals("from `$caret`", after, myFixture.file.text)
            } finally {
                WriteCommandAction.runWriteCommandAction(project) { virtualFile.delete(this) }
            }
        }
    }

    /**
     * Renames `snoc` to `cons` from the target's head in [text] and checks only that head changed. The `as:` atom is
     * masked: nothing references it yet, so it keeps the old name.
     */
    private fun assertTargetRenameLeavesFacade(text: String) {
        myFixture.configureByText("through_as.ex", text)
        val virtualFile = myFixture.file.virtualFile
        myFixture.editor.caretModel.moveToOffset(text.indexOf("def snoc") + "def sn".length)

        try {
            myFixture.renameTargetAtCaret("cons")
            val masked = { written: String -> written.replace(Regex("as: :\\w+"), "as: :_") }
            assertEquals(masked(text.replace("def snoc", "def cons")), masked(myFixture.file.text))
        } finally {
            WriteCommandAction.runWriteCommandAction(project) { virtualFile.delete(this) }
        }
    }

    /** Runs [check] with the caret on each occurrence of `renamee` in a fresh copy of [WITHOUT_AS]. */
    private fun assertEveryOccurrence(check: (caret: String) -> Unit) {
        val text = WITHOUT_AS
        Regex("""\brenamee\b""").findAll(text).forEachIndexed { index, occurrence ->
            myFixture.configureByText("caret$index.ex", text)
            val virtualFile = myFixture.file.virtualFile
            myFixture.editor.caretModel.moveToOffset(occurrence.range.first + 1)

            try {
                check("caret on line ${text.take(occurrence.range.first).count { it == '\n' } + 1}")
            } finally {
                WriteCommandAction.runWriteCommandAction(project) { virtualFile.delete(this) }
            }
        }
    }

    private fun usageLines(): List<Int> =
        myFixture.psiUsagesAtCaret(project)
            .map { usage -> usage.file.text.take(usage.range.startOffset).count { it == '\n' } + 1 }
            .sorted()

    private companion object {
        const val WITHOUT_AS =
            "defmodule T do\n  def renamee(x), do: x\n  def t_use, do: T.renamee(1)\nend\n\n" +
                "defmodule M do\n  defdelegate renamee(x), to: T\n  def m_use, do: M.renamee(1)\nend\n"

        const val ONE_HEAD_LIST =
            "defmodule T do\n  def renamee(x), do: x\nend\n\n" +
                "defmodule M do\n  defdelegate [renamee(x)], to: T\n  def m_use, do: M.renamee(1)\nend\n"

        const val LIST_HEAD_KEYWORD =
            "defmodule T do\n  def renamee(x), do: x\n  def renamee(x, y), do: {x, y}\nend\n\n" +
                "defmodule M do\n  defdelegate [renamee(x), renamee(x, y)], to: T\n  @compile {:inline, renamee: 1}\n" +
                "  def m_use, do: M.renamee(1)\nend\n"

        const val DEFAULTS =
            "defmodule T do\n  def renamee(a, b \\\\ nil), do: {a, b}\nend\n\n" +
                "defmodule M do\n  defdelegate renamee(a, b \\\\ nil), to: T\n  def m_use, do: M.renamee(1)\nend\n"

        const val WRONG_ARITY =
            "defmodule T do\n  def snoc(q, x), do: {q, x}\n  def t_use, do: T.snoc(1, 2)\nend\n\n" +
                "defmodule M do\n  defdelegate snoc(q), to: T\n  def m_use, do: M.snoc(1)\nend\n"

        const val EEX =
            "defmodule T do\n  require EEx\n  EEx.function_from_string(:def, :snoc, \"<%= q %>\", [:q])\nend\n\n" +
                "defmodule M do\n  defdelegate snoc(q), to: T\n  def m_use, do: M.snoc(1)\nend\n"

        const val CHAIN_TO_EEX =
            "defmodule T do\n  require EEx\n  EEx.function_from_string(:def, :x, \"<%= a %>\", [:a])\nend\n\n" +
                "defmodule N do\n  defdelegate x(a), to: T\nend\n\n" +
                "defmodule M do\n  defdelegate x(a), to: N\n  def m_use, do: M.x(1)\nend\n"

        const val INJECTED =
            "defmodule T do\n  def d(a), do: a\nend\n\n" +
                "defmodule U do\n  defmacro __using__(_) do\n    quote do\n      defdelegate d(a), to: T\n    end\n  end\nend\n\n" +
                "defmodule M do\n  use U\n  def m_use, do: d(1)\nend\n"

        const val CHAIN =
            "defmodule N do\n  defdelegate x(a), to: Nowhere\nend\n\n" +
                "defmodule M do\n  defdelegate x(a), to: N\n  def m_use, do: M.x(1)\nend\n"

        const val CHAIN_TO_OPTIONS =
            "defmodule N do\n  @opts [to: T]\n  defdelegate x(a), @opts\nend\n\n" +
                "defmodule M do\n  defdelegate x(a), to: N\n  def m_use, do: M.x(1)\nend\n"

        const val IMPLEMENTATIONS_WITH_OPTIONS =
            "defprotocol P do\n  def snoc(t)\nend\n\n" +
                "defimpl P, for: List do\n  defdelegate snoc(list), to: Missing\nend\n\n" +
                "defimpl P, for: Map do\n  @opts [to: Absent]\n  defdelegate snoc(map), @opts\nend\n"

        const val OPTIONS = "defmodule M do\n  @opts [to: T]\n  defdelegate d(a), @opts\n  def m_use, do: M.d(1)\nend\n"

        const val UNRESOLVABLE = "defmodule M do\n  defdelegate snoc(q), to: T\n  def m_use, do: M.snoc(1)\nend\n"

        const val BRACKETED = "defmodule M do\n  defdelegate snoc(q), [to: T]\n  def m_use, do: M.snoc(1)\nend\n"

        const val OWN_LINE = "defmodule M do\n  defdelegate snoc(q),\n    to: T\n\n  def m_use, do: M.snoc(1)\nend\n"

        const val WITH_AS =
            "defmodule T do\n  def other(x), do: x\nend\n\n" +
                "defmodule M do\n  defdelegate renamee(x), to: T, as: :other\n  def m_use, do: M.renamee(1)\nend\n"

        const val SAME_NAME_AS =
            "defmodule T do\n  def snoc(t), do: t\nend\n\n" +
                "defmodule Facade do\n  defdelegate snoc(t), to: T, as: :snoc\n  def facade_use(x), do: Facade.snoc(x)\nend\n"

        const val SAME_NAME_AS_PROTOCOL =
            "defprotocol P do\n  def snoc(t)\nend\n\n" +
                "defmodule Facade do\n  defdelegate snoc(t), to: P, as: :snoc\n  def facade_use(x), do: Facade.snoc(x)\nend\n"

        const val KEY_AND_CAPTURE =
            "defmodule T do\n  def snoc(t), do: t\nend\n\n" +
                "defmodule Facade do\n  defdelegate snoc(t), to: T\nend\n\n" +
                "defmodule User do\n  import Facade, only: [snoc: 1]\n  def call(x), do: snoc(x)\n" +
                "  def capture, do: &Facade.snoc/1\n  def direct, do: &T.snoc/1\nend\n"

        const val KEY_AND_CAPTURE_PROTOCOL =
            "defprotocol P do\n  def snoc(t)\nend\n\n" +
                "defimpl P, for: Integer do\n  def snoc(t), do: t\nend\n\n" +
                "defmodule Facade do\n  defdelegate snoc(t), to: P\nend\n\n" +
                "defmodule User do\n  import Facade, only: [snoc: 1]\n  def call(x), do: snoc(x)\n" +
                "  def capture, do: &Facade.snoc/1\n  def direct, do: &P.snoc/1\nend\n"

        const val LONG_CHAIN_LENGTH = 18

        const val CLOSURE_GROWTH_BOUND = 8

        /** Ends a closure that does not finish, and is no assertion: one along the longest chain here takes well under a second. */
        val CLOSURE_CEILING: Duration = Duration.ofMinutes(2)

        /** `M.x/1` delegating along [length] delegations, from the last `D` down to `D1`, then to `T.x/1`. */
        fun longChain(length: Int) =
            "defmodule T do\n  def x(a), do: a\nend\n\n" +
                (1..length).joinToString("") { i ->
                    "defmodule D$i do\n  defdelegate x(a), to: ${if (i == 1) "T" else "D${i - 1}"}\nend\n\n"
                } +
                "defmodule M do\n  defdelegate x(a), to: D$length\n  def m_use, do: M.x(1)\nend\n"

        const val PROTOCOL =
            "defprotocol P do\n  def snoc(t)\nend\n\n" +
                "defimpl P, for: Integer do\n  def snoc(t), do: t\nend\n\n" +
                "defmodule Facade do\n  defdelegate snoc(t), to: P\n  def p_use(x), do: P.snoc(x)\n" +
                "  def facade_use(x), do: Facade.snoc(x)\nend\n"

        const val IMPLEMENTED_BY_DELEGATION =
            "defmodule T do\n  def snoc(x), do: x\nend\n\n" +
                "defprotocol P do\n  def snoc(t)\nend\n\n" +
                "defimpl P, for: List do\n  defdelegate snoc(list), to: T\nend\n\n" +
                "defmodule U do\n  def u_use(x), do: P.snoc(x)\nend\n"

        const val TWO_IMPLEMENTATIONS_BY_DELEGATION =
            "defprotocol P do\n  def snoc(t)\nend\n\n" +
                "defimpl P, for: List do\n  defdelegate snoc(list), to: Missing\nend\n\n" +
                "defimpl P, for: Map do\n  defdelegate snoc(map), to: Absent\nend\n\n" +
                "defmodule U do\n  def u_use(x), do: P.snoc(x)\nend\n"

        const val FOUND_AND_MISSING_IMPLEMENTATIONS =
            "defmodule T do\n  def snoc(x), do: x\nend\n\n" +
                "defprotocol P do\n  def snoc(t)\nend\n\n" +
                "defimpl P, for: List do\n  defdelegate snoc(list), to: T\nend\n\n" +
                "defimpl P, for: Map do\n  defdelegate snoc(map), to: Missing\nend\n\n" +
                "defmodule U do\n  def u_use(x), do: P.snoc(x)\nend\n"

        const val IMPLEMENTATION_DELEGATED_TO =
            "defprotocol P do\n  def size(t)\nend\n\n" +
                "defimpl P, for: Tuple do\n  defdelegate size(t), to: Missing\nend\n\n" +
                "defimpl P, for: BitString do\n  defdelegate size(t), to: Absent\nend\n\n" +
                "defprotocol Q do\n  def size(t)\nend\n\n" +
                "defimpl Q, for: List do\n  defdelegate size(t), to: P.BitString\nend\n\n" +
                "defmodule U do\n  def p_use(x), do: P.size(x)\n  def q_use(x), do: Q.size(x)\nend\n"

        const val IMPLEMENTED_THROUGH_FACADE =
            "defmodule Facade do\n  defdelegate snoc(list), to: Missing\nend\n\n" +
                "defprotocol P do\n  def snoc(t)\nend\n\n" +
                "defimpl P, for: List do\n  defdelegate snoc(list), to: Facade\nend\n\n" +
                "defmodule U do\n  def u_use(x), do: P.snoc(x)\nend\n"

        const val CALLBACK_BY_DELEGATION =
            "defmodule B do\n  @callback snoc(term) :: term\nend\n\n" +
                "defmodule T do\n  def snoc(x), do: x\nend\n\n" +
                "defmodule Impl do\n  @behaviour B\n  defdelegate snoc(list), to: T\nend\n\n" +
                "defmodule U do\n  def u_use(x), do: Impl.snoc(x)\nend\n"
    }
}

/** The platform's [SearchService] that notes each word a word search is built for. */
@Suppress("UnstableApiUsage")
private class WordRecordingSearchService(
    private val real: SearchService,
    private val words: MutableList<String>
) : SearchService by real {
    override fun searchWord(project: Project, word: String): SearchWordQueryBuilder {
        words += word

        return real.searchWord(project, word)
    }
}
