package org.elixir_lang

import com.ericsson.otp.erlang.*
import com.intellij.openapi.diagnostic.Logger
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.v1.definitions.component1
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.v1.definitions.component2
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.v1.definitions.component3
import org.elixir_lang.beam.term.inspect
import org.elixir_lang.code.Identifier
import org.elixir_lang.code.InspectAtom
import org.elixir_lang.code.sanitizeErlangVariableName
import kotlin.collections.List

fun otpErlangList(vararg elements: OtpErlangObject): OtpErlangList = OtpErlangList(elements)
fun otpErlangList(elements: List<OtpErlangObject>): OtpErlangList = OtpErlangList(elements.toTypedArray())

object Macro {
    val logger = Logger.getInstance(Macro.javaClass)

    // The `end` keyword, not an identifier, atom or field that merely ends in `end`, such as `line_end`.
    private val END_KEYWORD_AT_END = Regex("""(?<![\p{L}\p{M}\p{N}_?!@:.])end$""")

    fun block(expressions: List<OtpErlangObject>): OtpErlangTuple =
        expr("__block__", otpErlangList(expressions))

    fun expr(function: String, vararg arguments: OtpErlangObject) =
        expr(function, OtpErlangList(arguments))

    fun expr(function: String, argumentList: List<OtpErlangObject>) =
        expr(function, otpErlangList(argumentList))

    fun expr(function: OtpErlangAtom, vararg arguments: OtpErlangObject) =
        expr(function = function, metadata = OtpErlangList(), arguments = OtpErlangList(arguments))

    fun expr(function: String, arguments: OtpErlangList) =
        expr(function = OtpErlangAtom(function), metadata = OtpErlangList(), arguments = arguments)

    fun expr(
        function: OtpErlangObject,
        metadata: OtpErlangList = OtpErlangList(),
        argumentList: List<OtpErlangObject>
    ) =
        expr(function, metadata, arguments = otpErlangList(argumentList))

    fun expr(function: OtpErlangObject, metadata: OtpErlangList = OtpErlangList(), arguments: OtpErlangList) =
        otpErlangTuple(function, metadata, arguments)

    fun variable(name: String) = expr(name, NIL)

    fun variable(name: OtpErlangAtom) = expr(name, NIL)

    fun callArguments(callExpression: OtpErlangTuple): OtpErlangList =
        when (val arguments = callExpression.elementAt(2)) {
            is OtpErlangList -> arguments
            is OtpErlangString -> OtpErlangList(arguments.stringValue())
            else -> TODO()
        }

    fun isAliases(macro: OtpErlangObject): Boolean {
        var aliases = false

        if (isExpression(macro)) {
            val expression = macro as OtpErlangTuple

            val first = expression.elementAt(0)

            if (first is OtpErlangAtom) {

                if (first.atomValue() == "__aliases__") {
                    aliases = true
                }
            }
        }

        return aliases
    }

    /**
     * Return whether the macro is an Expr node: `expr :: {expr | atom, Keyword.t, atom | \[t\]}`.
     *
     * @param macro a quoted form from a `quote` method.
     * @return `true` if a tuple with 3 elements; `false` otherwise.
     */
    fun isExpression(macro: OtpErlangObject): Boolean {
        var expression = false

        if (macro is OtpErlangTuple) {

            if (macro.arity() == 3) {
                expression = true
            }
        }

        return expression
    }

    /**
     * Returns whether macro is a local call.
     *
     * @param macro a quoted form
     * @return `true` if local call; `false` otherwise.
     * @see [Macro.decompose_call/1](https://github.com/elixir-lang/elixir/blob/6151f2ab1af0189b9c8c526db196e2a65c609c64/lib/elixir/lib/macro.ex.L277-L281)
     */
    fun isLocalCall(macro: OtpErlangObject): Boolean {
        var localCall = false

        if (isExpression(macro)) {
            val expression = macro as OtpErlangTuple

            val first = expression.elementAt(0)

            if (first is OtpErlangAtom) {
                val last = expression.elementAt(2)

                /* OtpErlangString maps to CharList, which are list, so is_list in Elixir would be true for
                   OtpErlangList and OtpErlangString. */
                if (last is OtpErlangList || last is OtpErlangString) {
                    localCall = true
                }
            }
        }

        return localCall
    }

    /** Return whether macro is a local call expression with no arguments.
     *
     * @param macro
     * @return
     */
    fun isVariable(macro: OtpErlangObject): Boolean {
        var variable = false

        if (isExpression(macro)) {
            val expression = macro as OtpErlangTuple

            val first = expression.elementAt(0)

            if (first is OtpErlangAtom) {
                val last = expression.elementAt(2)

                if (last is OtpErlangAtom) {
                    variable = true
                }
            }
        }

        return variable
    }

    fun metadata(expression: OtpErlangTuple): OtpErlangList = expression.elementAt(1) as OtpErlangList

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L721-L722
    fun otherToString(macro: OtpErlangObject): String = inspect(macro)

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L948-L952
    private fun keywordListToString(list: OtpErlangList): String =
        list.joinToString(", ") { element ->
            val tuple = element as OtpErlangTuple

            assert(tuple.arity() == 2)

            val (key, value) = tuple

            "${Identifier.inspectAsKey(key as OtpErlangAtom)} ${toString(value)}"
        }

