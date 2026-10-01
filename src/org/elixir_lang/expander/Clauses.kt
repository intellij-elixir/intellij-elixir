package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.CATCH_WHEN_ARITY_CHECKED
import org.elixir_lang.language_level.ElixirLanguageFeature.CLAUSES_TAKE_VERSION
import org.elixir_lang.language_level.ElixirLanguageFeature.PARALLEL_MATCH
import org.elixir_lang.lowering.ElixirAst

/** The heads of `elixir_clauses`' functions that expand `->` clauses, which aren't clauses of `expand`. */
internal val CLAUSES_HEADS = listOf(
    Clause.Head("elixir_clauses", "clause", 4, "{'->',_,[_,_]} when is_function(_,3)"),
    Clause.Head("elixir_clauses", "clause", 4, "{'->',_,[_,_]} when is_function(_,4)"),
    Clause.Head("elixir_clauses", "clause", 4, "{'->',_,[_,_]}"),
    Clause.Head("elixir_clauses", "clause", 4, "_"),
    Clause.Head("elixir_clauses", "expand_case", 2, "{do,_}"),
    Clause.Head("elixir_clauses", "expand_case", 2, "{_,_}"),
    Clause.Head("elixir_clauses", "expand_cond", 2, "{do,_}"),
    Clause.Head("elixir_clauses", "expand_cond", 2, "{_,_}"),
    Clause.Head("elixir_clauses", "expand_receive", 2, "{do,{'__block__',_,[]}}"),
    Clause.Head("elixir_clauses", "expand_receive", 2, "{do,_}"),
    Clause.Head("elixir_clauses", "expand_receive", 2, "{'after',[_]}"),
    Clause.Head("elixir_clauses", "expand_receive", 2, "{'after',_}"),
    Clause.Head("elixir_clauses", "expand_receive", 2, "{_,_}"),
    Clause.Head("elixir_clauses", "expand_try", 2, "{do,_}"),
    Clause.Head("elixir_clauses", "expand_try", 2, "{'after',_}"),
    Clause.Head("elixir_clauses", "expand_try", 2, "{'else',_}"),
    Clause.Head("elixir_clauses", "expand_try", 2, "{'catch',_}"),
    Clause.Head("elixir_clauses", "expand_try", 2, "{rescue,_}"),
    Clause.Head("elixir_clauses", "expand_try", 2, "{_,_}"),
    Clause.Head("elixir_clauses", "expand_catch", 2, "[{'when',_,[_,_,_,_|_]}]"),
    Clause.Head("elixir_clauses", "expand_catch", 2, "[{'when',_,[_,_,_]}]"),
    Clause.Head("elixir_clauses", "expand_catch", 2, "[{'when',_,[_,_]}]"),
    Clause.Head("elixir_clauses", "expand_catch", 2, "[_]"),
    Clause.Head("elixir_clauses", "expand_catch", 2, "[_,_]"),
    Clause.Head("elixir_clauses", "expand_catch", 2, "_"),
    Clause.Head("elixir_clauses", "expand_rescue", 2, "[_]"),
    Clause.Head("elixir_clauses", "expand_rescue", 2, "_"),
    Clause.Head("elixir_clauses", "expand_rescue", 2, "_ when is_atom(_), is_atom(_)"),
    Clause.Head("elixir_clauses", "expand_rescue", 2, "_ when is_atom(_), is_atom(_), is_atom(_)"),
    Clause.Head("elixir_clauses", "expand_rescue", 1, "{V1,_,V2} when is_atom(V1), is_atom(V2)"),
    Clause.Head("elixir_clauses", "expand_rescue", 1, "{'__aliases__',_,[_|_]}"),
    Clause.Head(
        "elixir_clauses",
        "expand_rescue",
        1,
        "{in,_,[{V1,_,V2},{'_',_,V3}]} when is_atom(V1), is_atom(V2), is_atom(V3)"
    ),
    Clause.Head("elixir_clauses", "expand_rescue", 1, "{in,_,[_,_]}"),
    Clause.Head("elixir_clauses", "expand_rescue", 1, "{_,_,_}"),
    Clause.Head("elixir_clauses", "expand_rescue", 1, "_"),
)

