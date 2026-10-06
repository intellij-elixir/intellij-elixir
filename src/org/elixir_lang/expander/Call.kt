package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.ANONYMOUS_CALL_OF_ATOM_REFUSED
import org.elixir_lang.language_level.ElixirLanguageFeature.CLAUSES_REFUSED_IN_CALL
import org.elixir_lang.language_level.ElixirLanguageFeature.IMPORTED_FUNCTION_NOT_REEXPANDED
import org.elixir_lang.language_level.ElixirLanguageFeature.PARENS_MAP_LOOKUP_ATOM
import org.elixir_lang.language_level.ElixirLanguageFeature.QUOTED_IMPORT_FUNCTION_TRACED
import org.elixir_lang.language_level.ElixirLanguageFeature.REMOTE_CALL_IN_PATTERN_EXPANDS_ARGUMENTS_IN_TURN
import org.elixir_lang.language_level.ElixirLanguageFeature.SIGNED_NUMBER_REWRITTEN_EVERYWHERE
import org.elixir_lang.language_level.ElixirLanguageFeature.SYSTEM_STACKTRACE_REWRITTEN
import org.elixir_lang.language_level.ElixirLanguageFeature.UNREQUIRED_MACRO_CALLED_AS_FUNCTION
import org.elixir_lang.language_level.ElixirLanguageFeature.UNREQUIRED_MACRO_SEEN_ONLY_WHEN_LOADED
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term

/**
 * The local-call head, `{Atom, Meta, Args}`: `assert_no_ambiguous_op/5`, then `elixir_dispatch:dispatch_import/6`, then
 * `expand_local/5`.
 */
internal fun expandLocalCall(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val name = (node.callee as ElixirAst.Literal.Atom).name
    val args = node.arguments!!

    if (args.size == 1 && hasMetaKey(node.meta, "ambiguous_op") && Variable(name, Variable.NIL) in state.read) {
        return Expansion.Error("op_ambiguity", node)
    }

    return expandImport(
        node,
        state,
        env,
        run,
        ambiguous = { Expansion.Error("ambiguous_call", node) },
        function = { receiver, kind ->
            val (inlinedReceiver, inlined) = importedFunction(node, receiver, kind, run)

            stacktrace(inlinedReceiver, inlined, args.size, state, env, run) ?: expandRemote(
                Term.Atom(inlinedReceiver),
                inlined,
                node,
                state,
                receiverState(state, env, run),
                env,
                run,
            )
        },
        none = {
            if (env.function == null) Expansion.Error("undefined_function", node) else expandLocal(node, state, env, run)
        },
        external = false,
    )
}

/**
 * `elixir_dispatch:do_expand_import/7`'s trace of [call] as a function of [receiver], an event of [kind], or none when
 * it is `null`: the receiver and name after `inline/3`, which it reports.
 */
internal fun importedFunction(
    call: ElixirAst.Call,
    receiver: String,
    kind: Dispatch.Kind?,
    run: Run,
): Pair<String, String> =
    importedFunction(call, receiver, (call.callee as ElixirAst.Literal.Atom).name, call.arguments!!.size, kind, run)

/** As [importedFunction] of a call, for [node], which calls or captures [name]/[arity]. */
internal fun importedFunction(
    node: ElixirAst,
    receiver: String,
    name: String,
    arity: Int,
    kind: Dispatch.Kind?,
    run: Run,
): Pair<String, String> {
    val inlined = inline(receiver, name, arity, run.level) ?: (receiver to name)

    kind?.let { run.observer.dispatched(node, Dispatch(it, inlined.first, inlined.second, arity)) }

    return inlined
}

/**
 * `elixir_import:record/4`: inside a function, [nameArity] dispatched through an import of [receiver], another module,
 * is kept for the import-conflict check once the module's body has run.
 */
internal fun recordImport(nameArity: NameArity, receiver: String, env: Env, run: Run) {
    if (env.function != null && receiver != env.module) run.compiling[env.module]?.imports?.put(nameArity, receiver)
}

/**
 * `elixir_dispatch:expand_import/7` of [call], a local call or what `Macro.expand/2` reads as one: a quoted import's
 * required macro is dispatched, then a local macro, then an imported macro, and [ambiguous], [function] with the
 * function's module and its trace event's kind (`null` when it traces none), or [none] answer the rest.
 *
 * @param external whether [call] is `Macro.expand/2`'s, which reads the module's macros wherever it is.
 *   `dispatch_import/6` reads them only inside a function, and never for the function being defined. The caller reads
 *   the macro's output, which a summary doesn't give, so a summarised macro is [Expansion.Unported] there.
 */
