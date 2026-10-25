package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_CONCAT
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.fitsAnAtom
import org.elixir_lang.psi.Import.Term
import java.math.BigInteger

/**
 * `Kernel.defprotocol/2`: `Protocol.__protocol__/2`'s output is expanded where the call is, and
 * `Protocol.__before_compile__/1`, which only warns, is applied by the macro.
 */
internal val DEFPROTOCOL = Summary { _, node, state, env, run ->
    val (name, options) = node.arguments!!
    val block = ((options as? ElixirAst.ListNode)?.elements?.singleOrNull()?.takeIf { keyOf(it) == "do" } as? ElixirAst.Tuple)
        ?.elements?.get(1)

    if (block == null) {
        Expansion.Unported(node)
    } else {
        val from = run.pending.size
        val expansion = expandQuoted(node, KERNEL, ProtocolOutput(Synthetic(node.meta), run.level, name, block).output(), state, env, run, ports = true)
        val index = (from until run.pending.size).firstOrNull { run.pending[it] is Pending.Module }

        if (expansion is Expansion.Expanded && index != null) {
            val module = run.pending[index] as Pending.Module

            run.pending[index] = Pending.Module(
                module.node, module.name, module.isAtom, module.body, module.env, module.state,
                applied = setOf(PROTOCOL to "__before_compile__"),
                injectedBy = module.injectedBy,
                outer = module.outer,
                placed = module.placed,
                statement = module.statement,
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
            val expansion = expandQuoted(node, PROTOCOL, output, state, env, run, ports = true)

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
                is ElixirAst.Value -> {
                    val arguments = (signature.term as? OtpErlangTuple)?.takeIf { it.arity() == 3 }?.elementAt(2)

                    if (arguments is OtpErlangAtom || (arguments as? OtpErlangList)?.arity() == 0) {
                        Raises("protocol_def_no_args")
                    } else {
                        Raises("protocol_def_bad")
                    }
                }
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

    run.pending[index] = Pending.Attribute(write.effect, write.at, statement = true, write.raises) { attributes, bindings ->
        val before = attributes.read(attribute) as? Term.List

        if (before == null || before.tail != null) {
            write.resolve?.invoke(attributes, bindings) ?: write.effect
        } else {
            Effect.Write(attribute, Term.List(listOf(declared) + before.elements))
        }
    }
}

private fun attributeWrite(pending: Pending, name: String): Effect.Write? =
    ((pending as? Pending.Attribute)?.effect as? Effect.Write)?.takeIf { it.name == name }

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
 * `Module.concat/2` before it, which drops a `nil`. `null` past an atom's length, which raises `system_limit`.
 */
internal fun implementationName(level: ElixirLanguageLevel, left: String, right: String): String? =
    if (PROTOCOL_CONCAT.isSufficient(level)) protocolConcat(left, right).takeIf(::fitsAnAtom) else concat(listOf(left, right))

/** `Protocol.__concat__/2`: the module [right] names under the protocol [left], an atom's `Elixir.` prefix dropped. */
private fun protocolConcat(left: String, right: String): String =
    "${left.takeIf { it.startsWith("Elixir.") } ?: "Elixir.$left"}.${right.removePrefix("Elixir.")}"
