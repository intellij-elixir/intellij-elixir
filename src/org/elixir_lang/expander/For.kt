package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.CATCH_WHEN_ARITY_CHECKED
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import
import org.elixir_lang.psi.Import.Term

/** The heads of `elixir_expand`'s functions that check `for`, which aren't clauses of `expand`. */
internal val FOR_HEADS = listOf(
    Clause.Head("elixir_expand", "assert_generator_start", 2, "[{'<-',_,[_,_]}|_]"),
    Clause.Head("elixir_expand", "assert_generator_start", 2, "[{'<<>>',_,[{'<-',_,[_,_]}]}|_]"),
    Clause.Head("elixir_expand", "assert_generator_start", 2, "_"),
    Clause.Head("elixir_expand", "validate_for_options", 1, "[{into,_}|_]"),
    Clause.Head("elixir_expand", "validate_for_options", 1, "[{uniq,V1}|_] when is_boolean(V1)"),
    Clause.Head("elixir_expand", "validate_for_options", 1, "[{uniq,_}|_]"),
    Clause.Head("elixir_expand", "validate_for_options", 1, "[{reduce,_}|_]"),
    Clause.Head("elixir_expand", "validate_for_options", 1, "[] when _ /= false; _ /= false"),
    Clause.Head("elixir_expand", "validate_for_options", 1, "[]"),
)

/**
 * `elixir_expand:expand_for/4`: the options from the state before the `for`, then each generator and filter from the
 * state the one before it left, and the `do` from the last.
 */
internal fun expandFor(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val (cases, block) = splitOptions(node.arguments!!, true)

    Import.optionsError(Term.List(block.map(::optionTerm)), OPTIONS)?.let { return Expansion.Error(it, node) }

    val doOption = block.firstOrNull { keyOf(it) == "do" } ?: return Expansion.Error("missing_option", node)
    val options = block - doOption

    return argumentScope(state, env) { scope ->
        expandList(options, scope, env) { option, s, e -> expandArg(option, s, state, e, run) }
    }.then { optionsState, optionsEnv ->
        mapfold(cases, optionsState, optionsEnv) { case, s, e -> generator(case, s, e, run) }
    }.then { generatorsState, generatorsEnv ->
        if (cases.firstOrNull()?.let(::isGenerator) != true) return@then Expansion.Error("for_generator_start", node)

        validateOptions(node, options)
            ?: doBlock(node, valueOf(doOption), options.any { keyOf(it) == "reduce" }, generatorsState, generatorsEnv, run)
    }.then { s, _ -> Expansion.Expanded(s.restoreVars(state), env) }.endConstruct(env, run)
}

/** [option] as `validate_opts/5` reads it before it is expanded: a pair by its key. */
private fun optionTerm(option: ElixirAst): Term =
    if (option is ElixirAst.Tuple && option.elements.size == 2) {
        Term.Pair((option.elements[0] as? ElixirAst.Literal.Atom)?.let { Term.Atom(it.name) } ?: Term.Other, Term.Other)
    } else {
        Term.Other
    }

/** `{'<-', _, [_, _]}`, or a bitstring whose last segment is one. */
internal fun isGenerator(node: ElixirAst): Boolean =
    isCall(node, "<-", 2) ||
        isBitstring(node) && (node as ElixirAst.Call).arguments!!.lastOrNull()?.let { isCall(it, "<-", 2) } == true

/** `expand_for_generator/3`: a generator's pattern, or a filter as any other expression. */
private fun generator(node: ElixirAst, state: ExState, env: Env, run: Run): Expansion =
    when {
        isCall(node, "<-", 2) -> leftArrow(node as ElixirAst.Call, state, env, run)
        isGenerator(node) -> bitstringGenerator(node as ElixirAst.Call, state, env, run)
        else -> Expander.expand(node, state, env, run)
    }

/** `<<segments, last <- right>>`: the segments with `last` as a bitstring pattern whose every segment is sized. */
private fun bitstringGenerator(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val segments = node.arguments!!
    val (last, right) = (segments.last() as ElixirAst.Call).arguments!!
    val pattern = ElixirAst.Call(node.meta, node.callee, segments.dropLast(1) + last)

    return generatorPattern(right, state, env, run) { after, before, patternEnv ->
        match(after, before, patternEnv, node) { s, e -> expandBitstring(pattern, s, e, run, requireSize = true) }
    }
}

/**
 * `validate_for_options/8` over [options] as expanded, which a `uniq:` must be a boolean literal in: what stops the
 * `for`, if anything does.
 */
private fun validateOptions(node: ElixirAst, options: List<ElixirAst>): Expansion? {
    var intoOrUniq = false
    var reduce = false

    for (option in options) {
        when (keyOf(option)) {
            "into" -> intoOrUniq = true
            "uniq" ->
                if ((expandedShape(valueOf(option)) as? ElixirAst.Literal.Atom)?.name in BOOLEANS) {
                    intoOrUniq = true
                } else {
                    return Expansion.Error("for_invalid_uniq", node)
                }
            "reduce" -> reduce = true
            // Elixir has no clause for it.
            else -> return Expansion.Unported(option)
        }
    }

    return if (reduce && intoOrUniq) Expansion.Error("for_conflicting_reduce_into_uniq", node) else null
}

/** `expand_for_do_block/5`: the body, or with `reduce:` each `->` clause from the state the generators left. */
private fun doBlock(node: ElixirAst, body: ElixirAst, reduce: Boolean, state: ExState, env: Env, run: Run): Expansion {
    val clauses = (body as? ElixirAst.ListNode)?.elements?.takeIf { it.firstOrNull()?.let { first -> isNamedCall(first, "->") } == true }

    return when {
        !reduce && clauses != null -> Expansion.Error("for_without_reduce_bad_block", node)
        !reduce -> Expander.expand(body, state, env, run)
        clauses == null -> Expansion.Error("for_with_reduce_bad_block", node)
        else -> {
            val accHead: HeadExpansion = { arrow, args, s, e -> head(arrow, args, s, e, run) }

            mapfold(clauses, state, env) { clause, s, _ ->
                val args = ((clause as? ElixirAst.Call)?.arguments?.takeIf { it.size == 2 }?.first() as? ElixirAst.ListNode)
                    ?.elements
                    ?.singleOrNull()
                val arityChecked = CATCH_WHEN_ARITY_CHECKED.isSufficient(run.level)

                if (args == null || arityChecked && (whenArguments(args)?.size ?: 0) >= 3) {
                    Expansion.Error("for_with_reduce_bad_block", node)
                } else {
                    clauseFrom(node, accHead, clause, s, env, run)
                }
            }
        }
    }
}

private val OPTIONS = setOf("do", "into", "uniq", "reduce")

private val BOOLEANS = setOf("true", "false")
