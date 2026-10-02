package org.elixir_lang.lowering

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.language_level.ElixirLanguageFeature.CLOSING_ON_BRACKET_ACCESS
import org.elixir_lang.language_level.ElixirLanguageFeature.CLOSING_ON_EMPTY_MULTIPLE_ALIASES
import org.elixir_lang.language_level.ElixirLanguageFeature.DELIMITER_ON_QUOTED_REMOTE_CALL
import org.elixir_lang.language_level.ElixirLanguageFeature.ELLIPSIS_NULLARY_CALL
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_NEWLINE_AS_SPACE
import org.elixir_lang.language_level.ElixirLanguageFeature.ESCAPED_NEWLINE_KEPT_IN_EXTRACTED_BUFFER
import org.elixir_lang.language_level.ElixirLanguageFeature.FROM_BRACKETS_ON_BRACKETED_EXPRESSION
import org.elixir_lang.language_level.ElixirLanguageFeature.FROM_BRACKETS_ON_EVERY_BRACKET_FORM
import org.elixir_lang.language_level.ElixirLanguageFeature.NESTED_PARENTHESES_DROP_INNER_METADATA
import org.elixir_lang.language_level.ElixirLanguageFeature.REMOTE_CALL_ON_NAME_LINE
import org.elixir_lang.language_level.ElixirLanguageFeature.UNESCAPED_QUOTED_REMOTE_CALL_NAME
import org.elixir_lang.psi.AtOperation
import org.elixir_lang.psi.AtUnqualifiedBracketOperation
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.BracketOperation
import org.elixir_lang.psi.DotCall
import org.elixir_lang.psi.ElixirAtomKeyword
import org.elixir_lang.psi.ElixirBlockIdentifier
import org.elixir_lang.psi.ElixirBlockItem
import org.elixir_lang.psi.ElixirBracketArguments
import org.elixir_lang.psi.ElixirDoBlock
import org.elixir_lang.psi.ElixirDotInfixOperator
import org.elixir_lang.psi.ElixirIdentifier
import org.elixir_lang.psi.ElixirInterpolation
import org.elixir_lang.psi.ElixirLine
import org.elixir_lang.psi.ElixirMatchedParenthesesArguments
import org.elixir_lang.psi.ElixirMultipleAliases
import org.elixir_lang.psi.ElixirNoParenthesesKeywordPair
import org.elixir_lang.psi.ElixirNoParenthesesKeywords
import org.elixir_lang.psi.ElixirNoParenthesesManyStrictNoParenthesesExpression
import org.elixir_lang.psi.ElixirNoParenthesesOneArgument
import org.elixir_lang.psi.ElixirParenthesesArguments
import org.elixir_lang.psi.ElixirRelativeIdentifier
import org.elixir_lang.psi.ElixirStabBody
import org.elixir_lang.psi.ElixirTypes
import org.elixir_lang.psi.ElixirUnaryPrefixOperator
import org.elixir_lang.psi.ElixirUnqualifiedNoParenthesesManyArgumentsCall
import org.elixir_lang.psi.ElixirVariable
import org.elixir_lang.psi.QualifiedBracketOperation
import org.elixir_lang.psi.QualifiedMultipleAliases
import org.elixir_lang.psi.QualifiedNoArgumentsCall
import org.elixir_lang.psi.QualifiedNoParenthesesCall
import org.elixir_lang.psi.QualifiedParenthesesCall
import org.elixir_lang.psi.UnqualifiedBracketOperation
import org.elixir_lang.psi.UnqualifiedNoArgumentsCall
import org.elixir_lang.psi.UnqualifiedNoParenthesesCall
import org.elixir_lang.psi.UnqualifiedParenthesesCall
import org.elixir_lang.psi.impl.operatorTokenNode
import org.elixir_lang.psi.impl.stripAccessExpression