internal fun expandImport(
    call: ElixirAst.Call,
    state: ExState,
    env: Env,
    run: Run,
    ambiguous: () -> Expansion,
    function: (receiver: String, kind: Dispatch.Kind?) -> Expansion,
    none: () -> Expansion,
    external: Boolean = true,
): Expansion {
    val name = (call.callee as ElixirAst.Literal.Atom).name
    val arity = call.arguments!!.size
    val nameArity = NameArity(name, arity)
    val match = findImportByNameArity(call.meta, name, arity, emptyList(), env, run.level)
    val dispatchMacro = { dispatch: Dispatch ->
        if (external && Summaries.of(dispatch) != null) {
            Expansion.Unported(call)
        } else {
            macro(dispatch, call, state, env, run)
        }
    }

    // A quoted import is dispatched before the locals.
    val localMacro = when (match) {
        is ImportMatch.Ambiguous, is ImportMatch.Quoted, ImportMatch.Unreadable -> null
        else -> localMacro(nameArity, env, run, external)
    }

    if (localMacro != null) {
        val module = env.module!!
        val imported = (match as? ImportMatch.Function)?.receiver ?: (match as? ImportMatch.Macro)?.receiver

        if (imported != null && imported != module) return Expansion.Error("macro_conflict", call)

        if (localMacro == DefinitionTable.Kind.DEFMACROP) run.compiling.getValue(module).usedPrivate += nameArity

        return dispatchMacro(Dispatch(Dispatch.Kind.LOCAL_MACRO, module, name, arity))
    }

    return when (match) {
        is ImportMatch.Ambiguous -> ambiguous()
        is ImportMatch.Macro -> {
            recordImport(nameArity, match.receiver, env, run)
            dispatchMacro(Dispatch(Dispatch.Kind.IMPORTED_MACRO, match.receiver, name, arity))
        }
        is ImportMatch.Function -> {
            recordImport(nameArity, match.receiver, env, run)
            function(match.receiver, Dispatch.Kind.IMPORTED_FUNCTION)
        }
        ImportMatch.None -> none()
        // `expand_require(true, ...)`.
        is ImportMatch.Quoted ->
            when (isMacro(match.receiver, name, arity, required = true, run)) {
                true -> dispatchMacro(Dispatch(Dispatch.Kind.REMOTE_MACRO, match.receiver, name, arity))
                false ->
                    function(match.receiver, Dispatch.Kind.REMOTE_FUNCTION.takeIf { isQuotedFunctionTraced(run.level) })
                null -> Expansion.Unported(call)
            }
        ImportMatch.Unreadable -> Expansion.Unported(call)
    }
}

/** Whether the dispatch traces a function a quoted import names: through the remote re-expansion, or in its arm. */
private fun isQuotedFunctionTraced(level: ElixirLanguageLevel): Boolean =
    !IMPORTED_FUNCTION_NOT_REEXPANDED.isSufficient(level) || QUOTED_IMPORT_FUNCTION_TRACED.isSufficient(level)

/**
 * `elixir_def:local_for/5` for a macro, where `elixir_dispatch` allows locals: the kind of [nameArity] if [env]'s module
 * has defined it as a macro so far. Locals are allowed for an [external] lookup, and inside a function for every name
 * and arity but the function's own.
 */
internal fun localMacro(nameArity: NameArity, env: Env, run: Run, external: Boolean = false): DefinitionTable.Kind? =
    if (external || env.function != null && env.function != nameArity) {
        run.compiling[env.module]?.table?.get(nameArity)?.kind?.takeIf { it.macro }
    } else {
        null
    }

/**
 * `elixir_dispatch:find_import_by_name_arity/4` of [call], a local call, in [env]; `null` for an import inside a
 * function, where `elixir_def:local_for/5` looks for a local first. A quoted import is found before the locals.
 */
internal fun importOf(call: ElixirAst.Call, env: Env, level: ElixirLanguageLevel): ImportMatch? {
    val name = (call.callee as ElixirAst.Literal.Atom).name

    return when (val match = findImportByNameArity(call.meta, name, call.arguments!!.size, emptyList(), env, level)) {
        is ImportMatch.Quoted, ImportMatch.Unreadable -> match
        else -> match.takeIf { env.function == null }
    }
}

