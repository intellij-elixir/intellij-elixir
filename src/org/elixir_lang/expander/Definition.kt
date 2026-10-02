package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.expander.DefinitionTable.Kind
import org.elixir_lang.expander.ExpansionResult.Owner
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFAULT_ARGUMENTS_THREAD_STATE
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFINER_REFUSED_IN_MATCH_OR_GUARD
import org.elixir_lang.language_level.ElixirLanguageFeature.FUNCTION_HEAD_GUARDS_CONTINUE
import org.elixir_lang.language_level.ElixirLanguageFeature.HAS_UNQUOTES_NAME_AT_QUOTE_LEVEL
import org.elixir_lang.language_level.ElixirLanguageFeature.HAS_UNQUOTES_QUOTE_AWARE
import org.elixir_lang.language_level.ElixirLanguageFeature.POST_MODULE_LOCAL_CHECKS_TYPED
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term
import java.util.IdentityHashMap

/**
 * `Kernel`'s `def/1,2`, `defp/1,2`, `defmacro/1,2` and `defmacrop/1,2`: the unquote fragments are evaluated where the
 * call is, and the definition is stored once the module body has run.
 */
internal val DEFINE = Summary { dispatch, node, state, env, run ->
    val kind = Kind.valueOf(dispatch.name.uppercase())

    when {
        env.context != Env.Context.NONE && !DEFINER_REFUSED_IN_MATCH_OR_GUARD.isSufficient(run.level) ->
            Expansion.Unported(node)
        env.context == Env.Context.MATCH -> Expansion.Error("definer_in_match", node)
        env.context == Env.Context.GUARD -> Expansion.Error("definer_in_guard", node)
        env.module == null -> Expansion.Error("definer_outside_module", node)
        env.function != null -> Expansion.Error("definer_inside_function", node)
        else -> define(kind, node, state, env, run)
    }
}

/**
 * [node]'s unquote fragments evaluated in turn, and the definition queued with their values in its head and body. The
 * output takes the module's next counter, as `elixir_dispatch:expand_quoted/7` gives it, and the fragments are linified
 * with it.
 */
private fun define(kind: Kind, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val counter = run.counters.next(env.module)
    val head = node.arguments!![0]
    // `def(call, expr \\ nil)`: a `nil` body is no body.
    val body = node.arguments.getOrNull(1)?.takeUnless { (it as? ElixirAst.Literal.Atom)?.name == "nil" }
    val fragments = Fragments()

    if (hasUnquotes(head, run.level) || body != null && hasUnquotes(body, run.level)) {
        fragments.walk(head)
        body?.let(fragments::walk)
    }

    val values = fragments.list.map {
        linifyWithContextCounter(lineOf(node.meta), KERNEL, counter, it.arguments!!.single())
    }

    return argumentScope(state, env) { scope ->
        mapfold(values, scope, env) { value, s, e -> expandArg(value, s, state, e, run) }
    }.thenValue { s, e, terms ->
        val call = extractGuards(head).first
        val nameFragment = (call as? ElixirAst.Call)?.callee?.takeIf { isCall(it, "unquote", 1) }
        val replacements = IdentityHashMap<ElixirAst, ElixirAst>()
        var unnamedAt: ElixirAst? = null
        var stop = fragments.stop

        for ((fragment, term) in fragments.list.zip((terms as Term.List).elements)) {
            val literal = literalOf(fragment, term)

            when {
                literal != null -> replacements[fragment] = literal
                fragment === nameFragment -> unnamedAt = fragment
                else -> stop = stop ?: fragment
            }
        }

        val ordered = run.compiling[env.module]?.isStatement(node) == true && unnamedAt == null

        run.pending += Pending.Definition(
            kind,
            node,
            substitute(head, replacements),
            body?.let { substitute(it, replacements) },
            unnamedAt,
            stop,
            env,
            ordered,
        )

        Expansion.Expanded(s, e, NODE)
    }
}

/** The literal [fragment]'s value [term] is, if it is one a definition can hold. */
private fun literalOf(fragment: ElixirAst, term: Term): ElixirAst? =
    when (term) {
        is Term.Atom -> ElixirAst.Literal.Atom(fragment.meta, term.name)
        is Term.Integer -> ElixirAst.Literal.Integer(fragment.meta, term.value)
        is Term.Binary -> term.bytes?.let { ElixirAst.Literal.Binary(fragment.meta, it) }
        is Term.List ->
            if (term.tail != null) null
            else ElixirAst.ListNode(fragment.meta, term.elements.map { literalOf(fragment, it) ?: return null })
        is Term.Pair -> ElixirAst.Tuple(
            fragment.meta,
            listOf(literalOf(fragment, term.first) ?: return null, literalOf(fragment, term.second) ?: return null),
        )
        else -> null
    }