/** Calls, qualified or not, with or without parentheses, `A.{B, C}`, bracket access and the `do` blocks of calls. */
internal fun Lowering.call(element: PsiElement): ElixirAst =
    when (element) {
        is UnqualifiedNoArgumentsCall<*> -> unqualifiedNoArgumentsCall(element)
        is UnqualifiedParenthesesCall<*> -> {
            val identifier = element.firstChild
            parenthesesCall(
                element,
                localCallee(identifier),
                location(identifier),
                emptyList(),
                parenthesesArguments(element),
                doBlock(element)
            )
        }
        is UnqualifiedNoParenthesesCall<*> -> {
            val arguments = PsiTreeUtil.getChildOfType(element, ElixirNoParenthesesOneArgument::class.java)
                ?: return broken(element)
            noParenthesesCall(element, element.firstChild, arguments, doBlock(element))
        }
        is ElixirUnqualifiedNoParenthesesManyArgumentsCall -> {
            ElixirAst.Call(
                meta(element, location(element)),
                localCallee(element.identifier),
                element.primaryArguments().map { lower(it) }
            )
        }
        is QualifiedNoArgumentsCall<*>, is QualifiedNoParenthesesCall<*>, is QualifiedParenthesesCall<*> ->
            qualifiedCall(element)
        is DotCall<*> -> dotCall(element)
        is UnqualifiedBracketOperation -> {
            val bracketArguments = element.bracketArguments as? ElixirBracketArguments ?: return broken(element)
            access(element, variable(element.firstChild), bracketArguments, BracketForm.IDENTIFIER)
        }
        is QualifiedBracketOperation -> qualifiedBracketOperation(element)
        is BracketOperation -> {
            val container = element.firstChild
            val form =
                if (container.stripAccessExpression() is AtOperation) BracketForm.IDENTIFIER else BracketForm.EXPRESSION
            val bracketArguments = element.bracketArguments as? ElixirBracketArguments ?: return broken(element)
            access(element, lower(container), bracketArguments, form)
        }
        is ElixirBracketArguments -> element.children.singleOrNull()?.let { lower(it) } ?: broken(element)
        is QualifiedMultipleAliases -> qualifiedMultipleAliases(element)
        is ElixirNoParenthesesKeywords ->
            ElixirAst.ListNode(
                meta(element),
                element.children.filterIsInstance<ElixirNoParenthesesKeywordPair>().map { lower(it) }
            )
        is ElixirNoParenthesesKeywordPair ->
            ElixirAst.Tuple(meta(element), listOf(lower(element.keywordKey), lower(element.keywordValue)))
        is ElixirNoParenthesesManyStrictNoParenthesesExpression ->
            element.children.singleOrNull()?.let { lower(it) } ?: broken(element)
        is ElixirIdentifier -> writtenAtom(element, identifier(element.text))
        is ElixirRelativeIdentifier -> relativeIdentifier(element) ?: broken(element)
        is ElixirVariable -> nameAlone(element, element)
        is ElixirBlockItem -> blockItem(element)
        is ElixirBlockIdentifier -> ElixirAst.Literal.Atom(meta(element), element.text)
        else -> unlowered(element)
    }

/** Which of Elixir's bracket access productions an access is, which decides when it gained `from_brackets`. */
internal enum class BracketForm {
    /** `[1][0]`, `foo(1)[0]`: an expression followed by brackets. */
    EXPRESSION,

    /** `foo[0]`, `Foo.bar[0]`, `@foo[0]`, `@1[0]` and `@Foo[0]`: a name, or an attribute, followed by brackets. */
    IDENTIFIER,
}

/** `container[key]`, a call of `Access.get/2` whose `.` shares its metadata. */
internal fun Lowering.access(
    access: PsiElement,
    container: ElixirAst,
    bracketArguments: ElixirBracketArguments,
    form: BracketForm,
): ElixirAst {
    val node = bracketArguments.node
    val opening = node.findChildByType(ElixirTypes.OPENING_BRACKET) ?: return broken(access)
    val closing = node.findChildByType(ElixirTypes.CLOSING_BRACKET) ?: return broken(access)
    val fromBrackets = isAvailable(
        when (form) {
            BracketForm.EXPRESSION -> FROM_BRACKETS_ON_BRACKETED_EXPRESSION
            BracketForm.IDENTIFIER -> FROM_BRACKETS_ON_EVERY_BRACKET_FORM
        }
    )
    val keys = arrayOf(
        Meta.Key.Entry("from_brackets", Meta.Value.Atom("true")).takeIf { fromBrackets },
        *if (isAvailable(CLOSING_ON_BRACKET_ACCESS)) {
            arrayOf(newlines(opening.textRange.endOffset), closing(closing.startOffset))
        } else {
            emptyArray()
        },
        location(opening),
    )

    return ElixirAst.Call(
        meta(access, *keys),
        ElixirAst.Call(
            meta(access, *keys),
            ElixirAst.Literal.Atom(meta(access), "."),
            listOf(ElixirAst.Literal.Atom(meta(access), "Elixir.Access"), ElixirAst.Literal.Atom(meta(access), "get"))
        ),
        listOf(container, lower(bracketArguments))
    )
}

