package org.elixir_lang.expander.interpreter

import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.TokenType.WHITE_SPACE
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import org.elixir_lang.ElixirLexer
import org.elixir_lang.language_level.ElixirLanguageFeature.TOKENIZER_ANSWERS_ITS_END
import org.elixir_lang.language_level.ElixirLanguageFeature.TOKENIZER_ANSWERS_TOKENS_REVERSED
import org.elixir_lang.language_level.ElixirLanguageFeature.TOKENIZER_KEEPS_IDENTIFIER_TEXT
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.ElixirTypes
import java.math.BigInteger

/**
 * `elixir_tokenizer:tokenize/3` of one line of printable ASCII, as `EEx` calls it (`check_terminators: false`):
 * `ElixirLexer`'s tokens, each named as `elixir_tokenizer` names it, at its line and column, in [level]'s shape. The map
 * names identifiers, aliases, keyword keys, `do`, `end`, `fn`, the block keywords, `true`, `false`, `nil`, `@`, `->`,
 * `<-`, `.`, `,`, brackets, unquoted atoms, decimal integers and strings with no escape or interpolation, each where what
 * follows it can't change what `elixir_tokenizer` makes of it. Any other text declines.
 */
internal class Tokenizer(private val level: ElixirLanguageLevel) {
    private class Lexed(val type: IElementType, val start: Int, val text: String)

    /** [text]'s tokens from [line], or a decline where the map can't name one exactly. */
    fun tokenize(text: String, line: Int): OtpErlangObject {
        if (text.length > COPY_NODES) decline("elixir_tokenizer:tokenize/3 of text past $COPY_NODES characters")
        if (text.any { it != '\t' && it !in ' '..'~' }) {
            decline("elixir_tokenizer:tokenize/3 of %s, past one line of printable ASCII", charlist(text))
        }

        val lexed = lexed(text)
        val tokens = ArrayList<OtpErlangTuple>()
        var index = 0

        fun meta(at: Lexed, third: OtpErlangObject = NIL) = tuple(integer(line.toLong()), integer(at.start + 1L), third)

        // A [kind] token at [at], named by [name]'s text.
        fun nameToken(kind: String, at: Lexed, name: Lexed = at) = tuple(atom(kind), meta(at, written(name.text)), atom(named(name)))

        // The kind of the last token made.
        fun lastKind() = tokens.lastOrNull()?.elementAt(0)

        fun refuse(at: Lexed): Nothing = decline("elixir_tokenizer:tokenize/3 of %s, at its column ${at.start + 1}", charlist(text))

        // The token after the one at [index], whitespace included, `null` at the end.
        fun next() = lexed.getOrNull(index + 1)

        // What follows the token at [index]: one of [followers], or the end.
        fun followed(followers: TokenSet) {
            next()?.let { if (it.type !in followers) refuse(lexed[index]) }
        }

        while (index < lexed.size) {
            ProgressManager.checkCanceled()

            val token = lexed[index]
            // `elixir_tokenizer` takes a keyword after a dot as a name.
            if (lastKind() == DOT && token.type !in AFTER_DOT) refuse(token)

            if (token.type in WORDS && next()?.type == ElixirTypes.KEYWORD_PAIR_COLON) {
                tokens += nameToken("kw_identifier", token)
                index += 2
                continue
            }

            when (token.type) {
                WHITE_SPACE -> {}
                ElixirTypes.IDENTIFIER_TOKEN -> {
                    // Only as a key may these be names.
                    if (token.text in RESERVED) refuse(token)

                    val kind = if (next()?.type == ElixirTypes.CALL) {
                        index++
                        when (next()?.type) {
                            ElixirTypes.OPENING_PARENTHESIS -> "paren_identifier"
                            ElixirTypes.OPENING_BRACKET -> "bracket_identifier"
                            else -> refuse(token)
                        }
                    } else {
                        followed(AFTER_NAME)
                        "identifier"
                    }

                    tokens += nameToken(kind, token)
                }
                ElixirTypes.ALIAS_TOKEN -> {
                    followed(AFTER_NAME)
                    tokens += nameToken("alias", token)
                }
                ElixirTypes.DO -> {
                    followed(AFTER_LITERAL)

                    // `elixir_tokenizer` makes the identifier before a `do` its call's name, and fails a `do` after `fn` or
                    // a comma.
                    when (lastKind()) {
                        in NOT_BEFORE_DO -> refuse(token)
                        IDENTIFIER_KIND -> tokens[tokens.lastIndex] = tokens.last().let { tuple(atom("do_identifier"), it.elementAt(1), it.elementAt(2)) }
                    }
                    tokens += tuple(atom("do"), meta(token))
                }
                in KEYWORDS -> {
                    followed(AFTER_LITERAL)
                    tokens += tuple(atom(token.text), meta(token))
                }
                in BLOCK_KEYWORDS -> {
                    followed(AFTER_LITERAL)
                    tokens += tuple(atom("block_identifier"), meta(token), atom(token.text))
                }
                in OPERATORS -> {
                    val kind = OPERATOR_KINDS[token.text] ?: refuse(token)

                    tokens += tuple(atom(kind), meta(token), atom(token.text))
                }
                ElixirTypes.DOT_OPERATOR -> tokens += tuple(DOT, meta(token))
                ElixirTypes.COMMA -> tokens += tuple(atom(","), meta(token, integer(0)))
                in BRACKETS -> {
                    // A parenthesis after an alias fails, whitespace between them or not.
                    if (token.type == ElixirTypes.OPENING_PARENTHESIS && lastKind() == ALIAS_KIND) refuse(token)
                    tokens += tuple(atom(token.text), meta(token))
                }
                ElixirTypes.COLON -> {
                    val name = next()?.takeIf { it.type == ElixirTypes.ATOM_FRAGMENT } ?: refuse(token)

                    index++
                    followed(AFTER_LITERAL)
                    tokens += nameToken("atom", token, name)
                }
                ElixirTypes.VALID_DECIMAL_DIGITS -> {
                    followed(AFTER_LITERAL)
                    tokens += tuple(atom("int"), meta(token, integer(BigInteger(token.text))), charlist(token.text))
                }
                ElixirTypes.LINE_PROMOTER -> {
                    if (token.text != "\"") refuse(token)

                    val fragment = next()?.takeIf { it.type == ElixirTypes.FRAGMENT }

                    if (fragment != null) index++
                    if (next()?.type != ElixirTypes.LINE_TERMINATOR) refuse(token)
                    index++
                    followed(AFTER_LITERAL)
                    tokens += tuple(atom("bin_string"), meta(token), list(listOf(binary(fragment?.text ?: ""))))
                }
                else -> refuse(token)
            }

            index++
        }

        if (lastKind() == DOT) refuse(lexed.last())

        val end = integer(text.length + 1L)

        return when {
            TOKENIZER_ANSWERS_TOKENS_REVERSED.isSufficient(level) ->
                tuple(OK, integer(line.toLong()), end, EMPTY, list(tokens.asReversed()), EMPTY)
            TOKENIZER_ANSWERS_ITS_END.isSufficient(level) -> tuple(OK, integer(line.toLong()), end, EMPTY, list(tokens))
            else -> tuple(OK, list(tokens))
        }
    }

