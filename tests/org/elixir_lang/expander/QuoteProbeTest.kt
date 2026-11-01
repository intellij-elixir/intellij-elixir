package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTE_OPTIONS_EXPANDED_FIRST
import org.elixir_lang.lowering.ElixirAst

/**
 * `quote` and `unquote` compiled as module bodies on the leg's Elixir: the variables a quote binds where it is
 * written, its errors, and the value it builds, sent back at run time.
 */
class QuoteProbeTest : SdkProbeTestCase() {
    fun testQuoteSiteVariables() {
        val expansions = SITE_FORMS.associateWith { probes.expand("l = 3\nf = \"x.ex\"\n$it") }

        assertUnported(expansions, emptyList())
        probes.assertMatchesElixir(expansions)
    }

    /** Each probe is delivered, and then the module body raises when it runs. */
    fun testAnInvalidRunTimeOptionRaisesWhenTheBodyRuns() {
        val expansions = listOf("l = :bad\n_ = quote(line: l, do: x)").associateWith { probes.expand(it) }

        assertUnported(expansions, emptyList())
        probes.assertMatchesElixir(expansions)

        val status = harness.attempt(listOf(expansions.values.single().case)).compiled.status as OtpErlangTuple

        assertEquals(
            "raise Elixir.ArgumentError invalid runtime value for option :line in quote, got: :bad",
            listOf(status.elementAt(0), status.elementAt(1)).joinToString(" ") { (it as OtpErlangAtom).atomValue() } +
                " " + utf8(status.elementAt(2)),
        )
    }

    /** The prelude expands an option's value a second time, which dispatches the first expansion's result. */
    fun testADynamicOptionHoldingACallIsUnportedAtItsValue() {
        val body = "quote(file: f = String.trim(\"x.ex\"), do: x)"
        val expansion = probes.expand(body)
        val outcome = expansion.outcome

        assertEquals(
            "f = String.trim(\"x.ex\")",
            (outcome as? Expansion.Unported)?.at?.let(expansion::source) ?: outcome.toString(),
        )
    }

    /** An expanded list has no position of its own: the stop is at what is written. */
    fun testADynamicOptionHoldingAListIsUnportedAtItsValue() {
        val body = "quote(file: [String.trim(\"x.ex\")], do: x)"
        val expansion = probes.expand(body)
        val outcome = expansion.outcome

        assertEquals(
            "[String.trim(\"x.ex\")]",
            (outcome as? Expansion.Unported)?.at?.let(expansion::source) ?: outcome.toString(),
        )
    }

    /** With the leg's modules' clauses, from which `Exception.normalize/3` names an error raised when the body runs. */
    fun testErrors() {
        val expansions = ERRORS.associateWith { expand(it, "") }

        assertUnported(expansions, emptyList())
        probes.assertMatchesElixir(expansions)
    }

