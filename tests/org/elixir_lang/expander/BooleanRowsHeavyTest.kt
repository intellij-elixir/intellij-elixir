package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.*
import java.io.File

/**
 * Each config file under `testData/org/elixir_lang/expander/boolean_rows/rows`, which reads `k` and `k1` over
 * `System.get_env("X")`. Elixir's answer is `judge.exs`'s over `X` unset, `"y"` and `""`: a key whose reads differ is
 * `Configured`, and where every read raises, `raised`. The plugin's answer is Elixir's, or, where the plugin can't know
 * it, the one it gives, with Elixir's beside it.
 */
class BooleanRowsHeavyTest : ConfigHeavyTestCase() {
    /** A row's plugin [answer], what it was [before] a feature that changed it, and [elixir]'s where it differs. */
    private class Row(val name: String, val answer: String, vararg val before: Before, val elixir: String? = null)

    fun testRows() {
        val base = File("testData/org/elixir_lang/expander/boolean_rows/rows")
        val keys = listOf("k", "k1")
        // Elixir's answer stands beside a row whose answer differs, so a failure shows it.
        fun elixir(row: Row) = onLeg(row.elixir.orEmpty(), row.before, Before::elixir).let { if (it.isEmpty()) "" else "\t(Elixir $it)" }
        val expected = ROWS.joinToString("\n") { "${it.name}\t${onLeg(it.answer, it.before)}${elixir(it)}" }
        val actual = ROWS.joinToString("\n") { "${it.name}\t${keysInOwnRoot(File(base, "${it.name}.exs").readText(), keys)}${elixir(it)}" }

        assertEquals(expected, actual)
    }

