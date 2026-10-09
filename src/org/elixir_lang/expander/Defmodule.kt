package org.elixir_lang.expander

import org.elixir_lang.language_level.ElixirLanguageFeature.DEFINER_REFUSED_IN_MATCH_OR_GUARD
import org.elixir_lang.language_level.ElixirLanguageFeature.DEFMODULE_ALIASES_THROUGH_REQUIRE
import org.elixir_lang.language_level.ElixirLanguageFeature.MACRO_ENV_VERSIONED_VARS
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import.Term

/**
 * What `defmodule` defines and the alias it adds where it is called.
 *
 * @property full the module it defines
 * @property old the module the alias names
 * @property alias the alias, or `null` when it adds none
 */
internal data class DefmoduleAlias(val full: String, val old: String, val alias: String?)

/** `elixir_compiler:only_defmodule/1`: whether [forms] are all `defmodule` calls with only a `do` block. */
internal fun onlyDefmodule(forms: ElixirAst): Boolean =
    when (forms) {
        is ElixirAst.Block -> forms.expressions.all(::onlyDefmodule)
        is ElixirAst.Call -> {
            val arguments = forms.arguments.orEmpty()
            val options = (arguments.getOrNull(1) as? ElixirAst.ListNode)?.elements.orEmpty()
            val pair = (options.singleOrNull() as? ElixirAst.Tuple)?.elements.orEmpty()

            (forms.callee as? ElixirAst.Literal.Atom)?.name == "defmodule" &&
                arguments.size == 2 &&
                pair.size == 2 &&
                (pair[0] as? ElixirAst.Literal.Atom)?.name == "do"
        }
        else -> false
    }

/**
 * `Kernel`'s `alias_defmodule/3` (`expand_module/3` before 1.13): what `defmodule` with [name], which expanded to
 * [expanded], defines inside the module [enclosing], or at the top when it is `null`.
 */
internal fun aliasDefmodule(name: ElixirAst, expanded: String, enclosing: String?): DefmoduleAlias {
    val segments = (name as? ElixirAst.Alias)?.segments.orEmpty()
    val head = (segments.firstOrNull() as? ElixirAst.Literal.Atom)?.name

    if (head == null || enclosing == null || (head == "Elixir" && segments.size > 1)) {
        return DefmoduleAlias(expanded, expanded, null)
    }

    val module = concat(listOf(enclosing, head))
    val tail = segments.drop(1).map { (it as ElixirAst.Literal.Atom).name }

    return DefmoduleAlias((listOf(module) + tail).joinToString("."), module, "Elixir.$head")
}

/**
 * `Kernel.defmodule/2`: the name expanded, the module recorded where it is defined through an `alias` or `require` of
 * it, and the module queued to compile, with the variables before it, once the body it is in has run. The output takes
 * the next counter of the module it is in, and the directive in it holds that counter.
 */
internal val DEFMODULE = Summary { _, node, state, env, run ->
    val (nameNode, options) = node.arguments!!
    val doBlock = (options as? ElixirAst.ListNode)?.elements?.singleOrNull()?.takeIf { keyOf(it) == "do" }
    val body = (doBlock as? ElixirAst.Tuple)?.elements?.get(1)
    val level = run.level

    when {
        // No clause of `defmodule/2` takes it.
        body == null -> Expansion.Error("reserved_word", node)
        env.context != Env.Context.NONE && !DEFINER_REFUSED_IN_MATCH_OR_GUARD.isSufficient(level) ->
            Expansion.Unported(node)
        env.context == Env.Context.MATCH -> Expansion.Error("definer_in_match", node)
        env.context == Env.Context.GUARD -> Expansion.Error("definer_in_guard", node)
        else -> {
            // Expanding the name takes the value the macro that made it supplies.
            val supplied = run.isSupplied(nameNode)

            Expander.expand(nameNode, state, env, run).thenValue { _, _, name ->
                // `elixir_dispatch:expand_quoted/7`, once the macro has run.
                val counter = run.counters.next(env.module)

                when (name) {
                    // A name the macro supplies is a variable, an atom only once the body runs: `Macro.expand/2` leaves
                    // it as it is, so it is not aliased.
                    is Term.Atom -> if (supplied) {
                        queueModule(node, name.name, true, body, state, env, run)
                    } else {
                        val defined = aliasDefmodule(nameNode, name.name, env.module)
                        val directive = linifyWithContextCounter(
                            lineOf(node.meta),
                            KERNEL,
                            counter,
                            definingDirective(nameNode, defined, env, level),
                        )

                        Expander.expand(directive, state, env, run).then { s, e ->
                            queueModule(node, defined.full, true, body, s, e, run)
                        }
                    }
                    // Only an atom is aliased; any other name raises once the module is compiled.
                    else -> inspected(name)?.let { queueModule(node, it, false, body, state, env, run) }
                        ?: Expansion.Unported(nameNode)
                }
            }
        }
    }
}

