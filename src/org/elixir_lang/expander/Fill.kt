package org.elixir_lang.expander

import org.elixir_lang.lowering.ElixirAst
import java.util.IdentityHashMap

/** [definition] with each `unquote` of a name in [fragments] replaced by that name's AST. */
internal fun filled(definition: Pending.Definition, fragments: Map<String, ElixirAst>): Pending.Definition =
    filled(definition) { argument ->
        (argument as? ElixirAst.Call)?.takeIf { isVariable(it) }?.let { fragments[(it.callee as ElixirAst.Literal.Atom).name] }
    }

/**
 * [definition] with the unquote fragments the module body's variables hold, which the expander doesn't evaluate, replaced
 * by what the macro that queued it knows they hold: each `unquote(x)` whose [fragment] of `x` is a node is that node, and
 * each unquoted call name `Receiver.unquote(x)(...)` whose [name] of the call is an atom is `Receiver.atom(...)`. A
 * fragment it has no value for keeps the definition stuck, and a [certain] one is a statement of the module body, as its
 * `def` is where the macro runs it.
 */
internal fun filled(
    definition: Pending.Definition,
    certain: Boolean = false,
    name: (ElixirAst.Call) -> String? = { null },
    fragment: (ElixirAst) -> ElixirAst?,
): Pending.Definition {
    val replacements = IdentityHashMap<ElixirAst, ElixirAst>()
    var remaining = false

    fun collect(node: ElixirAst) {
        when {
            isCall(node, "unquote", 1) -> {
                val replacement = fragment((node as ElixirAst.Call).arguments!!.single())

                if (replacement == null) remaining = true else replacements[node] = replacement
            }
            isCall(node, "unquote_splicing", 1) -> remaining = true
            isCall(node, "quote", 1) -> {}
            isCall(node, "quote", 2) -> collect((node as ElixirAst.Call).arguments!![0])
            isUnquotedCall(node) -> {
                val call = node as ElixirAst.Call
                val dot = call.callee as ElixirAst.Call
                val atom = name(call)

                if (atom == null) {
                    remaining = true
                } else {
                    replacements[call] = ElixirAst.Call(
                        dot.meta,
                        dot.callee,
                        listOf(dot.arguments!![0], ElixirAst.Literal.Atom(dot.meta, atom)),
                    )
                }
            }
            else -> children(node).forEach(::collect)
        }
    }

    collect(definition.head)
    definition.body?.let(::collect)

    return Pending.Definition(
        definition.kind,
        definition.node,
        substitute(definition.head, replacements),
        definition.body?.let { substitute(it, replacements) },
        definition.unnamedAt,
        stop = definition.stop.takeIf { remaining },
        definition.env,
        certain && definition.unnamedAt == null || definition.ordered,
        certain || definition.statement,
        definition.checksClauses,
    )
}
