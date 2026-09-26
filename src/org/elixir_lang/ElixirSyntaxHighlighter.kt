package org.elixir_lang

import org.elixir_lang.psi.CallableDeclaration
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.editor.colors.TextAttributesKey
import org.elixir_lang.psi.ElixirTypes
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import kotlin.collections.List

/**
 * Created by kadie.enheduanna.inanna on 8/2/14.
 */
class ElixirSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = ElixirLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
            when {
                tokenType == ElixirTypes.ALIAS -> {
                    ALIAS_KEYS
                }
                ATOM_KEYWORDS.contains(tokenType) -> {
                    ATOM_KEYWORD_KEYS
                }
                ATOMS.contains(tokenType) -> {
                    ATOM_KEYS
                }
                tokenType == TokenType.BAD_CHARACTER -> {
                    BAD_CHAR_KEYS
                }
                BIT_TOKEN_SET.contains(tokenType) -> {
                    BIT_KEYS
                }
                BRACES_TOKEN_SET.contains(tokenType) -> {
                    BRACES_KEYS
                }
                BRACKETS_TOKEN_SET.contains(tokenType) -> {
                    BRACKETS_KEYS
                }
                tokenType === ElixirTypes.CHAR_TOKENIZER -> {
                    CHAR_TOKEN_KEYS
                }
                tokenType == ElixirTypes.COMMA -> {
                    COMMA_KEYS
                }
                tokenType == ElixirTypes.COMMENT -> {
                    COMMENT_KEYS
                }
                DECIMAL_TOKEN_SET.contains(tokenType) -> {
                    DECIMAL_KEYS
                }
                tokenType == ElixirTypes.DOT_OPERATOR -> {
                    DOT_KEYS
                }
                EXPRESSION_SUBSTITUTION_MARKS.contains(tokenType) -> {
                    EXPRESSION_SUBSTITUTION_MARK_KEYS
                }
                tokenType == ElixirTypes.IDENTIFIER -> {
                    IDENTIFIER_KEYS
                }
                INVALID_DIGITS_TOKEN_SET.contains(tokenType) -> {
                    INVALID_DIGITS_KEYS
                }
                KEYWORD_TOKEN_SET.contains(tokenType) -> {
                    KEYWORD_KEYS
                }
                OBSOLETE_WHOLE_NUMBER_BASE_TOKEN_SET.contains(tokenType) -> {
                    OBSOLETE_WHOLE_NUMBER_BASE_KEYS
                }
                OPERATION_SIGNS.contains(tokenType) -> {
                    OPERATION_SIGN_KEYS
                }
                PARENTHESES_TOKEN_SET.contains(tokenType) -> {
                    PARENTHESES_KEYS
                }
                tokenType == ElixirTypes.SEMICOLON -> {
                    SEMICOLON_KEYS
                }
                SIGILS.contains(tokenType) -> {
                    SIGIL_KEYS
                }
                VALID_DIGITS_TOKEN_SET.contains(tokenType) -> {
                    VALID_DIGITS_KEYS
                }
                WHOLE_NUMBER_BASE_TOKEN_SET.contains(tokenType) -> {
                    WHOLE_NUMBER_BASE_KEYS
                }
                else -> EMPTY_KEYS
            }

    companion object {
        @JvmField
        val ALIAS = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_ALIAS",
                DefaultLanguageHighlighterColors.CLASS_NAME
        )
        @JvmField
        val ATOM = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_ATOM",
                DefaultLanguageHighlighterColors.INSTANCE_FIELD
        )
        @JvmField
        val BRACES = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_BRACES",
                DefaultLanguageHighlighterColors.BRACES
        )
        @JvmField
        val CHAR_LIST = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_CHAR_LIST",
                DefaultLanguageHighlighterColors.STRING
        )
        @JvmField
        val CHAR_TOKEN_TOKEN = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_CHAR_TOKEN",
                DefaultLanguageHighlighterColors.MARKUP_ENTITY
        )
        @JvmField
        val COMMENT = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_COMMENT",
                DefaultLanguageHighlighterColors.LINE_COMMENT
        )
        @JvmField
        val DECIMAL = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_DECIMAL",
                DefaultLanguageHighlighterColors.NUMBER
        )
        @JvmField
        val DOCUMENTATION_MODULE_ATTRIBUTE = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_DOCUMENTATION_MODULE_ATTRIBUTE",
                DefaultLanguageHighlighterColors.DOC_COMMENT_TAG
        )
        @JvmField
        val DOCUMENTATION_TEXT = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_DOCUMENTATION_TEXT",
                DefaultLanguageHighlighterColors.DOC_COMMENT_TAG_VALUE
        )
        @JvmField
        val FUNCTION_CALL = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_FUNCTION_CALL",
                DefaultLanguageHighlighterColors.FUNCTION_CALL
        )
        @JvmField
        val FUNCTION_DECLARATION = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_FUNCTION_DECLARATION",
                DefaultLanguageHighlighterColors.FUNCTION_DECLARATION
        )
        /** The key a declared name is highlighted with, by how its declaration is presented. */
        fun declarationKey(presentation: CallableDeclaration.Presentation): TextAttributesKey =
                when (presentation) {
                    CallableDeclaration.Presentation.FUNCTION -> FUNCTION_DECLARATION
                    CallableDeclaration.Presentation.MACRO -> MACRO_DECLARATION
                    CallableDeclaration.Presentation.GUARD -> GUARD_DECLARATION
                }

        @JvmField
        val GUARD_CALL = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_GUARD_CALL",
                FUNCTION_CALL
        )
        @JvmField
        val GUARD_DECLARATION = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_GUARD_DECLARATION",
                FUNCTION_DECLARATION
        )
        @JvmField
        val IDENTIFIER = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_IDENTIFIER",
                DefaultLanguageHighlighterColors.IDENTIFIER
        )
        @JvmField
        val INVALID_DIGIT = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_INVALID_DIGIT",
                HighlighterColors.BAD_CHARACTER
        )
        @JvmField
        val KEYWORD = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_KEYWORD",
                DefaultLanguageHighlighterColors.KEYWORD
        )
        @JvmField
        val MACRO_CALL = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_MACRO_CALL",
                FUNCTION_CALL
        )
        @JvmField
        val MACRO_DECLARATION = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_MACRO_DECLARATION",
                FUNCTION_DECLARATION
        )
        @JvmField
        val MAP = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_MAP",  // DO NOT link to {@link ElixirSyntaxHighlighter.BRACES} since that's for Tuples in standard Elixir
                DefaultLanguageHighlighterColors.BRACES
        )
        @JvmField
        val MODULE_ATTRIBUTE = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_MODULE_ATTRIBUTE",
                DefaultLanguageHighlighterColors.CONSTANT
        )
        @JvmField
        val OBSOLETE_WHOLE_NUMBER_BASE = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_OBSOLETE_WHOLE_NUMBER_BASE",
                HighlighterColors.BAD_CHARACTER
        )
        @JvmField
        val OPERATION_SIGN = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_OPERATION_SIGN",
                DefaultLanguageHighlighterColors.OPERATION_SIGN
        )
        @JvmField
        val PARAMETER = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_PARAMETER",
                DefaultLanguageHighlighterColors.PARAMETER
        )
        @JvmField
        val PREDEFINED_CALL = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_PREDEFINED",
                DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL
        )
        @JvmField
        val SEMICOLON = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_SEMICOLON",
                DefaultLanguageHighlighterColors.SEMICOLON
        )
        @JvmField
        val STRING = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_STRING",
                DefaultLanguageHighlighterColors.STRING
        )
        @JvmField
        val SIGIL = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_SIGIL",  // Based on color used for Regular expression's boundaries in Ruby
                DefaultLanguageHighlighterColors.TEMPLATE_LANGUAGE_COLOR
        )

        fun <T> sigilNames(transformer: (capitalization: String, letter: Char, name: Char) -> T): List<T> =
            ('A'..'Z').flatMap { letter ->
                arrayOf("LOWER", "UPPER").map { capitalization ->
                    val name: Char = when (capitalization) {
                        "LOWER" -> letter.lowercaseChar()
                        "UPPER" -> letter.uppercaseChar()
                        else -> '?'
                    }

                    transformer(capitalization, letter, name)
                }
            }

        @JvmField
        val SIGIL_NAMES: kotlin.collections.List<Char> = ('A'..'Z').flatMap { letter ->
            arrayOf("LOWER", "UPPER").map { capitalization ->
                val name: Char = when (capitalization) {
                    "LOWER" -> letter.lowercaseChar()
                    "UPPER" -> letter.uppercaseChar()
                    else -> '?'
                }
                name
            }
        }

        @JvmField
        val SIGIL_BY_NAME: Map<Char, TextAttributesKey> = ('A'..'Z').flatMap { letter ->
            arrayOf("LOWER", "UPPER").map { capitalization ->
                val name: Char = when (capitalization) {
                    "LOWER" -> letter.lowercaseChar()
                    "UPPER" -> letter.uppercaseChar()
                    else -> '?'
                }
                name to TextAttributesKey.createTextAttributesKey("ELIXIR_SIGIL_${capitalization}_${letter}", SIGIL)
            }
        }.toMap()

        @JvmField
        val SPECIFICATION = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_SPECIFICATION",
                DefaultLanguageHighlighterColors.FUNCTION_DECLARATION
        )
        @JvmField
        val STRUCT = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_STRUCT",
                MAP
        )
        @JvmField
        val CALLBACK = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_CALLBACK",
                SPECIFICATION
        )
        @JvmField
        val TYPE = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_TYPE",
                DefaultLanguageHighlighterColors.METADATA
        )
        @JvmField
        val TYPE_PARAMETER = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_TYPE_PARAMETER",
                DefaultLanguageHighlighterColors.PARAMETER
        )
        @JvmField
        val VALID_DIGIT = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_VALID_DIGIT",
                DefaultLanguageHighlighterColors.NUMBER
        )
        @JvmField
        val VALID_ESCAPE_SEQUENCE = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_VALID_ESCAPE_SEQUENCE",
                DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE
        )
        @JvmField
        val VARIABLE = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_VARIABLE",
                DefaultLanguageHighlighterColors.LOCAL_VARIABLE
        )
        @JvmField
        val IGNORED_VARIABLE = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_IGNORED_VARIABLE",
                VARIABLE
        )
        @JvmField
        val WHOLE_NUMBER_BASE = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_WHOLE_NUMBER_BASE",
                DefaultLanguageHighlighterColors.NUMBER
        )
        val ALIAS_KEYS = arrayOf(ALIAS)
        val ATOM_KEYS: Array<TextAttributesKey> = arrayOf(ATOM)
        val ATOM_KEYWORD_KEYS: Array<TextAttributesKey> = arrayOf(ATOM, KEYWORD)
        val BRACES_KEYS = arrayOf(BRACES)
        val CHAR_LIST_KEYS = arrayOf(CHAR_LIST)
        val DECIMAL_KEYS = arrayOf(DECIMAL)
        val EMPTY_KEYS = emptyArray<TextAttributesKey>()
        val KEYWORD_KEYS = arrayOf(KEYWORD)
        val OPERATION_SIGN_KEYS = arrayOf(OPERATION_SIGN)
        val STRING_KEYS = arrayOf(STRING)
        val BRACES_TOKEN_SET = TokenSet.create(
                ElixirTypes.OPENING_CURLY,
                ElixirTypes.CLOSING_CURLY
        )
        val BAD_CHARACTER = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_BAD_CHARACTER",
                HighlighterColors.BAD_CHARACTER
        )
        @JvmField
        val BIT = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_BIT"
        )
        val BIT_KEYS = arrayOf(BIT)
        @JvmField
        val BRACKETS = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_BRACKET",
                DefaultLanguageHighlighterColors.BRACKETS
        )
        val BRACKETS_KEYS = arrayOf(BRACKETS)
        @JvmField
        val COMMA = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_COMMA",
                DefaultLanguageHighlighterColors.COMMA
        )
        @JvmField
        val DOT = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_DOT",
                DefaultLanguageHighlighterColors.DOT
        )
        @JvmField
        val EXPRESSION_SUBSTITUTION_MARK = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_EXPRESSION_SUBSTITUTION_MARK",
                DefaultLanguageHighlighterColors.BRACES
        )
        @JvmField
        val PARENTHESES = TextAttributesKey.createTextAttributesKey(
                "ELIXIR_PARENTHESES",
                DefaultLanguageHighlighterColors.PARENTHESES
        )
        val PARENTHESES_KEYS = arrayOf(PARENTHESES)
        private val BAD_CHAR_KEYS = arrayOf(BAD_CHARACTER)
        private val CHAR_TOKEN_KEYS = arrayOf(CHAR_TOKEN_TOKEN)
        private val COMMA_KEYS = arrayOf(COMMA)
        private val COMMENT_KEYS = arrayOf(COMMENT)
        private val DOT_KEYS = arrayOf(DOT)
        private val EXPRESSION_SUBSTITUTION_MARK_KEYS = arrayOf(EXPRESSION_SUBSTITUTION_MARK)
        private val IDENTIFIER_KEYS = arrayOf(IDENTIFIER)
        private val INVALID_DIGITS_KEYS = arrayOf(INVALID_DIGIT)
        private val OBSOLETE_WHOLE_NUMBER_BASE_KEYS = arrayOf(OBSOLETE_WHOLE_NUMBER_BASE)
        private val SEMICOLON_KEYS = arrayOf(SEMICOLON)
        private val SIGIL_KEYS = arrayOf(SIGIL)
        private val VALID_DIGITS_KEYS = arrayOf(VALID_DIGIT)
        private val WHOLE_NUMBER_BASE_KEYS = arrayOf(WHOLE_NUMBER_BASE)
        private val ATOMS = TokenSet.create(
                ElixirTypes.COLON,
                ElixirTypes.ATOM_FRAGMENT
        )
        private val ATOM_KEYWORDS = TokenSet.create(
                ElixirTypes.FALSE,
                ElixirTypes.NIL,
                ElixirTypes.TRUE
        )
        private val BIT_TOKEN_SET = TokenSet.create(
                ElixirTypes.CLOSING_BIT,
                ElixirTypes.OPENING_BIT
        )
        private val BRACKETS_TOKEN_SET = TokenSet.create(
                ElixirTypes.OPENING_BRACKET,
                ElixirTypes.CLOSING_BRACKET
        )
        private val DECIMAL_TOKEN_SET = TokenSet.create(
                ElixirTypes.DECIMAL_MARK,
                ElixirTypes.NUMBER_SEPARATOR,
                ElixirTypes.EXPONENT_MARK
        )
        private val EXPRESSION_SUBSTITUTION_MARKS = TokenSet.create(
                ElixirTypes.INTERPOLATION_START,
                ElixirTypes.INTERPOLATION_END
        )
        private val INVALID_DIGITS_TOKEN_SET = TokenSet.create(
                ElixirTypes.INVALID_BINARY_DIGITS,
                ElixirTypes.INVALID_DECIMAL_DIGITS,
                ElixirTypes.INVALID_HEXADECIMAL_DIGITS,
                ElixirTypes.INVALID_OCTAL_DIGITS,
                ElixirTypes.INVALID_UNKNOWN_BASE_DIGITS
        )
        private val KEYWORD_TOKEN_SET = TokenSet.create(
                ElixirTypes.AFTER,
                ElixirTypes.CATCH,
                ElixirTypes.DO,
                ElixirTypes.ELSE,
                ElixirTypes.END,
                ElixirTypes.FN,
                ElixirTypes.RESCUE
        )
        private val OBSOLETE_WHOLE_NUMBER_BASE_TOKEN_SET = TokenSet.create(
                ElixirTypes.OBSOLETE_BINARY_WHOLE_NUMBER_BASE,
                ElixirTypes.OBSOLETE_HEXADECIMAL_WHOLE_NUMBER_BASE
        )
        private val OPERATION_SIGNS = TokenSet.create(
                ElixirTypes.AND_SYMBOL_OPERATOR,
                ElixirTypes.AND_WORD_OPERATOR,
                ElixirTypes.ARROW_OPERATOR,
                ElixirTypes.ASSOCIATION_OPERATOR,
                ElixirTypes.AT_OPERATOR,
                ElixirTypes.CAPTURE_OPERATOR,
                ElixirTypes.COMPARISON_OPERATOR,
                ElixirTypes.DIVISION_OPERATOR,
                ElixirTypes.IN_OPERATOR,
                ElixirTypes.IN_MATCH_OPERATOR,
                ElixirTypes.MATCH_OPERATOR,
                ElixirTypes.MINUS_OPERATOR,
                ElixirTypes.MULTIPLICATION_OPERATOR,
                ElixirTypes.NEGATE_OPERATOR,
                ElixirTypes.NUMBER_OR_BADARITH_OPERATOR,
                ElixirTypes.OR_SYMBOL_OPERATOR,
                ElixirTypes.OR_WORD_OPERATOR,
                ElixirTypes.PIPE_OPERATOR,
                ElixirTypes.PLUS_OPERATOR,
                ElixirTypes.POWER_OPERATOR,
                ElixirTypes.RANGE_OPERATOR,
                ElixirTypes.RELATIONAL_OPERATOR,
                ElixirTypes.STAB_OPERATOR,
                ElixirTypes.TWO_OPERATOR,
                ElixirTypes.TYPE_OPERATOR,
                ElixirTypes.UNARY_OPERATOR,
                ElixirTypes.WHEN_OPERATOR
        )
        private val PARENTHESES_TOKEN_SET = TokenSet.create(
                ElixirTypes.CLOSING_PARENTHESIS,
                ElixirTypes.OPENING_PARENTHESIS
        )

        // @todo Highlight each type of sigil separately and group char list and string with non-sigil versions
        private val SIGILS = TokenSet.create(
                ElixirTypes.INTERPOLATING_SIGIL_NAME,
                ElixirTypes.LITERAL_SIGIL_NAME,
                ElixirTypes.SIGIL_MODIFIER,
                ElixirTypes.TILDE
        )
        private val VALID_DIGITS_TOKEN_SET = TokenSet.create(
                ElixirTypes.VALID_BINARY_DIGITS,
                ElixirTypes.VALID_DECIMAL_DIGITS,
                ElixirTypes.VALID_HEXADECIMAL_DIGITS,
                ElixirTypes.VALID_OCTAL_DIGITS
        )
        private val WHOLE_NUMBER_BASE_TOKEN_SET = TokenSet.create(
                ElixirTypes.BASE_WHOLE_NUMBER_PREFIX,
                ElixirTypes.BINARY_WHOLE_NUMBER_BASE,
                ElixirTypes.HEXADECIMAL_WHOLE_NUMBER_BASE,
                ElixirTypes.OCTAL_WHOLE_NUMBER_BASE
        )
    }
}
