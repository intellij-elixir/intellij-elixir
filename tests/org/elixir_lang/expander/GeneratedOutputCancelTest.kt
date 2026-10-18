package org.elixir_lang.expander

import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * A macro's output can be far larger than any source, so each walk over what a macro generated is cancelled as it goes,
 * partway through the output. Each test cancels at, or counts, the checks its walks make themselves, so removing any
 * one of those checks turns a test red.
 */
class GeneratedOutputCancelTest : ExpanderTestCase() {
    override val exports: Exports = legExports
    override val kernel: KernelImports = legKernel
    override val structs: Structs = legStructs

    /** Linifying the output, as every macro call's output is, is cancelled as it copies the output. */
    fun testLinifyIsCancelledAsItCopiesTheOutput() =
        assertCancelledIn(HYGIENE, "linify", "defmacro m, do: ($TREE t)", LISTS / 2)

    /** A macro whose body is a large literal, written by another macro, is cancelled as the body is evaluated. */
    fun testAWrittenMacrosBodyIsCancelledAsItIsEvaluated() =
        assertCancelledIn(
            EVALUATION,
            $$"eval$intellij_elixir",
            "defmacro m, do: ($TREE {:defmacro, [], [{:n, [], []}, [do: t]]})",
            LISTS / 2,
            after = CALLS_N,
        )

    /** A macro written by another macro, matching a large pattern of `_`, is cancelled as the pattern is matched. */
    fun testAWrittenMacrosPatternIsCancelledAsItIsMatched() =
        assertCancelledIn(
            EVALUATION,
            $$"match$intellij_elixir",
            "defmacro m, do: ($TREE p = {:_, [], nil}; ${"p = [p, p]; ".repeat(DOUBLINGS)} " +
                "{:defmacro, [], [{:n, [], []}, [do: {:case, [], [t, [do: [{:->, [], [[p], :ok]}]]]}]]})",
            LISTS / 2,
            after = CALLS_N,
        )

    /** Giving another macro the output, as its argument, is cancelled as the argument is written as a term. */
    fun testAnArgumentIsCancelledAsItIsWrittenAsATerm() =
        assertCancelledIn(
            "org.elixir_lang.lowering.ElixirAst",
            "toOtp",
            """
            defmacro n(_), do: :ok
            defmacro m, do: ($TREE quote(do: A.n(unquote(t))))
            """,
            LISTS / 2,
        )

    /** Giving another macro a call whose metadata holds a large value is cancelled as the metadata is written. */
    fun testAnArgumentsMetadataIsCancelledAsItIsWritten() =
        assertCancelledIn(
            $$"org.elixir_lang.lowering.Meta$Companion",
            "toOtp",
            """
            defmacro n(_), do: :ok
            defmacro m, do: ($TREE quote(do: A.n(unquote({:f, [k: t], nil}))))
            """,
            LISTS / 2,
        )

    /** Output in place of a module body's statement is cancelled as its statements are recorded. */
    fun testAStatementsOutputIsCancelledAsItsStatementsAreRecorded() =
        assertCancelledIn(
            "${EXPANDER}Compiling",
            "addStatements",
            "defmacro m, do: (t = :ok; ${"t = {:__block__, [], [t, t]}; ".repeat(DOUBLINGS)} t)",
            LISTS / 2,
        )

    /** Output that is a block of one expression in a block of one, many deep, is cancelled as its shape is read. */
    fun testANestedBlocksShapeIsCancelledAsItIsRead() =
        assertCancelledIn(
            "${EXPANDER}ExpanderKt",
            "expandedShape",
            "defmacro m, do: (t = :ok; ${chain("t = {:__block__, [], [t]}")} t)",
            CHAIN / 2,
        )

    /** A definition whose head has many guards is cancelled as its guards are split. */
    fun testAHeadsGuardsAreCancelledAsTheyAreSplit() =
        assertCancelledIn(
            "${EXPANDER}DefinitionKt",
            "orGuards",
            "defmacro m, do: (g = true; ${chain("g = {:when, [], [true, g]}")} " +
                "{:def, [], [{:when, [], [{:f, [], []}, g]}, [do: :ok]]})",
            CHAIN / 2,
        )

