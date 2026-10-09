package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.expander.ModuleExports.Present
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_DERIVING_MACRO
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term

/**
 * `Protocol.__derive__/3` (`P:1116`), which `defstruct` calls with the [derived] writes of `@derive`: the checks of the protocol that runs
 * first, then the macro Elixir expands there, which is where [node] stops. Nothing is derived where `@derive` is
 * empty. Protocols run in the order `:lists.flatten/1` gives the accumulated attribute, the last written first, and
 * the first decides the outcome, as what follows it can't be followed past its macro or its fallback.
 *
 * @return an error where the protocol is no protocol, or has no `Any` implementation to derive from; [Expansion.Opaque]
 *   at the macro; [Expansion.Unported] where the derivation can't be followed; `null` where nothing is derived
 */
internal fun derive(node: ElixirAst.Call, derived: List<AttributeValue>, run: Run): Expansion? {
    val protocols = derived.asReversed().flatMap { protocolsOf(it) ?: return Expansion.Unported(node) }
    val protocol = protocols.firstOrNull() ?: return null

    val exports = when (val asserted = assertProtocol(protocol, node, run, "derive_not_available", "derive_not_a_protocol")) {
        is Asserted.Failed -> return asserted.expansion
        is Asserted.Is -> asserted.exports
    }

    if (PROTOCOL_DERIVING_MACRO.isSufficient(run.level) && NameArity("__deriving__", 2) in exports.macros) {
        return Expansion.Opaque(node, Dispatch(Dispatch.Kind.REMOTE_MACRO, protocol, "__deriving__", 2))
    }

    val any = "$protocol.Any"
    val implementation = when (val found = run.exports.of(any)) {
        ModuleExports.Absent -> return Expansion.Error("derive_not_available", node)
        ModuleExports.Unreadable -> return Expansion.Unported(node)
        is Present -> found
    }

    if (NameArity("__impl__", 1) !in implementation.functions) return Expansion.Error("derive_not_an_implementation", node)

    // A `__deriving__/3` that is a function runs, and with none Elixir defines the implementation: neither is followed.
    return if (NameArity("__deriving__", 3) in implementation.macros) {
        Expansion.Opaque(node, Dispatch(Dispatch.Kind.REMOTE_MACRO, any, "__deriving__", 3))
    } else {
        Expansion.Unported(node)
    }
}

/**
 * The protocols [value], one write of `@derive`, names, flattened: an atom, or a `{protocol, options}` pair. `null`
 * where the value isn't known, or holds what `foreach` has no clause for.
 */
private fun protocolsOf(value: AttributeValue): List<String>? =
    (value as? AttributeValue.Known)?.let { protocolsOf(it.term) }

private fun protocolsOf(term: Term): List<String>? =
    when (term) {
        is Term.Atom -> listOf(term.name)
        is Term.Pair -> (term.first as? Term.Atom)?.let { listOf(it.name) }
        is Term.List -> if (term.tail != null) null else flatten(term.elements.map(::protocolsOf))
        else -> null
    }

private fun <T> flatten(parts: List<List<T>?>): List<T>? = if (parts.any { it == null }) null else parts.flatMap { it!! }