    private companion object {
        val ROWS = listOf(
            Row("b1.raise", "k=0 k1=1"),
            Row("b1.value", "k=Configured k1=1"),
            Row("b2.raise", "k=0 k1=1"),
            Row("b2.value", "k=Configured k1=1"),
            Row("b3.raise", "k=0 k1=1"),
            Row("b3.value", "k=Configured k1=1"),
            Row("b4.raise", "k=0 k1=1"),
            Row("b4.value", "k=Configured k1=1"),
            Row("b5.raise", "k=0 k1=1"),
            Row("b5.value", "k=Configured k1=1"),
            Row("b6.raise", "k=0 k1=1"),
            Row("b6.value", "k=Configured k1=1"),
            Row("b7.raise", "k=Configured k1=1"),
            Row("b7.value", "k=:a k1=1"),
            Row("b8.raise", "k=0 k1=1"),
            Row("b8.value", "k=Configured k1=1"),
            // Every read raises, and the raise is marked as one that may not come.
            Row("b9.raise", "k=Configured k1=1", elixir = "k=5 k1=1"),
            // Elixir's read raises; outside every `try` the plugin takes a raise not to come, and `k` is not known.
            Row("b9.value", "k=Configured k1=1", elixir = "raised"),
            // Every read raises, and the raise is marked as one that may not come.
            Row("b10.raise", "k=Configured k1=1", elixir = "k=5 k1=1"),
            // Elixir's read raises; outside every `try` the plugin takes a raise not to come, and `k` is not known.
            Row("b10.value", "k=Configured k1=1", elixir = "raised"),
            Row("c1.raise", "k=0 k1=1"),
            Row("c1.value", "k=Configured k1=1"),
            Row("c2.raise", "k=0 k1=1"),
            Row("c2.value", "k=Configured k1=1"),
            Row("c3.raise", "k=0 k1=1"),
            Row("c3.value", "k=Configured k1=1"),
            Row("c4.raise", "k=Configured k1=1"),
            Row("c4.value", "k=Configured k1=1"),
            Row("cg1.raise", "k=0 k1=1"),
            Row("cg1.value", "k=Configured k1=1"),
            Row("g1.ab", "k=0 k1=1"),
            Row("g2.ab", "k=0 k1=1"),
            Row("g3.ab", "k=0 k1=1"),
            // Every read raises, and the raise is marked as one that may not come.
            Row("g4.raise", "k=Configured k1=1", elixir = "k=5 k1=1"),
            // The `if` joins `[true, "s"]` and `[]` into a value not known that isn't a list, and the generator's
            // `Enumerable` check declines.
            Row("g5.ab", "declined", elixir = "k=Configured k1=1"),
            Row("g5k.ab", "k=1 k1=1"),
            // The list isn't known, so the raise its `""` element always makes in the guard is marked as one that may not
            // come.
            Row("g5u.ab", "k=Configured k1=1", elixir = "k=1 k1=1"),
            // Elixir's guard always fails over `v`, a binary or `nil`, where a call in it raises; the plugin's call may not
            // raise, so the guard may pass.
            Row("gx1.value", "k=Configured k1=1", elixir = "k=:b k1=1"),
            Row("gx2.value", "k=Configured k1=1"),
            Row("gx3.value", "k=Configured k1=1"),
            Row("gx4.value", "k=:a k1=1"),
            // Elixir's guard always fails over `v`, a binary or `nil`, where a call in it raises; the plugin's call may not
            // raise, so the guard may pass.
            Row("gx5.value", "k=Configured k1=1", elixir = "k=:b k1=1"),
            // Elixir's read raises; outside every `try` the plugin takes a raise not to come, and `k` is not known.
            Row("gx6.value", "k=Configured k1=1", elixir = "raised"),
            Row("gx7.value", "k=Configured k1=1"),
            Row("gx8.value", "k=:a k1=1"),
            // Elixir's guard always fails over `v`, a binary or `nil`, where a call in it raises; the plugin's call may not
            // raise, so the guard may pass.
            Row("gx9.value", "k=Configured k1=1", elixir = "k=:b k1=1"),
            // Elixir's guard always fails over `v`, a binary or `nil`, where a call in it raises; the plugin's call may not
            // raise, so the guard may pass.
            Row("gx10.value", "k=Configured k1=1", elixir = "k=:b k1=1"),
            // Elixir's guard always fails over `v`, a binary or `nil`, where a call in it raises; the plugin's call may not
            // raise, so the guard may pass.
            Row("gx11.value", "k=Configured k1=1", elixir = "k=:b k1=1"),
            // Elixir's guard always fails over `v`, a binary or `nil`, where a call in it raises; the plugin's call may not
            // raise, so the guard may pass.
            Row("gx12.value", "k=Configured k1=1", elixir = "k=:b k1=1"),
            // Elixir's guard always fails over `v`, a binary or `nil`, where a call in it raises; the plugin's call may not
            // raise, so the guard may pass.
            Row("gx13.value", "k=Configured k1=1", elixir = "k=:b k1=1"),
            // Elixir's guard always fails over `v`, a binary or `nil`, where a call in it raises; the plugin's call may not
            // raise, so the guard may pass.
            Row("gx14.value", "k=Configured k1=1", elixir = "k=:b k1=1"),
            Row("gx15.value", "k=Configured k1=1"),
            // Elixir's guard always fails over `v`, a binary or `nil`, where a call in it raises; the plugin's call may not
            // raise, so the guard may pass.
            Row("gx16.value", "k=Configured k1=1", elixir = "k=:b k1=1"),
            Row("gx17.value", "k=Configured k1=1"),
            Row("h1.raise", "k=0 k1=1"),
            Row("h1.value", "k=Configured k1=1"),
            // Every read raises, and the raise is marked as one that may not come.
            Row("h2.raise", "k=Configured k1=1", elixir = "k=5 k1=1"),
            // Elixir's read raises; outside every `try` the plugin takes a raise not to come, and `k` is not known.
            Row("h2.value", "k=Configured k1=1", elixir = "raised"),
            Row("j1.raise", "k=0 k1=1"),
            // Every read raises, and the raise is marked as one that may not come.
            Row("j2.raise", "k=Configured k1=1", elixir = "k=5 k1=1"),
            Row("k1.value", "raised"),
            Row("k2.value", "raised"),
            Row("k3.value", "raised"),
            Row("k4.value", "raised"),
            Row("k5.value", "raised"),
            Row("k6.value", "raised"),
            Row("k7.value", "k=[true] k1=1"),
            Row("k8.value", "k=:b k1=1"),
            Row("k9.value", "k=:b k1=1"),
            Row("k10.value", "k=:b k1=1"),
            // `M`'s `def` is in code that may not run, so `M`'s exports aren't known.
            Row("m1.value", "declined", elixir = "k=Configured k1=1"),
            // `M`'s `def` is in code that may not run, so `M`'s exports aren't known.
            Row("m1b.raise", "declined", elixir = "k=0 k1=1"),
            Row("o1.raise", "k=Configured k1=1"),
            Row("o2.value", "k=0 k1=Configured"),
            // A generator's guard over a known list's element whose value isn't known declines.
            Row("o3.raise", "declined", elixir = "k=Configured k1=1"),
            Row("o4.raise", "declined", elixir = "k=Configured k1=1"),
            Row("o5.value", "k=Configured k1=1"),
            Row("o6.value", "k=Configured k1=1"),
            Row("p1.raise", "k=0 k1=1"),
            Row("p2.raise", "k=0 k1=1"),
            Row("p3.raise", "k=0 k1=1"),
            Row("p4.raise", "k=0 k1=1"),
            // The native marks a raise itself on a value not known (`givenUnknown`), so a raise Elixir never makes is kept.
            Row("p5.raise", "k=Configured k1=1", elixir = "k=0 k1=1"),
            // Elixir's `returns_boolean` doesn't name the native, so its value is not known to be a boolean.
            Row("p6.raise", "k=Configured k1=1", elixir = "k=0 k1=1"),
            // The native marks a raise itself on a value not known (`givenUnknown`), so a raise Elixir never makes is kept.
            Row("p7.raise", "k=Configured k1=1", elixir = "k=0 k1=1"),
            // The native marks a raise itself on a value not known (`givenUnknown`), so a raise Elixir never makes is kept.
            Row("p8.raise", "k=Configured k1=1", elixir = "k=0 k1=1"),
            // The native marks a raise itself on a value not known (`givenUnknown`), so a raise Elixir never makes is kept.
            Row("p9.raise", "k=Configured k1=1", elixir = "k=0 k1=1"),
            // The native marks a raise itself on a value not known (`givenUnknown`), so a raise Elixir never makes is kept.
            Row("p10.raise", "k=Configured k1=1", elixir = "k=0 k1=1"),
            Row("p11.raise", "k=0 k1=1"),
            Row("p12.raise", "k=0 k1=1"),
            Row("p13.raise", "k=0 k1=1"),
            Row("p14.raise", "k=0 k1=1"),
            Row("p15.raise", "k=0 k1=1"),
            Row("p16.raise", "k=Configured k1=1"),
            Row("p17.raise", "k=0 k1=1"),
            Row("p18.raise", "k=0 k1=1"),
            // Every read raises, and the raise is marked as one that may not come.
            Row("p19.raise", "k=Configured k1=1", elixir = "k=5 k1=1"),
            Row("q1.value", "k=true k1=1"),
            Row("q2.value", "k=true k1=1"),
            Row("q3.value", "k=false k1=1"),
            Row("q4.value", "k=Configured k1=1"),
            Row("r1.raise", "k=0 k1=1"),
            Row("r2.ab", "k=0 k1=1"),
            Row("r3.value", "k=true k1=1"),
            Row("s3.raise", "k=0 k1=1"),
            // The native marks a raise itself on a value not known (`givenUnknown`), so a raise Elixir never makes is kept.
            Row("s5.raise", "k=Configured k1=1", elixir = "k=0 k1=1"),
            Row("s6.raise", "k=0 k1=1"),
            // The native marks a raise itself on a value not known (`givenUnknown`), so a raise Elixir never makes is kept.
            Row("s7.raise", "k=Configured k1=1", elixir = "k=0 k1=1"),
            // The native marks a raise itself on a value not known (`givenUnknown`), so a raise Elixir never makes is kept.
            Row("s8.raise", "k=Configured k1=1", elixir = "k=0 k1=1"),
            // The native marks a raise itself on a value not known (`givenUnknown`), so a raise Elixir never makes is kept.
            Row("s9.raise", "k=Configured k1=1", elixir = "k=0 k1=1"),
            // The native marks a raise itself on a value not known (`givenUnknown`), so a raise Elixir never makes is kept.
            Row("s10.raise", "k=Configured k1=1", elixir = "k=0 k1=1"),
            Row("s14.raise", "k=0 k1=1"),
            Row("t1.value", "k=Configured k1=1"),
            Row("t2.value", "k=1 k1=1"),
            Row("t3.value", "k=1 k1=1"),
            // Before `Config.read_config/1`, its call raises.
            Row("t4.value", "k=1 k1=1", Before(CONFIG_READ_CONFIG, "k=2 k1=1")),
            Row("t5.value", "k=1 k1=1"),
            // Before 1.13 the plugin gives `__STACKTRACE__` as `[]` where Elixir gives its frames: #4509's "`__STACKTRACE__`
            // as `[]`" entry. Before 1.14 on OTP 25, Elixir's frames name no line of the file, so every read gives the same.
            Row(
                "t6.value",
                "k=Configured k1=1",
                Before(RAISE_ERROR_INFO, "k=[] k1=1"),
                Before(EVAL_STACKTRACE_NAMES_THE_FILE, elixir = "k=erl_eval's frames k1=1"),
            ),
            // Every way raises, with reasons that differ, so which clause takes the raise isn't known.
            Row("t7.value", "k=Configured k1=1", elixir = "k=1 k1=1"),
            Row("t8.value", "k=Configured k1=1", elixir = "k=1 k1=1"),
            Row("t9.value", "k=1 k1=1"),
            // Every way raises, with reasons that differ, so which clause takes the raise isn't known.
            Row("t11.value", "k=Configured k1=1", elixir = "k=1 k1=1"),
            Row("t12.value", "k=1 k1=1"),
            // A list not known may be any value to the plugin, a binary included.
            Row("t13.value", "k=Configured k1=1", elixir = "k=2 k1=1"),
            Row("v3.value", "k=true k1=1"),
            Row("v5.value", "k=true k1=1"),
            // Elixir's `returns_boolean` doesn't name the native, so its value is not known to be a boolean.
            Row("v6.value", "k=Configured k1=1", elixir = "k=true k1=1"),
            // Before 1.20.1, `returns_boolean` doesn't name `:lists.member/2`.
            Row("v7.value", "k=true k1=1", Before(RETURNS_BOOLEAN_LISTS_MEMBER, "k=Configured k1=1")),
            // Elixir's `returns_boolean` doesn't name the native, so its value is not known to be a boolean.
            Row("v8.value", "k=Configured k1=1", elixir = "k=true k1=1"),
            // Elixir's `returns_boolean` doesn't name the native, so its value is not known to be a boolean.
            Row("v9.value", "k=Configured k1=1", elixir = "k=true k1=1"),
            Row("v10.value", "k=true k1=1"),
            Row("v14.value", "k=true k1=1"),
        )
    }
}
