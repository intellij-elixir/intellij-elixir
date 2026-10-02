package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term

/**
 * The value each ported clause gives its expansion, as the term Elixir's expansion of the node is: the value of the
 * snippet's last statement, at every supported minor and at the first tag of each version difference.
 */
class ExpandedValueTest : ExpanderTestCase() {
    override val exports: Exports = DirectiveFixtures.EXPORTS

    fun testLiterals() {
        assertValue("1", "1")
        assertValue(":a", ":a")
        assertValue("nil", ":nil")
        assertValue("\"s\"", "\"s\"")
    }

    fun testAFloatIsNotATuple() = assertValue("1.5", "non-tuple")

    fun testAZeroFloatInAPatternIsNotATuple() = assertValue("[0.0] = [0.0]; 0.0", "non-tuple")

    fun testAPairIsATwoTuple() = assertValue("{1, :a}", "{1, :a}")

    fun testATupleOfAnotherSizeIsANode() = assertValue("{1, 2, 3}", "node")

    fun testAList() = assertValue("[1, :a]", "[1, :a]")

    fun testAnEmptyList() = assertValue("[]", "[]")

    fun testAListWithATail() = assertValue("t = [2]; [1 | t]", "[1 | node(variable)]")

    fun testAListPatternWithATail() = assertValue("[h | t] = [1, 2]", "node")

    fun testAVariable() = assertValue("x = 1; x", "node(variable)")

    fun testAMatch() = assertValue("x = 1", "node")

    fun testAMapAndABitstringAreNodes() {
        assertValue("%{a: 1}", "node")
        assertValue("<<1>>", "node")
    }

    fun testABlockOfSeveralExpressionsIsANode() = assertValue("[(1; 2)]", "[node]")

    fun testABlockOfOneExpressionIsThatExpression() = assertValue("[(:a)]", "[:a]")

    fun testAnEmptyBlockIsNil() = assertValue("[()]", "[:nil]")

    fun testAnAlias() = assertValue("Foo.Bar", ":Elixir.Foo.Bar")

    fun testConstructsAreNodes() {
        assertValue("fn -> 1 end", "node")
        assertValue("case 1 do _ -> :a end", "node")
        assertValue("cond do true -> :a end", "node")
        assertValue("try do :a after :b end", "node")
        assertValue("receive do _ -> :a end", "node")
    }

    fun testAQuotedLiteralIsItself() {
        assertValue("quote do: :a", ":a")
        assertValue("quote do: [1]", "[1]")
    }

    fun testAQuotedVariableIsANode() = assertValue("quote do: x", "node")

    fun testAnAliasThatDefinesANameIsARunTimeWarningFrom1_18() =
        assertSplit("alias M.A", "1.18.0-rc.0", ":Elixir.M.A", "node")

    fun testAnAliasThatDefinesNoNameIsTheModule() = assertValue("alias M", ":Elixir.M")

    fun testAnAliasThatDoesNotWarnIsTheModule() = assertValue("alias M.A, warn: false", ":Elixir.M.A")

    fun testAnAliasOfAModuleToItsOwnNameIsTheModuleEvenWhenItWarns() = assertValue("alias M, warn: true", ":Elixir.M")

    fun testAnImportThatImportsIsARunTimeWarningFrom1_18() =
        assertSplit("import M, only: [f: 1]", "1.18.0-rc.0", ":Elixir.M", "node")

    fun testAnImportThatImportsNothingIsTheModule() = assertValue("import M.A, only: :macros", ":Elixir.M.A")

    fun testAnImportThatDoesNotWarnIsTheModule() = assertValue("import M, only: [f: 1], warn: false", ":Elixir.M")

    fun testARequireIsARunTimeWarningFrom1_20() = assertSplit("require M", "1.20.0-rc.0", ":Elixir.M", "node")

    fun testARequireThatDoesNotWarnIsTheModule() = assertValue("require M, warn: false", ":Elixir.M")

    fun testAMultiAliasIsAListOfItsAliases() =
        assertSplit("alias M.{A, B}", "1.18.0-rc.0", "[:Elixir.M.A, :Elixir.M.B]", "[node, node]")

    private fun assertValue(code: String, expected: String) = assertEvery(code, expected)

    /** The value of [code]'s last statement, or how the expansion ended before it. */
    override fun expandAndRender(code: String, version: String): String {
        val level = ElixirLanguageLevel.of(version)
        val root = lower(code, level)
        val last = (root as? ElixirAst.Block)?.expressions?.lastOrNull() ?: root
        var lastExpansion: Expansion? = null
        val observer = object : ExpansionObserver {
            override fun entering(node: ElixirAst, state: ExState, env: Env) {}

            override fun left(node: ElixirAst, expansion: Expansion) {
                if (node === last) lastExpansion = expansion
            }
        }
        val expansion = Expander.expand(root, ExState.empty(level), Env.empty(level, kernel), level, exports, observer)

        return when (val reached = lastExpansion ?: expansion) {
            is Expansion.Expanded -> valueOf(reached.value)
            else -> super.render(code, reached)
        }
    }

    private fun valueOf(term: Term): String =
        when (term) {
            is Term.Atom -> ":${term.name}"
            is Term.Integer -> term.value.toString()
            is Term.Binary -> term.bytes?.let { "\"${String(it)}\"" } ?: "binary"
            is Term.List ->
                term.elements.joinToString(", ", "[", "") { valueOf(it) } +
                    (term.tail?.let { " | ${valueOf(it)}" } ?: "") + "]"
            is Term.Pair -> "{${valueOf(term.first)}, ${valueOf(term.second)}}"
            is Term.Node ->
                when (term.kind) {
                    Term.Node.Kind.VARIABLE -> "node(variable)"
                    Term.Node.Kind.PIN -> "node(pin)"
                    Term.Node.Kind.OTHER -> "node"
                }
            Term.NonTuple -> "non-tuple"
            Term.Unexpanded -> "unexpanded"
        }
}
