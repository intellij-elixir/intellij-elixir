package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import org.elixir_lang.FIXED_STACK
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.SMALL_STACK
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.expander.ExpanderThreads.Companion.KEEP_ALIVE_SECONDS
import org.elixir_lang.expander.ExpanderThreads.Companion.THREAD_NAME
import org.elixir_lang.expander.interpreter.NESTED_CALLS
import org.elixir_lang.junit.hangDump
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.onAStack
import org.elixir_lang.psi.scope.WalkTestSupport

/**
 * How deep Elixir calls nest before the expander stops them: [NESTED_CALLS] levels, through every construct that calls,
 * whatever stack the asking thread has.
 */
class BeamLevelBoundTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        WalkTestSupport.addLibrary(project, module, testRootDisposable, "leg_elixir", LegManifest.ebin())
    }

    fun testABodyRecursionPastAMebibyteGivesItsValue() = assertHalfTheBoundGivesItsValue(BODY)

    fun testABodyRecursionPastAMebibyteThroughAnFnGivesItsValue() = assertHalfTheBoundGivesItsValue(FN)

    fun testABodyRecursionPastAMebibyteThroughACaptureGivesItsValue() = assertHalfTheBoundGivesItsValue(CAPTURE)

    fun testABodyRecursionPastAMebibyteThroughANativeCallbackGivesItsValue() = assertHalfTheBoundGivesItsValue(NATIVE_CALLBACK)

    fun testABodyRecursionPastAMebibyteThroughCaseGivesItsValue() = assertHalfTheBoundGivesItsValue(CASE)

    fun testABodyRecursionPastAMebibyteThroughTryGivesItsValue() = assertHalfTheBoundGivesItsValue(TRY)

    fun testABodyRecursionPastAMebibyteThroughForGivesItsValue() = assertHalfTheBoundGivesItsValue(FOR)

    fun testABodyRecursionPastAMebibyteThroughWithGivesItsValue() = assertHalfTheBoundGivesItsValue(WITH)

    fun testOneLevelUnderTheBoundABodyRecursionGivesItsValue() = assertUnderTheBound(BODY)

    fun testOneLevelUnderTheBoundThroughAnFnGivesItsValue() = assertUnderTheBound(FN)

    fun testOneLevelUnderTheBoundThroughACaptureGivesItsValue() = assertUnderTheBound(CAPTURE)

    fun testOneLevelUnderTheBoundThroughANativeCallbackGivesItsValue() = assertUnderTheBound(NATIVE_CALLBACK)

    fun testOneLevelUnderTheBoundThroughCaseGivesItsValue() = assertUnderTheBound(CASE)

    fun testOneLevelUnderTheBoundThroughTryGivesItsValue() = assertUnderTheBound(TRY)

    fun testOneLevelUnderTheBoundThroughForGivesItsValue() = assertUnderTheBound(FOR)

    fun testOneLevelUnderTheBoundThroughWithGivesItsValue() = assertUnderTheBound(WITH)

    fun testOneLevelPastTheBoundABodyRecursionStops() = assertPastTheBound(BODY)

    fun testOneLevelPastTheBoundThroughAnFnStops() = assertPastTheBound(FN)

    fun testOneLevelPastTheBoundThroughACaptureStops() = assertPastTheBound(CAPTURE)

    fun testOneLevelPastTheBoundThroughANativeCallbackStops() = assertPastTheBound(NATIVE_CALLBACK)

    fun testOneLevelPastTheBoundThroughCaseStops() = assertPastTheBound(CASE)

    fun testOneLevelPastTheBoundThroughTryStops() = assertPastTheBound(TRY)

    fun testOneLevelPastTheBoundThroughForStops() = assertPastTheBound(FOR)

    fun testOneLevelPastTheBoundThroughWithStops() = assertPastTheBound(WITH)

    /** A recursion with no base case stops with one text at one line, asked from either stack, twice each. */
    fun testAnUnboundedRecursionStopsAtTheSameLineOnEveryStack() {
        val ends = listOf(FIXED_STACK, SMALL_STACK, FIXED_STACK, SMALL_STACK).mapIndexed { run, stack ->
            ended(add("unbounded_$run.ex", UNBOUNDED), stack)
        }

        assertEquals(List(ends.size) { stopped(0) }, ends)
    }

    /** A compute that stops at the bound leaves nothing counted on its thread: the next compute there runs to its bound. */
    fun testAPooledThreadStartsEachComputeAtZero() {
        // With none left idle by earlier tests, the next compute runs on the one thread the first left idle, once that
        // thread is back in the pool: one asked before then gets a new thread.
        retired()
        ended(add("unbounded.ex", UNBOUNDED))
        val first = expanderThreads()
        first.singleOrNull()?.let(::idle)

        val value = ended(add("under.ex", based(BODY, NESTED_CALLS - 2)))
        val after = expanderThreads()
        val threads = if (first.size == 1 && after == first) "on the one thread" else "on $first, then $after"

        assertEquals("${valued(NESTED_CALLS - 2)} on the one thread", "$value $threads")
    }

    /** After a recursion stops at the bound, the expander's threads retire once idle for their keep-alive. */
    fun testThePoolsThreadsRetireAfterAStopAtTheBound() {
        ended(add("unbounded.ex", UNBOUNDED))
        assertNotEmpty(expanderThreads())

        retired()
    }

    /** Once [thread] is back in the pool, waiting for its next compute. */
    private fun idle(thread: Thread) {
        PlatformTestUtil.waitWithEventsDispatching({ "still busy: $thread ${thread.state}" }, { thread.state == Thread.State.TIMED_WAITING }, 5)
    }

    /** Once every expander thread has retired, within its keep-alive. */
    private fun retired() {
        PlatformTestUtil.waitWithEventsDispatching({ "still live: ${expanderThreads()}" }, { expanderThreads().isEmpty() }, KEEP_ALIVE_SECONDS.toInt() + 5)
    }

    /** `R.f(B / 2)` through [construct] gives `B / 2`. */
    private fun assertHalfTheBoundGivesItsValue(construct: String) = assertGivesItsValue(construct, NESTED_CALLS / 2)

    /** `R.f(n)` nests `n + 1` calls, so `R.f(B - 2)` is one level under the bound, and gives its value. */
    private fun assertUnderTheBound(construct: String) = assertGivesItsValue(construct, NESTED_CALLS - 2)

    /** `R.f(levels)` through [construct] gives `levels`. */
    private fun assertGivesItsValue(construct: String, levels: Int) {
        assertEquals(valued(levels), ended(add("valued.ex", based(construct, levels))))
    }

    /** `R.f(B)` nests `B + 1` calls, one past the bound, and stops. */
    private fun assertPastTheBound(construct: String) {
        assertEquals(stopped(NESTED_CALLS), ended(add("past.ex", based(construct, NESTED_CALLS))))
    }

    /**
     * The live threads the expander made: named [THREAD_NAME], with ` @coroutine#<n>` after it while one runs a coroutine
     * under `-ea`, which turns on kotlinx's debug mode.
     */
    private fun expanderThreads(): Set<Thread> = Thread.getAllStackTraces().keys.filterTo(HashSet()) { it.name.startsWith(THREAD_NAME) }

    private fun add(path: String, text: String): PsiFile = myFixture.addFileToProject("lib/$path", text)

    /**
     * `S`'s `@a`, read by a fresh holder from a thread of [stack] bytes: its value, or where `S` stopped, and what it
     * didn't run there and why. An expansion still running after [EXPANSION_BOUND_MS] fails the test, leaving its thread
     * running, with the JVM's threads also written to stderr, which reaches the results even if the test never ends.
     */
    private fun ended(file: PsiFile, stack: Long = FIXED_STACK): String {
        val expansion = onAStack(stack, EXPANSION_BOUND_MS, { stillExpanding() }) {
            ReadAction.computeBlocking<FileExpansion, Throwable> { freshExpansion(project, file) }
        }!!.getOrThrow()
        val result = ProjectModules.results(expansion).single { it.module == "Elixir.S" }
        val ended = result.ended

        if (ended !is ExpansionResult.Ended.Stopped) return "${result.attributes.final["a"]}"

        val text = file.text
        val source = { node: ElixirAst -> node.meta.origin.substring(text).lines().first() }

        return "stopped at `${source(ended.at)}`: ${result.written?.let { "`${source(it.at)}` ${it.declined}" }}"
    }

    private fun stillExpanding() {
        val message = "still expanding after $EXPANSION_BOUND_MS ms\n\n${hangDump()}"
        System.err.println(message)
        fail(message)
    }

    private companion object {
        /** Many times the slowest expansion here takes. */
        const val EXPANSION_BOUND_MS = 60_000L

        /** `f(n)`'s recursive clause, `n` levels to the base case `f(0)`, each through the construct. */
        const val BODY = "def f(n), do: 1 + f(n - 1)"
        const val FN = "def f(n), do: 1 + (fn x -> f(x - 1) end).(n)"
        const val CAPTURE = "def f(n) do\n    g = &f/1\n    1 + g.(n - 1)\n  end"
        const val NATIVE_CALLBACK = "def f(n), do: 1 + hd(Enum.map([n - 1], &f/1))"
        const val CASE = "def f(n) do\n    case n do\n      x -> 1 + f(x - 1)\n    end\n  end"
        const val TRY = "def f(n) do\n    try do\n      1 + f(n - 1)\n    after\n      :ok\n    end\n  end"
        const val FOR = "def f(n), do: 1 + hd(for x <- [n - 1], do: f(x))"
        const val WITH = "def f(n) do\n    with x <- n - 1 do\n      1 + f(x)\n    end\n  end"

        /** `R`, whose `f/1` recurses through [construct] to `f(0)`, and `S`, whose `@a` is `R.f(levels)`. */
        fun based(construct: String, levels: Int): String = source("def f(0), do: 0\n  $construct", levels)

        /** As [based] through [BODY], with no base case. */
        val UNBOUNDED: String = source(BODY, 0)

        /** What [ended] gives where `R.f(levels)` runs past the bound: `S` stops at the call, as too deep. */
        fun stopped(levels: Int): String = "stopped at `defmodule S do`: `R.f($levels)` nests too deeply to run"

        /** What [ended] gives for `@a` holding [n]. */
        fun valued(n: Int): String = "Known(term=Integer(value=$n))"

        fun source(clauses: String, levels: Int): String =
            "defmodule R do\n  $clauses\nend\n\ndefmodule S do\n  @a R.f($levels)\n  def a, do: @a\nend\n"
    }
}
