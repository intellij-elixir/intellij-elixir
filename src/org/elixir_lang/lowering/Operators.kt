package org.elixir_lang.lowering

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import org.elixir_lang.language_level.ElixirLanguageFeature.AMBIGUOUS_DUAL_OPERATOR_CALL
import org.elixir_lang.language_level.ElixirLanguageFeature.ELLIPSIS_NULLARY_CALL
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_NEWLINE_AS_SPACE
import org.elixir_lang.language_level.ElixirLanguageFeature.IN_OF_NOT_IN_ON_ITS_OWN_LINE
import org.elixir_lang.language_level.ElixirLanguageFeature.NEWLINES_AFTER_MATCH_OPERATOR
import org.elixir_lang.language_level.ElixirLanguageFeature.NEWLINES_ON_NOT_IN
import org.elixir_lang.language_level.ElixirLanguageFeature.OPERATOR_ON_NOT_IN
import org.elixir_lang.language_level.ElixirLanguageFeature.REARRANGED_UNARY_KEEPS_ITS_METADATA
import org.elixir_lang.psi.ElixirNullaryRangeOperation
import org.elixir_lang.psi.ElixirSteppedRangeKeywordCall
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.psi.Operator
import org.elixir_lang.psi.UnqualifiedNoArgumentsCall
import org.elixir_lang.psi.impl.operatorTokenNode
import org.elixir_lang.psi.operation.In
import org.elixir_lang.psi.operation.Infix
import org.elixir_lang.psi.operation.Match
import org.elixir_lang.psi.operation.NotIn
import org.elixir_lang.psi.operation.Prefix
import org.elixir_lang.psi.operation.Ternary

/** Operators of every precedence, captures, `..` and `..//`, and the operator tokens their parents read. */
internal fun Lowering.operator(element: PsiElement): ElixirAst =
    when (element) {
        is ElixirNullaryRangeOperation -> ElixirAst.Call(meta(element, location(element)), atom(element, ".."), emptyList())
        is ElixirSteppedRangeKeywordCall -> steppedRangeKeywordCall(element)
        is Operator -> named(element)
        is NotIn -> notIn(element)
        is In -> `in`(element)
        is Ternary -> ternary(element)
        is Infix -> infix(element)
        is Prefix -> prefix(element)
        else -> unlowered(element)
    }

private fun Lowering.infix(infix: Infix): ElixirAst {
    val left = infix.leftOperand() ?: return broken(infix)
    val right = infix.rightOperand() ?: return broken(infix)

    return ambiguousDualOperatorCall(infix, left, right) ?: binary(infix, lower(left), lower(right))
}

private fun Lowering.binary(infix: Infix, left: ElixirAst, right: ElixirAst): ElixirAst {
    val operator = infix.operator().operatorTokenNode()

    return ElixirAst.Call(
        meta(
            infix,
            operatorNewlines(
                infix.node.firstChildNode,
                operator,
                afterCounts = infix !is Match || isAvailable(NEWLINES_AFTER_MATCH_OPERATOR)
            ),
            location(operator)
        ),
        atom(infix.operator(), operator.text),
        listOf(left, right)
    )
}

/** From 1.17, `a -[1]` is `a(-[1])`. */
private fun Lowering.ambiguousDualOperatorCall(infix: Infix, left: PsiElement, right: PsiElement): ElixirAst? {
    val operator = infix.operator()
    val sign = operator.text.singleOrNull()?.takeIf { it == '+' || it == '-' } ?: return null

    val beforeOperator = generateSequence(operator.prevSibling) { it.prevSibling }
        .takeWhile { it is PsiWhiteSpace }
        .toList()
        .ifEmpty { return null }
    val afterIdentifier = beforeOperator.last().text.first()
    if (afterIdentifier != ' ' && afterIdentifier != '\t') return null
    if (beforeOperator.any { '\\' in it.text } && !isAvailable(ESCAPED_NEWLINE_AS_SPACE)) return null
    val afterOperator = operator.nextSibling?.takeUnless { it is PsiWhiteSpace } ?: return null

    // `?dual_op(Sign), not(?is_space(NotMarker))`, less the three exclusions 1.17.0 kept.
    val notMarker = afterOperator.text.firstOrNull() ?: return null
    if (notMarker == sign || notMarker == '/' || notMarker == '>') return null

    val call = left as? UnqualifiedNoArgumentsCall<*> ?: return null
    if (call.doBlock != null) return null
    if (!isAvailable(AMBIGUOUS_DUAL_OPERATOR_CALL)) return null

    val name = call.functionNameElement()
    val signToken = operator.operatorTokenNode()

    val argument = ElixirAst.Call(
        meta(TextRange(signToken.startOffset, infix.textRange.endOffset), location(signToken)),
        atom(operator, sign.toString()),
        listOf(lower(right))
    )

    return noParenthesesCall(infix, name, listOf(argument), opensOnSign = true, doBlock = null)
}

