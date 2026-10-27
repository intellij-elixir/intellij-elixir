package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.GENERATOR_RIGHT_SIDE_SCOPED
import org.elixir_lang.language_level.ElixirLanguageFeature.WITH_OPTIONS_BEFORE_LAST_ARGUMENT
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst

/** The heads of `elixir_clauses:expand_with/2`, which isn't a clause of `expand`. */
internal val WITH_HEADS = listOf(
    // Before 1.13, `with var <- e` is expanded as `var = e`.
    Clause.Head("elixir_clauses", "expand_with", 1, "{'<-',_,[{V1,_,V2},_]} when is_atom(V1), is_atom(V2)"),
    Clause.Head("elixir_clauses", "expand_with", 1, "{'<-',_,[_,_]}"),
    Clause.Head("elixir_clauses", "expand_with", 1, "_"),
)

/**
 * `elixir_expand:expand/3`'s `with`, through `elixir_clauses:with/4`: each clause from the state the one before it
 * left, the `do` from the last, and `else` from the state before the `with`.
 */
internal fun expandWith(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val (clauses, options) = withOptions(node.arguments!!, run.level)

    return mapfold(clauses, state, env, run) { clause, s, e ->
        when {
            !isCall(clause, "<-", 2) -> Expander.expand(clause, s, e, run)
            !GENERATOR_RIGHT_SIDE_SCOPED.isSufficient(run.level) && isVariable((clause as ElixirAst.Call).arguments!![0]) -> {
                val callee = clause.callee as ElixirAst.Literal.Atom

                Expander.expand(ElixirAst.Call(clause.meta, ElixirAst.Literal.Atom(callee.meta, "="), clause.arguments), s, e, run)
            }
            else -> leftArrow(clause as ElixirAst.Call, s, e, run)
        }
    }.thenAst { clausesState, clausesEnv, _, clausesAst ->
        val doOption = options.firstOrNull { keyOf(it) == "do" } ?: return@thenAst Expansion.Error("missing_option", node)
        val afterDo = options - doOption
        val built = { doAst: ExpandedAst, elseAst: ExpandedAst? ->
            run.ast {
                val pairs = listOf(ElixirAst.Tuple(doOption.meta, listOf((doOption as ElixirAst.Tuple).elements[0], doAst.or(valueOf(doOption))))) +
                    listOfNotNull(elseAst?.let { ElixirAst.Tuple(LIST_META, listOf(ElixirAst.Literal.Atom(LIST_META, "else"), it.node)) })

                ElixirAst.ListNode(LIST_META, clausesAst.elements + ElixirAst.ListNode(LIST_META, pairs))
            }
        }

        Expander.expand(valueOf(doOption), clausesState, clausesEnv, run).thenAst { doState, _, _, doAst ->
            val beforeElse = doState.restoreVars(state)
            val elseOption = afterDo.firstOrNull { keyOf(it) == "else" }

            if (elseOption == null) {
                unexpectedOption(node, afterDo) ?: Expansion.Expanded(beforeElse, env, NODE, built(doAst, null))
            } else {
                expandClauses(node, expandHead(node, run), valueOf(elseOption), beforeElse, env, run).thenAst { s, _, _, elseAst ->
                    unexpectedOption(node, afterDo - elseOption) ?: Expansion.Expanded(s, env, NODE, built(doAst, elseAst))
                }
            }
        }
    }.endConstruct(node, env, run) { (it as ElixirAst.ListNode).elements }
}

/** `left <- right` in `with` or `for`: its left as a head. */
internal fun leftArrow(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val (left, right) = node.arguments!!

    return generatorPattern(right, state, env, run, node) { after, before, patternEnv ->
        head(node, listOf(left), after, patternEnv, run, before)
    }
}

/**
 * A generator's [right] side, then its [pattern] from the variables `after` and reading a `^` from those `before`, in
 * the env it is given. From 1.13 the pattern starts from the variables before the right side, in the env the right side
 * left; before, from the variables the right side left, in the env before it, and the env the right side left follows.
 */
internal fun generatorPattern(
    right: ElixirAst,
    state: ExState,
    env: Env,
    run: Run,
    arrow: ElixirAst.Call,
    pattern: (after: ExState, before: ExState, env: Env) -> Expansion,
): Expansion =
    Expander.expand(right, state, env, run).thenAst { rightState, rightEnv, _, rightAst ->
        val patterned = if (GENERATOR_RIGHT_SIDE_SCOPED.isSufficient(run.level)) {
            rightState.resetRead(state).let { pattern(it, it, rightEnv) }
        } else {
            pattern(rightState, state, env).thenAst { s, _, _, ast -> Expansion.Expanded(s, rightEnv, NODE, ast) }
        }

        patterned.thenAst { s, e, value, patternAst ->
            Expansion.Expanded(s, e, value, run.ast {
                val built = patternAst.or(arrow.arguments!![0]).let { (it as? ElixirAst.ListNode)?.elements?.singleOrNull() ?: it }

                ElixirAst.Call(arrow.meta, arrow.callee, listOf(built, rightAst.or(right)))
            })
        }
    }

/** A source `with`'s [args] as its clauses and its options, as `expand_with` splits them on [level]. */
internal fun withOptions(args: List<ElixirAst>, level: ElixirLanguageLevel): Pair<List<ElixirAst>, List<ElixirAst>> =
    splitOptions(args, WITH_OPTIONS_BEFORE_LAST_ARGUMENT.isSufficient(level))

/**
 * `elixir_utils:split_opts/1` where [inner], and otherwise only the last argument as options: [args] as the cases and
 * the options, which are the elements of a list last, and of a list before it when both are lists.
 */
internal fun splitOptions(args: List<ElixirAst>, inner: Boolean): Pair<List<ElixirAst>, List<ElixirAst>> {
    val outer = args.lastOrNull() as? ElixirAst.ListNode ?: return args to emptyList()
    val cases = args.dropLast(1)
    val before = cases.lastOrNull() as? ElixirAst.ListNode

    return if (inner && before != null) cases.dropLast(1) to before.elements + outer.elements else cases to outer.elements
}

/** The value of [option], a pair. */
internal fun valueOf(option: ElixirAst): ElixirAst = (option as ElixirAst.Tuple).elements[1]

/** The `unexpected_option` an option [left] after the `do` and the `else` gives, or a crash where it isn't a pair. */
private fun unexpectedOption(node: ElixirAst, left: List<ElixirAst>): Expansion? {
    val first = left.firstOrNull() ?: return null

    return if (first is ElixirAst.Tuple && first.elements.size == 2) {
        Expansion.Error("unexpected_option", node)
    } else {
        // Elixir has no clause for it.
        Expansion.Unported(first)
    }
}
