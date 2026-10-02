package org.elixir_lang.expander

import org.elixir_lang.psi.ElixirFile

/**
 * `var!`, `alias!` and captures compiled as module bodies on the leg's Elixir: the variables in counter contexts fall
 * into the same classes, and at each probe the hygiene counters taken are the expander's count.
 */
class HygieneProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness, accounting = true) {
        createPsiFile(getTestName(false), it) as ElixirFile
    }

    fun testHygiene() {
        val expansions = CASES.associateWith { probes.expand(it, PLACEHOLDER) }

        assertEquals(
            "",
            expansions.filterValues { it.outcome !is Expansion.Expanded && it.outcome !is Expansion.Error }
                .map { (case, expansion) -> "$case: ${expansion.outcome}" }
                .joinToString("\n"),
        )
        probes.assertMatchesElixir(expansions)
    }

    private companion object {
        const val PLACEHOLDER = "Elixir.HygieneCase"

        val CASES = listOf(
            "a = 1\nvar!(x) = 1\n_ = alias!(Foo)\ny = 2",
            "var!(x, Kernel) = 1\nvar!(x, Kernel) = 2",
            "var!(x, Kernel) = 1\ny = x",
            "alias Bar.Foo\nvar!(v, Foo) = 1\nvar!(v, :ctx) = 1\nKernel.var!(z) = 3",
            "y = 1\n_ = case 1 do\n  w when var!(w) == y -> w\nend",
            "y = 1\n_ = case 1 do\n  w when Kernel.var!(w) == y -> w\nend",
            "_ = case 1 do\n  1 -> var!(p) = 1\n  _ -> var!(q) = 2\nend\nz = 3",
            "a = 1\nh = &(&1 + &2 + &1)\ny = 2",
            "h = &{&1, &2}",
            "h = &(&1 + 1)\ng = &(&1 * &1)",
            "h = &Integer.to_string(&1, 2)",
            "h = &alias!(Integer).to_string(&1, 2)",
            "f = fn x -> x end\nh = &f.(&1)",
            "h = &Integer.to_string(&1)",
            "h = &abs/1",
            "m = Integer\nh = &m.to_string(&1)",
            "_ = case 1 do\n  1 -> &(&1 + &2)\n  _ -> & &1\nend\nz = 3",
        )
    }
}
