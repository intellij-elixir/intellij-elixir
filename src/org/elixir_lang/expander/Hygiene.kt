package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.VAR_BANG_IF_UNDEFINED
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta

/** The `counter` entry of [meta]: `{Module, n}`, or the integer Elixir draws when there is no module. */
internal fun counterOf(meta: Meta): Env.Counter? =
    metaValue(meta, "counter")
        ?.let { value ->
            when (value) {
                is Meta.Value.Integer -> Env.Counter.Unique(value.value)
                is Meta.Value.Tuple -> {
                    val (module, n) = value.elements

                    Env.Counter.InModule((module as Meta.Value.Atom).name, (n as Meta.Value.Integer).value)
                }
                is Meta.Value.Atom, is Meta.Value.Binary, is Meta.Value.Keywords, is Meta.Value.List -> null
            }
        }

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
        val n = counts.merge(module, 1, Long::plus)!!

        return if (module == null) Env.Counter.Unique(n) else Env.Counter.InModule(module, n)
    }

    /** How many counters [module] has given. */
    fun count(module: String?): Long = counts[module] ?: 0
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
): Expansion {
    val counter = run.counters.next(env.module)

    return Expander.expand(linifyWithContextCounter(lineOf(call.meta), receiver, counter, output), state, env, run)
}

/**
 * `linify_with_context_counter/3`, without `generated`: [line] added to every node with metadata that has none, unless
 * it is 0, and [counter] to a `quote` or a variable of [receiver]'s context, an alias, and an `alias`, `import` or
 * `require` call.
 */
internal fun linifyWithContextCounter(line: Int, receiver: String, counter: Env.Counter, node: ElixirAst): ElixirAst {
    val counterValue = counterMetaValue(counter)
    val receiverContext = ElixirAst.VariableContext.Atom(receiver)

    fun lined(meta: Meta): Meta = if (line == 0) meta else keynew(meta, "line", Meta.Value.Integer(line.toLong()))

    fun counted(meta: Meta, isCounted: Boolean): Meta = lined(if (isCounted) keynew(meta, "counter", counterValue) else meta)

    fun linify(node: ElixirAst): ElixirAst =
        when (node) {
            is ElixirAst.Call -> {
                val name = (node.callee as? ElixirAst.Literal.Atom)?.name
                val arguments = node.arguments
                val isCounted = when {
                    arguments == null -> name != null && name != "_" && node.context == receiverContext
                    arguments.isEmpty() -> false
                    name == "quote" -> (metaValue(node.meta, "context") as? Meta.Value.Atom)?.name == receiver
                    else -> name in LEXICAL
                }

                ElixirAst.Call(counted(node.meta, isCounted), linify(node.callee), arguments?.map(::linify), node.context)
            }
            is ElixirAst.Alias -> ElixirAst.Alias(counted(node.meta, node.segments.isNotEmpty()), node.segments.map(::linify))
            is ElixirAst.Block -> ElixirAst.Block(lined(node.meta), node.expressions.map(::linify))
            is ElixirAst.Tuple ->
                ElixirAst.Tuple(if (node.hasMetadata()) lined(node.meta) else node.meta, node.elements.map(::linify))
            is ElixirAst.ListNode -> ElixirAst.ListNode(node.meta, node.elements.map(::linify))
            is ElixirAst.Placeholder -> ElixirAst.Placeholder(lined(node.meta), node.reason)
            is ElixirAst.Literal -> node
        }

    return linify(node)
}

/** `?line(Meta)`: the `line` in [meta], from the source or added by [linifyWithContextCounter], or 0. */
internal fun lineOf(meta: Meta): Int =
    meta.keys.firstNotNullOfOrNull { key ->
        when (key) {
            is Meta.Key.Location -> key.position.line
            is Meta.Key.Entry -> (key.value as? Meta.Value.Integer)?.takeIf { key.name == "line" }?.value?.toInt()
        }
    } ?: 0