    /** A `quote` option that is a long chain of matches is cancelled as it is read for a call. */
    fun testAQuoteOptionsMatchesAreCancelledAsTheyAreRead() =
        assertCancelledIn(
            "${EXPANDER}Quote",
            "holdsCall",
            "defmacro m, do: (v = 1; ${chain("v = {:=, [], [{:a, [], nil}, v]}")} {:quote, [], [[line: v], [do: :ok]]})",
            CHAIN / 2,
        )

    /** An `import`'s large option is cancelled as it is read for a part the expansion can't tell. */
    fun testAnImportsOptionsAreCancelledAsTheyAreReadForUnknownParts() =
        assertCancelledIn(
            $$"org.elixir_lang.psi.Import$Term",
            "hasUnknownPart",
            "defmacro m, do: ($TREE quote(do: import(Kernel, warn: unquote(t))))",
            LISTS / 2,
        )

    /** A pattern that is a long chain of matches is cancelled as the chain is unpacked. */
    fun testAPatternsMatchesAreCancelledAsTheyAreUnpacked() =
        assertCancelledIn(
            "${EXPANDER}MatchKt",
            "unpackMatch",
            "defmacro m, do: (p = {:a, [], nil}; ${chain("p = {:=, [], [p, {:a, [], nil}]}")} {:=, [], [p, 1]})",
            CHAIN / 2,
        )

    /** A `++` in a pattern whose left side's tails are lists, many deep, is cancelled as it is appended. */
    fun testAnAppendInAPatternIsCancelledAsItIsAppended() =
        assertCancelledIn(
            "${EXPANDER}RewriteKt",
            "staticAppend",
            "defmacro m, do: (l = [1]; ${chain("l = [{:|, [], [1, l]}]")} " +
                "{:=, [], [{:++, [], [l, {:x, [], nil}]}, [1]]})",
            CHAIN / 2,
        )

    /** An `in` of a list whose tails are lists, many deep, is cancelled as its elements are bound, before 1.13. */
    fun testAnInsElementsAreCancelledAsTheyAreBound() =
        assertCancelledIn("${EXPANDER}In", "ensureEvaled", IN_CONSES, CHAIN / 2, version = "1.12.3")

    /** An `in` of a list whose tails are lists, many deep, is cancelled as its elements are compared, before 1.13. */
    fun testAnInsElementsAreCancelledAsTheyAreCompared() =
        assertCancelledIn("${EXPANDER}In", "comp", IN_CONSES, CHAIN / 2, version = "1.12.3")

    /** A pipe whose right side is a pipe, many deep, is cancelled as it is unpiped. */
    fun testAPipeIsCancelledAsItIsUnpiped() =
        assertCancelledIn("${EXPANDER}PipeKt", "unpipe", PIPES, CHAIN / 2)

    /** A pipe of many operands is cancelled as each operand is piped. */
    fun testAPipeIsCancelledAsItsOperandsArePiped() =
        assertCancelledIn($$"$${EXPANDER}PipeKt$PIPE$1", "output", PIPES, CHAIN / 2)

    /** A `<>` whose right side is a `<>`, many deep, is cancelled as its operands are read. */
    fun testAConcatenationIsCancelledAsItsOperandsAreRead() =
        assertCancelledIn("${EXPANDER}ConcatKt", "concatenations", CONCATENATIONS, CHAIN / 2)

    /** A `<>` of many operands is cancelled as each operand is made a segment. */
    fun testAConcatenationIsCancelledAsItsSegmentsAreMade() =
        assertCancelledIn($$"$${EXPANDER}ConcatKt$CONCAT$1", "output", CONCATENATIONS, CHAIN / 2)

    /** `defstruct` of a `++` that `Kernel` wrote, many deep, is cancelled as its fields are evaluated. */
    fun testAStructsFieldsAreCancelledAsTheyAreEvaluated() =
        assertCancelledIn(
            "${EXPANDER}DefstructKt",
            "evaluate",
            "defmacro m, do: (f = [a: 1]; ${chain("f = {:++, [context: Kernel], [[a: 1], f]}")} {:defstruct, [], [f]})",
            CHAIN / 2,
        )

    /** A quoted definition whose head has many guards, each around the last, is cancelled as its head is found. */
    fun testAQuotedDefinitionsHeadIsCancelledAsItIsFound() =
        assertCancelledIn(
            "${EXPANDER}QuoteEscapeKt",
            "definitionHead",
            "defmacro m, do: (h = {:f, [], []}; ${chain("h = {:when, [], [h, true]}")} " +
                "{:quote, [], [[do: {:def, [], [h, [do: :ok]]}]]})",
            CHAIN / 2,
        )

