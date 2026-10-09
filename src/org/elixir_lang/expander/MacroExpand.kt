package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.MACRO_EXPAND_CHECKS_REMOTE_FUNCTION_DEPRECATION
import org.elixir_lang.lowering.ElixirAst

/** What `Macro.expand/2` or `Macro.expand_once/2` gives a node. */
internal sealed class MacroExpanded {
    /** [node], which [expanded] says is an expansion of the node given rather than that node. */
    data class Node(val node: ElixirAst, val expanded: Boolean) : MacroExpanded()

    /** `__DIR__`'s binary, the directory of the file, which [Env] doesn't hold. */
    data object Dir : MacroExpanded()

    /** Where the port stops: an [Expansion.Error], [Expansion.Unported] or [Expansion.Opaque]. */
    data class Stopped(val expansion: Expansion) : MacroExpanded()
}

/** `Macro.expand/2` of [node] in [env]: [macroExpandOnce] until it leaves the node as it is. */
internal fun macroExpand(node: ElixirAst, state: ExState, env: Env, run: Run): MacroExpanded {
    var current = node
    var expanded = false

    while (true) {
        when (val once = macroExpandOnce(current, state, env, run)) {
            is MacroExpanded.Node ->
                if (once.expanded) {
                    current = once.node
                    expanded = true
                } else {
                    return MacroExpanded.Node(current, expanded)
                }
            MacroExpanded.Dir, is MacroExpanded.Stopped -> return once
        }
    }
}

/**
 * `Macro.expand_literals/2` of [node] in [env]: each alias of atoms and each `__MODULE__` in a literal, `%{}` or tuple,
 * list, pair or struct, and in the default of an `Application.compile_env/3`, expanded as [macroExpand] expands it.
 * Anything else is as it was.
 */
internal fun macroExpandLiterals(node: ElixirAst, state: ExState, env: Env, run: Run): MacroExpanded =
    try {
        val literals = LiteralExpander(state, env, run).expand(node)

        MacroExpanded.Node(literals, expanded = literals !== node)
    } catch (stop: LiteralsStopped) {
        MacroExpanded.Stopped(stop.expansion)
    }

/** Thrown where [macroExpand] stops inside [macroExpandLiterals]. */
private class LiteralsStopped(val expansion: Expansion) : RuntimeException(null, null, false, false)

private class LiteralExpander(private val state: ExState, private val env: Env, private val run: Run) {
    fun expand(node: ElixirAst): ElixirAst =
        when {
            node is ElixirAst.Alias -> {
                val segments = all(node.segments)
                val rebuilt = if (segments === node.segments) node else ElixirAst.Alias(node.meta, segments)

                if (segments.all { it is ElixirAst.Literal.Atom }) expanded(rebuilt) else rebuilt
            }
            isVariableNamed(node, "__MODULE__") -> expanded(node)
            node is ElixirAst.Tuple -> all(node.elements).let { if (it === node.elements) node else ElixirAst.Tuple(node.meta, it) }
            node is ElixirAst.ListNode ->
                all(node.elements).let { if (it === node.elements) node else ElixirAst.ListNode(node.meta, it) }
            isCall(node, "%", 2) || isNamedCall(node, "%{}") || isNamedCall(node, "{}") -> {
                val call = node as ElixirAst.Call
                val arguments = all(call.arguments!!)

                if (arguments === call.arguments) node else ElixirAst.Call(call.meta, call.callee, arguments)
            }
            isCompileEnv(node) -> {
                val call = node as ElixirAst.Call
                val (app, key, default) = call.arguments!!
                val expanded = expand(default)

                if (expanded === default) node else ElixirAst.Call(call.meta, call.callee, listOf(app, key, expanded))
            }
            else -> node
        }

    /** [nodes], each expanded, or the list itself where none changed. */
    private fun all(nodes: List<ElixirAst>): List<ElixirAst> {
        val expanded = nodes.map(::expand)

        return if (expanded.indices.all { expanded[it] === nodes[it] }) nodes else expanded
    }

