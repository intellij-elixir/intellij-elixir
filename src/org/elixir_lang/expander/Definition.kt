package org.elixir_lang.expander

import com.intellij.openapi.util.io.OSAgnosticPathUtil
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
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term
import java.util.Collections
import java.util.IdentityHashMap

/**
 * `Kernel`'s `def/1,2`, `defp/1,2`, `defmacro/1,2` and `defmacrop/1,2`: the unquote fragments are evaluated where the
 * call is, and the definition is stored once the module body has run.
 */
internal val DEFINE = Summary { dispatch, node, state, env, run ->
    val kind = Kind.of(dispatch.name)
    val scope = definerScopeError(node, env)

    when {
        env.context != Env.Context.NONE && !DEFINER_REFUSED_IN_MATCH_OR_GUARD.isSufficient(run.level) ->
            Expansion.Unported(node)
        env.context == Env.Context.MATCH -> Expansion.Error("definer_in_match", node)
        env.context == Env.Context.GUARD -> Expansion.Error("definer_in_guard", node)
        scope != null -> scope
        else -> define(kind, node, node.arguments!![0], bodyOf(node), run.counters.next(env.module), state, env, run)
    }
}

/** `Kernel.define/4`'s `assert_module_scope/3` and `assert_no_function_scope/3`: the error, or `null` in a module body. */
internal fun definerScopeError(node: ElixirAst, env: Env): Expansion.Error? = when {
    env.module == null -> Expansion.Error("definer_outside_module", node)
    env.function != null -> Expansion.Error("definer_inside_function", node)
    else -> null
}

/**
 * `elixir_bootstrap:def/2`, the `def` `Kernel`'s own macros call: the same definition, with none of `Kernel.def`'s
 * checks of where it is, which the compiler makes when the definition is stored.
 */
internal val BOOTSTRAP_DEF = Summary { _, node, state, env, run ->
    if (env.module == null || env.function != null || env.context != Env.Context.NONE) {
        Expansion.Unported(node)
    } else {
        define(Kind.DEF, node, node.arguments!![0], bodyOf(node), run.counters.next(env.module), state, env, run)
    }
}

/** `def(call, expr \\ nil)`: the options of [node], a `nil` body being no body. */
private fun bodyOf(node: ElixirAst.Call): ElixirAst? =
    node.arguments!!.getOrNull(1)?.takeUnless { (it as? ElixirAst.Literal.Atom)?.name == "nil" }

/**
 * [node]'s definition of [head] and [body], its unquote fragments evaluated in turn and the definition queued with
 * their values. The fragments are linified with [counter], the one `elixir_dispatch:expand_quoted/7` gave the macro's
 * output.
 */
internal fun define(
    kind: Kind,
    node: ElixirAst.Call,
    head: ElixirAst,
    body: ElixirAst?,
    counter: Env.Counter,
    state: ExState,
    env: Env,
    run: Run,
): Expansion {
    val unquotes = hasUnquotes(head, run.level) || body != null && hasUnquotes(body, run.level)
    val fragments = Unquotes()
    val pieces = mutableListOf<Piece>()

    for (part in listOfNotNull(head, body).takeIf { unquotes }.orEmpty()) {
        fragments.collect(part)

        when (val escape = Quote.escape(part, node.meta, run)) {
            is Quote.Escaped.Built -> fragments.order(escape.node, pieces)
            // Code the port stops at stops the definition when it is expanded, not the module body that holds it. The
            // source holds no calls of the escape, and its own `:erlang` calls are the body's to dispatch.
            is Quote.Escaped.Stopped -> fragments.order(part, pieces, escaped = false)
        }
    }

    val values = pieces.map {
        when (it) {
            is Piece.Fragment -> linifyWithContextCounter(lineOf(node.meta), KERNEL, counter, it.call.arguments!!.single())
            is Piece.Evaluated -> linifyWithContextCounter(lineOf(node.meta), KERNEL, counter, it.expression)
            is Piece.Quoted -> node
        }
    }
    var next = 0

    return argumentScope(state) { scope ->
        mapfold(values, scope, env) { value, s, e ->
            when (val piece = pieces[next++]) {
                is Piece.Fragment, is Piece.Evaluated -> expandArg(value, s, state, e, run)
                is Piece.Quoted -> {
                    run.observer.dispatched(node, Dispatch(Dispatch.Kind.REMOTE_FUNCTION, piece.receiver, piece.name, piece.arity))

                    Expansion.Expanded(s, e, NODE)
                }
            }
        }
    }.thenValue { s, e, terms ->
        val call = extractGuards(head).first
        val nameFragment = (call as? ElixirAst.Call)?.callee?.takeIf { isCall(it, "unquote", 1) }
        val replacements = IdentityHashMap<ElixirAst, ElixirAst>()
        var unnamedAt: ElixirAst? = null
        var stop = fragments.stop

        for ((piece, term) in pieces.zip((terms as Term.List).elements)) {
            val fragment = (piece as? Piece.Fragment)?.call ?: continue
            val literal = literalOf(fragment, term)

            when {
                literal != null -> replacements[fragment] = literal
                fragment === nameFragment -> unnamedAt = fragment
                else -> stop = stop ?: fragment
            }
        }

        val statement = run.compiling[env.module]?.isStatement(node) == true
        val substituted = substitute(head, replacements)

        if (!isReadWhole(extractGuards(substituted).first)) unnamedAt = unnamedAt ?: stop

        run.pending += Pending.Definition(
            kind,
            node,
            substituted,
            body?.let { substitute(it, replacements) },
            unnamedAt,
            stop,
            env,
            statement && unnamedAt == null,
            statement,
            // `elixir_def:store_definition/5`: a head with unquotes or from a quote isn't checked for clauses.
            checksClauses = !unquotes && !hasMetaKey(extractGuards(head).first.meta, "context"),
        )

        Expansion.Expanded(s, e, NODE)
    }
}

