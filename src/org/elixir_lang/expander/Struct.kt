package org.elixir_lang.expander

import org.elixir_lang.expander.ErrorSite.INVALID_KEY_FOR_STRUCT
import org.elixir_lang.expander.ErrorSite.UNKNOWN_KEY_FOR_STRUCT
import org.elixir_lang.language_level.ElixirLanguageFeature.STRUCT_KEYS_IN_FUNCTIONS_LEFT_TO_TYPES
import org.elixir_lang.language_level.ElixirLanguageFeature.STRUCT_KEYS_MUST_BE_ATOMS
import org.elixir_lang.language_level.ElixirLanguageFeature.STRUCT_OF_MODULE_BEING_DEFINED_NEVER_LOADED
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term

/** `elixir_map:expand_struct/5`, against the structs [Run.structs] gives. */
internal fun expandStruct(node: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val (left, right) = node.arguments!!
    if (!isMap(right)) return Expansion.Error("non_map_after_struct", node)

    val map = withoutStructKey(right as ElixirAst.Call)
    var pairs: List<Term> = emptyList()

    return expandArgs(listOf(left, map), state, env) { arg, s, e ->
        if (arg === map) {
            Expander.observed(map, s, e, run) {
                expandMapPairs(map, s, e, run).thenValue { ss, ee, value ->
                    pairs = (value as Term.List).elements
                    Expansion.Expanded(ss, ee, NODE)
                }
            }
        } else {
            Expander.expand(arg, s, e, run)
        }
    }.thenValue { s, e, values ->
        val name = (values as Term.List).elements.first()
        val match = env.context == Env.Context.MATCH

        when {
            name is Term.Atom -> {
                val keys = pairs.map { (it as? Term.Pair)?.first ?: return@thenValue Expansion.Unported(node) }

                structError(node, name.name, keys, mapUpdate(map) != null, env, run)
                    ?: traced(keys)?.let { traced ->
                        Expansion.Expanded(s, e, NODE).also { run.observer.structExpanded(node, name.name, traced) }
                    }
                    ?: Expansion.Unported(node)
            }
            match && (name == Term.Node(Term.Node.Kind.VARIABLE) || name == Term.Node(Term.Node.Kind.PIN)) ->
                Expansion.Expanded(s, e, NODE)
            match -> Expansion.Error("invalid_struct_name_in_match", node)
            else -> Expansion.Error("invalid_struct_name", node)
        }
    }
}

/** Each of [keys] as the `struct_expansion` trace is compared: an atom by its name, any other by `inspect/1`. */
private fun traced(keys: List<Term>): List<String>? =
    keys.map { key -> (key as? Term.Atom)?.name ?: inspected(key) ?: return null }

/**
 * What [module]'s struct gives the [keys] written, as their expansions, if anything stops it expanding: the error that
 * ends it, or `Unported`. Each error Elixir carries on after is reported, one per key.
 */
