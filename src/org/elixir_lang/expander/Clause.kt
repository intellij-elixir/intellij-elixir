package org.elixir_lang.expander

import org.elixir_lang.expander.ExState.Prematch.Bitsize
import org.elixir_lang.expander.ExState.Prematch.InMatch
import org.elixir_lang.expander.ExState.Prematch.OutsideMatch
import org.elixir_lang.expander.ExState.Write
import org.elixir_lang.language_level.ElixirLanguageFeature.CURSOR_RAISES
import org.elixir_lang.language_level.ElixirLanguageFeature.MISPLACED_TYPE_AND_CONS_OPERATORS
import org.elixir_lang.language_level.ElixirLanguageFeature.PARALLEL_MATCH
import org.elixir_lang.language_level.ElixirLanguageFeature.PATTERN_SEES_RIGHT_SIDE_ENV
import org.elixir_lang.language_level.ElixirLanguageFeature.PIN_IN_BITSTRING_SIZE
import org.elixir_lang.language_level.ElixirLanguageFeature.REPEATED_PATTERN_VARIABLE_WRITTEN_AT_NEXT_VERSION
import org.elixir_lang.language_level.ElixirLanguageFeature.STACKTRACE_REFUSED_IN_PATTERN
import org.elixir_lang.language_level.ElixirLanguageFeature.UNDERSCORE_TAKES_VERSION
import org.elixir_lang.language_level.ElixirLanguageFeature.ZERO_FLOAT_MATCH_WARNS
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst

/**
 * The ported clauses of Elixir's expander, in the order Elixir tries them: [Expander] takes the first entry that
 * [matches]. Each entry declares the heads of Elixir's expander it ports, as the leg manifests render them, and gives
 * [Expansion.Error] for each error branch.
 */
