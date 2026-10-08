package org.elixir_lang.expander

import org.elixir_lang.lowering.ElixirAst

/**
 * `Kernel.defexception/1`: its output is expanded where the call is. `defstruct` in it is followed, with the fields
 * bound to the output's own `fields`. The `message/1` and binary `exception/1` it defines when the struct has a
 * `message` field are what the output queued from the branch of an `if`, which the walk keeps or drops once the struct
 * is known.
 */
internal val DEFEXCEPTION = Summary { _, node, state, env, run ->
    val compiling = moduleBody(env, run)

    if (compiling == null) {
        Expansion.Unported(node)
    } else {
        defexception(node, node.arguments!!.single(), compiling, state, env, run)
    }
}

private fun defexception(
    node: ElixirAst.Call,
    fields: ElixirAst,
    compiling: Compiling,
    state: ExState,
    env: Env,
    run: Run,
): Expansion {
    val output = ExceptionOutput(Synthetic(node.meta), run.level, fields)
    val from = run.pending.size
    val outer = compiling.bound

    compiling.bound = mapOf("fields" to fields)

    val expansion = try {
        expandQuoted(node, KERNEL, output.output(), state, env, run)
    } finally {
        compiling.bound = outer
    }

    if (expansion is Expansion.Expanded && compiling.isStatement(node)) {
        run.replacePending(from, withMessageBranch(node, run.pending.subList(from, run.pending.size).toList()))
    }

    return expansion
}

/**
 * [queued], what the output queued, with the entries of the `if` branch, which are the ones that aren't statements,
 * left to an effect after `defstruct`'s. It gives them as statements once the struct has a `message` field, and none
 * once it hasn't. Where the struct isn't known, they stay as they were: they may or may not run.
 */
private fun withMessageBranch(node: ElixirAst.Call, queued: List<Pending>): List<Pending> {
    val branch = queued.filterNot { it.isStatement() }

    if (branch.isEmpty()) return queued

    var message: Boolean? = null
    val decision = Pending.Effect(
        node,
        ordered = true,
        queued = {
            when (message) {
                true -> branch.map { it.asStatement() }
                false -> emptyList()
                null -> branch
            }
        },
        apply = { compiling ->
            message = (compiling.struct as? ModuleStruct.Present)?.let { "message" in it.fields }

            null
        },
    )
    val first = queued.indexOfFirst { !it.isStatement() }

    return buildList {
        addAll(queued.take(first))
        add(decision)
        addAll(queued.drop(first).filter { it.isStatement() })
    }
}
