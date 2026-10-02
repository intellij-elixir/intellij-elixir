package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.ElixirFile

/**
 * `quote` and `unquote` compiled as module bodies on the leg's Elixir: the variables a quote binds where it is
 * written, its errors, and the value it builds, sent back at run time.
 */
class QuoteProbeTest : ProbeTestCase() {
    private val probes = ExpansionProbes(harness) { createPsiFile(getTestName(false), it) as ElixirFile }

    fun testQuoteSiteVariables() {
        val expansions = SITE_FORMS.associateWith { probes.expand("l = 3\nf = \"x.ex\"\n$it", PLACEHOLDER) }

        assertUnported(expansions, emptyList())
        probes.assertMatchesElixir(expansions)
    }

    fun testErrors() {
        val expansions = ERRORS.associateWith { probes.expand(it) }

        assertUnported(expansions, emptyList())
        probes.assertMatchesElixir(expansions)
    }

    /**
     * Each form's value, as Elixir sends it back, equals [quotedValue] of the escaped expression the expander builds
     * from the state and env before it, with the case module read as one placeholder module. The expander reads the
     * form from the line Elixir compiles it at, since `__ENV__.line` is a value the quote can hold.
     */
    fun testQuotedValues() {
        val level = legLevel()
        val expansions = VALUE_FORMS.map { probes.expand(it, PLACEHOLDER) }

        expansions.forEach { assertTrue("${it.case.body}: ${it.outcome}", it.outcome is Expansion.Expanded) }

        val attempt = harness.attempt(
            expansions.map { ProbeHarness.Case(it.case.body, it.case.identities, it.case.bodies, it.statements.size) }
        )

        assertEquals("compile status", OtpErlangAtom("ok"), attempt.compiled.status)

        val run = Run(level, ExpansionObserver.NONE, legExports)
        val expected = VALUE_FORMS.mapIndexed { index, form ->
            val expansion = probes.expand("\n".repeat(attempt.bodyLines[index] - 1) + form, PLACEHOLDER)
            val (state, env) = expansion.starts.last()
            val quote = (expansion.statements.last() as ElixirAst.Call).arguments!![1] as ElixirAst.Call
            val escaped = Quote.escaped(quote, state, env, run)
            val value = escaped?.let(::quotedValue)?.let(QuotedTerms::inspect) ?: "no value from the expander"

            "${VALUE_FORMS[index]}\n  $value"
        }
        val actual = VALUE_FORMS.indices.map { index ->
            val caseModule = attempt.batch.caseModule(index)
            val value = attempt.batch.values[index]?.let { term ->
                QuotedTerms.of(
                    term,
                    atoms = { if (it == caseModule) PLACEHOLDER else it },
                    binaries = { it.replace(COMPILE_FILE, "quoter-compile.ex") },
                )
            }

            "${VALUE_FORMS[index]}\n  ${value?.let(QuotedTerms::inspect) ?: "no value from Elixir"}"
        }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    private fun assertUnported(expansions: Map<String, ExpansionProbes.CaseExpansion>, expected: List<String>) =
        assertEquals(
            expected.joinToString("\n"),
            expansions.filterValues { it.outcome is Expansion.Unported }.keys.joinToString("\n"),
        )

    private companion object {
        const val PLACEHOLDER = "Elixir.QuoteCase"

        val COMPILE_FILE = Regex("""quoter-compile-\d+\.ex""")

        /** After `l = 3` and `f = "x.ex"`. */
        val SITE_FORMS = listOf(
            ":ok",
            "quote(do: x)",
            "quote(line: l, do: x)",
            "quote(context: c = Foo, do: x)",
            "quote(file: f, do: x)",
            "quote(line: __ENV__.line, do: x)",
            "quote(file: __DIR__, do: x)",
            "quote(context: __MODULE__, do: x)",
            "quote(generated: __ENV__.line > 0, do: x)",
            "quote(do: unquote(y = 1))",
            "quote(bind_quoted: [b: z = 2], do: b)",
            "quote(do: foo(unquote_splicing([l])))",
        )

        const val AMBIGUOUS = "import Map, only: [get: 2]\nimport Keyword, only: [get: 2]"

        val ERRORS = listOf(
            "unquote(1)",
            "unquote_splicing([1])",
            "quote(1)",
            "quote(1, 2)",
            "quote([foo: 1])",
            "quote(foo: 1, do: 1)",
            "quote(:foo, do: 1)",
            "quote(bind_quoted: 1, do: 1)",
            "x = 1\nquote(do: unquote(x)) = 1",
            "case 1 do\ny when quote(do: unquote(y)) -> y\nend",
            "quote(do: unquote_splicing([1]))",
            "g = true\nquote(generated: g, do: 1)",
            "u = true\nquote(unquote: u, do: 1)",
            "$AMBIGUOUS\nquote(do: get(1, 2))",
            "$AMBIGUOUS\nquote(do: &get/2)",
            "$AMBIGUOUS\nquote(do: get(1))",
            "$AMBIGUOUS\nquote(do: get)",
        )

        /** Each a case of its own, whose last statement binds `q`. */
        val VALUE_FORMS = listOf(
            "alias String.Chars\nq = quote(do: Chars.to_string(1))",
            "q = quote(do: Foo.Bar)",
            "q = quote(do: is_atom(1))",
            "q = quote(do: &inspect/1)",
            "q = quote(do: v)",
            "q = quote(context: Foo, do: v)",
            "q = quote(line: 42, do: foo(v))",
            "q = quote(line: __ENV__.line, do: foo(v))",
            "q = quote(context: __MODULE__, do: v)",
            "q = quote(generated: true, do: foo(v))",
            "q = quote(file: \"x.ex\", line: 7, do: foo(v))",
            "q = quote(file: \"x.ex\", do: foo(v))",
            "q = quote(do: quote(do: v))",
            "alias String.Chars\nq = quote(do: Chars.to_string(Foo.Bar))",
            "q = quote do\n  def f(x) when x, do: 1\nend",
            "q = quote(do: quote(do: unquote(v)))",
            "q = quote(unquote: false, do: unquote(x))",
            "q = quote(do: import Foo)",
            "q = quote(do: foo -x)",
            "q = quote(do: x.y)",
            "q = quote(do: {[1, 2], %{a: 3}, {4, 5, 6}})",
            "q = quote do\n  a\n  b\nend",
            "q = quote(do: Elixir.Foo)",
        )
    }
}