internal fun Lowering.noParenthesesCall(
    call: PsiElement,
    name: PsiElement,
    arguments: ElixirNoParenthesesOneArgument,
    doBlock: ElixirDoBlock?,
): ElixirAst {
    return noParenthesesCall(call, name, arguments.arguments().map { lower(it) }, opensOnSign(arguments), doBlock)
}

internal fun opensOnSign(element: PsiElement): Boolean {
    val first = PsiTreeUtil.getDeepestFirst(element)

    return first.parent is ElixirUnaryPrefixOperator && (first.text == "+" || first.text == "-")
}

/**
 * `name arguments`, at [name], with its [doBlock]'s keywords as the last argument. `ambiguous_op` marks a call whose
 * only argument [opensOnSign], a unary `+` or `-`; a `do` block is a second argument.
 */
internal fun Lowering.noParenthesesCall(
    call: PsiElement,
    name: PsiElement,
    arguments: List<ElixirAst>,
    opensOnSign: Boolean,
    doBlock: ElixirDoBlock?,
): ElixirAst {
    val ambiguous = doBlock == null && arguments.size == 1 && opensOnSign

    return blockCall(
        call,
        localCallee(name),
        listOfNotNull(
            // Elixir's `build_call` prepends it for an `op_identifier`, whatever the options.
            Meta.Key.Entry("ambiguous_op", Meta.Value.Atom("nil")).takeIf { ambiguous },
            location(name)
        ),
        arguments,
        doBlock
    )
}

private fun Lowering.unqualifiedNoArgumentsCall(call: UnqualifiedNoArgumentsCall<*>): ElixirAst {
    val identifier = call.firstChild
    val doBlock = doBlock(call)

    return if (doBlock != null) {
        blockCall(call, localCallee(identifier), listOf(location(identifier)), emptyList(), doBlock)
    } else {
        nameAlone(call, identifier)
    }
}

/** [identifier] with nothing after it: a variable, or from 1.17 for `...` a nullary call. */
private fun Lowering.nameAlone(element: PsiElement, identifier: PsiElement): ElixirAst =
    if (identifier.text == "..." && isAvailable(ELLIPSIS_NULLARY_CALL) && !isBeforeSlash(identifier)) {
        ElixirAst.Call(meta(element, location(identifier)), localCallee(identifier), emptyList())
    } else {
        variable(identifier)
    }

/**
 * The `...` in [expression], outside its nested blocks, that from 1.17 is a unary operator: a call of `...` with
 * parentheses or arguments, brackets or a `do` block, the attribute `@... 1` or `@...[0]`, or a lone `...` before a
 * `+` or `-`, even across a `\` ending the line. Its operand is still grouped as before 1.17.
 */
internal fun Lowering.unaryEllipsis(expression: PsiElement): PsiElement? {
    if (!isAvailable(ELLIPSIS_NULLARY_CALL) || !expression.textContains('.')) return null

    return SyntaxTraverser.psiTraverser(expression)
        .expand { it !is ElixirStabBody && it !is ElixirInterpolation }
        .firstOrNull { call ->
            val name = call.firstChild

            when (call) {
                is UnqualifiedNoArgumentsCall<*> ->
                    name?.text == "..." &&
                        (doBlock(call) != null ||
                            nextTokenText(name, skipsLineContinuations = true).let { it == "+" || it == "-" })
                is UnqualifiedParenthesesCall<*>, is UnqualifiedNoParenthesesCall<*>,
                is ElixirUnqualifiedNoParenthesesManyArgumentsCall, is UnqualifiedBracketOperation -> name?.text == "..."
                is AtUnqualifiedNoParenthesesCall<*> ->
                    call.atIdentifier.node.findChildByType(ElixirTypes.IDENTIFIER_TOKEN)?.text == "..."
                is AtUnqualifiedBracketOperation -> call.node.findChildByType(ElixirTypes.IDENTIFIER_TOKEN)?.text == "..."
                else -> false
            }
        }
}

/**
 * `elixir_tokenizer` turns an operator followed by `/` into an identifier, so `...` in `&.../0` stays a variable
 * where the lone `...` is a nullary call.
 */
