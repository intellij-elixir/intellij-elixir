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
import org.elixir_lang.language_level.ElixirLanguageFeature.COMPILER_VARIABLES_REFUSED_IN_PATTERN
import org.elixir_lang.language_level.ElixirLanguageFeature.UNDERSCORE_TAKES_VERSION
import org.elixir_lang.language_level.ElixirLanguageFeature.VAR_BANG_IF_UNDEFINED
import org.elixir_lang.language_level.ElixirLanguageFeature.ZERO_FLOAT_MATCH_WARNS
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term

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
                            match(left, after, state, env, run, node).then { s, _ ->
                                Expansion.Expanded(s, rightEnv, NODE)
                            }
                        }
                    }.then { s, e ->
                        when {
                            PARALLEL_MATCH.isSufficient(run.level) -> Expansion.Expanded(s, e, NODE)
                            seesRightSide -> refuteParallelBitstringMatch(left, right, false, s, e, run.level)
                            else ->
                                refuteParallelBitstringMatch(left, right, false, s, env, run.level).then { rs, _ ->
                                    Expansion.Expanded(rs, e, NODE)
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
            expandArgs((node as ElixirAst.Tuple).elements, state, env, run).withValue(NODE)
    },

    MAP(expandHead("{'%{}',_,_}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) = isMap(node)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            expandMap(node as ElixirAst.Call, state, env, run)
    },

    /** `%`, a struct, which has a clause of its own ahead of the local call's. */
    STRUCT(expandHead("{'%',_,[_,_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isCall(node, "%", 2)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            expandStruct(node as ElixirAst.Call, state, env, run)
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

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            Expansion.Expanded(state, env, NIL)
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
                val discarded = if (expression !== expressions.last()) discardedFor(expression) else null

                Expander.expand(discarded ?: expression, s, e, run)
            }.withValue(NODE)
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

    MODULE(expandHead("{'__MODULE__',_,V1} when is_atom(V1)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isVariableNamed(node, "__MODULE__")

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            Expansion.Expanded(state, env, Term.Atom(env.module ?: "nil"))
    },

    /** `__DIR__`, whose binary isn't known: `Env` holds no file. */
    DIR(expandHead("{'__DIR__',_,V1} when is_atom(V1)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isVariableNamed(node, "__DIR__")

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            Expansion.Expanded(state, env, Term.Binary(null))
    },

    CALLER(expandHead("{'__CALLER__',_,V1} when is_atom(V1)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isVariableNamed(node, "__CALLER__")

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) = expandCaller(node, env, run)
    },

    STACKTRACE(expandHead("{'__STACKTRACE__',_,V1} when is_atom(V1)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isVariable(node) && variable(node).name == "__STACKTRACE__"

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            (if (COMPILER_VARIABLES_REFUSED_IN_PATTERN.isSufficient(run.level)) noMatchScope(node, env) else null)
                ?: if (state.stacktrace) {
                    Expansion.Expanded(state, env, VARIABLE_NODE)
                } else {
                    Expansion.Error("stacktrace_not_allowed", node)
                }
    },

    /** `__ENV__`; before 1.13 a head of its own refuses it in a pattern. */
    ENV(expandHead("{'__ENV__',_,V1} when is_atom(V1)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isVariableNamed(node, "__ENV__")

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) = expandEnv(node, state, env, run)
    },

    ENV_FIELD(expandHead("{{'.',_,[{'__ENV__',_,V1},V2]},_,[]} when is_atom(V1), is_atom(V2)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            node is ElixirAst.Call &&
                node.arguments?.isEmpty() == true &&
                remoteArguments(node)?.let { (left, right) ->
                    isVariableNamed(left, "__ENV__") && right is ElixirAst.Literal.Atom
                } == true

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            expandEnvField(node as ElixirAst.Call, state, env, run)
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

    /** `&super(args)`, which `resolve_super/3` looks up. */
    CAPTURE_SUPER(expandHead("{'&',_,[{super,_,V1}]} when is_list(V1)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isCall(node, "&", 1) && isNamedCall((node as ElixirAst.Call).arguments!!.single(), "super")

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            noMatchOrGuardScope(node, state, env) ?: resolveSuper(node, env)
    },

    /** `&super/arity`, which `resolve_super/3` looks up. */
    CAPTURE_SUPER_ARITY(expandHead("{'&',_,[{'/',_,[{super,_,V1},V2]}]} when is_atom(V1), is_integer(V2)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel): Boolean {
            val arg = (node as? ElixirAst.Call)?.takeIf { isCall(it, "&", 1) }?.arguments?.single() ?: return false
            val (name, arity) = (arg as? ElixirAst.Call)?.takeIf { isCall(it, "/", 2) }?.arguments ?: return false

            return isVariable(name) && variable(name).name == "super" && arity is ElixirAst.Literal.Integer
        }

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            noMatchOrGuardScope(node, state, env) ?: resolveSuper(node, env)
    },

    CAPTURE(expandHead("{'&',_,[_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isCall(node, "&", 1)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            noMatchOrGuardScope(node, state, env) ?: expandCapture(node as ElixirAst.Call, state, env, run)
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

    FOR(expandHead("{for,_,[_|_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) = isFor(node)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            noMatchOrGuardScope(node, state, env) ?: expandFor(node as ElixirAst.Call, state, env, run)
    },

    WITH(expandHead("{with,_,[_|_]}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isNamedCall(node, "with") && (node as ElixirAst.Call).arguments!!.isNotEmpty()

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            noMatchOrGuardScope(node, state, env) ?: expandWith(node as ElixirAst.Call, state, env, run)
    },

    /** `super`, which has a clause of its own ahead of the local call's. */
    SUPER(expandHead("{super,_,V1} when is_list(V1)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isNamedCall(node, "super")

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            noMatchOrGuardScope(node, state, env) ?: resolveSuper(node, env)
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

            return Expander.expand(arg, pinState, env.copy(context = Env.Context.NONE), run).thenValue { _, _, value ->
                if (value == VARIABLE_NODE) {
                    Expansion.Expanded(state, env, Term.Node(Term.Node.Kind.PIN))
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

                Expansion.Expanded(state.copy(version = version), env, VARIABLE_NODE)
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

                Expansion.Expanded(state.copy(write = state.write.plus(variable, written)), env, VARIABLE_NODE)
            } else {
                Expansion.Expanded(
                    state.copy(
                        read = state.read + (variable to state.version),
                        write = state.write.plus(variable, state.version),
                        version = state.version + 1,
                    ),
                    env,
                    VARIABLE_NODE,
                )
            }
        }
    },

    VARIABLE(expandHead("{V1,_,V2} when is_atom(V1), is_atom(V2)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            isVariable(node)

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run): Expansion =
            when (val outcome = variableOutcome(node as ElixirAst.Call, state, run.level)) {
                VariableOutcome.Read -> Expansion.Expanded(state, env, VARIABLE_NODE)
                VariableOutcome.LocalCall -> Expander.expand(zeroArityCall(node), state, env, run)
                is VariableOutcome.Error -> Expansion.Error(outcome.kind, node)
            }
    },

    LOCAL_CALL(expandHead("{V1,V2,V3} when is_atom(V1), is_list(V2), is_list(V3)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            node is ElixirAst.Call && node.callee is ElixirAst.Literal.Atom && node.arguments != null

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            expandLocalCall(node as ElixirAst.Call, state, env, run)
    },

    REMOTE_CALL(
        expandHead(
            "{{'.',_,[V1,V2]},V3,V4} when is_tuple(V1) orelse is_atom(V1), is_atom(V2), is_list(V3), is_list(V4)"
        ),
    ) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            node is ElixirAst.Call &&
                node.arguments != null &&
                remoteArguments(node)?.let { (left, right) ->
                    isTupleOrAtom(left) && right is ElixirAst.Literal.Atom
                } == true

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            expandRemoteCall(node as ElixirAst.Call, state, env, run)
    },

    ANONYMOUS_CALL(expandHead("{{'.',_,[_]},_,V1} when is_list(V1)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            node is ElixirAst.Call && node.arguments != null && dotArguments(node.callee)?.size == 1

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            expandAnonymousCall(node as ElixirAst.Call, state, env, run)
    },

    /**
     * A call whose callee is neither a name nor a `.` call: `unquote(1)(2)`, or a remote call on a literal such as
     * `1.foo()`.
     */
    INVALID_CALL(expandHead("{_,V1,V2} when is_list(V1) and is_list(V2)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            node is ElixirAst.Call && node.arguments != null

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            Expansion.Error("invalid_call", node)
    },

    PAIR(expandHead("{_,_}")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            node is ElixirAst.Tuple && node.elements.size == 2

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            expandArgs((node as ElixirAst.Tuple).elements, state, env, run).thenValue { s, e, values ->
                val (first, second) = (values as Term.List).elements

                Expansion.Expanded(s, e, Term.Pair(first, second))
            }
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

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            Expansion.Expanded(state, env, Term.NonTuple)
    },

    LITERAL(expandHead("V1 when is_number(V1); is_atom(V1); is_binary(V1)")) {
        override fun matches(node: ElixirAst, state: ExState, env: Env, level: ElixirLanguageLevel) =
            node is ElixirAst.Literal

        override fun expand(node: ElixirAst, state: ExState, env: Env, run: Run) =
            Expansion.Expanded(state, env, literalValue(node as ElixirAst.Literal))
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

/** `{name, meta, context}` with an atom context. */
private fun isVariableNamed(node: ElixirAst, name: String): Boolean =
    isVariable(node) && ((node as ElixirAst.Call).callee as ElixirAst.Literal.Atom).name == name

/** The arguments of a `{'.', DotMeta, Args}` callee. */
internal fun dotArguments(callee: ElixirAst): List<ElixirAst>? =
    (callee as? ElixirAst.Call)?.takeIf { (it.callee as? ElixirAst.Literal.Atom)?.name == "." }?.arguments

/** `[Left, Right]` of a call whose callee is `{'.', DotMeta, [Left, Right]}`. */
private fun remoteArguments(node: ElixirAst.Call): List<ElixirAst>? = dotArguments(node.callee)?.takeIf { it.size == 2 }

/** `{Name, Meta, []}`, the local call a variable is when it isn't defined and the mode only warns. */
private fun zeroArityCall(variable: ElixirAst.Call): ElixirAst.Call =
    ElixirAst.Call(variable.meta, variable.callee, emptyList())

private val EXPAND_LIST = arrayOf(
    Clause.Head("elixir_expand", "expand_list", 1, "[]"),
    Clause.Head("elixir_expand", "expand_list", 1, "[_|_]"),
    Clause.Head("elixir_expand", "expand_list", 1, "[{'|',_,[_,_]}]"),
)

/** `{Name, var_context(Meta, Kind)}`. */
internal fun variable(node: ElixirAst): Variable {
    val call = node as ElixirAst.Call
    val context = counterOf(call.meta)?.let { Variable.Context.Counter(it) }
        ?: when (val written = call.context) {
            ElixirAst.VariableContext.Nil -> Variable.NIL
            is ElixirAst.VariableContext.Atom -> Variable.Context.Atom(written.name)
        }

    return Variable((call.callee as ElixirAst.Literal.Atom).name, context)
}

/** What the variable clause makes of a variable. */
internal sealed interface VariableOutcome {
    data object Read : VariableOutcome

    /** The local call `{Name, Meta, []}`. */
    data object LocalCall : VariableOutcome

    data class Error(val kind: String) : VariableOutcome
}

/** What the variable clause makes of [call] in [state]. */
internal fun variableOutcome(call: ElixirAst.Call, state: ExState, level: ElixirLanguageLevel): VariableOutcome {
    val variable = variable(call)
    val prematch = state.prematch
    val isRead = variable in state.read
    // A size can't read what its pattern bound before the bitstring.
    val isBitsize = isRead && prematch is Bitsize && variable !in prematch.match.read && variable in prematch.original

    if (isRead && !isBitsize) return VariableOutcome.Read

    // Only macro output marks a variable.
    if (VAR_BANG_IF_UNDEFINED.isSufficient(level)) {
        when ((metaValue(call.meta, "if_undefined") as? Meta.Value.Atom)?.name) {
            "apply" -> return VariableOutcome.LocalCall
            "raise" -> return VariableOutcome.Error("undefined_var")
        }
    } else if (!isRead && (metaValue(call.meta, "var") as? Meta.Value.Atom)?.name == "true") {
        return VariableOutcome.Error("undefined_var_bang")
    }

    if (isBitsize) return VariableOutcome.Error("undefined_var")

    return when ((prematch as? OutsideMatch)?.mode ?: OutsideMatch.Mode.Raise) {
        OutsideMatch.Mode.Warn -> VariableOutcome.LocalCall
        OutsideMatch.Mode.Raise -> VariableOutcome.Error("undefined_var")
        OutsideMatch.Mode.Pin -> VariableOutcome.Error("undefined_var_pin")
    }
}

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

/** `{for, _, [_ | _]}`. */
private fun isFor(node: ElixirAst): Boolean =
    node is ElixirAst.Call && (node.callee as? ElixirAst.Literal.Atom)?.name == "for" && !node.arguments.isNullOrEmpty()

/**
 * The `for` that `expand_block/5` sends to `expand_for/4` without expanding the rest of [node]: [node] itself, or the
 * right side of `_ = `.
 */
private fun discardedFor(node: ElixirAst): ElixirAst? {
    if (isFor(node)) return node
    if (!isCall(node, "=", 2)) return null

    val (left, right) = (node as ElixirAst.Call).arguments!!

    return right.takeIf { isUnderscore(left) && isFor(it) }
}
