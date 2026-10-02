package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst

/**
 * `unquote` and `quote` over lowered snippets at every supported minor, and the value each `quote` builds, read with
 * [quotedValue] in a module `T`. Each expected value is what Elixir's quote gives on that leg, as `QuoteProbeTest`
 * checks through the quoter for the forms the value route reaches.
 */
class QuoteExpanderTest : ExpanderTestCase() {
    override val exports: Exports = Exports { module ->
        when (module) {
            "Elixir.Map", "Elixir.Keyword" ->
                ModuleExports.Present(listOf(NameArity("get", 2), NameArity("get", 3)), emptyList(), hasInfo = true)
            else -> ModuleExports.Absent
        }
    }

    override val kernel = KernelImports(
        functions = listOf(NameArity("/", 2), NameArity("inspect", 1), NameArity("inspect", 2), NameArity("is_atom", 1)),
        macros = listOf(NameArity("def", 1), NameArity("def", 2)),
    )

    /**
     * A remote call that isn't ported, or that raises, shows as its receiver, name and arity, and its first argument
     * when that is an atom: the quote builds it, so it has no source of its own.
     */
    override fun render(code: String, expansion: Expansion): String {
        val outcome = when (expansion) {
            is Expansion.Unported -> "unported"
            is Expansion.Error -> "error ${expansion.kind}"
            else -> null
        }
        val at = when (expansion) {
            is Expansion.Unported -> expansion.at
            is Expansion.Error -> expansion.at
            else -> null
        } as? ElixirAst.Call
        val dot = at?.callee as? ElixirAst.Call
        val dotArguments = dot?.takeIf { isCall(it, ".", 2) }?.arguments
        val receiver = dotArguments?.get(0)
        val name = dotArguments?.get(1)

        return if (receiver is ElixirAst.Literal.Atom && name is ElixirAst.Literal.Atom) {
            val key = (at!!.arguments!!.firstOrNull() as? ElixirAst.Literal.Atom)?.let { " :${it.name}" }.orEmpty()

            "$outcome :${receiver.name}.${name.name}/${at.arguments!!.size}$key"
        } else {
            super.render(code, expansion)
        }
    }

    // unquote outside a quote

    fun testUnquoteOutsideAQuoteIsAnError() =
        assertEvery("unquote(1)", "error unquote_outside_quote `unquote(1)`")

    fun testUnquoteSplicingOutsideAQuoteIsAnError() =
        assertEvery("unquote_splicing([1])", "error unquote_outside_quote `unquote_splicing([1])`")

    // The quote clauses' errors

    fun testQuoteOfOneArgumentThatIsNotAListIsAnError() = assertEvery("quote(1)", "error invalid_args `quote(1)`")

    fun testQuoteOfTwoArgumentsWhoseLastIsNotAListIsAnError() =
        assertEvery("quote(1, 2)", "error invalid_args `quote(1, 2)`")

    fun testQuoteWithoutDoIsAnError() = assertEvery("quote([foo: 1])", "error missing_option `quote([foo: 1])`")

    fun testQuoteOfTwoArgumentsWithoutDoIsAnError() =
        assertEvery("quote([], [foo: 1])", "error missing_option `quote([], [foo: 1])`")

    fun testQuoteWithAnUnsupportedOptionIsAnError() =
        assertEvery("quote(foo: 1, do: 1)", "error unsupported_option `quote(foo: 1, do: 1)`")

    fun testQuoteOptionsThatAreNotAListIsAnError() =
        assertEvery("quote(:foo, do: 1)", "error options_are_not_keyword `quote(:foo, do: 1)`")

    fun testBindQuotedThatIsNotAListIsAnError() =
        assertEvery("quote(bind_quoted: 1, do: 1)", "error invalid_bind_quoted_for_quote `quote(bind_quoted: 1, do: 1)`")

    fun testBindQuotedWithAKeyThatIsNotAnAtomIsAnError() =
        assertEvery(
            "quote(bind_quoted: [{1, 2}], do: 1)",
            "error invalid_bind_quoted_for_quote `quote(bind_quoted: [{1, 2}], do: 1)`",
        )

