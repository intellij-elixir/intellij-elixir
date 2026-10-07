package org.elixir_lang.expander

import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFMODULE_FAST_PATH
import org.elixir_lang.language_level.ElixirLanguageFeature.FAST_PATH_ADDS_CONTEXT_MODULE
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term
import java.util.IdentityHashMap

/**
 * Elixir's expander (`elixir_expand`), ported clause for clause, over [ElixirAst]. It holds no PSI and takes no lock.
 */
object Expander {
    /**
     * [ast] expanded from [state] and [env] as Elixir at [level] expands it, with [exports] standing for the modules
     * Elixir would load and [structs] for their structs, up to the first error Elixir raises or the first node that
     * isn't ported. [observer] is told of each node reached, and [counters] gives each macro expansion its hygiene
     * counter. An error Elixir reports and carries on after isn't returned, and a definition or module [ast] defines is
     * never compiled; [expandFile] does both.
     */
    fun expand(
        ast: ElixirAst,
        state: ExState,
        env: Env,
        level: ElixirLanguageLevel,
        exports: Exports,
        structs: Structs,
        observer: ExpansionObserver = ExpansionObserver.NONE,
        counters: Counters = Counters(),
    ): Expansion = expand(ast, state, env, Run(level, observer, exports, structs, counters))

    internal fun expand(ast: ElixirAst, state: ExState, env: Env, run: Run): Expansion =
        observed(ast, state, env, run) {
            Clause.entries
                .firstOrNull { it.matches(ast, state, env, run.level) }
                ?.expand(ast, state, env, run)
                ?: Expansion.Unported(ast)
        }

    /**
     * A file's top-level [forms] expanded from [env] as Elixir at [level] compiles them: the forms, then each module
     * they define, in turn, with [counters] giving each macro expansion its hygiene counter. A file of only modules
     * compiles each directly from [DEFMODULE_FAST_PATH].
     */
    internal fun expandFile(
        forms: ElixirAst,
        env: Env,
        level: ElixirLanguageLevel,
        exports: Exports,
        structs: Structs,
        observer: ExpansionObserver = ExpansionObserver.NONE,
        counters: Counters = Counters(),
    ): FileExpansion {
        val run = Run(level, observer, exports, structs, counters)
        val state = ExState.empty(level)

        if (DEFMODULE_FAST_PATH.isSufficient(level) && env.module == null && onlyDefmodule(forms)) {
            return fastCompile(forms, state, env, run)
        }

        val top = expand(forms, state, env, run)

        return FileExpansion(top, if (top is Expansion.Error) emptyList() else compilePending(run))
    }

    /** `elixir_compiler:fast_compile/2` for each of [forms]: each module compiled directly, with no module variables. */
    private fun fastCompile(forms: ElixirAst, state: ExState, env: Env, run: Run): FileExpansion {
        val modules = mutableListOf<ExpansionResult>()

        for (form in defmodules(forms)) {
            val (nameNode, options) = form.arguments!!
            val (name, isAtom) = fastName(nameNode, env, run.level)
                ?: return FileExpansion(Expansion.Unported(nameNode), modules)
            val contextModules =
                if (FAST_PATH_ADDS_CONTEXT_MODULE.isSufficient(run.level)) listOf(name) + env.contextModules
                else env.contextModules
            val body = ((options as ElixirAst.ListNode).elements.single() as ElixirAst.Tuple).elements[1]
            val moduleEnv = env.copy(module = name, contextModules = contextModules)
            val result = compileModule(Pending.Module(form, name, isAtom, body, moduleEnv, state), run)

            modules += result

            if (result.ended.raises) break
        }

        return FileExpansion(Expansion.Expanded(state, env, NODE), modules)
    }

    /** `expand_defmodule/2`'s walk: each `defmodule` in [forms], into nested blocks. */
    private fun defmodules(forms: ElixirAst): List<ElixirAst.Call> =
        if (forms is ElixirAst.Block) forms.expressions.flatMap(::defmodules) else listOf(forms as ElixirAst.Call)

