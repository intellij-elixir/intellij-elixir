package org.elixir_lang.expander

import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.lowering.ElixirAst
import java.util.IdentityHashMap

/**
 * [definition] with the unquote fragments the module body's variables hold, which the expander doesn't evaluate, replaced
 * by what the macro that queued it knows they hold: each `unquote(x)` whose [fragment] of `x` is a node is that node, and
 * each unquoted call name `Receiver.unquote(x)(...)` whose [name] of the call is an atom is `Receiver.atom(...)`. The
 * walk evaluates each fragment it has no value for, a splice or call name it has none for keeps the definition stuck,
 * and a [certain] one is a statement of the module body, as its `def` is where the macro runs it.
 */
internal fun filled(
    definition: Pending.Definition,
    certain: Boolean = false,
    name: (ElixirAst.Call) -> String? = { null },
    fragment: (ElixirAst) -> ElixirAst?,
): Pending.Definition {
    val fragments = definition.fragments
    val replacements = IdentityHashMap(fragments?.supplied.orEmpty())
    var stop: ElixirAst? = null

    fun collect(node: ElixirAst) {
        ProgressManager.checkCanceled()

        when {
            isCall(node, "unquote", 1) -> fragment((node as ElixirAst.Call).arguments!!.single())?.let { replacements[node] = it }
            isCall(node, "unquote_splicing", 1) -> stop = stop ?: node
            isCall(node, "quote", 1) -> {}
            isCall(node, "quote", 2) -> collect((node as ElixirAst.Call).arguments!![0])
            isUnquotedCall(node) -> {
                val call = node as ElixirAst.Call
                val dot = call.callee as ElixirAst.Call
                val atom = name(call)

                if (atom == null) {
                    stop = stop ?: node
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
        definition.head,
        definition.body,
        fragments?.let { Fragments(it.values, it.stop?.let { stop }, it.state, replacements) },
        definition.env,
        certain || definition.statement,
        definition.checksClauses,
        definition.delegatesTo,
        definition.injectedBy,
    )
}
