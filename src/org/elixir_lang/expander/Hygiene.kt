package org.elixir_lang.expander

import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.language_level.ElixirLanguageFeature.LINIFY_CASCADES_GENERATED
import org.elixir_lang.language_level.ElixirLanguageFeature.LINIFY_GENERATED_ONCE
import org.elixir_lang.language_level.ElixirLanguageFeature.MACRO_ARGUMENTS_STOP_GENERATED
import org.elixir_lang.language_level.ElixirLanguageFeature.VAR_BANG_IF_UNDEFINED
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term
import java.util.Collections
import java.util.IdentityHashMap

/** The `counter` entry of [meta]: `{Module, n}`, or the integer Elixir draws when there is no module. */
internal fun counterOf(meta: Meta): Env.Counter? =
    metaValue(meta, "counter")
        ?.let(ElixirAst::counter)
        ?.let { (module, n) -> Env.Counter.of(module, n) }

/** [counter] as the `counter` entry of metadata holds it, which [counterOf] reads back. */
internal fun counterMetaValue(counter: Env.Counter): Meta.Value =
    when (counter) {
        is Env.Counter.InModule ->
            Meta.Value.Tuple(listOf(Meta.Value.Atom(counter.module), Meta.Value.Integer(counter.n)))
        is Env.Counter.Unique -> Meta.Value.Integer(counter.n)
    }

/**
 * `elixir_module:next_counter/1`: each module's macro expansions, counted from 1. One expansion owns it, and it isn't
 * thread-safe.
 */
class Counters {
    private val counts = HashMap<String?, Long>()

    /**
     * The counter of the next expansion in [module]. With no module, Elixir draws `erlang:unique_integer()`; this draws
     * from a count of its own, which keeps identity and is deterministic.
     */
    fun next(module: String?): Env.Counter {
        return Env.Counter.of(module, counts.merge(module, 1, Long::plus)!!)
    }

    /** How many counters [module] has given. */
    fun count(module: String?): Long = counts[module] ?: 0

    /** [module] starts compiling: Elixir builds its data tables, and its counter in them, anew, whatever a module of the name drew. */
    fun start(module: String) {
        counts.remove(module)
    }
}

/**
 * `elixir_dispatch:expand_quoted/7`: [output], what the macro [receiver] gave for [call], linified with the next
 * counter and expanded where [call] was.
 */
internal fun expandQuoted(
    call: ElixirAst.Call,
    receiver: String,
    output: ElixirAst,
    state: ExState,
    env: Env,
    run: Run,
    watched: Watched? = null,
    supplied: Map<ElixirAst, Term> = emptyMap(),
    ports: Boolean = false,
): Expansion {
    return expandQuoted(call, receiver, run.counters.next(env.module), output, state, env, run, watched, supplied, ports)
}

/**
 * [expandQuoted] with the [counter] the macro's output was given, for a macro that also uses it outside the output.
 * [watched], a node of [output], is told the value it expands to, and each node of [output] that [supplied], a map by
 * identity, has takes that value. Each node of [output] keeps its [Run.placedAt] place; where [call] has one, a node
 * the macro built takes [call]'s. Where the caller [ports] the raises of [output] itself, or [call] is [Run.ported], each
 * node the macro built is [Run.ported]; a node of [call]'s arguments is only where it already was, though it shares the
 * call's origin where the call is itself a macro's output.
 */
internal fun expandQuoted(
    call: ElixirAst.Call,
    receiver: String,
    counter: Env.Counter,
    output: ElixirAst,
    state: ExState,
    env: Env,
    run: Run,
    watched: Watched? = null,
    supplied: Map<ElixirAst, Term> = emptyMap(),
    ports: Boolean = false,
): Expansion {
    var watching: ElixirAst? = null
    val inheriting = inheriting(call, run)
    val porting = ports || call in run.ported
    val arguments = if (porting) descendants(call.arguments.orEmpty()) else emptySet()
    val linified = linifiedOutput(call, receiver, counter, output, run) { original, linified ->
        if (original === watched?.node) watching = linified
        if (porting && original !in arguments) run.ported += linified

        // A node in a module body is expanded when the module is compiled, after this call returns.
        supplied[original]?.let { run.supplying[linified] = it }
        inheriting(original, linified)
    }

    run.statementsAt(env)?.rewrote(call, linified)
    recordDispatch(call, ModuleClauses.Dispatched.Macro(linified), env, run)

    watching?.let { run.watching[it] = watched!! }

    val expansion = try {
        Expander.expand(linified, state, env, run)
    } finally {
        watching?.let { run.watching.remove(it) }
    }

    // The call is the bitstring its output expanded to, for the segment of a bitstring that holds the call.
    run.bitstrings[expandedShape(linified)]?.let { run.bitstrings[call] = it }

    return expansion
}