internal enum class Clause(vararg val heads: Head) {
    /** `=` inside a pattern from 1.18: `elixir_clauses:parallel_match/4`. */
    MATCH_IN_PATTERN(
        expandHead("{'=',_,[_,_]}"),
        Head("elixir_clauses", "unpack_match", 1, "{'=',_,[_,_]}"),
        Head("elixir_clauses", "unpack_match", 1, "_"),
    ) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isCall(node, "=", 2) && env.context == Env.Context.MATCH && PARALLEL_MATCH.isSufficient(level)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) = parallelMatch(node, state, env, run)
    },

    MATCH(expandHead("{'=',_,[_,_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isCall(node, "=", 2)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run): Expansion {
            val (left, right) = (node as ElixirAst.Call).arguments!!

            return when (env.context) {
                Env.Context.GUARD -> noGuardScope(node, state)
                // Only below 1.18, where `match` passes the pattern straight to `expand`.
                Env.Context.MATCH ->
                    Expander.expand(right, state, env, run).then { rightState, rightEnv ->
                        Expander.expand(left, rightState, rightEnv, run)
                    }.then { s, e -> refuteParallelBitstringMatch(left, right, true, s, e, run.level) }
                Env.Context.NONE -> {
                    val seesRightSide = PATTERN_SEES_RIGHT_SIDE_ENV.isSufficient(run.level)

                    Expander.expand(right, state, env, run).then { after, rightEnv ->
                        if (seesRightSide) {
                            match(left, after, state, rightEnv, run, node)
                        } else {
                            match(left, after, state, env, run, node).then { s, _ -> Expansion.Expanded(s, rightEnv) }
                        }
                    }.then { s, e ->
                        when {
                            PARALLEL_MATCH.isSufficient(run.level) -> Expansion.Expanded(s, e)
                            seesRightSide -> refuteParallelBitstringMatch(left, right, false, s, e, run.level)
                            else ->
                                refuteParallelBitstringMatch(left, right, false, s, env, run.level).then { rs, _ ->
                                    Expansion.Expanded(rs, e)
                                }
                        }
                    }
                }
            }
        }
    },

    TUPLE(expandHead("{'{}',_,_}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            node is ElixirAst.Tuple && node.elements.size != 2

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            expandArgs((node as ElixirAst.Tuple).elements, state, env, run)
    },

    MAP(expandHead("{'%{}',_,_}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) = isMap(node)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            expandMap(node as ElixirAst.Call, state, env, run)
    },

    BITSTRING(expandHead("{'<<>>',_,_}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) = isBitstring(node)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            expandBitstring(node as ElixirAst.Call, state, env, run)
    },

    /** `->` outside the clauses of a call that takes them. */
    STRAY_ARROW(expandHead("{'->',_,_}"), expandHead("{'->',_,[_,_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isCall(node, "->", 2)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            Expansion.Error("unhandled_arrow_op", node)
    },

    /** `::` outside a bitstring. */
    STRAY_TYPE_OPERATOR(expandHead("{'::',_,[_,_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isCall(node, "::", 2) && MISPLACED_TYPE_AND_CONS_OPERATORS.isSufficient(level)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            Expansion.Error("unhandled_type_op", node)
    },

    /** `|` outside a list or a map update. */
    STRAY_CONS_OPERATOR(expandHead("{'|',_,[_,_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isCall(node, "|", 2) && MISPLACED_TYPE_AND_CONS_OPERATORS.isSufficient(level)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            Expansion.Error("unhandled_cons_op", node)
    },

    EMPTY_BLOCK(expandHead("{'__block__',_,[]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            node is ElixirAst.Block && node.expressions.isEmpty()

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) = Expansion.Expanded(state, env)
    },

    SINGLE_BLOCK(expandHead("{'__block__',_,[_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            node is ElixirAst.Block && node.expressions.size == 1

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            Expander.expand((node as ElixirAst.Block).expressions.single(), state, env, run)
    },

    /** `expand_block/5`. */
    BLOCK(expandHead("{'__block__',_,V1} when is_list(V1)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            node is ElixirAst.Block

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run): Expansion {
            val expressions = (node as ElixirAst.Block).expressions

            return mapfold(expressions, state, env) { expression, s, e ->
                if (expression !== expressions.last() && isDiscardedFor(expression)) {
                    Expansion.Unported(expression)
                } else {
                    Expander.expand(expression, s, e, run)
                }
            }
        }
    },

    ALIASES(expandHead("{'__aliases__',_,_}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            node is ElixirAst.Alias

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            expandAliasesClause(node as ElixirAst.Alias, state, env, run)
    },

    /** `alias`, `require` or `import` of `Base.{A, B}`. */
    MULTI_ALIAS(
        expandHead("{V1,_,[{{'.',_,[_,'{}']},_,_}|_]} when V1 == alias; V1 == require; V1 == import"),
    ) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            Directive.entries.any { isNamedCall(node, it.atom) } &&
                isMultiAlias((node as ElixirAst.Call).arguments!!.firstOrNull())

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            expandMultiAlias(node as ElixirAst.Call, state, env, run)
    },

    ALIAS(expandHead("{alias,_,[_]}"), expandHead("{alias,_,[_,_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isDirective(node, Directive.ALIAS)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            expandDirective(Directive.ALIAS, node, state, env, run)
    },

    REQUIRE(expandHead("{require,_,[_]}"), expandHead("{require,_,[_,_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isDirective(node, Directive.REQUIRE)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            expandDirective(Directive.REQUIRE, node, state, env, run)
    },

    IMPORT(expandHead("{import,_,[_]}"), expandHead("{import,_,[_,_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isDirective(node, Directive.IMPORT)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            expandDirective(Directive.IMPORT, node, state, env, run)
    },

    STACKTRACE(expandHead("{'__STACKTRACE__',_,V1} when is_atom(V1)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isVariable(node) && variable(node).name == "__STACKTRACE__"

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            (if (STACKTRACE_REFUSED_IN_PATTERN.isSufficient(run.level)) noMatchScope(node, env) else null)
                ?: if (state.stacktrace) Expansion.Expanded(state, env) else Expansion.Error("stacktrace_not_allowed", node)
    },

    /**
     * `__MODULE__`, `__DIR__`, `__CALLER__` and `__ENV__`, which have a variable's shape and clauses of their own ahead
     * of the variables'.
     */
    ENVIRONMENT_NAME(*ENVIRONMENT_NAMES.map { expandHead("{'$it',_,V1} when is_atom(V1)") }.toTypedArray()) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isEnvironmentName(node)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) = Expansion.Unported(node)
    },

    CURSOR(expandHead("{'__cursor__',_,V1} when is_list(V1)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isNamedCall(node, "__cursor__") && CURSOR_RAISES.isSufficient(level)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            Expansion.Error("__cursor__", node)
    },

    UNQUOTE_OUTSIDE_QUOTE(expandHead("{V1,_,[_]} when V1 == unquote; V1 == unquote_splicing")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isCall(node, "unquote", 1) || isCall(node, "unquote_splicing", 1)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            Expansion.Error("unquote_outside_quote", node)
    },

    QUOTE_KEYWORDS(expandHead("{quote,_,[V1]} when is_list(V1)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isCall(node, "quote", 1) && (node as ElixirAst.Call).arguments!!.single() is ElixirAst.ListNode

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            Quote.expandKeywords(node as ElixirAst.Call, state, env, run)
    },

    QUOTE_INVALID_ARGUMENT(expandHead("{quote,_,[_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isCall(node, "quote", 1)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) = Expansion.Error("invalid_args", node)
    },

    QUOTE(expandHead("{quote,_,[_,V1]} when is_list(V1)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isCall(node, "quote", 2) && (node as ElixirAst.Call).arguments!![1] is ElixirAst.ListNode

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            Quote.expand(node as ElixirAst.Call, state, env, run)
    },

    QUOTE_INVALID_ARGUMENTS(expandHead("{quote,_,[_,_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isCall(node, "quote", 2)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) = Expansion.Error("invalid_args", node)
    },

    FN(expandHead("{fn,_,_}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isNamedCall(node, "fn")

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            noMatchOrGuardScope(node, state, env) ?: expandFn(node as ElixirAst.Call, state, env, run)
    },

    COND(expandHead("{'cond',_,[_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isCall(node, "cond", 1)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            noMatchOrGuardScope(node, state, env) ?: expandCond(node as ElixirAst.Call, state, env, run)
    },

    CASE(expandHead("{'case',_,[_,_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isCall(node, "case", 2)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            noMatchOrGuardScope(node, state, env) ?: expandCase(node as ElixirAst.Call, state, env, run)
    },

    RECEIVE(expandHead("{'receive',_,[_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isCall(node, "receive", 1)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            noMatchOrGuardScope(node, state, env) ?: expandReceive(node as ElixirAst.Call, state, env, run)
    },

    TRY(expandHead("{'try',_,[_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isCall(node, "try", 1)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            noMatchOrGuardScope(node, state, env) ?: expandTry(node as ElixirAst.Call, state, env, run)
    },

    /** `^` while a pattern is being expanded, which reads the variables from before the pattern. */
    PIN(expandHead("{'^',_,[_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isCall(node, "^", 1) &&
                if (PIN_IN_BITSTRING_SIZE.isSufficient(level)) {
                    state.prematch is InMatch || state.prematch is Bitsize
                } else {
                    env.context == Env.Context.MATCH
                }

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run): Expansion {
            val arg = (node as ElixirAst.Call).arguments!!.single()
            val before = when (val prematch = state.prematch) {
                is InMatch -> prematch.read
                is Bitsize -> prematch.match.read
                is OutsideMatch -> error("a pattern without the variables from before it, which Elixir never builds")
            }
            val pinState = state.copy(read = before, prematch = OutsideMatch(OutsideMatch.Mode.Pin))

            return Expander.expand(arg, pinState, env.copy(context = Env.Context.NONE), run).then { _, _ ->
                if (isVariable(expandedShape(arg))) {
                    Expansion.Expanded(state, env)
                } else {
                    Expansion.Error("invalid_arg_for_pin", node)
                }
            }
        }
    },

    PIN_OUTSIDE_PATTERN(expandHead("{'^',_,[_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isCall(node, "^", 1)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            Expansion.Error("pin_outside_of_match", node)
    },

    UNDERSCORE(expandHead("{'_',_,V1} when is_atom(V1)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isUnderscore(node)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            if (env.context == Env.Context.MATCH) {
                val version = if (UNDERSCORE_TAKES_VERSION.isSufficient(run.level)) state.version + 1 else state.version

                Expansion.Expanded(state.copy(version = version), env)
            } else {
                Expansion.Error("unbound_underscore", node)
            }
    },

    VARIABLE_IN_PATTERN(expandHead("{V1,_,V2} when is_atom(V1), is_atom(V2)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isVariable(node) && env.context == Env.Context.MATCH

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run): Expansion {
            val variable = variable(node)
            val bound = state.read[variable]

            return if (bound != null && bound >= (state.prematch as InMatch).version) {
                val written = if (REPEATED_PATTERN_VARIABLE_WRITTEN_AT_NEXT_VERSION.isSufficient(run.level)) {
                    state.version
                } else {
                    bound
                }

                Expansion.Expanded(state.copy(write = state.write.plus(variable, written)), env)
            } else {
                Expansion.Expanded(
                    state.copy(
                        read = state.read + (variable to state.version),
                        write = state.write.plus(variable, state.version),
                        version = state.version + 1,
                    ),
                    env
                )
            }
        }
    },

    VARIABLE(expandHead("{V1,_,V2} when is_atom(V1), is_atom(V2)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isVariable(node)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run): Expansion {
            val variable = variable(node)
            val prematch = state.prematch

            return if (variable in state.read) {
                // A size can't read what its pattern bound before the bitstring.
                if (prematch is Bitsize && variable !in prematch.match.read && variable in prematch.original) {
                    Expansion.Error("undefined_var", node)
                } else {
                    Expansion.Expanded(state, env)
                }
            } else {
                when ((prematch as? OutsideMatch)?.mode ?: OutsideMatch.Mode.Raise) {
                    // A local call of no arguments.
                    OutsideMatch.Mode.Warn -> Expansion.Unported(node)
                    OutsideMatch.Mode.Raise -> Expansion.Error("undefined_var", node)
                    OutsideMatch.Mode.Pin -> Expansion.Error("undefined_var_pin", node)
                }
            }
        }
    },

    /**
     * A call whose callee is neither a name nor a `.` call: `unquote(1)(2)`, or a remote call on a literal such as
     * `1.foo()`.
     */
    INVALID_CALL(expandHead("{_,V1,V2} when is_list(V1) and is_list(V2)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel): Boolean {
            if (node !is ElixirAst.Call || node.arguments == null || node.callee is ElixirAst.Literal.Atom) return false

            val dot = node.callee as? ElixirAst.Call
            val dotArguments = dot?.arguments?.takeIf { (dot.callee as? ElixirAst.Literal.Atom)?.name == "." }
            val isRemote = dotArguments?.size == 2 &&
                isTupleOrAtom(dotArguments[0]) &&
                dotArguments[1] is ElixirAst.Literal.Atom
            val isAnonymous = dotArguments?.size == 1

            return !isRemote && !isAnonymous
        }

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            Expansion.Error("invalid_call", node)
    },

    PAIR(expandHead("{_,_}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            node is ElixirAst.Tuple && node.elements.size == 2

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            expandArgs((node as ElixirAst.Tuple).elements, state, env, run)
    },

    LIST_IN_PATTERN(expandHead("V1 when is_list(V1)"), *EXPAND_LIST) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            node is ElixirAst.ListNode && env.context == Env.Context.MATCH

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            expandList((node as ElixirAst.ListNode).elements, state, env) { element, s, e ->
                Expander.expand(element, s, e, run)
            }
    },

    LIST(expandHead("V1 when is_list(V1)"), *EXPAND_LIST) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            node is ElixirAst.ListNode

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            argumentScope(state, env) { scope ->
                expandList((node as ElixirAst.ListNode).elements, scope, env) { element, s, e ->
                    expandArg(element, s, state, e, run)
                }
            }
    },

    /** From 1.16 Elixir warns of `0.0` in a pattern; warnings aren't recorded. */
    ZERO_FLOAT_IN_PATTERN(expandHead("V1 when is_float(V1), V1 == 0.0")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            node is ElixirAst.Literal.Float &&
                node.value == 0.0 &&
                env.context == Env.Context.MATCH &&
                ZERO_FLOAT_MATCH_WARNS.isSufficient(level)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) = Expansion.Expanded(state, env)
    },

    LITERAL(expandHead("V1 when is_number(V1); is_atom(V1); is_binary(V1)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            node is ElixirAst.Literal

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) = Expansion.Expanded(state, env)
    };

    abstract fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel): Boolean

    abstract fun expand(node: ElixirAst, state: ExState, env: Env, run: Run): Expansion

    /** `{module, function, argument, pattern}`: one line of a leg's `expander-clauses.txt`, less its count. */
    data class Head(val module: String, val function: String, val argument: Int, val pattern: String) {
        override fun toString() = "$module $function $argument $pattern"
    }
}

/**
 * `elixir_expand:assert_no_guard_scope/4` for [node], which is in a guard: the error names a bitstring size when
 * [state] is expanding one.
 */
internal fun noGuardScope(node: ElixirAst, state: ExState): Expansion.Error =
    Expansion.Error(if (state.prematch is Bitsize) "invalid_expr_in_bitsize" else "invalid_expr_in_guard", node)

private fun expandHead(pattern: String) = Clause.Head("elixir_expand", "expand", 1, pattern)

private val ENVIRONMENT_NAMES = listOf("__MODULE__", "__DIR__", "__CALLER__", "__ENV__")

private fun isDirective(node: ElixirAst, directive: Directive) =
    isCall(node, directive.atom, 1) || isCall(node, directive.atom, 2)

private fun expandDirective(directive: Directive, node: ElixirAst, state: ExState, env: Env, run: Run): Expansion {
    val arguments = (node as ElixirAst.Call).arguments!!

    return expandDirective(directive, node, arguments[0], arguments.getOrNull(1), state, env, run)
}

/** `{{'.', _, [Base, '{}']}, _, Refs}`. */
private fun isMultiAlias(node: ElixirAst?): Boolean =
    node is ElixirAst.Call && node.arguments != null && (node.callee as? ElixirAst.Call)?.let { dot ->
        isCall(dot, ".", 2) && (dot.arguments!![1] as? ElixirAst.Literal.Atom)?.name == "{}"
    } == true

internal fun isEnvironmentName(node: ElixirAst): Boolean =
    isVariable(node) && ((node as ElixirAst.Call).callee as ElixirAst.Literal.Atom).name in ENVIRONMENT_NAMES

private val EXPAND_LIST = arrayOf(
    Clause.Head("elixir_expand", "expand_list", 1, "[]"),
    Clause.Head("elixir_expand", "expand_list", 1, "[_|_]"),
    Clause.Head("elixir_expand", "expand_list", 1, "[{'|',_,[_,_]}]"),
)

internal fun variable(node: ElixirAst) =
    Variable(((node as ElixirAst.Call).callee as ElixirAst.Literal.Atom).name, "nil")

private fun Write.plus(variable: Variable, version: Int): Write =
    when (this) {
        Write.NotWriting -> this
        is Write.Writing -> Write.Writing(vars + (variable to version))
    }

/** Whether [node]'s term is an Erlang tuple or atom. */
private fun isTupleOrAtom(node: ElixirAst): Boolean =
    when (node) {
        is ElixirAst.Literal.Atom, is ElixirAst.Call, is ElixirAst.Alias, is ElixirAst.Block, is ElixirAst.Tuple,
        is ElixirAst.Placeholder -> true
        is ElixirAst.Literal, is ElixirAst.ListNode -> false
    }

/** `{for, _, [_ | _]}`, or `_ = ` one, which `expand_block/5` sends to `expand_for/4`. */
private fun isDiscardedFor(node: ElixirAst): Boolean {
    fun isFor(candidate: ElixirAst) =
        candidate is ElixirAst.Call &&
            (candidate.callee as? ElixirAst.Literal.Atom)?.name == "for" &&
            !candidate.arguments.isNullOrEmpty()

    if (isFor(node)) return true
    if (!isCall(node, "=", 2)) return false

    val (left, right) = (node as ElixirAst.Call).arguments!!

    return isUnderscore(left) && isFor(right)
}