/**
 * `elixir_quote:has_unquotes/1`: whether [node] makes its definition one with unquote fragments. Before
 * [HAS_UNQUOTES_QUOTE_AWARE] any `unquote` does. From it, one inside a `quote` does only if it unquotes past it, a
 * `quote` whose options disable unquoting holds none, and the options aren't read; a call's name is read outside any
 * `quote` until [HAS_UNQUOTES_NAME_AT_QUOTE_LEVEL], and at the call's own level from it.
 */
private fun hasUnquotes(node: ElixirAst, level: ElixirLanguageLevel, quoteLevel: Int = 0): Boolean {
    val arguments = (node as? ElixirAst.Call)?.arguments
    val unquote = isCall(node, "unquote", 1) || isCall(node, "unquote_splicing", 1)

    if (!HAS_UNQUOTES_QUOTE_AWARE.isSufficient(level)) {
        return unquote || isUnquotedCall(node) || children(node).any { hasUnquotes(it, level) }
    }

    return when {
        isCall(node, "quote", 1) -> hasUnquotes(arguments!![0], level, quoteLevel + 1)
        isCall(node, "quote", 2) -> !disablesUnquote(arguments!![0]) && hasUnquotes(arguments[1], level, quoteLevel + 1)
        unquote -> quoteLevel == 0 || hasUnquotes(arguments!![0], level, quoteLevel - 1)
        isUnquotedCall(node) -> true
        node is ElixirAst.Call && arguments != null -> {
            val nameLevel = if (HAS_UNQUOTES_NAME_AT_QUOTE_LEVEL.isSufficient(level)) quoteLevel else 0

            hasUnquotes(node.callee, level, nameLevel) || arguments.any { hasUnquotes(it, level, quoteLevel) }
        }
        else -> children(node).any { hasUnquotes(it, level, quoteLevel) }
    }
}

/** `{{'.', _, [_, unquote]}, _, [_]}`: a call of one argument whose name is unquoted. */
private fun isUnquotedCall(node: ElixirAst): Boolean =
    node is ElixirAst.Call && node.arguments?.size == 1 && isUnquotedCallName(node.callee)

/** `{'.', _, [Left, unquote]}`: the name of a remote call that is unquoted. */
private fun isUnquotedCallName(node: ElixirAst): Boolean =
    isCall(node, ".", 2) && ((node as ElixirAst.Call).arguments!![1] as? ElixirAst.Literal.Atom)?.name == "unquote"

/** `disables_unquote/1`: whether a `quote`'s [options] hold `unquote: false` or `bind_quoted:`. */
private fun disablesUnquote(options: ElixirAst): Boolean =
    (options as? ElixirAst.ListNode)?.elements.orEmpty().any { option ->
        val pair = (option as? ElixirAst.Tuple)?.elements?.takeIf { it.size == 2 }

        when ((pair?.get(0) as? ElixirAst.Literal.Atom)?.name) {
            "unquote" -> (pair[1] as? ElixirAst.Literal.Atom)?.name == "false"
            "bind_quoted" -> true
            else -> false
        }
    }

/**
 * `elixir_quote:escape/3` with unquoting: each `unquote(x)` in a head and body, in order, outside a `quote`'s body, and
 * the first `unquote_splicing` or `unquote` call name, whose value the expander can't splice. A `quote`'s options are
 * unquoted as the rest is.
 */
private class Fragments {
    val list = mutableListOf<ElixirAst.Call>()
    var stop: ElixirAst? = null

    fun walk(node: ElixirAst) {
        when {
            isCall(node, "unquote", 1) -> list += node as ElixirAst.Call
            isCall(node, "quote", 1) -> {}
            isCall(node, "quote", 2) -> walk((node as ElixirAst.Call).arguments!![0])
            isCall(node, "unquote_splicing", 1) || isUnquotedCallName(node) -> stop = stop ?: node
            else -> children(node).forEach(::walk)
        }
    }
}

private fun children(node: ElixirAst): List<ElixirAst> =
    when (node) {
        is ElixirAst.Call -> listOf(node.callee) + node.arguments.orEmpty()
        is ElixirAst.Alias -> node.segments
        is ElixirAst.ListNode -> node.elements
        is ElixirAst.Tuple -> node.elements
        is ElixirAst.Block -> node.expressions
        is ElixirAst.Literal, is ElixirAst.Placeholder -> emptyList()
    }

