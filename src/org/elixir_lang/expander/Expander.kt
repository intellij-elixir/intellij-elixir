package org.elixir_lang.expander

import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst

/**
 * Elixir's expander (`elixir_expand`), ported clause for clause, over [ElixirAst]. It holds no PSI and takes no lock.
 */
object Expander {
    /**
     * [ast] expanded from [state] and [env] as Elixir at [level] expands it, with [exports] standing for the modules
     * Elixir would load, up to the first error Elixir raises or the first node that isn't ported. [observer] is told
     * of each node reached.
     */
    fun expand(
        ast: ElixirAst,
        state: ExState,
        env: Env,
        level: ElixirLanguageLevel,
        exports: Exports,
        observer: ExpansionObserver = ExpansionObserver.NONE,
    ): Expansion = expand(ast, state, env, Run(level, observer, exports))

    internal fun expand(ast: ElixirAst, state: ExState, env: Env, run: Run): Expansion {
        ProgressManager.checkCanceled()
        run.observer.entering(ast, state, env)

        val expansion = Clause.entries
            .firstOrNull { it.matches(ast, state, env, run.level) }
            ?.expand(ast, state, env, run)
            ?: Expansion.Unported(ast)

        run.observer.left(ast, expansion)

        return expansion
    }
}

/** What every recursive expansion of one [Expander.expand] call shares. */
internal class Run(val level: ElixirLanguageLevel, val observer: ExpansionObserver, val exports: Exports)

internal inline fun Expansion.then(next: (ExState, Env) -> Expansion): Expansion =
    when (this) {
        is Expansion.Expanded -> next(state, env)
        is Expansion.Error, is Expansion.Unported -> this
    }

/** `mapfold/4`: [nodes] in order, each from the state and env the one before it left. */
internal inline fun mapfold(
    nodes: List<ElixirAst>,
    state: ExState,
    env: Env,
    expand: (ElixirAst, ExState, Env) -> Expansion,
): Expansion {
    var accState = state
    var accEnv = env

    for (node in nodes) {
        when (val expansion = expand(node, accState, accEnv)) {
            is Expansion.Expanded -> {
                accState = expansion.state
                accEnv = expansion.env
            }
            is Expansion.Error, is Expansion.Unported -> return expansion
        }
    }

    return Expansion.Expanded(accState, accEnv)
}

/** `elixir_expand:expand_args/3`: outside a pattern, what an argument binds is readable only after the last one. */
internal fun expandArgs(args: List<ElixirAst>, state: ExState, env: Env, run: Run): Expansion =
    when {
        args.size == 1 -> Expander.expand(args.single(), state, env, run)
        env.context == Env.Context.MATCH -> mapfold(args, state, env) { arg, s, e -> Expander.expand(arg, s, e, run) }
        else ->
            argumentScope(state, env) { scope ->
                mapfold(args, scope, env) { arg, s, e -> expandArg(arg, s, state, e, run) }
            }
    }

/**
 * `elixir_expand:expand_list/5`, which takes a `|` as the last element apart.
 *
 * @param expand `expand/3` in a pattern, `expand_arg/3` otherwise
 */
internal inline fun expandList(
    elements: List<ElixirAst>,
    state: ExState,
    env: Env,
    crossinline expand: (ElixirAst, ExState, Env) -> Expansion,
): Expansion =
    mapfold(elements.dropLast(1), state, env) { element, s, e -> expand(element, s, e) }.then { s, e ->
        val last = elements.lastOrNull()

        when {
            last == null -> Expansion.Expanded(s, e)
            isCall(last, "|", 2) ->
                mapfold((last as ElixirAst.Call).arguments!!, s, e) { arg, ss, ee -> expand(arg, ss, ee) }
            else -> expand(last, s, e)
        }
    }

/**
 * Expands [body] between `elixir_env:prepare_write/1` and `close_write/2`, so its bindings become readable only after
 * it.
 */
internal inline fun argumentScope(state: ExState, env: Env, body: (ExState) -> Expansion): Expansion =
    body(state.prepareWrite()).then { s, e -> Expansion.Expanded(s.closeWrite(state), e) }

/** `elixir_expand:expand_arg/3`: an argument reads only what [start], the scope's start, could. */
internal fun expandArg(arg: ElixirAst, acc: ExState, start: ExState, env: Env, run: Run): Expansion =
    if (arg is ElixirAst.Literal) {
        Expansion.Expanded(acc, env)
    } else {
        Expander.expand(arg, acc.resetRead(start), env, run)
    }

/** `{name, meta, args}` with [arity] arguments. */
internal fun isCall(node: ElixirAst, name: String, arity: Int): Boolean =
    node is ElixirAst.Call &&
        (node.callee as? ElixirAst.Literal.Atom)?.name == name &&
        node.arguments?.size == arity

/** `{'<<>>', meta, args}`. */
internal fun isBitstring(node: ElixirAst): Boolean = isNamedCall(node, "<<>>")

/** `{'%{}', meta, args}`. */
internal fun isMap(node: ElixirAst): Boolean = isNamedCall(node, "%{}")

/** `{name, meta, args}` with a list of arguments, of any length. */
internal fun isNamedCall(node: ElixirAst, name: String): Boolean =
    node is ElixirAst.Call && (node.callee as? ElixirAst.Literal.Atom)?.name == name && node.arguments != null

/** `{name, meta, context}` with an atom context: a variable, `_` included. */
internal fun isVariable(node: ElixirAst): Boolean =
    node is ElixirAst.Call && node.callee is ElixirAst.Literal.Atom && node.arguments == null

/** `{'_', meta, context}`. */
internal fun isUnderscore(node: ElixirAst): Boolean =
    isVariable(node) && ((node as ElixirAst.Call).callee as ElixirAst.Literal.Atom).name == "_"

/** The arguments of `{'when', meta, args}`, the guard last, if [node] is one. */
internal fun whenArguments(node: ElixirAst): List<ElixirAst>? =
    (node as? ElixirAst.Call)?.takeIf { isNamedCall(it, "when") }?.arguments

/**
 * [node] in the shape its expansion has, as far as a ported clause's check reads it: a block of one expression is that
 * expression, an empty block is `nil`, and an alias is an atom. Every other ported clause keeps its node's shape.
 */
internal fun expandedShape(node: ElixirAst): ElixirAst =
    when (node) {
        is ElixirAst.Block ->
            when (node.expressions.size) {
                0 -> ElixirAst.Literal.Atom(node.meta, "nil")
                1 -> expandedShape(node.expressions.single())
                else -> node
            }
        // The name is as written: only `literalShape` reads it, through the aliases.
        is ElixirAst.Alias ->
            node.segments
                .map { (it as? ElixirAst.Literal.Atom)?.name ?: return node }
                .let { ElixirAst.Literal.Atom(node.meta, concat(it)) }
        else -> node
    }
