package org.elixir_lang.beam.decompiler

import com.intellij.psi.TokenType
import com.intellij.psi.tree.TokenSet
import org.elixir_lang.ElixirLexer
import org.elixir_lang.psi.ElixirTypes

/**
 * The one rule for a parameter's text: how a definition's head is spelled, and which of its parameters a lower arity
 * keeps. Source, delegation and `.beam` insertion, Parameter Info and the decompiler's naming all read it.
 */
object ParameterText {
    private val PROMOTERS = TokenSet.create(ElixirTypes.LINE_PROMOTER, ElixirTypes.HEREDOC_PROMOTER)
    private val TERMINATORS = TokenSet.create(
        ElixirTypes.LINE_TERMINATOR,
        ElixirTypes.HEREDOC_TERMINATOR,
        ElixirTypes.ESCAPED_LINE_TERMINATOR,
        ElixirTypes.ESCAPED_HEREDOC_TERMINATOR
    )

    /** Whether [text] lexes as one identifier or one alias, as an Erlang variable does. */
    fun isName(text: String): Boolean {
        val lexer = ElixirLexer()
        lexer.start(text)
        if (lexer.tokenType != ElixirTypes.IDENTIFIER_TOKEN && lexer.tokenType != ElixirTypes.ALIAS_TOKEN) return false
        lexer.advance()

        return lexer.tokenType == null
    }

    /**
     * [text] as Elixir writes a head's parameter in a `Docs` signature: on one line with no comments. A comment is
     * dropped, one space is written where whitespace, a newline or a comment separates two tokens, and every other
     * token is copied unchanged, as is everything between a string, charlist, sigil or heredoc's promoter and its
     * terminator.
     */
    fun normalised(text: String): String {
        val lexer = ElixirLexer()
        lexer.start(text)

        val normalised = StringBuilder()
        var separated = false
        var literalDepth = 0
        var escaped = false

        while (lexer.tokenType != null) {
            val type = lexer.tokenType

            when {
                literalDepth > 0 -> {
                    if (type in TERMINATORS && !escaped) literalDepth--
                    if (type in PROMOTERS) literalDepth++

                    normalised.append(lexer.tokenText)
                }
                type == ElixirTypes.COMMENT || type == ElixirTypes.EOL || type == TokenType.WHITE_SPACE ->
                    separated = true
                else -> {
                    if (separated && normalised.isNotEmpty()) normalised.append(' ')

                    separated = false
                    if (type in PROMOTERS) literalDepth++
                    normalised.append(lexer.tokenText)
                }
            }

            escaped = type == ElixirTypes.ESCAPE
            lexer.advance()
        }

        return normalised.toString()
    }

    /** [parameter] before its top-level `\\`, or `null` when it has no default. */
    fun withoutDefault(parameter: String): String? = defaultOperator(parameter)?.let { parameter.substring(0, it.first).trim() }

    /** [parameter]'s default, after its top-level `\\`, or `null` when it has none. */
    fun default(parameter: String): String? = defaultOperator(parameter)?.let { parameter.substring(it.last + 1).trim() }

    /** Where [parameter]'s top-level `\\` is written, or `null` when it has none. */
    private fun defaultOperator(parameter: String): IntRange? {
        val lexer = ElixirLexer()
        lexer.start(parameter)

        var depth = 0

        while (lexer.tokenType != null) {
            when (lexer.tokenType) {
                in OPENERS -> depth++
                in CLOSERS -> depth--
                ElixirTypes.IN_MATCH_OPERATOR ->
                    if (depth == 0 && lexer.tokenText == "\\\\") {
                        return lexer.tokenStart until lexer.tokenEnd
                    }
            }

            lexer.advance()
        }

        return null
    }

    /**
     * The parameters [parameters] leave at [arity], without their defaults: Elixir fills the last defaults first, so
     * those are dropped. `null` when [arity] is out of reach, which is more than [parameters] has, or fewer than its
     * non-defaulted parameters.
     */
    fun covered(parameters: List<String>, arity: Int): List<String>? {
        val dropCount = parameters.size - arity

        return if (dropCount in 0..defaulted(parameters).size) dropping(parameters, dropCount) else null
    }

    /** The arities a head of [parameters] answers to: all of them, down to the ones its defaults leave. */
    fun arities(parameters: List<String>): IntRange = (parameters.size - defaults(parameters))..parameters.size

    /** How many of [parameters] write a default. */
    fun defaults(parameters: List<String>): Int = defaulted(parameters).size

    /** [covered], or for an [arity] out of reach, as many of the last defaults dropped as there are to drop. */
    fun reaching(parameters: List<String>, arity: Int): List<String> = dropping(parameters, reachingDropCount(parameters, arity))

    /** The positions in [parameters] that [reaching] keeps: which parameter each argument of a call at [arity] binds. */
    fun positionsReaching(parameters: List<String>, arity: Int): List<Int> =
        keeping(parameters, reachingDropCount(parameters, arity))

    private fun reachingDropCount(parameters: List<String>, arity: Int): Int =
        (parameters.size - arity).coerceIn(0, defaulted(parameters).size)

    private fun defaulted(parameters: List<String>): List<Int> = parameters.indices.filter { withoutDefault(parameters[it]) != null }

    private fun dropping(parameters: List<String>, count: Int): List<String> =
        keeping(parameters, count).map { withoutDefault(parameters[it]) ?: parameters[it] }

    private fun keeping(parameters: List<String>, dropCount: Int): List<Int> {
        val dropped = defaulted(parameters).takeLast(dropCount).toSet()

        return parameters.indices.filterNot { it in dropped }
    }
}
