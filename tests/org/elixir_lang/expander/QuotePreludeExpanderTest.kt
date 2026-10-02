package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst

/**
 * A `quote`'s run-time options, which its prelude binds as variables in the `elixir_quote` context, and the trace
 * events of the imports it quotes. Each expansion renders its variables with their contexts, then the dispatches and
 * quoted imports in the order the observer is told of them.
 */
class QuotePreludeExpanderTest : ExpanderTestCase() {
    override val exports: Exports = Exports { module ->
        when (module) {
            "Elixir.String" ->
                ModuleExports.Present(listOf(NameArity("length", 1), NameArity("trim", 1)), emptyList(), hasInfo = true)
            else -> ModuleExports.Absent
        }
    }

    override val kernel = KernelImports(
        functions = listOf(
            NameArity("/", 2),
            NameArity("context", 0),
            NameArity("inspect", 1),
            NameArity("inspect", 2),
            NameArity("is_atom", 1),
            NameArity("line", 0),
        ),
        macros = listOf(NameArity("and", 2), NameArity("is_nil", 1)),
    )

    override val module: String = "Elixir.Case"

    // The prelude

    fun testADynamicLineIsBoundInTheQuotesContext() =
        assertEvery("l = 3\nquote(line: l, do: x)", "expanded {l/nil:0 line/elixir_quote:1} next 2; $VALIDATE")

    /** The prelude's call is given the option as expanded, so Elixir expands it again. */
    fun testADynamicContextIsBoundAfterItsValueBindsAgain() =
        assertEvery("quote(context: c = Foo, do: x)", "expanded {c/nil:1 context/elixir_quote:2} next 3; $VALIDATE")

    fun testADynamicFileIsBoundInTheQuotesContext() =
        assertEvery("f = \"x.ex\"\nquote(file: f, do: x)", "expanded {f/nil:0 file/elixir_quote:1} next 2; $VALIDATE")

    fun testTheFileIsBoundBeforeTheLine() =
        assertEvery(
            "l = 3\nf = \"x.ex\"\nquote(line: l, file: f, do: x)",
            "expanded {f/nil:1 file/elixir_quote:2 l/nil:0 line/elixir_quote:3} next 4; $VALIDATE, $VALIDATE",
        )

    fun testAnInvalidLineIsValidatedAtRunTime() =
        assertEvery("l = :bad\nquote(line: l, do: 1)", "expanded {l/nil:0 line/elixir_quote:1} next 2; $VALIDATE")

    fun testALiteralLineThatIsNoLineIsValidatedAtRunTime() =
        assertEvery("quote(line: :bad, do: 1)", "expanded {line/elixir_quote:0} next 1; $VALIDATE")

    fun testANilContextIsValidatedAtRunTime() =
        assertEvery("quote(context: nil, do: x)", "expanded {context/elixir_quote:0} next 1; $VALIDATE")

    /** Before 1.17 `bind_quoted`'s values are expanded a second time, after the prelude. */
    fun testBindQuotedValuesBindAgainAfterThePreludeBefore1_17() =
        assertSplit(
            "quote(context: c = Foo, bind_quoted: [b: z = 2], do: b)",
            "1.17.0-rc.0",
            "expanded {c/nil:2 context/elixir_quote:3 z/nil:4} next 5; $VALIDATE",
            "expanded {c/nil:2 context/elixir_quote:3 z/nil:1} next 4; $VALIDATE",
        )

    fun testADynamicValueHoldingACallIsUnported() =
        assertEvery(
            "quote(file: f = String.trim(\"x.ex\"), do: x)",
            "unported `f = String.trim(\"x.ex\")`; remote_function Elixir.String.trim/1",
        )

    fun testABindQuotedValueHoldingACallIsUnportedBefore1_17() =
        assertSplit(
            "quote(bind_quoted: [b: String.length(\"a\")], do: b)",
            "1.17.0-rc.0",
            "unported `String.length(\"a\")`; remote_function Elixir.String.length/1",
            "expanded {} next 0; remote_function Elixir.String.length/1",
        )

    /** Before 1.15 an undefined variable is expanded as a local call, so the second pass is of that call. */
    fun testAnUndefinedVariableAsAValueIsACallBefore1_15() =
        assertSplit(
            "quote(line: context, do: x)",
            "1.15.0-rc.0",
            "unported `context`; imported_function Elixir.Kernel.context/0",
            "error undefined_var `context`; no events",
        )

    fun testAPseudoVariableAsABindQuotedValueIsNoCall() =
        assertEvery("quote(bind_quoted: [m: __MODULE__], do: m)", "expanded {} next 0; no events")

