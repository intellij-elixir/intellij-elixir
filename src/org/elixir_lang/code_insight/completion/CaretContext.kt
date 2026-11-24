package org.elixir_lang.code_insight.completion

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.util.text.CharArrayUtil
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.lowering.identifierAtomName
import org.elixir_lang.parser.isDecimalDigit
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.utils.ElixirModulesUtil

/**
 * What the code before the caret is being written as, read from the file's tokens on the caret's line, with no
 * expansion, resolve or index read. The values are `Code.Fragment.cursor_context/2`'s shapes, one for one.
 */
sealed interface CaretContext {
    /** `inside_dot`: what a dot follows. */
    sealed interface Receiver

    /** `inside_alias`: the expression an alias continues. */
    sealed interface AliasInside

    /** `inside_struct`. */
    sealed interface StructInside

    /** `inside_caller`: what an anonymous call calls. */
    sealed interface Caller

    data class Alias(val name: String) : CaretContext, Receiver {
        override fun toString() = "{:alias, ${charlist(name)}}"
    }

    data class InsideAlias(val inside: AliasInside, val name: String) : CaretContext, Receiver, StructInside {
        override fun toString() = "{:alias, $inside, ${charlist(name)}}"
    }

    data class BlockKeywordOrBinaryOperator(val hint: String) : CaretContext {
        override fun toString() = "{:block_keyword_or_binary_operator, ${charlist(hint)}}"
    }

    data class Dot(val receiver: Receiver, val hint: String) : CaretContext, Receiver, AliasInside, StructInside {
        override fun toString() = "{:dot, $receiver, ${charlist(hint)}}"
    }

    data class DotArity(val receiver: Receiver, val hint: String) : CaretContext {
        override fun toString() = "{:dot_arity, $receiver, ${charlist(hint)}}"
    }

    data class DotCall(val receiver: Receiver, val hint: String) : CaretContext {
        override fun toString() = "{:dot_call, $receiver, ${charlist(hint)}}"
    }

    data object Expr : CaretContext, Receiver {
        override fun toString() = ":expr"
    }

    data class LocalOrVar(val hint: String) : CaretContext, Receiver, AliasInside, StructInside {
        override fun toString() = "{:local_or_var, ${charlist(hint)}}"
    }

    data class Var(val name: String) : Receiver, Caller {
        override fun toString() = "{:var, ${charlist(name)}}"
    }

    data class LocalArity(val hint: String) : CaretContext {
        override fun toString() = "{:local_arity, ${charlist(hint)}}"
    }

    data class LocalCall(val hint: String) : CaretContext {
        override fun toString() = "{:local_call, ${charlist(hint)}}"
    }

    data class AnonymousCall(val caller: Caller) : CaretContext {
        override fun toString() = "{:anonymous_call, $caller}"
    }

    data class CaptureArg(val hint: String) : CaretContext {
        override fun toString() = "{:capture_arg, ${charlist(hint)}}"
    }

    data class ModuleAttribute(val hint: String) : CaretContext, Receiver, AliasInside, StructInside, Caller {
        override fun toString() = "{:module_attribute, ${charlist(hint)}}"
    }

    data class Operator(val operator: String) : CaretContext {
        override fun toString() = "{:operator, ${charlist(operator)}}"
    }

    data class OperatorArity(val operator: String) : CaretContext {
        override fun toString() = "{:operator_arity, ${charlist(operator)}}"
    }

    data class OperatorCall(val operator: String) : CaretContext {
        override fun toString() = "{:operator_call, ${charlist(operator)}}"
    }

    data object None : CaretContext {
        override fun toString() = ":none"
    }

    data class Sigil(val hint: String) : CaretContext {
        override fun toString() = "{:sigil, ${charlist(hint)}}"
    }

    data class Struct(val inside: StructInside) : CaretContext {
        override fun toString() = "{:struct, $inside}"
    }

    /** A struct's static alias, which Elixir gives as a bare charlist. */
    data class StructName(val name: String) : StructInside {
        override fun toString() = charlist(name)
    }

    data class UnquotedAtom(val hint: String) : CaretContext, Receiver {
        override fun toString() = "{:unquoted_atom, ${charlist(hint)}}"
    }

