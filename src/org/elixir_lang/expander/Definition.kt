package org.elixir_lang.expander

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.io.OSAgnosticPathUtil
import org.elixir_lang.NameArity
import org.elixir_lang.expander.DefinitionTable.Injected
import org.elixir_lang.expander.DefinitionTable.Kind
import org.elixir_lang.expander.ExpansionResult.Owner
import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.expander.interpreter.Binding
import org.elixir_lang.expander.interpreter.EMPTY
import org.elixir_lang.expander.interpreter.Evaluated
import org.elixir_lang.expander.interpreter.NIL
import org.elixir_lang.expander.interpreter.codeOf
import org.elixir_lang.expander.interpreter.properElements
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFAULT_ARGUMENTS_THREAD_STATE
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFINER_REFUSED_IN_MATCH_OR_GUARD
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFINITION_ESCAPES_HEAD_AND_BODY_TOGETHER
import org.elixir_lang.language_level.ElixirLanguageFeature.FUNCTION_HEAD_GUARDS_CONTINUE
import org.elixir_lang.language_level.ElixirLanguageFeature.POST_MODULE_LOCAL_CHECKS_TYPED
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term
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
    val view = AstEscapeView(Synthetic(node.meta), ParsedMeta(columns = false))
    val hasUnquotes = HasUnquotes(view, run.level)
    // `Kernel.define/4` reads the head, then the body.
    val headUnquotes: Boolean
    val bodyUnquotes: Boolean

    try {
        headUnquotes = hasUnquotes.has(AstTerm.Node(head))
        bodyUnquotes = body != null && hasUnquotes.has(AstTerm.Node(body))
    } catch (_: ImproperList) {
        return Expansion.Error("Elixir.FunctionClauseError", node)
    }

    val unquotes = bodyUnquotes || headUnquotes
    val together = DEFINITION_ESCAPES_HEAD_AND_BODY_TOGETHER.isSufficient(run.level)
    // Before the head and body are escaped together, `Kernel.define/4` escapes the head whether or not it has unquotes.
    val escapesHead = unquotes || !together
    val escapesBody = bodyUnquotes || unquotes && together
    // `Kernel.define/4` escapes `{call, expr}` as one pair, which `do_quote/2` quotes as a `{}` call where either
    // holds an `unquote_splicing` call. The parts are then that call's arguments: a splice that is a whole part is
    // spliced into them, and the store reads the call as a cache key.
    val pairBody = body ?: ElixirAst.Literal.Atom(node.meta, "nil")
    val pairIsCall = unquotes && together && isSplicingPair(view, AstTerm.Node(head), AstTerm.Node(pairBody))
    val fragments = Unquotes()
    val pieces = mutableListOf<Piece>()
    val parts = listOfNotNull(head.takeIf { escapesHead }, body?.takeIf { escapesBody })

    parts.forEach { fragments.collect(it, listed = pairIsCall) }

    val escaped = if (pairIsCall) listOf(ElixirAst.Tuple(node.meta, listOf(head, pairBody))) else parts
    // Code the port can't read may hold an `unquote`, which Elixir expands with the body whether or not the definition
    // is escaped, so where the definition holds any, whether Elixir runs the body isn't known.
    var holdsUnread = listOfNotNull(head.takeUnless { escapesHead }, body?.takeUnless { escapesBody }).any(::holdsUnreadCode)

    for (part in escaped) {
        when (val escape = Quote.escape(part, node.meta, run)) {
            is Quote.Escaped.Built -> {
                escape.readAsCode.forEach(fragments::readAsCode)
                fragments.order(escape.node, pieces)
            }
            // Elixir escapes code the port can't read as it is, so that code stops the definition when it is expanded, not
            // the module body that holds it. The source holds no calls of the escape, and its own `:erlang` calls are the
            // body's to dispatch.
            is Quote.Escaped.Unread -> {
                holdsUnread = true
                fragments.order(part, pieces, escaped = false)
            }
            // `Kernel.define/4` escapes as the `def` expands, before any statement of the body runs, so where the escape
            // stops the body's expansion stops at the `def`, and what it raises is the module's there.
            is Quote.Escaped.Stopped -> return Expansion.Unported(node)
            is Quote.Escaped.Raised -> return Expansion.Error(escape.exception, node)
        }
    }

    if (holdsUnread) env.module?.let(run.compiling::get)?.values?.unread()

    // The fragments before each piece, and so the index of each piece's own.
    val before = pieces.runningFold(0) { count, piece ->
        count + if (piece is Piece.Quoted) 0 else 1
    }
    val values = pieces.map {
        when (it) {
            is Piece.Fragment -> linifyWithContextCounter(lineOf(node.meta), KERNEL, counter, run.level, it.call.arguments!!.single())
            is Piece.Evaluated -> linifyWithContextCounter(lineOf(node.meta), KERNEL, counter, run.level, it.expression)
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
        val evaluated = pieces.indices.mapNotNull { i ->
            val term = (terms as Term.List).elements[i]

            when (val piece = pieces[i]) {
                is Piece.Fragment -> Fragment(piece.call, values[i], term, piece.call.arguments!!.single())
                is Piece.Evaluated -> piece.placement.let {
                    val checkedAfter = (piece.helper?.end?.let(before::get) ?: before[i + 1]) - 1

                    Fragment(it.call, values[i], term, piece.expression, it.kind, checkedAfter, it.named, it.list, checkOf(piece), piece.helper?.call, piece.helper?.name)
                }
                is Piece.Quoted -> null
            }
        }

        run.pending += Pending.Definition(
            kind,
            node,
            head,
            body,
            Fragments(evaluated, state, escapesBody, pairIsCall = pairIsCall).takeIf { escapesHead },
            env,
            run.compiling[env.module]?.isStatement(node) == true,
            // `elixir_def:store_definition/5`: a head with unquotes or from a quote isn't checked for clauses.
            checksClauses = !unquotes && !hasMetaKey(extractGuards(head).first.meta, "context"),
            injectedBy = run.injecting?.takeIf { builtBy(node, it) },
            followed = run.compiling[env.module]?.statements?.follows(node) == true,
        )

        Expansion.Expanded(s, e, NODE)
    }
}

