package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.RAISE_ERROR_INFO
import org.elixir_lang.lowering.ElixirAst
import java.math.BigInteger
import java.util.Arrays

/** `Kernel.to_string/1`. */
internal val TO_STRING = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output =
        Summary.Output.Built(Synthetic(node.meta).remoteCall("Elixir.String.Chars", "to_string", node.arguments!!))
}

/** `Kernel.raise/1`: the message is expanded first, unless it is a binary. */
internal val RAISE_1 = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val written = node.arguments!!.single()
        val message = written as? ElixirAst.Literal.Binary ?: expandArgument(written, state, env, run) { stopped -> return stopped }
        val s = Synthetic(node.meta)
        val exception = when {
            isBinaryValue(message) || isBitstring(message) ->
                s.remoteCall(kernelAlias(s, "RuntimeError"), "exception", listOf(message))
            message is ElixirAst.Literal.Atom -> s.remoteCall(message.name, "exception", listOf(s.list()))
            else -> s.remoteCall(kernelAlias(s, "Kernel", "Utils"), "raise", listOf(message))
        }
        val args = if (RAISE_ERROR_INFO.isSufficient(run.level)) {
            val errorInfo = s.call("%{}", listOf(s.tuple(s.atom("module"), kernelAlias(s, "Exception"))))

            listOf(exception, s.atom("none"), s.keywords(listOf("error_info" to errorInfo)))
        } else {
            listOf(exception)
        }

        return Summary.Output.Built(s.remoteCall("erlang", "error", args))
    }
}

/** `Kernel.raise/2`. */
internal val RAISE_2 = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val (exception, attributes) = node.arguments!!
        val s = Synthetic(node.meta)

        return Summary.Output.Built(s.remoteCall("erlang", "error", listOf(s.remoteCall(exception, "exception", listOf(attributes)))))
    }
}

/**
 * `Kernel.binding/0,1`: each readable variable of the context, by name, as `{name, var}` with `var` generated, and
 * pinned in a match. The context is compared as written, so only an atom selects any.
 */
internal val BINDING = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val context = when (val argument = node.arguments.orEmpty().singleOrNull()) {
            null -> "nil"
            else -> (argument as? ElixirAst.Literal.Atom)?.name
        }
        val s = Synthetic(node.meta)
        val names = state.read.keys
            .filter { context != null && it.context == Variable.Context.Atom(context) }
            .map { it.name }
            .sortedWith(::compareCodePoints)
        val bindings = names.map { name ->
            val variable = s.variable(name, context!!, GENERATED)
            val bound = if (env.context == Env.Context.MATCH) s.call("^", listOf(variable)) else variable

            s.tuple(s.atom(name), bound)
        }

        return Summary.Output.Built(s.list(bindings))
    }
}

/** `Kernel.destructure/2`: a list on the left only; anything else matches no clause of the macro. */
internal val DESTRUCTURE = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val (left, right) = node.arguments!!

        if (left !is ElixirAst.ListNode) return Summary.Output.Raised("destructure_function_clause")

        val s = Synthetic(node.meta)
        val length = s.integer(BigInteger.valueOf(left.elements.size.toLong()))

        return Summary.Output.Built(
            s.call("=", listOf(left, s.remoteCall(kernelAlias(s, "Kernel", "Utils"), "destructure", listOf(right, length)))),
        )
    }
}

/** An alias `Kernel`'s `quote` builds: `alias: false`, so it isn't expanded through the caller's aliases. */
internal fun kernelAlias(s: Synthetic, vararg segments: String): ElixirAst.Alias =
    ElixirAst.Alias(s.meta(listOf(entry("alias", "false"))), segments.map { s.atom(it) })

/** Erlang's order of atoms, by code point. */
private fun compareCodePoints(left: String, right: String): Int =
    Arrays.compare(left.codePoints().toArray(), right.codePoints().toArray())
