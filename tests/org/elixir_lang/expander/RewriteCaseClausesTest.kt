package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta

/**
 * `elixir_expand:rewrite_case_clauses/1`: the options of a `case` whose subject returns a boolean, with `if`'s two
 * clauses turned into `false` and `true` ones.
 */
class RewriteCaseClausesTest : ExpanderTestCase() {
    fun testTheKernelInGuardIsRewrittenBefore1_20() =
        assertSplit(
            "case s do\n  x when :\"Elixir.Kernel\".in(x, [false, nil]) -> 1\n  _ -> 2\nend",
            "1.20.0-rc.0",
            REWRITTEN,
            UNCHANGED,
        )

    fun testTheOrelseGuardIsRewrittenFrom1_20() =
        assertSplit(
            "case s do\n  x when :erlang.orelse(:erlang.\"=:=\"(x, false), :erlang.\"=:=\"(x, nil)) -> 1\n  _ -> 2\nend",
            "1.20.0-rc.0",
            UNCHANGED,
            REWRITTEN,
        )

    fun testTheKernelInGuardOfAnotherVariableIsUnchanged() =
        assertEvery("case s do\n  x when :\"Elixir.Kernel\".in(y, [false, nil]) -> 1\n  _ -> 2\nend", UNCHANGED)

    fun testTheOrelseGuardOfAnotherVariableIsUnchanged() =
        assertEvery(
            "case s do\n  x when :erlang.orelse(:erlang.\"=:=\"(x, false), :erlang.\"=:=\"(y, nil)) -> 1\n  _ -> 2\nend",
            UNCHANGED,
        )

    fun testTheOrelseGuardOfAVariableOfNoContextIsUnchanged() =
        assertEvery(
            "case s do\n  z when :erlang.orelse(:erlang.\"=:=\"(z, false), :erlang.\"=:=\"(z, nil)) -> 1\n  _ -> 2\nend",
            UNCHANGED,
        )

    fun testTheKernelInGuardWithAnotherListIsUnchanged() =
        assertEvery("case s do\n  x when :\"Elixir.Kernel\".in(x, [nil, false]) -> 1\n  _ -> 2\nend", UNCHANGED)

    fun testTheKernelInGuardBeforeAClauseOtherThanUnderscoreIsUnchanged() =
        assertEvery("case s do\n  x when :\"Elixir.Kernel\".in(x, [false, nil]) -> 1\n  y -> 2\nend", UNCHANGED)

    fun testFalseThenTrueIsRewritten() = assertEvery("case s do\n  false -> 1\n  true -> 2\nend", REWRITTEN)

    fun testFalseThenTrueDropsTheClausesAfterThem() =
        assertEvery("case s do\n  false -> 1\n  true -> 2\n  _ -> 3\nend", REWRITTEN)

    fun testTrueThenFalseIsUnchanged() = assertEvery("case s do\n  true -> 1\n  false -> 2\nend", UNCHANGED)

    fun testOneClauseIsUnchanged() = assertEvery("case s do\n  false -> 1\nend", UNCHANGED)

    fun testOtherClausesAreUnchanged() = assertEvery("case s do\n  1 -> 1\n  _ -> 2\nend", UNCHANGED)

    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val options = (lower(code, level) as ElixirAst.Call).arguments!![1].let(::kernelVariables)
        val rewritten = rewriteCaseClauses(options, level)

        return if (rewritten === options) UNCHANGED else render(code, options, rewritten)
    }

    /** [rewritten] as `<clause> => <pattern> -> <body>` per clause, each by its source; a built pattern by its atom. */
    private fun render(code: String, options: ElixirAst, rewritten: ElixirAst): String {
        val before = clauses(options)

        return clauses(rewritten).joinToString("; ") { arrow ->
            val (patterns, body) = arrow.arguments!!
            val pattern = (patterns as ElixirAst.ListNode).elements.single()
            val clause = before.indexOfFirst { it.meta === arrow.meta }

            "${clause + 1} => ${(pattern as? ElixirAst.Literal.Atom)?.name ?: "?"} -> ${body.meta.origin.substring(code)}"
        }
    }

    private fun clauses(options: ElixirAst): List<ElixirAst.Call> =
        ((((options as ElixirAst.ListNode).elements.single() as ElixirAst.Tuple).elements[1]) as ElixirAst.ListNode)
            .elements
            .map { it as ElixirAst.Call }

    private companion object {
        const val UNCHANGED = "unchanged"
        const val REWRITTEN = "1 => false -> 1; 2 => true -> 2"

        /** [node] with `x` and `y` as `if`'s output has its variable: of `Kernel`'s context, with only a counter. */
        fun kernelVariables(node: ElixirAst): ElixirAst =
            when (node) {
                is ElixirAst.Call ->
                    if (node.arguments == null && (node.callee as? ElixirAst.Literal.Atom)?.name in listOf("x", "y")) {
                        ElixirAst.Call(
                            Meta(
                                node.meta.origin,
                                node.meta.start,
                                node.meta.end,
                                listOf(
                                    Meta.Key.Entry(
                                        "counter",
                                        Meta.Value.Tuple(listOf(Meta.Value.Atom("Elixir.Case"), Meta.Value.Integer(1))),
                                    ),
                                ),
                                built = true,
                            ),
                            node.callee,
                            null,
                            ElixirAst.VariableContext.Atom("Elixir.Kernel"),
                        )
                    } else {
                        ElixirAst.Call(node.meta, kernelVariables(node.callee), node.arguments?.map(::kernelVariables), node.context)
                    }
                is ElixirAst.ListNode -> ElixirAst.ListNode(node.meta, node.elements.map(::kernelVariables))
                is ElixirAst.Tuple -> ElixirAst.Tuple(node.meta, node.elements.map(::kernelVariables))
                is ElixirAst.Block -> ElixirAst.Block(node.meta, node.expressions.map(::kernelVariables))
                else -> node
            }
    }
}