    /** `defimpl` for a large list of lists is cancelled as an implementation is written for each. */
    fun testAnImplementationsTypesAreCancelledAsTheyAreWritten() =
        assertCancelledIn("${EXPANDER}ImplKt", "output", IMPLEMENTATION, LISTS / 2)

    /** `defimpl` for a large list of lists is cancelled as the list is expanded. */
    fun testAnImplementationsTypesAreCancelledAsTheyAreExpanded() =
        assertCancelledIn("${EXPANDER}LiteralExpander", "expand", IMPLEMENTATION, LISTS / 2)

    /** A segment of many specs is cancelled as its specs are unpacked. */
    fun testASegmentsSpecsAreCancelledAsTheyAreUnpacked() =
        assertCancelledIn(
            "${EXPANDER}BitstringKt",
            "unpackSpecs",
            "defmacro m, do: (s = {:integer, [], nil}; ${chain("s = {:-, [], [s, {:integer, [], nil}]}")} " +
                "{:<<>>, [], [{:\"::\", [], [1, s]}]})",
            CHAIN / 2,
        )

    /** A map key that is a block of one expression in a block of one, many deep, is cancelled as it is read. */
    fun testAMapKeysBlocksAreCancelledAsTheyAreRead() =
        assertCancelledIn(
            "${EXPANDER}MapExpansionKt",
            "withoutBlocks",
            "defmacro m, do: (k = :a; ${chain("k = {:__block__, [], [k]}")} {:%{}, [], [{k, 1}]})",
            CHAIN / 2,
        )

    /** A definition with an unquote fragment checks as it finds, orders, values and fills the fragment. */
    fun testADefinitionsFragmentsAreChecked() =
        assertChecked(
            listOf(
                "${EXPANDER}DefinitionKt" to "hasUnquotes",
                "${EXPANDER}Unquotes" to "collect",
                "${EXPANDER}Unquotes" to "order",
                "${EXPANDER}DefinitionKt" to "literalOf",
                "${EXPANDER}DefinitionKt" to "substitute",
            ),
            LISTS / 2,
            "defmacro m, do: ($TREE {:def, [], [{:f, [], []}, [do: [t, {:unquote, [], [t]}]]]})",
        )

    /** An implementation's definition stopped at a fragment checks as its fragments are filled. */
    fun testAnImplementationsStoppedDefinitionIsChecked() =
        assertChecked(
            listOf("${EXPANDER}FillKt" to "collect"),
            LISTS / 2,
            "defmacro m, do: ($TREE {:defimpl, [], [Enumerable, [for: Integer], [do: {:def, [], [{:f, [], [{:_, [], nil}]}, " +
                "[do: [t, {:unquote, [], [{:{}, [], [:a, :b, :c]}]}]]]}]]})",
        )

    /** A delegation's large head checks as it is read for an unquote. */
    fun testADelegationsHeadIsChecked() =
        assertChecked(
            listOf("${EXPANDER}DefdelegateKt" to "mentionsUnquote"),
            LISTS / 2,
            "defmacro m, do: ($TREE {:defdelegate, [], [{:f, [], [t]}, [to: List]]})",
        )

    /** A guard checks as its arguments' variables are read. */
    fun testAGuardsArgumentsAreChecked() =
        assertChecked(
            listOf("${EXPANDER}DefguardKt" to "refs"),
            1,
            "defguard g(x) when is_integer(x)",
            call = "def f(y) when A.g(y), do: y",
        )

    /** A protocol checks as its stopped definitions are read for its guard. */
    fun testAProtocolsStoppedDefinitionsAreChecked() =
        assertChecked(
            listOf("${EXPANDER}ProtocolTail" to "callsGuard"),
            1,
            "defprotocol P do\n  def f(x)\nend",
            call = "",
        )

    /** A `quote` whose run-time option is large checks as the option is copied. */
    fun testAQuotesRunTimeOptionIsChecked() =
        assertChecked(
            listOf("${EXPANDER}Quote" to "builtCopy"),
            LISTS / 2,
            "defmacro m, do: ($TREE {:quote, [], [[context: {:hd, [], [[t]]}], [do: :ok]]})",
        )