    /** From 1.13 a variable whose metadata says `if_undefined: :apply` is expanded as a local call in every mode. */
    fun testAnUndefinedVariableToApplyAsAValueIsACall() =
        withKeys("context" to listOf(entry("if_undefined", atom("apply")))) {
            assertEvery("quote(line: context, do: x)", "unported `context`; imported_function Elixir.Kernel.context/0")
        }

    /** Elixir's second expansion is of the first one's result, so the source is left once. */
    fun testTheSecondExpansionOfAValueIsNotObserved() {
        val code = "quote(context: c = Foo, bind_quoted: [b: z = 2], do: b)"

        assertEquals(
            LEVELS.joinToString("\n") { "$it: c = Foo left 1, z = 2 left 1" },
            LEVELS.joinToString("\n") { version ->
                val left = mutableMapOf<String, Int>()

                expand(code, version, object : ExpansionObserver {
                    override fun entering(node: ElixirAst, state: ExState, env: Env) {}

                    override fun left(node: ElixirAst, expansion: Expansion) {
                        left.merge(node.meta.origin.substring(code), 1, Int::plus)
                    }
                })

                "$version: c = Foo left ${left["c = Foo"]}, z = 2 left ${left["z = 2"]}"
            },
        )
    }

    /** `{Key, Meta, elixir_quote}` has the quote's metadata, so the counter linify gives the quote is its context. */
    fun testThePreludeOfAQuoteWithACounterBindsInTheCountersContext() =
        withKeys("quote(line: l, do: x)" to listOf(counter(1))) {
            assertEvery("l = 3\nquote(line: l, do: x)", "expanded {l/nil:0 line/{Elixir.Case,1}:1} next 2; $VALIDATE")
        }

    /** `do_quote_call` quotes the context, here the variable the prelude binds, and so traces its import. */
    fun testADynamicContextIsQuotedInAnUnquotedCall() {
        val code = "c = Foo\nquote(context: c, do: Bar.unquote(:f)(1))"

        assertEquals(
            LEVELS.joinToString("\n") { version ->
                "$version: " + if (isBefore(version, "1.14.0-rc.0")) {
                    "imported_function Elixir.Kernel.context/0"
                } else {
                    "imported_quoted Elixir.Kernel.context [0]"
                }
            },
            LEVELS.joinToString("\n") { version ->
                val events = mutableListOf<String>()

                expand(code, version, observer(events, dispatches = false))

                "$version: " + events.joinToString()
            },
        )
    }

    /** Before 1.13 `do_quote_call` quotes the call's metadata, which holds the line the prelude binds, ahead of the rest. */
    fun testADynamicLineIsQuotedInAnUnquotedCallsMetadataBefore1_13() {
        val code = "c = Foo\nl = 3\nquote(context: c, line: l, do: Bar.unquote(:f)(1))"

        assertEquals(
            LEVELS.joinToString("\n") { version ->
                "$version: " + when {
                    isBefore(version, "1.13.0-rc.0") ->
                        "imported_function Elixir.Kernel.line/0, imported_function Elixir.Kernel.context/0"
                    isBefore(version, "1.14.0-rc.0") -> "imported_function Elixir.Kernel.context/0"
                    else -> "imported_quoted Elixir.Kernel.context [0]"
                }
            },
            LEVELS.joinToString("\n") { version ->
                val events = mutableListOf<String>()

                expand(code, version, observer(events, dispatches = false))

                "$version: " + events.joinToString()
            },
        )
    }

    /** `{'__block__', [], Prelude ++ [Quoted]}`. */
    fun testAQuoteWithAPreludeIsABlock() =
        assertEquals(
            LEVELS.joinToString("\n") { "$it: $NODE" },
            LEVELS.joinToString("\n") { "$it: " + (expand("quote(line: :bad, do: 1)", it) as Expansion.Expanded).value },
        )

    /** `{'{}', [], ['__block__', [], Bindings ++ [Quoted]]}`. */
    fun testAQuoteWithBindQuotedIsABlock() =
        assertEquals(
            LEVELS.joinToString("\n") { "$it: $NODE" },
            LEVELS.joinToString("\n") { version ->
                "$version: " + (expand("quote(bind_quoted: [a: 1], do: true)", version) as Expansion.Expanded).value
            },
        )

    /** From 1.20 a quote outside a pattern or guard is `:elixir_quote.validate_quote(EBlock)`. */
    fun testAQuoteIsAValidatedNodeFrom1_20() =
        assertEquals(
            LEVELS.joinToString("\n") { "$it: ${isBefore(it, "1.20.0")}" },
            LEVELS.joinToString("\n") { "$it: " + ((expand("quote(do: 1)", it) as Expansion.Expanded).value != NODE) },
        )