private fun Lowering.isBeforeSlash(element: PsiElement): Boolean =
    nextTokenText(element, skipsLineContinuations = isAvailable(ESCAPED_NEWLINE_AS_SPACE))?.startsWith("/") == true

/** `{name, meta, nil}`: a variable, or a call without arguments that may be one. */
internal fun Lowering.variable(identifier: PsiElement): ElixirAst =
    ElixirAst.Call(meta(identifier, location(identifier)), localCallee(identifier), null)

private fun Lowering.localCallee(identifier: PsiElement): ElixirAst =
    writtenAtom(identifier, identifier(identifier.text))

private val LINE_CONTINUATION = Regex("\\\\\r?\n")

/** The text of the first leaf after [element] that is neither horizontal space nor a skipped `\` ending a line. */
private fun nextTokenText(element: PsiElement, skipsLineContinuations: Boolean): String? =
    generateSequence(PsiTreeUtil.nextLeaf(element)) { PsiTreeUtil.nextLeaf(it) }
        .firstOrNull { leaf ->
            leaf !is PsiWhiteSpace ||
                '\n' in if (skipsLineContinuations) leaf.text.replace(LINE_CONTINUATION, "") else leaf.text
        }
        ?.text

/** `qualifier.name`, `qualifier.name arguments` and `qualifier.name(arguments)`. */
private fun Lowering.qualifiedCall(call: PsiElement): ElixirAst {
    val remote = remote(call) ?: return broken(call)
    val doBlock = doBlock(call)
    val parentheses = PsiTreeUtil.getChildOfType(call, ElixirMatchedParenthesesArguments::class.java)
    val arguments = PsiTreeUtil.getChildOfType(call, ElixirNoParenthesesOneArgument::class.java)

    return when {
        parentheses != null -> {
            val list = parentheses.parenthesesArgumentsList
            parenthesesCall(call, remote.callee, remote.location, remote.keys, list, doBlock)
        }
        arguments != null -> {
            val lowered = arguments.arguments().map { lower(it) }
            blockCall(call, remote.callee, remote.keys + remote.location, lowered, doBlock)
        }
        doBlock != null -> blockCall(call, remote.callee, remote.keys + remote.location, emptyList(), doBlock)
        call is QualifiedNoArgumentsCall<*> -> noParentheses(remote)
        else -> unlowered(call)
    }
}

/** A remote name with nothing after it, which Elixir marks `no_parens`. */
private fun Lowering.noParentheses(remote: Remote): ElixirAst =
    ElixirAst.Call(
        meta(
            remote.range,
            Meta.Key.Entry("no_parens", Meta.Value.Atom("true")),
            *remote.keys.toTypedArray(),
            remote.location
        ),
        remote.callee,
        emptyList()
    )

/**
 * A remote call's `.` call, spanning [range] from the qualifier to the name, the keys its call carries before its
 * location, and that location: the name's, or before 1.13, the `.`'s, which the `.` call has on every version.
 */
private class Remote(val callee: ElixirAst, val range: TextRange, val keys: List<Meta.Key>, val location: Meta.Key)

private fun Lowering.remote(call: PsiElement): Remote? {
    val children = call.children
    val qualifier = children.getOrNull(0) ?: return null
    val dot = children.getOrNull(1) as? ElixirDotInfixOperator ?: return null
    val relativeIdentifier = children.getOrNull(2) as? ElixirRelativeIdentifier ?: return null
    val name = relativeIdentifier(relativeIdentifier) ?: return null
    val dotLocation = location(dot.operatorTokenNode())
    val line = relativeIdentifier.children.singleOrNull() as? ElixirLine
    val range = TextRange(qualifier.textRange.startOffset, relativeIdentifier.textRange.endOffset)

    return Remote(
        dotCallee(range, dot, dotLocation, listOf(lower(qualifier), name)),
        range,
        listOfNotNull(
            line?.takeIf { isAvailable(DELIMITER_ON_QUOTED_REMOTE_CALL) }
                ?.let { tokenMetadata("delimiter", if (it.isCharList) "'" else "\"") }
        ),
        if (isAvailable(REMOTE_CALL_ON_NAME_LINE)) location(relativeIdentifier) else dotLocation
    )
}

/**
 * A remote call's name: an identifier, operator or reserved word, or quoted without interpolation, which Elixir's
 * tokenizer rejects in a name.
 */
