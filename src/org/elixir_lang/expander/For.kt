package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.CATCH_WHEN_ARITY_CHECKED
import org.elixir_lang.language_level.ElixirLanguageFeature.POST_MODULE_LOCAL_CHECKS_TYPED
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

/** A `for`'s [args] as its generators and filters, and its options, as `expand_for/4` splits them. */
internal fun forOptions(args: List<ElixirAst>): Pair<List<ElixirAst>, List<ElixirAst>> = splitOptions(args, true)

/**
 * `elixir_expand:expand_for/4`: the options from the state before the `for`, then each generator and filter from the
 * state the one before it left, and the `do` from the last.
 */
internal fun expandFor(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val (cases, block) = forOptions(node.arguments!!)

    Import.optionsError(Term.List(block.map(::optionTerm)), OPTIONS)?.let { return Expansion.Error(it, node) }

    val doOption = block.firstOrNull { keyOf(it) == "do" } ?: return Expansion.Error("missing_option", node)
    val options = block - doOption

    return argumentScope(state) { scope ->
        expandList(options, scope, env) { option, s, e -> expandArg(option, s, state, e, run) }
    }.thenValue { optionsState, optionsEnv, expanded ->
        mapfold(cases, optionsState, optionsEnv) { case, s, e -> generator(case, s, e, run) }
            .thenValue { generatorsState, generatorsEnv, values ->
                // `assert_generator_start/3` reads the expanded cases.
                val first = cases.firstOrNull()

                if (first?.let(::isGenerator) != true && (values as Term.List).elements.firstOrNull() != GENERATOR_NODE) {
                    return@thenValue Expansion.Error("for_generator_start", node)
                }

                val reduce = options.any { keyOf(it) == "reduce" }

                validateOptions(node, options, (expanded as Term.List).elements)
                    ?: doBlock(node, valueOf(doOption), reduce, generatorsState, generatorsEnv, run)
            }
    }.then { s, _ -> Expansion.Expanded(s.restoreVars(state), env, NODE) }.endConstruct(env, run)
}

/** [option] as `validate_opts/5` reads it before it is expanded: a pair by its key. */
private fun optionTerm(option: ElixirAst): Term =
    if (option is ElixirAst.Tuple && option.elements.size == 2) {
        Term.Pair((option.elements[0] as? ElixirAst.Literal.Atom)?.let { Term.Atom(it.name) } ?: NODE, NODE)
    } else {
        NODE
    }

/** A list generator or a bitstring generator. */
internal fun isGenerator(node: ElixirAst): Boolean = isListGenerator(node) || isBitstringGenerator(node)

/** `{'<-', _, [_, _]}`. */
internal fun isListGenerator(node: ElixirAst): Boolean = isCall(node, "<-", 2)

/** A bitstring whose last segment is a list generator. */
internal fun isBitstringGenerator(node: ElixirAst): Boolean =
    isBitstring(node) && (node as ElixirAst.Call).arguments!!.lastOrNull()?.let(::isListGenerator) == true

/** `expand_for_generator/3`: a generator's pattern, or a filter as any other expression. */
private fun generator(node: ElixirAst, state: ExState, env: Env, run: Run): Expansion =
    when {
        isListGenerator(node) -> leftArrow(node as ElixirAst.Call, state, env, run)
        isBitstringGenerator(node) -> bitstringGenerator(node as ElixirAst.Call, state, env, run)
        else -> expandNonGenerator(node, state, env, run)
    }

/**
 * A case that isn't a generator as written. From [POST_MODULE_LOCAL_CHECKS_TYPED] the type checker reads one that
 * expands to `<-/2` as the generator it is, so that call isn't checked, and the calls in its arguments are.
 */
private fun expandNonGenerator(node: ElixirAst, state: ExState, env: Env, run: Run): Expansion {
    val calls = mutableListOf<LocalCall<ElixirAst>>()

    run.callArguments.addLast(calls)

    val expansion = try {
        Expander.expand(node, state, env, run)
    } finally {
        run.callArguments.removeLast()
    }
    val generator = expansion is Expansion.Expanded && expansion.value == GENERATOR_NODE &&
        POST_MODULE_LOCAL_CHECKS_TYPED.isSufficient(run.level)

    for (call in calls) {
        if (generator && call.called == GENERATOR_CALL) call.arguments.forEach { keepLocal(it, env, run) } else keepLocal(call, env, run)
    }

    return expansion
}

/** `<-/2`, which `for` reads as a generator. */
internal val GENERATOR_CALL = NameArity("<-", 2)

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
 * `validate_for_options/8` over [options], with [expanded] each one's expanded pair, whose `uniq:` must be a boolean:
 * what stops the `for`, if anything does.
 */
private fun validateOptions(node: ElixirAst, options: List<ElixirAst>, expanded: List<Term>): Expansion? {
    var intoOrUniq = false
    var reduce = false

    for ((option, pair) in options.zip(expanded)) {
        when (keyOf(option)) {
            "into" -> intoOrUniq = true
            "uniq" ->
                when ((pair as Term.Pair).second) {
                    TRUE, FALSE -> intoOrUniq = true
                    else -> return Expansion.Error("for_invalid_uniq", node)
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