    /**
     * `expand_defmodule/2`'s name: an alias as `expand_or_concat` gives it, otherwise `Macro.expand/2`'s, which
     * [env] gives `__MODULE__` alone or leading an alias. With whether it is an atom, or `null` where it isn't ported.
     */
    private fun fastName(nameNode: ElixirAst, env: Env, level: ElixirLanguageLevel): Pair<String, Boolean>? {
        val module = env.module ?: "nil"

        return when {
            nameNode is ElixirAst.Literal.Atom -> nameNode.name to true
            nameNode is ElixirAst.Alias -> {
                val head = nameNode.segments.first()
                val tail = nameNode.segments.drop(1).map { (it as? ElixirAst.Literal.Atom)?.name ?: return null }

                aliasesModule(nameNode, env, level)?.let { it to true }
                    ?: if (isModuleVariable(head)) concat(listOf(module) + tail) to true else null
            }
            isModuleVariable(nameNode) -> module to true
            nameNode is ElixirAst.Literal.Integer -> inspected(Term.Integer(nameNode.value))?.let { it to false }
            nameNode is ElixirAst.Literal.Binary -> inspected(Term.Binary(nameNode.bytes))?.let { it to false }
            else -> null
        }
    }

    private fun isModuleVariable(node: ElixirAst): Boolean =
        isVariable(node) && ((node as ElixirAst.Call).callee as ElixirAst.Literal.Atom).name == "__MODULE__"

    /** [ast] expanded by [body] in place of its clause, with the observer told of it as of any node. */
    internal inline fun observed(ast: ElixirAst, state: ExState, env: Env, run: Run, body: () -> Expansion): Expansion {
        ProgressManager.checkCanceled()
        // A built node shares its source node's origin, so an observer would take it for that node.
        val observed = !ast.meta.built
        if (observed) run.observer.entering(ast, state, env)

        val expansion = body()

        if (observed) run.observer.left(ast, expansion)

        run.watched(ast, expansion)

        return expansion
    }
}

/**
 * A node of the output [expandQuoted] expands whose value the caller reads after: [value] is the term it expanded to, or
 * `null` where the expansion never reached it or stopped at it.
 */
internal class Watched(val node: ElixirAst) {
    var value: Term? = null
}

/** What every recursive expansion of one [Expander.expand] call shares. */
internal class Run(
    val level: ElixirLanguageLevel,
    val observer: ExpansionObserver,
    exports: Exports,
    val structs: Structs,
    val counters: Counters = Counters(),
) {
    /** The modules whose exports the run read, in the order it read them. */
    val consulted = mutableListOf<String>()

    /** The nodes being expanded whose value a summary reads, by identity, until they are read. */
    val watching = IdentityHashMap<ElixirAst, Watched>()

    /** [node] was expanded to [expansion], which the watch of it reads. */
    fun watched(node: ElixirAst, expansion: Expansion) {
        watching.remove(node)?.value = (expansion as? Expansion.Expanded)?.value
    }

    /** What each module the run compiled exports, which Elixir loads once `elixir_module:compile` returns. */
    private val compiled = HashMap<String, ModuleExports>()

    /**
     * What each module exports: one the run compiled, what it defined; one it is compiling, nothing, as Elixir hasn't
     * loaded it; any other, what [exports] gives.
     */
    val exports = Exports { module ->
        compiled[module] ?: if (module in compiling) {
            ModuleExports.Absent
        } else {
            consulted += module
            exports.of(module)
        }
    }

    /** The struct of each module the run compiled, which Elixir loads once `elixir_module:compile` returns. */
    private val compiledStructs = HashMap<String, ModuleStruct>()

    /**
     * [module]'s struct: one the run is compiling, the record its `defstruct` made, or none before it, and unreadable
     * where the module defines `__struct__/1` itself, which Elixir calls; one it compiled, what it left; any other,
     * what [structs] gives.
     */
    fun structOf(module: String): ModuleStruct {
        val inProgress = compiling[module] ?: return compiledStructs[module] ?: structs.of(module)

        return recorded(inProgress.struct, inProgress.table)
    }

    /** The [struct] a module's `defstruct` recorded, or where it recorded none, `Unreadable` if [table] defines `__struct__/1` itself. */
    private fun recorded(struct: ModuleStruct?, table: DefinitionTable): ModuleStruct =
        struct ?: if (table[NameArity("__struct__", 1)] != null) ModuleStruct.Unreadable else ModuleStruct.Absent

    /** [result]'s module loaded, with the [struct] its `defstruct` recorded, as Elixir loads a module once it compiles. */
    fun load(result: ExpansionResult, struct: ModuleStruct?) {
        val table = result.table
        val named = { kind: DefinitionTable.Kind ->
            table.entries.filterValues { it.kind == kind }.keys.sortedWith(NAME_ARITY_ORDER)
        }

        val unreadable = result.ended is ExpansionResult.Ended.Stopped || table.unnamed.isNotEmpty()
        val loaded = !unreadable && result.ended == ExpansionResult.Ended.Compiled

        compiledStructs[result.module] = when {
            unreadable -> ModuleStruct.Unreadable
            loaded -> recorded(struct, table)
            else -> ModuleStruct.Absent
        }
        compiled[result.module] = when {
            unreadable -> ModuleExports.Unreadable
            loaded -> ModuleExports.Present(named(DefinitionTable.Kind.DEF), named(DefinitionTable.Kind.DEFMACRO), true)
            else -> ModuleExports.Absent
        }
    }

    /** What each bitstring expanded builds, by node, for the bitstring that nests it. */
    val bitstrings = IdentityHashMap<ElixirAst, BitstringParts>()

    /** The errors Elixir reported and carried on after, in its order. No state restore takes them back. */
    val errors = mutableListOf<Reported>()

    /** How Elixir's own code raised after a reported error, if it did. */
    var crash: Crash? = null

    /** Each module whose body or definitions are being expanded, by name. */
    val compiling = HashMap<String, Compiling>()

    /** The definitions and modules the body being expanded defines, in the order it reached them. */
    var pending = mutableListOf<Pending>()

    /**
     * For each local call whose arguments are being expanded, or default argument, innermost last, the local calls in
     * it.
     */
    val callArguments = ArrayDeque<MutableList<LocalCall<ElixirAst>>>()
}