/**
 * What the escape of a definition's head and body evaluates when the `def` runs: each `unquote(x)`, splice and unquoted
 * call name, in order.
 *
 * @property state the state at the `def*` call, from which the fragments are evaluated
 * @property escapesBody whether the body is escaped with the head, and so holds fragments: before
 *   [DEFINITION_ESCAPES_HEAD_AND_BODY_TOGETHER], only where the body itself has unquotes
 * @property pairIsCall whether the head and body, escaped together, are quoted as a `{}` call rather than a pair, as
 *   `do_quote/2` quotes a pair holding an `unquote_splicing` call: the store then reads a cache key where it expects
 *   `{Call, Body}`, and raises
 */
internal class Fragments(
    val values: List<Fragment>,
    val state: ExState,
    val escapesBody: Boolean,
    val pairIsCall: Boolean = false,
)

/**
 * An `unquote(x)`, `unquote_splicing(x)` or `Left.unquote(x)` of a definition, or an expression read as code, the
 * [call] of a [Kind.CODE], with [expanded], the `x` the module body expanded, and the [term] it expanded to.
 *
 * @property escaped the `x` in the escape's code, which its value stands for
 * @property checkedAfter for a value its helper checks ([check]), the index of the last fragment that helper's
 *   arguments hold, all of which are evaluated before it runs
 * @property named for a call name, the call it names
 * @property list for a splice before a tail, the elements of the list, arguments, tuple or block it is in, whose others
 *   and tail `tail_list/3` takes
 * @property check what the helper the escape hands the value to checks before the value is placed, if it checks it
 * @property helper that helper's call, where the escape built one
 * @property helperName that helper's function
 */
internal class Fragment(
    val call: ElixirAst,
    val expanded: ElixirAst,
    val term: Term,
    val escaped: ElixirAst,
    val kind: Kind = Kind.UNQUOTE,
    val checkedAfter: Int = -1,
    val named: ElixirAst.Call? = null,
    val list: List<ElixirAst>? = null,
    val check: Check? = null,
    val helper: ElixirAst.Call? = null,
    val helperName: String? = null,
) {
    enum class Kind {
        UNQUOTE,

        /**
         * A splice into the list, arguments, tuple or block that holds it, which `elixir_quote:list/2` checks, or
         * `tail_list/3` before their tail.
         */
        SPLICE,

        /** A called name, `Left.unquote(x)(...)`, which `elixir_quote:dot/4` makes. */
        NAME,

        /**
         * An uncalled name's expression, `Left.unquote(x)`, which `elixir_quote:dot/5` checks and builds a call from
         * that the walk doesn't port: the definition stops there, while what comes after it still runs.
         */
        STOP,

        /**
         * An `unquote`'s expression that the escape read as code from a macro's term, checked as its place gives
         * ([check]): the port places its value nowhere, so the definition stops there, while what comes after it still
         * runs.
         */
        CODE,
    }

    /** What an `elixir_quote` helper checks of the value it is handed, which raises where the check fails. */
    enum class Check {
        /**
         * `list/2`, or `tail_list/3` before a tail ([list]): a splice's value is a list of quoted expressions.
         */
        LIST,

        /** `dot/5` given arguments: a called name's value is an atom or a call, whose name it takes. */
        CALLED,

        /** `dot/5` given `nil`: an uncalled name's value is an atom, an alias or a quoted call. */
        UNCALLED,
    }
}

/**
 * A definition's head and body as the `def` runs them, each fragment replaced by its value.
 *
 * @property unnamedAt the name's fragment, when its value isn't an atom, or the first fragment that stops a head whose
 *   name or argument count a fragment decides
 * @property stop the first other fragment whose value isn't code, or is placed nowhere
 * @property ordered whether the `def*` call is a statement of the module body, and named
 * @property raised what the escape raises, which ends the module body before the definition is stored
 */
internal class Unquoted(
    val head: ElixirAst,
    val body: ElixirAst?,
    val unnamedAt: ElixirAst?,
    val stop: ElixirAst?,
    val ordered: Boolean,
    val raised: Expansion? = null,
)