private fun Lowering.relativeIdentifier(relativeIdentifier: ElixirRelativeIdentifier): ElixirAst? =
    when (val child = relativeIdentifier.children.singleOrNull()) {
        null ->
            writtenAtom(relativeIdentifier, identifier(relativeIdentifier.node.firstChildNode.text))
        is ElixirLine ->
            if (child.lineBody?.interpolationList.orEmpty().isNotEmpty()) {
                null
            } else if (isAvailable(UNESCAPED_QUOTED_REMOTE_CALL_NAME)) {
                quotedAtom(relativeIdentifier, child, emptyList()) as? ElixirAst.Literal.Atom
            } else {
                writtenAtom(
                    relativeIdentifier,
                    literalQuotedRemoteCallName(child, isAvailable(ESCAPED_NEWLINE_KEPT_IN_EXTRACTED_BUFFER))
                )
            }
        is ElixirAtomKeyword -> ElixirAst.Literal.Atom(meta(relativeIdentifier), child.text)
        else -> null
    }

/**
 * A quoted remote call's name as Elixir 1.17 and earlier kept it: `extract` without unescaping, which still drops the
 * `\` before the terminator, and before 1.12 a `\` ending a line with its newline, but keeps every other escape as
 * written.
 */
private fun literalQuotedRemoteCallName(line: ElixirLine, keepsLineContinuation: Boolean): String {
    val text = line.lineBody?.text.orEmpty()
    val terminator = if (line.isCharList) '\'' else '"'
    val name = StringBuilder()
    var index = 0

    while (index < text.length) {
        if (text[index] == '\\' && index + 1 < text.length) {
            if (keepsLineContinuation || text[index + 1] != '\n') {
                if (text[index + 1] != terminator) name.append('\\')
                name.append(text[index + 1])
            }
            index += 2
        } else {
            name.append(text[index])
            index += 1
        }
    }

    return name.toString()
}

private fun Lowering.dotCallee(
    range: TextRange,
    dot: ElixirDotInfixOperator,
    location: Meta.Key,
    operands: List<ElixirAst>,
): ElixirAst = ElixirAst.Call(meta(range, location), ElixirAst.Literal.Atom(meta(dot), "."), operands)

private fun Lowering.dotCall(call: DotCall<*>): ElixirAst {
    val children = call.children
    val qualifier = children.getOrNull(0) ?: return broken(call)
    val dot = children.getOrNull(1) as? ElixirDotInfixOperator ?: return broken(call)
    val dotLocation = location(dot.operatorTokenNode())
    val range = TextRange(qualifier.textRange.startOffset, dot.textRange.endOffset)
    val callee = dotCallee(range, dot, dotLocation, listOf(lower(qualifier)))

    return parenthesesCall(call, callee, dotLocation, emptyList(), call.parenthesesArgumentsList, doBlock(call))
}

/**
 * [callee] called with each set of [parentheses] in turn, the last with [doBlock]. A later set's call keeps the
 * earlier call's metadata after its own, or from 1.19 only its location.
 */
private fun Lowering.parenthesesCall(
    call: PsiElement,
    callee: ElixirAst,
    location: Meta.Key,
    keys: List<Meta.Key>,
    parentheses: List<ElixirParenthesesArguments>,
    doBlock: ElixirDoBlock?,
): ElixirAst {
    if (parentheses.isEmpty()) return broken(call)
    var called = callee
    var calledKeys = keys + location

    for ((index, arguments) in parentheses.withIndex()) {
        val node = arguments.node
        val opening = node.firstChildNode
        val own = listOfNotNull(newlines(opening.textRange.endOffset), closing(node.lastChildNode.startOffset))
        val inherited =
            if (index > 0 && isAvailable(NESTED_PARENTHESES_DROP_INNER_METADATA)) listOf(location) else calledKeys
        calledKeys = own + inherited
        val lowered = arguments.arguments().map { lower(it) }

        called =
            if (index == parentheses.lastIndex) {
                blockCall(call, called, calledKeys, lowered, doBlock)
            } else {
                val range = TextRange(call.textRange.startOffset, arguments.textRange.endOffset)
                ElixirAst.Call(meta(range, *calledKeys.toTypedArray()), called, lowered)
            }
    }

    return called
}

