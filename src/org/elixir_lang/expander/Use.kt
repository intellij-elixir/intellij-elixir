package org.elixir_lang.expander

import org.elixir_lang.lowering.AtomName
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.fitsAnAtom

/**
 * `Kernel.use/1,2`: each module, expanded as `Macro.expand/2` expands it, is required and has its `__using__/1`
 * called. `Foo.{A, B}` is the modules `Foo.A` and `Foo.B`. The `__using__` call is the macro's output; its own
 * expansion has no summary.
 */
internal val USE = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val s = Synthetic(node.meta)
        val arguments = node.arguments!!
        val module = arguments[0]
        val opts = arguments.getOrNull(1) ?: s.list()
        val modules = if (isMultiAlias(module)) {
            val call = module as ElixirAst.Call
            val base = expandArgument(dotArguments(call.callee)!![0], state, env, run) { return it }

            call.arguments!!.map { ref ->
                when (ref) {
                    is ElixirAst.Alias -> {
                        val segments = ref.segments.map { (it as? ElixirAst.Literal.Atom)?.name }

                        if (null in segments) return Summary.Output.Unported(ref)

                        concatenated(base, segments.filterNotNull()) ?: return Summary.Output.Unported(base)
                    }
                    is ElixirAst.Literal.Atom -> concatenated(base, listOf(ref.name)) ?: return Summary.Output.Unported(base)
                    else -> null
                }
            }
        } else {
            listOf((expandArgument(module, state, env, run) { return it } as? ElixirAst.Literal.Atom)?.name)
        }

        if (null in modules) return Summary.Output.Raised("use_invalid_arguments")

        val calls = modules.filterNotNull().map { name ->
            ElixirAst.Block(
                s.meta(),
                listOf(
                    s.call("require", listOf(s.atom(name)), listOf(entry("context", KERNEL))),
                    s.remoteCall(s.atom(name), "__using__", listOf(opts)),
                ),
            )
        }

        return Summary.Output.Built(ElixirAst.Block(s.meta(), calls))
    }
}

/**
 * `Module.concat([base | segments])`, or `null` where [base] is neither an atom nor a valid UTF-8 binary, which raises,
 * or where the name is too long for an atom, which raises `system_limit`.
 */
private fun concatenated(base: ElixirAst, segments: List<String>): String? {
    val first = when (base) {
        is ElixirAst.Literal.Atom -> base.name
        is ElixirAst.Literal.Binary -> AtomName.text(base.bytes)
        else -> null
    }

    return first
        ?.let { concat(listOf(it) + segments, firstIsBinary = base is ElixirAst.Literal.Binary) }
        ?.takeIf(::fitsAnAtom)
}
