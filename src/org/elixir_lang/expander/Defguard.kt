package org.elixir_lang.expander

import com.intellij.openapi.progress.ProgressManager
import org.elixir_lang.expander.DefinitionTable.Kind
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFGUARD_ARGS_FROM_ONE
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFGUARD_ARGS_IN_MODULE
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFGUARD_QUOTE_OPTIONS
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFGUARD_UNIQUE_ARGS
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFGUARD_UNQUOTE_NAMES
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta

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
 * `Kernel.Utils.defguard/2`: its guard expanded as a guard that can read the variables of its arguments, then the
 * template `defguard/3` builds from the expanded guard, the macro's body: a `case` on `Macro.Env.in_guard?(__CALLER__)`
 * of two `quote`s. A guard whose expansion isn't followed stops there.
 */
internal val UTILS_DEFGUARD = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val (args, guard) = node.arguments!!
        // `extract_refs_from_args/1` gives the variables last first, and `with_vars/2` versions them in that order.
        val refs = refs(args).asReversed()
        val read = refs.withIndex().associate { (version, variable) -> variable to version }
        val guardState = ExState.empty(run.level).copy(read = read, version = read.size)
        val expansion = run.askingAst { Expander.expand(guard, guardState, env.copy(context = Env.Context.GUARD), run) }
        val expanded = ((expansion as? Expansion.Expanded)?.ast as? ExpandedAst.Built)?.node
            ?: return Summary.Output.Stopped(expansion)
        val s = Synthetic(node.meta)
        val inGuard = literalQuote(s, unquoteEveryRef(s, expanded, refs.toSet()), emptyList(), run.level)
        val outside = literalQuote(
            s,
            unquoteRefsOnce(s, expanded, refs, env.module, run),
            listOf("generated" to s.atom("true")),
            run.level,
        )
        val inGuardCall = s.remoteCall(
            kernelAlias(s, "Macro", "Env"),
            "in_guard?",
            listOf(s.variable("__CALLER__", "Elixir.Kernel.Utils")),
        )

        return Summary.Output.Built(
            s.case(inGuardCall, listOf(s.arrow(listOf(s.atom("true")), inGuard), s.arrow(listOf(s.atom("false")), outside))),
        )
    }
}

/** `extract_refs_from_args/1`: each variable in [node], in order, added to [refs]. */
private fun refs(node: ElixirAst, refs: MutableList<Variable> = mutableListOf()): List<Variable> {
    ProgressManager.checkCanceled()

    if (isVariable(node)) refs += variable(node)
    children(node).forEach { refs(it, refs) }

    return refs
}

/** `literal_quote/2`, or `literal_quote/1` before [DEFGUARD_QUOTE_OPTIONS]. */
private fun literalQuote(
    s: Synthetic,
    ast: ElixirAst,
    options: List<Pair<String, ElixirAst>>,
    level: ElixirLanguageLevel,
): ElixirAst =
    s.call(
        "quote",
        (if (DEFGUARD_QUOTE_OPTIONS.isSufficient(level)) listOf(s.keywords(options)) else emptyList()) +
            s.keywords(listOf("do" to ast)),
    )

/** `literal_unquote/1`. */
private fun literalUnquote(s: Synthetic, ast: ElixirAst): ElixirAst = s.call("unquote", listOf(ast))

/** `unquote_every_ref/2`: each variable of [refs] in [guard] unquoted. */
private fun unquoteEveryRef(s: Synthetic, guard: ElixirAst, refs: Set<Variable>): ElixirAst =
    postwalk(guard) { node -> if (isVariable(node) && variable(node) in refs) literalUnquote(s, node) else node }

/**
 * `unquote_refs_once/3`: each variable of [refs] in [guard] replaced by a fresh variable, bound once ahead of the
 * guard to the unquoted argument. An argument the guard never reads is dropped.
 */
private fun unquoteRefsOnce(s: Synthetic, guard: ElixirAst, refs: List<Variable>, module: String?, run: Run): ElixirAst {
    val used = LinkedHashMap<Variable, Pair<ElixirAst, ElixirAst>>()
    val replaced = postwalk(guard) { node ->
        val pair = if (isVariable(node)) variable(node) else null

        if (pair == null || pair !in refs) {
            node
        } else {
            used.getOrPut(pair) { uniqueArg(s, used.size, module, run) to node }.first
        }
    }
    val allUsed = refs.asReversed().mapNotNull { used[it] }

    return ElixirAst.Block(
        s.meta(),
        listOf(
            s.call(
                "=",
                listOf(s.call("{}", allUsed.map { it.first }), s.call("{}", allUsed.map { literalUnquote(s, it.second) })),
            ),
            replaced,
        ),
    )
}

/** The variable `unquote_refs_once/3` makes for the argument after [index] others, `Macro.unique_var/2`'s from 1.14.2. */
private fun uniqueArg(s: Synthetic, index: Int, module: String?, run: Run): ElixirAst {
    val name = "arg${index + if (DEFGUARD_ARGS_FROM_ONE.isSufficient(run.level)) 1 else 0}"

    return when {
        DEFGUARD_ARGS_IN_MODULE.isSufficient(run.level) ->
            s.variable(name, module ?: "nil", listOf(Meta.Key.Entry("counter", counterMetaValue(run.counters.next(module)))))
        DEFGUARD_UNIQUE_ARGS.isSufficient(run.level) ->
            s.variable(name, "Elixir", listOf(Meta.Key.Entry("counter", counterMetaValue(run.counters.next(null)))))
        else -> s.variable(name, "Elixir")
    }
}

/** `Macro.postwalk/2`: [transform] of each node after its children, a call's callee before its arguments. */
private fun postwalk(node: ElixirAst, transform: (ElixirAst) -> ElixirAst): ElixirAst {
    ProgressManager.checkCanceled()

    return transform(
        when (node) {
            is ElixirAst.Call ->
                if (node.arguments == null) {
                    node
                } else {
                    ElixirAst.Call(
                        node.meta,
                        postwalk(node.callee, transform),
                        node.arguments.map { postwalk(it, transform) },
                        node.context,
                    )
                }
            is ElixirAst.ListNode -> ElixirAst.ListNode(node.meta, node.elements.map { postwalk(it, transform) })
            is ElixirAst.Tuple -> ElixirAst.Tuple(node.meta, node.elements.map { postwalk(it, transform) })
            is ElixirAst.Block -> ElixirAst.Block(node.meta, node.expressions.map { postwalk(it, transform) })
            is ElixirAst.Alias, is ElixirAst.Literal, is ElixirAst.Placeholder, is ElixirAst.Value -> node
        },
    )
}
