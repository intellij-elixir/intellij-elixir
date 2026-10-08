package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.expander.DefinitionTable.Kind
import org.elixir_lang.language_level.ElixirLanguageFeature.SUPER_TRACES_LOCAL_FUNCTION
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term
import java.math.BigInteger

/**
 * `elixir_overridable`'s record of a definition made overridable.
 *
 * @property count how many times it was made overridable, which names the hidden definition
 * @property entry the definition taken out of the table, the latest one
 * @property calls the local calls of [entry]'s body, taken out with it and put back when it is stored
 * @property stored whether `super` or the end of the body stored it
 */
internal class Overridable(
    val count: Int,
    val entry: DefinitionTable.Entry,
    val calls: List<LocalCall<ElixirAst>>,
) {
    var stored = false
}

internal enum class Overriding { RECORDED, NOT_DEFINED, BAD_KIND }

/**
 * `Module.make_overridable/2` of [nameArity]: its entry taken out of the table and recorded, if it is there, with its
 * body's local calls.
 */
internal fun makeOverridable(compiling: Compiling, nameArity: NameArity): Overriding {
    val entry = compiling.table.remove(nameArity) ?: return Overriding.NOT_DEFINED
    val previous = compiling.overridable[nameArity]

    if (previous != null && previous.entry.kind.macro != entry.kind.macro) return Overriding.BAD_KIND

    val (hidden, calls) = compiling.calls.remove(nameArity).orEmpty().partition { it in compiling.hiddenCalls }

    if (hidden.isNotEmpty()) compiling.calls[nameArity] = hidden.toMutableList()

    compiling.overridable[nameArity] = Overridable((previous?.count ?: 0) + 1, entry, calls)

    return Overriding.RECORDED
}

/** `elixir_overridable:store_not_overridden/1`: each overridable definition with no new one is stored back. */
internal fun storeNotOverridden(compiling: Compiling): Expansion.Error? {
    for ((nameArity, overridable) in compiling.overridable) {
        val defined = compiling.table[nameArity]

        when {
            defined == null -> storeOverridable(compiling, nameArity, overridable, hidden = false)
            defined.kind.macro != overridable.entry.kind.macro -> return Expansion.Error("bad_kind", defined.at)
        }
    }

    return null
}

/** What [storeOverridable] stores a definition as: its [kind] and [name]. */
internal class Stored(val kind: Kind, val name: String)

/**
 * `elixir_overridable:store/5`: [overridable]'s definition stored once, as it was, or for `super` as the private
 * `"<name> (overridable <count>)"`. Its default arities were never taken, so they aren't stored again.
 */
internal fun storeOverridable(
    compiling: Compiling,
    nameArity: NameArity,
    overridable: Overridable,
    hidden: Boolean,
): Stored {
    val entry = overridable.entry
    val kind = when {
        !hidden -> entry.kind
        entry.kind.macro -> Kind.DEFMACROP
        else -> Kind.DEFP
    }
    val name = if (hidden) "${nameArity.name} (overridable ${overridable.count})" else nameArity.name

    if (!overridable.stored) {
        overridable.stored = true

        compiling.table.restore(name, nameArity.arity, kind, entry.at, entry.clauses, entry.defaults, entry.ordered)
        // Filed under the original name, as `elixir_locals:reattach` does, whichever name the definition is stored as.
        compiling.calls.getOrPut(nameArity) { mutableListOf() } += overridable.calls

        if (hidden && compiling.checksHiddenBodies) compiling.hiddenCalls += overridable.calls
    }

    return Stored(kind, name)
}

/**
 * `Module.make_overridable/2` of the module whose body is running, given [definitions], the value of its second
 * argument, at [at]: the error the body raises there, if it does, or `null`. A definition list that isn't known, or an
 * element that isn't, stops the module there; the elements before it were taken. A module is a behaviour, whose
 * callbacks [exports] gives.
 */