    fun testSourceTheLoweringLeftAsAPlaceholderIsUnported() {
        val code = "quote(do: foo(y))"

        assertEquals(
            LEVELS.joinToString("\n") { "$it: unported `y`" },
            LEVELS.joinToString("\n") { version ->
                val level = ElixirLanguageLevel.of(version)
                val ast = placeholding(lower(code, level), "y")

                "$version: " + render(code, Expander.expand(ast, ExState.empty(level), Env.empty(level, kernel), level, exports, structs))
            }
        )
    }

    fun testBindQuotedWithATailIsAnError() =
        assertEvery(
            "b = []\nquote(bind_quoted: [{:a, 1} | b], do: 1)",
            "error invalid_bind_quoted_for_quote `quote(bind_quoted: [{:a, 1} | b], do: 1)`",
        )

    fun testAnUnsupportedOptionIsReportedBeforeBindQuoted() =
        assertEvery("quote(foo: 1, bind_quoted: 1, do: 1)", "error unsupported_option `quote(foo: 1, bind_quoted: 1, do: 1)`")

    fun testALoneUnquoteSplicingIsAnError() =
        assertEvery("quote(do: unquote_splicing([1]))", "error quote_unquote_splicing `quote(do: unquote_splicing([1]))`")

    fun testAnUnquoteOptionThatIsNotABooleanIsAnError() =
        assertEvery("u = true\nquote(unquote: u, do: 1)", "error quote_invalid_runtime_option `quote(unquote: u, do: 1)`")

    fun testAGeneratedOptionThatIsNotABooleanIsAnError() =
        assertEvery(
            "g = true\nquote(generated: g, do: 1)",
            "error quote_invalid_runtime_option `quote(generated: g, do: 1)`",
        )

    // What a quote binds where it is written

    fun testAStaticQuoteBindsNothing() = assertEvery("quote(do: x)", "expanded {} next 0")

    /** From 1.18 the unquoted expression is an argument of a remote call that validates it at run time. */
    fun testUnquoteBindsInTheCaller() = assertEvery("quote(do: unquote(y = 1))", "expanded {y:0} next 1")

    /** Before 1.17 the values are expanded a second time. */
    fun testBindQuotedBindsWhatItsValuesBindAndNotItsKeys() =
        assertSplit("quote(bind_quoted: [b: z = 2], do: b)", "1.17.0-rc.0", "expanded {z:1} next 2", "expanded {z:0} next 1")

    fun testAnOptionIsReadFromItsExpandedValue() {
        assertEvery("quote(line: __ENV__.line, do: x)", "expanded {} next 0")
        assertValue("quote(context: __MODULE__, do: v)", "{:v, [], T}")
    }

    /** `__DIR__` is a binary whose content `Env` doesn't hold: the quote expands, but its escaped expression is unknown. */
    fun testAFileWhoseContentIsNotKnownHasNoEscapedExpression() {
        assertEvery("quote(file: __DIR__, do: x)", "expanded {} next 0")
        assertValue("quote(file: __DIR__, do: foo(v))", "no escaped expression")
    }

    fun testUnquoteSplicingInArgumentsIsARemoteCall() =
        assertEvery("l = [1]\nquote(do: foo(unquote_splicing(l)))", "expanded {l:0} next 1")

    fun testUnquoteSplicingAmongElementsIsAConcatenation() =
        assertEvery("l = [1]\nquote(do: [1, unquote_splicing(l), 2])", "expanded {l:0} next 1")

    fun testUnquoteSplicingBeforeATailIsARemoteCall() =
        assertEvery("l = [1]\nquote(do: [unquote_splicing(l) | 2])", "expanded {l:0} next 1")

    fun testAnUnquotedCallNameIsARemoteCall() =
        assertEvery("f = :foo\nquote(do: Kernel.unquote(f)(1))", "expanded {f:0} next 1")