/** The module [name] queued, with the variables [state] can read, unless [env] is a function's. */
private fun queueModule(
    node: ElixirAst.Call,
    name: String,
    isAtom: Boolean,
    body: ElixirAst,
    state: ExState,
    env: Env,
    run: Run,
): Expansion {
    val moduleState = moduleState(state, run.level)

    return when {
        // The body is escaped, and runs only when the function does.
        env.function != null -> Expansion.Unported(body)
        moduleState == null -> Expansion.Unported(node)
        else -> {
            run.pending += Pending.Module(node, name, isAtom, body, env.copy(module = name), moduleState)

            Expansion.Expanded(state, env, NODE)
        }
    }
}

/** `inspect/1` of a name that isn't an atom, for an integer or a binary of printable ASCII, the only ones ported. */
internal fun inspected(name: Term): String? =
    when (name) {
        is Term.Integer -> name.value.toString()
        // Latin-1 maps each byte to one char, so any byte past `~` fails the check.
        is Term.Binary ->
            name.bytes
                ?.let { String(it, Charsets.ISO_8859_1) }
                ?.takeIf { text -> text.all { it in ' '..'~' && it !in "\"\\#" } }
                ?.let { "\"$it\"" }
        else -> null
    }

/**
 * The directive `defmodule` expands where it is called, with `defined:` meta: from
 * [DEFMODULE_ALIASES_THROUGH_REQUIRE] a `require` of the module, `as:` the alias for a nested name; before it, an
 * `alias` of it `as:` the alias, or `nil` for a root name.
 */
private fun definingDirective(
    nameNode: ElixirAst,
    defined: DefmoduleAlias,
    env: Env,
    level: ElixirLanguageLevel,
): ElixirAst.Call {
    val throughRequire = DEFMODULE_ALIASES_THROUGH_REQUIRE.isSufficient(level)
    val entries = listOfNotNull(
        Meta.Key.Entry("defined", Meta.Value.Atom(defined.full)),
        if (throughRequire) null else Meta.Key.Entry("context", Meta.Value.Atom(env.module ?: "nil")),
    )
    val source = nameNode.meta
    val meta = Meta(source.origin, source.start, source.end, entries + source.keys)
    val atom = { text: String -> ElixirAst.Literal.Atom(meta, text) }
    val pair = { key: String, value: String -> ElixirAst.Tuple(meta, listOf(atom(key), atom(value))) }
    val opts =
        if (throughRequire && defined.alias == null) {
            emptyList()
        } else {
            listOf(pair("as", defined.alias ?: "nil"), pair("warn", "false"))
        }

    return ElixirAst.Call(
        meta,
        atom(if (throughRequire) "require" else "alias"),
        listOf(atom(defined.old), ElixirAst.ListNode(meta, opts)),
    )
}

/**
 * The state a module body starts from: from [MACRO_ENV_VERSIONED_VARS] the variables [state] can read, renumbered from
 * 0 in term order, as `maps:to_list/1` lists a map of at most 32 keys; before it, those variables at their versions.
 * `null` for a map of more, which lists in hash order.
 */
private fun moduleState(state: ExState, level: ElixirLanguageLevel): ExState? {
    val empty = ExState.empty(level)

    if (!MACRO_ENV_VERSIONED_VARS.isSufficient(level)) return empty.copy(read = state.read, version = state.version)

    if (state.read.size > FLATMAP_KEYS) return null

    val sorted = state.read.keys.sortedWith(VARIABLE_ORDER)

    return empty.copy(read = sorted.withIndex().associate { (i, variable) -> variable to i }, version = sorted.size)
}

/** The most keys an Erlang map lists in term order. */
private const val FLATMAP_KEYS = 32
