package org.elixir_lang.expander

import org.elixir_lang.expander.DefinitionTable.Kind
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFGUARD_UNQUOTE_NAMES
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst

/** `Kernel.defguard/1`: `@doc guard: true`, then the macro the guard defines. */
internal val DEFGUARD = defguard(Kind.DEFMACRO)

/** `Kernel.defguardp/1`: `@doc guard: true`, then the private macro the guard defines. */
internal val DEFGUARDP = defguard(Kind.DEFMACROP)

/**
 * `define_guard/3`, with the macro stored by `define/4` as a definition of its own: the `@doc` is expanded first, as
 * the output's block holds it before the store, and the head and the body that calls `Kernel.Utils.defguard/2` are
 * queued. The body has no clause for a guard without a `when`.
 */
private fun defguard(kind: Kind) = Summary { _, node, state, env, run ->
    val (call, guards) = extractGuards(node.arguments!!.single())
    val args = decomposeArgs(call, run.level)
    val scope = definerScopeError(node, env)

    when {
        guards.size > 1 -> Expansion.Error("defguard_two_whens", node)
        args == null || !args.all(::isVariableArgument) -> Expansion.Error("invalid_defguard", node)
        scope != null -> scope
        env.context != Env.Context.NONE -> Expansion.Unported(node)
        else -> {
            val s = Synthetic(node.meta)
            val doc = ElixirAst.Block(s.meta(), listOf(docGuard(s, run)))
            val body = guards.singleOrNull()?.let { guard ->
                val template = ElixirAst.Block(
                    s.meta(),
                    listOf(
                        s.call("require", listOf(kernelAlias(s, "Kernel", "Utils"))),
                        s.remoteCall(kernelAlias(s, "Kernel", "Utils"), "defguard", listOf(s.list(args), guard)),
                    ),
                )

                s.keywords(listOf("do" to template))
            }
            val counter = run.counters.next(env.module)

            expandQuoted(node, KERNEL, counter, doc, state, env, run).then { after, afterEnv ->
                define(kind, node, call, body, counter, after, afterEnv, run)
            }
        }
    }
}

/** `@doc guard: true`, as `Kernel` writes the attribute. */
private fun docGuard(s: Synthetic, run: Run): ElixirAst =
    Nodes(s, run.level, KERNEL, generated = false)
        .kernelAt(s.call("doc", listOf(s.keywords(listOf("guard" to s.atom("true"))))))

/**
 * `decompose_args/1`: the arguments of [call], whose name is an atom or, from [DEFGUARD_UNQUOTE_NAMES], an `unquote`, or
 * `null` where it isn't a call.
 */
private fun decomposeArgs(call: ElixirAst, level: ElixirLanguageLevel): List<ElixirAst>? {
    val callee = (call as? ElixirAst.Call)?.callee ?: return null
    val named = callee is ElixirAst.Literal.Atom ||
        DEFGUARD_UNQUOTE_NAMES.isSufficient(level) &&
        callee is ElixirAst.Call && (callee.callee as? ElixirAst.Literal.Atom)?.name == "unquote"

    return if (named) call.arguments.orEmpty() else null
}

/** `validate_variable_only_args!/2`: a variable, or one with a default. */
private fun isVariableArgument(arg: ElixirAst): Boolean =
    isVariable(arg) || isCall(arg, "\\\\", 2) && isVariable((arg as ElixirAst.Call).arguments!![0])

/**
 * `Kernel.Utils.defguard/2`: its guard expanded as a guard that can read the variables of its arguments. What it
 * builds from the expanded guard is the macro's body, read when the guard is called, which needs the expanded guard
 * the expander doesn't give; its expansion is [Expansion.Opaque].
 */
internal val UTILS_DEFGUARD = Summary { dispatch, node, _, env, run ->
    val (args, guard) = node.arguments!!
    val variables = refs(args).distinct().withIndex().associate { (version, variable) -> variable to version }
    val state = ExState.empty(run.level).copy(read = variables, version = variables.size)

    Expander.expand(guard, state, env.copy(context = Env.Context.GUARD), run).then { _, _ ->
        Expansion.Opaque(node, dispatch)
    }
}

/** `extract_refs_from_args/1`: each variable in [node], in order. */
private fun refs(node: ElixirAst): List<Variable> =
    (if (isVariable(node)) listOf(variable(node)) else emptyList()) + children(node).flatMap(::refs)