internal fun makeAllOverridable(compiling: Compiling, definitions: Term, at: ElixirAst, exports: Exports): Expansion? {
    if (definitions is Term.Atom) return makeBehaviourOverridable(compiling, definitions.name, at, exports)

    val elements = (definitions as? Term.List)?.takeIf { it.tail == null }?.elements ?: return Expansion.Unported(at)

    for (element in elements) {
        val pair = element as? Term.Pair

        when {
            element is Term.Node || element == Term.Unexpanded -> return Expansion.Unported(at)
            pair == null -> return Expansion.Error("overridable_bad_element", at)
            pair.first is Term.Node || pair.first == Term.Unexpanded -> return Expansion.Unported(at)
            pair.second is Term.Node || pair.second == Term.Unexpanded -> return Expansion.Unported(at)
        }

        val name = (pair.first as? Term.Atom)?.name
        val arity = (pair.second as? Term.Integer)?.value?.takeIf { it in ARITIES }?.toInt()

        if (name == null || arity == null) return Expansion.Error("overridable_bad_element", at)

        take(compiling, NameArity(name, arity), at)?.let { return it }
    }

    return null
}

/** [makeOverridable] of [nameArity] as the call at [at] raises: the error, if it does, or `null`. */
private fun take(compiling: Compiling, nameArity: NameArity, at: ElixirAst): Expansion.Error? {
    val definedAt = compiling.table[nameArity]?.at

    return when (makeOverridable(compiling, nameArity)) {
        Overriding.RECORDED -> null
        Overriding.NOT_DEFINED -> Expansion.Error("overridable_not_defined", at)
        Overriding.BAD_KIND -> Expansion.Error("bad_kind", definedAt!!)
    }
}

private val ARITIES = BigInteger.ZERO..BigInteger.valueOf(255)

/**
 * `Module.make_overridable/2` of a [behaviour] (`check_module_for_overridable`): the module's definitions that are its
 * callbacks, once the behaviour is loaded, defines callbacks, and is one of the module's `@behaviour`s.
 */
private fun makeBehaviourOverridable(compiling: Compiling, behaviour: String, at: ElixirAst, exports: Exports): Expansion? {
    val loaded = when (val found = exports.of(behaviour)) {
        ModuleExports.Absent -> return Expansion.Error("overridable_undefined_behaviour", at)
        ModuleExports.Unreadable -> return Expansion.Unported(at)
        is ModuleExports.Present -> found
    }
    val callbacks = when (val info = loaded.behaviour) {
        ModuleExports.Behaviour.None -> return Expansion.Error("overridable_not_a_behaviour", at)
        ModuleExports.Behaviour.Unreadable -> return Expansion.Unported(at)
        is ModuleExports.Behaviour.Callbacks -> info.callbacks.map(::macroCallbackNormalised).toSet()
    }
    val declared = compiling.attributes.values("behaviour")

    when {
        declared.any { (it as? AttributeValue.Known)?.term == Term.Atom(behaviour) } -> {}
        AttributeValue.Unknown in declared -> return Expansion.Unported(at)
        else -> return Expansion.Error("overridable_missing_behaviour", at)
    }

    for (nameArity in compiling.table.entries.keys.filter { it in callbacks }) {
        take(compiling, nameArity, at)?.let { return it }
    }

    return null
}

/** `Module.Behaviour.callbacks/1`'s `normalize_macro_or_function_callback/1`: a `MACRO-` callback by its macro. */
private fun macroCallbackNormalised(callback: NameArity): NameArity =
    if (callback.name.startsWith("MACRO-")) NameArity(callback.name.removePrefix("MACRO-"), callback.arity - 1) else callback

/** `Kernel.defoverridable/1`: `Module.make_overridable(__MODULE__, definitions)`. */
internal val DEFOVERRIDABLE = object : Summary.Rewrite() {
    override fun output(dispatch: Dispatch, node: ElixirAst.Call, state: ExState, env: Env, run: Run): Summary.Output {
        val s = Synthetic(node.meta)
        val module = s.variable("__MODULE__", KERNEL)

        return Summary.Output.Built(
            s.remoteCall(kernelAlias(s, "Module"), "make_overridable", listOf(module, node.arguments!!.single())),
        )
    }
}

