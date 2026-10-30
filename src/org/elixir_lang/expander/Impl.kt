package org.elixir_lang.expander

import com.intellij.openapi.progress.ProgressManager
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
 * computed by `Protocol.__concat__/2` when the body runs, which the macro supplies to `defmodule`.
 */
internal val DEFIMPL = Summary { _, node, state, env, run ->
    val arguments = node.arguments!!
    val options = ImplOptions.of(arguments[1], arguments.getOrNull(2))
    val compiling = moduleBody(env, run)

    when {
        options == null || env.context != Env.Context.NONE || compiling == null && env.module != null -> Expansion.Unported(node)
        else -> defimpl(node, arguments[0], options, state, env, run)
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
            val secondKeys = second.mapTo(HashSet()) { it.first }
            val merged = first.filter { (key, _) -> key !in secondKeys } + second

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
    fun output(type: ElixirAst): ElixirAst {
        ProgressManager.checkCanceled()

        return if (type is ElixirAst.ListNode) {
            s.list(type.elements.map(::output))
        } else {
            ImplOutput(s, level, expandedProtocol, type, block).also { outputs += it }.output()
        }
    }

    val output = output(expandedFor)
    val protocolName = namedBy(expandedProtocol, state, env, run)

    // `Protocol.__concat__/2` runs when the body does; the macro knows its value where both are atoms.
    val supplied = IdentityHashMap<ElixirAst, Term>()

    if (protocolName != null) {
        for (implementation in outputs) {
            val type = namedBy(implementation.type, state, env, run) ?: continue

            supplied[implementation.name] = Term.Atom(implementationName(level, protocolName, type) ?: continue)
        }

        // Before v1.19 `@behaviour` is given the variable that holds the protocol.
        for (implementation in outputs) implementation.behaviourVariable?.let { supplied[it] = Term.Atom(protocolName) }
    }

    val from = run.pending.size
    val expansion = expandQuoted(node, KERNEL, output, state, env, run, supplied = supplied, ports = true)

    if (expansion is Expansion.Expanded && protocolName != null) {
        val queued = run.pending.subList(from, run.pending.size).toList()
        val ordered = run.statementsAt(env)?.contains(node) == true
        val entries = queued.flatMap { entry -> if (entry is Pending.Module) checked(entry, node, protocolName, ordered, run) else listOf(entry) }

        run.queueOutput(from, node, ordered, queued = { entries })
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
 * One implementation's module, after the check of the protocol that runs before it, where the body or the file runs
 * it, so after the modules before it compile, one of which can make the protocol available.
 */
private fun checked(module: Pending.Module, node: ElixirAst.Call, protocolName: String, ordered: Boolean, run: Run): List<Pending> {
    val check = Pending.Check(node, ordered) {
        (assertProtocol(protocolName, node, run, "impl_not_available", "impl_not_a_protocol") as? Asserted.Failed)?.expansion
    }

    return listOf(check, module)
}
