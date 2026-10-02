package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import org.elixir_lang.lowering.inspect
import org.elixir_lang.psi.ElixirFile

/**
 * Each call the expander dispatches, in its order, against the dispatch events the leg's compiler traces for the same
 * case module body, as [DispatchEvents] normalises them.
 */
class DispatchProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness) { createPsiFile(getTestName(false), it) as ElixirFile }

    fun testDispatchesMatchTheCompilersEvents() {
        val expansions = CASES.map { probes.expand(it, PLACEHOLDER) }

        expansions.forEach { assertTrue("${it.case.body}: ${it.outcome}", it.outcome is Expansion.Expanded) }

        val attempt = harness.attempt(expansions.map { it.case })

        assertEquals(
            "compile status ${attempt.compiled.diagnostics.map(::inspect)}",
            OtpErlangAtom("ok"),
            attempt.compiled.status,
        )
        assertEquals(
            CASES.indices.joinToString("\n") { render(it, expansions[it].dispatches) },
            CASES.indices.joinToString("\n") { case ->
                val caseModule = attempt.batch.caseModule(case)
                val events = DispatchEvents.of(
                    attempt.compiled.events,
                    caseModule,
                    attempt.batch.probeModule,
                    attempt.bodyLines[case],
                )

                render(case, events.map { it.replace(caseModule, PLACEHOLDER) })
            },
        )
    }

    private fun render(case: Int, dispatches: List<String>) =
        "== ${CASES[case].replace("\n", "; ")}\n" + dispatches.joinToString("") { "  $it\n" }

    private companion object {
        const val PLACEHOLDER = "Elixir.DispatchCase"

        /** Each must expand, and compile in a module body; a call that would raise when the body runs is in an `fn`. */
        val CASES = listOf(
            "x = 1\n_ = x + 1",
            "x = 1\n_ = Integer.to_string(x)",
            "t = {1}\n_ = elem(t, 0)",
            "t = {1}\n_ = abs(elem(t, 0) + 1)",
            "x = -1\n_ = abs(:erlang.abs(x))",
            "m = %{}\n_ = Map.get(m, String.length(\"a\"))",
            "m = %{a: 1}\n_ = m.a",
            "f = fn y -> y end\n_ = f.(1)",
            "l = [1]\n_ = :lists.reverse(l)",
            "_ = fn -> __MODULE__.foo() end",
            "_ = fn -> __ENV__.module.foo() end",
            "x = 1\n_ = -x",
            "_ = String.Chars.to_string(\"a\")",
            "_ = fn -> Kernel.alias(Foo) end",
            "_ = fn -> Kernel.require(Foo) end",
            "_ = fn -> Kernel.import(Foo) end",
            "_ = fn -> Kernel.quote(do: 1) end",
            "_ = fn -> (alias Foo.Bar).baz() end",
            "_ = fn -> (alias Foo).baz() end",
            "_ = fn -> (alias Foo.Bar, warn: false).baz() end",
            "_ = fn -> (import Integer, only: [parse: 1]).baz() end",
            "_ = fn -> (require Integer).baz() end",
            "case 1 do\n  y when y > 0 -> y\nend",
            "case {1} do\n  t when elem(t, 0) == 1 -> t\nend",
            "[1] ++ x = [1, 2]",
            "<<x::+8>> = <<1>>",
            "<<x::+(+8)>> = <<1>>",
            "try do\n  :ok\nrescue\n  _ -> System.stacktrace()\nend",
            "_ = System.stacktrace()",
            "import System, only: [stacktrace: 0]\ntry do\n  :ok\nrescue\n  _ -> stacktrace()\nend",
            "x = 1\n_ = fn -> Integer.parse(\"1\") end\n_ = x",
        )
    }
}
