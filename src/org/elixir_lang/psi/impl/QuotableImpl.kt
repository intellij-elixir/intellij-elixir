@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package org.elixir_lang.psi.impl

import com.ericsson.otp.erlang.*
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.Factory
import com.intellij.psi.stubs.StubBuildCachedValuesManager
import com.intellij.psi.stubs.StubBuildCachedValuesManager.StubBuildCachedValue
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.ElixirLanguage
import org.elixir_lang.Macro
import org.elixir_lang.mix.project.computeReadAction
import org.elixir_lang.otpErlangList
import org.elixir_lang.otpErlangTuple
import org.elixir_lang.psi.*
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.IDENTIFIER_TOKEN_SET
import org.elixir_lang.psi.impl.ParentImpl.RAW_BYTE_OFFSET
import org.elixir_lang.psi.impl.ParentImpl.addChildTextCodePoints
import org.elixir_lang.psi.impl.ParentImpl.elixirCharList
import org.elixir_lang.psi.impl.ParentImpl.elixirString
import org.elixir_lang.psi.operation.*
import org.elixir_lang.language_level.ElixirLanguageFeature.*
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.language_level.ElixirLanguageLevelResolver.isAvailable
import org.elixir_lang.language_level.ElixirLanguageLevelResolver.languageLevelFor
import org.jetbrains.annotations.Contract
import java.lang.Double
import java.lang.Long
import java.math.BigInteger
import java.text.Normalizer
import java.util.*

val UNQUOTED_TYPES = arrayOf<Class<*>>(ElixirEndOfExpression::class.java, PsiComment::class.java, PsiWhiteSpace::class.java)

/**
 * @return {@code true} if {@code element} should not have {@code quote} called on it because Elixir natively
 *   ignores such tokens.  {@code false} if {@code element} should have {@code quote} called on it.
 */
fun PsiElement.isUnquoted(): Boolean {
    var unquoted = false

    for (unquotedType in UNQUOTED_TYPES) {
        if (unquotedType.isInstance(this)) {
            unquoted = true
            break
        }
    }

    return unquoted
}

fun ElixirStab.quote(metadata: OtpErlangList): OtpErlangObject =
        stabBody?.quote(metadata) ?:
        stabOperationList.map(Quotable::quote).toTypedArray().let(::OtpErlangList)

fun ElixirStabBody.quote(metadata: OtpErlangList): OtpErlangObject =
        children
                .asSequence()
                .filterNot {
                    // skip endOfExpression
                    it.isUnquoted()
                }
                .map { it as Quotable }
                .map { it.quote() }
                .let {
                    QuotableImpl.buildBlock(
                        it.toList(),
                        metadata,
                        QuotableImpl.rearrangesUnaryOperators(this),
                        QuotableImpl.mergesEnclosingParenMetadataOntoBlock(this)
                    )
                }

object QuotableImpl {
    private val AMBIGUOUS_OP = OtpErlangAtom("ambiguous_op")
    @JvmField
    val NIL = OtpErlangAtom("nil")
    private val AMBIGUOUS_OP_KEYWORD_PAIR = OtpErlangTuple(
            arrayOf<OtpErlangObject>(AMBIGUOUS_OP, NIL)
    )
    private val ALIASES = OtpErlangAtom("__aliases__")
    private val DIVISION = OtpErlangAtom("/")
    internal val BLOCK = OtpErlangAtom("__block__")
    private val FN = OtpErlangAtom("fn")
    private val EXCLAMATION_POINT = OtpErlangAtom("!")
    private val MULTIPLE_ALIASES = OtpErlangAtom("{}")
    private val NOT = OtpErlangAtom("not")
    private val RANGE = OtpErlangAtom("..")
    private val REARRANGED_UNARY_OPERATORS = arrayOf(EXCLAMATION_POINT, NOT)
    private val UNQUOTE_SPLICING = OtpErlangAtom("unquote_splicing")
    private val WHEN = OtpErlangAtom("when")