    private fun expanded(node: ElixirAst): ElixirAst =
        when (val result = macroExpand(node, state, env, run)) {
            is MacroExpanded.Node -> result.node
            MacroExpanded.Dir -> node
            is MacroExpanded.Stopped -> throw LiteralsStopped(result.expansion)
        }

    /** `Application.compile_env(app, key, default)`. */
    private fun isCompileEnv(node: ElixirAst): Boolean {
        val call = node as? ElixirAst.Call ?: return false
        val dot = dotArguments(call.callee)?.takeIf { it.size == 2 } ?: return false
        val receiver = dot[0] as? ElixirAst.Alias

        return call.arguments?.size == 3 &&
            receiver?.segments?.singleOrNull().let { it is ElixirAst.Literal.Atom && it.name == "Application" } &&
            (dot[1] as? ElixirAst.Literal.Atom)?.name == "compile_env"
    }
}

/**
 * `Macro.expand_once/2` of [node] in [env], tracing as it does. A macro with no summary is [Expansion.Opaque], and one
 * whose summary isn't a [Summary.Rewrite] is [Expansion.Unported]. An alias's `alias_reference` trace isn't reported.
 */
internal fun macroExpandOnce(node: ElixirAst, state: ExState, env: Env, run: Run): MacroExpanded {
    val unchanged = MacroExpanded.Node(node, expanded = false)

    return when {
        node is ElixirAst.Alias -> expandAlias(node, state, env, run)
        node is ElixirAst.Placeholder && node.stopsExpansion -> MacroExpanded.Stopped(Expansion.Unported(node))
        node !is ElixirAst.Call -> unchanged
        isVariable(node) ->
            when ((node.callee as ElixirAst.Literal.Atom).name) {
                "__MODULE__" -> MacroExpanded.Node(ElixirAst.Literal.Atom(node.meta, env.module ?: "nil"), expanded = true)
                "__DIR__" -> MacroExpanded.Dir
                "__ENV__" -> MacroExpanded.Stopped(Expansion.Unported(node))
                else -> unchanged
            }
        node.arguments == null -> unchanged
        node.callee is ElixirAst.Literal.Atom -> expandLocal(node, state, env, run)
        else -> {
            val (left, right) = dotArguments(node.callee)?.takeIf { it.size == 2 } ?: return unchanged
            val name = (right as? ElixirAst.Literal.Atom)?.name ?: return unchanged

            expandRemote(node, left, name, state, env, run)
        }
    }
}

/** An alias: its module or, when its head isn't an atom, that head expanded once and concatenated if it is an atom. */
private fun expandAlias(alias: ElixirAst.Alias, state: ExState, env: Env, run: Run): MacroExpanded {
    val head = alias.segments.first()

    if (head is ElixirAst.Literal.Atom) {
        val module = aliasesModule(alias, env, run.level) ?: return MacroExpanded.Stopped(Expansion.Unported(alias))

        return MacroExpanded.Node(ElixirAst.Literal.Atom(alias.meta, module), expanded = true)
    }

    val tail = alias.segments.drop(1).map {
        (it as? ElixirAst.Literal.Atom)?.name ?: return MacroExpanded.Stopped(Expansion.Unported(alias))
    }

    return when (val expanded = macroExpandOnce(head, state, env, run)) {
        is MacroExpanded.Node ->
            (expanded.node as? ElixirAst.Literal.Atom)
                ?.let { MacroExpanded.Node(ElixirAst.Literal.Atom(alias.meta, concat(listOf(it.name) + tail)), true) }
                ?: MacroExpanded.Node(alias, expanded = false)
        MacroExpanded.Dir -> MacroExpanded.Node(alias, expanded = false)
        is MacroExpanded.Stopped -> expanded
    }
}

