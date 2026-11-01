package org.elixir_lang.expander

import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.NameArity
import org.elixir_lang.expander.ModuleExports.Present
import org.elixir_lang.language_level.ElixirLanguageFeature.PROTOCOL_DERIVING_MACRO
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term

/**
 * `Protocol.__derive__/3` (`P:1116`), which `defstruct` calls with the [derived] writes of `@derive`: the checks of each
 * protocol, in the order `:lists.flatten/1` gives the accumulated attribute, the last written first. The module goes on
 * past a `__deriving__` macro, whose output isn't followed.
 *
 * @return an error where a protocol is no protocol, or has no `Any` implementation to derive from;
 *   [Expansion.Unported] where a derivation can't be followed; `null` where the module goes on
 */
internal fun derive(node: ElixirAst.Call, derived: List<AttributeValue>, run: Run): Expansion? {
    for (write in derived.asReversed()) {
        val protocols = protocolsOf(write) ?: return Expansion.Unported(node)

        for (protocol in protocols) deriving(protocol, node, run)?.let { return it }
    }

    return null
}

/** One protocol's step of [derive]. */
private fun deriving(protocol: String, node: ElixirAst.Call, run: Run): Expansion? {
    val exports = when (val asserted = assertProtocol(protocol, node, run, "derive_not_available", "derive_not_a_protocol")) {
        is Asserted.Failed -> return asserted.expansion
        is Asserted.Is -> asserted.exports
    }

    if (PROTOCOL_DERIVING_MACRO.isSufficient(run.level) && NameArity("__deriving__", 2) in exports.macros) return null

    val implementation = when (val found = run.exports.of("$protocol.Any")) {
        ModuleExports.Absent -> return Expansion.Error("derive_not_available", node)
        ModuleExports.Unreadable -> return Expansion.Unported(node)
        is Present -> found
    }

    if (NameArity("__impl__", 1) !in implementation.functions) return Expansion.Error("derive_not_an_implementation", node)

    // A `__deriving__/3` that is a function runs, and with none Elixir defines the implementation: neither is followed.
    return if (NameArity("__deriving__", 3) in implementation.macros) null else Expansion.Unported(node)
}

/**
 * The protocols [value], one write of `@derive`, names, flattened: an atom, or a `{protocol, options}` pair. `null`
 * where the value isn't known, or holds what `foreach` has no clause for.
 */
private fun protocolsOf(value: AttributeValue): List<String>? =
    (value as? AttributeValue.Known)?.let { protocolsOf(it.term) }

private fun protocolsOf(term: Term): List<String>? {
    ProgressManager.checkCanceled()

    return when (term) {
        is Term.Atom -> listOf(term.name)
        is Term.Pair -> (term.first as? Term.Atom)?.let { listOf(it.name) }
        is Term.List -> if (term.tail != null) null else flatten(term.elements.map(::protocolsOf))
        else -> null
    }
}

private fun <T> flatten(parts: List<List<T>?>): List<T>? = if (parts.any { it == null }) null else parts.flatMap { it!! }