    @Contract(pure = true)
    @JvmStatic
    fun quote(associationOperation: AssociationOperation): OtpErlangObject {
        val children = associationOperation.children

        // associationInfixOperator is private so not a PsiElement
        assert(children.size == 2)

        return OtpErlangTuple(children.map { it as Quotable }.map(Quotable::quote).toTypedArray())
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(infix: Infix): OtpErlangObject {
        ambiguousDualOperatorCall(infix)?.let { return it }

        val quotedLeftOperand = infix.leftOperand()!!.quote()

        val operator = infix.operator()
        val quotedOperator = operator.quote()

        val quotedRightOperand = infix.rightOperand()?.quote() ?:
        // this is not valid Elixir quoting, but something needs to be there for quoting to work
        NIL

        return quotedFunctionCall(
                quotedOperator,
                metadata(operator),
                quotedLeftOperand,
                quotedRightOperand
        )
    }

    /**
     * `one +(two)` quoted as the no-parentheses call `one(+two)` - what Elixir does from 1.17.0 - or
     * `null` when this is not that shape or the language level predates it, leaving the caller's
     * binary-operation quoting correct.
     *
     * The plugin's lexer implements the pre-1.17.0 rule, so `one +two` already parses as a call and
     * quotes through [quote] for `UnqualifiedNoParenthesesCall` instead. What arrives here as an
     * operation is only the set 1.17.0 added - a container, `%` or the opposite sign after the sign -
     * so this reshapes rather than re-lexes: `{op, [left, right]}` becomes
     * `{leftIdentifier, [{op, [right]}]}`.
     *
     * Reshaping at quote time keeps one parse tree per file, which the shared `.txt` fixtures and the
     * SDK-less lexer both require. The cost is that the PSI still reads as an operation on a 1.17
     * project, so anything that walks the tree rather than the quoted form sees the older reading.
     *
     * FIXME This is interim, and belongs in the lexer. Deciding it here reconstructs from post-parse
     * PSI what the lexer knew and discarded, so it only reaches the case where the ambiguous operator
     * is the outermost node: `a + b +(c)` quotes as `(a + b) + c` where Elixir 1.17 gives `a + b(+c)`,
     * and `one +(two) + three` as `one(+two) + three` where Elixir gives `one(+two + three)`. Both are
     * out of reach because **association** was committed at lex time, and no reshape at a single node
     * can move an operand across an operator that bound differently. The fix is `op_identifier`
     * classification in `Elixir.flex` - keyed on the file's Elixir version, since the exclusion set
     * changed at 1.17.0 - with an `Elixir.bnf` rule that produces this shape directly, at which point
     * delete this function rather than leaving two mechanisms for one rule.
     */
    @RequiresReadLock
    private fun ambiguousDualOperatorCall(infix: Infix): OtpErlangObject? {
        val operator = infix.operator()
        val sign = operator.text.singleOrNull()?.takeIf { it == '+' || it == '-' } ?: return null

        // `?dual_op(Sign), not(?is_space(NotMarker))`: a space before the sign and none after is what
        // makes the identifier a call rather than the operation's left operand. An escaped newline
        // straight after the identifier is not that space.
        val beforeOperator = generateSequence(operator.prevSibling) { it.prevSibling }
            .takeWhile { it is PsiWhiteSpace }
            .toList()
            .ifEmpty { return null }
        val afterIdentifier = beforeOperator.last().text.first()
        if (afterIdentifier != ' ' && afterIdentifier != '\t') return null
        // Before 1.20 Elixir stops at a line continuation between that space and the sign.
        if (beforeOperator.any { '\\' in it.text } && !isAvailable(ESCAPED_NEWLINE_AS_SPACE, operator)) return null
        val afterOperator = operator.nextSibling?.takeUnless { it is PsiWhiteSpace } ?: return null

        // `NotMarker =/= Sign, NotMarker =/= $/, NotMarker =/= $>` - the three exclusions 1.17.0 kept.
        val notMarker = afterOperator.text.firstOrNull() ?: return null
        if (notMarker == sign || notMarker == '/' || notMarker == '>') return null

        val call = infix.leftOperand() as? UnqualifiedNoArgumentsCall<*> ?: return null
        // A `do` block makes it a call on its own terms, with the block as keyword arguments.
        if (call.doBlock != null) return null

        val identifier = call.identifier

        if (!isAvailable(AMBIGUOUS_DUAL_OPERATOR_CALL, identifier)) return null

        val quotedRightOperand = infix.rightOperand()?.quote() ?: return null

        return quotedFunctionCall(
                identifier.text,
                // `ambiguous_op` leads the keyword list, as Elixir's `meta_with_ambiguous_op` prepends it.
                OtpErlangList(arrayOf(AMBIGUOUS_OP_KEYWORD_PAIR, metadata(identifier).elementAt(0))),
                quotedFunctionCall(sign.toString(), metadata(operator), quotedRightOperand)
        )
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(notIn: NotIn): OtpErlangObject {
        val quotedLeftOperand = notIn.leftOperand()!!.quote()

        val notOperator = notIn.notInfixOperator
        val quotedNotOperator = notOperator.quote()

        val inOperator = notIn.inInfixOperator
        val quotedInOperator = inOperator.quote()

        val quotedRightOperand = notIn.rightOperand()?.quote() ?:
        // this is not valid Elixir quoting, but something needs to be there for quoting to work
        NIL

        return quotedFunctionCall(
                quotedNotOperator,
                metadata(notOperator),
                quotedFunctionCall(
                        quotedInOperator,
                        metadata(if (isAvailable(IN_OF_NOT_IN_ON_ITS_OWN_LINE, notIn)) inOperator else notOperator),
                        quotedLeftOperand,
                        quotedRightOperand
                )
        )
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(ternary: Ternary): OtpErlangObject = when (val leftOperand = ternary.leftOperand()) {
        is Two -> {
            val quotedRangeFirst = leftOperand.leftOperand()!!.quote()
            val quotedRangeLast = leftOperand.rightOperand()?.quote() ?: NIL

            val operator = ternary.operator()
            val quotedOperator = OtpErlangAtom("..//")

            val quotedRangeStep = ternary.rightOperand()?.quote() ?: NIL

            quotedFunctionCall(
                    quotedOperator,
                    metadata(operator),
                    quotedRangeFirst,
                    quotedRangeLast,
                    quotedRangeStep
            )
        }
        else -> {
            val quoted = quote(ternary as Infix)
            val quotedLeftOperand = Macro.callArguments(quoted as OtpErlangTuple).elementAt(0)

            // `build_op` checks the quoted left operand, so parentheses around the range do not stop the step joining it.
            // Small integer operands quote as an OtpErlangString, not an OtpErlangList.
            val range = (quotedLeftOperand as? OtpErlangTuple)
                ?.takeIf { it.arity() == 3 && it.elementAt(0) == RANGE }
                ?.takeIf { it.elementAt(2) is OtpErlangList || it.elementAt(2) is OtpErlangString }
                ?.let { Macro.callArguments(it) }
                ?.takeIf { it.arity() == 2 }

            if (range != null) {
                quotedFunctionCall(
                        OtpErlangAtom("..//"),
                        quotedLeftOperand.elementAt(1) as OtpErlangList,
                        range.elementAt(0),
                        range.elementAt(1),
                        Macro.callArguments(quoted).elementAt(1)
                )
            } else {
                // invalid for Elixir native
                quoted
            }
        }
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(bitString: ElixirBitString): OtpErlangObject {
        val openingBits = bitString.node.getChildren(TokenSet.create(ElixirTypes.OPENING_BIT))

        assert(openingBits.size == 1)

        val openingBit = openingBits[0]

        return quotedFunctionCall(
                "<<>>",
                metadata(openingBit),
                *bitString.children.map { it as Quotable }.map { it.quote() }.toTypedArray()
        )
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(blockIdentifier: ElixirBlockIdentifier): OtpErlangObject = OtpErlangAtom(blockIdentifier.node.text)

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(blockItem: ElixirBlockItem): OtpErlangObject {
        val blockIdentifier = blockItem.blockIdentifier
        val quotedValue = blockItem.stab?.quote() ?: emptyBlock()

        return OtpErlangTuple(
                arrayOf(blockIdentifier.quote(), quotedValue)
        )
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(bracketArguments: ElixirBracketArguments): OtpErlangObject {
        val children = bracketArguments.children

        assert(children.size == 1)

        return (children[0] as Quotable).quote()
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(digits: Digits): OtpErlangObject {
        val text = digits.text
        val base = digits.base()

        return try {
            val value = Long.parseLong(text, base)
            OtpErlangLong(value)
        } catch (_: NumberFormatException) {
            val value = BigInteger(text, base)
            OtpErlangLong(value)
        }
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(accessExpression: ElixirAccessExpression): OtpErlangObject {
        val children = accessExpression.children

        if (children.size != 1) {
            TODO("Expecting 1 child in accessExpression")
        }

        return (children[0] as Quotable).quote()
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(alias: ElixirAlias): OtpErlangObject =
            quotedFunctionCall(
                    ALIASES,
                    metadata(alias),
                    OtpErlangAtom(alias.text)
            )

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(anonymousFunction: ElixirAnonymousFunction): OtpErlangObject {
        val metadata = metadata(anonymousFunction)
        val quotedStab = anonymousFunction.stab.quote()

        return OtpErlangTuple(
                arrayOf(FN, metadata, quotedStab)
        )
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(associations: ElixirAssociations): OtpErlangObject = associations.associationsBase.quote()

    @Contract(pure = true)
    @JvmStatic
    fun quote(associationsBase: ElixirAssociationsBase): OtpErlangObject {
        return OtpErlangList(associationsBase.children.map { it as Quotable }.map(Quotable::quote).toTypedArray())
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(atom: ElixirAtom): OtpErlangObject =
            atom.line?.quoteAsAtom() ?: atom.node.let { atomNode ->
                val atomFragmentNode = atomNode.lastChildNode

                assert(atomFragmentNode.elementType === ElixirTypes.ATOM_FRAGMENT)

                identifierAtom(atomFragmentNode.text, atom)
            }

    @Contract(pure = true)
    @JvmStatic
    fun quote(atomKeyword: ElixirAtomKeyword): OtpErlangObject = OtpErlangAtom(atomKeyword.text)

    @Contract(pure = true)
    @JvmStatic
    fun quote(line: ElixirLine): OtpErlangObject =
            quotedChildNodes(line, *childNodes(line.lineBody!!))

    @Contract(pure = true)
    @JvmStatic
    fun quote(charToken: ElixirCharToken): OtpErlangObject {
        val children = charToken.node.getChildren(null)

        if (children.size != 2) {
            TODO("CharToken expected to be ?(<character>|<escape sequence>)")
        }

        val tokenized = children[1]
        val tokenizedElementType = tokenized.elementType

        val codePoint = if (tokenizedElementType === ElixirTypes.FRAGMENT) {
            if (tokenized.text.codePointCount(0, tokenized.textLength) != 1) {
                TODO("Tokenized character expected to only be one code point long")
            }

            tokenized.text.codePointAt(0)
        } else {
            (tokenized.psi as EscapeSequence).codePoint()
        }

        return OtpErlangLong(codePoint.toLong())
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(
            containerAssociationOperation: ElixirContainerAssociationOperation
    ): OtpErlangObject {
        val children = containerAssociationOperation.children

        // associationInfixOperator is private so not a PsiElement
        assert(children.size == 2)

        return OtpErlangTuple(children.map { it as Quotable }.map(Quotable::quote).toTypedArray())
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(atUnqualifiedBracketOperation: AtNumericBracketOperation): OtpErlangObject {
        val quotedOperator = atUnqualifiedBracketOperation.atPrefixOperator.quote()

        val children = atUnqualifiedBracketOperation.children

        assert(children.size == 3)

        val quotedOperand = (children[1] as Quotable).quote()
        val metadata = metadata(atUnqualifiedBracketOperation)

        val quotedContainer = quotedFunctionCall(quotedOperator, metadata, quotedOperand)

        val bracketArguments = atUnqualifiedBracketOperation.bracketArguments
        val quotedBracketArguments = bracketArguments.quote()

        // `@1[:a]` - Elixir's `bracket_at_expr -> at_op_eol access_expr bracket_arg`.
        return quotedFunctionCall(
                "Elixir.Access",
                "get",
                bracketIdentifierMetadata(bracketArguments),
                quotedContainer,
                quotedBracketArguments
        )
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(noParenthesesManyStrictNoParenthesesExpression: ElixirNoParenthesesManyStrictNoParenthesesExpression): OtpErlangObject {
        val children = noParenthesesManyStrictNoParenthesesExpression.children

        assert(children.size == 1)

        return (children[0] as Quotable).quote()
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(multipleAliases: ElixirMultipleAliases): OtpErlangObject =
            quotedFunctionArguments(*multipleAliases.children.map { it as Quotable }.map { it.quote() }.toTypedArray())

    @Contract(pure = true)
    @JvmStatic
    fun quote(mapUpdateArguments: ElixirMapUpdateArguments): OtpErlangObject {
        val children = mapUpdateArguments.children

        assert(children.size >= 3)

        val quotedCurrentMap = (children[0] as Quotable).quote()

        val pipeOperator = children[1] as Operator
        val quotedPipeOperator = pipeOperator.quote()

        val quotedMapUpdates = (2 until children.size)
                .asSequence()
                .map { children[it] as Quotable }
                .map { it.quote() }
                .flatMap {
                    if (it is OtpErlangList) {
                        it.asSequence()
                    } else {
                        sequenceOf(it)
                    }
                }
                .toList()
                .toTypedArray()
                .let(::OtpErlangList)

        return quotedFunctionCall(
                quotedPipeOperator,
                metadata(pipeOperator),
                quotedCurrentMap,
                quotedMapUpdates
        )
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(mapOperation: ElixirMapOperation): OtpErlangObject = mapOperation.mapArguments.quote()

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(mapArguments: ElixirMapArguments): OtpErlangObject {
        val node = mapArguments.node
        val openingCurlies = node.getChildren(TokenSet.create(ElixirTypes.OPENING_CURLY))

        assert(openingCurlies.size == 1)

        val openingCurly = openingCurlies[0]

        val quotedArguments =
                mapArguments.mapUpdateArguments?.quote()?.let { arrayOf(it) }
                        ?: mapArguments.mapConstructionArguments?.quoteArguments() ?: emptyArray()

        return quotedFunctionCall(
                "%{}",
                metadata(openingCurly),
                *quotedArguments
        )
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(list: ElixirList): OtpErlangObject {
        val listArguments = list.children
        val quotedListArgumentList = mutableListOf<OtpErlangObject>()

        if (listArguments.isNotEmpty()) {
            (0 until listArguments.size - 1)
                    .asSequence()
                    .map { listArguments[it] as Quotable }
                    .mapTo(quotedListArgumentList) { it.quote() }

            val lastListArgument = listArguments[listArguments.size - 1]

            if (lastListArgument is ElixirKeywords) {
                (lastListArgument as QuotableKeywordList)
                        .quotableKeywordPairList()
                        .mapTo(quotedListArgumentList) { it.quote() }
            } else {
                val quotable = lastListArgument as Quotable
                quotedListArgumentList.add(quotable.quote())
            }
        }

        return elixirCharList(OtpErlangList(quotedListArgumentList.toTypedArray()))
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(keywordKey: ElixirKeywordKey): OtpErlangObject =
            keywordKey.line?.quoteAsAtom()
            ?: computeReadAction<OtpErlangObject> { identifierAtom(keywordKey.text, keywordKey) }

    @Contract(pure = true)
    @JvmStatic
    fun quote(quotableKeywordPair: QuotableKeywordPair): OtpErlangObject {
        val quotedKeywordKey = quotableKeywordPair.keywordKey.quote()
        val quotedKeywordValue = quotableKeywordPair.keywordValue.quote()

        val elements = arrayOf(quotedKeywordKey, quotedKeywordValue)

        return OtpErlangTuple(elements)
    }


    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(atUnqualifiedBracketOperation: AtUnqualifiedBracketOperation): OtpErlangObject {
        val operator = atUnqualifiedBracketOperation.atPrefixOperator
        val quotedOperator = operator.quote()

        val node = atUnqualifiedBracketOperation.node
        val identifierNodes = node.getChildren(IDENTIFIER_TOKEN_SET)

        assert(identifierNodes.size == 1)

        val identifierNode = identifierNodes[0]
        val identifier = identifierNode.text
        val metadata = metadata(atUnqualifiedBracketOperation)

        val quotedOperand = quotedVariable(identifierAtom(identifier, atUnqualifiedBracketOperation), metadata)
        val quotedContainer = quotedFunctionCall(quotedOperator, metadata, quotedOperand)

        val bracketArguments = atUnqualifiedBracketOperation.bracketArguments
        val quotedBracketArguments = bracketArguments.quote()

        // `@foo[:a]` - Elixir's `bracket_at_expr -> at_op_eol dot_bracket_identifier bracket_arg`.
        return quotedFunctionCall(
                "Elixir.Access",
                "get",
                bracketIdentifierMetadata(bracketArguments),
                quotedContainer,
                quotedBracketArguments
        )
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(atUnqualifiedNoParenthesesCall: AtUnqualifiedNoParenthesesCall<*>): OtpErlangObject {
        val operator = atUnqualifiedNoParenthesesCall.atIdentifier.atPrefixOperator
        val quotedOperator = operator.quote()

        val identifierNodes = atUnqualifiedNoParenthesesCall.atIdentifier.node.getChildren(IDENTIFIER_TOKEN_SET)

        assert(identifierNodes.size == 1)

        val identifierNode = identifierNodes[0]
        val identifier = identifierNode.text
        val quotedIdentifier = identifierAtom(identifier, atUnqualifiedNoParenthesesCall)

        val noParenthesesOneArgument = atUnqualifiedNoParenthesesCall.noParenthesesOneArgument
        val quotedArguments = noParenthesesOneArgument.quoteArguments()
        val doBlock = atUnqualifiedNoParenthesesCall.doBlock

        val quotedOperand = quotedBlockCall(
                quotedIdentifier,
                ambiguousOperatorMetadata(metadata(operator), noParenthesesOneArgument, quotedArguments, doBlock),
                quotedArguments,
                doBlock
        )

        return quotedFunctionCall(
                quotedOperator,
                metadata(operator),
                quotedOperand
        )
    }

    @Contract(pure = true)
    @JvmStatic
    private fun quotedBlockCall(
            quotedIdentifier: OtpErlangObject,
            callMetadata: OtpErlangList,
            quotedArguments: Array<OtpErlangObject>,
            doBlock: ElixirDoBlock?): OtpErlangObject {
        val quotedCombinedArguments: Array<OtpErlangObject> =
                if (doBlock != null) {
                    quotedArguments + doBlock.quoteArguments()
                } else {
                    quotedArguments
                }

        return quotedFunctionCall(
                quotedIdentifier,
                callMetadata,
                *quotedCombinedArguments
        )
    }

    @RequiresReadLock
    @JvmStatic
    fun quote(bracketOperation: BracketOperation): OtpErlangObject {
        val children = bracketOperation.children

        assert(children.size == 2)

        val container = children[0]
        val quotedMatchedExpression = (container as Quotable).quote()
        val bracketArguments = children[1] as Quotable
        val quotedBracketArguments = bracketArguments.quote()

        // Two Elixir productions land here, and they gained `from_brackets` two minors apart:
        // `bracket_expr -> access_expr bracket_arg` (`[1, 2][0]`) at 1.15.0, and
        // `bracket_at_expr -> at_op_eol access_expr bracket_arg` (`@Alias[:a]`) at 1.16.2. There is
        // dedicated PSI for `@1[...]` and `@foo[...]`, but every other `@`-prefixed operand - an
        // alias, a string, a sigil, a list, `@&1` - reaches this generic operation, so the `@` is
        // what tells the two apart.
        val metadata = if (container.isAtPrefixed()) {
            bracketIdentifierMetadata(bracketArguments)
        } else {
            bracketedExpressionMetadata(bracketArguments)
        }

        return quotedFunctionCall(
                "Elixir.Access",
                "get",
                metadata,
                quotedMatchedExpression,
                quotedBracketArguments
        )
    }

    /** Whether [this] is an `@`-prefix operation, so bracket access on it is Elixir's `bracket_at_expr`. */
    private fun PsiElement.isAtPrefixed(): Boolean = stripAccessExpression() is AtOperation

    @Contract(pure = true)
    @JvmStatic
    fun quote(dotCall: DotCall<*>): OtpErlangObject {
        val leftOperand = dotCall.firstChild as Quotable
        val quotedLeftOperand = leftOperand.quote()

        val operator = dotCall.dotInfixOperator
        val quotedOperator = operator.quote()
        val operatorMetadata = metadata(operator)

        val quotedIdentifier = quotedFunctionCall(
                quotedOperator,
                operatorMetadata,
                quotedLeftOperand
        )

        val parenthesesArgumentsList = dotCall.parenthesesArgumentsList
        val doBlock = dotCall.doBlock

        return quotedParenthesesCall(
                quotedIdentifier,
                operatorMetadata,
                parenthesesArgumentsList,
                doBlock
        )
    }

    @Contract(pure = true)
    @JvmStatic
    private fun quotedParenthesesCall(
            quotedIdentifier: OtpErlangObject,
            identifierMetadata: OtpErlangList,
            parenthesesArgumentsList: List<ElixirParenthesesArguments>,
            doBlock: ElixirDoBlock?): OtpErlangObject {
        val parenthesesArgumentsListSize = parenthesesArgumentsList.size

        assert(parenthesesArgumentsListSize > 0)

        val lastIndex = parenthesesArgumentsListSize - 1

        return parenthesesArgumentsList.foldIndexed(
                quotedIdentifier
        ) { index, quotedIdentifierForCall, parenthesesArguments ->
            val quotedParenthesesArguments = parenthesesArguments.quoteArguments()

            if (index == lastIndex) {
                quotedBlockCall(
                        quotedIdentifierForCall,
                        identifierMetadata,
                        quotedParenthesesArguments,
                        doBlock
                )
            } else {
                quotedFunctionCall(
                        quotedIdentifierForCall,
                        identifierMetadata,
                        *quotedParenthesesArguments
                )
            }
        }
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(`in`: In): OtpErlangObject {
        val children = `in`.children

        if (children.size != 3) {
            TODO("BinaryOperation expected to have 3 children (left operand, operator, right operand")
        }

        val quotedLeftOperand = (children[0] as Quotable).quote()

        val operator = children[1] as Quotable
        val quotedOperator = operator.quote()

        val quotedRightOperand = (children[2] as Quotable).quote()

        var quoted: OtpErlangObject? = null

        // @see https://github.com/elixir-lang/elixir/blob/6c288be7300509ff7b809002a3563c6a02dc13fa/lib/elixir/src/elixir_parser.yrl#L596-L597
        // @see https://github.com/elixir-lang/elixir/blob/6c288be7300509ff7b809002a3563c6a02dc13fa/lib/elixir/src/elixir_parser.yrl#L583
        if (Macro.isExpression(quotedLeftOperand)) {
            val leftExpression = quotedLeftOperand as OtpErlangTuple
            val leftOperator = leftExpression.elementAt(0)

            for (rearrangedUnaryOperator in REARRANGED_UNARY_OPERATORS) {
                /* build_op({_Kind, Line, 'in'}, {UOp, _, [Left]}, Right) when ?rearrange_uop(UOp) ->
                     {UOp, meta(Line), [{'in', meta(Line), [Left, Right]}]}; */
                if (leftOperator == rearrangedUnaryOperator) {
                    val originalUnaryOperand = when (val unaryOperatorArguments = leftExpression.elementAt(2)) {
                        is OtpErlangString -> {
                            OtpErlangLong(unaryOperatorArguments.stringValue().codePointAt(0).toLong())
                        }
                        is OtpErlangList -> unaryOperatorArguments.elementAt(0)
                        else -> TODO("Expected REARRANGED_UNARY_OPERATORS operand to be quoted as an OtpErlangString or OtpErlangList")
                    }

                    val operatorMetadata = metadata(operator)

                    quoted = quotedFunctionCall(
                            leftOperator,
                            operatorMetadata,
                            quotedFunctionCall(
                                    quotedOperator,
                                    operatorMetadata,
                                    originalUnaryOperand,
                                    quotedRightOperand
                            )
                    )
                }
            }
        }

        if (quoted == null) {
            quoted = quotedFunctionCall(
                    quotedOperator,
                    metadata(operator),
                    quotedLeftOperand,
                    quotedRightOperand
            )
        }

        return quoted
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(qualifiedAlias: QualifiedAlias): OtpErlangObject {
        val children = qualifiedAlias.children

        assert(children.size == 3)

        val quotedAlias = (children[2] as Quotable).quote() as OtpErlangTuple
        val quotedMatchedExpression = (children[0] as Quotable).quote()

        /*
         * Use line from last alias, but drop `counter: 0`
         */
        val aliasMetadata = Macro.metadata(quotedAlias)
        val lineTuple = org.elixir_lang.List.keyfind(
                aliasMetadata,
                OtpErlangAtom("line"),
                0
        ) as OtpErlangTuple
        val qualifiedAliasMetadata = OtpErlangList(
                arrayOf<OtpErlangObject>(lineTuple)
        )

        val lastAliases = Macro.callArguments(quotedAlias).elements()

        /* if both aliases, then the counter: 0 needs to be removed from the metadata data and the arguments for
           each __aliases__ need to be combined */
        val mergedArguments = if (Macro.isAliases(quotedMatchedExpression)) {
            val firstAliasList = Macro.callArguments(quotedMatchedExpression as OtpErlangTuple)
            firstAliasList.elements() + lastAliases
        } else {
            arrayOf(quotedMatchedExpression) + lastAliases
        }

        return quotedFunctionCall(
                ALIASES,
                qualifiedAliasMetadata,
                *mergedArguments
        )
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(qualifiedMultipleAliases: QualifiedMultipleAliases): OtpErlangObject {
        val children = qualifiedMultipleAliases.children

        assert(children.size == 3)

        val quotedMultipleAliases = (children[2] as Quotable).quote()
        val quotedExpression = (children[0] as Quotable).quote()
        val metadata = metadata(children[1] as Operator)

        // See https://github.com/lexmag/elixir/blob/8c57c9110301c1ee02d84b574c59feff00e14ba3/lib/elixir/src/elixir_parser.yrl#L644
        val head = quotedFunctionCall(
                ".",
                metadata,
                quotedExpression,
                MULTIPLE_ALIASES
        )

        return OtpErlangTuple(
                arrayOf(head, metadata, quotedMultipleAliases)
        )
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(qualifiedBracketOperation: QualifiedBracketOperation): OtpErlangObject {
        var quotedIdentifier = (qualifiedBracketOperation.firstChild as Quotable).quote()

        val relativeIdentifier = qualifiedBracketOperation.relativeIdentifier
        val quotedRelativeIdentifier = relativeIdentifier.quote()

        quotedIdentifier = quotedFunctionCall(
                ".",
                metadata(relativeIdentifier),
                quotedIdentifier,
                quotedRelativeIdentifier
        )

        val callMetadata = OtpErlangList(arrayOf<OtpErlangObject>(
                trueKeywordTuple("no_parens"),
                lineNumberKeywordTuple(relativeIdentifier.node)
        ))

        val quotedContainer = quotedFunctionCall(
                quotedIdentifier,
                callMetadata
        )

        val bracketArguments = qualifiedBracketOperation.bracketArguments
        val quotedBracketArguments = bracketArguments.quote()

        // `Foo.bar[:a]` - Elixir's `dot_bracket_identifier -> matched_expr dot_op bracket_identifier`.
        return quotedFunctionCall(
                "Elixir.Access",
                "get",
                bracketIdentifierMetadata(bracketArguments),
                quotedContainer,
                quotedBracketArguments
        )
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(qualifiedNoArgumentsCall: QualifiedNoArgumentsCall<*>): OtpErlangObject {
        val quotedQualifier = (qualifiedNoArgumentsCall.firstChild as Quotable).quote()

        val relativeIdentifier = qualifiedNoArgumentsCall.relativeIdentifier
        val quotedRelativeIdentifier = relativeIdentifier.quote()
        val dotOperator = dotOperator(qualifiedNoArgumentsCall)

        val quotedIdentifier = quotedFunctionCall(
                ".",
                metadata(dotOperator),
                quotedQualifier,
                quotedRelativeIdentifier
        )

        val doBlock = qualifiedNoArgumentsCall.doBlock

        val line = remoteCallMetadata(dotOperator, relativeIdentifier).elementAt(0)

        val metadataElements = if (doBlock != null) {
            arrayOf(line)
        } else {
            arrayOf(trueKeywordTuple("no_parens"), line)
        }

        val quotedBlockCallMetadata = OtpErlangList(metadataElements)

        return quotedBlockCall(
                quotedIdentifier,
                quotedBlockCallMetadata,
                emptyArray(),
                doBlock
        )
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(qualifiedNoParenthesesCall: QualifiedNoParenthesesCall<*>): OtpErlangObject {
        val quotedQualifier = (qualifiedNoParenthesesCall.firstChild as Quotable).quote()

        val relativeIdentifier = qualifiedNoParenthesesCall.relativeIdentifier
        val quotedRelativeIdentifier = relativeIdentifier.quote()
        val dotOperator = dotOperator(qualifiedNoParenthesesCall)

        val quotedIdentifier = quotedFunctionCall(
                ".",
                metadata(dotOperator),
                quotedQualifier,
                quotedRelativeIdentifier
        )

        val quotedArguments = qualifiedNoParenthesesCall.noParenthesesOneArgument.quoteArguments()
        val doBlock = qualifiedNoParenthesesCall.doBlock

        return quotedBlockCall(
                quotedIdentifier,
                remoteCallMetadata(dotOperator, relativeIdentifier),
                quotedArguments,
                doBlock
        )
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(qualifiedParenthesesCall: QualifiedParenthesesCall<*>): OtpErlangObject {
        val quotedQualifier = (qualifiedParenthesesCall.firstChild as Quotable).quote()

        val relativeIdentifier = qualifiedParenthesesCall.relativeIdentifier
        val quotedRelativeIdentifier = relativeIdentifier.quote()
        val dotOperator = dotOperator(qualifiedParenthesesCall)

        val quotedIdentifier = quotedFunctionCall(
                ".",
                metadata(dotOperator),
                quotedQualifier,
                quotedRelativeIdentifier
        )

        val parenthesesArgumentsList = qualifiedParenthesesCall.matchedParenthesesArguments.parenthesesArgumentsList
        val doBlock = qualifiedParenthesesCall.doBlock

        return quotedParenthesesCall(
                quotedIdentifier,
                remoteCallMetadata(dotOperator, relativeIdentifier),
                parenthesesArgumentsList,
                doBlock
        )
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(unqualifiedBracketOperation: UnqualifiedBracketOperation): OtpErlangObject {
        val quotedIdentifier =
            identifierAtom(unqualifiedBracketOperation.node.firstChildNode.text, unqualifiedBracketOperation)
        val quotedContainer = quotedVariable(quotedIdentifier, metadata(unqualifiedBracketOperation))

        val bracketArguments = unqualifiedBracketOperation.bracketArguments
        val quotedBracketArguments = bracketArguments.quote()

        // `foo[:a]` - Elixir's `dot_bracket_identifier -> bracket_identifier`.
        return quotedFunctionCall(
                "Elixir.Access",
                "get",
                bracketIdentifierMetadata(bracketArguments),
                quotedContainer,
                quotedBracketArguments
        )
    }

    /* Replaces `nil` argument in variables with the quoted ElixirMatchedNotParenthesesArguments.
     *
     */
    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(unqualifiedNoParenthesesCall: UnqualifiedNoParenthesesCall<*>): OtpErlangObject {
        val quotedIdentifier = identifierAtom(unqualifiedNoParenthesesCall.functionName(), unqualifiedNoParenthesesCall)
        val noParenthesesOneArgument = unqualifiedNoParenthesesCall.noParenthesesOneArgument
        val quotedArguments = noParenthesesOneArgument.quoteArguments()
        val doBlock = unqualifiedNoParenthesesCall.doBlock

        return quotedBlockCall(
                quotedIdentifier,
                ambiguousOperatorMetadata(
                        metadata(unqualifiedNoParenthesesCall),
                        noParenthesesOneArgument,
                        quotedArguments,
                        doBlock
                ),
                quotedArguments,
                doBlock
        )
    }

    /**
     * `build_call` gives a call on an `op_identifier` `ambiguous_op` only when it has exactly one argument, so a
     * `do` block, which becomes a second, drops it. The lexer only makes this a call when the identifier is an
     * `op_identifier`, so the argument opening on a unary `+` or `-` is the whole test. A keyword key such as `+:`
     * opens on the same text, but Elixir lexes it as a `kw_identifier`, which leaves the call unambiguous.
     */
    @RequiresReadLock
    private fun ambiguousOperatorMetadata(
        metadata: OtpErlangList,
        noParenthesesOneArgument: PsiElement,
        quotedArguments: Array<OtpErlangObject>,
        doBlock: PsiElement?
    ): OtpErlangList {
        if (doBlock != null || quotedArguments.size != 1) return metadata

        val first = PsiTreeUtil.getDeepestFirst(noParenthesesOneArgument)
        if (first.parent !is ElixirUnaryPrefixOperator || (first.text != "+" && first.text != "-")) return metadata

        return OtpErlangList(arrayOf(AMBIGUOUS_OP_KEYWORD_PAIR, *metadata.elements()))
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(unqualifiedNoArgumentsCall: UnqualifiedNoArgumentsCall<*>): OtpErlangObject {
        val doBlock = unqualifiedNoArgumentsCall.doBlock
        val quoted: OtpErlangObject
        val identifier = unqualifiedNoArgumentsCall.identifier

        val identifierText = identifier.text
        val quotedIdentifier = identifierAtom(identifierText, identifier)
        val callMetadata = metadata(identifier)

        // if a variable has a `do` block is no longer a variable because the do block acts as keyword arguments.
        if (doBlock != null) {
            val quotedBlockArguments = doBlock.quoteArguments()

            quoted = quotedFunctionCall(
                    quotedIdentifier,
                    callMetadata,
                    *quotedBlockArguments
            )
        } else if (identifierText == "..." &&
            !isFollowedBySlash(identifier) &&
            isAvailable(ELLIPSIS_NULLARY_CALL, identifier)
        ) {
            // Elixir 1.17.0 gave the parser an `ellipsis_op` production built with
            // `build_nullary_op`, so `...` - in a `@spec` or on its own - became a call with no
            // arguments, `{:..., meta, []}`, where before it quoted as a variable, `{:..., meta, nil}`.
            quoted = quotedFunctionCall(
                identifierText,
                callMetadata
            )
        } else {
            /* @note quotedFunctionCall cannot be used here because in the 3-tuple for function calls, the elements are
              {name, metadata, arguments}, while for an ambiguous call or variable, the elements are
              {name, metadata, context}.  Importantly, context is nil when there is no context while arguments are []
              when there are no arguments. */
            quoted = quotedVariable(
                    quotedIdentifier,
                    callMetadata
            )
        }

        return quoted
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(unqualifiedParenthesesCall: UnqualifiedParenthesesCall<*>): OtpErlangObject {
        val metadata = metadata(unqualifiedParenthesesCall)
        val quotedIdentifier =
            identifierAtom(unqualifiedParenthesesCall.node.firstChildNode.text, unqualifiedParenthesesCall)
        val parenthesesArgumentsList = unqualifiedParenthesesCall.matchedParenthesesArguments.parenthesesArgumentsList
        val doBlock = unqualifiedParenthesesCall.doBlock

        return quotedParenthesesCall(
                quotedIdentifier,
                metadata,
                parenthesesArgumentsList,
                doBlock
        )
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(parentheticalStab: ElixirParentheticalStab): OtpErlangObject {
        val stab = parentheticalStab.stab
            // @note CANNOT use quotedFunctionCall because it requires metadata and gives nil instead of [] when no
            //   arguments are given while empty block is quoted as `{__block__, [], []}`
            ?: return OtpErlangTuple(
                    arrayOf(BLOCK, emptyMetadata(parentheticalStab), OtpErlangList())
            )

        val quoted = stab.quote(emptyMetadata(parentheticalStab))

        // Elixir 1.15.0's `build_paren_stab` took over wrapping a solitary `not`/`!`, so from then
        // on it happens here rather than in every block - and with empty metadata, because that
        // clause returns before the one that adds the parentheses' own.
        return if (!rearrangesUnaryOperators(parentheticalStab) && isRearrangedUnary(quoted)) {
            blockFunctionCall(listOf(quoted), OtpErlangList())
        } else {
            quoted
        }
    }

    /** A `not` or `!` call, the `?rearrange_uop` operators. */
    private fun isRearrangedUnary(quoted: OtpErlangObject): Boolean =
        Macro.isLocalCall(quoted) &&
                (quoted as OtpErlangTuple).elementAt(0) in REARRANGED_UNARY_OPERATORS

    /** [block]'s own metadata with [extra] appended, as `Meta ++ meta_from_token_with_closing(...)` does. */
    private fun appendMetadata(block: OtpErlangTuple, extra: OtpErlangList): OtpErlangTuple =
        OtpErlangTuple(
            arrayOf(
                block.elementAt(0),
                otpErlangList((block.elementAt(1) as OtpErlangList).elements().toList() + extra.elements()),
                block.elementAt(2)
            )
        )

    private fun emptyMetadata(parentheticalStab: ElixirParentheticalStab): OtpErlangList = metadata(parentheticalStab)

    /* @note quotedFunctionCall cannot be used here because in the 3-tuple for function calls, the elements are
       {name, metadata, arguments}, while for an ambiguous call or variable, the elements are
       {name, metadata, context}.  Importantly, context is nil when there is no context while arguments are [] when
       there are no arguments. */
    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(variable: ElixirVariable): OtpErlangObject = quotedVariable(variable)

    @Contract(pure = true)
    @JvmStatic
    fun quote(operator: Operator): OtpErlangObject = OtpErlangAtom(operator.operatorTokenNode().text)

    @Contract(pure = true)
    @JvmStatic
    fun quote(prefix: Prefix): OtpErlangObject {
        val children = prefix.children

        if (children.size != 2) {
            TODO("Prefix expected to have 2 children (operator and operand")
        }

        val operator = children[0]
        val quotedOperand = (children[1] as Quotable).quote()
        val metadata = metadata(prefix)

        // Elixir's build_unary_op turns a prefix `//` into `(/)/operand`.
        if (operator is ElixirUnaryPrefixOperator && operator.operatorTokenNode().elementType == ElixirTypes.TERNARY_OPERATOR) {
            return quotedFunctionCall("/", metadata, OtpErlangTuple(arrayOf(DIVISION, metadata, NIL)), quotedOperand)
        }

        return quotedFunctionCall(
                (operator as Quotable).quote(),
                metadata,
                quotedOperand
        )
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(nullaryRangeOperation: ElixirNullaryRangeOperation): OtpErlangObject =
            quotedFunctionCall(RANGE, metadata(nullaryRangeOperation))

    /** `..(/([/: value]))`, from the `..` and first `/` of a `..//` key. */
    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(steppedRangeKeywordCall: ElixirSteppedRangeKeywordCall): OtpErlangObject {
        val node = steppedRangeKeywordCall.node
        val division = node.findChildByType(ElixirTypes.DIVISION_OPERATOR)!!

        return quotedFunctionCall(
                RANGE,
                metadata(node.firstChildNode),
                quotedFunctionCall(DIVISION, metadata(division), steppedRangeKeywordCall.noParenthesesKeywords.quote())
        )
    }

    /**
     * An interpolation's body, as a block. Elixir parses its tokens as a grammar of their own, so an empty body follows
     * the empty-file rule of quote(ElixirFile) - but its `line` is always 1, not the interpolation's.
     */
    @RequiresReadLock
    @JvmStatic
    fun quote(interpolation: ElixirInterpolation): OtpErlangObject {
        val children = interpolation.children
        val quotables = children.filter { it !is Unquoted }.map {
            it as? Quotable ?: TODO("Child, $it, must be Quotable or Unquoted")
        }
        val quotedChildren = quotables.map(Quotable::quote)
        // A newline in an empty interpolation is not parsed as an end of expression, so look for the tokens themselves.
        val emptyMetadata =
            if (quotedChildren.isEmpty() &&
                (interpolation.node.getChildren(null).any {
                    it.psi !is PsiComment && (it.textContains('\n') || it.textContains(';'))
                } || isAvailable(LINE_METADATA_ON_BLOCK, interpolation))
            ) {
                otpErlangList(keywordTuple("line", 1))
            } else {
                OtpErlangList()
            }

        return toBlock(quotedChildren, rearrangesUnaryOperators(quotables.firstOrNull()), emptyMetadata)
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(file: PsiFile): OtpErlangObject =
            (file.viewProvider.getPsi(ElixirLanguage) as ElixirFile).let { root ->
                ElixirPsiImplUtil.quote(root)
            }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(unqualifiedNoParenthesesManyArgumentsCall: ElixirUnqualifiedNoParenthesesManyArgumentsCall): OtpErlangObject {
        val quotedIdentifier = unqualifiedNoParenthesesManyArgumentsCall.identifier.quote()
        val quotedArguments = ElixirPsiImplUtil.quoteArguments(unqualifiedNoParenthesesManyArgumentsCall)

        return anchoredQuotedFunctionCall(unqualifiedNoParenthesesManyArgumentsCall, quotedIdentifier, *quotedArguments)
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(sigilHeredoc: SigilHeredocLiteral): OtpErlangObject {
        val quotedHeredocLiteral = quote(sigilHeredoc as HeredocLiteral)

        return quote(sigilHeredoc, quotedHeredocLiteral)
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(sigilLine: SigilLine): OtpErlangObject {
        val bodyChildNodes = childNodes(sigilLine.body)
        val quotedBody = quotedChildNodes(sigilLine, *bodyChildNodes)

        return quote(sigilLine, quotedBody)
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(quotableKeywordList: QuotableKeywordList): OtpErlangObject =
            OtpErlangList(quotableKeywordList.quotableKeywordPairList().map { it.quote() }.toTypedArray())

    @Contract(pure = true)
    @JvmStatic
    fun quote(heredocLineable: HeredocLineable, heredocLiteral: HeredocLiteral, prefixLength: Int): OtpErlangObject {
        val excessWhitespace = heredocLineable.heredocLinePrefix.excessWhitespace(ElixirTypes.FRAGMENT, prefixLength)
        val directChildNodes = childNodes(heredocLineable.body)

        val accumulatedChildNodes = if (excessWhitespace != null) {
            arrayOf(excessWhitespace) + directChildNodes
        } else {
            directChildNodes
        }

        return quotedChildNodes(
                heredocLiteral,
                metadata(heredocLineable),
                *accumulatedChildNodes
        )
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(heredocLiteral: HeredocLiteral): OtpErlangObject {
        val prefixLength = heredocLiteral.heredocPrefix.textLength
        val alignedNodeQueue = LinkedList<ASTNode>()
        val heredocLineList = heredocLiteral.heredocLineList

        for (line in heredocLineList) {
            queueChildNodes(line, prefixLength, alignedNodeQueue)
        }

        val mergedNodeQueue = mergeFragments(alignedNodeQueue, heredocLiteral.manager)

        return quotedChildNodes(heredocLiteral, *mergedNodeQueue.toTypedArray())
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(tuple: ElixirTuple): OtpErlangObject {
        val openingCurlies = tuple.node.getChildren(TokenSet.create(ElixirTypes.OPENING_CURLY))

        assert(openingCurlies.size == 1)

        val openingCurly = openingCurlies[0]
        val quotedChildren =
                tuple.children.asSequence().map { it as Quotable }.map(Quotable::quote).toList().toTypedArray()

        // 2-tuples are literals in quoted form
        return if (quotedChildren.size == 2) {
            OtpErlangTuple(quotedChildren)
        } else {
            quotedFunctionCall(
                    "{}",
                    metadata(openingCurly),
                    *quotedChildren
            )
        }
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(structOperation: ElixirStructOperation): OtpErlangObject {
        val children = structOperation.children

        assert(children.size == 3)

        val operator = children[0] as Operator
        val quotedOperator = operator.quote()

        val quotedName = (children[1] as Quotable).quote()
        val quotedMapArguments = (children[2] as Quotable).quote()

        return quotedFunctionCall(
                quotedOperator,
                metadata(operator),
                quotedName,
                quotedMapArguments
        )
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(stabParenthesesSignature: ElixirStabParenthesesSignature): OtpErlangObject {
        val children = stabParenthesesSignature.children

        assert(children.isNotEmpty())

        // stabParenthesesManyArguments ...
        val quotedArguments = (children[0] as QuotableArguments).quoteArguments()

        // stabParenthesesManyArguments WHEN_OPERATOR expression
        val quotedListElements = if (children.size > 1) {
            assert(children.size == 3)

            val operator = children[1] as Operator
            val quotedOperator = operator.quote()

            val quotedGuard = (children[2] as Quotable).quote()
            val quotedWhenArguments = quotedArguments + arrayOf(quotedGuard)

            val quotedWhenOperation = quotedFunctionCall(
                    quotedOperator,
                    metadata(operator),
                    *quotedWhenArguments
            )

            arrayOf(quotedWhenOperation)
        } else {
            quotedArguments
        }

        return OtpErlangList(quotedListElements)
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(stabOperation: ElixirStabOperation): OtpErlangObject {
        val operator = stabOperation.operator()
        val quotedOperator = operator.quote()

        return quotedFunctionCall(
                quotedOperator,
                metadata(operator),
                quotedLeftOperand(stabOperation),
                quotedRightOperand(stabOperation)
        )
    }

    @Contract(pure = true)
    fun quotedRightOperand(stabOperation: ElixirStabOperation): OtpErlangObject {
        val rightOperand = stabOperation.rightOperand()
        val quotedRightOperand: OtpErlangObject

        if (rightOperand != null) {
            val quotableRightOperand = rightOperand as Quotable?
            quotedRightOperand = quotableRightOperand!!.quote()
        } else {
            quotedRightOperand = NIL
        }

        return quotedRightOperand
    }

    // https://github.com/elixir-lang/elixir/blob/de39bbaca277002797e52ffbde617ace06233a2b/lib/elixir/src/elixir_parser.yrl#L277
    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(stabNoParenthesesSignature: ElixirStabNoParenthesesSignature): OtpErlangObject =
        stabNoParenthesesSignature.noParenthesesArguments.quoteArguments()
                .let { unwrapWhen(it) }
                .let(::OtpErlangList)
                .let { elixirCharList(it) }

    @Contract(pure = true)
    @JvmStatic
    fun quote(stabBody: ElixirStabBody): OtpErlangObject = stabBody.quote(OtpErlangList())

    @Contract(pure = true)
    @JvmStatic
    fun quote(stab: ElixirStab): OtpErlangObject = stab.quote(OtpErlangList())

    @Contract(pure = true)
    @JvmStatic
    fun quote(sigilModifiers: ElixirSigilModifiers): OtpErlangObject {
        val codePoints = sigilModifiers.text.codePoints().toArray()

        return if (codePoints.isEmpty()) {
            OtpErlangList()
        } else {
            elixirCharList(codePoints.toList())
        }
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(relativeIdentifier: ElixirRelativeIdentifier): OtpErlangObject {
        val children = relativeIdentifier.children

        // Only tokens
        return if (children.isEmpty()) {
            // take first node to avoid SIGNIFICANT_WHITE_SPACE after DUAL_OPERATOR
            identifierAtom(relativeIdentifier.node.firstChildNode.text, relativeIdentifier)
        } else {
            assert(children.size == 1)

            when (val child = children[0]) {
                is ElixirLine if !isAvailable(UNESCAPED_QUOTED_REMOTE_CALL_NAME, child) ->
                    OtpErlangAtom(literalQuotedRemoteCallName(child))
                is Atomable -> child.quoteAsAtom()
                else -> (child as Quotable).quote()
            }
        }
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(identifier: ElixirIdentifier): OtpErlangObject = identifierAtom(identifier.text, identifier)

    @RequiresReadLock
    private fun identifierAtom(identifier: String, element: PsiElement): OtpErlangAtom =
        OtpErlangAtom(normalizeIdentifier(identifier, element))

    /**
     * [identifier] the way the real compiler would see it at [element]'s language level: NFC, with `µ`
     * (MICRO SIGN) folded to `μ` (GREEK SMALL LETTER MU) - the same identifier-equivalence the compiler
     * itself normalises to, not merely a superficial lookalike.
     */
    @RequiresReadLock
    @JvmStatic
    fun normalizeIdentifier(identifier: String, element: PsiElement): String =
        if (identifier.any { it.code > 0x7F } && isAvailable(NORMALIZED_IDENTIFIERS, element)) {
            Normalizer.normalize(identifier, Normalizer.Form.NFC).replace('µ', 'μ')
        } else {
            identifier
        }

    @JvmStatic
    fun quote(decimalFloat: ElixirDecimalFloat): OtpErlangObject {
        val integralDigitsList = decimalFloat.decimalFloatIntegral.decimalWholeNumber.digitsList()
        val integralString = compactDigits(integralDigitsList)

        val fractionalDigitsList = decimalFloat.decimalFloatFractional.decimalWholeNumber.digitsList()
        val fractionalString = compactDigits(fractionalDigitsList)

        val decimalFloatExponent = decimalFloat.decimalFloatExponent

        return if (decimalFloatExponent != null) {
            val exponentDigitsList = decimalFloatExponent.decimalWholeNumber.digitsList()
            val exponentSignString = decimalFloatExponent.decimalFloatExponentSign?.text ?: ""
            val exponentString = compactDigits(exponentDigitsList)

            val floatString = String.format(
                    "%s.%se%s%s",
                    integralString,
                    fractionalString,
                    exponentSignString,
                    exponentString
            )

            if (integralDigitsList.inBase() && fractionalDigitsList.inBase() && exponentDigitsList.inBase()) {
                Double.parseDouble(floatString).let(::OtpErlangDouble)
            } else {
                // Convert parser error to runtime ArgumentError
                quotedFunctionCall(
                        "String",
                        "to_float",
                        metadata(decimalFloat),
                        elixirString(floatString)
                )
            }
        } else {
            val floatString = String.format(
                    "%s.%s",
                    integralString,
                    fractionalString
            )

            if (integralDigitsList.inBase() && fractionalDigitsList.inBase()) {
                Double.parseDouble(floatString).let(::OtpErlangDouble)
            } else {
                // Convert parser error to runtime ArgumentError
                quotedFunctionCall(
                        "String",
                        "to_float",
                        metadata(decimalFloat),
                        elixirString(floatString)
                )
            }
        }
    }

    @JvmStatic
    fun quote(@Suppress("UNUSED_PARAMETER") emptyParentheses: ElixirEmptyParentheses): OtpErlangObject =
         emptyBlock()

    @RequiresReadLock
    @JvmStatic
    fun quote(file: ElixirFile): OtpErlangObject {
        val quotedChildren = LinkedList<OtpErlangObject>()

        file.acceptChildren(
                object : PsiElementVisitor() {
                    override fun visitElement(element: PsiElement) {
                        if (element is Quotable) {
                            visitQuotable((element as Quotable?)!!)
                        } else if (!element.isUnquoted()) {
                            TODO("Don't know how to visit $element")
                        }

                        super.visitElement(element)
                    }

                    fun visitQuotable(child: Quotable) {
                        val quotedChild = child.quote()
                        quotedChildren.add(quotedChild)
                    }
                }
        )

        // @see https://github.com/elixir-lang/elixir/blob/de39bbaca277002797e52ffbde617ace06233a2b/lib/elixir/src/elixir_parser.yrl#L76-L79
        // `grammar -> eoe` has always had `line`, so a file of only `;` or newlines does too; `'$empty'` only gained
        // it with LINE_METADATA_ON_BLOCK.
        val emptyMetadata =
            if (PsiTreeUtil.getChildOfType(file, ElixirEndOfExpression::class.java) != null ||
                isAvailable(LINE_METADATA_ON_BLOCK, file)
            ) {
                metadata(file)
            } else {
                OtpErlangList()
            }
        return toBlock(quotedChildren, rearrangesUnaryOperators(file), emptyMetadata)
    }

    @RequiresReadLock
    @Contract(pure = true)
    @JvmStatic
    fun quote(sigil: Sigil, quotedContent: OtpErlangObject): OtpErlangObject {
        val sigilLine = lineNumberKeywordTuple(sigil.node)
        val sigilBinaryMetadataArray =
                sigil.indentation()
                        ?.let { indentation -> arrayOf<OtpErlangObject>(keywordTuple("indentation", indentation), sigilLine) }
                        ?: arrayOf<OtpErlangObject>(sigilLine)
        val sigilBinaryMetadata = OtpErlangList(sigilBinaryMetadataArray)
        val sigilBinary = when (quotedContent) {
            is OtpErlangTuple -> {
                assert(quotedContent.arity() == 3)
                OtpErlangTuple(arrayOf(quotedContent.elementAt(0), sigilBinaryMetadata, quotedContent.elementAt(2)))
            }
            else -> quotedFunctionCall("<<>>", sigilBinaryMetadata, quotedContent)
        }

        val sigilName = sigil.sigilName()
        val quotedModifiers = sigil.sigilModifiers.quote()
        val sigilMetadata = OtpErlangList(arrayOf<OtpErlangObject>(delimiterKeywordTuple(sigil.sigilDelimiter()), sigilLine))

        return quotedFunctionCall(
                "sigil_$sigilName",
                sigilMetadata,
                sigilBinary,
                quotedModifiers
        )
    }

    @Contract(pure = true)
    @JvmStatic
    fun quote(wholeNumber: WholeNumber): OtpErlangObject {
        val digitsList = wholeNumber.digitsList()
        val digitsListString = digitsList.textToString()
        val base = wholeNumber.base()

        return if (digitsList.inBase()) {
            quoteInBase(digitsListString, base)
        } else {
            /* 0 elements is invalid in native Elixir and can be emulated as `String.to_integer("", base)` while
               2 elements implies at least one element is invalidDigitsElementType which is invalid in native Elixir and
               can be emulated as String.to_integer(<all-digits>, base) so that it raises an ArgumentError on the invalid
               digits */
            quotedFunctionCall(
                    "String",
                    "to_integer",
                    metadata(wholeNumber),
                    elixirString(digitsListString),
                    OtpErlangLong(base.toLong())
            )
        }
    }

    // Private Functions

    private fun anchoredQuotedFunctionCall(
            anchor: PsiElement,
            quotedIdentifier: OtpErlangObject,
            vararg quotedArguments: OtpErlangObject
    ): OtpErlangObject {
        val metadata = if (Macro.isExpression(quotedIdentifier)) {
            val expression = quotedIdentifier as OtpErlangTuple
            /* Grab metadata from quotedIdentifier so line of quotedFunctionCall is line of identifier or `.` in
               identifier, which can differ from the line of quotable call when there are newlines on either side of
               `.`. */
            Macro.metadata(expression)
        } else {
            metadata(anchor)
        }

        return quotedFunctionCall(
                quotedIdentifier,
                metadata,
                *quotedArguments
        )
    }

    private fun trueKeywordTuple(key: String): OtpErlangTuple = keywordTuple(OtpErlangAtom(key), OtpErlangAtom(true))

    private fun keywordTuple(key: String, value: Int): OtpErlangTuple {
        val keyAtom = OtpErlangAtom(key)
        val valueInt = OtpErlangInt(value)

        return keywordTuple(keyAtom, valueInt)
    }

    private fun delimiterKeywordTuple(delimiter: String): OtpErlangTuple =
            keywordTuple(OtpErlangAtom("delimiter"), OtpErlangBinary(delimiter.toByteArray()))

    private fun keywordTuple(key: OtpErlangAtom, value: OtpErlangObject): OtpErlangTuple =
            OtpErlangTuple(arrayOf(key, value))

    /* Returns the 0-indexed line number for the element */
    private fun lineNumber(node: ASTNode): Int {
        val psi = node.psi
        val documentLine = psi.document()!!.getLineNumber(node.startOffset)
        val uncounted = psi.containingFile?.let { uncountedNewlines(it, node) } ?: return documentLine

        if (uncounted.isEmpty()) return documentLine

        val languageLevel = languageLevelFor(psi)
        val before = { offsets: IntArray -> offsets.count { it < node.startOffset } }

        return documentLine -
                (
                    if (ESCAPED_NEWLINE_COUNTED_IN_LITERAL_SIGIL_LINE.isSufficient(languageLevel)) 0
                    else before(uncounted.literalSigilLine)
                ) -
                (if (NEWLINE_COUNTED_IN_CHARACTER.isSufficient(languageLevel)) 0 else before(uncounted.character))
    }

    /** Offsets of newlines that older tokenizers consumed without advancing the line. */
    private class UncountedNewlines(val literalSigilLine: IntArray, val character: IntArray) {
        fun isEmpty(): Boolean = literalSigilLine.isEmpty() && character.isEmpty()
    }

    /**
     * Cached per file, as every line in a quoted file asks.
     *
     * Walks [node]'s own tree rather than [file]'s: building a stub can quote while the file's tree loads from its
     * stubs, and asking the file for its tree then loads it again, which the platform stops with an exception.
     */
    private fun uncountedNewlines(file: PsiFile, node: ASTNode): UncountedNewlines =
        // A stub build can quote, where the platform flags a plain cached value. The stub-build value is held on the file
        // itself: the platform's other overloads hold it on the file's node, which loads a stub-backed tree.
        if (StubBuildCachedValuesManager.isBuildingStubs) {
            StubBuildCachedValuesManager.getCachedValueIfBuildingStubs(file, UNCOUNTED_NEWLINES, node, ::scanUncountedNewlines)
        } else {
            CachedValuesManager.getCachedValue(file) {
                // Not `file` itself: a physical PSI dependency asks for InjectedLanguageManager, which ParsingTestCase's
                // mock project does not register.
                CachedValueProvider.Result.create(scanUncountedNewlines(node), ModificationTracker { file.modificationStamp })
            }
        }

    private val UNCOUNTED_NEWLINES = Key.create<StubBuildCachedValue<UncountedNewlines>>("ELIXIR_UNCOUNTED_NEWLINES.stub.building")

    private fun scanUncountedNewlines(node: ASTNode): UncountedNewlines {
        val literalSigilLine = mutableListOf<Int>()
        val character = mutableListOf<Int>()
        val root = generateSequence(node) { it.treeParent }.last()
        var current: ASTNode? = root

        while (current != null) {
            when (current.elementType) {
                ElixirTypes.ESCAPED_EOL -> {
                    val parent = current.treeParent

                    if (parent?.elementType == ElixirTypes.CHAR_TOKEN) {
                        character.add(current.startOffset)
                    } else if (parent?.treeParent?.psi.let { it is SigilLine && it !is Interpolated }) {
                        literalSigilLine.add(current.startOffset)
                    }
                }

                ElixirTypes.CHAR_TOKEN ->
                    if (current.lastChildNode.let { it.elementType != ElixirTypes.ESCAPED_EOL && it.textContains('\n') }) {
                        character.add(current.startOffset)
                    }
            }

            var next = current.firstChildNode
            var ancestor: ASTNode = current

            while (next == null && ancestor !== root) {
                next = ancestor.treeNext
                ancestor = ancestor.treeParent
            }

            current = next
        }

        return UncountedNewlines(literalSigilLine.toIntArray(), character.toIntArray())
    }

    private fun lineNumberKeywordTuple(node: ASTNode): OtpErlangTuple =
            keywordTuple(
                    "line",
                    lineNumber(node) + 1
            )

    @JvmStatic
    fun quotedFunctionCall(
            quotedQualifiedIdentifier: OtpErlangObject,
            metadata: OtpErlangList,
            vararg arguments: OtpErlangObject
    ): OtpErlangTuple = OtpErlangTuple(
            arrayOf(quotedQualifiedIdentifier, metadata, quotedFunctionArguments(*arguments))
    )

    @JvmStatic
    fun metadata(node: ASTNode): OtpErlangList = otpErlangList(lineNumberKeywordTuple(node))
    @JvmStatic
    fun metadata(operator: Operator): OtpErlangList = metadata(operator.operatorTokenNode())
    @JvmStatic
    fun metadata(element: PsiElement): OtpErlangList = metadata(element.node)

    /**
     * `Access.get/2` metadata for bracket access on an expression - `[1, 2][0]`, Elixir's
     * `bracket_expr -> access_expr bracket_arg`. Carries `from_brackets` from 1.15.0, two minors
     * before the other four bracket forms do.
     */
    @RequiresReadLock
    private fun bracketedExpressionMetadata(bracketArguments: PsiElement): OtpErlangList =
        bracketMetadata(
            bracketArguments,
            isAvailable(FROM_BRACKETS_ON_BRACKETED_EXPRESSION, bracketArguments)
        )

    /**
     * `Access.get/2` metadata for the four bracket forms Elixir 1.16.2 brought in line with
     * [bracketedExpressionMetadata]: `foo[:a]`, `Foo.bar[:a]`, `@foo[:a]` and `@1[:a]`.
     */
    @RequiresReadLock
    private fun bracketIdentifierMetadata(bracketArguments: PsiElement): OtpErlangList =
        bracketMetadata(
            bracketArguments,
            isAvailable(FROM_BRACKETS_ON_EVERY_BRACKET_FORM, bracketArguments)
        )

    /** `from_brackets` leads the keyword list, as `meta_with_from_brackets` prepends it. */
    private fun bracketMetadata(bracketArguments: PsiElement, fromBrackets: Boolean): OtpErlangList =
        if (fromBrackets) {
            OtpErlangList(
                arrayOf<OtpErlangObject>(
                    trueKeywordTuple("from_brackets"),
                    lineNumberKeywordTuple(bracketArguments.node)
                )
            )
        } else {
            metadata(bracketArguments)
        }

    /**
     * The `Kernel.to_string/1` call that interpolation quotes to, tagged `from_interpolation` from
     * Elixir 1.16.0 on.
     *
     * Only the outer call is tagged; the inner `.` call that names `Kernel.to_string` keeps the
     * plain metadata, matching `{{:., [line: 1], [Kernel, :to_string]},
     * [from_interpolation: true, line: 1], [...]}`.
     */
    @RequiresReadLock
    @JvmStatic
    fun quotedInterpolationCall(
        interpolation: ElixirInterpolation,
        module: String,
        identifier: String,
        metadata: OtpErlangList,
        vararg arguments: OtpErlangObject
    ): OtpErlangTuple {
        if (!isAvailable(FROM_INTERPOLATION, interpolation)) {
            return quotedFunctionCall(module, identifier, metadata, *arguments)
        }

        val quotedQualifiedIdentifier = quotedFunctionCall(
            ".",
            metadata,
            OtpErlangAtom(module),
            OtpErlangAtom(identifier)
        )

        return quotedFunctionCall(
            quotedQualifiedIdentifier,
            OtpErlangList(
                arrayOf(trueKeywordTuple("from_interpolation"), *metadata.elements())
            ),
            *arguments
        )
    }

    @JvmStatic
    fun quotedFunctionCall(
            identifier: String,
            metadata: OtpErlangList,
            vararg arguments: OtpErlangObject
    ): OtpErlangTuple =
            quotedFunctionCall(
                    OtpErlangAtom(identifier),
                    metadata,
                    *arguments
            )

    @JvmStatic
    fun quotedFunctionCall(
            module: String,
            identifier: String,
            metadata: OtpErlangList,
            vararg arguments: OtpErlangObject
    ): OtpErlangTuple {
        val quotedQualifiedIdentifier = quotedFunctionCall(
                ".",
                metadata,
                OtpErlangAtom(module),
                OtpErlangAtom(identifier)
        )

        return quotedFunctionCall(
                quotedQualifiedIdentifier,
                metadata,
                *arguments
        )
    }

    /*
     * Erlang will automatically stringify a list that is just a list of LATIN-1 printable code
     * points.
     * OtpErlangString and OtpErlangList are not equal when they have the same content, so to check against
     * Elixir.Code.string_to_quoted, this code must determine if Erlang would return an OtpErlangString instead
     * of OtpErlangList and do the same.
     */
    private fun quotedFunctionArguments(vararg arguments: OtpErlangObject): OtpErlangObject =
            elixirCharList(OtpErlangList(arguments))

    @Contract(pure = true)
    private fun quotedLeftOperand(stabOperation: ElixirStabOperation): OtpErlangObject =
        stabOperation.leftOperand()?.quote() ?:
        // when there is not signature before `->`.
        OtpErlangList()

    @JvmStatic
    fun childNodes(parentElement: PsiElement): Array<ASTNode> = parentElement.node.getChildren(null)

    fun quotedChildNodes(parent: Parent, vararg children: ASTNode): OtpErlangObject =
            quotedChildNodes(parent, metadata(parent), *children)

    private fun quotedChildNodes(parent: Parent, metadata: OtpErlangList, vararg children: ASTNode): OtpErlangObject {
        val quoted: OtpErlangObject

        val childCount = children.size

        if (childCount == 0) {
            quoted = parent.quoteEmpty()
        } else {
            val quotedParentList = LinkedList<OtpErlangObject>()
            var codePointList: MutableList<Int>? = null

            for (child in children) {
                val elementType = child.elementType

                if ((elementType === ElixirTypes.FRAGMENT) || (elementType === ElixirTypes.EOL)) {
                    codePointList = parent.addFragmentCodePoints(codePointList, child)
                } else if (elementType === ElixirTypes.ESCAPED_CHARACTER) {
                    codePointList = parent.addEscapedCharacterCodePoints(codePointList, child)
                } else if (elementType === ElixirTypes.ESCAPED_EOL) {
                    codePointList = parent.addEscapedEOL(codePointList, child)
                } else if (elementType === ElixirTypes.ESCAPED_HEREDOC_TERMINATOR || elementType === ElixirTypes.ESCAPED_LINE_TERMINATOR) {
                    codePointList = parent.addEscapedTerminator(codePointList, child)
                } else if (elementType === ElixirTypes.HEXADECIMAL_ESCAPE_PREFIX) {
                    codePointList = addChildTextCodePoints(codePointList, child)
                } else if (elementType === ElixirTypes.INTERPOLATION) {
                    // `build_string([], Output) -> Output` drops an empty *buffer*, and below 1.12 a
                    // `\` ending a line was consumed into one, so the part never existed.
                    if (codePointList != null) {
                        if (codePointList.isNotEmpty() ||
                            isAvailable(ESCAPED_NEWLINE_KEPT_IN_EXTRACTED_BUFFER, parent)
                        ) {
                            quotedParentList.add(elixirString(codePointList))
                        }

                        codePointList = null
                    }

                    if (parent is HeredocLiteral && quotedParentList.isEmpty() &&
                        isAvailable(EMPTY_LEADING_HEREDOC_SEGMENT, parent)) {
                        quotedParentList.add(elixirString(""))
                    }

                    val childElement = child.psi as ElixirInterpolation
                    quotedParentList.add(parent.quoteInterpolation(childElement))
                } else if (elementType === ElixirTypes.QUOTE_HEXADECIMAL_ESCAPE_SEQUENCE || elementType === ElixirTypes.SIGIL_HEXADECIMAL_ESCAPE_SEQUENCE) {
                    val escapedByte = (child.psi as? ElixirQuoteHexadecimalEscapeSequence)?.let { escapedByte(it) }

                    codePointList = if (escapedByte != null) {
                        (codePointList ?: mutableListOf()).apply { add(RAW_BYTE_OFFSET + escapedByte) }
                    } else {
                        parent.addHexadecimalEscapeSequenceCodePoints(codePointList, child)
                    }
                } else {
                    TODO("Can't quote $child")
                }
            }

            quoted = if (codePointList != null && quotedParentList.isEmpty()) {
                parent.quoteLiteral(codePointList)
            } else {
                if (codePointList != null &&
                    (codePointList.isNotEmpty() || isAvailable(ESCAPED_NEWLINE_KEPT_IN_EXTRACTED_BUFFER, parent))
                ) {
                    quotedParentList.add(elixirString(codePointList))
                }

                parent.quoteBinary(metadata, quotedParentList)
            }
        }

        return quoted
    }

    /**
     * `unescape_hex` appends one byte, so `"\xC3\xA9"` is `"é"`; Elixir 1.11's deprecated `\xH` and `\x{H*}` are code
     * points. Not decided in [Parent.addHexadecimalEscapeSequenceCodePoints]: atom resolution builds a `String` from
     * that list, which cannot hold [RAW_BYTE_OFFSET].
     */
    private fun escapedByte(sequence: ElixirQuoteHexadecimalEscapeSequence): Int? {
        if (sequence.hexadecimalEscapePrefix.text != "\\x") return null
        val digits = sequence.openHexadecimalEscapeSequence?.text?.takeIf { it.length == 2 } ?: return null

        return digits.toInt(16).takeIf { it >= 0x80 }
    }

    private fun dotOperator(qualified: PsiElement): ElixirDotInfixOperator =
        PsiTreeUtil.getChildOfType(qualified, ElixirDotInfixOperator::class.java)!!

    /** The call's own metadata, which differs from its `.`'s only when a newline separates the dot from the name. */
    @RequiresReadLock
    private fun remoteCallMetadata(dotOperator: ElixirDotInfixOperator, relativeIdentifier: PsiElement): OtpErlangList {
        val nameMetadata = metadata(relativeIdentifier)
        val dotMetadata = metadata(dotOperator)

        return if (nameMetadata == dotMetadata || isAvailable(REMOTE_CALL_ON_NAME_LINE, relativeIdentifier)) {
            nameMetadata
        } else {
            dotMetadata
        }
    }

    /**
     * A quoted remote call's name as Elixir 1.17 and earlier kept it: `extract` without unescaping, which still drops
     * the `\` before the terminator but keeps every other escape as written.
     */
    private fun literalQuotedRemoteCallName(line: ElixirLine): String {
        val text = line.lineBody?.text.orEmpty()
        val terminator = if (line.isCharList) '\'' else '"'
        val name = StringBuilder()
        var index = 0

        while (index < text.length) {
            if (text[index] == '\\' && index + 1 < text.length) {
                if (text[index + 1] != terminator) name.append('\\')
                name.append(text[index + 1])
                index += 2
            } else {
                name.append(text[index])
                index += 1
            }
        }

        return name.toString()
    }

    /**
     * `elixir_tokenizer` turns an operator followed by `/`, after any horizontal space, into an identifier, so `...` in
     * `&.../0` or `... / 2` is a variable in every version rather than the 1.17 nullary operator.
     */
    private fun isFollowedBySlash(element: PsiElement): Boolean =
        generateSequence(PsiTreeUtil.nextLeaf(element)) { PsiTreeUtil.nextLeaf(it) }
            .firstOrNull { it !is PsiWhiteSpace || it.textContains('\n') }
            ?.text
            ?.startsWith("/") == true

    @RequiresReadLock
    @Contract(pure = true)
    private fun quotedVariable(variable: PsiElement): OtpErlangObject =
            quotedVariable(
                    identifierAtom(variable.text, variable),
                    metadata(variable)
            )

    @Contract(pure = true)
    fun quotedVariable(identifier: String, metadata: OtpErlangList): OtpErlangObject =
            quotedVariable(
                    OtpErlangAtom(identifier),
                    metadata
            )

    @Contract(pure = true)
    private fun quotedVariable(
            quotedIdentifier: OtpErlangObject,
            metadata: OtpErlangList,
            context: OtpErlangObject = NIL
    ): OtpErlangObject =
            OtpErlangTuple(
                    arrayOf(quotedIdentifier, metadata, context)
            )

    private fun queueChildNodes(
        line: HeredocLineable,
        prefixLength: Int,
        heredocDescendantNodes: Queue<ASTNode>
    ) {
        val excessWhitespace = line.heredocLinePrefix.excessWhitespace(ElixirTypes.FRAGMENT, prefixLength)

        if (excessWhitespace != null) {
            heredocDescendantNodes.add(excessWhitespace)
        }

        val childNodes = childNodes(line.body)
        Collections.addAll(heredocDescendantNodes, *childNodes)

        heredocDescendantNodes.add(line.lastChild.node)
    }

    @Contract(pure = true)
    private fun mergeFragments(
            unmergedNodes: Deque<ASTNode>,
            manager: PsiManager
    ): Queue<ASTNode> {
        val mergedNodes = LinkedList<ASTNode>()
        var fragmentStringBuilder: StringBuilder? = null

        for (unmergedNode in unmergedNodes) {
            if (unmergedNode.elementType === ElixirTypes.FRAGMENT) {
                if (fragmentStringBuilder == null) {
                    fragmentStringBuilder = StringBuilder()
                }

                val fragment = unmergedNode.text
                fragmentStringBuilder.append(fragment)
            } else {
                addMergedFragments(mergedNodes, fragmentStringBuilder, manager)
                fragmentStringBuilder = null
                mergedNodes.add(unmergedNode)
            }
        }

        addMergedFragments(mergedNodes, fragmentStringBuilder, manager)

        return mergedNodes
    }

    private fun addMergedFragments(
            mergedNodes: Queue<ASTNode>,
            fragmentStringBuilder: StringBuilder?,
            manager: PsiManager
    ) {
        if (fragmentStringBuilder != null) {
            val charListFragment = Factory.createSingleLeafElement(
                    ElixirTypes.FRAGMENT,
                    fragmentStringBuilder.toString(),
                    0,
                    fragmentStringBuilder.length, null,
                    manager
            )
            mergedNodes.add(charListFragment)
        }
    }

    /**
     * Unwraps `when` from left end of noParenthesesArguments in stabNoParenthesesSignature so that the when acts as a guard for the signature instead of a guard on the last positional argument.
     *
     * Based on Elixir's `unwrap_when`:
     * - [`unwrap_when` in `stab_expr`](https://github.com/elixir-lang/elixir/blob/de39bbaca277002797e52ffbde617ace06233a2b/lib/elixir/src/elixir_parser.yrl#L276-L277)
     * - [`unwrap_when`](https://github.com/elixir-lang/elixir/blob/de39bbaca277002797e52ffbde617ace06233a2b/lib/elixir/src/elixir_parser.yrl#L716-L722)
     */
    private fun unwrapWhen(quotedArguments: Array<OtpErlangObject>): Array<OtpErlangObject> {
        // https://github.com/elixir-lang/elixir/blob/de39bbaca277002797e52ffbde617ace06233a2b/lib/elixir/src/elixir_parser.yrl#L717
        val last = quotedArguments[quotedArguments.size - 1]
        // https://github.com/elixir-lang/elixir/blob/de39bbaca277002797e52ffbde617ace06233a2b/lib/elixir/src/elixir_parser.yrl#L720-L721
        var unwrapped = quotedArguments

        // { _, _, _}
        if (Macro.isExpression(last)) {
            val expression = last as OtpErlangTuple
            val receiver = expression.elementAt(0)

            // {'when', _, _ }
            if (receiver == WHEN) {
                // Operands that are all small integers, as in `3 when 4`, arrive as an OtpErlangString.
                val operands = Macro.callArguments(expression)

                // Have to check for two element so that unwrap_when doesn't happen recursively as the unwrapped version of when will have more than 2 arguments, which is only seen in stabSignatures.
                // [_, _] = End
                if (operands.arity() == 2) {
                    val unwrappedArguments =
                            quotedArguments.slice(0 until quotedArguments.size - 1).toTypedArray() +
                                    operands.elements()

                    unwrapped = arrayOf(
                            quotedFunctionCall(
                                    receiver,
                                    Macro.metadata(expression),
                                    *unwrappedArguments
                            )
                    )
                }
            }
        }

        return unwrapped
    }

    private fun compactDigits(digitsList: List<Digits>): String {
        val builtString = StringBuilder()

        for (digits in digitsList) {
            builtString.append(digits.text)
        }

        return builtString.toString()
    }

    private fun quoteInBase(string: String, base: Int): OtpErlangObject =
        BigInteger(string, base).let(::OtpErlangLong)

    /**
     * Converts group of separated expressions into a block or returns the single expression.
     *
     * See https://github.com/elixir-lang/elixir/blob/de39bbaca277002797e52ffbde617ace06233a2b/lib/elixir/src/elixir_parser.yrl#L724-L725
     */
    @Contract(pure = true)
    private fun toBlock(
        quotedChildren: List<OtpErlangObject>,
        rearrangeUnaryOperators: Boolean,
        emptyMetadata: OtpErlangList = OtpErlangList()
    ): OtpErlangObject =
            when (quotedChildren.size) {
                0 -> otpErlangTuple(BLOCK, emptyMetadata, OtpErlangList())
                // A file's own metadata never reaches a single- or multi-expression body (only an
                // empty one, above) - see quote(ElixirFile) - so there is nothing here for the merge
                // below to accumulate; pass `false` rather than resolving a language level for nothing.
                1 -> buildBlock(quotedChildren, OtpErlangList(), rearrangeUnaryOperators, false)
                else -> blockFunctionCall(quotedChildren, OtpErlangList())
            }

    /**
     * Whether a solitary `not`/`!` in a block built from [element] takes a `__block__` wrapper.
     *
     * Elixir 1.15.0 moved that wrapper out of `build_block` - which a file, a stab body and an
     * interpolation all go through - and into `build_paren_stab`, so from then on only a
     * parenthesised single unary expression carries it. `unquote_splicing` is unaffected.
     */
    @RequiresReadLock
    internal fun rearrangesUnaryOperators(element: PsiElement?): Boolean =
        SOLITARY_UNARY_WRAPPED_IN_EVERY_BLOCK.isSufficient(
            element?.let(::languageLevelFor) ?: ElixirLanguageLevel.FALLBACK
        )

    /**
     * Whether a solitary expression that already quotes to a `__block__` - from a nested
     * parenthetical stab's own `rearrange_uop` wrap, `unquote_splicing`, or several expressions -
     * picks up [element]'s own metadata too, rather than being passed through unchanged.
     *
     * Only [quote] for [ElixirParentheticalStab] ever calls [buildBlock] with non-empty metadata for
     * a single-expression body - a file's is always empty (see [toBlock]) and a do-block's own
     * metadata exists only with [LINE_METADATA_ON_BLOCK], after this window closes - so
     * gating on it here is exactly the parenthetical-stab case `build_paren_stab` gates on, without
     * needing to know the caller.
     */
    @RequiresReadLock
    internal fun mergesEnclosingParenMetadataOntoBlock(element: PsiElement?): Boolean =
        ENCLOSING_PARENS_MERGE_BLOCK_METADATA.isSufficient(
            element?.let(::languageLevelFor) ?: ElixirLanguageLevel.FALLBACK
        )

    private fun emptyBlock() =
             otpErlangTuple(
                     BLOCK, OtpErlangList(), OtpErlangList()
             )

    @Contract(pure = true)
    private fun blockFunctionCall(quotedChildren: List<OtpErlangObject>, metadata: OtpErlangList): OtpErlangTuple =
            quotedFunctionCall(
                    BLOCK,
                    metadata,
                    *quotedChildren.toTypedArray()
            )

    /**
     * Builds a block for stab bodies.  Unlike `toBlock`, handles rearranging unary operations `not` and `!` and putting
     * solitary `unquote_splicing` calls in blocks.
     *
     * @param quotedChildren
     */
    @Contract(pure = true)
    internal fun buildBlock(
        quotedChildren: List<OtpErlangObject>,
        metadata: OtpErlangList,
        rearrangeUnaryOperators: Boolean,
        mergesEnclosingParenMetadataOntoBlock: Boolean = false
    ): OtpErlangObject =
            when (quotedChildren.size) {
                0 -> NIL
                1 -> {
                    val quotedChild = quotedChildren.first()

                    // @see https://github.com/elixir-lang/elixir/blob/de39bbaca277002797e52ffbde617ace06233a2b/lib/elixir/src/elixir_parser.yrl#L588
                    if (Macro.isLocalCall(quotedChild)) {
                        // @see https://github.com/elixir-lang/elixir/blob/de39bbaca277002797e52ffbde617ace06233a2b/lib/elixir/src/elixir_parser.yrl#L547
                        when ((quotedChild as OtpErlangTuple).elementAt(0)) {
                            // `unquote_splicing` wraps in every version; `not` and `!` stopped
                            // wrapping here in 1.15.0 and wrap only inside parentheses from then on.
                            EXCLAMATION_POINT, NOT ->
                                if (rearrangeUnaryOperators) {
                                    blockFunctionCall(quotedChildren, metadata)
                                } else {
                                    quotedChild
                                }
                            UNQUOTE_SPLICING ->
                                blockFunctionCall(quotedChildren, metadata)
                            // The sole child is already a `__block__` - typically a nested
                            // parenthetical stab that rearranged a `not`/`!`, spliced an
                            // `unquote_splicing`, or held several expressions itself. Below 1.17.0,
                            // this level's own metadata is appended to what is already there rather
                            // than discarded, so parentheses nested around the same block each add
                            // one more entry - see ENCLOSING_PARENS_MERGE_BLOCK_METADATA.
                            BLOCK ->
                                if (mergesEnclosingParenMetadataOntoBlock) {
                                    appendMetadata(quotedChild, metadata)
                                } else {
                                    quotedChild
                                }
                            else ->
                                quotedChild
                        }
                    } else {
                        quotedChild
                    }
                }
                else -> blockFunctionCall(quotedChildren, metadata)
            }
}