    /** A `quote` in a pattern checks as its body is read for an unquote. */
    fun testAQuoteInAPatternIsChecked() =
        assertChecked(
            listOf("${EXPANDER}Quote" to "hasUnquotes"),
            LISTS / 2,
            "defmacro m, do: ($TREE {:case, [], [1, [do: [{:->, [], [[{:quote, [], [[do: [t, {:unquote, [], [{:y, [], nil}]}]]]}], :ok]}]]]})",
        )

    /** A quoted call whose metadata holds a large value checks as the metadata is quoted. */
    fun testAQuotedCallsMetadataIsChecked() =
        assertChecked(
            listOf("${EXPANDER}ParsedMeta" to "code", "${EXPANDER}ParsedMeta" to "pairs"),
            LISTS / 2,
            "defmacro m, do: ($TREE {:quote, [], [[do: {:f, [k: t], []}]]})",
        )

    /** A capture of a large call checks as its body is escaped. */
    fun testACapturesBodyIsChecked() =
        assertChecked(
            listOf("${EXPANDER}Escape" to "escape"),
            LISTS / 2,
            "defmacro m, do: ($TREE {:&, [], [{:f, [], [t, {:&, [], [1]}]}]})",
        )

    /** A map pattern whose key is large checks as the key is validated and read. */
    fun testAMapPatternsKeyIsChecked() =
        assertChecked(
            listOf(
                "${EXPANDER}MapExpansionKt" to "matchKeyError",
                "${EXPANDER}MapExpansionKt" to "isLiteral",
                "${EXPANDER}MapExpansionKt" to "literalShape",
            ),
            LISTS / 2,
            "defmacro m, do: ($TREE {:=, [], [{:%{}, [], [{t, 1}]}, {:%{}, [], []}]})",
        )

    /** A match of two large patterns in a pattern checks as they are paired for a bitstring, before 1.18. */
    fun testAMatchInAPatternIsCheckedForBitstrings() =
        assertChecked(
            listOf("${EXPANDER}MatchKt" to "parallelBitstring"),
            LISTS / 2,
            "defmacro m, do: ($TREE {:case, [], [1, [do: [{:->, [], [[{:=, [], [t, t]}], :ok]}]]]})",
            version = "1.17.3",
        )

    /** A match in a pattern of many variables checks as its variables are checked for a cycle. */
    fun testAMatchInAPatternIsCheckedForCycles() {
        val variables = { name: String -> (1..VARIABLES).joinToString { "$name$it" } }

        assertChecked(
            listOf(
                "${EXPANDER}MatchKt" to "storeCycles",
                "${EXPANDER}MatchKt" to "isCyclic",
                "${EXPANDER}MatchKt" to "recurCycles",
            ),
            VARIABLES,
            "defmacro m do\n  quote do\n    case 1 do\n      {${variables("x")}} = {${variables("y")}} -> :ok\n" +
                "    end\n  end\nend",
        )
    }

    /** A bitstring pattern of bitstrings, many deep, checks as it is read for a match, before 1.19. */
    fun testABitstringPatternIsCheckedForAMatch() =
        assertChecked(
            listOf("${EXPANDER}BitstringKt" to "containsMatch"),
            CHAIN / 2,
            "defmacro m, do: (b = {:x, [], nil}; ${chain("b = {:<<>>, [], [b]}")} {:=, [], [{:<<>>, [], [b]}, \"\"]})",
            version = "1.18.4",
        )

    /** A struct field whose default is large checks as the default is unlocated. */
    fun testAStructFieldsDefaultIsChecked() =
        assertChecked(
            listOf("${EXPANDER}DefstructKt" to "unlocated"),
            LISTS / 2,
            "defmacro m, do: ($TREE {:defstruct, [], [[a: {:hd, [], [[t]]}]]})",
        )

    /** A local call in a local call, many deep, checks as the local calls are walked. */
    fun testLocalCallsAreCheckedAsTheyAreWalked() =
        assertChecked(
            listOf("${EXPANDER}Traversal" to "walk", "${EXPANDER}Traversal" to "visit"),
            CHAIN / 2,
            NESTED_LOCAL_CALLS,
            call = NESTED_LOCAL_CALLS_CALL,
        )

    /** Before 1.18 the same local calls check as they are listed. */
    fun testLocalCallsAreCheckedAsTheyAreListed() =
        assertChecked(
            listOf("${EXPANDER}LocalChecksKt" to "withArguments"),
            CHAIN / 2,
            NESTED_LOCAL_CALLS,
            call = NESTED_LOCAL_CALLS_CALL,
            version = "1.17.3",
        )