/** `callee(arguments)` with [doBlock]'s `do:` and `end:` before [keys] and its keywords after [arguments]. */
private fun Lowering.blockCall(
    call: PsiElement,
    callee: ElixirAst,
    keys: List<Meta.Key>,
    arguments: List<ElixirAst>,
    doBlock: ElixirDoBlock?,
): ElixirAst =
    if (doBlock == null) {
        ElixirAst.Call(meta(call, *keys.toTypedArray()), callee, arguments)
    } else {
        val node = doBlock.node
        val doToken = node.firstChildNode
        val endToken = node.lastChildNode

        ElixirAst.Call(
            meta(
                call,
                Meta.Key.Entry("do", Meta.Value.Keywords(listOf(location(doToken))), tokenMetadata = true),
                Meta.Key.Entry("end", Meta.Value.Keywords(listOf(location(endToken))), tokenMetadata = true),
                *keys.toTypedArray()
            ),
            callee,
            arguments + doBlockKeywords(doBlock)
        )
    }

/** `[do: body, else: body, ...]`, where only the `do` body is enclosed by its `do`. */
private fun Lowering.doBlockKeywords(doBlock: ElixirDoBlock): ElixirAst {
    val enclosing = Blocks.Enclosing.Do(position(doBlock.node.firstChildNode.startOffset))
    val body = doBlock.stab?.let { stab(it, enclosing) } ?: buildBlock(emptyList(), doBlock, enclosing)
    val items = doBlock.blockList?.blockItemList.orEmpty().map { lower(it) }

    return ElixirAst.ListNode(
        meta(doBlock),
        listOf(ElixirAst.Tuple(meta(doBlock), listOf(ElixirAst.Literal.Atom(meta(doBlock), "do"), body))) + items
    )
}

private fun Lowering.blockItem(blockItem: ElixirBlockItem): ElixirAst =
    ElixirAst.Tuple(
        meta(blockItem),
        listOf(
            lower(blockItem.blockIdentifier),
            blockItem.stab?.let { stab(it, null) } ?: buildBlock(emptyList(), blockItem, null)
        )
    )

private fun doBlock(call: PsiElement): ElixirDoBlock? = PsiTreeUtil.getChildOfType(call, ElixirDoBlock::class.java)

private fun parenthesesArguments(call: PsiElement): List<ElixirParenthesesArguments> =
    PsiTreeUtil.getChildOfType(call, ElixirMatchedParenthesesArguments::class.java)?.parenthesesArgumentsList.orEmpty()

/** `qualifier.name[key]`, whose container is the remote call without parentheses. */
private fun Lowering.qualifiedBracketOperation(operation: QualifiedBracketOperation): ElixirAst {
    val remote = remote(operation) ?: return broken(operation)
    val bracketArguments = operation.bracketArguments as? ElixirBracketArguments ?: return broken(operation)

    return access(operation, noParentheses(remote), bracketArguments, BracketForm.IDENTIFIER)
}

/** `qualifier.{A, B}`, a call of `{}` on the qualifier. */
private fun Lowering.qualifiedMultipleAliases(qualifiedMultipleAliases: QualifiedMultipleAliases): ElixirAst {
    val children = qualifiedMultipleAliases.children
    val qualifier = children.getOrNull(0) ?: return broken(qualifiedMultipleAliases)
    val dot = children.getOrNull(1) as? ElixirDotInfixOperator ?: return broken(qualifiedMultipleAliases)
    val multipleAliases = children.getOrNull(2) as? ElixirMultipleAliases ?: return broken(qualifiedMultipleAliases)
    val aliases = multipleAliases.children.map { lower(it) }
    val node = multipleAliases.node
    val opening = node.firstChildNode
    val dotLocation = location(dot.operatorTokenNode())
    val keys =
        if (aliases.isNotEmpty() || isAvailable(CLOSING_ON_EMPTY_MULTIPLE_ALIASES)) {
            listOfNotNull(newlines(opening.textRange.endOffset), closing(node.lastChildNode.startOffset))
        } else {
            emptyList()
        }

    return ElixirAst.Call(
        meta(qualifiedMultipleAliases, *keys.toTypedArray(), dotLocation),
        dotCallee(
            qualifiedMultipleAliases.textRange,
            dot,
            dotLocation,
            listOf(lower(qualifier), ElixirAst.Literal.Atom(meta(multipleAliases), "{}"))
        ),
        aliases
    )
}
