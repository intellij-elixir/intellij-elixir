package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFIMPL_EXPANDS_PROTOCOL
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFIMPL_FOR_EXPANDED_IN_KERNEL
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFIMPL_FOR_REQUIRED
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFIMPL_IMPL4
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term
import java.util.IdentityHashMap

/**
 * `Kernel.defimpl/2,3`, which is `Protocol.__impl__/4`: the options merged and `:for` taken from them, the protocol and
 * the type expanded as literals, and one `defmodule` of the output for each type a list names. The module's name is
 * computed by `Protocol.__concat__/2` when the body runs, which the macro supplies to `defmodule`; and the definitions
 * that unquote the protocol and the type, which only the body running binds, are settled with their atoms.
 */
internal val DEFIMPL = Summary { _, node, state, env, run ->
    val arguments = node.arguments!!
    val options = ImplOptions.of(arguments[1], arguments.getOrNull(2))
    val compiling = moduleBody(env, run)

    when {
        options == null || env.context != Env.Context.NONE || compiling == null && env.module != null -> Expansion.Unported(node)
        else -> defimpl(node, arguments[0], options, compiling, state, env, run)
    }
}

/**
 * What `Keyword.merge/2` and `Keyword.pop_lazy/3` make of `defimpl`'s options: the first `for:`, how many there are, and
 * the others.
 */
private class ImplOptions(val forValue: ElixirAst?, val forCount: Int, val rest: List<Pair<String, ElixirAst>>) {
    companion object {
        fun of(written: ElixirAst, doBlock: ElixirAst?): ImplOptions? {
            val first = keyword(written) ?: return null
            val second = doBlock?.let { keyword(it) ?: return null }.orEmpty()
            val merged = first.filter { (key, _) -> second.none { it.first == key } } + second

            return ImplOptions(
                merged.firstOrNull { it.first == "for" }?.second,
                merged.count { it.first == "for" },
                merged.filter { it.first != "for" },
            )
        }

        private fun keyword(node: ElixirAst): List<Pair<String, ElixirAst>>? =
            (node as? ElixirAst.ListNode)?.elements?.map { pairOf(it) ?: return null }
    }
}

private fun defimpl(
    node: ElixirAst.Call,
    protocol: ElixirAst,
    options: ImplOptions,
    compiling: Compiling?,
    state: ExState,
    env: Env,
    run: Run,
): Expansion {
    val s = Synthetic(node.meta)
    val level = run.level
    val forValue = options.forValue
        ?: env.module?.let(s::atom)
        ?: return if (DEFIMPL_FOR_REQUIRED.isSufficient(level)) Expansion.Error("impl_outside_no_for", node) else Expansion.Unported(node)

    // Up to v1.13 `Kernel.defimpl/3` raises for an explicit `for: nil` as well.
    if (!DEFIMPL_IMPL4.isSufficient(level) && DEFIMPL_FOR_REQUIRED.isSufficient(level) && isNil(forValue)) {
        return Expansion.Error("impl_outside_no_for", node)
    }

    // Since v1.14 the protocol (v1.19) and `for:` are expanded as literals here; before, the body expands them.
    val expansionEnv = if (DEFIMPL_FOR_EXPANDED_IN_KERNEL.isSufficient(level)) {
        env.copy(module = KERNEL, function = NameArity("defimpl", 3))
    } else {
        env.copy(module = env.module ?: "Elixir", function = NameArity("__impl__", 1))
    }
    val expandedProtocol = if (DEFIMPL_EXPANDS_PROTOCOL.isSufficient(level)) {
        when (val expanded = macroExpandLiterals(protocol, state, expansionEnv, run)) {
            is MacroExpanded.Node -> expanded.node
            is MacroExpanded.Stopped -> return expanded.expansion
            MacroExpanded.Dir -> protocol
        }
    } else {
        protocol
    }
    val expandedFor = if (DEFIMPL_IMPL4.isSufficient(level)) {
        when (val expanded = macroExpandLiterals(forValue, state, expansionEnv, run)) {
            is MacroExpanded.Node -> expanded.node
            is MacroExpanded.Stopped -> return expanded.expansion
            MacroExpanded.Dir -> forValue
        }
    } else {
        forValue
    }
    val block = when {
        !DEFIMPL_IMPL4.isSufficient(level) ->
            // `Protocol.do_defimpl/2` takes exactly `[do: block, for: for]`, sorted.
            if (options.rest.size == 1 && options.rest[0].first == "do" && (options.forValue == null || options.forCount == 1)) {
                options.rest[0].second
            } else {
                return Expansion.Error("impl_options", node)
            }
        options.rest.isEmpty() -> return Expansion.Error("impl_no_do", node)
        options.rest.size == 1 && options.rest[0].first == "do" -> options.rest[0].second
        else -> return Expansion.Error("impl_bad_opt", node)
    }

    val outputs = mutableListOf<ImplOutput>()

    // `for f <- for, do: __impl__(protocol, f, block)`, for each element of a list, however deep.
    fun output(type: ElixirAst): ElixirAst =
        if (type is ElixirAst.ListNode) {
            s.list(type.elements.map(::output))
        } else {
            ImplOutput(s, level, expandedProtocol, type, block).also { outputs += it }.output()
        }

    val output = output(expandedFor)
    val protocolName = namedBy(expandedProtocol, state, env, run)
    val protocolNode = protocolName?.let(s::atom) ?: expandedProtocol

    if (compiling == null && protocolName != null) {
        // Elixir expands the whole file and then runs it, so the check comes after the forms before the call, which aren't
        // compiled yet and one of which can make the protocol available, and after the forms after it expand: a check that
        // fails isn't answered.
        if (run.pending.any { it is Pending.Module && it.name == protocolName }) return Expansion.Unported(node)

        if (assertProtocol(protocolName, node, run, "impl_not_available", "impl_not_a_protocol") is Asserted.Failed) {
            return Expansion.Unported(node)
        }
    }

    // `Protocol.__concat__/2` runs when the body does; the macro knows its value where both are atoms.
    val supplied = IdentityHashMap<ElixirAst, Term>()

    if (protocolName != null) {
        for (implementation in outputs) {
            val type = namedBy(implementation.type, state, env, run) ?: continue

            supplied[implementation.name] = Term.Atom(implementationName(level, protocolName, type))
        }

        // Before v1.19 `@behaviour` is given the variable that holds the protocol.
        for (implementation in outputs) implementation.behaviourVariable?.let { supplied[it] = Term.Atom(protocolName) }
    }

    val from = run.pending.size
    val expansion = expandQuoted(node, KERNEL, output, state, env, run, supplied = supplied)

    if (expansion is Expansion.Expanded && protocolName != null) {
        val queued = run.pending.subList(from, run.pending.size).toList()
        var next = 0

        if (queued.count { it is Pending.Module } == outputs.size) {
            run.replacePending(
                from,
                queued.flatMap { entry ->
                    if (entry is Pending.Module) {
                        val type = outputs[next++].type

                        implemented(entry, node, protocolName, protocolNode, namedBy(type, state, env, run)?.let(s::atom) ?: type, compiling, run)
                    } else {
                        listOf(entry)
                    }
                },
            )
        }
    }

    return expansion
}

