package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_BEFORE_COMPILE
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_CONCAT
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_DISPATCHES_BY_IMPL_TARGET
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term
import java.math.BigInteger

/**
 * `Kernel.defprotocol/2`: `Protocol.__protocol__/2`'s output is expanded where the call is. What the module it defines
 * queues for the definitions that depend on `@fallback_to_any` and the functions it declares is settled once its body has
 * run, and `Protocol.__before_compile__/1`, which only warns, is applied by the macro.
 */
internal val DEFPROTOCOL = Summary { _, node, state, env, run ->
    val (name, options) = node.arguments!!
    val block = ((options as? ElixirAst.ListNode)?.elements?.singleOrNull()?.takeIf { keyOf(it) == "do" } as? ElixirAst.Tuple)
        ?.elements?.get(1)

    if (block == null) {
        Expansion.Unported(node)
    } else {
        val from = run.pending.size
        val expansion = expandQuoted(node, KERNEL, ProtocolOutput(Synthetic(node.meta), run.level, name, block).output(), state, env, run)
        val index = (from until run.pending.size).firstOrNull { run.pending[it] is Pending.Module }

        if (expansion is Expansion.Expanded && index != null) {
            val module = run.pending[index] as Pending.Module

            run.pending[index] = Pending.Module(
                module.node, module.name, module.isAtom, module.body, module.env, module.state,
                settle = { pending -> ProtocolTail(module.name, module.node.meta, run.level).settled(pending) },
                applied = setOf(PROTOCOL to "__before_compile__"),
            )
        }

        expansion
    }
}

/**
 * `Protocol.def/1`: the function it declares, its output expanded where the call is. Where the call is a statement of the
 * module body and the functions declared before it are known, the `@__functions__` it writes is known as well.
 */
internal val PROTOCOL_DEF = Summary { _, node, state, env, run ->
    when (val shape = ProtocolFunction.of(node.arguments!!.single())) {
        is ProtocolFunction.Raises -> Expansion.Error(shape.kind, node)
        ProtocolFunction.Unread -> Expansion.Unported(node)
        is ProtocolFunction.Declared -> {
            val from = run.pending.size
            val output = ProtocolDefOutput(Synthetic(node.meta), run.level, shape.name, shape.arguments).output()
            val expansion = expandQuoted(node, PROTOCOL, output, state, env, run)

            if (expansion is Expansion.Expanded) knowFunctions(shape, from, run)

            expansion
        }
    }
}

/** What `Protocol.def/1`'s clauses make of a signature. */
private sealed interface ProtocolFunction {
    class Declared(val name: String, val arguments: List<ElixirAst>) : ProtocolFunction

    class Raises(val kind: String) : ProtocolFunction

    /** A signature whose clause the expander doesn't follow. */
    data object Unread : ProtocolFunction

    companion object {
        fun of(signature: ElixirAst): ProtocolFunction =
            when (signature) {
                is ElixirAst.Call -> {
                    val arguments = signature.arguments

                    when {
                        // `{_, _, args}` with `args == [] or is_atom(args)`: no arguments, or a variable.
                        arguments.isNullOrEmpty() -> Raises("protocol_def_no_args")
                        signature.callee is ElixirAst.Literal.Atom -> Declared(signature.callee.name, arguments)
                        else -> Raises("protocol_def_bad")
                    }
                }
                is ElixirAst.Literal, is ElixirAst.ListNode -> Raises("protocol_def_bad")
                is ElixirAst.Tuple -> if (signature.elements.size == 2) Raises("protocol_def_bad") else Unread
                is ElixirAst.Alias, is ElixirAst.Block, is ElixirAst.Placeholder -> Unread
            }
    }
}

/**
 * The `@__functions__` write [shape]'s output queued, [from] the entries it started at, which adds [shape] to the
 * functions the attribute holds where the walk reaches the write, when they are known.
 */
private fun knowFunctions(shape: ProtocolFunction.Declared, from: Int, run: Run) {
    val attribute = functionsAttribute(run.level)
    val index = (from until run.pending.size).firstOrNull { attributeWrite(run.pending[it], attribute) != null } ?: return
    val write = run.pending[index] as Pending.Attribute

    if (!write.statement) return

    val declared = Term.Pair(Term.Atom(shape.name), Term.Integer(BigInteger.valueOf(shape.arguments.size.toLong())))

    run.pending[index] = Pending.Attribute(write.effect, write.at, statement = true) { attributes ->
        val before = attributes.read(attribute) as? Term.List

        if (before == null || before.tail != null) write.effect else Effect.Write(attribute, Term.List(listOf(declared) + before.elements))
    }
}

private fun attributeWrite(pending: Pending, name: String): Effect.Write? =
    ((pending as? Pending.Attribute)?.effect as? Effect.Write)?.takeIf { it.name == name }