/** Each node of [nodes] and every node in them, by identity. */
private fun descendants(nodes: List<ElixirAst>): Set<ElixirAst> {
    val found: MutableSet<ElixirAst> = Collections.newSetFromMap(IdentityHashMap())
    val pending = ArrayDeque(nodes)

    while (pending.isNotEmpty()) {
        ProgressManager.checkCanceled()

        val node = pending.removeLast()

        if (found.add(node)) pending.addAll(children(node))
    }

    return found
}

/**
 * Gives each node that linify copies from the output of the macro [call] dispatched what the node it copies has
 * ([Run.inherits]), and, where the macro built it and [call] has one, [call]'s [Run.placedAt] place.
 */
internal fun inheriting(call: ElixirAst.Call, run: Run): (original: ElixirAst, linified: ElixirAst) -> Unit {
    val place = run.placedAt[call]

    return { original, linified ->
        run.inherits(original, linified)
        if (original !in run.placedAt) place?.takeIf { builtBy(original, call) }?.let { run.placedAt[linified] = it }
    }
}

/**
 * `linify_with_context_counter/3`: [line] added to every node with metadata that has none, unless it is 0, [counter] to
 * a `quote` or a variable of [receiver]'s context, an alias, and an `alias`, `import` or `require` call, at the heads
 * [Linify] chooses, and with [generated], the caller's `generated: true`, from [LINIFY_CASCADES_GENERATED] each node
 * marked so but at and below a `stop_generated: true`, where [Linify.readsStopGenerated] reads one, or a node whose
 * metadata is one of [stopsAt], which `stop_generated/1` marked.
 */
internal fun linifyWithContextCounter(
    line: Int,
    receiver: String,
    counter: Env.Counter,
    level: ElixirLanguageLevel,
    node: ElixirAst,
    generated: Boolean = false,
    stopsAt: Set<Meta> = emptySet(),
    onNode: (original: ElixirAst, linified: ElixirAst) -> Unit = { _, _ -> },
): ElixirAst {
    val linifier = Linify(receiver, level)
    val marks = generated && LINIFY_CASCADES_GENERATED.isSufficient(level)
    val counterValue = counterMetaValue(counter)
    val onceOnly = LINIFY_GENERATED_ONCE.isSufficient(level)

    fun lined(meta: Meta): Meta = if (line == 0) meta else keynew(meta, "line", Meta.Value.Integer(line.toLong()))

    fun counted(meta: Meta, clause: LinifyClause): Meta =
        if (linifier.counted(clause) { (metaValue(meta, "context") as? Meta.Value.Atom)?.name == it }) keynew(meta, "counter", counterValue) else meta

    /**
     * `do_tuple_linify/6`'s metadata of [node] and whether the nodes under it are marked: a `stop_generated: true` it
     * reads deleted, which stops the marking, as [node]'s metadata being one of [stopsAt] does, and [line] added, with
     * the mark before it from [Linify.readsStopGenerated] and after it before that.
     */
    fun tupled(node: ElixirAst, meta: Meta, gen: Boolean): Pair<Meta, Boolean> =
        when {
            node.meta in stopsAt -> lined(meta) to false
            linifier.readsStopGenerated && isTrueAt(meta, "stop_generated") -> lined(meta.withKeys(keydelete(meta.keys, "stop_generated"))) to false
            !gen -> lined(meta) to false
            linifier.readsStopGenerated -> lined(generatedOnce(meta)) to true
            onceOnly -> generatedOnce(lined(meta)) to true
            else -> markedGenerated(lined(meta)) to true
        }

    fun linify(node: ElixirAst, gen: Boolean): ElixirAst {
        ProgressManager.checkCanceled()

        return when (node) {
            is ElixirAst.Call -> {
                val arguments = node.arguments
                val right = when {
                    arguments == null -> LinifyRight.Atom(
                        when (val context = node.context) {
                            ElixirAst.VariableContext.Nil -> "nil"
                            is ElixirAst.VariableContext.Atom -> context.name
                        },
                    )
                    arguments.isEmpty() -> LinifyRight.Other
                    else -> LinifyRight.NonEmptyList
                }
                val clause = linifier.clause((node.callee as? ElixirAst.Literal.Atom)?.name, true, right)
                val (meta, inner) = tupled(node, counted(node.meta, clause), gen)

                ElixirAst.Call(meta, linify(node.callee, inner), arguments?.map { linify(it, inner) }, node.context)
            }
            is ElixirAst.Alias -> {
                val right = if (node.segments.isEmpty()) LinifyRight.Other else LinifyRight.NonEmptyList
                val (meta, inner) = tupled(node, counted(node.meta, linifier.clause("__aliases__", true, right)), gen)

                ElixirAst.Alias(meta, node.segments.map { linify(it, inner) })
            }
            is ElixirAst.Block -> {
                val (meta, inner) = tupled(node, node.meta, gen)

                ElixirAst.Block(meta, node.expressions.map { linify(it, inner) })
            }
            is ElixirAst.Tuple -> {
                val (meta, inner) = if (node.hasMetadata()) tupled(node, node.meta, gen) else node.meta to gen

                ElixirAst.Tuple(meta, node.elements.map { linify(it, inner) })
            }
            is ElixirAst.ListNode -> ElixirAst.ListNode(node.meta, node.elements.map { linify(it, gen) })
            is ElixirAst.Placeholder -> ElixirAst.Placeholder(tupled(node, node.meta, gen).first, node.reason)
            is ElixirAst.Literal, is ElixirAst.Value -> node
        }.also { onNode(node, it) }
    }

    return linify(node, marks)
}