/**
 * The literal [fragment]'s value [term] is, if it is one a definition can hold: the code `unquote` inserts, in which a
 * tuple of three is a call and one of any other size but two isn't code at all.
 */
internal fun literalOf(fragment: ElixirAst, term: Term): ElixirAst? =
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
internal fun isUnquotedCall(node: ElixirAst): Boolean =
    node is ElixirAst.Call && node.arguments?.size == 1 && isUnquotedCallName(node.callee)

/** `{'.', _, [Left, unquote]}`: the name of a remote call that is unquoted. */
internal fun isUnquotedCallName(node: ElixirAst): Boolean =
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

/** What the output of `elixir_quote:escape/3` does when the `def` runs it, in order. */
private sealed interface Piece {
    /** An `unquote(x)`, whose `x` is evaluated. */
    class Fragment(val call: ElixirAst.Call) : Piece

    /** The expression of an `unquote_splicing` or of an unquoted call name, evaluated, whose value isn't spliced. */
    class Evaluated(val expression: ElixirAst) : Piece

    /** A call the escape makes into `elixir_quote` or `:erlang`, for a splice or an unquoted call name. */
    class Quoted(val receiver: String, val name: String, val arity: Int) : Piece
}

/**
 * The expressions `elixir_quote:escape/3` evaluates rather than quotes, in a head or body: the `unquote(x)` fragments
 * outside a `quote`'s body, the expressions of an `unquote_splicing` and of an unquoted call name, and the first splice
 * or call name, whose value the expander can't splice. A `quote`'s options are unquoted as the rest is.
 */
private class Unquotes {
    private val fragments = IdentityHashMap<ElixirAst, ElixirAst.Call>()
    private val evaluated = Collections.newSetFromMap(IdentityHashMap<ElixirAst, Boolean>())
    var stop: ElixirAst? = null

    fun collect(node: ElixirAst) {
        when {
            isCall(node, "unquote", 1) -> (node as ElixirAst.Call).let { fragments[it.arguments!!.single()] = it }
            isCall(node, "quote", 1) -> {}
            isCall(node, "quote", 2) -> collect((node as ElixirAst.Call).arguments!![0])
            isCall(node, "unquote_splicing", 1) -> {
                evaluated += (node as ElixirAst.Call).arguments!!.single()
                stop = stop ?: node
            }
            isUnquotedCall(node) -> {
                val call = node as ElixirAst.Call

                evaluated += call.arguments!!.single()
                stop = stop ?: node
                collect((call.callee as ElixirAst.Call).arguments!![0])
            }
            else -> children(node).forEach(::collect)
        }
    }

    /**
     * The pieces of [output], the expression the escape built, as the compiler expands it: its `elixir_quote` and
     * `:erlang` calls, and each expression it evaluates, in order. When [escaped] is false, [output] is the source the
     * escape stopped at, and holds only the expressions.
     */
    fun order(output: ElixirAst, pieces: MutableList<Piece>, escaped: Boolean = true) {
        val fragment = fragments[output]

        when {
            fragment != null -> pieces += Piece.Fragment(fragment)
            output in evaluated -> pieces += Piece.Evaluated(output)
            else -> {
                if (escaped) quotedCall(output)?.let { pieces += it }
                children(output).forEach { order(it, pieces, escaped) }
            }
        }
    }