/** A local call: a special form is left as it is, and anything else is what [expandImport] finds. */
private fun expandLocal(call: ElixirAst.Call, state: ExState, env: Env, run: Run): MacroExpanded {
    val name = (call.callee as ElixirAst.Literal.Atom).name
    val args = call.arguments!!
    val unchanged = MacroExpanded.Node(call, expanded = false)

    if (specialForm(name, args.size, run.level)) return unchanged

    return expandImport(
        call,
        env,
        run,
        macro = { dispatch -> expandMacro(dispatch, call, state, env, run) },
        function = { receiver, kind ->
            importedFunction(call, receiver, kind, run)

            if (receiver == KERNEL && (name == "+" || name == "-") && args.size == 1) {
                fold(call, name, args.single(), state, env, run)
            } else {
                unchanged
            }
        },
        none = { unchanged },
        stop = { MacroExpanded.Stopped(it) },
    )
}

/** `Kernel.+/1` or `-/1` of [arg] expanded once, folded when that is an integer. */
private fun fold(call: ElixirAst.Call, name: String, arg: ElixirAst, state: ExState, env: Env, run: Run): MacroExpanded =
    when (val operand = macroExpandOnce(arg, state, env, run)) {
        is MacroExpanded.Node ->
            when (val integer = (operand.node as? ElixirAst.Literal.Integer)?.value) {
                null -> MacroExpanded.Node(call, expanded = false)
                else -> {
                    val value = if (name == "-") integer.negate() else integer

                    MacroExpanded.Node(ElixirAst.Literal.Integer(call.meta, value), expanded = true)
                }
            }
        MacroExpanded.Dir -> MacroExpanded.Node(call, expanded = false)
        is MacroExpanded.Stopped -> operand
    }

/** A remote call: its receiver expanded once and, if that is an atom, what [expandRequire] finds. */
private fun expandRemote(
    call: ElixirAst.Call,
    left: ElixirAst,
    name: String,
    state: ExState,
    env: Env,
    run: Run,
): MacroExpanded {
    val unchanged = MacroExpanded.Node(call, expanded = false)

    // `__ENV__.field`; `__ENV__` with arguments expands to a map, which isn't a receiver.
    if (isVariableNamed(left, "__ENV__")) {
        return if (call.arguments!!.isEmpty()) MacroExpanded.Stopped(Expansion.Unported(call)) else unchanged
    }

    val receiver = when (val expanded = macroExpandOnce(left, state, env, run)) {
        is MacroExpanded.Node -> (expanded.node as? ElixirAst.Literal.Atom)?.name ?: return unchanged
        MacroExpanded.Dir -> return unchanged
        is MacroExpanded.Stopped -> return expanded
    }

    return expandRequire(
        receiver,
        name,
        call,
        env,
        run,
        macro = { dispatch -> expandMacro(dispatch, call, state, env, run) },
        function = {
            if (MACRO_EXPAND_CHECKS_REMOTE_FUNCTION_DEPRECATION.isSufficient(run.level)) {
                checkDeprecated(CalledKind.FUNCTION, call, receiver, name, call.arguments!!.size, env, run)
            }

            unchanged
        },
        stop = { MacroExpanded.Stopped(it) },
    )
}

/** `Macro.Env`'s `wrap_expansion/7` of [call], which dispatches as the macro [dispatch]. */
private fun expandMacro(dispatch: Dispatch, call: ElixirAst.Call, state: ExState, env: Env, run: Run): MacroExpanded {
    checkDeprecated(dispatch, call, env, run)

    return summarised(dispatch, call, opaque = { MacroExpanded.Stopped(it) }) { summary ->
        when (summary) {
            is Summary.Rewrite -> {
                run.observer.dispatched(call, dispatch)

                when (val output = summary.output(dispatch, call, state, env, run)) {
                    is Summary.Output.Built -> {
                        val counter = run.counters.next(env.module)

                        MacroExpanded.Node(linifyWithContextCounter(0, dispatch.receiver, counter, output.ast), true)
                    }
                    is Summary.Output.Raised -> MacroExpanded.Stopped(Expansion.Error(output.kind, call))
                    is Summary.Output.Unported -> MacroExpanded.Stopped(Expansion.Unported(output.at))
                    is Summary.Output.Stopped -> MacroExpanded.Stopped(output.expansion)
                }
            }
            else -> MacroExpanded.Stopped(Expansion.Unported(call))
        }
    }
}
