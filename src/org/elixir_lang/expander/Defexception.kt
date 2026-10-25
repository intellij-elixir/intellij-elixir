package org.elixir_lang.expander

/**
 * `Kernel.defexception/1`: its output is expanded where the call is. `defstruct` in it is followed, its fields read
 * from the output's own `fields` when the module body runs to it, and the struct it gives is what the output's
 * `if Map.has_key?(struct, :message)` reads: the walk runs the `message/1` and binary `exception/1` it defines where
 * Elixir does.
 */
internal val DEFEXCEPTION = Summary { _, node, state, env, run ->
    if (moduleBody(env, run) == null) {
        Expansion.Unported(node)
    } else {
        val output = ExceptionOutput(Synthetic(node.meta), run.level, node.arguments!!.single())

        expandQuoted(node, KERNEL, output.output(), state, env, run, ports = true)
    }
}