/**
 * [definition] as the `def` runs it: each fragment's value is what its expansion knows, or, if [evaluates], what the
 * evaluator gives from [bindings], the values [compiling]'s body has bound or those the
 * evaluator has where it reaches the `def` inside a statement. A value a helper checks ([Fragment.check]) is checked
 * once the fragments the helper's arguments hold are evaluated. The definition stops at the first value that isn't
 * known, or that it places nowhere, and a fragment or helper raises only where every value evaluated before it is known.
 */
internal fun unquoted(
    definition: Pending.Definition,
    compiling: Compiling,
    run: Run,
    evaluates: Boolean = true,
    bindings: HashMap<Binding, OtpErlangObject> = compiling.values.bindings,
): Unquoted {
    // A definition in a followed body is stored once per run of the body: each store's nodes, and so the records the
    // stored clause keeps by node, are its own.
    fun own(node: ElixirAst?): ElixirAst? = if (definition.followed) node?.let { copied(it, run) } else node

    val fragments = definition.fragments
        ?: return Unquoted(own(definition.head)!!, own(definition.body), null, null, definition.statement)
    val name = nameFragment(definition.head)
    val replacements = IdentityHashMap<ElixirAst, ElixirAst>()
    val splices = IdentityHashMap<ElixirAst, List<ElixirAst>>()
    var unnamedAt: ElixirAst? = null
    // The first fragment whose value isn't known, after which none is evaluated.
    var stop: ElixirAst? = null
    // The first fragment the definition stops at: the stop, or one read as code whose value is placed nowhere.
    var stopsAt: ElixirAst? = null

    // The values whose helpers are still to run, the innermost last.
    val held = ArrayDeque<Held>()
    // Each fragment's value and each helper's result, by the node of the escape's code that gives it.
    val escapedValues = IdentityHashMap<ElixirAst, ElixirAst>()

    fun place(fragment: Fragment, placed: Any?) {
        when {
            placed != null -> {}
            fragment.call === name -> unnamedAt = fragment.call
            else -> {
                stop = stop ?: fragment.call
                stopsAt = stopsAt ?: fragment.call
            }
        }
    }

    // A value the walk places nowhere, at which the definition stops.
    fun nowhere(fragment: Fragment, value: Any?): Any? = value?.also { stopsAt = stopsAt ?: fragment.call }

    // The helper the escape hands a value to, with the value and a name's `Left`, which never decides whether it
    // raises.
    fun checked(held: Held, left: OtpErlangObject?): Evaluated? {
        val fragment = held.fragment
        val term = held.term ?: return held.evaluated
        if (!evaluates || stop != null || unnamedAt != null) return null
        val (function, args) = when (fragment.check!!) {
            Fragment.Check.LIST -> fragment.helper?.arguments?.let { arguments ->
                // The helper the escape built: its arguments after the value are the escape's code, run with the values
                // of the fragments and helpers they hold.
                fragment.helperName!! to listOf(term) + arguments.drop(1).map { node ->
                    val code = substitute(node, escapedValues)

                    when (val ran = compiling.values.evaluate(code, fragments.state, definition.env, run, bindings)) {
                        is Evaluated.Value -> ran.value
                        else -> return ran
                    }
                }
            } ?: fragment.list?.let { list ->
                // `tail_list(Left, Right, Tail)`: the tail and the elements before the splice, quoted.
                val bar = list.last() as ElixirAst.Call
                val right = substitute(bar.arguments!![1], replacements, splices).toOtp()
                val tail = substituteAll(list.dropLast(1), replacements, splices).map { it.toOtp() }

                "tail_list" to listOf(term, right, OtpErlangList(tail.toTypedArray()))
            } ?: ("list" to listOf(term, EMPTY))
            Fragment.Check.CALLED -> "dot" to listOf(EMPTY, left ?: NIL, term, EMPTY)
            Fragment.Check.UNCALLED -> "dot" to listOf(EMPTY, left ?: NIL, term, NIL)
        }

        return compiling.values.quoteHelper(function, args, fragment.call, fragments.state, definition.env, run)
    }

    for ((index, fragment) in fragments.values.withIndex()) {
        ProgressManager.checkCanceled()

        if (fragment.call !in replacements) {
            val known = literalOf(fragment.call, fragment.term)
            val evaluated = if (known == null && evaluates && stop == null && unnamedAt == null) {
                compiling.values.evaluate(fragment.expanded, fragments.state, definition.env, run, bindings)
            } else {
                null
            }

            if (evaluated is Evaluated.Raised) {
                return Unquoted(definition.head, definition.body, null, null, definition.statement, Expansion.Error(evaluated.exception, fragment.call))
            }

            val holding = Held(fragment, known, evaluated)

            holding.term?.let { value -> escapedValues[fragment.escaped] = ElixirAst.Value(fragment.escaped.meta, value) }

            when {
                fragment.check != null -> held.addLast(holding)
                fragment.kind == Fragment.Kind.UNQUOTE ->
                    place(fragment, (known ?: holding.term?.let { valueNode(it, fragment.call) })
                        ?.let { replacements[fragment.call] = it })
                else -> place(fragment, nowhere(fragment, holding.term))
            }
        }

        while (held.lastOrNull()?.fragment?.checkedAfter?.let { it <= index } == true) {
            val checking = held.removeLast()
            val call = checking.fragment.call
            // Only a called name's placement reads its `Left`.
            val left = ((call as? ElixirAst.Call)?.callee as? ElixirAst.Call)
                ?.takeIf { checking.fragment.kind == Fragment.Kind.NAME }
                ?.let { substitute(it.arguments!![0], replacements, splices) }
            val leftTerm = left?.toOtp()
            val escaped = checked(checking, leftTerm)

            if (escaped is Evaluated.Raised) {
                return Unquoted(definition.head, definition.body, null, null, definition.statement, Expansion.Error(escaped.exception, call))
            }

            val value = (escaped as? Evaluated.Value)?.value

            if (value != null) checking.fragment.helper?.let { escapedValues[it] = ElixirAst.Value(it.meta, value) }

            val placed = when (checking.fragment.kind) {
                Fragment.Kind.SPLICE ->
                    // Before a tail the splice stands, at its `|`, for the whole list `tail_list/3` gives; elsewhere for
                    // the value the helper took, as the elements beside it are placed by their own nodes.
                    value?.let { if (checking.fragment.list != null) it else checking.term }
                        ?.let(::properElements)
                        ?.map { valueNode(it, call) }
                        ?.takeIf { elements -> elements.all { it != null } }
                        ?.let { elements -> splices[checking.fragment.list?.last() ?: call] = elements.filterNotNull() }
                Fragment.Kind.NAME -> value?.let { dotted(checking.fragment, it, left!!, leftTerm!!) }
                    ?.let { (at, node) -> replacements[at] = node }
                else -> nowhere(checking.fragment, value)
            }

            place(checking.fragment, placed)
        }
    }

    val head = substitute(definition.head, replacements, splices)

    if (!isReadWhole(extractGuards(head).first)) unnamedAt = unnamedAt ?: stopsAt

    return Unquoted(
        own(head)!!,
        own(if (fragments.escapesBody) definition.body?.let { substitute(it, replacements, splices) } else definition.body),
        unnamedAt,
        stopsAt,
        definition.statement && unnamedAt == null,
    )
}