    private fun quotedCall(node: ElixirAst): Piece.Quoted? {
        val remote = (node as? ElixirAst.Call)?.callee?.takeIf { isCall(it, ".", 2) } as? ElixirAst.Call ?: return null
        val receiver = (remote.arguments!![0] as? ElixirAst.Literal.Atom)?.name?.takeIf { it in QUOTE_RECEIVERS } ?: return null

        return Piece.Quoted(receiver, (remote.arguments[1] as ElixirAst.Literal.Atom).name, node.arguments!!.size)
    }

    private companion object {
        val QUOTE_RECEIVERS = setOf("elixir_quote", "erlang")
    }
}

internal fun children(node: ElixirAst): List<ElixirAst> =
    when (node) {
        is ElixirAst.Call -> listOf(node.callee) + node.arguments.orEmpty()
        is ElixirAst.Alias -> node.segments
        is ElixirAst.ListNode -> node.elements
        is ElixirAst.Tuple -> node.elements
        is ElixirAst.Block -> node.expressions
        is ElixirAst.Literal, is ElixirAst.Placeholder -> emptyList()
    }

/** [node] with each node in [replacements] replaced, rebuilding only what holds a replaced node. */
internal fun substitute(node: ElixirAst, replacements: Map<ElixirAst, ElixirAst>): ElixirAst {
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

/**
 * Whether `store_definition/5` reads [call]'s name and `length(Args)` with no fragment the expander can't evaluate
 * deciding either: [call] isn't itself a fragment, and no direct argument is a splice.
 */
private fun isReadWhole(call: ElixirAst): Boolean =
    !isCall(call, "unquote", 1) &&
        !isCall(call, "unquote_splicing", 1) &&
        (call as? ElixirAst.Call)?.arguments.orEmpty().none { isCall(it, "unquote_splicing", 1) }

/** `elixir_utils:extract_guards/1`: [head] without its guards, and each guard of its `when`s, which `extract_or_guards/1` splits. */
internal fun extractGuards(head: ElixirAst): Pair<ElixirAst, List<ElixirAst>> =
    whenArguments(head)?.takeIf { it.size == 2 }?.let { (call, guards) -> call to orGuards(guards) } ?: (head to emptyList())

/** `elixir_utils:extract_or_guards/1`: each guard of [node]'s `when`s. */
internal fun orGuards(node: ElixirAst): List<ElixirAst> =
    whenArguments(node)?.takeIf { it.size == 2 }?.let { (guard, rest) -> listOf(guard) + orGuards(rest) } ?: listOf(node)

/**
 * Whether `run_with_location_change/3` moves a definition into another file: whether the file of [file], the `@file` it
 * took, or else of the `keep:` that `elixir_utils:meta_keep/1` reads off [head]'s name and arguments, made absolute as
 * `filename:absname/1` makes it, isn't [run]'s. A file that can't be read moves it.
 */
private fun moved(file: AttributeValue?, head: ElixirAst, run: Run): Boolean {
    val path = when (file) {
        null -> keptFile(extractGuards(head).first.meta) ?: return false
        AttributeValue.Unknown -> return true
        is AttributeValue.Known -> fileOf(file.term) ?: return true
    }
    val joined = when {
        OSAgnosticPathUtil.isAbsolute(path) -> path
        else -> run.file?.let(run.directories::of)?.let { "$it/$path" } ?: return true
    }

    return absname(joined) != run.file?.let(::absname)
}

/**
 * `filename:absname/1` of [path], already absolute, as `join1b/4` joins it on Windows: a drive letter lowercased, `\`
 * read as `/`, a `//` root kept, and a repeated separator or a `.` before one dropped.
 */
private fun absname(path: String): String {
    val slashed = path.replace('\\', '/')
    val drive = slashed.getOrNull(1) == ':' && slashed[0].lowercaseChar() in 'a'..'z'
    val root = when {
        drive -> "${slashed[0].lowercaseChar()}:"
        slashed.startsWith("//") -> "//"
        else -> ""
    }
    val joined = StringBuilder(root)

    for (char in slashed.substring(root.length)) {
        when {
            char != '/' -> joined.append(char)
            joined.endsWith("/.") -> joined.setLength(joined.length - 1)
            !joined.endsWith("/") -> joined.append(char)
        }
    }

    val name = joined.toString()

    // `maybe_remove_dirsep/2`: a trailing separator is dropped, but not a root's.
    return if (name.endsWith("/") && name != "$root/" && name != "//") name.dropLast(1) else name
}

/** `meta_keep/1`: the file of [meta]'s first `keep:`, where that is `{File, Line}` with a binary and an integer. */
private fun keptFile(meta: Meta): String? {
    val keep = meta.keys.firstNotNullOfOrNull { key -> (key as? Meta.Key.Entry)?.takeIf { it.name == "keep" } } ?: return null
    val (file, line) = (keep.value as? Meta.Value.Tuple)?.elements?.takeIf { it.size == 2 } ?: return null

    return (file as? Meta.Value.Binary)?.text?.takeIf { line is Meta.Value.Integer }
}

/** The file of `@file`'s [term], a binary or `{File, Line}` as `retrieve_location/2` takes it, or `null` for any other. */
private fun fileOf(term: Term): String? =
    when (term) {
        is Term.Binary -> term.bytes?.toString(Charsets.UTF_8)
        is Term.Pair -> (term.first as? Term.Binary)?.bytes?.takeIf { term.second is Term.Integer }?.toString(Charsets.UTF_8)
        else -> null
    }

/**
 * `elixir_def:store_definition/5`: [definition]'s name checked, its defaults and clause expanded in the env where it
 * was defined, and the definition stored unless that raised. The owner names the definition as far as it is known.
 *
 * @param expand whether the defaults and clause are expanded; where they aren't, the definition is stored as one that
 *   stops at its node
 */
internal fun storeDefinition(
    definition: Pending.Definition,
    compiling: Compiling,
    run: Run,
    expand: Boolean = true,
): Pair<Owner, Expansion> {
    val kind = definition.kind
    val node = definition.node
    val unnamedAt = definition.unnamedAt

    // `retrieve_location/2`, before the name is checked. Only `invalid_def` comes before it in Elixir, and it raises.
    compiling.definitionMoved = moved(takeFile(definition, compiling), definition.head, run)

    if (unnamedAt != null) {
        compiling.table.define(null, 0, kind, node, 0, 0, definition.statement, definition.checksClauses)

        return Owner.Definition(kind, null, 0) to Expansion.Unported(unnamedAt)
    }

    val (call, guards) = extractGuards(definition.head)
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
    if (definition.stop != null || !expand) {
        store(definition, name, arity, defaults.size, compiling)

        return owner to Expansion.Unported(definition.stop ?: node)
    }

    val nameArity = NameArity(name, arity)
    val env = definition.env.copy(function = nameArity)
    val fresh = ExState.empty(run.level).copy(caller = kind.macro)
    val args = defaultsArgs.map { arg -> if (arg in defaults) (arg as ElixirAst.Call).arguments!![0] else arg }
    val body = definition.body?.let { clauseBody(node, it) }
    val defaulted = expandDefaults(defaults, fresh, env, run, compiling, node)
    val expansion = defaulted.then { _, _ -> clause(node, args, guards, definition.body, body, fresh, env, run) }

    if (expansion is Expansion.Error) return owner to expansion

    body?.let { compiling.clauses.getOrPut(nameArity) { mutableListOf() } += ModuleClauses.Clause(args, guards, it, env) }
    if (defaulted is Expansion.Expanded) {
        val counter = if (DEFAULT_ARGUMENTS_THREAD_STATE.isSufficient(run.level)) defaulted.state.version else 0

        for (clause in defaultClauses(kind, name, defaultsArgs, defaults, counter, env)) {
            compiling.clauses.getOrPut(NameArity(name, clause.args.size)) { mutableListOf() } += clause
        }
    }

    // The store follows the expansion, so from 1.15 the body's errors are reported before the store raises. A body
    // that isn't expanded may raise before the store does.
    val raised = store(definition, name, arity, defaults.size, compiling)?.takeIf { expansion is Expansion.Expanded }

    return owner to (raised?.let { Expansion.Error(it, node) } ?: expansion)
}

/** [definition] stored as [name] and [arity] with [defaults] defaults: the kind of the error the store raises, if any. */
private fun store(
    definition: Pending.Definition,
    name: String,
    arity: Int,
    defaults: Int,
    compiling: Compiling,
): String? =
    compiling.table.define(
        name,
        arity,
        definition.kind,
        definition.node,
        if (definition.body == null) 0 else 1,
        defaults,
        definition.ordered,
        definition.checksClauses,
        definition.delegatesTo,
    )

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
        val arities = DefinitionTable.defaultArities(full.arity, defaults.size)

        for (arity in arities) {
            compiling.calls.getOrPut(NameArity(full.name, arity)) { mutableListOf() } +=
                LocalCall(
                    node,
                    full,
                    position?.line ?: 0,
                    position?.column ?: 0,
                    calls.drop(arity - arities.first).flatten(),
                    checked = false,
                )
        }
    } else {
        compiling.calls.getOrPut(full) { mutableListOf() } += calls.flatten()
    }

    return expansion
}