/** A clause's head expanded from the state and env before the clause: `elixir_clauses:clause/6`'s `Fun`. */
internal typealias HeadExpansion = (arrow: ElixirAst.Call, args: List<ElixirAst>, state: ExState, env: Env) -> Expansion

/** `elixir_expand:expand/3`'s `case`, through `expand_case/5` and `elixir_clauses:'case'/4`. */
internal fun expandCase(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val (subject, opts) = node.arguments!!

    return Expander.expand(subject, state, env, run).then { subjectState, subjectEnv ->
        options(node, opts, listOf("do"), subjectState, subjectEnv) { key, value, s ->
            when (key) {
                "do" -> expandClauses(node, expandHead(node, run), value, s, subjectEnv, run)
                else -> Expansion.Error("unexpected_option", node)
            }
        }.endConstruct(subjectEnv, run)
    }
}

/** `elixir_expand:expand/3`'s `cond`, through `elixir_clauses:'cond'/4`. */
internal fun expandCond(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val opts = node.arguments!!.single()

    underscoreInCond(node, opts)?.let { return it }

    return options(node, opts, listOf("do"), state, env) { key, value, s ->
        when (key) {
            "do" -> expandClauses(node, expandOne(node, run), value, s, env, run)
            else -> Expansion.Error("unexpected_option", node)
        }
    }.endConstruct(env, run)
}

