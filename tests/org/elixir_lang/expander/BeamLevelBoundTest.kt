package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiFile
import org.elixir_lang.FIXED_STACK
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.junit.hangDump
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.onAStack
import org.elixir_lang.psi.scope.WalkTestSupport

/** How deep Elixir calls nest before the expander stops them, through every construct that calls, whatever stack asks. */
class BeamLevelBoundTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        WalkTestSupport.addLibrary(project, module, testRootDisposable, "leg_elixir", LegManifest.ebin())
    }

    fun testABodyRecursionPastAMebibyteGivesItsValue() = assertPastAMebibyteGivesItsValue(BODY)

    fun testABodyRecursionPastAMebibyteThroughAnFnGivesItsValue() = assertPastAMebibyteGivesItsValue(FN)

    fun testABodyRecursionPastAMebibyteThroughACaptureGivesItsValue() = assertPastAMebibyteGivesItsValue(CAPTURE)

    fun testABodyRecursionPastAMebibyteThroughANativeCallbackGivesItsValue() = assertPastAMebibyteGivesItsValue(NATIVE_CALLBACK)

    fun testABodyRecursionPastAMebibyteThroughCaseGivesItsValue() = assertPastAMebibyteGivesItsValue(CASE)

    fun testABodyRecursionPastAMebibyteThroughTryGivesItsValue() = assertPastAMebibyteGivesItsValue(TRY)

    fun testABodyRecursionPastAMebibyteThroughForGivesItsValue() = assertPastAMebibyteGivesItsValue(FOR)

    fun testABodyRecursionPastAMebibyteThroughWithGivesItsValue() = assertPastAMebibyteGivesItsValue(WITH)

    /** `R.f(PAST_A_MEBIBYTE)` through [construct] gives its value. */
    private fun assertPastAMebibyteGivesItsValue(construct: String) {
        assertEquals(valued(PAST_A_MEBIBYTE), ended(add("past_a_mebibyte.ex", based(construct, PAST_A_MEBIBYTE))))
    }

    private fun add(path: String, text: String): PsiFile = myFixture.addFileToProject("lib/$path", text)

    /**
     * `S`'s `@a`, read by a fresh holder from a thread of [stack] bytes: its value, or where `S` stopped, and what it
     * didn't run there and why. An expansion still running after [EXPANSION_BOUND_MS] fails the test, leaving its thread
     * running, with the JVM's threads also written to stderr, which reaches the results even if the test never ends.
     * The read there is a blocking one, as [onAStack] needs on the EDT.
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
        /** Levels deeper than a mebibyte's stack holds through any construct. */
        const val PAST_A_MEBIBYTE = 10_000

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

        /** What [ended] gives for `@a` holding [n]. */
        fun valued(n: Int): String = "Known(term=Integer(value=$n))"

        fun source(clauses: String, levels: Int): String =
            "defmodule R do\n  $clauses\nend\n\ndefmodule S do\n  @a R.f($levels)\n  def a, do: @a\nend\n"
    }
}
