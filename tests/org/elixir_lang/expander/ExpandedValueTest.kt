package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term

/**
 * The value each ported clause gives its expansion, as the term Elixir's expansion of the node is: the value of the
 * snippet's last statement, at every supported minor and at the first tag of each version difference.
 */
class ExpandedValueTest : ExpanderTestCase() {
    override val exports: Exports = DirectiveFixtures.EXPORTS
    override val kernel = KernelImports(listOf(NameArity("+", 1), NameArity("-", 1)), emptyList())

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

    fun testAQuotedLiteralIsItselfBefore1_20() {
        assertSplit("quote do: :a", "1.20.0", ":a", "node")
        assertSplit("quote do: [1]", "1.20.0", "[1]", "node")
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

    fun testTheModuleIsItsName() = assertValue("__MODULE__", ":nil")

    fun testTheDirectoryIsABinary() = assertValue("__DIR__", "binary")

    fun testTheEnvIsAMap() = assertValue("__ENV__", "node")

    fun testAFieldOfTheEnvIsItsValue() {
        assertValue("__ENV__.line", "1")
        assertValue("\n\n__ENV__.line", "3")
        assertValue("__ENV__.module", ":nil")
        assertValue("__ENV__.function", ":nil")
        assertValue("__ENV__.context", ":nil")
        assertValue("__ENV__.aliases", "[]")
        assertValue("alias M.A\n__ENV__.aliases", "[{:Elixir.A, :Elixir.M.A}]")
        assertValue("__ENV__.functions", "[{:Elixir.Kernel, [{:+, 1}, {:-, 1}]}]")
        assertValue("__ENV__.macro_aliases", "[]")
        assertValue("__ENV__.context_modules", "[]")
        assertValue("__ENV__.file", "binary")
        assertValue("__ENV__.__struct__", ":Elixir.Macro.Env")
        assertSplit(
            "__ENV__.requires",
            "1.17.0-rc.0",
            "[:Elixir.Application, :Elixir.Kernel, :Elixir.Kernel.Typespec]",
            "[:Elixir.Application, :Elixir.Kernel]",
        )
    }

    fun testAFieldTheEnvLacksIsARunTimeCall() = assertValue("__ENV__.nope", "node")

    fun testTheVersionedVariablesAreAMap() = assertValue("__ENV__.versioned_vars", "node")

    fun testARemoteCallIsANode() = assertValue("M.f(1)", "node")

    fun testAnAnonymousCallIsANode() = assertValue("f = fn -> 1 end\nf.()", "node")

    fun testASignedNumberIsANumberFrom1_16() {
        assertSplit("-1", "1.16.0-rc.0", "node", "-1")
        assertSplit("+1.5", "1.16.0-rc.0", "node", "non-tuple")
        assertSplit("-(-1)", "1.16.0-rc.0", "node", "1")
    }

    fun testToStringOfABinaryIsTheBinary() {
        assertValue("String.Chars.to_string(\"a\")", "\"a\"")
        assertValue("String.Chars.to_string(:a)", "node")
    }

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
        val env = Env.empty(level, kernel).copy(module = module)
        val expansion = Expander.expand(root, ExState.empty(level), env, level, exports, structs, observer)

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
