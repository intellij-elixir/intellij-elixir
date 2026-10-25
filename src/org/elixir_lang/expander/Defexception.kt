package org.elixir_lang.expander

import org.elixir_lang.lowering.ElixirAst

/**
 * `Kernel.defexception/1`: its output is expanded where the call is. `defstruct` in it is followed, its fields read
 * from the output's own `fields` when the module body runs to it. The `message/1` and binary `exception/1` it defines
 * when the struct has a `message` field are what the output queued from the branch of an `if`, which the walk keeps or
 * drops once the struct is known.
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
    val expansion = expandQuoted(node, KERNEL, output.output(), state, env, run, ports = true)

    if (expansion is Expansion.Expanded && compiling.isStatement(node)) {
        run.replacePending(from, withMessageBranch(node, run.pending.subList(from, run.pending.size).toList()))
    }

    return expansion
}

/**
 * [queued], what the output queued, with the `if`'s statement, whose branch holds the entries, replaced by an effect
 * after `defstruct`'s. It gives them as statements once the struct has a `message` field, and none once it hasn't.
 * Where the struct isn't known, they stay as they were: they may or may not run.
 */
private fun withMessageBranch(node: ElixirAst.Call, queued: List<Pending>): List<Pending> {
    val index = queued.indexOfFirst { it is Pending.Statement && it.inner.isNotEmpty() }

    if (index < 0) return queued

    val branch = queued[index].inner
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
        apply = { compiling, _, _ ->
            message = (compiling.struct as? ModuleStruct.Present)?.let { "message" in it.fields }

            null
        },
    )
    return queued.toMutableList().also { it[index] = decision }
}