/**
 * `expand_local/5` inside a function: in a pattern or guard the call is an error and its arguments are expanded;
 * otherwise it is a call of the module's own function, kept for the checks once the module's body has run.
 */
private fun expandLocal(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val args = node.arguments!!

    if (env.context != Env.Context.NONE) {
        return report(ErrorSite.INVALID_LOCAL_INVOCATION, node, env, run) {
            expandArgs(args, state, env, run).withValue(NODE)
        }
    }

    val name = (node.callee as ElixirAst.Literal.Atom).name
    val called = NameArity(name, args.size)

    run.observer.dispatched(node, Dispatch(Dispatch.Kind.LOCAL_FUNCTION, env.module ?: "nil", name, args.size))

    val inArguments = mutableListOf<LocalCall<ElixirAst>>()

    run.callArguments.addLast(inArguments)

    val expansion = try {
        expandArgs(args, state, env, run)
    } finally {
        run.callArguments.removeLast()
    }
    recordLocal(node, called, inArguments, env, run)

    return expansion.withValue(NODE)
}

/**
 * Keeps [call], a local call of [called] inside a function, for the checks once the module's body has run: in the
 * arguments of the local call it is in, or else in its definition's calls.
 */
internal fun recordLocal(
    call: ElixirAst,
    called: NameArity,
    inArguments: List<LocalCall<ElixirAst>>,
    env: Env,
    run: Run,
) {
    val position = location(call.meta)
    val local = LocalCall(call, called, position?.line ?: 0, position?.column ?: 0, inArguments)

    (run.callArguments.lastOrNull() ?: run.compiling[env.module]?.calls?.getOrPut(env.function!!) { mutableListOf() })
        ?.add(local)
}

/**
 * The remote-call head, `{{'.', DotMeta, [Left, Right]}, Meta, Args}`: the receiver is expanded, then
 * `elixir_dispatch:dispatch_require/7` decides between a macro and a function.
 */
internal fun expandRemoteCall(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val (left, right) = (node.callee as ElixirAst.Call).arguments!!
    val name = (right as ElixirAst.Literal.Atom).name

    return Expander.expand(left, receiverState(state, env, run), env, run).thenValue { after, receiverEnv, receiver ->
        if (receiver is Term.Atom) {
            dispatchRequire(receiver.name, name, node, state, receiverEnv, run) { functionReceiver, functionName ->
                remoteFunction(functionReceiver, functionName, node, state, after, receiverEnv, run)
            }
        } else {
            expandRemote(receiver, name, node, state, after, receiverEnv, run)
        }
    }
}

/**
 * The anonymous-call head, `{{'.', DotMeta, [Expr]}, Meta, Args}`: no dispatch, and the value is a call at run time.
 */
internal fun expandAnonymousCall(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    noMatchOrGuardScope(node, state, env)?.let { return it }

    val function = (node.callee as ElixirAst.Call).arguments!!.single()

    return expandArgs(listOf(function) + node.arguments!!, state, env, run).thenValue { s, e, values ->
        val callsAnAtom = (values as Term.List).elements.first() is Term.Atom

        if (callsAnAtom && ANONYMOUS_CALL_OF_ATOM_REFUSED.isSufficient(run.level)) {
            report(ErrorSite.INVALID_FUNCTION_CALL, node, env, run) { Expansion.Expanded(s, e, NODE) }
        } else {
            Expansion.Expanded(s, e, NODE)
        }
    }
}

/** `elixir_env:prepare_write/2`, or before 1.18 `prepare_write/1`: the state a remote call's receiver starts from. */
private fun receiverState(state: ExState, env: Env, run: Run): ExState =
    if (env.context == Env.Context.NONE || !REMOTE_CALL_IN_PATTERN_EXPANDS_ARGUMENTS_IN_TURN.isSufficient(run.level)) {
        state.prepareWrite()
    } else {
        state
    }

/** Inside `rescue` or `catch`, before 1.14, `System.stacktrace()` is rewritten to `__STACKTRACE__`. */
private fun stacktrace(receiver: String, name: String, arity: Int, state: ExState, env: Env, run: Run): Expansion? =
    Expansion.Expanded(state, env, VARIABLE_NODE).takeIf {
        receiver == SYSTEM && name == "stacktrace" && arity == 0 && state.stacktrace &&
            SYSTEM_STACKTRACE_REWRITTEN.isSufficient(run.level)
    }

