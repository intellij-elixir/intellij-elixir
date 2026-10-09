package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFDELEGATE_ALL_EACH
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFDELEGATE_GUARD_ERROR
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFDELEGATE_SELF_CHECK
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term
import java.math.BigInteger

/**
 * `Kernel.defdelegate/2`: its output is expanded where the call is, and what the output queued for its one `for`
 * iteration is replaced by what the loop does for each head: the `@doc` and a `def` named for the head, or the error
 * that taking the head apart raises. A head or an option the expander can't read leaves the output's own entries, the
 * `def` of a name it doesn't know, which stops the module there.
 */
internal val DEFDELEGATE = Summary { _, node, state, env, run ->
    val compiling = moduleBody(env, run)

    if (compiling == null) {
        Expansion.Unported(node)
    } else {
        defdelegate(node, node.arguments!!, compiling, state, env, run)
    }
}

private fun defdelegate(
    node: ElixirAst.Call,
    arguments: List<ElixirAst>,
    compiling: Compiling,
    state: ExState,
    env: Env,
    run: Run,
): Expansion {
    val (funs, written) = arguments
    val escaped = when (val escape = Quote.escape(funs, node.meta, env, run)) {
        is Quote.Escaped.Built -> escape.node
        is Quote.Escaped.Stopped -> return escape.expansion
    }
    val opts = when (val options = options(written, state, env, run)) {
        is MacroExpanded.Node -> options.node
        is MacroExpanded.Stopped -> return options.expansion
        MacroExpanded.Dir -> written
    }
    val from = run.pending.size
    val expansion = expandQuoted(node, KERNEL, DelegateOutput(Synthetic(node.meta), run.level, escaped, opts).output(), state, env, run)

    if (expansion is Expansion.Expanded && compiling.isStatement(node)) {
        val queued = run.pending.subList(from, run.pending.size).toList()
        val replacement = Delegates(node, env, run.level) { variable ->
            (macroExpandOnce(variable, state, env, run) as? MacroExpanded.Node)?.node ?: variable
        }.pending(funs, opts, queued)

        // A definition the loop makes for a head it can name is a statement of the body, as its `def` is once it runs.
        replacement.filterIsInstance<Pending.Definition>().firstOrNull { it !in queued }
            ?.let { compiling.rewrote(node, it.node) }
        run.replacePending(from, replacement)
    }

    return expansion
}

/**
 * The options as `defdelegate` passes them on: from [DEFDELEGATE_ALL_EACH] with its aliases and `__MODULE__` expanded,
 * and before it only `:to` expanded, where it is an alias, so that the call adds no compile-time dependency. Both
 * expand in the env of `__info__/1`.
 */
private fun options(written: ElixirAst, state: ExState, env: Env, run: Run): MacroExpanded {
    val info = env.copy(function = NameArity("__info__", 1))

    if (DEFDELEGATE_ALL_EACH.isSufficient(run.level)) return macroExpandLiterals(written, state, info, run)

    val list = written as? ElixirAst.ListNode ?: return MacroExpanded.Node(written, expanded = false)
    val to = list.elements.firstOrNull { pairOf(it)?.first == "to" } as? ElixirAst.Tuple
    val target = to?.elements?.get(1) as? ElixirAst.Alias ?: return MacroExpanded.Node(written, expanded = false)
    val expanded = when (val result = macroExpand(target, state, info, run)) {
        is MacroExpanded.Node -> result.node
        MacroExpanded.Dir -> target
        is MacroExpanded.Stopped -> return result
    }
    val replaced = ElixirAst.Tuple(to.meta, listOf(to.elements[0], expanded))
    val rest = list.elements.filterNot { pairOf(it)?.first == "to" }

    return MacroExpanded.Node(ElixirAst.ListNode(list.meta, listOf(replaced) + rest), expanded = true)
}

/** `{key, value}` with an atom key: the key's name and the value. */
internal fun pairOf(node: ElixirAst): Pair<String, ElixirAst>? {
    val elements = (node as? ElixirAst.Tuple)?.elements?.takeIf { it.size == 2 } ?: return null
    val key = (elements[0] as? ElixirAst.Literal.Atom)?.name ?: return null

    return key to elements[1]
}