/** `nil`, written as it is. */
private fun isNil(node: ElixirAst) = (node as? ElixirAst.Literal.Atom)?.name == "nil"

/**
 * The module [node] names when it is an atom, an alias of atoms or `__MODULE__`, which Elixir resolves where the
 * implementation runs: through `Macro.expand_literals/2` here, or when its body runs before v1.14. Resolving it emits no
 * event.
 */
private fun namedBy(node: ElixirAst, state: ExState, env: Env, run: Run): String? {
    if (node is ElixirAst.Literal.Atom) return node.name

    // Only an alias or `__MODULE__` is resolved; expanding the literals of another node would trace what it holds again.
    val resolved = node is ElixirAst.Alias && node.segments.first().let { it is ElixirAst.Literal.Atom || isVariableNamed(it, "__MODULE__") } ||
        isVariableNamed(node, "__MODULE__")

    if (!resolved) return null

    return ((macroExpandLiterals(node, state, env, run) as? MacroExpanded.Node)?.node as? ElixirAst.Literal.Atom)?.name
}

/**
 * The entries the output queued for one implementation: the module, settled with the protocol and the type its
 * definitions unquote, and, in a module body, the check of the protocol that runs before it.
 */
private fun implemented(
    module: Pending.Module,
    node: ElixirAst.Call,
    protocolName: String,
    protocol: ElixirAst,
    type: ElixirAst,
    compiling: Compiling?,
    run: Run,
): List<Pending> {
    val settled = Pending.Module(
        module.node, module.name, module.isAtom, module.body, module.env, module.state,
        settle = { pending -> ImplTail(protocol, type).settled(pending) },
        applied = module.applied,
    )

    if (compiling == null) return listOf(settled)

    val check = Pending.Effect(
        node,
        ordered = compiling.isStatement(node),
        apply = { (assertProtocol(protocolName, node, run, "impl_not_available", "impl_not_a_protocol") as? Asserted.Failed)?.expansion },
    )

    return listOf(check, settled)
}

/** What the implementation module queued that unquotes the protocol and the type, the body's own variables. */
private class ImplTail(private val protocol: ElixirAst, private val type: ElixirAst) {
    fun settled(pending: List<Pending>): List<Pending> =
        pending.map { entry ->
            if (entry is Pending.Definition && entry.stop != null) {
                filled(entry) { argument ->
                    when {
                        isProtocolVariable(argument, "protocol") -> protocol
                        isProtocolVariable(argument, "for") -> type
                        else -> null
                    }
                }
            } else {
                entry
            }
        }
}