    /**
     * Each form's value, as Elixir sends it back, equals [quotedValue] of the escaped expression the expander builds
     * from the state and env before it, the two compiled from one file.
     */
    fun testQuotedValues() {
        val level = legLevel()
        val expansions = probes.expandAll(VALUE_FORMS, values = true)

        expansions.cases.forEach { assertTrue("${it.case.body}: ${it.outcome}", it.outcome is Expansion.Expanded) }

        val attempt = harness.attempt(expansions.layout)

        assertEquals("compile status", OtpErlangAtom("ok"), attempt.compiled.status)

        val run = Run(level, ExpansionObserver.NONE, legExports, legStructs, NO_APP_ENV)
        val expected = expansions.cases.mapIndexed { index, expansion ->
            val (state, env) = expansion.starts.last()
            val quote = (expansion.statements.last() as ElixirAst.Call).arguments!![1] as ElixirAst.Call
            val escaped = Quote.escaped(quote, state, env, run)
            val value = escaped?.let(::quotedValue)?.let(QuotedTerms::inspect) ?: "no value from the expander"

            "${VALUE_FORMS[index]}\n  $value"
        }
        val actual = VALUE_FORMS.indices.map { index ->
            val value = attempt.batch.values[index]?.let { term ->
                QuotedTerms.of(term, binaries = { it.replace(COMPILE_FILE, "quoter-compile.ex") })
            }

            "${VALUE_FORMS[index]}\n  ${value?.let(QuotedTerms::inspect) ?: "no value from Elixir"}"
        }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    fun testBindQuotedIsValidatedExpanded() {
        val expansions = EXPANDED_OPTIONS.associateWith { expand(it, OPTION_MACROS) }

        assertUnported(expansions, emptyList())
        printed { probes.assertMatchesElixir(expansions) }
    }

    /** Elixir's answer depends on a read the expander has no value for: a stop, never its `invalid_bind_quoted`. */
    fun testAnUnknownBindQuotedStops() {
        val unknown = "System.get_env(\"HOME\")"
        val bodies = listOf(
            "@b $unknown\ndef f, do: quote(bind_quoted: @b, do: 1)",
            "@p $unknown\ndef f, do: quote(bind_quoted: [@p], do: 1)",
            "@k $unknown\ndef f, do: quote(bind_quoted: [{@k, 1}], do: 1)",
            "@o $unknown\ndef f, do: quote(@o, do: 1)",
            "@p $unknown\ndef f, do: quote([@p], do: 1)",
            "@k $unknown\ndef f, do: quote([{@k, 1}], do: 1)",
        )

        assertEquals(
            bodies.joinToString("\n") { "$it: Unported" },
            bodies.joinToString("\n") { "$it: ${expand(it, "").outcome::class.simpleName}" },
        )
    }

    fun testBindQuotedStillRaises() {
        val expansions = listOf(
            "x = 1\nquote(bind_quoted: x, do: 1)",
            "quote(bind_quoted: 1, do: 1)",
            "quote(bind_quoted: [{\"a\", 1}], do: 1)",
            "quote(bind_quoted: @nope, do: 1)",
            "@b 1\ndef f, do: quote(bind_quoted: @b, do: 1)",
            "@p 1\ndef f, do: quote(bind_quoted: [@p], do: 1)",
            "@k 1\ndef f, do: quote(bind_quoted: [{@k, 1}], do: 1)",
            "quote(bind_quoted: Keyword.merge([], []), do: 1)",
            "require $OPTION_MODULE\nquote(bind_quoted: $OPTION_MODULE.not_a_list(), do: 1)",
        ).associateWith { expand(it, OPTION_MACROS) }

        assertUnported(expansions, emptyList())
        printed { probes.assertMatchesElixir(expansions) }
    }

    fun testOptionValuesExpandUnderAsk() {
        val values = listOf(
            "fn -> 1 end",
            "case 1 do\nx -> x\nend",
            "try do\n1\nrescue\ne in ArgumentError -> e\nend",
            "for(x <- [1], do: x)",
            "with(x <- 1, do: x)",
            "%{a: 1}",
            "%URI{path: \"a\"}",
            "<<1, 2>>",
            "&String.to_atom/1",
        )
        val expansions = values.associateWith { expand("_ = quote(bind_quoted: [a: $it], do: a)", "") }

        // Before 1.17 Elixir expands a value a second time, as what the first gave, which isn't ported for a call.
        assertUnported(expansions, if (QUOTE_OPTIONS_EXPANDED_FIRST.isSufficient(legLevel())) emptyList() else values)
        printed { probes.assertMatchesElixir(expansions) }
    }

    /** A macro of the preamble's runs from its source, where a binding's value must be a node the expansion recorded. */
    fun testASourceMacroWithBindQuotedEvaluates() {
        val called = "require $CALLED_MODULE\n_ = $CALLED_MODULE.called(1)"
        val written = mapOf("require $WRITTEN_MODULE\n_ = $WRITTEN_MODULE.written(1)".let { it to expand(it, WRITTEN_MACROS) })
        val calledExpansion = expand(called, CALLED_MACROS)
        val bound = (listOf("bound", "bound_twice").map { "require $BOUND_MODULE\n_ = $BOUND_MODULE.$it(1)" } + "use $BOUND_MODULE, a: 1")
            .associateWith { expand(it, BOUND_MACROS) }
        // Before 1.17 Elixir expands a value a second time, as what the first gave, which isn't ported for a call.
        val expandedFirst = QUOTE_OPTIONS_EXPANDED_FIRST.isSufficient(legLevel())
        val expansions = written + bound + if (expandedFirst) mapOf(called to calledExpansion) else emptyMap()

        if (!expandedFirst) assertTrue("${calledExpansion.outcome}", calledExpansion.outcome is Expansion.Unported)
        assertEquals(
            "",
            expansions.filterValues { it.outcome is Expansion.Unported || it.outcome is Expansion.Opaque }
                .map { (body, expansion) -> "${body.replace("\n", "; ")}: ${expansion.outcome}" }
                .joinToString("\n"),
        )
        printed { probes.assertMatchesElixir(expansions) }
    }

    /** The expansion may stop at this macro, and never gives another answer than Elixir's. */
    fun testABindingWithNoWrittenValueNeverAnswersWrongly() {
        val attribute = { value: String -> "@b [a: $value]\n  defmacro copied(_), do: quote(bind_quoted: @b, do: a)" }
        val macros = listOf("{1, 2, 3}", "quote(do: x)", "{:__MODULE__, [], nil}", "<<1, 2>>").map(attribute) +
            "defmacro copied(y), do: quote(bind_quoted: quote(do: [a: unquote(y)]), do: a)"

        for (macro in macros) {
            val preamble = "defmodule $COPIED_MODULE do\n  $macro\nend"
            val body = "require $COPIED_MODULE\n_ = $COPIED_MODULE.copied(1)"

            printed { probes.assertMatchesElixir(mapOf(body to expand(body, preamble))) }
        }
    }

    private fun assertUnported(expansions: Map<String, ExpansionProbes.CaseExpansion>, expected: List<String>) =
        assertEquals(
            expected.joinToString("\n"),
            expansions.filterValues { it.outcome is Expansion.Unported }.keys.joinToString("\n"),
        )

    private companion object {
        val COMPILE_FILE = Regex("""quoter-compile-\d+\.ex""")

        const val OPTION_MODULE = "QuoteOptions{token}"

        val OPTION_MACROS =
            """
            defmodule $OPTION_MODULE do
              defmacro keywords, do: [a: 1]
              defmacro pair, do: {:a, 1}
              defmacro opts, do: [bind_quoted: [a: 1]]
              defmacro no, do: false
              defmacro not_a_list, do: 1
            end
            """.trimIndent()

        const val WRITTEN_MODULE = "QuoteWritten{token}"

        val WRITTEN_MACROS =
            """
            defmodule $WRITTEN_MODULE do
              defmacro written(x), do: quote(bind_quoted: [x: x], do: x)
            end
            """.trimIndent()

        const val CALLED_MODULE = "QuoteCalled{token}"

        val CALLED_MACROS =
            """
            defmodule $CALLED_MODULE do
              defmacro called(y), do: quote(bind_quoted: [x: id(y)], do: x)

              defp id(y), do: y
            end
            """.trimIndent()

        const val COPIED_MODULE = "QuoteCopied{token}"

        const val BOUND_MODULE = "QuoteBound{token}"

        val BOUND_MACROS =
            """
            defmodule $BOUND_MODULE do
              defmacro bound(x), do: quote(bind_quoted: binding(), do: x)
              defmacro bound_twice(x) do
                y = x
                quote bind_quoted: binding() do
                  {x, y}
                end
              end
              defmacro __using__(opts) do
                quote bind_quoted: binding() do
                  @used opts
                end
              end
            end
            """.trimIndent()

        val EXPANDED_OPTIONS = listOf(
            "def f(opts) do\nquote bind_quoted: binding() do\n_ = opts\nend\nend",
            "defmacro m(opts) do\nquote bind_quoted: binding() do\n_ = opts\nend\nend",
            "@b [a: 1]\n_ = quote(bind_quoted: @b, do: a)",
            "@b [a: 1]\ndef f, do: quote(bind_quoted: @b, do: a)",
            "require $OPTION_MODULE\n_ = quote(bind_quoted: $OPTION_MODULE.keywords(), do: a)",
            "require $OPTION_MODULE\n_ = quote(bind_quoted: [$OPTION_MODULE.pair()], do: a)",
            "_ = quote(bind_quoted: [{__MODULE__, 1}], do: 1)",
            "_ = quote(bind_quoted: [{Foo, 1}], do: 1)",
            "require $OPTION_MODULE\n_ = quote($OPTION_MODULE.opts(), do: a)",
            "require $OPTION_MODULE\n_ = quote(unquote: $OPTION_MODULE.no(), do: a)",
            "@p {:a, 1}\n_ = quote(bind_quoted: [@p], do: a)",
            "@p {:a, 1}\ndef f, do: quote(bind_quoted: [@p], do: a)",
            "@k :a\n_ = quote(bind_quoted: [{@k, 1}], do: a)",
            "@k :a\ndef f, do: quote(bind_quoted: [{@k, 1}], do: a)",
            "@o [bind_quoted: [a: 1]]\ndef f, do: quote(@o, do: a)",
        )

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
            "case 1 do\n  _ -> quote(do: foo(unquote(y = 1)))\nend",
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
            "l = 3\nquote(line: l, do: x) = 1",
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