/** `Kernel.var!/1,2`. */
internal val VAR_BANG = Summary { _, node, state, env, run ->
    val (variable, context) = node.arguments!!.let { it[0] to it.getOrNull(1) }

    if (variable !is ElixirAst.Call || variable.arguments != null || variable.callee !is ElixirAst.Literal.Atom) {
        return@Summary Expansion.Error("var_bang_not_a_variable", node)
    }

    val expandedContext = if (context == null) "nil" else when (val expanded = macroExpandToAtom(context, env, run)) {
        is MacroExpanded.Atom -> expanded.name
        MacroExpanded.Other -> return@Summary Expansion.Error("var_bang_context_not_atom", node)
        MacroExpanded.Unported -> return@Summary Expansion.Unported(context)
    }
    val (key, value) = if (VAR_BANG_IF_UNDEFINED.isSufficient(run.level)) "if_undefined" to "raise" else "var" to "true"
    val keys = keystore(keydelete(variable.meta.keys, "counter"), key, Meta.Value.Atom(value))
    val meta = Meta(variable.meta.origin, variable.meta.start, variable.meta.end, keys, variable.meta.built)
    val output = ElixirAst.Call(
        meta,
        variable.callee,
        null,
        if (expandedContext == "nil") ElixirAst.VariableContext.Nil else ElixirAst.VariableContext.Atom(expandedContext),
    )

    expandQuoted(node, KERNEL, output, state, env, run)
}

/** `Kernel.alias!/1`. */
internal val ALIAS_BANG = Summary { _, node, state, env, run ->
    val output = when (val alias = node.arguments!!.single()) {
        is ElixirAst.Literal.Atom -> alias
        is ElixirAst.Alias -> {
            val meta = alias.meta

            ElixirAst.Alias(Meta(meta.origin, meta.start, meta.end, keydelete(meta.keys, "alias"), meta.built), alias.segments)
        }
        else -> return@Summary Expansion.Error("alias_bang_function_clause", node)
    }

    expandQuoted(node, KERNEL, output, state, env, run)
}

private sealed class MacroExpanded {
    class Atom(val name: String) : MacroExpanded()

    object Other : MacroExpanded()

    object Unported : MacroExpanded()
}

/**
 * Whether `Macro.expand/2` gives an atom for [node] in the caller's [env]. A node the port can't follow it through, such
 * as one it would expand by running a macro, is [MacroExpanded.Unported].
 */
private fun macroExpandToAtom(node: ElixirAst, env: Env, run: Run): MacroExpanded =
    when {
        node is ElixirAst.Literal.Atom -> MacroExpanded.Atom(node.name)
        node is ElixirAst.Alias ->
            if (node.segments.first() is ElixirAst.Literal.Atom) {
                aliasesModule(node, env, run.level)?.let { MacroExpanded.Atom(it) } ?: MacroExpanded.Unported
            } else {
                MacroExpanded.Unported
            }
        isVariable(node) && variableName(node) == "__MODULE__" -> MacroExpanded.Atom(env.module ?: "nil")
        node is ElixirAst.Call && node.arguments != null -> macroExpandCall(node, env, run)
        node is ElixirAst.Placeholder -> MacroExpanded.Unported
        else -> MacroExpanded.Other
    }

private fun macroExpandCall(node: ElixirAst.Call, env: Env, run: Run): MacroExpanded {
    val arity = node.arguments!!.size

    when (val callee = node.callee) {
        is ElixirAst.Literal.Atom ->
            return when (val match = importOf(node, env, run.level)) {
                is ImportMatch.Function -> {
                    importedFunction(node, match.receiver, Dispatch.Kind.IMPORTED_FUNCTION, run)
                    recordImport(NameArity(callee.name, node.arguments!!.size), match.receiver, env, run)
                    MacroExpanded.Other
                }
                ImportMatch.None -> MacroExpanded.Other
                is ImportMatch.Macro, is ImportMatch.Ambiguous, is ImportMatch.Quoted, ImportMatch.Unreadable, null ->
                    MacroExpanded.Unported
            }
        is ElixirAst.Call -> {
            val (left, right) = dotArguments(callee)?.takeIf { it.size == 2 } ?: return MacroExpanded.Other
            val name = (right as? ElixirAst.Literal.Atom)?.name ?: return MacroExpanded.Other

            if (isVariable(left) && variableName(left) == "__ENV__") return MacroExpanded.Unported

            val receiver = when (val expanded = macroExpandToAtom(left, env, run)) {
                is MacroExpanded.Atom -> expanded.name
                MacroExpanded.Other -> return MacroExpanded.Other
                MacroExpanded.Unported -> return MacroExpanded.Unported
            }

            return when (val exports = run.exports.of(receiver)) {
                ModuleExports.Absent -> MacroExpanded.Other
                ModuleExports.Unreadable -> MacroExpanded.Unported
                is ModuleExports.Present ->
                    if (NameArity(name, arity) in exports.macros) MacroExpanded.Unported else MacroExpanded.Other
            }
        }
        else -> return MacroExpanded.Other
    }
}

private fun variableName(node: ElixirAst): String = ((node as ElixirAst.Call).callee as ElixirAst.Literal.Atom).name

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