internal inline fun Expansion.then(next: (ExState, Env) -> Expansion): Expansion =
    thenValue { state, env, _ -> next(state, env) }

internal inline fun Expansion.thenValue(next: (ExState, Env, Term) -> Expansion): Expansion =
    when (this) {
        is Expansion.Expanded -> next(state, env, value)
        is Expansion.Error, is Expansion.Unported, is Expansion.Opaque -> this
    }

/** This expansion with [value] in place of its own, if it expanded. */
internal fun Expansion.withValue(value: Term): Expansion = if (this is Expansion.Expanded) copy(value = value) else this

/** `?line(Meta)`: the `line` in [meta], or 0. */
internal fun line(meta: Meta): Int = location(meta)?.line ?: 0

/** The line and column in [meta], if it has them. */
internal fun location(meta: Meta): Meta.Position? =
    meta.keys.firstNotNullOfOrNull { (it as? Meta.Key.Location)?.position }

/** The value of a node that expands to an AST node other than a variable or a pin. */
internal val NODE: Term = Term.Node(Term.Node.Kind.OTHER)

/** The value of a node that expands to a variable, which `^` and `rescue ... in` accept. */
internal val VARIABLE_NODE: Term = Term.Node(Term.Node.Kind.VARIABLE)

/** The value of a node other than a boolean that `elixir_utils:returns_boolean/1` holds of. */
internal val BOOLEAN_NODE: Term = Term.Node(Term.Node.Kind.BOOLEAN)

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
    expandArgs(args, state, env) { arg, s, e -> Expander.expand(arg, s, e, run) }

/** `elixir_expand:expand_args/3`, with [expand] for its `expand/3`. */
internal inline fun expandArgs(
    args: List<ElixirAst>,
    state: ExState,
    env: Env,
    expand: (ElixirAst, ExState, Env) -> Expansion,
): Expansion =
    when {
        args.size == 1 -> expand(args.single(), state, env).thenValue { s, e, v ->
            Expansion.Expanded(s, e, Term.List(listOf(v)))
        }
        env.context == Env.Context.MATCH -> mapfold(args, state, env, expand)
        else ->
            argumentScope(state, env) { scope ->
                mapfold(args, scope, env) { arg, s, e -> expandArg(arg, s, state, e, expand) }
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

/**
 * `elixir_expand:expand_arg/3`: an argument reads only what [start], the scope's start, could. A literal isn't expanded,
 * so [Expander.observed] never reports it to the watch, and this does.
 */
internal fun expandArg(arg: ElixirAst, acc: ExState, start: ExState, env: Env, run: Run): Expansion =
    expandArg(arg, acc, start, env) { a, s, e -> Expander.expand(a, s, e, run) }
        .also { if (arg is ElixirAst.Literal) run.watched(arg, it) }

/** `elixir_expand:expand_arg/3`, with [expand] for its `expand/3`. */
internal inline fun expandArg(
    arg: ElixirAst,
    acc: ExState,
    start: ExState,
    env: Env,
    expand: (ElixirAst, ExState, Env) -> Expansion,
): Expansion =
    if (arg is ElixirAst.Literal) {
        Expansion.Expanded(acc, env, literalValue(arg))
    } else {
        expand(arg, acc.resetRead(start), env)
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