    companion object {
        /** The context at [caret] in [file], from the tokens before it on its line. */
        fun at(file: PsiFile, caret: Int): CaretContext = of(lineTokens(file, caret).flatMap(::pieces))

        /**
         * The tokens before [caret] on its line, nearest first, the one the caret is inside cut at it. A line ending in
         * `(` or `.`, or followed by one starting with `.`, continues onto the next, as Elixir joins them.
         */
        private fun lineTokens(file: PsiFile, caret: Int): List<Token> {
            val tokens = mutableListOf<Token>()
            var leaf = leafBefore(file, caret)
            // Only the leaf at the caret runs past it; every leaf before it is taken whole.
            var length = leaf?.let { caret - it.textRange.startOffset } ?: 0
            var joined = false

            while (leaf != null) {
                val type = leaf.elementType
                // A template's markers and text bound its tag's code, which is all Elixir's tools pass `cursor_context`.
                if (type in TEMPLATE) break

                val text = leaf.text.take(length)
                val newline = text.lastIndexOf('\n')
                val line = text.substring(newline + 1)

                // Elixir drops a joined line's comment.
                if (line.isNotEmpty() && !(joined && type == ElixirTypes.COMMENT)) {
                    tokens.add(Token(type, line, cut = text.length < leaf.textLength))
                }
                if (newline >= 0) {
                    val before = text.substring(0, newline)
                    if (!continues(tokens, leaf, before)) break
                    joined = true

                    // A literal's line breaks are in its own text, so the line it joins starts in the same leaf.
                    if (before.isNotEmpty()) {
                        length = newline
                        continue
                    }
                }

                length = Int.MAX_VALUE
                leaf = PsiTreeUtil.prevLeaf(leaf)
            }

            return tokens
        }

        /** The leaf holding the character before [caret]; `null` at the file's start. */
        internal fun leafBefore(file: PsiFile, caret: Int): PsiElement? = if (caret > 0) file.findElementAt(caret - 1) else null

        /**
         * Whether the line before [newline] joins the one [tokens] holds. That line ends in [before], the leaf's own text
         * before its line break, such as a line continuation's `\`, or else in the code before the leaf.
         */
        private fun continues(tokens: List<Token>, newline: PsiElement, before: String): Boolean =
            tokens.asReversed().firstOrNull { !it.blank }?.text?.startsWith(".") == true ||
                (before.trimEnd(::space).lastOrNull() ?: lineEnd(newline)?.text?.lastOrNull())
                    .let { it == '.' || it == '(' }

        /**
         * The last code before [newline], past white space and comments, and so past the lines holding only those, which
         * Elixir joins as unfinished; `null` at the file's start.
         */
        private fun lineEnd(newline: PsiElement): PsiElement? =
            generateSequence(PsiTreeUtil.prevLeaf(newline), PsiTreeUtil::prevLeaf).firstOrNull { !blankOrComment(it) }

        /** Whether [c] is one of Elixir's spaces or line breaks. */
        private fun space(c: Char): Boolean = c in SPACES || c == '\n' || c == '\r'

        private fun blankOrComment(leaf: PsiElement): Boolean = leaf.elementType == ElixirTypes.COMMENT || leaf.text.all(::space)

        private fun pieces(token: Token): List<Piece> {
            val type = token.type
            val text = token.text

            return when {
                // Elixir reads an atom's name, dots, a lone `:` or `%`, and the part of a token before the caret, by
                // their characters.
                token.cut || type == ElixirTypes.ATOM_FRAGMENT || type == ElixirTypes.STRUCT_OPERATOR ||
                    text == ":" || text.all { it == '.' } -> characters(text)
                // Elixir reads these as operator characters, even an operator before a `/` the lexer reads as a name, as
                // in `&==/2`, and a bitstring's `<<` and `>>`.
                text.all { it in OPERATOR_CHARACTERS } -> listOf(Piece(Kind.OPERATOR, text))
                type in WORDS -> listOf(Piece(Kind.WORD, text))
                type in NUMBERS -> listOf(Piece(Kind.NUMBER, text))
                type == ElixirTypes.INTERPOLATION_START -> listOf(Piece(Kind.OPEN, text))
                // A literal's body, a comment, a quote, an `@` or a `(`: Elixir reads its characters as code.
                else -> characters(text)
            }
        }

        /** [text]'s characters as the pieces `cursor_context` would read, nearest first. */
        private fun characters(text: String): List<Piece> {
            val pieces = mutableListOf<Piece>()
            var at = 0

            while (at < text.length) {
                val c = text[at]
                val kind = when (c) {
                    in SPACES -> Kind.SPACE
                    '.' -> Kind.DOT
                    ':' -> Kind.COLON
                    '@' -> Kind.AT
                    '%' -> Kind.PERCENT
                    '?' -> Kind.QUESTION
                    '(' -> Kind.OPEN_PARENTHESIS
                    in STARTING_PUNCTUATION -> Kind.OPEN
                    in CLOSING_PUNCTUATION -> Kind.CLOSE
                    in OPERATOR_CHARACTERS -> Kind.OPERATOR
                    else if isDecimalDigit(c) -> Kind.NUMBER
                    else -> Kind.WORD
                }
                val end = when (kind) {
                    Kind.SPACE -> CharArrayUtil.shiftForward(text, at, SPACES)
                    Kind.WORD, Kind.NUMBER -> nameEnd(text, at)
                    Kind.OPERATOR if c in OPERATOR_RUN -> CharArrayUtil.shiftForward(text, at, OPERATOR_RUN)
                    else -> at + 1
                }

                pieces.add(Piece(kind, text.substring(at, end)))
                at = end
            }

            return pieces.asReversed()
        }

        /** Where the name starting at [start] in [text] ends, on its line, its `?` or `!` included. */
        internal fun nameEnd(text: CharSequence, start: Int): Int =
            CharArrayUtil.shiftForwardUntil(text, start, NAME_ENDS)
                .let { if (it > start && it < text.length && text[it] in TRAILING) it + 1 else it }

        /** Whether [character] can name a sigil after its `~`. */
        internal fun sigilLetter(character: Char?): Boolean =
            character != null && (character in 'a'..'z' || character in 'A'..'Z')

        /** `codepoint_cursor_context/2` over [pieces], nearest the caret first. */
        private fun of(pieces: List<Piece>): CaretContext {
            val spaced = pieces.firstOrNull()?.kind == Kind.SPACE
            val rest = strip(pieces)
            val head = rest.firstOrNull() ?: return Expr
            val before = rest.drop(1)

            return when (head.kind) {
                Kind.PERCENT ->
                    if (atomColon(before)) UnquotedAtom("%") else Struct(StructName(""))
                Kind.OPERATOR if startsExpression(rest) -> Expr
                Kind.COLON if atomColon(rest) -> if (spaced) Expr else UnquotedAtom("")
                Kind.DOT if lastCharacter(before).let { it != null && it !in ".:" } -> dot(before, "")
                Kind.OPEN_PARENTHESIS -> call(before)
                Kind.OPERATOR if head.text.endsWith("/") ->
                    arity(listOfNotNull(head.text.dropLast(1).takeIf(String::isNotEmpty)?.let { Piece(Kind.OPERATOR, it) }) + before)
                Kind.OPEN -> Expr
                else -> when {
                    !spaced -> identifier(rest, false)
                    closing(rest) -> BlockKeywordOrBinaryOperator("")
                    else -> call(rest)
                }
            }
        }

        /** Whether the code before the caret ends in `=>` or `->` not after a `:`, or opens a bitstring, `<<` not after another `<`. */
        private fun startsExpression(pieces: List<Piece>): Boolean =
            lastCharacters(pieces, 3).let { it.endsIn("=>", ":") || it.endsIn("->", ":") || it.endsIn("<<", "<") }

        /** Whether this ends in [suffix], not right after one of [notAfter]. */
        private fun String.endsIn(suffix: String, notAfter: String): Boolean =
            endsWith(suffix) && (length == suffix.length || this[length - suffix.length - 1] !in notAfter)

        /** The last [count] characters of [pieces], in the order they are written, read across tokens as Elixir reads them. */
        private fun lastCharacters(pieces: List<Piece>, count: Int): String {
            val characters = StringBuilder()

            for (piece in pieces) {
                if (characters.length >= count) break
                characters.insert(0, piece.text)
            }

            return characters.takeLast(count).toString()
        }

        /** The last character of [pieces]; `null` where they hold none. */
        private fun lastCharacter(pieces: List<Piece>): Char? = lastCharacters(pieces, 1).singleOrNull()

        /** [pieces]' text in the order it is written. */
        private fun written(pieces: List<Piece>): String = pieces.asReversed().joinToString("", transform = Piece::text)

        private fun strip(pieces: List<Piece>): List<Piece> = pieces.dropWhile { it.kind == Kind.SPACE }

        private fun arity(pieces: List<Piece>): CaretContext =
            when (val context = identifier(strip(pieces), true)) {
                is LocalOrVar -> LocalArity(context.hint)
                is Dot -> DotArity(context.receiver, context.hint)
                is Operator -> OperatorArity(context.operator)
                is Sigil -> None
                else -> Operator("/")
            }

        private fun call(pieces: List<Piece>): CaretContext {
            val rest = strip(pieces)

            val called = if (rest.firstOrNull()?.kind == Kind.DOT) strip(rest.drop(1)) else emptyList()

            if (called.firstOrNull()?.let(::endsInName) == true) {
                return when (val context = identifier(called, true)) {
                    is LocalOrVar -> AnonymousCall(Var(context.hint))
                    is ModuleAttribute -> AnonymousCall(context)
                    else -> None
                }
            }

            return when (val context = identifier(rest, true)) {
                is LocalOrVar -> LocalCall(context.hint)
                is Dot -> DotCall(context.receiver, context.hint)
                is Operator -> OperatorCall(context.operator)
                else -> None
            }
        }

        private fun closing(pieces: List<Piece>): Boolean {
            val head = pieces.firstOrNull() ?: return false

            return when (head.kind) {
                Kind.CLOSE -> true
                // A bitstring's `>>`, not the end of an operator such as `>>>` or `~>>`.
                Kind.OPERATOR -> lastCharacters(pieces, 3).endsIn(">>", ">~")
                else -> {
                    // Elixir reads the name back from the caret to a `?` or `!`, so one nearest the caret leaves it no name.
                    val run = pieces.takeWhile(::endsInName).ifEmpty { return false }
                    val name = written(run)

                    name in LITERAL_WORDS || isDecimalDigit(name.first()) || name.first() in 'A'..'Z' || atomColon(pieces.drop(run.size))
                }
            }
        }

        /** `identifier_to_cursor_context/3`: the word, number or operator [pieces] starts with. */
        private fun identifier(pieces: List<Piece>, callOperator: Boolean): CaretContext {
            val head = pieces.firstOrNull() ?: return None
            val before = pieces.drop(1)

            // Elixir reads a `?` or `!` after one of a name's characters as the name's last, where the lexer splits them, as
            // in `1!` and `:a@?`.
            if (head.kind != Kind.WORD && head.text.length == 1 && whole(head.text) && before.firstOrNull()?.let(::endsInName) == true) {
                return identifier(listOf(Piece(Kind.WORD, head.text)) + before, callOperator)
            }

            atInside(pieces)?.let { return it }

            return when (head.kind) {
                Kind.WORD -> word(pieces, callOperator)
                Kind.NUMBER ->
                    if (head.text.all(::isDecimalDigit) && lastCharacters(before, 2).endsIn("&", "&")) {
                        CaptureArg("&${head.text}")
                    } else {
                        None
                    }
                Kind.AT -> ModuleAttribute("")
                Kind.CLOSE -> if (callOperator && head.text == ")" && lastCharacter(before).let { it != null && it != '?' }) Expr else None
                in OPERATOR_KINDS -> operator(pieces, callOperator)
                Kind.DOT -> dots(pieces)
                else -> None
            }
        }

        /**
         * Elixir scans `@` as one of a name's characters, and reads a name with one after its first character as an atom
         * after a `:` and as nothing elsewhere; `null` where the name [pieces] starts with has none.
         */
        private fun atInside(pieces: List<Piece>): CaretContext? {
            val name = nameRun(pieces)
            if (name.dropLast(1).none { it.kind == Kind.AT }) return null
            val before = pieces.drop(name.size)

            return if (atomColon(before)) unquotedAtom(written(name)) else None
        }

        /** The pieces of the name [pieces] starts with, `@` included, back to one that ends in a `?` or `!`, which ends an earlier name. */
        private fun nameRun(pieces: List<Piece>): List<Piece> =
            pieces.withIndex()
                .takeWhile { (i, piece) -> if (i == 0) piece.kind in NAME_STARTS else endsInName(piece) }
                .map { it.value }

        /** Whether [piece] ends in one of a name's characters: a word, number or `@` with no `?` or `!` after it. */
        private fun endsInName(piece: Piece) = piece.kind in NAME_STARTS && !whole(piece.text)

        /** Whether [name] ends in `?` or `!`, which end a name, so nothing typed after it joins it. */
        internal fun whole(name: String): Boolean = name.lastOrNull()?.let { it in TRAILING } == true

        /** Elixir's `:..`, `...`, `:.` and `..`, which the code before the caret ends in. */
        private fun dots(pieces: List<Piece>): CaretContext {
            val last = lastCharacters(pieces, 3)

            return when {
                last.endsWith(":..") -> UnquotedAtom("..")
                last.endsWith("...") -> Operator("...")
                last.endsWith(":.") -> UnquotedAtom(".")
                last.endsWith("..") -> Operator("..")
                else -> None
            }
        }

        private fun word(pieces: List<Piece>, callOperator: Boolean): CaretContext {
            // Elixir reads a name back to the first character that ends one, across what the lexer splits, as a `` ` ``.
            val run = nameRun(pieces).takeWhile { it.kind != Kind.AT }
            val name = written(run)
            val before = pieces.drop(run.size)
            val previous = before.firstOrNull()

            return when {
                previous?.kind == Kind.AT ->
                    when {
                        !isIdentifier(name) -> None
                        before.getOrNull(1)?.kind == Kind.PERCENT -> Struct(ModuleAttribute(name))
                        else -> ModuleAttribute(name)
                    }
                name.length == 1 && opensSigil(pieces) -> Sigil(name)
                atomColon(before) -> unquotedAtom(name)
                lastCharacter(before) == '?' -> None
                isAlias(name) -> alias(name, before)
                !isIdentifier(name) -> None
                callOperator && name in InspectAtom.TEXTUAL_OPERATORS -> Operator(name)
                before.size == 1 && previous?.kind == Kind.PERCENT -> if (name in InspectAtom.TEXTUAL_OPERATORS) None else Struct(LocalOrVar(name))
                else -> {
                    val spaced = previous?.kind == Kind.SPACE
                    val rest = strip(before)

                    when {
                        dotted(rest) -> dot(rest.drop(1), name)
                        spaced && closing(rest) -> BlockKeywordOrBinaryOperator(name)
                        else -> LocalOrVar(name)
                    }
                }
            }
        }

        private fun alias(name: String, before: List<Piece>): CaretContext {
            val rest = strip(before)

            return when {
                dotted(rest) -> nested(rest.drop(1), name)
                rest.firstOrNull()?.kind == Kind.PERCENT -> Struct(StructName(name))
                else -> Alias(name)
            }
        }

        /** Whether [pieces] start with a dot operator: one `.`, not the end of a range or `...`. */
        private fun dotted(pieces: List<Piece>) = pieces.firstOrNull()?.kind == Kind.DOT && pieces.getOrNull(1)?.kind != Kind.DOT

        /** Whether [pieces] start with an atom's colon: one `:`, not the end of a `::`. */
        private fun atomColon(pieces: List<Piece>) = pieces.firstOrNull()?.kind == Kind.COLON && pieces.getOrNull(1)?.kind != Kind.COLON

        /** `nested_alias/3`: [name] after a dot. */
        private fun nested(pieces: List<Piece>, name: String): CaretContext =
            when (val previous = identifier(strip(pieces), true)) {
                is Struct -> when (val inside = previous.inside) {
                    is StructName -> Struct(StructName("${inside.name}.$name"))
                    is InsideAlias -> Struct(InsideAlias(inside.inside, "${inside.name}.$name"))
                    is AliasInside -> Struct(InsideAlias(inside, name))
                }
                is Alias -> Alias("${previous.name}.$name")
                is InsideAlias -> InsideAlias(previous.inside, "${previous.name}.$name")
                is LocalOrVar, is ModuleAttribute -> InsideAlias(previous, name)
                else -> None
            }

        /** `dot/3`: [hint] after a dot. */
        private fun dot(pieces: List<Piece>, hint: String): CaretContext =
            when (val previous = identifier(strip(pieces), true)) {
                is LocalOrVar -> Dot(Var(previous.hint), hint)
                is Struct -> when (val inside = previous.inside) {
                    is StructName -> Struct(Dot(Alias(inside.name), hint))
                    // Elixir writes a variable receiver inside a struct as `local_or_var`, not `var`.
                    is InsideAlias, is Dot, is ModuleAttribute, is LocalOrVar -> Struct(Dot(inside, hint))
                }
                is Receiver -> Dot(previous, hint)
                else -> None
            }

        /** `operator/4`: the operator characters [pieces] starts with, a name's `!` before them included, as Elixir reads `value!=`. */
        private fun operator(pieces: List<Piece>, callOperator: Boolean): CaretContext {
            val run = pieces.takeWhile { it.kind in OPERATOR_KINDS }
            val bang = pieces.getOrNull(run.size)?.takeIf { it.kind in NAME_STARTS && it.text.length > 1 && it.text.endsWith('!') }
            val (operator, rest) = if (bang == null) {
                written(run) to pieces.drop(run.size)
            } else {
                "!${written(run)}" to listOf(Piece(bang.kind, bang.text.dropLast(1))) + pieces.drop(run.size + 1)
            }

            return when {
                operator == "~" -> if (callOperator || dotted(strip(rest))) None else Sigil("")
                operator.length == 1 && operator in "<|/" && opensSigil(rest) -> None
                // The tokenizer reads `:` before an operator as an atom, a callable one or not, such as `:->`.
                operator.length > 1 && operator.first() == ':' && InspectAtom.classify(operator.drop(1)) != InspectAtom.Class.OTHER ->
                    UnquotedAtom(operator.drop(1))
                !InspectAtom.isOperator(operator) -> None
                else -> {
                    val afterOperator = strip(rest)

                    if (dotted(afterOperator)) dot(afterOperator.drop(1), operator) else Operator(operator)
                }
            }
        }

        /** Whether [pieces] end in a sigil's `~` and its one letter, where that `~` doesn't end an operator such as `=~`. */
        private fun opensSigil(pieces: List<Piece>): Boolean =
            lastCharacters(pieces, 3).let { sigilLetter(it.lastOrNull()) && it.dropLast(1).endsIn("~", TILDE_OPERATOR_PREFIXES) }

        /** Whether `String.Tokenizer` reads [name] as an alias, which it does with one `?` or `!` after it. */
        private fun isAlias(name: String) =
            ElixirModulesUtil.elixirAliasSegmentsRegex.matches(if (whole(name)) name.dropLast(1) else name)

        /** Whether `String.Tokenizer` reads [name] as an identifier, which it takes in any spelling it normalizes. */
        private fun isIdentifier(name: String): Boolean = tokenize(name) == InspectAtom.Class.IDENTIFIER

        /** The atom [name] after a `:` is, or nothing where `String.Tokenizer.tokenize/1` doesn't read all of [name] as one token. */
        private fun unquotedAtom(name: String): CaretContext = if (tokenize(name) == InspectAtom.Class.OTHER) None else UnquotedAtom(name)

        private fun tokenize(name: String): InspectAtom.Class = InspectAtom.tokenize(identifierAtomName(name) { InspectAtom.LANGUAGE_LEVEL })

        private fun charlist(text: String): String = "~c\"${InspectAtom.escape(text)}\""

        private val WORDS = TokenSet.create(
            ElixirTypes.IDENTIFIER_TOKEN,
            ElixirTypes.ALIAS_TOKEN,
            ElixirTypes.AFTER,
            ElixirTypes.CATCH,
            ElixirTypes.DO,
            ElixirTypes.ELSE,
            ElixirTypes.END,
            ElixirTypes.FN,
            ElixirTypes.RESCUE,
            ElixirTypes.TRUE,
            ElixirTypes.FALSE,
            ElixirTypes.NIL,
            ElixirTypes.AND_WORD_OPERATOR,
            ElixirTypes.OR_WORD_OPERATOR,
            ElixirTypes.NOT_OPERATOR,
            ElixirTypes.IN_OPERATOR,
            ElixirTypes.WHEN_OPERATOR,
            ElixirTypes.INTERPOLATING_SIGIL_NAME,
            ElixirTypes.LITERAL_SIGIL_NAME,
        )

        private val NUMBERS = TokenSet.create(
            ElixirTypes.VALID_DECIMAL_DIGITS,
            ElixirTypes.VALID_BINARY_DIGITS,
            ElixirTypes.VALID_HEXADECIMAL_DIGITS,
            ElixirTypes.VALID_OCTAL_DIGITS,
            ElixirTypes.INVALID_DECIMAL_DIGITS,
            ElixirTypes.INVALID_BINARY_DIGITS,
            ElixirTypes.INVALID_HEXADECIMAL_DIGITS,
            ElixirTypes.INVALID_OCTAL_DIGITS,
            ElixirTypes.INVALID_UNKNOWN_BASE_DIGITS,
            ElixirTypes.BASE_WHOLE_NUMBER_PREFIX,
            ElixirTypes.BINARY_WHOLE_NUMBER_BASE,
            ElixirTypes.HEXADECIMAL_WHOLE_NUMBER_BASE,
            ElixirTypes.OCTAL_WHOLE_NUMBER_BASE,
            ElixirTypes.UNKNOWN_WHOLE_NUMBER_BASE,
            ElixirTypes.OBSOLETE_BINARY_WHOLE_NUMBER_BASE,
            ElixirTypes.OBSOLETE_HEXADECIMAL_WHOLE_NUMBER_BASE,
            ElixirTypes.DECIMAL_MARK,
            ElixirTypes.EXPONENT_MARK,
            ElixirTypes.NUMBER_SEPARATOR,
        )

        private val TEMPLATE = TokenSet.create(
            ElixirTypes.EEX_CLOSING,
            ElixirTypes.EEX_COMMENT,
            ElixirTypes.EEX_COMMENT_MARKER,
            ElixirTypes.EEX_DATA,
            ElixirTypes.EEX_EMPTY_MARKER,
            ElixirTypes.EEX_EQUALS_MARKER,
            ElixirTypes.EEX_ESCAPED_OPENING,
            ElixirTypes.EEX_FORWARD_SLASH_MARKER,
            ElixirTypes.EEX_OPENING,
            ElixirTypes.EEX_PIPE_MARKER,
        )

        internal const val OPERATOR_CHARACTERS = "\\<>+-*/:=|&~^%!$"

        /** The [OPERATOR_CHARACTERS] that run together into one operator; `%`, `:`, `~` and `&` each stand alone. */
        private const val OPERATOR_RUN = "\\<>+-*/=|^!$"

        /** The characters that make a `~` after them part of an operator, as in `<~` and `=~`. */
        private const val TILDE_OPERATOR_PREFIXES = "<=~"

        /** Elixir's `@space`: the characters it reads as a space, which a form feed is not. */
        internal const val SPACES = " \t"

        private const val STARTING_PUNCTUATION = ",([{;"

        private const val CLOSING_PUNCTUATION = ")]}\"'"

        /** What ends a name on its line. */
        private const val NAME_ENDS = "?$OPERATOR_CHARACTERS$STARTING_PUNCTUATION$CLOSING_PUNCTUATION$SPACES.@\n\r"

        /** What ends a name, so nothing typed after it joins it. */
        private const val TRAILING = "?!"

        private val OPERATOR_KINDS = setOf(Kind.OPERATOR, Kind.COLON, Kind.PERCENT)

        /** The pieces a name's characters start. */
        private val NAME_STARTS = setOf(Kind.WORD, Kind.NUMBER, Kind.AT)
        private val LITERAL_WORDS = setOf("nil", "true", "false")

        /** One of the file's tokens: its [type], its text up to the caret, and whether the caret [cut] it. */
        private class Token(val type: IElementType?, val text: String, val cut: Boolean) {
            /** Whether the token holds only Elixir's spaces and line breaks. */
            val blank: Boolean = text.all(::space)
        }

        /** What `cursor_context` reads a run of characters as. */
        private enum class Kind { SPACE, WORD, NUMBER, DOT, COLON, AT, PERCENT, QUESTION, OPEN_PARENTHESIS, OPEN, CLOSE, OPERATOR }

        private class Piece(val kind: Kind, val text: String)
    }
}