    // Quoted imports

    fun testQuotedImportsAreTracedLastArgumentFirst() =
        assertSplit(
            "quote(do: is_atom(1) and is_nil(2))",
            "1.14.0-rc.0",
            "expanded {} next 0; imported_macro Elixir.Kernel.and/2, imported_macro Elixir.Kernel.is_nil/1, " +
                "imported_function erlang.is_atom/1",
            "expanded {} next 0; imported_quoted Elixir.Kernel.and [2], imported_quoted Elixir.Kernel.is_nil [1], " +
                "imported_quoted Elixir.Kernel.is_atom [1]",
        )

    fun testAQuotedCaptureTracesItsImportAndThenItsArguments() =
        assertSplit(
            "quote(do: &inspect/1)",
            "1.14.0-rc.0",
            "expanded {} next 0; imported_function Elixir.Kernel.inspect/1, imported_function erlang.//2",
            "expanded {} next 0; imported_function Elixir.Kernel.inspect/1, imported_quoted Elixir.Kernel./ [2], " +
                "imported_quoted Elixir.Kernel.inspect [1, 2]",
        )

    fun testAQuotedNameTracesEveryArityFrom1_14() =
        assertSplit(
            "quote(do: inspect)",
            "1.14.0-rc.0",
            "expanded {} next 0; no events",
            "expanded {} next 0; imported_quoted Elixir.Kernel.inspect [1, 2]",
        )

    /** From 1.17 the prelude is expanded before the body is quoted. */
    fun testTheBodyIsQuotedBeforeThePreludeBefore1_17() {
        val boundaries = listOf("1.14.0-rc.0", "1.17.0-rc.0")

        assertLevels(
            "l = 3\nquote(line: l, do: is_atom(1))",
            (LEVELS + boundaries).sortedBy { ElixirLanguageLevel.of(it).elixir },
        ) { version ->
            val imported = if (isBefore(version, "1.14.0-rc.0")) {
                "imported_function erlang.is_atom/1"
            } else {
                "imported_quoted Elixir.Kernel.is_atom [1]"
            }
            val events = if (isBefore(version, "1.17.0-rc.0")) "$imported, $VALIDATE" else "$VALIDATE, $imported"

            "expanded {l/nil:0 line/elixir_quote:1} next 2; $events"
        }
    }

    // Helpers

    override fun expandAndRender(code: String, version: String): String {
        val events = mutableListOf<String>()
        val expansion = expand(code, version, observer(events, dispatches = true))

        return "${render(code, expansion)}; ${events.joinToString().ifEmpty { "no events" }}"
    }

    /** Adds to [events] each quoted import, and each dispatch when [dispatches], in the order it is told of them. */
    private fun observer(events: MutableList<String>, dispatches: Boolean) =
        object : ExpansionObserver {
            override fun entering(node: ElixirAst, state: ExState, env: Env) {}

            override fun dispatched(node: ElixirAst, dispatch: Dispatch) {
                if (dispatches) events.add(render(dispatch))
            }

            override fun quotedImport(
                node: ElixirAst,
                kind: QuotedImportKind,
                module: String,
                name: String,
                arities: List<Int>,
            ) {
                val event = kind.name.lowercase()

                events.add(
                    if (kind == QuotedImportKind.IMPORTED_QUOTED) {
                        "$event $module.$name $arities"
                    } else {
                        "$event $module.$name/${arities.single()}"
                    }
                )
            }
        }

    /** As [ExpanderTestCase.render], with each variable's context. */
    override fun render(code: String, expansion: Expansion): String =
        when (expansion) {
            is Expansion.Expanded -> {
                val state = expansion.state
                val read = state.read.entries
                    .sortedWith(compareBy(TERM_ORDER) { it.key })
                    .joinToString(" ") { (variable, version) ->
                        "${variable.name}/${context(variable.context)}:$version"
                    }

                "expanded {$read} next ${state.version}"
            }
            else -> super.render(code, expansion)
        }

    private fun context(context: Variable.Context): String =
        when (context) {
            is Variable.Context.Atom -> context.text
            is Variable.Context.Counter -> when (val counter = context.counter) {
                is Env.Counter.InModule -> "{${counter.module},${counter.n}}"
                is Env.Counter.Unique -> "${counter.n}"
            }
        }

    private companion object {
        const val VALIDATE = "remote_function elixir_quote.validate_runtime/2"
    }
}