/** `elixir_expand:expand/3`'s `receive`, through `elixir_clauses:'receive'/4`. */
internal fun expandReceive(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion =
    options(node, node.arguments!!.single(), listOf("do", "after"), state, env) { key, value, s ->
        when (key) {
            "do" ->
                if (value is ElixirAst.Block && value.expressions.isEmpty()) {
                    Expansion.Expanded(s, env)
                } else {
                    expandClauses(node, expandHead(node, run), value, s, env, run)
                }
            "after" ->
                if (value is ElixirAst.ListNode && value.elements.size == 1) {
                    expandClauses(node, expandOne(node, run), value, s, env, run)
                } else {
                    Expansion.Error("multiple_after_clauses_in_receive", node)
                }
            else -> Expansion.Error("unexpected_option", node)
        }
    }.endConstruct(env, run)

/** `elixir_expand:expand/3`'s `try`, through `elixir_clauses:'try'/4`. */
internal fun expandTry(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val opts = node.arguments!!.single()

    if (opts is ElixirAst.ListNode && opts.elements.singleOrNull()?.let { keyOf(it) } == "do") {
        return Expansion.Error("missing_option", node)
    }

    return options(node, opts, listOf("do", "rescue", "catch", "else", "after"), state, env) { key, value, s ->
        when (key) {
            "do", "after" -> Expander.expand(value, s, env, run).then { body, _ -> Expansion.Expanded(body.restoreVars(s), env) }
            "else" -> expandClauses(node, expandHead(node, run), value, s, env, run)
            "catch" -> withStacktrace(s) { expandClauses(node, expandCatch(run), value, it, env, run) }
            "rescue" -> withStacktrace(s) { expandClauses(node, expandRescue(run), value, it, env, run) }
            else -> Expansion.Error("unexpected_option", node)
        }
    }.endConstruct(env, run)
}

/**
 * `elixir_expand:assert_no_match_or_guard_scope/4`: the error a construct that can't appear in a pattern or a guard
 * gives there, if [env] is in one.
 */
internal fun noMatchOrGuardScope(node: ElixirAst, state: ExState, env: Env): Expansion.Error? =
    noMatchScope(node, env) ?: if (env.context == Env.Context.GUARD) noGuardScope(node, state) else null

/** `elixir_expand:assert_no_match_scope/3`: the error [node] gives in a pattern, if [env] is in one. */
internal fun noMatchScope(node: ElixirAst, env: Env): Expansion.Error? =
    if (env.context == Env.Context.MATCH) Expansion.Error("invalid_pattern_in_match", node) else null

/**
 * `elixir_clauses:clause/6`: [clause]'s head expanded by [head], and then its body.
 *
 * @param construct the `case`, `cond`, `receive`, `try` or `fn`, which a clause that isn't `->` reports
 */
internal fun clause(
    construct: ElixirAst,
    head: HeadExpansion,
    clause: ElixirAst,
    state: ExState,
    env: Env,
    run: Run,
): Expansion {
    if (!isCall(clause, "->", 2)) return Expansion.Error("bad_or_missing_clauses", construct)

    val arrow = clause as ElixirAst.Call
    val (args, body) = arrow.arguments!!
    val elements = (args as? ElixirAst.ListNode)?.elements ?: return Expansion.Unported(args)

    return head(arrow, elements, state, env).then { s, e -> Expander.expand(body, s, e, run) }
}

/**
 * `elixir_clauses:head/4`: [args] as a pattern, and a guard after a `when` as a guard.
 *
 * @param before the state whose variables a `^` reads: [state] itself, except before 1.13, where a `<-` pattern starts
 *   from what its right side bound and pins what was bound before it
 */
internal fun head(
    arrow: ElixirAst,
    args: List<ElixirAst>,
    state: ExState,
    env: Env,
    run: Run,
    before: ExState = state,
): Expansion {
    val all = args.singleOrNull()?.let(::whenArguments)

    return if (!all.isNullOrEmpty()) {
        guardedHead(arrow, all.dropLast(1), all.last(), state, env, run, before)
    } else {
        match(state, before, env, arrow) { s, e -> expandArgs(args, s, e, run) }
    }
}

/**
 * `elixir_clauses:guarded_head/6`. Before 1.18 the guard is expanded inside `match`'s `Fun`, with the prematch the
 * clause began with, which leaves the same state as expanding it after `match` restores that prematch.
 */
private fun guardedHead(
    arrow: ElixirAst,
    args: List<ElixirAst>,
    guardNode: ElixirAst,
    state: ExState,
    env: Env,
    run: Run,
    before: ExState = state,
): Expansion =
    match(state, before, env, arrow) { s, e -> expandArgs(args, s, e, run) }.then { s, e ->
        guard(guardNode, s, e.copy(context = Env.Context.GUARD), run)
    }.then { s, e -> Expansion.Expanded(s, e.copy(context = Env.Context.NONE)) }

/**
 * `elixir_clauses:expand_head/2`: one argument, as a pattern. A clause of another arity raises at the construct before
 * 1.18, and at the clause from it.
 */
internal fun expandHead(construct: ElixirAst, run: Run): HeadExpansion = { arrow, args, state, env ->
    val single = args.singleOrNull()
    val at = if (PARALLEL_MATCH.isSufficient(run.level)) arrow else construct

    if (single == null || (whenArguments(single)?.size ?: 0) >= 3) {
        Expansion.Error("wrong_number_of_args_for_clause", at)
    } else {
        head(arrow, args, state, env, run)
    }
}

/** `elixir_clauses:expand_one/4`: one argument, as an expression. */
private fun expandOne(construct: ElixirAst, run: Run): HeadExpansion = { _, args, state, env ->
    if (args.size == 1) {
        expandArgs(args, state, env, run)
    } else {
        Expansion.Error("wrong_number_of_args_for_clause", construct)
    }
}

/** `elixir_clauses:expand_catch/4`: a kind and a value, or a value thrown. */
private fun expandCatch(run: Run): HeadExpansion = { arrow, args, state, env ->
    val single = args.singleOrNull()
    val whenArgs = single?.let(::whenArguments)
    val thrown = ElixirAst.Literal.Atom(arrow.meta, "throw")

    when {
        whenArgs != null && whenArgs.size >= 4 && CATCH_WHEN_ARITY_CHECKED.isSufficient(run.level) ->
            Expansion.Error("wrong_number_of_args_for_clause", arrow)
        whenArgs != null && whenArgs.size >= 4 -> head(arrow, args, state, env, run)
        whenArgs != null && whenArgs.size == 3 -> guardedHead(arrow, whenArgs.dropLast(1), whenArgs.last(), state, env, run)
        whenArgs != null && whenArgs.size == 2 -> guardedHead(arrow, listOf(thrown, whenArgs[0]), whenArgs[1], state, env, run)
        single != null -> head(arrow, listOf(thrown, single), state, env, run)
        args.size == 2 -> head(arrow, args, state, env, run)
        else -> Expansion.Error("wrong_number_of_args_for_clause", arrow)
    }
}

/** `elixir_clauses:expand_rescue/4`: one argument, which must be one of the forms `rescue` takes. */
private fun expandRescue(run: Run): HeadExpansion = { arrow, args, state, env ->
    val single = args.singleOrNull()

    if (single == null) {
        Expansion.Error("wrong_number_of_args_for_clause", arrow)
    } else {
        rescue(arrow, single, state, env, run)
    }
}

/** `elixir_clauses:expand_rescue/3`, with its `false` as `invalid_rescue_clause` at [arrow]. */
private fun rescue(arrow: ElixirAst, arg: ElixirAst, state: ExState, env: Env, run: Run): Expansion =
    when {
        isVariable(arg) -> match(arg, state, state, env, run, arg)
        isCall(arg, "in", 2) -> {
            val (left, right) = (arg as ElixirAst.Call).arguments!!

            if (isUnderscore(right) && isVariable(left)) {
                match(left, state, state, env, run, left)
            } else {
                rescueIn(arrow, arg, left, right, state, env, run)
            }
        }
        // Elixir's `{_, _, _}` shape (a call, a block, a tuple not of two) is macro-expanded once from 1.15, and before
        // is expanded as `_ in` it; neither is ported.
        arg is ElixirAst.Call || arg is ElixirAst.Block || arg is ElixirAst.Placeholder ||
            arg is ElixirAst.Tuple && arg.elements.size != 2 -> Expansion.Unported(arg)
        else -> rescueIn(arrow, arg, underscore(arg), arg, state, env, run)
    }

/** `rescue left in right`, where [right] must expand to an atom or a list of atoms, and [left] be a variable. */
private fun rescueIn(
    arrow: ElixirAst,
    at: ElixirAst,
    left: ElixirAst,
    right: ElixirAst,
    state: ExState,
    env: Env,
    run: Run,
): Expansion =
    match(left, state, state, env, run, at).then { leftState, leftEnv ->
        Expander.expand(right, leftState, leftEnv, run).then { s, e ->
            val rights = expandedShape(right).let { if (it is ElixirAst.ListNode) it.elements else listOf(it) }

            if (isVariable(expandedShape(left)) && rights.all { expandedShape(it) is ElixirAst.Literal.Atom }) {
                Expansion.Expanded(s, e)
            } else {
                Expansion.Error("invalid_rescue_clause", arrow)
            }
        }
    }

/** The `_` that a `rescue` without `in` matches against. */
private fun underscore(at: ElixirAst): ElixirAst =
    ElixirAst.Call(at.meta, ElixirAst.Literal.Atom(at.meta, "_"), null)

/** `elixir_clauses:expand_clauses_with_stacktrace/5`: `__STACKTRACE__` is readable in the clauses [expand] expands. */
private inline fun withStacktrace(state: ExState, expand: (ExState) -> Expansion): Expansion =
    expand(state.copy(stacktrace = true)).then { s, e -> Expansion.Expanded(s.copy(stacktrace = state.stacktrace), e) }

/**
 * `elixir_clauses:expand_clauses/6`: each clause of [clauses] from the variables before it, keeping the version it
 * reached. A non-empty list of clauses is required.
 */
internal fun expandClauses(
    construct: ElixirAst,
    head: HeadExpansion,
    clauses: ElixirAst,
    state: ExState,
    env: Env,
    run: Run,
): Expansion {
    val elements = (clauses as? ElixirAst.ListNode)?.elements?.takeIf { it.isNotEmpty() }
        ?: return Expansion.Error("bad_or_missing_clauses", construct)

    return mapfold(elements, state, env) { each, s, _ -> clauseFrom(construct, head, each, s, env, run) }
}

/** [each] as a [clause] from [state], with [state]'s variables after it. */
internal fun clauseFrom(
    construct: ElixirAst,
    head: HeadExpansion,
    each: ElixirAst,
    state: ExState,
    env: Env,
    run: Run,
): Expansion =
    clause(construct, head, each, state, env, run).then { clauseState, _ ->
        Expansion.Expanded(clauseState.restoreVars(state), env)
    }

/** `elixir_env:merge_and_check_unused_vars/3`: [before]'s variables, with everything else this state reached. */
internal fun ExState.restoreVars(before: ExState): ExState = copy(read = before.read, write = before.write)

/**
 * The options of `case`, `cond`, `receive` or `try`, each expanded by [expand] from the state the one before it left:
 * a non-empty list in which each of [once] appears at most once.
 */
private inline fun options(
    construct: ElixirAst,
    options: ElixirAst,
    once: List<String>,
    state: ExState,
    env: Env,
    expand: (key: String?, value: ElixirAst, ExState) -> Expansion,
): Expansion {
    if (options !is ElixirAst.ListNode) return Expansion.Error("invalid_args", construct)
    if (options.elements.isEmpty()) return Expansion.Error("missing_option", construct)
    if (once.any { key -> options.elements.count { keyOf(it) == key } > 1 }) {
        return Expansion.Error("duplicated_clauses", construct)
    }

    return mapfold(options.elements, state, env) { option, s, _ ->
        // Elixir has no clause for an option that isn't a pair.
        if (option !is ElixirAst.Tuple || option.elements.size != 2) {
            Expansion.Unported(option)
        } else {
            expand(keyOf(option), option.elements[1], s)
        }
    }
}

/** The atom [option] pairs a value with, if it is such a pair. */
internal fun keyOf(option: ElixirAst): String? =
    ((option as? ElixirAst.Tuple)?.takeIf { it.elements.size == 2 }?.elements?.first() as? ElixirAst.Literal.Atom)?.name

/**
 * The end of a `case`, `cond`, `receive`, `try` or `fn`: its clauses' state, which from 1.20 takes a version, and
 * [env], the env it returns.
 */
internal fun Expansion.endConstruct(env: Env, run: Run): Expansion =
    then { state, _ ->
        val version = if (CLAUSES_TAKE_VERSION.isSufficient(run.level)) state.version + 1 else state.version

        Expansion.Expanded(state.copy(version = version), env)
    }

/** `elixir_expand:assert_no_underscore_clause_in_cond/2`, which reads only a lone `do`. */
private fun underscoreInCond(node: ElixirAst, options: ElixirAst): Expansion? {
    val clauses = (options as? ElixirAst.ListNode)?.elements?.singleOrNull()
        ?.takeIf { keyOf(it) == "do" }
        ?.let { (it as ElixirAst.Tuple).elements[1] as? ElixirAst.ListNode }
        ?.elements
        ?: return null
    // `lists:last([])` raises.
    val last = clauses.lastOrNull() ?: return Expansion.Unported(node)
    val args = (last as? ElixirAst.Call)?.takeIf { isCall(it, "->", 2) }?.arguments?.first() as? ElixirAst.ListNode

    return if (args?.elements?.singleOrNull()?.let(::isUnderscore) == true) {
        Expansion.Error("underscore_in_cond", last)
    } else {
        null
    }
}