    /** A segment of many specs, in a macro read from debug info, is cancelled as its specs are read. */
    fun testADebugInfoSegmentsSpecsAreCancelledAsTheyAreRead() {
        val body = lower("<<1::${"integer()-".repeat(CHAIN)}integer()>>", ElixirLanguageLevel.of("1.20.4"))
        val definition = ModuleClauses.Definition(DefinitionTable.Kind.DEFMACRO, listOf(ModuleClauses.Clause(emptyList(), emptyList(), body, null)))
        val d = ModuleClauses(
            "Elixir.D",
            mapOf(NameArity("n", 0) to definition),
            ModuleClauses.Source.DBGI,
            ModuleClauses.Quotes(),
            null,
        )
        val exports = Exports { module ->
            if (module == "Elixir.D") ModuleExports.Present(emptyList(), listOf(NameArity("n", 0)), hasInfo = true) else legExports.of(module)
        }
        val expanding = expanding("", "require D\n  D.n()", "1.20.4", exports = exports, clauses = { if (it == "Elixir.D") d else null })
        var ended = "not run"

        assertCancelledIn("${EXPANDER}interpreter.Bits", "parts", CHAIN / 2, { ended }) { ended = expanding() }
    }

    /** `:lists.append/1` of many lists is cancelled as they are appended. */
    fun testAnAppendOfManyListsIsCancelledAsTheyAreAppended() =
        assertCancelledIn(
            "${EXPANDER}interpreter.Natives",
            "append",
            "defmacro m, do: (l = []; ${chain("l = [[1] | l]")} :lists.append(l))",
            CHAIN / 2,
        )

    /** A quoted definition built by `unquote`, whose head has many guards, is cancelled as its head is annotated. */
    fun testABuiltDefinitionsHeadIsCancelledAsItIsAnnotated() =
        assertCancelledIn(
            "${EXPANDER}interpreter.Natives",
            "annotateHead",
            "defmacro m, do: (h = {:f, [], []}; ${chain("h = {:when, [], [h, true]}")} " +
                "quote(do: unquote(Kernel).unquote(:def)(unquote(h), do: :ok)))",
            CHAIN / 2,
        )

    /** An attribute whose value is a list in a list, many deep, is cancelled as its shape is read. */
    fun testAnAttributesNestedListsAreCancelledAsTheirShapeIsRead() =
        assertCancelledIn("${EXPANDER}AttributeTableKt", "isShaped", nestedListsIn("x"), CHAIN / 2)

    /** `@compile` of a list in a list, many deep, is cancelled as its options are checked. */
    fun testACompileOptionsNestedListsAreCancelledAsTheyAreChecked() =
        assertCancelledIn("${EXPANDER}Steps", "compileOption", nestedListsIn("compile"), CHAIN / 2)

    /** `@dialyzer` of many options, which may not nest, is cancelled as they are flattened. */
    fun testADialyzerOptionsAreCancelledAsTheyAreFlattened() =
        assertCancelledIn(
            $$"$${EXPANDER}Steps$Companion",
            "flatten",
            "defmacro m, do: (t = []; ${chain("t = [:no_return | t]")} {:@, [], [{:dialyzer, [], [t]}]})",
            CHAIN / 2,
        )

    /** A clause's guard of many `when`s, each around the last, is cancelled as it is expanded. */
    fun testAGuardOfManyWhensIsCancelledAsItIsExpanded() =
        assertCancelledIn(
            "${EXPANDER}GuardKt",
            "guard",
            "defmacro m, do: (g = true; ${chain("g = {:when, [], [true, g]}")} " +
                "{:case, [], [1, [do: [{:->, [], [[{:when, [], [{:x, [], nil}, g]}], :ok]}]]]})",
            CHAIN / 2,
        )

    /** `@derive` of a list in a list, many deep, is cancelled as its protocols are read. */
    fun testADerivesNestedListsAreCancelledAsTheyAreRead() =
        assertCancelledIn(
            "${EXPANDER}DeriveKt",
            "protocolsOf",
            "defmacro m, do: (d = {:__aliases__, [], [:Inspect]}; ${chain("d = [d]")} " +
                "{:__block__, [], [{:@, [], [{:derive, [], [d]}]}, {:defstruct, [], [[:a]]}]})",
            CHAIN / 2,
        )