    fun testAnUnquotedNameIsARemoteCall() =
        assertEvery("f = :foo\nquote(do: Kernel.unquote(f))", "expanded {f:0} next 1")

    fun testLocationKeepReadsTheFile() =
        assertEvery("quote(location: :keep, do: 1)", "unported `quote(location: :keep, do: 1)`")

    fun testAnotherLocationCrashes() =
        assertEvery("quote(location: :other, do: 1)", "unported `quote(location: :other, do: 1)`")

    // A quote in a pattern

    fun testAQuoteWithUnquoteInAPattern() =
        assertLevels("x = 1\nquote(do: unquote(x)) = 1", LEVELS + listOf("1.20.1", "1.20.2")) { version ->
            when {
                isBefore(version, "1.18.0-rc.0") -> "expanded {x:1} next 2"
                isBefore(version, "1.20.0") -> "error invalid_match :elixir_quote.shallow_validate_ast/1"
                isBefore(version, "1.20.2") -> "error invalid_match :elixir_quote.unquote/1"
                else -> "error quote_in_pattern_with_unquote `quote(do: unquote(x))`"
            }
        }

    fun testAQuoteWithUnquoteDisabledInAPattern() =
        assertEvery("x = 1\nquote(unquote: false, do: unquote(x)) = 1", "expanded {x:0} next 1")

    fun testAnUnquoteInANestedQuoteIsQuotedInAPattern() =
        assertEvery("quote(do: quote(do: unquote(x))) = 1", "expanded {} next 0")

    // Imports two modules bring in under one name

    fun testAQuotedCallOfAnAmbiguousImportIsAnError() =
        assertEvery("$AMBIGUOUS\nquote(do: get(1, 2))", "error ambiguous_call `get(1, 2)`")

    fun testAQuotedCaptureOfAnAmbiguousImportIsAnError() =
        assertEvery("$AMBIGUOUS\nquote(do: &get/2)", "error ambiguous_call `&get/2`")

    fun testAQuotedCallOfAnAmbiguousNameAtAnotherArity() =
        assertSplit(
            "$AMBIGUOUS\nquote(do: get(1))",
            "1.14.0-rc.0",
            "expanded {} next 0",
            "error ambiguous_call `get(1)`",
        )

    fun testAQuotedVariableNamedAsAnAmbiguousImport() =
        assertSplit("$AMBIGUOUS\nquote(do: get)", "1.14.0-rc.0", "expanded {} next 0", "error ambiguous_call `get`")

    fun testAListIsQuotedFromItsLastElement() =
        assertEvery("$AMBIGUOUS\nquote(do: [get(1, 2), &get/2])", "error ambiguous_call `&get/2`")

    // The quoted value

    fun testAnAliasTheAliasesName() =
        assertValue(
            "alias String.Chars\nquote(do: Chars.to_string(1))",
            "{{:., [], [{:__aliases__, [alias: String.Chars], [:Chars]}, :to_string]}, [], [1]}",
        )

    fun testAnAliasNoAliasNames() = assertValue("quote(do: Foo.Bar)", "{:__aliases__, [alias: false], [:Foo, :Bar]}")

    fun testAnAliasInsideAnotherNode() =
        assertValue(
            "alias String.Chars\nquote(do: Chars.to_string(Foo.Bar))",
            "{{:., [], [{:__aliases__, [alias: String.Chars], [:Chars]}, :to_string]}, [], " +
                "[{:__aliases__, [alias: false], [:Foo, :Bar]}]}",
        )

    /** `alias: false` looks only in the macro aliases. */
    fun testAnAliasMarkedFalseIsNotLookedUpInTheAliases() =
        withKeys("Foo" to listOf(entry("alias", atom("false")))) {
            assertValue("alias Bar.Foo\nquote(do: Foo)", "{:__aliases__, [alias: false], [:Foo]}")
        }

    fun testAnAliasMarkedWithAModuleIsThatModule() =
        withKeys("Foo" to listOf(entry("alias", atom("Elixir.Bar")))) {
            assertValue("quote(do: Foo)", "{:__aliases__, [alias: Bar], [:Foo]}")
        }