private fun structError(
    node: ElixirAst,
    module: String,
    keys: List<Term>,
    update: Boolean,
    env: Env,
    run: Run,
): Expansion? {
    // Up to 1.18 a second `__struct__` key is dropped silently; from 1.19 it reaches the struct's own checks.
    if (Term.Atom("__struct__") in keys) return Expansion.Unported(node)
    if (STRUCT_KEYS_MUST_BE_ATOMS.isSufficient(run.level)) {
        repeat(keys.count { it !is Term.Atom }) {
            reportOrEnd(INVALID_KEY_FOR_STRUCT, node, env, run)?.let { error -> return error }
        }
    }

    val build = !update && env.context != Env.Context.MATCH
    if (!build && env.function != null && STRUCT_KEYS_IN_FUNCTIONS_LEFT_TO_TYPES.isSufficient(run.level)) return null
    // Elixir expands the module's body before it evaluates any `defstruct` in it. A function reads the module's own
    // definitions, which are modelled where the module is compiled.
    val readsRecord = env.function != null && run.compiling[module] != null
    if (module == env.module && !readsRecord) {
        return if (env.function == null && neverLoaded(module, env, run)) {
            Expansion.Error("inaccessible_struct", node)
        } else {
            Expansion.Unported(node)
        }
    }

    val struct = when (val struct = run.structOf(module)) {
        is ModuleStruct.Present -> struct
        ModuleStruct.Unreadable -> return Expansion.Unported(node)
        ModuleStruct.Absent -> return if (loadedModuleMayAnswer(module, env, run)) {
            Expansion.Unported(node)
        } else {
            undefinedStruct(node, module, env)
        }
    }
    val names = keys.map { (it as? Term.Atom)?.name }
    val unknown = names.filter { it == null || it !in struct.fields }

    return when {
        !build -> {
            repeat(unknown.size) { reportOrEnd(UNKNOWN_KEY_FOR_STRUCT, node, env, run)?.let { error -> return error } }

            null
        }
        // `__struct__/1` raises, for an unknown key before a missing enforced one.
        unknown.isNotEmpty() -> Expansion.Error("struct_unknown_key", node)
        else -> when (val enforced = struct.enforced) {
            is Enforced.Known ->
                Expansion.Error("struct_missing_enforced_keys", node).takeUnless { names.containsAll(enforced.keys) }
            Enforced.Unknown -> Expansion.Unported(node).takeUnless { names.containsAll(struct.fields) }
        }
    }
}

/** Whether Elixir doesn't call the struct of a loaded module of [module]'s name: it is the module being defined. */
private fun neverLoaded(module: String, env: Env, run: Run): Boolean =
    module == env.module && STRUCT_OF_MODULE_BEING_DEFINED_NEVER_LOADED.isSufficient(run.level)

/**
 * Whether Elixir would call the struct of a loaded module of the same name, which the expander can't know: [module] is
 * still compiling, and so isn't loaded itself, unless it is [neverLoaded].
 */
private fun loadedModuleMayAnswer(module: String, env: Env, run: Run): Boolean =
    module in run.compiling && !neverLoaded(module, env, run)

/** `elixir_map:struct_undef/2`, for a module with no struct other than the one being defined. */
private fun undefinedStruct(node: ElixirAst, module: String, env: Env): Expansion =
    if (module in env.contextModules && env.function == null) {
        Expansion.Error("inaccessible_struct", node)
    } else {
        Expansion.Error("undefined_struct", node)
    }

/** [map] without its first `__struct__` pair, which `elixir_map:delete_struct_key/3` takes out before expansion. */
private fun withoutStructKey(map: ElixirAst.Call): ElixirAst.Call {
    val update = mapUpdate(map)

    return if (update == null) {
        withoutStructPair(map.arguments!!)?.let { ElixirAst.Call(map.meta, map.callee, it) } ?: map
    } else {
        val (updated, pairs) = update.arguments!!

        (pairs as? ElixirAst.ListNode)
            ?.let { withoutStructPair(it.elements) }
            ?.let { elements ->
                val clean = ElixirAst.ListNode(pairs.meta, elements)
                val cleanUpdate = ElixirAst.Call(update.meta, update.callee, listOf(updated, clean))

                ElixirAst.Call(map.meta, map.callee, listOf(cleanUpdate))
            }
            ?: map
    }
}

private fun withoutStructPair(pairs: List<ElixirAst>): List<ElixirAst>? {
    val index = pairs.indexOfFirst(::isStructKeyed)

    return if (index < 0) null else pairs.filterIndexed { i, _ -> i != index }
}

/**
 * Whether `lists:keytake('__struct__', 1, _)` takes [node]: any tuple whose first element is `__struct__`, so the AST
 * of a `__struct__` variable or call too.
 */
private fun isStructKeyed(node: ElixirAst): Boolean =
    when (node) {
        is ElixirAst.Tuple -> node.elements.size == 2 && isStructAtom(node.elements[0])
        is ElixirAst.Call -> isStructAtom(node.callee)
        else -> false
    }

private fun isStructAtom(node: ElixirAst): Boolean = (node as? ElixirAst.Literal.Atom)?.name == "__struct__"