/**
 * [linifyWithContextCounter] of [output], [call]'s macro's, at [line], with [call]'s `generated: true` stopped where
 * `stop_generated/1` marked its arguments ([Run.stopsGenerated]).
 */
internal fun linifiedOutput(
    call: ElixirAst.Call,
    receiver: String,
    counter: Env.Counter,
    output: ElixirAst,
    run: Run,
    line: Int = lineOf(call.meta),
    onNode: (original: ElixirAst, linified: ElixirAst) -> Unit = { _, _ -> },
): ElixirAst =
    linifyWithContextCounter(
        line,
        receiver,
        counter,
        run.level,
        output,
        isTrueAt(call.meta, "generated"),
        run.stopsGenerated[call].orEmpty(),
        onNode,
    )

/** Whether [meta]'s first [key] entry is `true`, as `lists:keyfind/3` finds it. */
internal fun isTrueAt(meta: Meta, key: String): Boolean = metaValue(meta, key).isTrue()

/** Whether this key is the entry `{name, true}`. */
internal fun Meta.Key.isTrue(name: String): Boolean = this is Meta.Key.Entry && this.name == name && value.isTrue()

/** Whether this value is the atom `true`. */
private fun Meta.Value?.isTrue(): Boolean = (this as? Meta.Value.Atom)?.name == "true"

/** `elixir_utils:generated/1`: [meta] marked `generated: true`, unless that is already its first entry. */
private fun generatedOnce(meta: Meta): Meta =
    if (meta.keys.firstOrNull()?.isTrue("generated") == true) meta else markedGenerated(meta)

/** `?line(Meta)`: the `line` in [meta], from the source or added by [linifyWithContextCounter], or 0. */
internal fun lineOf(meta: Meta): Int =
    when (val key = meta.keys.firstOrNull { it is Meta.Key.Location || it is Meta.Key.Entry && it.name == "line" }) {
        is Meta.Key.Location -> key.position.line
        is Meta.Key.Entry -> (key.value as? Meta.Value.Integer)?.value?.toInt() ?: 0
        null -> 0
    }

/** `Kernel.var!/1,2`. */
internal val VAR_BANG = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val (variable, context) = node.arguments!!.let { it[0] to it.getOrNull(1) }

        if (variable !is ElixirAst.Call || variable.arguments != null || variable.callee !is ElixirAst.Literal.Atom) {
            return Summary.Output.Raised("var_bang_not_a_variable")
        }

        val expandedContext = if (context == null) "nil" else when (val expanded = macroExpand(context, state, env, run)) {
            is MacroExpanded.Node ->
                (expanded.node as? ElixirAst.Literal.Atom)?.name
                    ?: return Summary.Output.Raised("var_bang_context_not_atom")
            MacroExpanded.Dir -> return Summary.Output.Raised("var_bang_context_not_atom")
            is MacroExpanded.Stopped -> return Summary.Output.Stopped(expanded.expansion)
        }
        val (key, value) = if (VAR_BANG_IF_UNDEFINED.isSufficient(run.level)) "if_undefined" to "raise" else "var" to "true"
        val keys = keystore(keydelete(variable.meta.keys, "counter"), key, Meta.Value.Atom(value))
        val meta = variable.meta.withKeys(keys).also { carry(node, variable.meta, it, run) }
        val variableContext =
            if (expandedContext == "nil") ElixirAst.VariableContext.Nil else ElixirAst.VariableContext.Atom(expandedContext)

        return Summary.Output.Built(ElixirAst.Call(meta, variable.callee, null, variableContext))
    }
}