    /** The prelude isn't in the escaped expression, which reads the variables the prelude binds. */
    fun testAQuoteWithAPreludeHasNoEscapedExpression() =
        assertValue("l = 3\nquote(line: l, do: x)", "no escaped expression")

    fun testAnAliasFromTheElixirRoot() = assertValue("quote(do: Elixir.Foo)", "{:__aliases__, [], [:Elixir, :Foo]}")

    fun testAnImportedCall() =
        assertSplitValue(
            "quote(do: is_atom(1))",
            "1.14.0-rc.0",
            "{:is_atom, [context: T, import: Kernel], [1]}",
            "{:is_atom, [context: T, imports: [{1, Kernel}]], [1]}",
        )

    fun testAnImportedCapture() =
        assertSplitValue(
            "quote(do: &inspect/1)",
            "1.14.0-rc.0",
            "{:&, [import: Kernel, context: T], [{:/, [context: T, import: Kernel], [{:inspect, [], T}, 1]}]}",
            "{:&, [imports: [{1, Kernel}], context: T], [{:/, [context: T, imports: [{2, Kernel}]], " +
                "[{:inspect, [context: T, imports: [{1, Kernel}, {2, Kernel}]], T}, 1]}]}",
        )

    fun testAVariable() = assertValue("quote(do: v)", "{:v, [], T}")

    fun testAContext() = assertValue("quote(context: Foo, do: v)", "{:v, [], Foo}")

    fun testALine() = assertValue("quote(line: 42, do: foo(v))", "{:foo, [line: 42], [{:v, [line: 42], T}]}")

    fun testGenerated() =
        assertValue("quote(generated: true, do: foo(v))", "{:foo, [generated: true], [{:v, [generated: true], T}]}")

    fun testAFileWithALine() =
        assertSplitValue(
            "quote(file: \"x.ex\", line: 7, do: foo(v))",
            "1.17.0-rc.0",
            "{:foo, [keep: {\"x.ex\", 1}], [{:v, [keep: {\"x.ex\", 1}], T}]}",
            "{:foo, [keep: {\"x.ex\", 7}], [{:v, [keep: {\"x.ex\", 7}], T}]}",
        )

    fun testAFileWithoutALine() =
        assertSplitValue(
            "quote(file: \"x.ex\", do: foo(v))",
            "1.17.0-rc.0",
            "{:foo, [keep: {\"x.ex\", 1}], [{:v, [keep: {\"x.ex\", 1}], T}]}",
            "{:foo, [keep: {\"x.ex\", 0}], [{:v, [keep: {\"x.ex\", 0}], T}]}",
        )

    fun testANestedQuote() =
        assertValue("quote(do: quote(do: v))", "{:quote, [context: T], [[do: {:v, [], T}]]}")

    fun testANestedQuoteKeepsItsUnquote() =
        assertValue(
            "quote(do: quote(do: unquote(v)))",
            "{:quote, [context: T], [[do: {:unquote, [], [{:v, [], T}]}]]}",
        )

    fun testUnquoteDisabled() =
        assertValue("quote(unquote: false, do: unquote(x))", "{:unquote, [], [{:x, [], T}]}")

    fun testBindQuoted() =
        assertLevelValues("quote(bind_quoted: [b: 1], do: b)", LEVELS + listOf("1.16.0-rc.0", "1.19.0-rc.2", "1.19.0")) {
            val binding = if (isBefore(it, "1.16.0-rc.0") || !isBefore(it, "1.19.0")) "[line: 1]" else "[line: 1, column: 1]"

            "{:__block__, [], [{:=, [], [{:b, $binding, T}, 1]}, {:b, [], T}]}"
        }