/** What `resolve_super/3` gives. */
private sealed interface Resolved {
    /** The definition `super` stores, as [stored]. */
    class Found(val stored: Stored) : Resolved

    class Failed(val expansion: Expansion) : Resolved
}

/**
 * `elixir_expand:resolve_super/3` for [node], a `super` or `&super` of [arity] arguments: the hidden definition
 * `elixir_overridable:super/4` stores for the function being defined.
 */
private fun resolveSuper(node: ElixirAst, arity: Int, env: Env, run: Run): Resolved {
    val module = env.module
    val function = env.function

    if (module == null || function == null) return Resolved.Failed(Expansion.Error("invalid_expr_in_scope", node))

    if (function.arity != arity) return Resolved.Failed(Expansion.Error("wrong_number_of_args_for_super", node))

    val compiling = run.compiling[module] ?: return Resolved.Failed(Expansion.Unported(node))
    val overridable = compiling.overridable[function] ?: return Resolved.Failed(Expansion.Error("no_super", node))

    return Resolved.Found(storeOverridable(compiling, function, overridable, hidden = true))
}

/** `elixir_expand:expand/3`'s `super`: [node] resolved, traced as a local call, and its arguments expanded. */
internal fun expandSuper(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val args = node.arguments!!

    return when (val resolved = resolveSuper(node, args.size, env, run)) {
        is Resolved.Failed -> resolved.expansion
        is Resolved.Found -> {
            if (SUPER_TRACES_LOCAL_FUNCTION.isSufficient(run.level)) {
                run.observer.dispatched(
                    node,
                    Dispatch(Dispatch.Kind.LOCAL_FUNCTION, env.module!!, resolved.stored.name, args.size),
                )
            }

            expandArgs(args, state, env, run).thenValue { s, e, _ -> Expansion.Expanded(s, e, NODE) }
        }
    }
}

/** The two forms of a capture of `super`. */
internal enum class SuperCapture { CALL, ARITY }

/**
 * `elixir_expand:expand/3`'s `&super(args)` and `&super/arity`: [node], a `&` of [arity] arguments in the [form] the
 * clause matched, as a capture of the function `super` stores, or as written where that is a macro.
 */
internal fun expandCaptureSuper(
    node: ElixirAst.Call,
    form: SuperCapture,
    arity: Int,
    state: ExState,
    env: Env,
    run: Run,
): Expansion =
    when (val resolved = resolveSuper(node, arity, env, run)) {
        is Resolved.Failed -> resolved.expansion
        is Resolved.Found -> when {
            resolved.stored.kind.macro -> expandCapture(node, state, env, run)
            // Before it traces, `&super/arity` of a function is returned renamed and isn't expanded.
            form == SuperCapture.ARITY && !SUPER_TRACES_LOCAL_FUNCTION.isSufficient(run.level) ->
                Expansion.Expanded(state, env, NODE)
            else -> expandCapture(named(node, form, resolved.stored.name), state, env, run)
        }
    }

/** [capture], a `&` of `super(args)` or `super/arity` as [form] says, with the function called [name]. */
private fun named(capture: ElixirAst.Call, form: SuperCapture, name: String): ElixirAst.Call {
    val call = capture.arguments!!.single() as ElixirAst.Call
    val renamed = when (form) {
        SuperCapture.ARITY -> {
            val (function, arity) = call.arguments!!
            val variable = function as ElixirAst.Call

            ElixirAst.Call(
                call.meta,
                call.callee,
                listOf(ElixirAst.Call(variable.meta, ElixirAst.Literal.Atom(variable.callee.meta, name), null, variable.context), arity),
            )
        }
        SuperCapture.CALL -> ElixirAst.Call(call.meta, ElixirAst.Literal.Atom(call.callee.meta, name), call.arguments, call.context)
    }

    return ElixirAst.Call(capture.meta, capture.callee, listOf(renamed), capture.context)
}