/** `Kernel.alias!/1`. */
internal val ALIAS_BANG = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output =
        when (val alias = node.arguments!!.single()) {
            is ElixirAst.Literal.Atom -> Summary.Output.Built(alias)
            is ElixirAst.Alias -> {
                val meta = alias.meta
                val keys = keydelete(meta.keys, "alias")
                val rebuilt = meta.withKeys(keys).also { carry(node, meta, it, run) }

                Summary.Output.Built(ElixirAst.Alias(rebuilt, alias.segments))
            }
            else -> Summary.Output.Raised("alias_bang_function_clause")
        }
}

/** [rebuilt], [original] with its keys changed, keeps the `stop_generated` mark [original] has at [call]. */
private fun carry(call: ElixirAst.Call, original: Meta, rebuilt: Meta, run: Run) {
    run.stopsGenerated[call]?.takeIf { original in it }?.add(rebuilt)
}

/** `keynew/3`: [meta] with [name] put first, unless it has one; a source location is a `line`. */
private fun keynew(meta: Meta, name: String, value: Meta.Value): Meta {
    val has = meta.keys.any { (it is Meta.Key.Location && name == "line") || (it is Meta.Key.Entry && it.name == name) }

    return if (has) meta else Meta(meta.origin, meta.start, meta.end, listOf(Meta.Key.Entry(name, value)) + meta.keys, meta.built)
}

/** `lists:keystore/4`: [keys] with [name]'s entry replaced in place, or added last. */
private fun keystore(keys: List<Meta.Key>, name: String, value: Meta.Value): List<Meta.Key> {
    val index = keys.indexOfFirst { it is Meta.Key.Entry && it.name == name }
    val entry = Meta.Key.Entry(name, value)

    return if (index < 0) keys + entry else keys.toMutableList().apply { set(index, entry) }
}

/** `lists:keydelete/3`: [keys] without the first entry named [name]. */
private fun keydelete(keys: List<Meta.Key>, name: String): List<Meta.Key> {
    val index = keys.indexOfFirst { it is Meta.Key.Entry && it.name == name }

    return if (index < 0) keys else keys.toMutableList().apply { removeAt(index) }
}

/** `elixir_quote`'s `?lexical/1`. */
internal val LEXICAL = setOf("import", "alias", "require")

/** A `{Left, Meta, Right}` node's `Right`, as `do_linify/4`'s heads read it. */
internal sealed interface LinifyRight {
    data object NonEmptyList : LinifyRight

    data class Atom(val name: String) : LinifyRight

    data object Other : LinifyRight
}

/** The `do_linify/4` head a `{Left, Meta, Right}` node takes. */
internal enum class LinifyClause {
    /** `{quote, Meta, [_ | _]}`, given the counter where its `context` is the receiver. */
    QUOTE,

    /** A variable of the receiver's context, or `alias`, `import`, `require` or `__aliases__` with arguments. */
    COUNTED,

    /** Any other node with list metadata. */
    TUPLE,

    /** Not a node: `do_linify(_, Else, _, _) -> Else`. */
    ELSE,
}

/**
 * `do_linify/4`'s and `do_tuple_linify/6`'s choices for a macro's result from [receiver], read by
 * [linifyWithContextCounter] over [ElixirAst] and by the evaluator's walk over the result's term, which raises where
 * Elixir's walk raises.
 */
internal class Linify(private val receiver: String, level: ElixirLanguageLevel) {
    /** Whether `do_tuple_linify/6` reads `stop_generated` from a node's metadata, deleting it where it is `true`. */
    val readsStopGenerated: Boolean = MACRO_ARGUMENTS_STOP_GENERATED.isSufficient(level)

    /** The head `{left, meta, right}` takes: [left] is its atom's name, if it is one, and [metaIsList] is `is_list(Meta)`. */
    fun clause(left: String?, metaIsList: Boolean, right: LinifyRight): LinifyClause =
        when {
            left == "quote" && metaIsList && right == LinifyRight.NonEmptyList -> LinifyClause.QUOTE
            left != null && left != "_" && metaIsList && right == LinifyRight.Atom(receiver) -> LinifyClause.COUNTED
            (left in LEXICAL || left == "__aliases__") && right == LinifyRight.NonEmptyList -> LinifyClause.COUNTED
            metaIsList -> LinifyClause.TUPLE
            else -> LinifyClause.ELSE
        }

    /**
     * Whether `do_linify/4` gives a node of [clause] the counter: a [LinifyClause.QUOTE] only where [hasContext] holds for
     * the receiver, as its metadata's `context` is.
     */
    fun counted(clause: LinifyClause, hasContext: (receiver: String) -> Boolean): Boolean =
        when (clause) {
            LinifyClause.QUOTE -> hasContext(receiver)
            LinifyClause.COUNTED -> true
            LinifyClause.TUPLE, LinifyClause.ELSE -> false
        }
}