    fun testAGuardedDefinition() =
        assertLevelValues("quote do\n  def f(x) when x, do: 1\nend", LEVELS) { version ->
            val def = if (isBefore(version, "1.14.0-rc.0")) {
                "[context: T, import: Kernel]"
            } else {
                "[context: T, imports: [{1, Kernel}, {2, Kernel}]]"
            }
            val (`when`, head) = if (isBefore(version, "1.16.0-rc.0")) "[context: T]" to "[]" else "[]" to "[context: T]"

            "{:def, $def, [{:when, $`when`, [{:f, $head, [{:x, [], T}]}, {:x, [], T}]}, [do: 1]]}"
        }

    fun testADirective() =
        assertValue("quote(do: import Foo)", "{:import, [context: T], [{:__aliases__, [alias: false], [:Foo]}]}")

    fun testAnAmbiguousOperator() =
        assertValue("quote(do: foo -x)", "{:foo, [ambiguous_op: T], [{:-, [], [{:x, [], T}]}]}")

    fun testOtherMetadataIsKept() =
        assertValue("quote(do: x.y)", "{{:., [], [{:x, [], T}, :y]}, [no_parens: true], []}")

    fun testATupleListAndMap() =
        assertValue("quote(do: {[1, 2], %{a: 3}, {4, 5, 6}})", "{:{}, [], [[1, 2], {:%{}, [], [a: 3]}, {:{}, [], [4, 5, 6]}]}")

    fun testABlock() = assertValue("quote do\n  a\n  b\nend", "{:__block__, [], [{:a, [], T}, {:b, [], T}]}")

    fun testAnUnquotedVariableHasNoValue() = assertValue("x = 1\nquote(do: foo(unquote(x)))", "no value")

    fun testAnUnquotedLiteralBelow1_18() =
        assertSplitValue("quote(do: foo(unquote(1)))", "1.18.0-rc.0", "{:foo, [], [1]}", "no value")

    fun testTheLineOffsetShiftsSourceLinesOnly() {
        val level = ElixirLanguageLevel.of("1.20.4")

        assertEquals(
            "{:__block__, [], [{:=, [], [{:b, [line: 41], T}, 1]}, {:b, [], T}]} " +
                "{:foo, [line: 7], [{:v, [line: 7], T}]}",
            listOf("quote(bind_quoted: [b: 1], do: b)", "quote(line: 7, do: foo(v))").joinToString(" ") {
                value(it, level, lineOffset = 40)
            },
        )
    }

    // Helpers

    private fun assertValue(code: String, expected: String) = assertLevelValues(code, LEVELS) { expected }

    private fun assertSplitValue(code: String, boundary: String, before: String, from: String) =
        assertLevelValues(code, LEVELS + boundary) { if (isBefore(it, boundary)) before else from }

    private fun assertLevelValues(code: String, versions: List<String>, expected: (String) -> String) {
        val sorted = versions.distinct().sortedBy { ElixirLanguageLevel.of(it).elixir }

        assertEquals(
            sorted.joinToString("\n") { "$it: ${expected(it)}" },
            sorted.joinToString("\n") { "$it: ${value(code, ElixirLanguageLevel.of(it))}" },
        )
    }

    /**
     * The value of [code]'s last statement, a `quote`, expanded in module `T` from the empty env after the statements
     * before it.
     */
    private fun value(code: String, level: ElixirLanguageLevel, lineOffset: Int = 0): String {
        val ast = lower(code, level)
        val statements = if (ast is ElixirAst.Block) ast.expressions else listOf(ast)
        val run = Run(level, ExpansionObserver.NONE, exports, structs)
        var state = ExState.empty(level)
        var env = Env.empty(level, kernel).copy(module = "Elixir.T")

        for (statement in statements.dropLast(1)) {
            val expansion = Expander.expand(statement, state, env, run) as Expansion.Expanded
            state = expansion.state
            env = expansion.env
        }

        val escaped = Quote.escaped(statements.last() as ElixirAst.Call, state, env, run, lineOffset)
            ?: return "no escaped expression"

        return quotedValue(escaped)?.let(QuotedTerms::inspect) ?: "no value"
    }

    private companion object {
        const val AMBIGUOUS = "import Map, only: [get: 2]\nimport Keyword, only: [get: 2]"
    }
}