    /**
     * [token]'s text as its atom's name, the lexer's name for it, but where `elixir_tokenizer` reads it otherwise: `...`
     * is an `ellipsis_op`, an alias ending in `?` or `!` fails, and an operator's atom holds no text in its metadata.
     */
    private fun named(token: Lexed): String {
        val refused = when (token.type) {
            ElixirTypes.IDENTIFIER_TOKEN -> token.text == "..."
            ElixirTypes.ALIAS_TOKEN -> token.text.last() in "?!"
            ElixirTypes.ATOM_FRAGMENT -> token.text.first().let { it != '_' && !it.isLetter() }
            else -> false
        }

        if (refused) decline("elixir_tokenizer:tokenize/3 of the name `${token.text}`")

        return token.text
    }

    /** The third of an identifier's, alias's, key's or atom's metadata: [text] as written, from 1.14, else `nil`. */
    private fun written(text: String): OtpErlangObject = if (TOKENIZER_KEEPS_IDENTIFIER_TEXT.isSufficient(level)) charlist(text) else NIL

    private fun lexed(text: String): List<Lexed> {
        val lexer = ElixirLexer()
        val lexed = ArrayList<Lexed>()

        lexer.start(text)
        while (true) {
            ProgressManager.checkCanceled()

            val type = lexer.tokenType ?: return lexed

            lexed += Lexed(type, lexer.tokenStart, lexer.tokenText)
            lexer.advance()
        }
    }

    private companion object {
        val OK = atom("ok")
        val DOT = atom(".")
        val IDENTIFIER_KIND = atom("identifier")
        val ALIAS_KIND = atom("alias")
        val NOT_BEFORE_DO = setOf(atom("fn"), atom(","))
        val RESERVED = setOf("__aliases__", "__block__")

        val KEYWORDS = TokenSet.create(ElixirTypes.END, ElixirTypes.FN, ElixirTypes.TRUE, ElixirTypes.FALSE, ElixirTypes.NIL)
        val BLOCK_KEYWORDS = TokenSet.create(ElixirTypes.ELSE, ElixirTypes.AFTER, ElixirTypes.CATCH, ElixirTypes.RESCUE)
        val WORDS = TokenSet.orSet(
            TokenSet.create(ElixirTypes.ATOM_FRAGMENT, ElixirTypes.IDENTIFIER_TOKEN, ElixirTypes.ALIAS_TOKEN, ElixirTypes.DO),
            KEYWORDS,
            BLOCK_KEYWORDS,
        )
        val AFTER_DOT = TokenSet.create(WHITE_SPACE, ElixirTypes.IDENTIFIER_TOKEN, ElixirTypes.ALIAS_TOKEN)
        val OPERATORS = TokenSet.create(ElixirTypes.AT_OPERATOR, ElixirTypes.STAB_OPERATOR, ElixirTypes.IN_MATCH_OPERATOR)
        /** `\\` is an `in_match_op` too, unported. */
        val OPERATOR_KINDS = mapOf("@" to "at_op", "->" to "stab_op", "<-" to "in_match_op")
        val CLOSING = TokenSet.create(ElixirTypes.CLOSING_PARENTHESIS, ElixirTypes.CLOSING_BRACKET, ElixirTypes.CLOSING_CURLY)
        val BRACKETS = TokenSet.orSet(
            CLOSING,
            TokenSet.create(ElixirTypes.OPENING_PARENTHESIS, ElixirTypes.OPENING_BRACKET, ElixirTypes.OPENING_CURLY),
        )

        /** What may follow a literal, keyword, atom or string with nothing between them. */
        val AFTER_LITERAL = TokenSet.orSet(CLOSING, TokenSet.create(WHITE_SPACE, ElixirTypes.COMMA))

        /** What may follow an identifier or alias with nothing between them, past a call's bracket or a key's colon. */
        val AFTER_NAME = TokenSet.orSet(AFTER_LITERAL, TokenSet.create(ElixirTypes.DOT_OPERATOR))
    }
}