/**
 * `unpack_expanded/7`: the clause of each default arity of [defaultsArgs], whose [defaults] are expanded. It binds the
 * arguments it is given to variables numbered from [counter], and calls the full arity through `super` with the
 * defaults it lacks.
 */
private fun defaultClauses(
    kind: Kind,
    name: String,
    defaultsArgs: List<ElixirAst>,
    defaults: List<ElixirAst.Call>,
    counter: Int,
    env: Env,
): List<ModuleClauses.Clause> =
    defaultsArgs.indices.filter { defaultsArgs[it] in defaults }.map { index ->
        val default = defaultsArgs[index] as ElixirAst.Call
        val synthetic = Synthetic(default.meta)
        // `default_var/1`.
        val variable = { version: Int ->
            val keys = listOf(
                Meta.Key.Entry("generated", Meta.Value.Atom("true")),
                Meta.Key.Entry("version", Meta.Value.Integer(version.toLong())),
            )

            synthetic.variable("x$version", "elixir_def", keys)
        }
        val base = (0 until index).map { variable(counter + it) }
        var next = counter + index
        val rest = defaultsArgs.drop(index).map { arg ->
            if (arg in defaults) null to (arg as ElixirAst.Call).arguments!![1] else variable(next++).let { it to it }
        }
        val superKeys = listOf(
            Meta.Key.Entry("super", Meta.Value.Tuple(listOf(Meta.Value.Atom(kind.name.lowercase()), Meta.Value.Atom(name)))),
        ) + default.meta.keys

        ModuleClauses.Clause(
            base + rest.mapNotNull { it.first },
            emptyList(),
            synthetic.call("super", base + rest.map { it.second }, superKeys),
            env,
        )
    }

