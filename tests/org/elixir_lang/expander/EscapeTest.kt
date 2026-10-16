package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.COMPILER_PARSES_COLUMNS
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.ParserOptions

/**
 * `Macro.escape(ast, unquote: true)` of a snippet, parsed as the compiler parses it on each leg. Each expected term is
 * what that leg's Elixir writes with `~w`.
 */
class EscapeTest : ExpanderTestCase() {
    fun testAnEscapedQuoteKeepsItsColumnOnlyBefore116AndFrom119() =
        assertEscaped(
            "quote(do: 1)",
            "{'{}',[],[quote,[{line,1}],[[{do,1}]]]}",
            from119 = "{'{}',[],[quote,[{line,1},{column,1}],[[{do,1}]]]}",
        )

    fun testAnEscapedUnquotedCallKeepsItsMetadataArgumentsColumnOnlyBefore116AndFrom119() =
        assertEscaped(
            "Mod.unquote(f)()",
            "{{'.',[{line,1}],[elixir_quote,dot]},[{line,1}],[[{line,1}],{'{}',[],['__aliases__',[{line,1}],['Mod']]},{f,[{line,1}],nil},[],nil]}",
            from116 = "{{'.',[{line,1},{column,4}],[elixir_quote,dot]},[{line,1},{column,4}],[[{line,1}],{'{}',[],['__aliases__',[{line,1},{column,1}],['Mod']]},{f,[{line,1},{column,13}],nil},[],nil]}",
            from119 = "{{'.',[{line,1},{column,4}],[elixir_quote,dot]},[{line,1},{column,4}],[[{line,1},{column,4}],{'{}',[],['__aliases__',[{line,1},{column,1}],['Mod']]},{f,[{line,1},{column,13}],nil},[],nil]}",
        )

    fun testAnEscapedCallKeepsItsColumn() =
        assertEscaped(
            "foo(1)",
            "{'{}',[],[foo,[{line,1}],[1]]}",
            from116 = "{'{}',[],[foo,[{line,1},{column,1}],[1]]}",
        )

    fun testAnEscapedAnonymousCallAndVariableKeepTheirColumns() =
        assertEscaped(
            "f.(1)",
            "{'{}',[],[{'{}',[],['.',[{line,1}],[{'{}',[],[f,[{line,1}],nil]}]]},[{line,1}],[1]]}",
            from116 = "{'{}',[],[{'{}',[],['.',[{line,1},{column,2}],[{'{}',[],[f,[{line,1},{column,1}],nil]}]]},[{line,1},{column,2}],[1]]}",
        )

    /** The tuple an escaped `quote` builds is placed at the `quote`, so what evaluates it knows which call it quotes. */
    fun testAnEscapedQuoteIsPlacedAtTheQuote() {
        val level = ElixirLanguageLevel.of("1.20.4")
        val node = lower("[quote(do: 1)]", level)
        val run = Run(level, ExpansionObserver.NONE, exports, structs)
        val escape = Quote.escape(node, node.meta, Env.empty(level, kernel), run) as Quote.Escaped.Built
        val tuple = (escape.node as ElixirAst.ListNode).elements.single()

        assertEquals((node as ElixirAst.ListNode).elements.single().meta.origin, tuple.meta.origin)
    }

    /** [code]'s escape on each leg: [before116], then [from116] from 1.16 and [from119] from 1.19, each defaulting to the one before. */
    private fun assertEscaped(code: String, before116: String, from116: String = before116, from119: String = from116) {
        val legs = listOf("1.13.4", "1.15.8", "1.16.3", "1.18.4", "1.19.5", "1.20.4")

        assertEquals(
            legs.joinToString("\n") { leg ->
                "$leg: " + when {
                    isBefore(leg, "1.16.0-rc.0") -> before116
                    isBefore(leg, "1.19.0") -> from116
                    else -> from119
                }
            },
            legs.joinToString("\n") { "$it: ${escaped(code, ElixirLanguageLevel.of(it))}" },
        )
    }

    private fun escaped(code: String, level: ElixirLanguageLevel): String {
        val node = lower(code, level)
        val run = Run(level, ExpansionObserver.NONE, exports, structs)

        return when (val escape = Quote.escape(node, node.meta, Env.empty(level, kernel), run)) {
            is Quote.Escaped.Built -> written(escape.node.toOtp(ParserOptions(columns = COMPILER_PARSES_COLUMNS.isSufficient(level))))
            is Quote.Escaped.Stopped -> "stopped ${escape.expansion}"
        }
    }
}