/** [node] copied: each node of it a new one with the same metadata, standing where the original does ([Run.inherits]). */
private fun copied(node: ElixirAst, run: Run): ElixirAst {
    ProgressManager.checkCanceled()

    val copy = when (node) {
        is ElixirAst.Call -> ElixirAst.Call(node.meta, copied(node.callee, run), node.arguments?.map { copied(it, run) }, node.context)
        is ElixirAst.Alias -> ElixirAst.Alias(node.meta, node.segments.map { copied(it, run) })
        is ElixirAst.Block -> ElixirAst.Block(node.meta, node.expressions.map { copied(it, run) })
        is ElixirAst.Tuple -> ElixirAst.Tuple(node.meta, node.elements.map { copied(it, run) })
        is ElixirAst.ListNode -> ElixirAst.ListNode(node.meta, node.elements.map { copied(it, run) })
        is ElixirAst.Placeholder -> ElixirAst.Placeholder(node.meta, node.reason)
        is ElixirAst.Literal.Atom -> ElixirAst.Literal.Atom(node.meta, node.name)
        is ElixirAst.Literal.Integer -> ElixirAst.Literal.Integer(node.meta, node.value)
        is ElixirAst.Literal.Float -> ElixirAst.Literal.Float(node.meta, node.value)
        is ElixirAst.Literal.Binary -> ElixirAst.Literal.Binary(node.meta, node.bytes)
        is ElixirAst.Value -> ElixirAst.Value(node.meta, node.term)
    }

    run.inherits(node, copy)

    return copy
}

/** A fragment's value, [known] from its expansion or [evaluated], held until its helper runs where one checks it. */
private data class Held(val fragment: Fragment, val known: ElixirAst?, val evaluated: Evaluated?) {
    /** The value, `null` where it isn't known. */
    val term: OtpErlangObject? = known?.toOtp() ?: (evaluated as? Evaluated.Value)?.value
}

/**
 * The code [value] is, as a macro's result is read, for [call]: marked built, as no observer is to take it for a node of
 * the source. It is `null` where the value nests too deeply to convert on the thread's stack or is too large to copy.
 */
private fun valueNode(value: OtpErlangObject, call: ElixirAst): ElixirAst? =
    unlessTooDeep({ null }) { codeOf(value, call.meta.origin, values = true)?.let(Quote::builtCopy) }

/** The name in `{{:., _, [_, name]}, _, _}`, the call `elixir_quote:dot/4` made. */
private fun calledName(call: OtpErlangObject): String? {
    val dot = ((call as? OtpErlangTuple)?.elementAt(0) as? OtpErlangTuple)?.takeIf { it.arity() == 3 }

    return (dot?.elementAt(2)?.let(::properElements)?.getOrNull(1) as? OtpErlangAtom)?.atomValue()
}

/**
 * What [value], the call `elixir_quote:dot/5` made for [fragment], `Left.unquote(x)`, replaces, with the node it is:
 * where the call [Fragment.named] has arguments, [fragment] as `Left.name`; where it has none, that call as `dot/5`
 * built it from `nil` arguments, with [left] where it holds [leftTerm].
 */