/** [node] with each node in [replacements] replaced, rebuilding only what holds a replaced node. */
private fun substitute(node: ElixirAst, replacements: Map<ElixirAst, ElixirAst>): ElixirAst {
    replacements[node]?.let { return it }

    if (replacements.isEmpty()) return node

    return when (node) {
        is ElixirAst.Call -> {
            val callee = substitute(node.callee, replacements)
            val arguments = node.arguments?.let { substituteAll(it, replacements) }

            if (callee === node.callee && arguments === node.arguments) node
            else ElixirAst.Call(node.meta, callee, arguments)
        }
        is ElixirAst.Alias ->
            substituteAll(node.segments, replacements).let {
                if (it === node.segments) node else ElixirAst.Alias(node.meta, it)
            }
        is ElixirAst.ListNode ->
            substituteAll(node.elements, replacements).let {
                if (it === node.elements) node else ElixirAst.ListNode(node.meta, it)
            }
        is ElixirAst.Tuple ->
            substituteAll(node.elements, replacements).let {
                if (it === node.elements) node else ElixirAst.Tuple(node.meta, it)
            }
        is ElixirAst.Block ->
            substituteAll(node.expressions, replacements).let {
                if (it === node.expressions) node else ElixirAst.Block(node.meta, it)
            }
        is ElixirAst.Literal, is ElixirAst.Placeholder -> node
    }
}

private fun substituteAll(nodes: List<ElixirAst>, replacements: Map<ElixirAst, ElixirAst>): List<ElixirAst> {
    val substituted = nodes.map { substitute(it, replacements) }

    return if (substituted.indices.all { substituted[it] === nodes[it] }) nodes else substituted
}

/** `elixir_utils:extract_guards/1`: [head] without its guard, and the guard. */
private fun extractGuards(head: ElixirAst): Pair<ElixirAst, ElixirAst?> =
    whenArguments(head)?.takeIf { it.size == 2 }?.let { (call, guard) -> call to guard } ?: (head to null)

/**
 * `elixir_def:store_definition/5`: [definition]'s name checked, its defaults and clause expanded in the env where it
 * was defined, and the definition stored unless that raised. The owner names the definition as far as it is known.
 */
internal fun storeDefinition(definition: Pending.Definition, compiling: Compiling, run: Run): Pair<Owner, Expansion> {
    val kind = definition.kind
    val node = definition.node
    val unnamedAt = definition.unnamedAt

    if (unnamedAt != null) {
        compiling.table.define(null, 0, kind, line(node.meta), 0, 0, definition.ordered)

        return Owner.Definition(kind, null, 0) to Expansion.Unported(unnamedAt)
    }

    val (call, guard) = extractGuards(definition.head)
    val (name, defaultsArgs) = when (call) {
        is ElixirAst.Call if call.callee is ElixirAst.Literal.Atom -> call.callee.name to call.arguments.orEmpty()
        // `{'__aliases__', Meta, Segments}`: a name `assert_no_aliases_name/4` refuses for one segment.
        is ElixirAst.Alias ->
            return if (call.segments.singleOrNull() is ElixirAst.Literal.Atom) {
                Owner.Definition(kind, ALIASES, 1) to Expansion.Error("no_alias", node)
            } else {
                Owner.Definition(kind, ALIASES, call.segments.size) to Expansion.Unported(call)
            }
        else -> return Owner.Definition(kind, null, 0) to Expansion.Error("invalid_def", node)
    }
    val arity = defaultsArgs.size
    val owner = Owner.Definition(kind, name, arity)

    reservedNameError(kind, name, arity)?.let { return owner to Expansion.Error(it, node) }

    val defaults = defaultsArgs.mapNotNull { arg -> (arg as? ElixirAst.Call)?.takeIf { isCall(it, "\\\\", 2) } }
    val line = line(node.meta)
    val clauseCount = if (definition.body == null) 0 else 1

    definition.stop?.let { stop ->
        compiling.table.define(name, arity, kind, line, clauseCount, defaults.size, definition.ordered)

        return owner to Expansion.Unported(stop)
    }

    val env = definition.env.copy(function = NameArity(name, arity))
    val fresh = ExState.empty(run.level).copy(caller = kind.macro)
    val args = defaultsArgs.map { arg -> if (arg in defaults) (arg as ElixirAst.Call).arguments!![0] else arg }
    val expansion = expandDefaults(defaults, fresh, env, run, compiling, node)
        .then { _, _ -> clause(node, args, guard, definition.body, fresh, env, run) }

    if (expansion !is Expansion.Error) {
        compiling.table.define(name, arity, kind, line, clauseCount, defaults.size, definition.ordered)
    }

    return owner to expansion
}