/** Whether [node] is the variable [name] of `Protocol`'s own quotes. */
internal fun isProtocolVariable(node: ElixirAst, name: String) =
    isVariableNamed(node, name) && (node as ElixirAst.Call).context == ElixirAst.VariableContext.Atom(PROTOCOL)

/** What `Protocol.assert_protocol!/2` makes of a module. */
internal sealed interface Asserted {
    /** The module is a protocol, with these exports. */
    class Is(val exports: ModuleExports.Present) : Asserted

    /** The module is not, or can't be read: the error that raises, or the stop. */
    class Failed(val expansion: Expansion) : Asserted
}

/**
 * `Protocol.assert_protocol!/2` of [protocol], at [node]: the module must be available, and define `__protocol__/1`.
 * [notAvailable] and [notAProtocol] are the kinds of its two errors.
 */
internal fun assertProtocol(protocol: String, node: ElixirAst, run: Run, notAvailable: String, notAProtocol: String): Asserted =
    when (val found = run.exports.of(protocol)) {
        ModuleExports.Absent -> Asserted.Failed(Expansion.Error(notAvailable, node))
        ModuleExports.Unreadable -> Asserted.Failed(Expansion.Unported(node))
        is ModuleExports.Present ->
            if (NameArity("__protocol__", 1) in found.functions) Asserted.Is(found) else Asserted.Failed(Expansion.Error(notAProtocol, node))
    }

/**
 * The module [right] names under the protocol [left] on [level]: `Protocol.__concat__/2` from [PROTOCOL_CONCAT], and
 * `Module.concat/2` before it, which drops a `nil`.
 */
internal fun implementationName(level: ElixirLanguageLevel, left: String, right: String): String =
    if (PROTOCOL_CONCAT.isSufficient(level)) protocolConcat(left, right) else concat(listOf(left, right))

/** `Protocol.__concat__/2`: the module [right] names under the protocol [left], an atom's `Elixir.` prefix dropped. */
private fun protocolConcat(left: String, right: String): String =
    "${left.takeIf { it.startsWith("Elixir.") } ?: "Elixir.$left"}.${right.removePrefix("Elixir.")}"

/**
 * What the protocol module [module] queued that depends on values only the body running gives, which the expander
 * doesn't evaluate: `@fallback_to_any`, which decides the `Any` implementation `impl_for/1` answers, and the functions
 * declared, which `__protocol__(:functions)` lists. Each such definition, and each branch of the `if` that chooses
 * `impl_for!/1`, is an effect in the queue that reads those attributes where the walk reaches it, as Elixir does, and
 * queues what the definition holds then.
 */
internal class ProtocolTail(private val module: String, meta: Meta, private val level: ElixirLanguageLevel) {
    private val s = Synthetic(meta)

    /** [pending] with each definition that waits on the body's values, or is a branch of `impl_for!/1`, an effect. */
    fun settled(pending: List<Pending>): List<Pending> {
        val branches = pending.filter { it is Pending.Definition && !it.statement && named(it, "impl_for!", 1) }
            .takeIf { it.size == 2 }.orEmpty()

        return pending.map { entry ->
            if (entry is Pending.Definition && (entry.stop != null || entry in branches)) {
                waiting(entry, branches.indexOf(entry))
            } else {
                entry
            }
        }
    }

    /** [definition], the [branch]th of the `if`'s two unless [branch] is negative, as an effect. */
    private fun waiting(definition: Pending.Definition, branch: Int): Pending {
        var queued: List<Pending> = listOf(definition)

        return Pending.Effect(definition.node, ordered = true, queued = { queued }) { compiling ->
            queued = filling(definition, branch, compiling.attributes)
            null
        }
    }

    /**
     * What [definition] holds with the attributes as [attributes] has them: itself when they aren't known, and, for a
     * branch, nothing unless it is the one `if any_impl_for` takes.
     */
    private fun filling(definition: Pending.Definition, branch: Int, attributes: AttributeTable): List<Pending> {
        val fallback = fallbackToAny(attributes) ?: return listOf(definition)
        val functions = declared(attributes) ?: return listOf(definition)
        val fragments = Fragments(anyImplFor(fallback), functions, description(attributes))
        val filled = if (definition.stop != null) fill(definition, fragments) else listOf(definition)

        if (branch < 0) return filled

        // `if any_impl_for do def impl_for!(data) ... else def impl_for!(data) ... end` runs one `def`. `if` expands to a
        // `case` whose `false`/`nil` clause, the `else`, comes first: the second is taken when there is an `Any`
        // implementation, otherwise the first.
        return if (branch == (if (fallback) 1 else 0)) filled.map(Pending::asStatement) else emptyList()
    }

    /** What the body's variables hold: the `Any` implementation, the sorted functions, and the undefined-implementation text. */
    private class Fragments(val anyImplFor: ElixirAst, val functions: ElixirAst, val description: ElixirAst?)

