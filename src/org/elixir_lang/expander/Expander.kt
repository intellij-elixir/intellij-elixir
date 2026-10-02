package org.elixir_lang.expander

import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term

/**
 * Elixir's expander (`elixir_expand`), ported clause for clause, over [ElixirAst]. It holds no PSI and takes no lock.
 */
object Expander {
    /**
     * [ast] expanded from [state] and [env] as Elixir at [level] expands it, with [exports] standing for the modules
     * Elixir would load, up to the first error Elixir raises or the first node that isn't ported. [observer] is told
     * of each node reached, and [counters] gives each macro expansion its hygiene counter.
     */
    fun expand(
        ast: ElixirAst,
        state: ExState,
        env: Env,
        level: ElixirLanguageLevel,
        exports: Exports,
        observer: ExpansionObserver = ExpansionObserver.NONE,
        counters: Counters = Counters(),
    ): Expansion = expand(ast, state, env, Run(level, observer, exports, counters))

    internal fun expand(ast: ElixirAst, state: ExState, env: Env, run: Run): Expansion {
        ProgressManager.checkCanceled()
        // A built node shares its source node's origin, so an observer would take it for that node.
        val observed = !ast.meta.built
        if (observed) run.observer.entering(ast, state, env)

        val expansion = Clause.entries
            .firstOrNull { it.matches(ast, state, env, run.level) }
            ?.expand(ast, state, env, run)
            ?: Expansion.Unported(ast)

        if (observed) run.observer.left(ast, expansion)

        return expansion
    }
}

/** What every recursive expansion of one [Expander.expand] call shares. */
internal class Run(
    val level: ElixirLanguageLevel,
    val observer: ExpansionObserver,
    val exports: Exports,
    val counters: Counters = Counters(),
)

internal inline fun Expansion.then(next: (ExState, Env) -> Expansion): Expansion =
    thenValue { state, env, _ -> next(state, env) }

internal inline fun Expansion.thenValue(next: (ExState, Env, Term) -> Expansion): Expansion =
    when (this) {
        is Expansion.Expanded -> next(state, env, value)
        is Expansion.Error, is Expansion.Unported, is Expansion.Opaque -> this
    }

/** This expansion with [value] in place of its own, if it expanded. */
internal fun Expansion.withValue(value: Term): Expansion = if (this is Expansion.Expanded) copy(value = value) else this

/** The value of a node that expands to an AST node other than a variable or a pin. */
internal val NODE: Term = Term.Node(Term.Node.Kind.OTHER)

/** The value of a node that expands to a variable, which `^` and `rescue ... in` accept. */
internal val VARIABLE_NODE: Term = Term.Node(Term.Node.Kind.VARIABLE)

internal val NIL = Term.Atom("nil")

internal val TRUE = Term.Atom("true")

internal val FALSE = Term.Atom("false")

/** `mapfold/4`: [nodes] in order, each from the state and env the one before it left; the value is their values. */
internal inline fun mapfold(
    nodes: List<ElixirAst>,
    state: ExState,
    env: Env,
    expand: (ElixirAst, ExState, Env) -> Expansion,
): Expansion {
    var accState = state
    var accEnv = env
    val values = ArrayList<Term>(nodes.size)

    for (node in nodes) {
        when (val expansion = expand(node, accState, accEnv)) {
            is Expansion.Expanded -> {
                accState = expansion.state
                accEnv = expansion.env
                values.add(expansion.value)
            }
            is Expansion.Error, is Expansion.Unported, is Expansion.Opaque -> return expansion
        }
    }

    return Expansion.Expanded(accState, accEnv, Term.List(values))
}

/**
 * `elixir_expand:expand_args/3`: outside a pattern, what an argument binds is readable only after the last one. The
 * value is the list of the arguments' values.
 */
internal fun expandArgs(args: List<ElixirAst>, state: ExState, env: Env, run: Run): Expansion =
    when {
        args.size == 1 -> Expander.expand(args.single(), state, env, run).thenValue { s, e, v ->
            Expansion.Expanded(s, e, Term.List(listOf(v)))
        }
        env.context == Env.Context.MATCH -> mapfold(args, state, env) { arg, s, e -> Expander.expand(arg, s, e, run) }
        else ->
            argumentScope(state, env) { scope ->
                mapfold(args, scope, env) { arg, s, e -> expandArg(arg, s, state, e, run) }
            }
    }

/**
 * `elixir_expand:expand_list/5`, which takes a `|` as the last element apart. The value is the list, with the `|`'s
 * right side as its tail.
 *
 * @param expand `expand/3` in a pattern, `expand_arg/3` otherwise
 */
internal inline fun expandList(
    elements: List<ElixirAst>,
    state: ExState,
    env: Env,
    crossinline expand: (ElixirAst, ExState, Env) -> Expansion,
): Expansion =
    mapfold(elements.dropLast(1), state, env) { element, s, e -> expand(element, s, e) }.thenValue { s, e, init ->
        val values = (init as Term.List).elements
        val last = elements.lastOrNull()

        when {
            last == null -> Expansion.Expanded(s, e, init)
            isCall(last, "|", 2) ->
                mapfold((last as ElixirAst.Call).arguments!!, s, e) { arg, ss, ee -> expand(arg, ss, ee) }
                    .thenValue { ss, ee, pair ->
                        val (head, tail) = (pair as Term.List).elements

                        Expansion.Expanded(ss, ee, Term.List(values + head, tail))
                    }
            else -> expand(last, s, e).thenValue { ss, ee, v -> Expansion.Expanded(ss, ee, Term.List(values + v)) }
        }
    }

/**
 * Expands [body] between `elixir_env:prepare_write/1` and `close_write/2`, so its bindings become readable only after
 * it.
 */
internal inline fun argumentScope(state: ExState, env: Env, body: (ExState) -> Expansion): Expansion =
    body(state.prepareWrite()).thenValue { s, e, v -> Expansion.Expanded(s.closeWrite(state), e, v) }

/** `elixir_expand:expand_arg/3`: an argument reads only what [start], the scope's start, could. */
internal fun expandArg(arg: ElixirAst, acc: ExState, start: ExState, env: Env, run: Run): Expansion =
    if (arg is ElixirAst.Literal) {
        Expansion.Expanded(acc, env, literalValue(arg))
    } else {
        Expander.expand(arg, acc.resetRead(start), env, run)
    }

internal fun literalValue(literal: ElixirAst.Literal): Term =
    when (literal) {
        is ElixirAst.Literal.Atom -> Term.Atom(literal.name)
        is ElixirAst.Literal.Integer -> Term.Integer(literal.value)
        is ElixirAst.Literal.Binary -> Term.Binary(literal.bytes)
        is ElixirAst.Literal.Float -> Term.NonTuple
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