    /** A struct field whose default holds a `fn` in a list in a list, many deep, is cancelled as it is evaluated. */
    fun testAStructFieldsNestedDefaultIsCancelledAsItIsEvaluated() =
        assertCancelledIn(
            "${EXPANDER}DefstructKt",
            "evaluated",
            "defmacro m, do: (d = {:fn, [], [{:->, [], [[], 1]}]}; ${chain("d = [d]")} {:defstruct, [], [[a: d]]})",
            CHAIN / 2,
        )

    /** A capture of a block of one expression in a block of one, many deep, is cancelled as it is expanded. */
    fun testACapturesNestedBlocksAreCancelledAsTheyAreExpanded() =
        assertCancelledIn(
            "${EXPANDER}CaptureKt",
            "capture",
            "defmacro m, do: (b = {:f, [], [{:&, [], [1]}]}; ${chain("b = {:__block__, [], [b]}")} {:&, [], [b]})",
            CHAIN / 2,
        )

    /** Before 1.16 an alias of an alias, many deep, is cancelled as it is looked up. */
    fun testAnAliasOfAnAliasIsCancelledAsItIsLookedUp() =
        assertCancelledIn(
            "${EXPANDER}DirectiveKt",
            "lookup",
            "",
            CHAIN / 2,
            call = (1..CHAIN).joinToString("\n  ") { "alias Elixir.X${it + 1}, as: X$it" } + "\n  X1",
            version = "1.15.8",
        )

    /** A module body whose macro writes many definitions is cancelled as it walks what the body queued. */
    fun testAModuleBodysQueuedDefinitionsAreCancelledAsTheyAreWalked() =
        assertCancelledIn(
            "${EXPANDER}CompileKt",
            "compileModule",
            "defmacro m, do: (t = quote(do: def(f, do: 1)); ${"t = {:__block__, [], [t, t]}; ".repeat(DOUBLINGS)} t)",
            LISTS / 2,
        )

    /**
     * An escaped map whose equal keys order their entries by comparing a value only Elixir knows with `t`, a list holding
     * itself twice at every depth, too large to write out, ends: no order is known, and `t` isn't written out.
     */
    fun testAnUnorderedComparisonWithALargeSharedTermEnds() =
        assertEquals(
            "Elixir.A Compiled, Elixir.B Stopped",
            expanding(
                "defmacro m, do: (t = []; ${"t = [t, t]; ".repeat(SHARED)} Macro.escape(%{1 => inspect(:a), 1.0 => t}))",
                "A.m()",
                "1.20.4",
            )(),
        )

    /** A map read by a key equal to the map's, built apart from it, is cancelled as the two keys are compared. */
    fun testAMapReadIsCancelledAsItComparesKeys() =
        assertCancelledIn(VALUES, "sameTerm", "defmacro m, do: (${tree("a")} ${tree("b")} :maps.get([b], %{[a] => :ok}))", LISTS / 2)

    /** A map built with two equal keys, built apart, is cancelled as the two keys are compared. */
    fun testAMapBuiltWithEqualKeysIsCancelledAsItComparesThem() =
        assertCancelledIn(VALUES, "sameTerm", "defmacro m, do: (${tree("a")} ${tree("b")} Macro.escape(%{[a] => 1, [b] => 2}))", LISTS / 2)

    /**
     * The expansion of `B`, which calls `A`'s [macros] by [call], is cancelled at the [n]th check [walk], a method of
     * [owner], makes.
     */
    private fun assertCancelledIn(
        owner: String,
        walk: String,
        macros: String,
        n: Int,
        call: String = "A.m()",
        version: String = "1.20.4",
        after: String = "",
    ) {
        val expanding = expanding(macros, call, version, after)
        var ended = "not run"

        assertCancelledIn(owner, walk, n, { ended }) { ended = expanding() }
    }

    /** Each of [walks], a class and a method of it, makes at least [least] checks as `B`, calling `A`'s [macros] by [call], expands. */
    private fun assertChecked(
        walks: List<Pair<String, String>>,
        least: Int,
        macros: String,
        call: String = "A.m()",
        version: String = "1.20.4",
    ) {
        val expanding = expanding(macros, call, version)
        val indicator = ChecksBy()
        var ended = "not run"

        whileCheckingIndicators { ProgressManager.getInstance().runProcess({ ended = expanding() }, indicator) }

        val counts = walks.map { (owner, walk) -> walk to indicator.count(owner, walk) }

        assertTrue("fewer than $least checks: $counts; $ended", counts.all { (_, count) -> count >= least })
    }