/** `def_to_clauses/6`'s body of [node]'s [options]: a `do` alone is its body, and with more a `try`; `null` without one. */
private fun clauseBody(node: ElixirAst.Call, options: ElixirAst): ElixirAst? {
    val pairs = (options as? ElixirAst.ListNode)?.elements

    return when {
        pairs?.singleOrNull()?.let(::keyOf) == "do" -> (pairs.single() as ElixirAst.Tuple).elements[1]
        pairs?.any { keyOf(it) == "do" } == true ->
            ElixirAst.Call(node.meta, ElixirAst.Literal.Atom(node.meta, "try"), listOf(options))
        else -> null
    }
}

/**
 * `def_to_clauses/6`, then `elixir_clauses:def/3` for the clause: the arguments as a pattern, the guard, then the
 * [body] of [options]. A bodiless head stores no clause: each argument that isn't a variable is an error, and so is a
 * guard.
 */
private fun clause(
    node: ElixirAst.Call,
    args: List<ElixirAst>,
    guards: List<ElixirAst>,
    options: ElixirAst?,
    body: ElixirAst?,
    state: ExState,
    env: Env,
    run: Run,
): Expansion {
    if (options == null) {
        if (guards.isNotEmpty() && !FUNCTION_HEAD_GUARDS_CONTINUE.isSufficient(run.level)) {
            return Expansion.Error("missing_option", node)
        }

        val errors = args.filterNot(::isVariable).size + if (guards.isNotEmpty()) 1 else 0

        repeat(errors) { reportOrEnd(ErrorSite.INVALID_FUNCTION_HEAD, node, env, run)?.let { return it } }

        return Expansion.Expanded(state, env, NODE)
    }

    if (body == null) return Expansion.Error("missing_option", node)

    return match(state, state, env, node) { s, e -> expandArgs(args, s, e, run) }
        .then { s, e ->
            guards.fold(Expansion.Expanded(s, e, NODE) as Expansion) { expansion, guard ->
                expansion.then { s, e -> guard(guard, s, e.copy(context = Env.Context.GUARD), run) }
            }
        }
        .then { s, e -> Expander.expand(body, s, e.copy(context = Env.Context.NONE), run) }
}

private const val ALIASES = "__aliases__"