    /**
     * `any_impl_for`: `nil`, the `Any` module, or before [PROTOCOL_DISPATCHES_BY_IMPL_TARGET] the quoted call of that
     * module's `__impl__(:target)`.
     */
    private fun anyImplFor(fallback: Boolean): ElixirAst {
        if (!fallback) return s.atom("nil")

        val any = s.atom(implementationName(level, module, "Any"))

        return if (PROTOCOL_DISPATCHES_BY_IMPL_TARGET.isSufficient(level)) {
            s.remoteCall(any, "__impl__", listOf(s.atom("target")))
        } else {
            any
        }
    }

    /** The text of `@undefined_impl_description`, `""` where it isn't set, or `null` where it isn't known. */
    private fun description(attributes: AttributeTable): ElixirAst? {
        val value = attributes.row("undefined_impl_description") ?: return ElixirAst.Literal.Binary(s.meta(), ByteArray(0))
        val bytes = ((value as? AttributeValue.Known)?.term as? Term.Binary)?.bytes ?: return null

        return ElixirAst.Literal.Binary(s.meta(), bytes)
    }

    /** Whether `@fallback_to_any` is true, if it is known. */
    private fun fallbackToAny(attributes: AttributeTable): Boolean? = attributes.read("fallback_to_any").isTruthy()

    /** The functions declared, `{name, arity}` as `:lists.sort/1` orders them, if `@__functions__` is known. */
    private fun declared(attributes: AttributeTable): ElixirAst? {
        val list = attributes.read(functionsAttribute(level)) as? Term.List ?: return null
        // Before `PROTOCOL_BEFORE_COMPILE` the body can write `@functions`, and `:lists.sort/1` takes any term in it.
        val functions = list.elements.map { element ->
            val pair = element as? Term.Pair ?: return null

            ((pair.first as? Term.Atom)?.name ?: return null) to ((pair.second as? Term.Integer)?.value ?: return null)
        }

        return s.list(
            functions
                .sortedWith { a, b -> compareCodePoints(a.first, b.first).takeIf { it != 0 } ?: a.second.compareTo(b.second) }
                .map { (name, arity) -> s.tuple(s.atom(name), s.integer(arity)) },
        )
    }

    /** [stuck], which waits on values of the body, once for each type if it is `defprotocol`'s loop over them. */
    private fun fill(stuck: Pending.Definition, fragments: Fragments): List<Pending> {
        if (!callsGuard(stuck.head)) {
            return listOf(filled(stuck) { fragment(it, fragments) })
        }

        // The loop's `fn` body is expanded once, and runs for each of the built-in types.
        return BUILT_IN.map { (type, guard) ->
            val target = s.atom(implementationName(level, module, "Elixir.$type"))

            filled(
                stuck,
                certain = true,
                name = { call -> guard.takeIf { isGuardCall(call) } },
            ) { argument -> fragment(argument, fragments) ?: target.takeIf { isProtocolVariable(argument, "target") } }
        }
    }

    private fun fragment(argument: ElixirAst, fragments: Fragments): ElixirAst? =
        when {
            isProtocolVariable(argument, "any_impl_for") -> fragments.anyImplFor
            isProtocolVariable(argument, "undefined_impl_description") -> fragments.description
            isSortedFunctions(argument) -> fragments.functions
            else -> null
        }

    /** `:lists.sort(@__functions__)`, or `@functions` before [PROTOCOL_BEFORE_COMPILE]. */
    private fun isSortedFunctions(argument: ElixirAst): Boolean {
        val call = argument as? ElixirAst.Call ?: return false
        val dot = dotArguments(call.callee) ?: return false

        val sorted = call.arguments?.singleOrNull()

        return dot.size == 2 && (dot[0] as? ElixirAst.Literal.Atom)?.name == "lists" &&
            (dot[1] as? ElixirAst.Literal.Atom)?.name == "sort" &&
            sorted != null && isCall(sorted, "@", 1) &&
            isVariableNamed((sorted as ElixirAst.Call).arguments!!.single(), functionsAttribute(level))
    }

    /** Whether [node] holds a call of `Protocol`'s own `guard`, the name the loop over the built-in types gives a head. */
    private fun callsGuard(node: ElixirAst): Boolean =
        isUnquotedCall(node) && isGuardCall(node as ElixirAst.Call) || children(node).any(::callsGuard)

    /** Whether the unquoted [call] unquotes `Protocol`'s own `guard`. */
    private fun isGuardCall(call: ElixirAst.Call): Boolean = isProtocolVariable(call.arguments!!.single(), "guard")

    private fun named(definition: Pending.Definition, name: String, arity: Int): Boolean {
        val call = extractGuards(definition.head).first as? ElixirAst.Call ?: return false

        return (call.callee as? ElixirAst.Literal.Atom)?.name == name && call.arguments?.size == arity
    }
}