    /**
     * Expands `A`, of [macros], `B`, which requires `A` and runs [call], and then [after], at [version], giving how each
     * module ended.
     */
    private fun expanding(
        macros: String,
        call: String,
        version: String,
        after: String = "",
        exports: Exports = this.exports,
        clauses: ModuleClauses.Lookup = ModuleClauses.Lookup.NONE,
    ): () -> String {
        val code = "defmodule A do\n${macros.trimIndent()}\nend\n\ndefmodule B do\n  require A\n  $call\nend\n\n$after\n"
        val level = ElixirLanguageLevel.of(version)
        val forms = lower(code, level)

        return {
            Expander.expandFile(forms, Env.empty(level, kernel), level, exports, structs, clauses = clauses)
                .modules.joinToString { "${it.module} ${it.ended::class.simpleName}" }
        }
    }

    private companion object {
        const val EXPANDER = "org.elixir_lang.expander."
        const val HYGIENE = "${EXPANDER}HygieneKt"
        const val EVALUATION = "${EXPANDER}interpreter.Evaluation"

        /** A module calling `B.n`, the macro `A.m` writes into `B`. */
        const val CALLS_N = "defmodule C do\n  require B\n  def f, do: B.n()\nend"

        /** How many times [TREE] doubles its list. */
        const val DOUBLINGS = 12

        /** The lists [TREE] holds, itself included, as a macro's output holds them. */
        const val LISTS = (1 shl (DOUBLINGS + 1)) - 1

        /**
         * How long a chain the chain walks are given: short enough for the EDT's 1 MB stack, since each link nests the
         * expander's frames, and a chain of 256 took up to about 1.1 MB and stopped its module.
         */
        const val CHAIN = 64

        /** How many variables each side of a match has. */
        const val VARIABLES = 100

        /** How many times a shared term too large to write out doubles: its leaves are 2^64. */
        const val SHARED = 64

        const val VALUES = "${EXPANDER}interpreter.ValuesKt"

        /** A body building `t`, a list that holds itself twice at every depth. */
        val TREE = tree("t")

        /** A body building [name], a list that holds itself twice at every depth. */
        fun tree(name: String): String = "$name = []; " + "$name = [$name, $name]; ".repeat(DOUBLINGS)

        /** `1 in [y | [y | ...]]`, [CHAIN] deep. */
        val IN_CONSES =
            "defmacro m, do: (l = [{:y, [], nil}]; ${chain("l = [{:|, [], [{:y, [], nil}, l]}]")} {:in, [], [1, l]})"

        /** `1 |> (f() |> (f() |> ...))`, [CHAIN] deep. */
        val PIPES = "defmacro m, do: (r = {:f, [], []}; ${chain("r = {:|>, [], [{:f, [], []}, r]}")} {:|>, [], [1, r]})"

        /** `"a" <> ("a" <> ...)`, [CHAIN] deep. */
        val CONCATENATIONS = "defmacro m, do: (c = \"a\"; ${chain("c = {:<>, [], [\"a\", c]}")} c)"

        /** `defimpl Enumerable, for: t`, `t` of [TREE]. */
        val IMPLEMENTATION = "defmacro m, do: ($TREE {:defimpl, [], [Enumerable, [for: t], [do: :ok]]})"

        /** `def g, do: f(f(...))`, [CHAIN] deep. */
        val NESTED_LOCAL_CALLS = "defmacro m, do: (c = 1; ${chain("c = {:f, [], [c]}")} {:def, [], [{:g, [], []}, [do: c]]})"

        /** [NESTED_LOCAL_CALLS]'s call, with the `f` it calls. */
        const val NESTED_LOCAL_CALLS_CALL = "A.m()\n  def f(x), do: x"

        /** `@`[name] of `[[...[]...]]`, [CHAIN] deep. */
        fun nestedListsIn(name: String): String = "defmacro m, do: (t = []; ${chain("t = [t]")} {:@, [], [{:$name, [], [t]}]})"

        /** [statement], each run on the last's result, [CHAIN] times. */
        fun chain(statement: String): String = "$statement; ".repeat(CHAIN)
    }
}