/** `a..b//c` is `..//` with the range's metadata; a step after anything else is left as `//`, which Elixir rejects. */
private fun Lowering.ternary(ternary: Ternary): ElixirAst {
    val left = ternary.leftOperand() ?: return broken(ternary)
    val right = ternary.rightOperand() ?: return broken(ternary)
    val range = lower(left)

    return if (range is ElixirAst.Call && (range.callee as? ElixirAst.Literal.Atom)?.name == ".." && range.arguments?.size == 2) {
        ElixirAst.Call(
            meta(ternary, *range.meta.keys.toTypedArray()),
            atom(ternary.operator(), "..//"),
            range.arguments + lower(right)
        )
    } else {
        binary(ternary, range, lower(right))
    }
}

/** `not a in b` and `!a in b` move the `not` or `!` outside the `in`. */
private fun Lowering.`in`(`in`: In): ElixirAst {
    val left = `in`.leftOperand() ?: return broken(`in`)
    val right = `in`.rightOperand() ?: return broken(`in`)
    val loweredLeft = lower(left)
    val unary = (loweredLeft as? ElixirAst.Call)
        ?.takeIf { (it.callee as? ElixirAst.Literal.Atom)?.name in REARRANGED_UNARY_OPERATORS && it.arguments?.size == 1 }
        ?: return binary(`in`, loweredLeft, lower(right))

    val operator = `in`.operator().operatorTokenNode()
    val inMeta = meta(`in`, location(operator))
    val unaryMeta = if (isAvailable(REARRANGED_UNARY_KEEPS_ITS_METADATA)) meta(`in`, *unary.meta.keys.toTypedArray()) else inMeta

    return ElixirAst.Call(
        unaryMeta,
        unary.callee,
        listOf(ElixirAst.Call(inMeta, atom(`in`.operator(), operator.text), listOf(unary.arguments!!.single(), lower(right))))
    )
}

private val REARRANGED_UNARY_OPERATORS = setOf("not", "!")

/** `a not in b` is `not(a in b)`. */
private fun Lowering.notIn(notIn: NotIn): ElixirAst {
    val left = notIn.leftOperand() ?: return broken(notIn)
    val right = notIn.rightOperand() ?: return broken(notIn)
    val not = notIn.notInfixOperator.operatorTokenNode()
    val `in` = notIn.inInfixOperator.operatorTokenNode()

    return ElixirAst.Call(
        meta(
            notIn,
            if (isAvailable(OPERATOR_ON_NOT_IN)) {
                Meta.Key.Entry("operator", Meta.Value.Atom("not in"), tokenMetadata = true)
            } else {
                null
            },
            if (isAvailable(NEWLINES_ON_NOT_IN)) {
                operatorNewlines(notIn.node.firstChildNode, not, `in`.textRange.endOffset)
            } else {
                null
            },
            location(not)
        ),
        atom(notIn.notInfixOperator, "not"),
        listOf(
            ElixirAst.Call(
                meta(notIn, location(if (isAvailable(IN_OF_NOT_IN_ON_ITS_OWN_LINE)) `in` else not)),
                atom(notIn.inInfixOperator, "in"),
                listOf(lower(left), lower(right))
            )
        )
    )
}

private fun Lowering.prefix(prefix: Prefix): ElixirAst {
    val operand = prefix.operand() ?: return broken(prefix)
    val operator = prefix.operator()
    val token = operator.operatorTokenNode()

    return when {
        token.elementType == ElixirTypes.TERNARY_OPERATOR ->
            // Elixir's `build_unary_op` reads a prefix `//` as `(/)/operand`.
            ElixirAst.Call(
                meta(prefix, location(token.startOffset + 1)),
                atom(operator, "/"),
                listOf(ElixirAst.Call(meta(operator, location(token)), atom(operator, "/"), null), lower(operand))
            )
        // In a map, where the grammar reads `...` as a prefix, it was a call before 1.17.
        token.text == "..." && !isAvailable(ELLIPSIS_NULLARY_CALL) ->
            noParenthesesCall(prefix, operator, listOf(lower(operand)), opensOnSign(operand), doBlock = null)
        else -> ElixirAst.Call(meta(prefix, location(token)), atom(operator, token.text), listOf(lower(operand)))
    }
}

/** `..//: value` before 1.12: `..(/([/: value]))`, from the `..` and the first `/` of the `..//` key. */
private fun Lowering.steppedRangeKeywordCall(steppedRangeKeywordCall: ElixirSteppedRangeKeywordCall): ElixirAst {
    val node = steppedRangeKeywordCall.node
    val range = node.firstChildNode
    val division = node.findChildByType(ElixirTypes.DIVISION_OPERATOR) ?: return broken(steppedRangeKeywordCall)

    return ElixirAst.Call(
        meta(steppedRangeKeywordCall, location(range)),
        atom(range.psi, ".."),
        listOf(
            ElixirAst.Call(
                meta(TextRange(division.startOffset, node.textRange.endOffset), location(division)),
                atom(division.psi, "/"),
                listOf(lower(steppedRangeKeywordCall.noParenthesesKeywords))
            )
        )
    )
}

private fun Lowering.atom(element: PsiElement, name: String): ElixirAst = ElixirAst.Literal.Atom(meta(element), name)