/**
 * `elixir_dispatch:dispatch_require/7` for an atom [receiver]: an inlined function, a required macro, or else a
 * function, which [function] is given as its receiver and name.
 */
internal fun dispatchRequire(
    receiver: String,
    name: String,
    node: ElixirAst.Call,
    state: ExState,
    env: Env,
    run: Run,
    function: (receiver: String, name: String) -> Expansion,
): Expansion {
    val arity = node.arguments!!.size

    stacktrace(receiver, name, arity, state, env, run)?.let { return it }

    inline(receiver, name, arity, run.level)?.let { (inlinedReceiver, inlinedName) ->
        return function(inlinedReceiver, inlinedName)
    }

    val required = receiver == env.module || isRequiredByMeta(node.meta) || receiver in env.requires

    return when (isMacro(receiver, name, arity, required, run)) {
        null -> Expansion.Unported(node)
        true ->
            when {
                required -> macro(Dispatch(Dispatch.Kind.REMOTE_MACRO, receiver, name, arity), node, state, env, run)
                // Inside a function the deprecation check doesn't load the module first.
                UNREQUIRED_MACRO_SEEN_ONLY_WHEN_LOADED.isSufficient(run.level) || env.function != null ->
                    Expansion.Unported(node)
                else -> Expansion.Error("unrequired_module", node)
            }
        false -> function(receiver, name)
    }
}

/**
 * `elixir_dispatch:is_macro/4`, or before 1.13 `get_macros/2`: whether [name]/[arity] is a macro of [receiver], or
 * `null` where its exports can't be read. From 1.13 an unrequired [receiver]'s macros aren't read.
 */
internal fun isMacro(receiver: String, name: String, arity: Int, required: Boolean, run: Run): Boolean? =
    if (required || !UNREQUIRED_MACRO_CALLED_AS_FUNCTION.isSufficient(run.level)) {
        when (val exports = run.exports.of(receiver)) {
            ModuleExports.Absent -> false
            ModuleExports.Unreadable -> null
            is ModuleExports.Present -> NameArity(name, arity) in exports.macros
        }
    } else {
        false
    }

private fun remoteFunction(
    receiver: String,
    name: String,
    node: ElixirAst.Call,
    state: ExState,
    after: ExState,
    env: Env,
    run: Run,
): Expansion {
    run.observer.dispatched(node, Dispatch(Dispatch.Kind.REMOTE_FUNCTION, receiver, name, node.arguments!!.size))

    return expandRemote(Term.Atom(receiver), name, node, state, after, env, run)
}

/**
 * `expand_remote/8`: [node]'s arguments expanded, and the call rewritten for its context by `elixir_rewrite`.
 *
 * @param receiver the expanded receiver, after `inline/3`
 * @param name the name, after `inline/3`
 * @param state the state before the call, `S`
 * @param after the state after the receiver, `SL`
 */
private fun expandRemote(
    receiver: Term,
    name: String,
    node: ElixirAst.Call,
    state: ExState,
    after: ExState,
    env: Env,
    run: Run,
): Expansion {
    val level = run.level
    val args = node.arguments!!

    if (receiver !is Term.Atom && receiver !is Term.Pair && receiver !is Term.Node) {
        return Expansion.Error("invalid_call", node)
    }

    if (CLAUSES_REFUSED_IN_CALL.isSufficient(level) && hasClauses(args)) return Expansion.Error("invalid_clauses", node)

    if (env.context == Env.Context.GUARD && receiver !is Term.Atom) {
        return when {
            isNoParens(node.meta) -> Expansion.Expanded(after, env, NODE)
            PARENS_MAP_LOOKUP_ATOM.isSufficient(level) ->
                report(ErrorSite.PARENS_MAP_LOOKUP, node, env, run) { Expansion.Expanded(after, env, NODE) }
            else -> Expansion.Error("parens_map_lookup_guard", node)
        }
    }

    val isSign = (receiver as? Term.Atom)?.name == ERLANG && (name == "+" || name == "-") && args.size == 1

    if (env.context != Env.Context.NONE && REMOTE_CALL_IN_PATTERN_EXPANDS_ARGUMENTS_IN_TURN.isSufficient(level)) {
        val literal = args.singleOrNull()

        if (isSign && (literal is ElixirAst.Literal.Integer || literal is ElixirAst.Literal.Float)) {
            return Expansion.Expanded(after, env, signed(name, literalValue(literal)))
        }

        return mapfold(args, after, env) { arg, s, e -> Expander.expand(arg, s, e, run) }.thenValue { s, e, values ->
            rewrite(receiver, name, values as Term.List, node, s, e, run)
        }
    }

    return mapfold(args, after, env) { arg, s, e -> expandArg(arg, s, state, e, run) }.thenValue { s, e, values ->
        if (env.context == Env.Context.NONE) queueEffect(receiver, name, node, values as Term.List, env, run)

        val closed = s.closeWrite(state)
        val arg = (values as Term.List).elements.singleOrNull()
        val folds = env.context == Env.Context.MATCH || SIGNED_NUMBER_REWRITTEN_EVERYWHERE.isSufficient(level)

        // Every `NonTuple` the expander gives is a float.
        if (isSign && folds && (arg is Term.Integer || arg is Term.NonTuple)) {
            Expansion.Expanded(closed, e, signed(name, arg))
        } else {
            rewrite(receiver, name, values, node, closed, e, run)
        }
    }
}