private fun dotted(fragment: Fragment, value: OtpErlangObject, left: ElixirAst, leftTerm: OtpErlangObject): Pair<ElixirAst, ElixirAst>? {
    val call = fragment.call as ElixirAst.Call
    val dot = call.callee as ElixirAst.Call
    val named = fragment.named!!

    return if (named.arguments != null) {
        calledName(value)?.let { call to ElixirAst.Call(dot.meta, dot.callee, listOf(left, ElixirAst.Literal.Atom(dot.meta, it))) }
    } else {
        codeOf(value, call.meta.origin, values = true, known = { if (it === leftTerm) left else null })
            ?.let { named to it }
    }
}

/** The `unquote` that names [head]'s call, if one does. */
private fun nameFragment(head: ElixirAst): ElixirAst? =
    (extractGuards(head).first as? ElixirAst.Call)?.callee?.takeIf { isCall(it, "unquote", 1) }

/**
 * The literal [fragment]'s value [term] is, if it is one a definition can hold: the code `unquote` inserts, in which a
 * tuple of three is a call and one of any other size but two isn't code at all.
 */
internal fun literalOf(fragment: ElixirAst, term: Term): ElixirAst? {
    ProgressManager.checkCanceled()

    return when (term) {
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
}

/** Whether [node] holds code the port can't read, which the escape reads as an unknown term. */
private fun holdsUnreadCode(node: ElixirAst): Boolean {
    ProgressManager.checkCanceled()

    return node is ElixirAst.Placeholder && node.stopsExpansion || children(node).any(::holdsUnreadCode)
}

/** `{{'.', _, [_, unquote]}, _, [_]}`: a call of one argument whose name is unquoted. */
internal fun isUnquotedCall(node: ElixirAst): Boolean =
    node is ElixirAst.Call && node.arguments?.size == 1 && isUnquotedCallName(node.callee)

/** `{'.', _, [Left, unquote]}`: the name of a remote call that is unquoted. */
internal fun isUnquotedCallName(node: ElixirAst): Boolean =
    isCall(node, ".", 2) && ((node as ElixirAst.Call).arguments!![1] as? ElixirAst.Literal.Atom)?.name == "unquote"

/** What the output of `elixir_quote:escape/3` does when the `def` runs it, in order. */
private sealed interface Piece {
    /** An `unquote(x)`, whose `x` is evaluated. */
    class Fragment(val call: ElixirAst.Call) : Piece

    /**
     * The expression of an `unquote_splicing`, of an unquoted call name or read as code, evaluated, with its [placement],
     * where the walk places the value, and the [helper] call it is an argument of, if the escape built one.
     */
    class Evaluated(val expression: ElixirAst, val placement: Placement, val helper: Quoted?) : Piece

    /**
     * A call the escape makes into `elixir_quote` or `:erlang`, [call], for a splice or an unquoted call name.
     *
     * @property end the index just past its arguments' pieces
     */
    class Quoted(val call: ElixirAst.Call, val receiver: String, val name: String) : Piece {
        val arity get() = call.arguments!!.size

        var end = 0
    }
}

/**
 * What the helper checks of [piece]'s value: the helper call the escape built, or where it built none, as the source
 * escaped as it is places the value.
 */
private fun checkOf(piece: Piece.Evaluated): Fragment.Check? {
    piece.helper?.let { return checkOf(it, piece.expression) }

    return when (piece.placement.kind) {
        Fragment.Kind.UNQUOTE, Fragment.Kind.CODE -> null
        Fragment.Kind.SPLICE -> Fragment.Check.LIST
        Fragment.Kind.NAME ->
            if (piece.placement.named!!.arguments != null) Fragment.Check.CALLED else Fragment.Check.UNCALLED
        Fragment.Kind.STOP -> Fragment.Check.UNCALLED
    }
}

/** What [helper] checks of [argument], one of its arguments: `list/2`'s and `tail_list/3`'s first, `dot/5`'s third. */
private fun checkOf(helper: Piece.Quoted, argument: ElixirAst): Fragment.Check? {
    val arguments = helper.call.arguments!!

    return when {
        helper.receiver != "elixir_quote" -> null
        (helper.name == "list" || helper.name == "tail_list") && arguments[0] === argument -> Fragment.Check.LIST
        helper.name == "dot" && arguments.getOrNull(2) === argument ->
            if ((arguments[3] as? ElixirAst.Literal.Atom)?.name == "nil") {
                Fragment.Check.UNCALLED
            } else {
                Fragment.Check.CALLED
            }
        else -> null
    }
}

/**
 * Where the walk places the value of a splice, an unquoted call name or an expression read as code, [call], as its
 * [kind] says; [named] is the call a called name names, and [list] the elements whose tail a splice precedes.
 */
private class Placement(
    val call: ElixirAst,
    val kind: Fragment.Kind,
    val named: ElixirAst.Call? = null,
    val list: List<ElixirAst>? = null,
)

/**
 * The expressions `elixir_quote:escape/3` evaluates rather than quotes, in a head or body: the `unquote(x)` fragments
 * outside a `quote`'s body, and the expressions of an `unquote_splicing` and of an unquoted call name. A `quote`'s
 * options are unquoted as the rest is.
 */
private class Unquotes {
    private val fragments = IdentityHashMap<ElixirAst, ElixirAst.Call>()
    private val evaluated = IdentityHashMap<ElixirAst, Placement>()

    /**
     * @param listed whether [node] is one of the [quotedElements] of the node that holds it, or of a pair that
     * [isSplicingPair], where `do_quote/2` splices an `unquote_splicing` call; anywhere else that call is a call like any
     * other
     */
    fun collect(node: ElixirAst, listed: Boolean = false) {
        ProgressManager.checkCanceled()

        when {
            isCall(node, "unquote", 1) -> (node as ElixirAst.Call).let { fragments[it.arguments!!.single()] = it }
            isCall(node, "quote", 1) -> {}
            isCall(node, "quote", 2) -> collect((node as ElixirAst.Call).arguments!![0])
            listed && isCall(node, "unquote_splicing", 1) -> {
                val call = node as ElixirAst.Call

                evaluated[call.arguments!!.single()] = Placement(call, Fragment.Kind.SPLICE)
            }
            isUnquotedCall(node) -> {
                val call = node as ElixirAst.Call

                evaluated[call.arguments!!.single()] = Placement(call, Fragment.Kind.STOP)
                collect((call.callee as ElixirAst.Call).arguments!![0])
            }
            node is ElixirAst.Call && isUnquotedCall(node.callee) -> {
                val name = node.callee as ElixirAst.Call

                evaluated[name.arguments!!.single()] = Placement(name, Fragment.Kind.NAME, node)
                collect((name.callee as ElixirAst.Call).arguments!![0])
                collectAll(quotedElements(node).orEmpty())
            }
            else -> {
                if (node is ElixirAst.Call) collect(node.callee)

                val elements = quotedElements(node)

                when {
                    elements != null -> collectAll(elements)
                    // A pair is quoted as `{:{}, [], [Left, Right]}` where it splices, else as its two elements.
                    node is ElixirAst.Tuple ->
                        if (isSplicingPair(node)) collectAll(node.elements) else node.elements.forEach { collect(it) }
                }
            }
        }
    }

    /**
     * [elements], of a list, a call's arguments, a `{}` tuple or a block, as `do_quote_tail/2` quotes them: a splice
     * before their tail, `[..., unquote_splicing(x) | tail]`, is `tail_list/3`'s.
     */
    private fun collectAll(elements: List<ElixirAst>) {
        val splice = (elements.lastOrNull() as? ElixirAst.Call)
            ?.takeIf { isCall(it, "|", 2) }
            ?.arguments?.first()
            ?.takeIf { isCall(it, "unquote_splicing", 1) } as ElixirAst.Call?

        if (splice == null) {
            elements.forEach { collect(it, listed = true) }
        } else {
            elements.dropLast(1).forEach { collect(it, listed = true) }
            evaluated[splice.arguments!!.single()] = Placement(splice, Fragment.Kind.SPLICE, list = elements)
            collect((elements.last() as ElixirAst.Call).arguments!![1], listed = true)
        }
    }

    /**
     * The pieces of [output], the expression the escape built, as the compiler expands it: its `elixir_quote` and
     * `:erlang` calls, and each expression it evaluates, in order. When [escaped] is false, [output] is the source the
     * escape couldn't read, and holds only the expressions.
     */
    fun order(output: ElixirAst, pieces: MutableList<Piece>, escaped: Boolean = true, helper: Piece.Quoted? = null) {
        ProgressManager.checkCanceled()

        val fragment = fragments[output]

        when {
            fragment != null -> pieces += Piece.Fragment(fragment)
            output in evaluated -> pieces += Piece.Evaluated(output, evaluated[output]!!, helper)
            else -> {
                val quoted = if (escaped) quotedCall(output)?.also { pieces += it } else null

                children(output).forEach { order(it, pieces, escaped, quoted ?: helper) }
                quoted?.end = pieces.size
            }
        }
    }

    /**
     * [expression], an `unquote`'s expression that the escape read as code from a macro's term: Elixir expands it with
     * the definition, and it is evaluated with the fragments in escape order.
     */
    fun readAsCode(expression: ElixirAst) {
        evaluated[expression] = Placement(expression, Fragment.Kind.CODE)
    }

    private fun quotedCall(node: ElixirAst): Piece.Quoted? {
        val remote = (node as? ElixirAst.Call)?.callee?.takeIf { isCall(it, ".", 2) } as? ElixirAst.Call ?: return null
        val receiver = (remote.arguments!![0] as? ElixirAst.Literal.Atom)?.name?.takeIf { it in QUOTE_RECEIVERS } ?: return null

        return Piece.Quoted(node, receiver, (remote.arguments[1] as ElixirAst.Literal.Atom).name)
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
        is ElixirAst.Literal, is ElixirAst.Placeholder, is ElixirAst.Value -> emptyList()
    }

/**
 * [node] with each node in [replacements] replaced, each in [splices] replaced by its elements in the list that holds
 * it, the elements of a list, arguments, tuple or block whose `|` before the tail is in [splices] by the whole list
 * `tail_list/3` built, and each pair
 * [isSplicing] holds as `{:{}, [], [Left, Right]}`, as `do_quote/2` quotes it, but in a nested `quote`'s body,
 * rebuilding only what holds a changed node.
 */
internal fun substitute(
    node: ElixirAst,
    replacements: Map<ElixirAst, ElixirAst>,
    splices: Map<ElixirAst, List<ElixirAst>> = emptyMap(),
): ElixirAst {
    ProgressManager.checkCanceled()

    replacements[node]?.let { return it }

    return when (node) {
        is ElixirAst.Call -> {
            val callee = substitute(node.callee, replacements, splices)
            val arguments = node.arguments?.let { arguments ->
                if (isCall(node, "quote", 1) || isCall(node, "quote", 2)) {
                    // `do_quote/2` quotes a nested `quote`'s body with `unquote` off, so nothing in it changes.
                    val options = arguments.dropLast(1)
                    val substituted = substituteAll(options, replacements, splices)

                    if (substituted === options) arguments else substituted + arguments.last()
                } else {
                    substituteAll(arguments, replacements, splices)
                }
            }

            if (callee === node.callee && arguments === node.arguments) node
            else ElixirAst.Call(node.meta, callee, arguments)
        }
        is ElixirAst.Alias ->
            substituteAll(node.segments, replacements, splices).let {
                if (it === node.segments) node else ElixirAst.Alias(node.meta, it)
            }
        is ElixirAst.ListNode ->
            substituteAll(node.elements, replacements, splices).let {
                if (it === node.elements) node else ElixirAst.ListNode(node.meta, it)
            }
        is ElixirAst.Tuple ->
            substituteAll(node.elements, replacements, splices).let {
                when {
                    quotedElements(node) == null && isSplicingPair(node) -> ElixirAst.tupleCall(node.meta, it)
                    it === node.elements -> node
                    // A tuple quoted as `{:{}, meta, elements}` stays a `{}` call where its splices leave two elements.
                    quotedElements(node) != null -> ElixirAst.tupleCall(node.meta, it)
                    else -> ElixirAst.Tuple(node.meta, it)
                }
            }
        is ElixirAst.Block ->
            substituteAll(node.expressions, replacements, splices).let {
                if (it === node.expressions) node else ElixirAst.Block(node.meta, it)
            }
        is ElixirAst.Literal, is ElixirAst.Placeholder, is ElixirAst.Value -> node
    }
}

private fun substituteAll(
    nodes: List<ElixirAst>,
    replacements: Map<ElixirAst, ElixirAst>,
    splices: Map<ElixirAst, List<ElixirAst>>,
): List<ElixirAst> {
    // A splice before the tail stands for the whole list `tail_list/3` gave, keyed by its `|`.
    (nodes.lastOrNull() as? ElixirAst.Call)?.takeIf { isCall(it, "|", 2) }?.let { splices[it] }?.let { return it }

    val substituted = nodes.flatMap { splices[it] ?: listOf(substitute(it, replacements, splices)) }

    return if (substituted.size == nodes.size && substituted.indices.all { substituted[it] === nodes[it] }) nodes else substituted
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
internal fun orGuards(node: ElixirAst): List<ElixirAst> {
    ProgressManager.checkCanceled()

    return whenArguments(node)?.takeIf { it.size == 2 }?.let { (guard, rest) -> listOf(guard) + orGuards(rest) } ?: listOf(node)
}

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
 * `elixir_def:store_definition/5`: [definition], [unquoted] as the `def` ran it, its name checked, its defaults and
 * clause expanded in the env where it was defined, and the definition stored unless that raised. The owner names the
 * definition as far as it is known.
 *
 * @param expand whether the defaults and clause are expanded; where they aren't, the definition is stored as one that
 *   stops at its node
 */
internal fun storeDefinition(
    definition: Pending.Definition,
    unquoted: Unquoted,
    compiling: Compiling,
    run: Run,
    expand: Boolean = true,
): Pair<Owner, Expansion> {
    val kind = definition.kind
    val node = definition.node
    val unnamedAt = unquoted.unnamedAt

    unquoted.raised?.let { return Owner.Definition(kind, null, 0) to it }

    // `store_definition/3`: a `{}` call isn't `{Call, Body}`, so it is read as a cache key, which raises once the
    // fragments have run. Nothing is defined.
    if (definition.fragments?.pairIsCall == true) {
        return Owner.Definition(kind, null, 0) to
            (unquoted.stop?.let { Expansion.Unported(it) } ?: Expansion.Error("Elixir.ArgumentError", node))
    }

    // `retrieve_location/2`, before the name is checked. Only `invalid_def` comes before it in Elixir, and it raises.
    compiling.definitionMoved = moved(takeFile(definition, compiling), unquoted.head, run)

    if (unnamedAt != null) {
        compiling.table.define(null, 0, kind, node, 0, 0, definition.statement, definition.checksClauses)

        return Owner.Definition(kind, null, 0) to Expansion.Unported(unnamedAt)
    }

    val (call, guards) = extractGuards(unquoted.head)
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

    val defaults = defaultsArgs.mapNotNull { arg -> (arg as? ElixirAst.Call)?.takeIf(::isDefault) }
    val nameArity = NameArity(name, arity)
    val args = defaultsArgs.map { arg -> if (isDefault(arg)) (arg as ElixirAst.Call).arguments!![0] else arg }

    /** The definition stored with this clause in its place, stopped at [at]. */
    fun stopped(at: ElixirAst): Pair<Owner, Expansion> {
        if (unquoted.body != null) {
            compiling.clauses.getOrPut(nameArity) { mutableListOf() } += ModuleClauses.Clause(args, guards, null)
        }
        store(definition, unquoted, name, arity, defaults.size, compiling, run)

        return owner to Expansion.Unported(at)
    }

    if (unquoted.stop != null || !expand) return stopped(unquoted.stop ?: node)

    val env = definition.env.copy(function = nameArity)
    val fresh = ExState.empty(run.level).copy(caller = kind.macro)
    val body = unquoted.body?.let { clauseBody(node, it) }
    val (defaulted, expansion) = unlessTooDeep({ return stopped(node) }) {
        val defaulted = expandDefaults(defaults, fresh, env, run, compiling, node)

        defaulted to defaulted.then { _, _ -> clause(node, args, guards, unquoted.body, body, fresh, env, run) }
    }

    if (expansion is Expansion.Error) return owner to expansion

    body?.let { compiling.clauses.getOrPut(nameArity) { mutableListOf() } += ModuleClauses.Clause(args, guards, it) }
    if (defaulted is Expansion.Expanded) {
        val counter = if (DEFAULT_ARGUMENTS_THREAD_STATE.isSufficient(run.level)) defaulted.state.version else 0

        for (clause in defaultClauses(kind, name, defaultsArgs, counter, compiling.dispatches)) {
            compiling.clauses.getOrPut(NameArity(name, clause.args.size)) { mutableListOf() } += clause
        }
    }

    // The store follows the expansion, so from 1.15 the body's errors are reported before the store raises. A body
    // that isn't expanded may raise before the store does.
    val raised = store(definition, unquoted, name, arity, defaults.size, compiling, run)
        ?.takeIf { expansion is Expansion.Expanded }

    return owner to (raised?.let { Expansion.Error(it, node) } ?: expansion)
}

/**
 * [definition], [unquoted] as the `def` ran it, stored as [name] and [arity] with [defaults] defaults: the kind of the
 * error the store raises, if any.
 * A definition whose `def*` call a macro's output built is recorded as injected by the call that injected it, and
 * placed where its `def*` call was built.
 */
private fun store(
    definition: Pending.Definition,
    unquoted: Unquoted,
    name: String,
    arity: Int,
    defaults: Int,
    compiling: Compiling,
    run: Run,
): String? =
    compiling.table.define(
        name,
        arity,
        definition.kind,
        definition.node,
        if (unquoted.body == null) 0 else 1,
        defaults,
        unquoted.ordered,
        definition.checksClauses,
        definition.delegatesTo,
        definition.injectedBy?.let { Injected(it, run.placedAt[definition.node]) },
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
        ProgressManager.checkCanceled()

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
            ProgressManager.checkCanceled()

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
 * `unpack_expanded/7`: the clause of each default arity of [defaultsArgs], whose defaults are expanded. It binds the
 * arguments it is given to variables numbered from [counter], and calls the full arity through `super` with the
 * defaults it lacks. The clause isn't expanded, so each variable's version goes into [dispatches] here.
 */
private fun defaultClauses(
    kind: Kind,
    name: String,
    defaultsArgs: List<ElixirAst>,
    counter: Int,
    dispatches: ModuleClauses.Dispatches,
): List<ModuleClauses.Clause> =
    defaultsArgs.indices.filter { isDefault(defaultsArgs[it]) }.map { index ->
        ProgressManager.checkCanceled()

        val default = defaultsArgs[index] as ElixirAst.Call
        val synthetic = Synthetic(default.meta)
        // `default_var/1`.
        val variable = { version: Int ->
            val keys = listOf(
                Meta.Key.Entry("generated", Meta.Value.Atom("true")),
                Meta.Key.Entry("version", Meta.Value.Integer(version.toLong())),
            )

            synthetic.variable("x$version", "elixir_def", keys).also { dispatches.putVersion(it, version) }
        }
        val base = (0 until index).map { variable(counter + it) }
        var next = counter + index
        val rest = defaultsArgs.drop(index).map { arg ->
            if (isDefault(arg)) null to (arg as ElixirAst.Call).arguments!![1] else variable(next++).let { it to it }
        }
        val superKeys = listOf(
            Meta.Key.Entry("super", Meta.Value.Tuple(listOf(Meta.Value.Atom(kind.name.lowercase()), Meta.Value.Atom(name)))),
        ) + default.meta.keys

        ModuleClauses.Clause(
            base + rest.mapNotNull { it.first },
            emptyList(),
            synthetic.call("super", base + rest.map { it.second }, superKeys),
        )
    }

/** `{'\\\\', Meta, [Arg, Default]}`: an argument with a default. */
private fun isDefault(arg: ElixirAst): Boolean = isCall(arg, "\\\\", 2)

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

/** Whether [pair], a two-element tuple, splices, so that `do_quote/2` quotes it as `{:{}, [], [Left, Right]}`. */
private fun isSplicingPair(pair: ElixirAst.Tuple): Boolean {
    val view = AstEscapeView(Synthetic(pair.meta), ParsedMeta(columns = false))

    return isSplicingPair(view, AstTerm.Node(pair.elements[0]), AstTerm.Node(pair.elements[1]))
}