    inline fun <T> ifTupleTo(macro: OtpErlangObject, arity: Int, tupleTo: (OtpErlangTuple) -> T?): T? =
        (macro as? OtpErlangTuple)?.let { tuple ->
            if (tuple.arity() == arity) {
                tupleTo(tuple)
            } else {
                null
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L543-L546
    private fun ifAliasToString(macro: OtpErlangObject): String? =
        ifTagged3TupleTo(macro, "__aliases__") { tuple ->
            (tuple.elementAt(2) as OtpErlangList).joinToString(".") { callToString(it) }
        }

    private fun ifArrowToString(macro: OtpErlangObject): String? =
        (macro as? OtpErlangList)?.let { list ->
            if (list.arity() > 0) {
                ifTagged3TupleTo(list.elementAt(0), "->") {
                    "(${arrowToString(list, true)})"
                }
            } else {
                null
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L558-L576
    private fun ifBitContainerToString(macro: OtpErlangObject): String? =
        ifTagged3TupleTo(macro, "<<>>") { tuple ->
            if (isInterpolated(tuple)) {
                interpolate(tuple)
            } else {
                val result = (tuple.elementAt(2) as OtpErlangList).joinToString(", ") { part ->
                    val partString = bitPartToString(part)

                    if (partString.startsWith('<') || partString.endsWith('>')) {
                        "($partString)"
                    } else {
                        partString
                    }
                }

                "<<$result>>"
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L553-L556
    private fun ifBlockToString(macro: OtpErlangObject): String? =
        ifTagged3TupleTo(macro, "__block__") { tuple ->
            val adjusted = adjustNewLines(blockToString(tuple), "\n  ")

            "(\n  $adjusted\n)"
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L924-L935
    private fun blockToString(term: OtpErlangObject): String =
        ifArrowListBlockToString(term) ?: ifBlockBlockToString(term) ?:
        // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L935
        toString(term)

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L924-L929
    private fun ifArrowListBlockToString(block: OtpErlangObject): String? =
        (block as? OtpErlangList)?.let { list ->
            if (list.arity() > 0) {
                ifTagged3TupleTo(list.elementAt(0), "->") {
                    list.joinToString("\n") { element ->
                        val (function, _, arguments) = element as OtpErlangTuple

                        assert(function is OtpErlangAtom && function.atomValue() == "->")

                        val argumentList = arguments as OtpErlangList

                        assert(argumentList.arity() == 2)

                        val (left, right) = argumentList
                        val leftList = left.toOtpErlangList()

                        val leftCommaJoined = commaJoinOrEmptyParentheses(leftList, false)

                        // A `cond`/`case`/`receive` clause's single condition needs parenthesizing
                        // whenever its raw term is `case`-tagged, for two different reasons that both
                        // end up checked here:
                        //
                        // - Still rendered as a literal `case ... do ... end` (or nested inside a
                        //   match, `source = case ... do ... end`, e.g. from deinlining `&&`/`||`):
                        //   the bare `end ->` is ambiguous - the parser reads `end` as closing the
                        //   enclosing macro instead of the inner block - the same reason
                        //   `operandToString` parenthesizes a binary operation's left operand.
                        // - Deinlined to `and`/`or`/`&&`/`||` by `toString` (`ifDeinlineToString`):
                        //   the rendered text no longer ends with `end` at all, but `and`/`or` bind
                        //   looser than `->`, so it still needs parens - just for precedence instead
                        //   of a dangling `end`.
                        //
                        // Checking the raw term's tag catches both `case`-shaped origins regardless of
                        // how `toString` ends up rendering them; checking the rendered text separately
                        // catches the match-wrapped form, whose raw term is `=`, not `case`.
                        val leftString = if (leftList.arity() == 1) {
                            val condition = leftList.elementAt(0)
                            val isCaseTagged = ifCaseTo(condition) { _, _ -> true } ?: false

                            if (isCaseTagged || END_KEYWORD_AT_END.containsMatchIn(leftCommaJoined.trimEnd())) {
                                "($leftCommaJoined)"
                            } else {
                                leftCommaJoined
                            }
                        } else {
                            leftCommaJoined
                        }

                        "$leftString->\n  ${adjustNewLines(blockToString(right), "\n  ")}"
                    }
                }
            } else {
                null
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L931-L933
    private fun ifBlockBlockToString(term: OtpErlangObject): String? =
        ifTagged3TupleTo(term, "__block__") { tuple ->
            (tuple.elementAt(2) as OtpErlangList).joinToString("\n") { toString(it) }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L645-L651
    private fun ifCaptureModuleNameArityToString(macro: OtpErlangObject): String? =
        ifTagged3TupleTo(macro, "&") { tuple ->
            (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                if (arguments.arity() == 1) {
                    ifTagged3TupleTo(arguments.elementAt(0), "/") { slashTuple ->
                        (slashTuple.elementAt(2) as? OtpErlangList)?.let { slashArguments ->
                            if (slashArguments.arity() == 2) {
                                val (call, arity) = slashArguments

                                ifCallConvertTo(call) { module, function, arguments ->
                                    if (arguments.arity() == 0 && arity is OtpErlangLong) {
                                        if (module.atomValue() == "erlang") {
                                            val rewrittenFunction =
                                                ifModuleFunctionRewriteTo(module, function, arity.intValue())

                                            when (rewrittenFunction) {
                                                is OtpErlangAtom -> "&${rewrittenFunction.atomValue()}/${toString(arity)}"
                                                is OtpErlangTuple -> {
                                                    val (rewrittenModule, rewrittenRelativeFunction) = rewrittenFunction.elementAt(
                                                        2
                                                    ) as OtpErlangList

                                                    "&${toString(rewrittenModule)}.${InspectAtom.remoteCall((rewrittenRelativeFunction as OtpErlangAtom).atomValue())}/${
                                                        toString(
                                                            arity
                                                        )
                                                    }"
                                                }
                                                null -> "&:erlang.${InspectAtom.remoteCall(function.atomValue())}/${toString(arity)}"
                                                else -> TODO("Don' know how to convert $rewrittenFunction to capture string")
                                            }

                                        } else {
                                            "&${toString(module)}.${InspectAtom.remoteCall(function.atomValue())}/${toString(arity)}"
                                        }
                                    } else {
                                        null
                                    }
                                }
                            } else {
                                null
                            }
                        }
                    }
                } else {
                    null
                }
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L653-L655
    private fun ifCaptureNonInteger(macro: OtpErlangObject): String? =
        ifTagged3TupleTo(macro, "&") { tuple ->
            (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                if (arguments.arity() == 1) {
                    val argument = arguments.elementAt(0)

                    if (argument !is OtpErlangLong) {
                        "&(${toString(argument)})"
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L894-L908
    private fun argumentsToString(arguments: OtpErlangList): String {
        val (init, last) = splitLast(arguments)

        return if (last is OtpErlangList && last.arity() != 0 && Inspect.List.isKeyword(last)) {
            val prefix = if (init.arity() == 0) {
                ""
            } else {
                init.joinToString(", ") { toString(it) } + ", "
            }

            prefix + keywordListToString(last)
        } else {
            arguments.joinToString(", ") { toString(it) }
        }
    }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L662-L669
    private fun ifAccessToString(macro: OtpErlangObject): String? =
        ifTupleTo(macro, 3) { tuple: OtpErlangTuple ->
            ifTagged3TupleTo(tuple.elementAt(0), ".") { dotTuple ->
                (dotTuple.elementAt(2) as? OtpErlangList)?.let { dotArguments ->
                    if (dotArguments.arity() == 2 &&
                        dotArguments.elementAt(0) == OtpErlangAtom("Elixir.Access") &&
                        dotArguments.elementAt(1) == OtpErlangAtom("get")
                    ) {
                        (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                            if (arguments.arity() == 2) {
                                val (left, right) = arguments
                                val leftString = toString(left)
                                val rightString = toString(OtpErlangList(right))

                                if (isOperationExpression(left)) {
                                    "($leftString)$rightString"
                                } else {
                                    leftString + rightString
                                }
                            } else {
                                null
                            }
                        }
                    } else {
                        null
                    }
                }
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L676-L693
    private fun ifCallToString(macro: OtpErlangObject): String? =
        ifTupleTo(macro, 3) { tuple: OtpErlangTuple ->
            tuple.elementAt(2).toOtpErlangList().let {
                ifModuleAttributeDefinitionToString(tuple) ?: ifUnaryCallToString(tuple) ?: ifBinaryCallToString(tuple)
                ?: ifSigilCallToString(tuple) ?: ifDeinlineToString(tuple) ?: otherCallToString(tuple)
            }
        }

    private fun ifDeinlineToString(term: OtpErlangTuple): String? =
        ifErlangAtomToBinaryRewriteTo(term) { toString(it) } ?: ifErlangBinaryToAtomRewriteTo(term) { toString(it) }
        ?: ifErlangElementRewriteTo(term) { toString(it) } ?: ifErlangErrorRewriteTo(term) { toString(it) }
        ?: ifErlangGroupLeaderRewriteTo(term) { toString(it) }
        ?: ifErlangIntegerToBinaryRewriteTo(term) { toString(it) } ?: ifErlangMonitorProcessRewriteTo(term) {
            toString(
                it
            )
        } ?: ifErlangSendAfterRewriteTo(term) { toString(it) } ?: ifErlangSetElementRewriteTo(term) { toString(it) }
        ?: ifErlangRewriteTo(term) { toString(it) } ?: ifMapsIsKeyRewriteTo(term) { toString(it) }
        ?: ifMapsMergeRewriteTo(term) { toString(it) } ?: ifSymbolicAndRewriteTo(term) { toString(it) }
        ?: ifSymbolicOrRewriteTo(term) { toString(it) } ?: ifWordAndRewriteTo(term) { toString(it) }
        ?: ifWordOrRewriteTo(term) { toString(it) } ?: ifIfRewriteTo(term) { toString(it) } ?: ifMatchQuestionRewriteTo(
            term
        ) { toString(it) }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L681-L687
    private fun otherCallToString(tuple: OtpErlangTuple): String {
        val arguments = tuple.elementAt(2).toOtpErlangList()
        val (list, last) = splitLast(arguments)

        return if (isKeywordBlocks(last)) {
            callToStringWithArguments(tuple.elementAt(0), list) + keywordBlocksToString(last as OtpErlangList)
        } else {
            callToStringWithArguments(tuple.elementAt(0), arguments)
        }
    }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L919-L922
    private fun keywordBlockToString(keyword: String, block: OtpErlangObject): String =
        "$keyword\n  ${adjustNewLines(blockToString(block), "\n  ")}\n"

    // in order they should be rendered in keywordBlocksToString
    val KEYWORD_BLOCK_KEYWORDS = arrayOf("do", "catch", "rescue", "after", "else")

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L910-L917
    private fun keywordBlocksToString(keywordBlocks: OtpErlangList): String =
        KEYWORD_BLOCK_KEYWORDS.mapNotNull { keywordBlockKeyword ->
            Keyword.get(keywordBlocks, keywordBlockKeyword)?.let { keywordBlock ->
                keywordBlockToString(keywordBlockKeyword, keywordBlock)
            }
        }.joinToString("", " ") + "end"

    private fun callToStringWithArguments(target: OtpErlangObject, arguments: OtpErlangList): String {
        return "${callToString(target)}(${argumentsToString(arguments)})"
    }

    private fun isKeywordBlock(term: OtpErlangObject): Boolean =
        when (term) {
            is OtpErlangTuple -> isKeywordBlock(term)
            else -> false
        }

    private fun isKeywordBlock(tuple: OtpErlangTuple): Boolean =
        tuple.arity() == 2 && (tuple.elementAt(0) as? OtpErlangAtom)?.atomValue() in KEYWORD_BLOCK_KEYWORDS

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L750-L754
    private fun isKeywordBlocks(list: OtpErlangList): Boolean =
        if (list.arity() > 0) {
            (list.elementAt(0) as? OtpErlangTuple)?.let { firstTuple ->
                if (firstTuple.arity() == 2 && firstTuple.elementAt(0) == OtpErlangAtom("do")) {
                    list.all { isKeywordBlock(it) }
                } else {
                    false
                }
            } ?: false
        } else {
            false
        }

    private fun isKeywordBlocks(term: OtpErlangObject): Boolean =
        when (term) {
            is OtpErlangList -> isKeywordBlocks(term)
            else -> false
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L842-L855
    private fun ifSigilCallToString(macro: OtpErlangObject): String? =
        ifTupleTo(macro, 3) { tuple ->
            (tuple.elementAt(0) as? OtpErlangAtom)?.let { sigil ->
                (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                    if (arguments.arity() == 2) {
                        ifTagged3TupleTo(arguments.elementAt(0), "<<>>") { binary ->
                            (arguments.elementAt(1) as? OtpErlangList)?.let { sigilArguments ->
                                val sigilAtomValue = sigil.atomValue()

                                if (sigilAtomValue.startsWith("sigil_")) {
                                    val name = sigilAtomValue.removePrefix("sigil_")

                                    "~$name${interpolate(binary)}${sigilArguments(sigilArguments)}"
                                } else {
                                    null
                                }
                            }
                        }
                    } else {
                        null
                    }
                }
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L825-L840
    private fun ifBinaryCallToString(macro: OtpErlangObject): String? =
        ifTupleTo(macro, 3) { tuple ->
            (tuple.elementAt(0) as? OtpErlangAtom)?.let { operator ->
                (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                    if (arguments.arity() == 2 && Identifier.binaryOperator(operator) != null) {
                        val operatorAtomValue = operator.atomValue()
                        val (left, right) = arguments

                        if (operatorAtomValue == "==" && right is OtpErlangAtom && right.atomValue() == "nil") {
                            toString(
                                OtpErlangTuple(
                                    arrayOf(
                                        OtpErlangAtom("is_nil"),
                                        OtpErlangList(),
                                        OtpErlangList(left)
                                    )
                                )
                            )
                        } else {
                            val operatorString = if (operatorAtomValue == "..") {
                                operatorAtomValue
                            } else {
                                " $operatorAtomValue "
                            }

                            val operationString =
                                operandToString(left, operatorAtomValue, Identifier.Associativity.LEFT) +
                                        operatorString +
                                        operandToString(right, operatorAtomValue, Identifier.Associativity.RIGHT)

                            if (operatorAtomValue == "->") {
                                "($operationString)"
                            } else {
                                operationString
                            }
                        }
                    } else {
                        null
                    }
                }
            }
        }

    private fun ifModuleAttributeDefinitionToString(macro: OtpErlangObject): String? =
        ifTupleTo(macro, 3) { tuple ->
            (tuple.elementAt(0) as? OtpErlangAtom)?.let { operator ->
                if (operator.atomValue() == "@") {
                    (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                        if (arguments.arity() == 1) {
                            val argument = arguments.elementAt(0)

                            ifTupleTo(argument, 3) { definition ->
                                definition.elementAt(0).let { it as? OtpErlangAtom }?.let { name ->
                                    definition.elementAt(2).let { it as? OtpErlangList }?.let { values ->
                                        if (values.arity() == 1) {
                                            val value = values.elementAt(0)
                                            val valueString = toString(value)

                                            "@${name.atomValue()} $valueString"
                                        } else {
                                            null
                                        }
                                    }
                                }
                            }
                        } else {
                            null
                        }
                    }
                } else {
                    null
                }
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L807-L823
    private fun ifUnaryCallToString(macro: OtpErlangObject): String? =
        ifTupleTo(macro, 3) { tuple ->
            (tuple.elementAt(0) as? OtpErlangAtom)?.let { operator ->
                (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                    if (arguments.arity() == 1) {
                        val operatorString = operator.atomValue()

                        Identifier.unaryOperator(operatorString)?.let {
                            val argument = arguments.elementAt(0)
                            val argumentString = toString(argument)

                            if (operatorString == "not" || isOperationExpression(argument)) {
                                "$operatorString($argumentString)"
                            } else {
                                operatorString + argumentString
                            }
                        }
                    } else {
                        null
                    }
                }
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L638-L643
    private fun ifCaptureNameArityToString(macro: OtpErlangObject): String? =
        ifTagged3TupleTo(macro, "&") { tuple ->
            (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                if (arguments.arity() == 1) {
                    ifTagged3TupleTo(arguments.elementAt(0), "/") { slashTuple ->
                        (slashTuple.elementAt(2) as? OtpErlangList)?.let { slashArguments ->
                            if (slashArguments.arity() == 2) {
                                val (nameContext, arity) = slashArguments

                                (nameContext as? OtpErlangTuple)?.let { nameContextTuple ->
                                    if (nameContextTuple.arity() == 3) {
                                        val (name, _, context) = nameContextTuple

                                        if (name is OtpErlangAtom && context is OtpErlangAtom && arity is OtpErlangLong) {
                                            "&${InspectAtom.localCall(name.atomValue())}/${toString(arity)}"
                                        } else {
                                            null
                                        }
                                    } else {
                                        null
                                    }
                                }
                            } else {
                                null
                            }
                        }
                    }
                } else {
                    null
                }
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L671-L674
    private fun ifDotTupleToString(macro: OtpErlangObject): String? =
        ifTupleTo(macro, 3) { tuple: OtpErlangTuple ->
            ifTagged3TupleTo(tuple.elementAt(0), ".") { dotTuple ->
                (dotTuple.elementAt(2) as? OtpErlangList)?.let { dotArguments ->
                    if (dotArguments.arity() == 2 && dotArguments.elementAt(1) == OtpErlangAtom("{}")) {
                        val left = dotArguments.elementAt(0)
                        val arguments = tuple.elementAt(2) as OtpErlangList

                        "${toString(left)}.{${argumentsToString(arguments)}}"
                    } else {
                        null
                    }
                }
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L606-L609
    private fun ifFnAdjustedBlockToString(macro: OtpErlangObject): String? =
        ifTagged3TupleTo(macro, "fn") { tuple ->
            "fn\n ${adjustNewLines(blockToString(tuple.elementAt(2)), "\n  ")}\nend"
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L596-L600
    private fun ifFnArrowToString(macro: OtpErlangObject): String? =
        ifTagged3TupleTo(macro, "fn") { tuple ->
            (tuple.elementAt(2) as? OtpErlangList)?.let { arrow ->
                if (arrow.arity() == 1) {
                    ifTagged3TupleTo(arrow.elementAt(0), "->") { arrowElement ->
                        (arrowElement.elementAt(2) as? OtpErlangList)?.let { arrowElementArguments ->
                            if (arrowElementArguments.arity() == 2) {
                                val arrowElementArgumentsTuple = arrowElementArguments.elementAt(1)

                                if (arrowElementArgumentsTuple !is OtpErlangTuple || arrowElementArgumentsTuple.elementAt(
                                        0
                                    ) != OtpErlangAtom("__block__")
                                ) {
                                    "fn ${arrowToString(arrow, false)} end"
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        }
                    }
                } else {
                    null
                }
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L602-L604
    private fun ifFnBlockToString(macro: OtpErlangObject): String? =
        ifTagged3TupleTo(macro, "fn") { tuple ->
            (tuple.elementAt(2) as? OtpErlangList)?.let { block ->
                if (block.arity() == 1) {
                    ifTagged3TupleTo(block.elementAt(0), "->") {
                        "fn ${blockToString(block)}\nend"
                    }
                } else {
                    null
                }
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L585-L588
    private fun ifMapContainerToString(macro: OtpErlangObject): String? =
        ifTagged3TupleTo(macro, "%{}") { tuple ->
            val pairs = tuple.elementAt(2) as OtpErlangList
            val (structName, fields) = structNameAndFields(pairs)

            "%${structName}{${mapToString(fields)}}"
        }

    private fun structNameAndFields(pairs: OtpErlangList): Pair<String, OtpErlangList> {
        val (structPairs, fieldPairs) = pairs
            .partition { pair ->
                pair is OtpErlangTuple && pair.arity() == 2 &&
                        pair.elementAt(0).let { it as? OtpErlangAtom }?.atomValue() == "__struct__" &&
                        pair.elementAt(1) is OtpErlangAtom
            }

        val structPair = structPairs.singleOrNull()

        return if (structPair != null) {
            val structNameAtom = structPair.let { it as OtpErlangTuple }.elementAt(1).let { it as OtpErlangAtom }
            val structName = toString(structNameAtom)

            Pair(structName, OtpErlangList(fieldPairs.toTypedArray()))
        } else {
            Pair("", pairs)
        }
    }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L657-L660
    private fun ifNotInToString(macro: OtpErlangObject): String? =
        ifTagged3TupleTo(macro, "not") { tuple ->
            (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                if (arguments.arity() == 1) {
                    ifTagged3TupleTo(arguments.elementAt(0), "in") { inTuple ->
                        (inTuple.elementAt(2) as? OtpErlangList)?.let { inArguments ->
                            if (inArguments.arity() == 2) {
                                val (left, right) = inArguments

                                "${toString(left)} not in ${toString(right)}"
                            } else {
                                null
                            }
                        }
                    }
                } else {
                    null
                }
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L538-L541
    private fun ifVariableToString(macro: OtpErlangObject): String? =
        (macro as? OtpErlangTuple)?.let { tuple ->
            if (tuple.arity() == 3) {
                tuple.elementAt(0).let { it as? OtpErlangAtom }?.let { variable ->
                    val scope = tuple.elementAt(2)

                    if (scope is OtpErlangAtom || (scope is OtpErlangList && scope.arity() == 1 && scope.elementAt(0) == NIL)) {
                        sanitizeErlangVariableName(variable)
                    } else {
                        null
                    }
                }
            } else {
                null
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L548-L551
    private fun ifSingleExpressionBlockToString(macro: OtpErlangObject): String? =
        ifTagged3TupleTo(macro, "__block__") { tuple ->
            val arguments = tuple.elementAt(2)

            if (arguments is OtpErlangList && arguments.arity() == 1) {
                toString(arguments.elementAt(0))
            } else {
                null
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L590-L594
    private fun ifStructContainerToString(macro: OtpErlangObject): String? =
        ifTagged3TupleTo(macro, "%") { tuple ->
            val arguments = tuple.elementAt(2)

            if (arguments is OtpErlangList && arguments.arity() == 2) {
                val (structName, map) = arguments

                val mapTuple = map as OtpErlangTuple

                assert(mapTuple.arity() == 3 && mapTuple.elementAt(0) == OtpErlangAtom("%{}"))

                val mapArguments = (mapTuple.elementAt(2) as OtpErlangList)

                "%${toString(structName)}{${mapToString(mapArguments)}}"
            } else {
                null
            }
        }

    inline fun <T> ifTagged3TupleTo(
        macro: OtpErlangObject,
        tag: String,
        crossinline taggedTupleTo: (OtpErlangTuple) -> T?
    ): T? =
        ifTaggedTuple(macro, arity = 3, predicate = { it == tag }, ifTrue = taggedTupleTo)

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L578-L582
    private fun ifTupleContainerToString(macro: OtpErlangObject): String? =
        ifTagged3TupleTo(macro, "{}") { tuple ->
            "{${tuple.elementAt(2).toOtpErlangList().joinToString(", ") { toString(it) }}}"
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L616-L626
    private fun ifWhenBinaryToString(macro: OtpErlangObject): String? =
        ifTagged3TupleTo(macro, "when") { tuple ->
            (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                if (arguments.arity() == 2) {
                    val (left, right) = arguments

                    val rightString = if (right != OtpErlangList() && Keyword.isKeyword(right)) {
                        keywordListToString(right as OtpErlangList)
                    } else {
                        operandToString(rewriteGuard(right), "when", Identifier.Associativity.RIGHT)
                    }

                    "${operandToString(left, "when", Identifier.Associativity.LEFT)} when $rightString"
                } else {
                    null
                }
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L628-L636
    private fun ifWhenSplatToString(macro: OtpErlangObject): String? =
        ifTagged3TupleTo(macro, "when") { tuple ->
            val arguments = tuple.elementAt(2) as OtpErlangList
            val (left, right) = splitLast(arguments)

            "(${left.joinToString(", ") { toString(it) }}) when ${toString(right)}"
        }

    fun toString(macro: OtpErlangObject): String =
        ifVariableToString(macro) ?: ifAliasToString(macro) ?: ifSingleExpressionBlockToString(macro)
        ?: ifBlockToString(macro) ?: ifBitContainerToString(macro) ?: ifTupleContainerToString(macro)
        ?: ifMapContainerToString(macro) ?: ifStructContainerToString(macro) ?: ifFnArrowToString(macro)
        ?: ifFnBlockToString(macro) ?: ifFnAdjustedBlockToString(macro) ?: ifArrowToString(macro)
        ?: ifWhenBinaryToString(macro) ?: ifWhenSplatToString(macro) ?: ifCaptureNameArityToString(macro)
        ?: ifCaptureModuleNameArityToString(macro) ?: ifCaptureNonInteger(macro) ?: ifNotInToString(macro)
        ?: ifAccessToString(macro) ?: ifDotTupleToString(macro) ?: ifConsToString(macro) ?: ifCallToString(macro)
        ?: if2TupleToString(macro) ?: ifListToString(macro) ?: otherToString(macro)

    private fun ifConsToString(macro: OtpErlangObject): String? =
        ifTagged3TupleTo(macro, "|") { tuple ->
            val arguments = tuple.elementAt(2).toOtpErlangList()

            val headString = toString(arguments.elementAt(0))
            val tailString = toString(arguments.elementAt(1))

            "$headString | $tailString"
        }

    private fun ifListToString(macro: OtpErlangObject): String? =
        (macro as? OtpErlangList)?.let { list ->
            when {
                list.arity() == 0 ->
                    "[]"
                IOLib.printableList(list) ->
                    "'${charlistContentsToString(list)}'"
                Inspect.List.isKeyword(list) ->
                    "[${keywordListToString(list)}]"
                else ->
                    "[${list.joinToString(", ") { toString(it) }}]"
            }
        }

    private fun charlistContentsToString(list: OtpErlangList): String =
        IOLib.printableListToString(list)
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("#{", "\\#{")
            .replace("\b", "\\b")
            .replace("\t", "\\t")
            .replace("\n", "\\n")
            .replace("\u000B", "\\v")
            .replace("\u000C", "\\f")
            .replace("\r", "\\r")
            .replace("\u001B", "\\e")
            .replace(ELIXIR_REJECTED_IN_STRINGS) { "\\u%04X".format(it.value.single().code) }

    // Line and paragraph separators and bidirectional formatting characters, which Elixir refuses raw in a string.
    private val ELIXIR_REJECTED_IN_STRINGS = Regex("[\u2028\u2029\u202A-\u202E\u2066-\u2069]")

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L695-L698
    private fun if2TupleToString(macro: OtpErlangObject): String? =
        ifTupleTo(macro, 2) { tuple ->
            toString(
                OtpErlangTuple(
                    arrayOf(
                        OtpErlangAtom("{}"), OtpErlangList(), OtpErlangList(tuple.elements())
                    )
                )
            )
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L860-L871
    private fun isOperationExpression(term: OtpErlangObject): Boolean =
        ifTupleTo(term, 3) { tuple ->
            (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                when (arguments.arity()) {
                    2 -> Identifier.binaryOperator(tuple.elementAt(0)) != null
                    1 -> Identifier.unaryOperator(tuple.elementAt(0)) != null
                    else -> false
                }
            }
        } ?: false

    private fun sigilArguments(@Suppress("UNUSED_PARAMETER") term: OtpErlangObject): String {
        TODO("not implemented")
    }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L997-L1004
    private fun splitLast(list: OtpErlangList): Pair<OtpErlangList, OtpErlangObject> =
        if (list.arity() == 0) {
            Pair(OtpErlangList(), OtpErlangList())
        } else {
            val elements = list.elements()
            val init = OtpErlangList(elements.sliceArray(0 until elements.lastIndex))
            val last = elements[elements.lastIndex]

            Pair(init, last)
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L964-L981
    private fun operandToString(
        expression: OtpErlangObject,
        parentOperator: String,
        side: Identifier.Associativity
    ): String =
        ifTupleTo(expression, 3) { tuple ->
            (tuple.elementAt(0) as? OtpErlangAtom)?.let { operator ->
                (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                    if (arguments.arity() == 2) {
                        Identifier.binaryOperator(operator)?.let { (_, precedence) ->
                            val (parentAssociativity, parentPrecedence) = Identifier.binaryOperator(parentOperator)!!

                            when {
                                parentPrecedence < precedence -> toString(expression)
                                parentPrecedence > precedence -> wrapInParenthesis(expression)
                                parentAssociativity == side -> toString(expression)
                                else -> wrapInParenthesis(expression)
                            }
                        }
                    } else {
                        null
                    }
                }
            }
        }
            ?: if (parentOperator == "->" && side == Identifier.Associativity.LEFT && expression is OtpErlangList && expression.arity() == 0) {
                "()"
            } else {
                val rendered = toString(expression)

                // Wrap do...end block expressions in parentheses when they appear as the
                // LEFT operand of a binary operation AND the rendered output actually ends
                // with `end`.  Example: `(case ... end) + title_padding` instead of
                // `case ... end + title_padding` which causes the parser to treat `end`
                // as closing the outer block.
                //
                // We check the rendered string rather than the raw AST structure because
                // deinlining rewrites (e.g. `case` -> `||` via ifSymbolicOrRewriteTo) may
                // eliminate the do...end block entirely.  Right-side operands are safe
                // because `end` comes last with no trailing operator token.
                //
                // Excluded: `::` (type annotation in binary segments) - inside `<<>>`,
                // `case...end::binary()` is parsed correctly by Elixir.
                //
                // Analogous to `MacroString.group()` in the Erlang abstract code
                // decompiler (see commit a5cbbaa2).
                if (side == Identifier.Associativity.LEFT
                    && parentOperator != "::"
                    && rendered.trimEnd().endsWith("end")) {
                    "($rendered)"
                } else {
                    rendered
                }
            }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L960-L962
    private fun wrapInParenthesis(expression: OtpErlangObject): String =
        "(${toString(expression)})"

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L983-L988
    private fun arrowToString(pairs: OtpErlangList, emptyParentheses: Boolean): String =
        pairs.joinToString("; ") { pair ->
            val (operator, _, arguments) = pair as OtpErlangTuple

            assert(operator is OtpErlangAtom && operator.atomValue() == "->")

            val argumentList = arguments as OtpErlangList

            assert(argumentList.arity() == 2)

            val (left, right) = argumentList

            val leftString = commaJoinOrEmptyParentheses(left as OtpErlangList, emptyParentheses)

            leftString + "-> " + toString(right)
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L990-L995
    private fun commaJoinOrEmptyParentheses(left: OtpErlangList, emptyParentheses: Boolean): String =
        if (left.arity() == 0) {
            if (emptyParentheses) {
                "() "
            } else {
                ""
            }
        } else {
            left.joinToString(", ", "", " ") { toString(it) }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L724-L733
    private fun bitPartToString(bitPart: OtpErlangObject): String =
        // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L724-L729
        ifTagged3TupleTo(bitPart, "::") { tuple ->
            (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                if (arguments.arity() == 2) {
                    val (left, right) = arguments

                    operandToString(left, "::", Identifier.Associativity.LEFT) + "::" + bitModsToString(
                        right,
                        "::",
                        Identifier.Associativity.RIGHT
                    )
                } else {
                    null
                }
            }
        } ?: toString(bitPart)

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L735-L745
    private fun bitModsToString(
        other: OtpErlangObject,
        parentOperator: String,
        side: Identifier.Associativity
    ): String =
        // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L735-L741
        ifTupleTo(other, 3) { tuple ->
            (tuple.elementAt(0) as? OtpErlangAtom)?.let { operator ->
                val operatorAtomValue = operator.atomValue()

                when (operatorAtomValue) {
                    "*", "-" -> {
                        (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                            if (arguments.arity() == 2) {
                                val (left, right) = arguments

                                bitModsToString(left, operatorAtomValue, Identifier.Associativity.LEFT) +
                                        operatorAtomValue +
                                        bitModsToString(right, operatorAtomValue, Identifier.Associativity.RIGHT)
                            } else {
                                null
                            }
                        }
                    }
                    else -> null
                }
            }
        } ?:
        // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L743-L745
        operandToString(other, parentOperator, side)

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L937-L946
    private fun mapToString(list: OtpErlangList): String =
        if (list.arity() == 1) {
            ifTagged3TupleTo(list.elementAt(0), "|") { tuple ->
                (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                    if (arguments.arity() == 2) {
                        val (updateMap, updateArguments) = arguments

                        "${toString(updateMap)} | ${mapToString(updateArguments as OtpErlangList)}"
                    } else {
                        null
                    }
                }
            }
        } else {
            null
        } ?: if (Inspect.List.isKeyword(list)) {
            keywordListToString(list)
        } else {
            mapListToString(list)
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L954-L958
    private fun mapListToString(list: OtpErlangList): String =
        list.joinToString(", ") { element ->
            val tuple = element as OtpErlangTuple

            assert(tuple.arity() == 2)

            val (key, value) = tuple

            "${toString(key)} => ${toString(value)}"
        }


    private fun interpolate(@Suppress("UNUSED_PARAMETER") macro: OtpErlangTuple): String {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L756-L767
    private fun isInterpolated(macro: OtpErlangObject): Boolean =
        ifTagged3TupleTo(macro, "<<>>>") { tuple ->
            (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                if (arguments.arity() > 0) {
                    arguments.all { isInterpolatedPart(it) }
                } else {
                    false
                }
            }
        } ?: false

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L759-L761
    private fun isInterpolatedPart(part: OtpErlangObject): Boolean =
        // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L759
        ifTagged3TupleTo(part, "::") { tuple ->
            (tuple.elementAt(2) as? OtpErlangList)?.let { typeArguments ->
                if (typeArguments.arity() == 2) {
                    ifCallConvertArgumentsTo(
                        typeArguments.elementAt(0),
                        "Elixir.Kernel",
                        "to_string"
                    ) { toStringArguments ->
                        if (toStringArguments.arity() == 1) {
                            ifTagged3TupleTo(toStringArguments.elementAt(1), "binary") {
                                true
                            }
                        } else {
                            false
                        }
                    }
                } else {
                    false
                }
            }
        } ?:
        // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L760-L761
        (part is OtpErlangBinary)

    fun adjustNewLines(textWithNewLines: String, newLineReplacement: String): String =
        Regex(Regex.escape("\n")).replace(textWithNewLines, Regex.escapeReplacement(newLineReplacement))

    private fun isCallingIsFalsy(term: OtpErlangObject, variable: OtpErlangObject): Boolean =
        ifCallConvertArgumentsTo(term, "erlang", "orelse") { orelseArguments ->
            orelseArguments.arity() == 2 &&
                    isCallingWithArguments(
                        orelseArguments.elementAt(0),
                        variable,
                        OtpErlangAtom("false")
                    )
                    &&
                    isCallingWithArguments(
                        orelseArguments.elementAt(1),
                        variable,
                        OtpErlangAtom("nil")
                    )
        } ?: false

    private fun isCallingWithArguments(
        term: OtpErlangObject,
        vararg expected: OtpErlangObject
    ): Boolean =
        ifCallConvertArgumentsTo(term, "erlang", "=:=") { actual ->
            actual.elements()!!.contentEquals(expected)
        } ?: false

    private inline fun <T> ifCallConvertArgumentsTo(
        term: OtpErlangObject,
        module: String,
        function: String,
        crossinline argumentsTo: (OtpErlangList) -> T?
    ): T? =
        ifCallConvertTo(term) { actualModule, actualFunction, arguments ->
            if (actualModule.atomValue() == module && actualFunction.atomValue() == function) {
                argumentsTo(arguments)
            } else {
                null
            }
        }

    private inline fun <T> ifCallConvertTo(
        term: OtpErlangObject,
        crossinline transformer: (
            module: OtpErlangAtom,
            function: OtpErlangAtom,
            arguments: OtpErlangList
        ) -> T?
    ): T? =
        ifTupleTo(term, 3) { call ->
            ifTagged3TupleTo(call.elementAt(0), ".") { dot ->
                (dot.elementAt(2) as? OtpErlangList)?.let { dotArguments ->
                    if (dotArguments.arity() == 2) {
                        dotArguments.elementAt(0).let { it as? OtpErlangAtom }?.let { module ->
                            dotArguments.elementAt(1).let { it as? OtpErlangAtom }?.let { function ->
                                call.elementAt(2).let { it as? OtpErlangList }?.let { arguments ->
                                    transformer(module, function, arguments)
                                }
                            }
                        }
                    } else {
                        null
                    }
                }
            }
        }

    private inline fun <T> ifErlangElementCallConvertArgumentsTo(
        term: OtpErlangObject,
        crossinline argumentsTo: (OtpErlangObject, OtpErlangObject) -> T?
    ): T? =
        ifCallConvertArgumentsTo(term, "erlang", "element") { arguments ->
            if (arguments.arity() == 2) {
                argumentsTo(arguments.elementAt(0), arguments.elementAt(1))
            } else {
                null
            }
        }

    private inline fun <T> ifErlangAtomToBinaryRewriteTo(
        macro: OtpErlangObject,
        crossinline transformer: (OtpErlangObject) -> T
    ): T? =
        ifCallConvertArgumentsTo(macro, "erlang", "atom_to_binary") { arguments ->
            if (arguments.arity() == 2 && arguments.elementAt(1) == OtpErlangAtom("utf8")) {
                transformer(
                    otpErlangTuple(
                        otpErlangTuple(
                            OtpErlangAtom("."),
                            OtpErlangList(),
                            otpErlangList(
                                OtpErlangAtom("Elixir.Atom"),
                                OtpErlangAtom("to_string")
                            )
                        ),
                        OtpErlangList(),
                        otpErlangList(
                            arguments.elementAt(0)
                        )
                    )
                )
            } else {
                null
            }
        }

    private inline fun <T> ifErlangBinaryToAtomRewriteTo(
        macro: OtpErlangObject,
        crossinline transformer: (OtpErlangObject) -> T
    ): T? =
        ifCallConvertArgumentsTo(macro, "erlang", "binary_to_atom") { arguments ->
            if (arguments.arity() == 2 && arguments.elementAt(1) == OtpErlangAtom("utf8")) {
                transformer(
                    otpErlangTuple(
                        otpErlangTuple(
                            OtpErlangAtom("."),
                            OtpErlangList(),
                            otpErlangList(
                                OtpErlangAtom("Elixir.String"),
                                OtpErlangAtom("to_atom")
                            )
                        ),
                        OtpErlangList(),
                        otpErlangList(
                            arguments.elementAt(0)
                        )
                    )
                )
            } else {
                null
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/exception.ex#L304-L308
    private inline fun <T> ifErlangElementRewriteTo(
        macro: OtpErlangObject,
        crossinline transformer: (OtpErlangObject) -> T
    ): T? =
        ifErlangElementCallConvertArgumentsTo(macro) { first, second ->
            // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/exception.ex#L307-L308
            if (first is OtpErlangLong) {
                transformer(
                    OtpErlangTuple(
                        arrayOf(
                            OtpErlangAtom("elem"),
                            OtpErlangList(),
                            OtpErlangList(
                                arrayOf(
                                    second,
                                    OtpErlangLong(first.longValue() - 1)
                                )
                            )
                        )
                    )
                )
            } else {
                // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/exception.ex#L304-L305
                ifCallConvertArgumentsTo(first, "erlang", "+") { plusArguments ->
                    if (plusArguments.arity() == 2 && plusArguments.elementAt(1) == OtpErlangLong(1)) {
                        transformer(
                            OtpErlangTuple(
                                arrayOf(
                                    OtpErlangAtom("elem"),
                                    OtpErlangList(),
                                    OtpErlangList(
                                        arrayOf(
                                            second,
                                            plusArguments.elementAt(0)
                                        )
                                    )
                                )
                            )
                        )
                    } else {
                        null
                    }
                } ?: transformer(
                    otpErlangTuple(
                        OtpErlangAtom("elem"),
                        OtpErlangList(),
                        otpErlangList(
                            second,
                            otpErlangTuple(
                                OtpErlangAtom("-"),
                                OtpErlangList(),
                                otpErlangList(
                                    first,
                                    OtpErlangLong(1)
                                )
                            )
                        )
                    )
                )
            }
        }

    private inline fun <T> ifErlangErrorRewriteTo(
        macro: OtpErlangObject,
        crossinline transformer: (OtpErlangObject) -> T
    ): T? =
        ifCallConvertArgumentsTo(macro, "erlang", "error") { errorArguments ->
            errorArguments.singleOrNull()?.let { errorArgument ->
                ifCallConvertTo(errorArgument) { exceptionModule, exceptionFunction, exceptionArguments ->
                    if (exceptionFunction == OtpErlangAtom("exception") && exceptionArguments.arity() == 1) {
                        transformer(
                            otpErlangTuple(
                                OtpErlangAtom("raise"),
                                OtpErlangList(),
                                otpErlangList(
                                    exceptionModule,
                                    exceptionArguments.elementAt(0)
                                )
                            )
                        )
                    } else {
                        null
                    }
                }
            }
        }

    private inline fun <T> ifErlangGroupLeaderRewriteTo(
        macro: OtpErlangObject,
        crossinline transformer: (OtpErlangObject) -> T
    ): T? =
        ifCallConvertArgumentsTo(macro, "erlang", "group_leader") { arguments ->
            val reorderedArguments = when (arguments.arity()) {
                0 -> arguments
                2 -> {
                    val (leader, pid) = arguments

                    otpErlangList(pid, leader)
                }
                else -> null
            }

            if (reorderedArguments != null) {
                transformer(
                    otpErlangTuple(
                        otpErlangTuple(
                            OtpErlangAtom("."),
                            OtpErlangList(),
                            otpErlangList(
                                OtpErlangAtom("Elixir.Process"),
                                OtpErlangAtom("group_leader")
                            )
                        ),
                        OtpErlangList(),
                        reorderedArguments
                    )
                )
            } else {
                null
            }
        }

    private inline fun <T> ifErlangIntegerToBinaryRewriteTo(
        macro: OtpErlangObject,
        crossinline transformer: (OtpErlangObject) -> T
    ): T? =
        ifCallConvertArgumentsTo(macro, "erlang", "integer_to_binary") { arguments ->
            when (arguments.arity()) {
                1, 2 -> transformer(
                    otpErlangTuple(
                        otpErlangTuple(
                            OtpErlangAtom("."),
                            OtpErlangList(),
                            otpErlangList(
                                OtpErlangAtom("Elixir.Integer"),
                                OtpErlangAtom("to_string")
                            )
                        ),
                        OtpErlangList(),
                        arguments
                    )
                )
                else -> null
            }
        }

    private inline fun <T> ifErlangMonitorProcessRewriteTo(
        macro: OtpErlangObject,
        crossinline transformer: (OtpErlangObject) -> T
    ): T? =
        ifCallConvertArgumentsTo(macro, "erlang", "monitor") { arguments ->
            if (arguments.arity() == 2 && arguments.elementAt(0) == OtpErlangAtom("process")) {
                transformer(
                    otpErlangTuple(
                        otpErlangTuple(
                            OtpErlangAtom("."),
                            OtpErlangList(),
                            otpErlangList(
                                OtpErlangAtom("Elixir.Process"),
                                OtpErlangAtom("monitor")
                            )
                        ),
                        OtpErlangList(),
                        otpErlangList(
                            arguments.elementAt(1)
                        )
                    )
                )
            } else {
                null
            }
        }

    private inline fun <T> ifErlangSendAfterRewriteTo(
        macro: OtpErlangObject,
        crossinline transformer: (OtpErlangObject) -> T
    ): T? =
        ifCallConvertArgumentsTo(macro, "erlang", "send_after") { arguments ->
            val reorderedArguments = when (arguments.arity()) {
                3 -> {
                    val (time, destination, message) = arguments

                    otpErlangList(
                        destination,
                        message,
                        time
                    )
                }
                4 -> {
                    val (time, destination, message, opts) = arguments

                    otpErlangList(
                        destination,
                        message,
                        time,
                        opts
                    )
                }
                else -> null
            }

            if (reorderedArguments != null) {
                transformer(
                    otpErlangTuple(
                        otpErlangTuple(
                            OtpErlangAtom("."),
                            OtpErlangList(),
                            otpErlangList(
                                OtpErlangAtom("Elixir.Process"),
                                OtpErlangAtom("send_after")
                            )
                        ),
                        OtpErlangList(),
                        reorderedArguments
                    )
                )
            } else {
                null
            }
        }

    private inline fun <T> ifErlangSetElementRewriteTo(
        macro: OtpErlangObject,
        crossinline transformer: (OtpErlangObject) -> T
    ): T? =
        ifCallConvertArgumentsTo(macro, "erlang", "setelement") { arguments ->
            if (arguments.arity() == 3) {
                val (index, tuple, value) = arguments

                transformer(
                    otpErlangTuple(
                        OtpErlangAtom("put_elem"),
                        OtpErlangList(),
                        otpErlangList(
                            tuple,
                            otpErlangTuple(
                                OtpErlangAtom("-"),
                                OtpErlangList(),
                                otpErlangList(
                                    index,
                                    OtpErlangLong(1)
                                )
                            ),
                            value
                        )
                    )
                )
            } else {
                null
            }
        }

    private inline fun <T> ifMapsIsKeyRewriteTo(
        term: OtpErlangObject,
        crossinline transformer: (OtpErlangTuple) -> T
    ): T? =
        ifCallConvertArgumentsTo(term, "maps", "is_key") { arguments ->
            if (arguments.arity() == 2) {
                transformer(
                    OtpErlangTuple(
                        arrayOf(
                            OtpErlangTuple(
                                arrayOf(
                                    OtpErlangAtom("."),
                                    OtpErlangList(),
                                    OtpErlangList(
                                        arrayOf(
                                            OtpErlangAtom("Elixir.Map"),
                                            OtpErlangAtom("has_key?")
                                        )
                                    )
                                )
                            ),
                            OtpErlangList(),
                            // Order or arguments is swapped in Elixir compared to Erlang
                            OtpErlangList(
                                arrayOf(
                                    arguments.elementAt(1),
                                    arguments.elementAt(0)
                                )
                            )
                        )
                    )
                )
            } else {
                null
            }
        }

    private inline fun <T> ifMapsMergeRewriteTo(
        term: OtpErlangObject,
        crossinline transformer: (OtpErlangTuple) -> T
    ): T? =
        ifCallConvertArgumentsTo(term, "maps", "merge") { arguments ->
            if (arguments.arity() == 2) {
                transformer(
                    OtpErlangTuple(
                        arrayOf(
                            OtpErlangTuple(
                                arrayOf(
                                    OtpErlangAtom("."),
                                    OtpErlangList(),
                                    OtpErlangList(
                                        arrayOf(
                                            OtpErlangAtom("Elixir.Map"),
                                            OtpErlangAtom("merge")
                                        )
                                    )
                                )
                            ),
                            OtpErlangList(),
                            OtpErlangList(
                                arrayOf(
                                    arguments.elementAt(0),
                                    arguments.elementAt(1)
                                )
                            )
                        )
                    )
                )
            } else {
                null
            }
        }

    private inline fun <T> ifSymbolicAndRewriteTo(
        term: OtpErlangObject,
        crossinline transformer: (OtpErlangObject) -> T
    ): T? =
        ifCaseTo(term) { argument, clauses ->
            if (clauses.arity() == 2 && isReturnFalsyClause(clauses.elementAt(0))) {
                ifDefaultClauseReturn(clauses.elementAt(1))?.let { secondary ->
                    transformer(
                        OtpErlangTuple(
                            arrayOf(
                                OtpErlangAtom("&&"),
                                OtpErlangList(),
                                OtpErlangList(arrayOf(argument, secondary))
                            )
                        )
                    )
                }
            } else {
                null
            }
        }

    private inline fun <T> ifSymbolicOrRewriteTo(
        term: OtpErlangObject,
        crossinline transformer: (OtpErlangObject) -> T
    ): T? =
        ifCaseTo(term) { argument, clauses ->
            if (clauses.arity() == 2) {
                ifFalsyCaseClauseTo(clauses.elementAt(0)) { _, _ ->
                    ifCaseClauseTo(clauses.elementAt(1)) { passThroughInput, passThroughOutput ->
                        if (passThroughInput is OtpErlangList && passThroughInput.arity() == 1 && passThroughInput.elementAt(
                                0
                            ) == passThroughOutput
                        ) {
                            transformer(
                                otpErlangTuple(
                                    OtpErlangAtom("||"),
                                    OtpErlangList(""),
                                    otpErlangList(
                                        argument,
                                        passThroughOutput
                                    )
                                )
                            )
                        } else {
                            null
                        }
                    }
                }
            } else {
                null
            }
        }

    private inline fun <T> ifWordAndRewriteTo(
        term: OtpErlangObject,
        crossinline transformer: (OtpErlangObject) -> T
    ): T? =
        ifCaseTo(term) { arguments, clauses ->
            val clauseCount = clauses.arity()

            if (clauseCount in 2..3) {
                ifCaseClauseTo(clauses.elementAt(0)) { falseInput, falseOutput ->
                    if (falseInput.let { it as? OtpErlangList }?.singleOrNull() == OtpErlangAtom("false") &&
                        falseOutput == OtpErlangAtom("false")
                    ) {
                        ifCaseClauseTo(clauses.elementAt(1)) { trueInput, trueOutput ->
                            if (trueInput.let { it as? OtpErlangList }?.singleOrNull() == OtpErlangAtom("true")) {
                                if (clauseCount == 2 || isBadBoolClause(clauses.elementAt(2))
                                ) {
                                    transformer(
                                        otpErlangTuple(
                                            OtpErlangAtom("and"),
                                            OtpErlangList(),
                                            otpErlangList(
                                                arguments,
                                                trueOutput
                                            )
                                        )
                                    )
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        }
                    } else {
                        null
                    }
                }
            } else {
                null
            }
        }

    private fun isBadBoolClause(macro: OtpErlangObject): Boolean =
        ifCaseClauseTo(macro) { otherInput, otherOutput ->
            otherInput is OtpErlangList && otherInput.arity() == 1 && otherInput.elementAt(0).let { variable ->
                isVariable(variable) && isErrorBadBool(otherOutput, variable)
            }
        } ?: false

    private fun isErrorBadBool(macro: OtpErlangObject, variable: OtpErlangObject): Boolean =
        ifCallConvertArgumentsTo(macro, "erlang", "error") { arguments ->
            if (arguments.arity() == 1) {
                ifTupleTo(arguments.elementAt(0), 3) { tuple ->
                    tuple.elementAt(2).let { it as? OtpErlangList }?.let { elements ->
                        (elements.elementAt(0).let { it as? OtpErlangAtom }?.atomValue() == "badbool") &&
                                (elements.elementAt(1).let { it as? OtpErlangAtom }?.atomValue() == "and") &&
                                elements.elementAt(2) == variable
                    }

                }
            } else {
                null
            }
        } ?: false

    private inline fun <T> ifWordOrRewriteTo(
        term: OtpErlangObject,
        crossinline transformer: (OtpErlangObject) -> T
    ): T? =
        ifCaseTo(term) { arguments, clauses ->
            if (clauses.arity() == 2) {
                ifCaseClauseTo(clauses.elementAt(0)) { falseInput, falseOutput ->
                    if (falseInput.let { it as? OtpErlangList }?.singleOrNull() == OtpErlangAtom("false")) {
                        ifCaseClauseTo(clauses.elementAt(1)) { trueInput, trueOutput ->
                            if (trueInput.let { it as? OtpErlangList }?.singleOrNull() == OtpErlangAtom("true") &&
                                trueOutput == OtpErlangAtom("true")
                            ) {
                                transformer(
                                    otpErlangTuple(
                                        OtpErlangAtom("or"),
                                        OtpErlangList(),
                                        otpErlangList(
                                            arguments,
                                            falseOutput
                                        )
                                    )
                                )
                            } else {
                                null
                            }
                        }
                    } else {
                        null
                    }
                }
            } else {
                null
            }
        }

    private fun isReturnFalsyClause(term: OtpErlangObject): Boolean =
        ifFalsyCaseClauseTo(term) { inputPattern, output ->
            inputPattern == output
        } ?: false

    private inline fun <T> ifFalsyCaseClauseTo(
        term: OtpErlangObject,
        crossinline transformer: (inputPattern: OtpErlangTuple, output: OtpErlangObject) -> T
    ): T? =
        ifCaseClauseTo(term) { input, output ->
            if (input is OtpErlangList && input.arity() == 1) {
                ifTagged3TupleTo(input.elementAt(0), "when") { `when` ->
                    `when`.elementAt(2).let { it as? OtpErlangList }?.let { patternGuard ->
                        if (patternGuard.arity() == 2) {
                            val pattern = patternGuard.elementAt(0)

                            if (pattern is OtpErlangTuple &&
                                pattern.arity() == 3 &&
                                pattern.elementAt(0) is OtpErlangAtom &&
                                isCallingIsFalsy(patternGuard.elementAt(1), pattern)
                            ) {
                                transformer(pattern, output)
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    }
                }
            } else {
                null
            }
        }

    private fun ifDefaultClauseReturn(term: OtpErlangObject): OtpErlangObject? =
        ifCaseClauseTo(term) { input, output ->
            if (isDefaultPattern(input)) {
                output
            } else {
                null
            }
        }

    private fun isDefaultPattern(term: OtpErlangObject): Boolean =
        (term as? OtpErlangList)?.let { list ->
            if (list.arity() == 1) {
                ifTagged3TupleTo(list.elementAt(0), "_") {
                    true
                }
            } else {
                null
            }
        } ?: false

    private inline fun <T> ifIfRewriteTo(term: OtpErlangObject, crossinline transformer: (OtpErlangObject) -> T?): T? =
        ifCaseTo(term) { condition, clauses ->
            if (clauses.arity() == 2) {
                ifFalsyCaseClauseTo(clauses.elementAt(0)) { _, trueBranch ->
                    ifDefaultClauseReturn(clauses.elementAt(1))?.let { falseBranch ->
                        if (trueBranch == OtpErlangAtom("true") && falseBranch == OtpErlangAtom("false")) {
                            transformer(
                                otpErlangTuple(
                                    OtpErlangAtom("!"),
                                    OtpErlangList(),
                                    otpErlangList(condition)
                                )
                            )
                        } else {
                            transformer(
                                otpErlangTuple(
                                    OtpErlangAtom("if"),
                                    OtpErlangList(),
                                    otpErlangList(
                                        condition,
                                        otpErlangList(
                                            otpErlangTuple(OtpErlangAtom("do"), trueBranch),
                                            otpErlangTuple(OtpErlangAtom("else"), falseBranch)
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            } else {
                null
            }
        }

    private inline fun <T> ifMatchQuestionRewriteTo(
        term: OtpErlangObject,
        crossinline transformer: (OtpErlangObject) -> T
    ): T? =
        ifCaseTo(term) { argument, clauses ->
            if (clauses.arity() == 2) {
                ifCaseClauseTo(clauses.elementAt(0)) { trueInput, trueOutput ->
                    if (trueInput is OtpErlangList && trueInput.arity() == 1 &&
                        trueOutput is OtpErlangAtom && trueOutput.atomValue() == "true"
                    ) {
                        ifDefaultClauseReturn(clauses.elementAt(1))?.let { defaultReturn ->
                            if (defaultReturn is OtpErlangAtom && defaultReturn.atomValue() == "false") {
                                transformer(
                                    otpErlangTuple(
                                        OtpErlangAtom("match?"),
                                        OtpErlangList(),
                                        otpErlangList(
                                            trueInput.elementAt(0),
                                            argument
                                        )
                                    )
                                )
                            } else {
                                null
                            }
                        }
                    } else {
                        null
                    }
                }
            } else {
                null
            }
        }

    private inline fun <T> ifCaseTo(
        term: OtpErlangObject,
        crossinline transformer: (argument: OtpErlangObject, clauses: OtpErlangList) -> T
    ): T? =
        ifTagged3TupleTo(term, "case") { tuple ->
            (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                if (arguments.arity() == 2) {
                    (arguments.elementAt(1) as? OtpErlangList)?.let { blockListItems ->
                        if (blockListItems.arity() == 1) {
                            (blockListItems.elementAt(0) as? OtpErlangTuple)?.let { keywordPair ->
                                if (keywordPair.arity() == 2 && keywordPair.elementAt(0) == OtpErlangAtom("do")) {
                                    (keywordPair.elementAt(1) as? OtpErlangList)?.let { clauses ->
                                        transformer(arguments.elementAt(0), clauses)
                                    }
                                } else {
                                    null
                                }
                            }
                        } else {
                            null
                        }
                    }
                } else {
                    null
                }
            }
        }

    private inline fun <T> ifCaseClauseTo(
        term: OtpErlangObject,
        crossinline transformer: (input: OtpErlangObject, output: OtpErlangObject) -> T?
    ): T? =
        ifTagged3TupleTo(term, "->") { clause ->
            clause.elementAt(2)?.let { it as? OtpErlangList }?.takeIf { it.arity() == 2 }?.let { inputOutput ->
                transformer(inputOutput.elementAt(0), inputOutput.elementAt(1))
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/exception.ex#L310-L311
    private inline fun <T> ifErlangRewriteTo(
        term: OtpErlangObject,
        crossinline transformer: (OtpErlangObject) -> T
    ): T? =
        ifCallConvertTo(term) { module, function, arguments ->
            ifModuleFunctionRewriteTo(module, function, arguments.arity())?.let { rewritten ->
                transformer(
                    otpErlangTuple(
                        rewritten,
                        OtpErlangList(),
                        arguments
                    )
                )
            }
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/exception.ex#L302-L316
    fun rewriteGuard(guard: OtpErlangObject): OtpErlangObject =
        prewalk(guard) { macro ->
            ifErlangElementRewriteTo(macro) { it } ?: ifErlangRewriteTo(macro) { it } ?: macro
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/exception.ex#L318-L329
    private fun ifModuleFunctionRewriteTo(
        module: OtpErlangAtom,
        function: OtpErlangAtom,
        arity: Int
    ): OtpErlangObject? =
        when (module.atomValue()) {
            "erlang" -> when (function.atomValue()) {
                "self" -> when (arity) {
                    0 -> function
                    else -> null
                }
                "byte_size", "is_atom", "is_binary", "is_integer", "is_list", "is_pid", "is_map", "is_tuple",
                "length", "map_size", "not" -> when (arity) {
                    1 -> function
                    else -> null
                }
                "*", "/", "+", "++", "-", "--", "<", "==", ">=", ">", "div", "min", "rem", "send" -> when (arity) {
                    2 -> function
                    else -> null
                }
                "process_flag" -> when (arity) {
                    2, 3 -> otpErlangTuple(
                        OtpErlangAtom("."),
                        OtpErlangList(),
                        otpErlangList(
                            OtpErlangAtom("Elixir.Process"),
                            OtpErlangAtom("flag")
                        )
                    )
                    else -> null
                }
                "apply" -> when (arity) {
                    2, 3 -> function
                    else -> null
                }
                "binary_part" -> when (arity) {
                    3 -> function
                    else -> null
                }
                "fun_info" -> when (arity) {
                    1, 2 -> otpErlangTuple(
                        OtpErlangAtom("."),
                        OtpErlangList(),
                        otpErlangList(
                            OtpErlangAtom("Elixir.Function"),
                            OtpErlangAtom("info")
                        )
                    )
                    else -> null
                }
                "=<" -> when (arity) {
                    2 -> OtpErlangAtom("<=")
                    else -> null
                }
                "/=" -> when (arity) {
                    2 -> OtpErlangAtom("!=")
                    else -> null
                }
                "=:=" -> when (arity) {
                    2 -> OtpErlangAtom("===")
                    else -> null
                }
                "=/=" -> when (arity) {
                    2 -> OtpErlangAtom("!==")
                    else -> null
                }
                "andalso" -> when (arity) {
                    2 -> OtpErlangAtom("and")
                    else -> null
                }
                "atom_to_list" -> when (arity) {
                    1 -> otpErlangTuple(
                        OtpErlangAtom("."),
                        OtpErlangList(),
                        otpErlangList(
                            OtpErlangAtom("Elixir.Atom"),
                            OtpErlangAtom("to_charlist")
                        )
                    )
                    else -> null
                }
                "demonitor" -> when (arity) {
                    2 -> otpErlangTuple(
                        OtpErlangAtom("."),
                        OtpErlangList(),
                        otpErlangList(
                            OtpErlangAtom("Elixir.Process"),
                            function
                        )
                    )
                    else -> null
                }
                "node" -> when (arity) {
                    0 -> otpErlangTuple(
                        OtpErlangAtom("."),
                        OtpErlangList(),
                        otpErlangList(
                            OtpErlangAtom("Elixir.Node"),
                            OtpErlangAtom("self")
                        )
                    )
                    else -> null
                }
                "orelse" -> when (arity) {
                    2 -> OtpErlangAtom("or")
                    else -> null
                }
                "band", "bor", "bnot", "bsl", "bsr", "bxor" -> when (arity) {
                    2 -> otpErlangTuple(
                        OtpErlangAtom("."),
                        OtpErlangList(),
                        otpErlangList(
                            OtpErlangAtom("Elixir.Bitwise"),
                            function
                        )
                    )
                    else -> null
                }
                else -> null
            }
            else -> null
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L873-L882
    private fun callToString(call: OtpErlangObject): String =
        // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L873
        call.let { it as? OtpErlangAtom }?.let {
            Identifier.inspectAsFunction(it, true)
        } ?:
        // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L874
        ifTagged3TupleTo(call, ".") { tuple ->
            (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                if (arguments.arity() == 1) {
                    "${moduleToString(arguments.elementAt(0))}."
                } else {
                    null
                }
            }
        } ?:
        // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L876-L877
        ifTagged3TupleTo(call, ".") { tuple ->
            (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                if (arguments.arity() == 2) {
                    (arguments.elementAt(1) as? OtpErlangAtom)?.let { right ->
                        val left = arguments.elementAt(0)

                        moduleToString(left) + "." + Identifier.inspectAsFunction(right, false)
                    }
                } else {
                    null
                }
            }
        } ?:
        // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex#L879-L880
        ifTagged3TupleTo(call, ".") { tuple ->
            (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                if (arguments.arity() == 2) {
                    val (left, right) = arguments

                    moduleToString(left) + "." + callToString(right)
                } else {
                    null
                }
            }
        } ?: kernelToString(call)

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L783-L805
    private fun moduleToString(module: OtpErlangObject): String =
        when (module) {
            is OtpErlangAtom -> inspect(module)
            is OtpErlangTuple -> moduleToString(module)
            else -> toString(module)
        }

    private fun moduleToString(module: OtpErlangTuple): String =
        // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L787-L789
        ifTagged3TupleTo(module, "&") { tuple ->
            (tuple.elementAt(0) as? OtpErlangList)?.let { arguments ->
                if (arguments.arity() == 1 && arguments.elementAt(0) !is OtpErlangLong) {
                    "(${toString(module)})"
                } else {
                    null
                }
            }
        } ?:
        // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L791-L793
        ifTagged3TupleTo(module, "fn") {
            "(${toString(module)})"
        } ?:
        // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L795-L801
        ifTupleTo(module, 3) { tuple ->
            (tuple.elementAt(2) as? OtpErlangList)?.let { arguments ->
                if (arguments.arity() > 0) {
                    if (isKeywordBlocks(arguments.elementAt(0))) {
                        "(${toString(module)})"
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        } ?:
        // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L798-L805
        toString(module)

    private fun kernelToString(@Suppress("UNUSED_PARAMETER") term: OtpErlangObject): String {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    val NIL = OtpErlangAtom("nil")

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L282-L288
    fun prewalk(macro: OtpErlangObject, transformer: (OtpErlangObject) -> OtpErlangObject): OtpErlangObject =
        prewalk(macro, NIL) { expression, acc ->
            Pair(transformer(expression), acc)
        }.first

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L290-L297
    private fun <T : OtpErlangObject> prewalk(
        macro: OtpErlangObject,
        acc: T,
        pre: (OtpErlangObject, T) -> Pair<OtpErlangObject, T>
    ): Pair<OtpErlangObject, T> =
        traverse(macro, acc, pre, ::Pair)

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L232-L240
    private fun <T> traverse(
        macro: OtpErlangObject,
        acc: T,
        pre: (OtpErlangObject, T) -> Pair<OtpErlangObject, T>,
        post: (OtpErlangObject, T) -> Pair<OtpErlangObject, T>
    ): Pair<OtpErlangObject, T> {
        val (preMacro, preAcc) = pre(macro, acc)

        return traverseTail(preMacro, preAcc, pre, post)
    }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L271-L280
    private fun <T> traverseArguments(
        arguments: OtpErlangObject,
        acc: T,
        pre: (OtpErlangObject, T) -> Pair<OtpErlangObject, T>,
        post: (OtpErlangObject, T) -> Pair<OtpErlangObject, T>
    ): Pair<OtpErlangObject, T> =
        if (arguments is OtpErlangAtom) {
            Pair(arguments, acc)
        } else {
            var forEachAcc = acc

            // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L275-L280
            val mappedArguments = when (arguments) {
                is OtpErlangList ->
                    arguments
                        .map { element ->
                            val (preElement, preAcc) = pre(element, forEachAcc)
                            val (traverseTailElement, traverseTailAcc) = traverseTail(preElement, preAcc, pre, post)

                            forEachAcc = traverseTailAcc

                            traverseTailElement
                        }
                        .toTypedArray()
                        .let(::OtpErlangList)
                is OtpErlangString ->
                    arguments
                        .stringValue()
                        .let { string ->
                            val codePoints = OtpErlangString.stringToCodePoints(string)

                            codePoints.map { codePoint ->
                                val element = OtpErlangInt(codePoint)

                                val (preElement, preAcc) = pre(element, forEachAcc)
                                val (traverseTailElement, traverseTailAcc) = traverseTail(preElement, preAcc, pre, post)

                                forEachAcc = traverseTailAcc

                                traverseTailElement
                            }
                        }
                        .toTypedArray()
                        .let(::OtpErlangList)
                else -> {
                    TODO("Don't know how traverseArguments for ${arguments.javaClass}")
                }
            }

            Pair(mappedArguments, forEachAcc)
        }

    // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L242-L269
    private fun <T> traverseTail(
        macro: OtpErlangObject,
        acc: T,
        pre: (OtpErlangObject, T) -> Pair<OtpErlangObject, T>,
        post: (OtpErlangObject, T) -> Pair<OtpErlangObject, T>
    ): Pair<OtpErlangObject, T> =
        // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L242-L245
        ifTupleTo(macro, 3) { tuple ->
            (tuple.elementAt(0) as? OtpErlangAtom)?.let { form ->
                val meta = tuple.elementAt(1)
                val arguments = tuple.elementAt(2)

                val (traversedArguments, traversedArgumentsAcc) = traverseArguments(arguments, acc, pre, post)

                post(OtpErlangTuple(arrayOf(form, meta, traversedArguments)), traversedArgumentsAcc)
            }
        } ?:
        // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L247-L252
        ifTupleTo(macro, 3) { (form, meta, arguments) ->
            val (preForm, preAcc) = pre(form, acc)
            val (traverseTailForm, traverseTailAcc) = traverseTail(preForm, preAcc, pre, post)
            val (traverseArgumentsArguments, traverseArgumentsAcc) = traverseArguments(
                arguments,
                traverseTailAcc,
                pre,
                post
            )

            post(OtpErlangTuple(arrayOf(traverseTailForm, meta, traverseArgumentsArguments)), traverseArgumentsAcc)
        } ?:
        // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L254-L260
        ifTupleTo(macro, 2) { (left, right) ->
            val (preLeft, preLeftAcc) = pre(left, acc)
            val (traverseTailLeftLeft, traverseTailLeftAcc) = traverseTail(preLeft, preLeftAcc, pre, post)
            val (preRight, preRightAcc) = pre(right, traverseTailLeftAcc)
            val (traverseTailRightRight, traverseTailRightAcc) = traverseTail(preRight, preRightAcc, pre, post)

            post(
                OtpErlangTuple(
                    arrayOf(
                        traverseTailLeftLeft, traverseTailRightRight
                    )
                ),
                traverseTailRightAcc
            )
        } ?:
        // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L262-L265
        (macro as? OtpErlangList)?.let { list ->
            val (traverseArgumentsList, traverseArgumentsAcc) = traverseArguments(list, acc, pre, post)

            post(traverseArgumentsList, traverseArgumentsAcc)
        } ?:
        // https://github.com/elixir-lang/elixir/blob/v1.6.0-rc.1/lib/elixir/lib/macro.ex?utf8=%E2%9C%93#L267-L269
        post(macro, acc)
}

fun OtpErlangObject.toOtpErlangList(): OtpErlangList =
    when (this) {
        is OtpErlangList -> this
        is OtpErlangString -> this.stringValue().let(::OtpErlangList)
        else -> TODO("Don't know how to turn ${this.javaClass} into an OtpErlangList")
    }

private operator fun OtpErlangList.component1(): OtpErlangObject = this.elementAt(0)
private operator fun OtpErlangList.component2(): OtpErlangObject = this.elementAt(1)
private operator fun OtpErlangList.component3(): OtpErlangObject = this.elementAt(2)
private operator fun OtpErlangList.component4(): OtpErlangObject = this.elementAt(3)