/** `assert_valid_name/5`: the error a definition of [name] and [arity] raises, as one the compiler defines. */
private fun reservedNameError(kind: Kind, name: String, arity: Int): String? =
    when (name) {
        "__info__" if arity == 1 -> "__info__"
        "module_info" if arity in 0..1 -> "module_info"
        "is_record" if arity == 2 && !kind.macro -> "is_record"
        else -> null
    }

/**
 * `unpack_defaults/6`: each of [defaults] expanded in [env], from the state the one before it left from
 * [DEFAULT_ARGUMENTS_THREAD_STATE], and from [fresh] before it. Their local calls are the full arity's before
 * [POST_MODULE_LOCAL_CHECKS_TYPED]. From it, each default arity is a definition whose clause calls the full arity with
 * the defaults it lacks, so their calls are that call's arguments.
 */
private fun expandDefaults(
    defaults: List<ElixirAst.Call>,
    fresh: ExState,
    env: Env,
    run: Run,
    compiling: Compiling,
    node: ElixirAst.Call,
): Expansion {
    val full = env.function!!
    val calls = mutableListOf<List<LocalCall<ElixirAst>>>()
    var state = fresh
    var expansion: Expansion = Expansion.Expanded(fresh, env, NODE)

    for (default in defaults) {
        val defaultCalls = mutableListOf<LocalCall<ElixirAst>>()

        run.callArguments.addLast(defaultCalls)

        expansion = try {
            Expander.expand(default.arguments!![1], state, env, run)
        } finally {
            run.callArguments.removeLast()
        }
        calls += defaultCalls

        if (expansion !is Expansion.Expanded) break

        if (DEFAULT_ARGUMENTS_THREAD_STATE.isSufficient(run.level)) state = expansion.state
    }

    if (POST_MODULE_LOCAL_CHECKS_TYPED.isSufficient(run.level)) {
        val position = location(node.meta)
        val first = full.arity - defaults.size

        for (arity in first until full.arity) {
            compiling.calls.getOrPut(NameArity(full.name, arity)) { mutableListOf() } +=
                LocalCall(
                    node,
                    full,
                    position?.line ?: 0,
                    position?.column ?: 0,
                    calls.drop(arity - first).flatten(),
                    checked = false,
                )
        }
    } else {
        compiling.calls.getOrPut(full) { mutableListOf() } += calls.flatten()
    }

    return expansion
}

/**
 * `def_to_clauses/6`, then `elixir_clauses:def/3` for the clause: the arguments as a pattern, the guard, then the body.
 * A bodiless head stores no clause: each argument that isn't a variable is an error, and so is a guard.
 */
private fun clause(
    node: ElixirAst.Call,
    args: List<ElixirAst>,
    guard: ElixirAst?,
    options: ElixirAst?,
    state: ExState,
    env: Env,
    run: Run,
): Expansion {
    if (options == null) {
        if (guard != null && !FUNCTION_HEAD_GUARDS_CONTINUE.isSufficient(run.level)) {
            return Expansion.Error("missing_option", node)
        }

        val errors = args.filterNot(::isVariable).size + if (guard != null) 1 else 0

        repeat(errors) { reportOrEnd(ErrorSite.INVALID_FUNCTION_HEAD, node, env, run)?.let { return it } }

        return Expansion.Expanded(state, env, NODE)
    }

    val pairs = (options as? ElixirAst.ListNode)?.elements
    val body = when {
        pairs?.singleOrNull()?.let(::keyOf) == "do" -> (pairs.single() as ElixirAst.Tuple).elements[1]
        pairs?.any { keyOf(it) == "do" } == true ->
            ElixirAst.Call(node.meta, ElixirAst.Literal.Atom(node.meta, "try"), listOf(options))
        else -> return Expansion.Error("missing_option", node)
    }

    return match(state, state, env, node) { s, e -> expandArgs(args, s, e, run) }
        .then { s, e ->
            if (guard == null) {
                Expansion.Expanded(s, e, NODE)
            } else {
                guard(guard, s, e.copy(context = Env.Context.GUARD), run)
            }
        }
        .then { s, e -> Expander.expand(body, s, e.copy(context = Env.Context.NONE), run) }
}

private const val ALIASES = "__aliases__"