/**
 * What a call of [receiver] with [values] in a module body does to the module's attributes, if anything, queued at its
 * place in the order the body runs. A call in a function runs only when the function does.
 */
private fun queueEffect(receiver: Term, name: String, node: ElixirAst.Call, values: Term.List, env: Env, run: Run) {
    val module = env.module ?: return
    val compiling = run.compiling[module] ?: return

    if (receiver !is Term.Atom || env.function != null) return

    val dispatch = Dispatch(Dispatch.Kind.REMOTE_FUNCTION, receiver.name, name, values.elements.size)
    val effect = ModuleEffects.of(dispatch, values.elements, module, run.level) ?: return
    val at = compiling.site(node)

    run.pending += Pending.Attribute(effect, at, compiling.isStatement(at))
}

/**
 * `elixir_rewrite`'s `match/6` or `guard/6`, or `rewrite/5` with no context, on the expanded [args]: the error, or the
 * call's value.
 */
private fun rewrite(
    receiver: Term,
    name: String,
    args: Term.List,
    node: ElixirAst.Call,
    state: ExState,
    env: Env,
    run: Run,
): Expansion {
    val atom = (receiver as? Term.Atom)?.name

    return when (env.context) {
        // The `String.Chars.to_string/1` fold: a binary is its own string.
        Env.Context.NONE -> {
            val arg = args.elements.singleOrNull()
            val isToString = atom == STRING_CHARS && name == "to_string" && arg is Term.Binary

            Expansion.Expanded(state, env, if (isToString) arg else NODE)
        }
        Env.Context.MATCH ->
            if (atom == ERLANG && name == "++" && args.elements.size == 2) {
                staticAppend(args.elements[0], args.elements[1])?.let { Expansion.Expanded(state, env, it) }
                    ?: Expansion.Error("invalid_match_append", node)
            } else {
                Expansion.Error("invalid_match", node)
            }
        Env.Context.GUARD ->
            if (atom != null && isAllowedInGuard(atom, name, args.elements.size, run.level)) {
                Expansion.Expanded(state, env, NODE)
            } else {
                Expansion.Error("invalid_guard", node)
            }
    }
}

/** `+N` or `-N`, as [name] signs [number], an integer or a float. */
private fun signed(name: String, number: Term): Term =
    if (name == "-" && number is Term.Integer) Term.Integer(number.value.negate()) else number

/** `assert_no_clauses/4`: whether the last of [args] is a keyword list with `->` clauses as a value. */
private fun hasClauses(args: List<ElixirAst>): Boolean {
    for (element in (args.lastOrNull() as? ElixirAst.ListNode)?.elements.orEmpty()) {
        val (key, value) = (element as? ElixirAst.Tuple)?.elements?.takeIf { it.size == 2 } ?: return false

        if (key !is ElixirAst.Literal.Atom) return false

        val first = (value as? ElixirAst.ListNode)?.elements?.firstOrNull()

        if (first != null && isNamedCall(first, "->")) return true
    }

    return false
}

/** Whether [meta] has `{key, true}`. */
private fun isTrue(meta: Meta, key: String): Boolean =
    meta.keys.any { it is Meta.Key.Entry && it.name == key && (it.value as? Meta.Value.Atom)?.name == "true" }

private fun isNoParens(meta: Meta) = isTrue(meta, "no_parens")

private fun isRequiredByMeta(meta: Meta) = isTrue(meta, "required")

private const val STRING_CHARS = "Elixir.String.Chars"
private const val SYSTEM = "Elixir.System"