/** What `Kernel.Utils` makes of one head, as far as the expander reads it. */
private sealed interface Head {
    /** A `def` of [name] with [arguments] in its head that calls [target]'s [function] with [calls]. */
    class Defined(
        val name: String,
        val arguments: List<ElixirAst>,
        val target: String,
        val function: String,
        val calls: List<ElixirAst>,
    ) : Head

    class Raises(val kind: String) : Head

    /** A head or an option whose effect isn't read. */
    data object Unread : Head
}

/**
 * What the output's loop does when the module body runs it, for a `defdelegate` at [node]: `defdelegate_all`'s checks,
 * then for each head `defdelegate_each`'s, the `@doc`, and the `def`.
 */
private class Delegates(
    private val node: ElixirAst.Call,
    private val env: Env,
    private val level: ElixirLanguageLevel,
    private val variableValue: (ElixirAst) -> ElixirAst,
) {
    private val s = Synthetic(node.meta)

    /** [queued], what the output queued for one iteration, replaced by what each head queues. */
    fun pending(funs: ElixirAst, opts: ElixirAst, queued: List<Pending>): List<Pending> {
        val attribute = queued.getOrNull(0) as? Pending.Attribute
        val definition = queued.getOrNull(1) as? Pending.Definition

        // The output's `def unquote({name, meta, args})` unquotes the whole head, which `define` can't name.
        if (queued.size != 2 || attribute == null || definition == null || !unquotesHead(definition.head)) return queued

        // Whatever the option's value, `Keyword.get/2,3` accepts only a list.
        if (opts is ElixirAst.Literal || opts is ElixirAst.Tuple) return listOf(raises("delegate_options"))

        val pairs = (opts as? ElixirAst.ListNode)?.elements?.map { pairOf(it) ?: return queued } ?: return queued
        val to = pairs.firstOrNull { it.first == "to" }?.second
        val asValue = pairs.firstOrNull { it.first == "as" }?.second
        val appendFirst = pairs.firstOrNull { it.first == "append_first" }?.second

        if (to == null || to is ElixirAst.Literal.Atom && to.name in FALSY) return listOf(raises("delegate_no_to"))

        // Before `DEFDELEGATE_ALL_EACH` only an alias is expanded in the options; a `__MODULE__` is read when the loop runs.
        val target = ((if (isVariable(to)) variableValue(to) else to) as? ElixirAst.Literal.Atom)?.name ?: return queued
        val function = asValue?.let { (it as? ElixirAst.Literal.Atom)?.name ?: return queued }

        if (DEFDELEGATE_SELF_CHECK.isSufficient(level) && target == env.module && (function == null || function == "nil")) {
            return listOf(raises("delegate_self"))
        }
        val rotate = when ((appendFirst as? ElixirAst.Literal.Atom)?.name) {
            null -> if (appendFirst == null) false else return queued
            "true" -> true
            "false" -> false
            else -> return queued
        }

        // `List.wrap(nil)` is `[]`.
        val heads = when (funs) {
            is ElixirAst.ListNode -> funs.elements
            is ElixirAst.Literal.Atom -> if (funs.name == "nil") emptyList() else listOf(funs)
            else -> listOf(funs)
        }

        return buildList {
            for (head in heads) {
                when (val taken = take(head, target, function, rotate)) {
                    is Head.Defined -> {
                        add(Pending.Attribute(delegateDoc(taken), attribute.at, statement = true))
                        add(named(definition, taken))
                    }
                    is Head.Raises -> {
                        add(raises(taken.kind))

                        return@buildList
                    }
                    Head.Unread -> {
                        addAll(queued)

                        return@buildList
                    }
                }
            }
        }
    }

    /** The head of the output's `def`: `unquote(head)`, and before `DEFDELEGATE_HEAD_BUILT_BY_HAND` `unquote(name)(unquote_splicing(args))`. */
    private fun unquotesHead(head: ElixirAst): Boolean {
        if (isCall(head, "unquote", 1)) return true

        val call = head as? ElixirAst.Call ?: return false

        return isCall(call.callee, "unquote", 1) && call.arguments?.singleOrNull()?.let { isCall(it, "unquote_splicing", 1) } == true
    }

    /** `Kernel.Utils.defdelegate_each/2` for [head]: `Macro.decompose_call/1`, the guard check, and each argument checked. */
    private fun take(head: ElixirAst, target: String, function: String?, rotate: Boolean): Head {
        if (mentionsUnquote(head)) return Head.Unread

        val guarded = isCall(head, "when", 2)

        if (guarded && DEFDELEGATE_GUARD_ERROR.isSufficient(level)) return Head.Raises("delegate_guard")

        val (name, arguments) = when (head) {
            is ElixirAst.Call ->
                when (head.callee) {
                    is ElixirAst.Literal.Atom -> head.callee.name to head.arguments.orEmpty()
                    is ElixirAst.Call -> return Head.Raises("delegate_call")
                    else -> return Head.Unread
                }
            is ElixirAst.Alias -> return Head.Raises("delegate_arg")
            is ElixirAst.Literal, is ElixirAst.ListNode -> return Head.Raises("delegate_call")
            is ElixirAst.Tuple -> return if (head.elements.size == 2) Head.Raises("delegate_call") else Head.Unread
            is ElixirAst.Block, is ElixirAst.Placeholder -> return Head.Unread
        }

        val parameters = arguments.map { if (isCall(it, "\\\\", 2)) (it as ElixirAst.Call).arguments!![0] else it }

        for (parameter in parameters) {
            if (parameter is ElixirAst.Placeholder) return Head.Unread
            if (!isVariable(parameter)) return Head.Raises("delegate_arg")
        }

        // Before the guard check `when` is decomposed as a call, with the guard as an argument; a head of variables only
        // defines a function by that head, whose name isn't followed.
        if (guarded) return Head.Unread

        // `tl([])` raises.
        if (rotate && parameters.isEmpty()) return Head.Unread

        val calls = if (rotate) parameters.drop(1) + parameters.first() else parameters

        return Head.Defined(name, arguments, target, function ?: name, calls)
    }

    /** `@doc delegate_to: {target, function, arity}`, as the loop writes it for [head]. */
    private fun delegateDoc(head: Head.Defined): Effect {
        val delegation = Term.Tuple(
            listOf(Term.Atom(head.target), Term.Atom(head.function), Term.Integer(BigInteger.valueOf(head.calls.size.toLong()))),
        )
        val line = Term.Integer(BigInteger.valueOf(lineOf(node.meta).toLong()))

        return Effect.Write("doc", Term.Pair(line, Term.List(listOf(Term.Pair(Term.Atom("delegate_to"), delegation)))))
    }

    /** The `def` the output queued, with its head and body as the loop's unquotes give them for [head]. */
    private fun named(queued: Pending.Definition, head: Head.Defined): Pending.Definition {
        val line = Meta.Key.Entry("line", Meta.Value.Integer(lineOf(node.meta).toLong()))
        val call = ElixirAst.Call(s.meta(listOf(line)), s.atom(head.name), head.arguments)
        val body = s.keywords(listOf("do" to s.remoteCall(head.target, head.function, head.calls, listOf(line))))

        return Pending.Definition(
            queued.kind, queued.node, call, body, unnamedAt = null, stop = null, queued.env,
            ordered = true, statement = true, checksClauses = false,
        )
    }

    private fun raises(kind: String): Pending =
        Pending.Effect(node, ordered = true, apply = { Expansion.Error(kind, node) })
}

/** The values `Keyword.get(opts, :to) || raise` refuses. */
private val FALSY = setOf("nil", "false")

/** Whether [node] holds an `unquote` or `unquote_splicing`, whose value `Macro.escape/2` puts in its place. */
private fun mentionsUnquote(node: ElixirAst): Boolean =
    isCall(node, "unquote", 1) || isCall(node, "unquote_splicing", 1) || children(node).any(::mentionsUnquote)
