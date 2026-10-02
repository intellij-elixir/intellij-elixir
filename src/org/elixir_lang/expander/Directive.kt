package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.ALIAS_AS_NIL_REJECTED
import org.elixir_lang.language_level.ElixirLanguageFeature.ALIAS_EXPANDS_ONE_STEP
import org.elixir_lang.language_level.ElixirLanguageFeature.CIRCULAR_MODULE_CHECKED_FIRST
import org.elixir_lang.language_level.ElixirLanguageFeature.DIGITS_IN_SIGIL_NAMES
import org.elixir_lang.language_level.ElixirLanguageFeature.DIRECTIVE_WARNS_AT_RUN_TIME
import org.elixir_lang.language_level.ElixirLanguageFeature.ERLANG_IMPORT_DROPS_BEHAVIOUR_INFO
import org.elixir_lang.language_level.ElixirLanguageFeature.IMPLICIT_ALIAS_NEEDS_ELIXIR_MODULE
import org.elixir_lang.language_level.ElixirLanguageFeature.IMPORT_DISCARDS_SPECIAL_FORMS
import org.elixir_lang.language_level.ElixirLanguageFeature.IMPORT_ONLY_MACROS_WITHOUT_INFO
import org.elixir_lang.language_level.ElixirLanguageFeature.IMPORT_OPTION_MISTAKES_WARN
import org.elixir_lang.language_level.ElixirLanguageFeature.INVALID_MULTI_ALIAS_BASE_RAISES
import org.elixir_lang.language_level.ElixirLanguageFeature.REQUIRE_WARNS_AT_RUN_TIME
import org.elixir_lang.language_level.ElixirLanguageFeature.SIGIL_FILTER_TOLERATES_ANY_NAME
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.elixir_lang.psi.Import
import org.elixir_lang.psi.Import.Term

/** `alias`, `require` or `import`: `elixir_expand`'s directive clauses. */
internal enum class Directive(val allowed: List<String>) {
    ALIAS(listOf("as", "warn")),
    REQUIRE(listOf("as", "warn")),
    IMPORT(listOf("only", "except", "warn"));

    val atom: String = name.lowercase()

    companion object {
        fun of(name: String): Directive? = entries.firstOrNull { it.atom == name }
    }
}

/** `expand_aliases/4` for the `__aliases__` clause. */
internal fun expandAliasesClause(node: ElixirAst.Alias, state: ExState, env: Env, run: Run): Expansion =
    expandAliases(node, state, env, run) { module, s, e -> Expansion.Expanded(s, e, Term.Atom(module)) }

/** `expand_multi_alias_call/7`. */
internal fun expandMultiAlias(call: ElixirAst.Call, state: ExState, env: Env, run: Run): Expansion {
    val directive = Directive.of((call.callee as ElixirAst.Literal.Atom).name)!!
    val arguments = call.arguments!!
    val multi = arguments.first() as ElixirAst.Call
    val base = (multi.callee as ElixirAst.Call).arguments!!.first()
    val opts = arguments.getOrNull(1)

    // `case Rest` has no clause for more arguments.
    if (arguments.size > 2) return Expansion.Unported(call)

    if (opts != null) {
        // `lists:keymember/3` crashes on options that aren't a list.
        if (opts !is ElixirAst.ListNode) return Expansion.Unported(call)
        if (opts.elements.any { keyOf(it) == "as" }) return Expansion.Error("as_in_multi_alias_call", call)
    }

    return expandWithoutAliasesReport(base, state, env, run) { baseRef, sb, eb ->
        when {
            baseRef is Term.Atom ->
                mapfold(multi.arguments!!, sb, eb) { ref, s, e ->
                    val segments = when (ref) {
                        is ElixirAst.Alias -> ref.segments.map { (it as? ElixirAst.Literal.Atom)?.name }
                        is ElixirAst.Literal.Atom -> listOf(ref.name)
                        else -> return@mapfold Expansion.Error("expected_compile_time_module", call)
                    }
                    // `elixir_aliases:concat/1` crashes on a segment that isn't an atom.
                    if (null in segments) return@mapfold Expansion.Unported(ref)

                    val module = concat(listOf(baseRef.name) + segments.filterNotNull())

                    expandDirective(directive, call, ElixirAst.Literal.Atom(ref.meta, module), opts, s, e, run)
                }
            INVALID_MULTI_ALIAS_BASE_RAISES.isSufficient(run.level) -> Expansion.Error("invalid_alias", call)
            else -> Expansion.Unported(call)
        }
    }
}

/** The `alias/1,2`, `require/1,2` and `import/1,2` clauses, with [opts] `null` for the arity-1 head. */
internal fun expandDirective(
    directive: Directive,
    call: ElixirAst.Call,
    ref: ElixirAst,
    opts: ElixirAst?,
    state: ExState,
    env: Env,
    run: Run,
): Expansion {
    when {
        env.context == Env.Context.MATCH -> return Expansion.Error("invalid_pattern_in_match", call)
        env.context == Env.Context.GUARD -> return noGuardScope(call, state)
        // `defmodule`'s and a macro's metadata.
        hasMetaKey(call.meta, "defined") || hasMetaKey(call.meta, "counter") -> return Expansion.Unported(call)
    }

    return expandWithoutAliasesReport(ref, state, env, run) { eRef, sr, er ->
        expandOpts(directive, call, opts, sr, er, run) { eOpts, st, et ->
            when {
                eRef !is Term.Atom -> Expansion.Error("expected_compile_time_module", call)
                // `should_warn/3` crashes on any other `warn:`.
                eOpts.keyfind("warn").let { it != null && it != TRUE && it != FALSE } -> Expansion.Unported(call)
                else -> {
                    val module = eRef.name
                    val level = run.level

                    val warns = warnsAtRunTime(call, eOpts, et)
                    val directiveWarns = warns && DIRECTIVE_WARNS_AT_RUN_TIME.isSufficient(level)
                    val requireWarns = warns && REQUIRE_WARNS_AT_RUN_TIME.isSufficient(level)

                    when (directive) {
                        Directive.ALIAS ->
                            alias(call, module, true, eOpts, st, et, level) { defined ->
                                directiveValue(module, defined && directiveWarns)
                            }
                        Directive.REQUIRE ->
                            ensureLoaded(call, module, et, run)
                                ?: expandRequire(call, module, eOpts, st, et, level) {
                                    directiveValue(module, requireWarns)
                                }
                        Directive.IMPORT ->
                            ensureLoaded(call, module, et, run)
                                ?: import(call, module, eOpts, st, et, run) { imported ->
                                    directiveValue(module, imported && directiveWarns)
                                }
                    }
                }
            }
        }
    }
}

/**
 * `should_warn/3` where the lexical tracker is present, and only in a module body, where a directive that warns
 * expands to the tracker's call: unless [opts] say `warn: false`, or say nothing and [call] came from a quote with a
 * `context`.
 */
private fun warnsAtRunTime(call: ElixirAst.Call, opts: Term, env: Env): Boolean =
    env.function == null &&
        when (opts.keyfind("warn")) {
            TRUE -> true
            FALSE -> false
            else -> !hasMetaKey(call.meta, "context")
        }

/** What a directive of [module] expands to: the module, or with [warns] the lexical tracker's call. */
private fun directiveValue(module: String, warns: Boolean): Term = if (warns) NODE else Term.Atom(module)

/** `expand_without_aliases_report/3`: [next] gets the term [ref] expands to, with the state and env after it. */
private fun expandWithoutAliasesReport(
    ref: ElixirAst,
    state: ExState,
    env: Env,
    run: Run,
    next: (Term, ExState, Env) -> Expansion,
): Expansion =
    if (ref is ElixirAst.Alias) {
        expandAliases(ref, state, env, run) { module, s, e -> next(Term.Atom(module), s, e) }
    } else {
        Expander.expand(ref, state, env, run).thenValue { s, e, v -> next(v, s, e) }
    }

/** `expand_aliases/4`: [next] gets the module [node] names. */
private fun expandAliases(
    node: ElixirAst.Alias,
    state: ExState,
    env: Env,
    run: Run,
    next: (String, ExState, Env) -> Expansion,
): Expansion {
    val head = node.segments.first()
    val tail = node.segments.drop(1).map { (it as? ElixirAst.Literal.Atom)?.name ?: return Expansion.Unported(node) }

    if (head is ElixirAst.Literal.Atom) {
        return aliasesModule(node, env, run.level)?.let { next(it, state, env) } ?: Expansion.Unported(node)
    }

    return Expander.expand(head, state, env, run).thenValue { s, e, value ->
        if (value is Term.Atom) {
            next(concat(listOf(value.name) + tail), s, e)
        } else {
            Expansion.Error("invalid_alias", node)
        }
    }
}

/**
 * `elixir_aliases:expand_or_concat/4` for [node], whose head is an atom: the module it names through [env]'s aliases.
 * `null` for an alias whose head isn't an atom, or that `quote` or a macro marked.
 */
internal fun aliasesModule(node: ElixirAst.Alias, env: Env, level: ElixirLanguageLevel): String? {
    if (hasMetaKey(node.meta, "alias") || hasMetaKey(node.meta, "counter")) return null

    val names = node.segments.map { (it as? ElixirAst.Literal.Atom)?.name ?: return null }
    val head = names.first()

    if (head == "Elixir") return concat(names)

    val lookup = "Elixir.$head"
    val module = lookup(lookup, env.aliases, level) ?: return null

    return when {
        module == lookup -> concat(names)
        names.size == 1 -> module
        else -> concat(listOf(module) + names.drop(1))
    }
}

/** `elixir_aliases:lookup/3`, without a counter, or null on a cycle of aliases, where Elixir never returns. */
private tailrec fun lookup(
    name: String,
    aliases: List<Env.Alias>,
    level: ElixirLanguageLevel,
    seen: Set<String> = emptySet(),
): String? {
    val module = aliases.firstOrNull { it.alias == name }?.module ?: return name

    return when {
        ALIAS_EXPANDS_ONE_STEP.isSufficient(level) -> module
        name in seen -> null
        else -> lookup(module, aliases, level, seen + name)
    }
}

/** `elixir_aliases:concat/1` over atom texts: `nil` is skipped, and an `Elixir.` prefix after the first is dropped. */
internal fun concat(names: List<String>): String {
    val first = names.firstOrNull()
    val (start, rest) = when {
        first == null || first == "nil" -> "Elixir" to names
        first.startsWith("Elixir.") || first == "Elixir" -> first to names.drop(1)
        else -> "Elixir" to names
    }

    return rest.filter { it != "nil" }.fold(start) { acc, name ->
        val partial = if (name.startsWith("Elixir.")) name.removePrefix("Elixir.") else name.removePrefix(".")

        "$acc.$partial"
    }
}

/**
 * `expand_opts/6`: expands [opts], then `validate_opts/5`; [next] gets the options' term. `alias` and `require` read
 * `as:` as written (`no_alias_opts/1`).
 */
private fun expandOpts(
    directive: Directive,
    call: ElixirAst.Call,
    opts: ElixirAst?,
    state: ExState,
    env: Env,
    run: Run,
    next: (Term, ExState, Env) -> Expansion,
): Expansion {
    if (opts == null) return next(Term.List(emptyList()), state, env)

    return Expander.expand(opts, state, env, run).thenValue { s, e, value ->
        if (hasTail(value)) return@thenValue Expansion.Unported(opts)

        val eOpts = if (directive == Directive.IMPORT) value else noAliasOpts(opts, value)

        Import.optionsError(eOpts, directive.allowed)?.let { Expansion.Error(it, call) } ?: next(eOpts, s, e)
    }
}

/** `no_alias_opts/1`: the first `as:` holding an alias names it as written, not through the aliases. */
private fun noAliasOpts(opts: ElixirAst, value: Term): Term {
    if (opts !is ElixirAst.ListNode || value !is Term.List) return value

    val index = opts.elements.indexOfFirst { keyOf(it) == "as" }.takeIf { it >= 0 } ?: return value
    val alias = (opts.elements[index] as ElixirAst.Tuple).elements[1] as? ElixirAst.Alias ?: return value
    val names = alias.segments.map { (it as? ElixirAst.Literal.Atom)?.name ?: return value }

    val asWritten = Term.Pair(Term.Atom("as"), Term.Atom(concat(names)))

    return Term.List(value.elements.toMutableList().apply { set(index, asWritten) })
}

/** `elixir_aliases:ensure_loaded/3`, outside the parallel compiler: `null` when [module] is loaded. */
private fun ensureLoaded(call: ElixirAst.Call, module: String, env: Env, run: Run): Expansion? {
    if (module == KERNEL) return null

    val checkedFirst = CIRCULAR_MODULE_CHECKED_FIRST.isSufficient(run.level)

    if (checkedFirst && env.module == module) return Expansion.Error("circular_module", call)

    return when (run.exports.of(module)) {
        is ModuleExports.Present -> null
        ModuleExports.Unreadable -> Expansion.Unported(call)
        ModuleExports.Absent ->
            Expansion.Error(
                when {
                    module !in env.contextModules -> "unloaded_module"
                    !checkedFirst && env.module == module -> "circular_module"
                    else -> "scheduled_module"
                },
                call,
            )
    }
}

/**
 * `elixir_aliases:alias/6` from 1.16, and `expand_alias/5` before it: the alias [opts]' `as:` names, or with
 * [includeByDefault] the last segment of [module]. An alias of [module] to itself removes the alias. [value] is given
 * whether a name was defined.
 */
private inline fun alias(
    call: ElixirAst.Call,
    module: String,
    includeByDefault: Boolean,
    opts: Term,
    state: ExState,
    env: Env,
    level: ElixirLanguageLevel,
    value: (defined: Boolean) -> Term,
): Expansion {
    val asNilRejected = ALIAS_AS_NIL_REJECTED.isSufficient(level)
    val new = when (val option = opts.keyfind("as")) {
        null ->
            when {
                includeByDefault -> last(module, level) ?: return Expansion.Error("invalid_alias_module", call)
                asNilRejected -> return Expansion.Expanded(state, env, value(false))
                else -> module
            }
        TRUE, FALSE -> return Expansion.Error("invalid_alias_for_as", call)
        is Term.Atom ->
            when {
                option == NIL && !asNilRejected -> module
                isSimpleAlias(option.name) -> option.name
                else -> return Expansion.Error("invalid_alias_for_as", call)
            }
        else -> return Expansion.Error("invalid_alias_for_as", call)
    }
    val aliases = if (new == module) keydelete(env.aliases, module) else keystore(env.aliases, Env.Alias(new, module))

    return Expansion.Expanded(state, env.copy(aliases = aliases), value(new != module))
}

/** `elixir_aliases:last/1`: `Elixir.` and the text after [module]'s last dot. */
private fun last(module: String, level: ElixirLanguageLevel): String? =
    if (IMPLICIT_ALIAS_NEEDS_ELIXIR_MODULE.isSufficient(level) && !isElixirAlias(module)) {
        null
    } else {
        "Elixir." + module.substringAfterLast('.')
    }

private fun isElixirAlias(name: String): Boolean =
    name.startsWith("Elixir.") && name.removePrefix("Elixir.").firstOrNull()?.let { it in 'A'..'Z' } == true

/** An alias of one segment, as `expand_as` splits it with `string:tokens/2`. */
private fun isSimpleAlias(name: String): Boolean =
    isElixirAlias(name) && name.removePrefix("Elixir.").split('.').count { it.isNotEmpty() } == 1

private fun keystore(aliases: List<Env.Alias>, alias: Env.Alias): List<Env.Alias> {
    val index = aliases.indexOfFirst { it.alias == alias.alias }

    return if (index < 0) aliases + alias else aliases.toMutableList().apply { set(index, alias) }
}

private fun keydelete(aliases: List<Env.Alias>, alias: String): List<Env.Alias> {
    val index = aliases.indexOfFirst { it.alias == alias }

    return if (index < 0) aliases else aliases.toMutableList().apply { removeAt(index) }
}

/** `expand_require/5`: [module] added to the requires, then aliased only as [opts]' `as:` says. */
private inline fun expandRequire(
    call: ElixirAst.Call,
    module: String,
    opts: Term,
    state: ExState,
    env: Env,
    level: ElixirLanguageLevel,
    value: () -> Term,
): Expansion = alias(call, module, false, opts, state, env.copy(requires = require(module, env)), level) { value() }

/** `ordsets:add_element/2`. */
private fun require(module: String, env: Env): List<String> =
    if (module in env.requires) env.requires else (env.requires + module).sorted()

/**
 * `elixir_import:import` and the `require` it implies: [module]'s entries in `E.functions` and `E.macros` as the
 * level's `elixir_import` computes them. [value] is given whether anything was imported.
 */
private inline fun import(
    call: ElixirAst.Call,
    module: String,
    opts: Term,
    state: ExState,
    env: Env,
    run: Run,
    value: (imported: Boolean) -> Term,
): Expansion {
    val level = run.level
    val exports = run.exports.of(module) as? ModuleExports.Present ?: return Expansion.Unported(call)
    val legacy = !IMPORT_OPTION_MISTAKES_WARN.isSufficient(level)
    val priorFunctions = env.functions.firstOrNull { it.module == module }
    val priorMacros = env.macros.firstOrNull { it.module == module }
    val options = Import.Filter.of(
        opts as? Term.List ?: return Expansion.Unported(call),
        level,
        Import.Imports(priorFunctions?.nameArities.orEmpty().toSet(), priorMacros?.nameArities.orEmpty().toSet()),
    )
    val filter = options.filter
    val selector = options.selector
    val internalsFirst = ERLANG_IMPORT_DROPS_BEHAVIOUR_INFO.isSufficient(level)
    val functions = if (exports.hasInfo || !internalsFirst) exports.functions else exports.functions - INTERNALS
    // Before 1.15 `remove_internals` runs on what the options leave instead.
    val removedAfter = if (internalsFirst) emptySet() else MODULE_INFO

    when {
        legacy && options.only.hasDuplicate() -> return Expansion.Error("duplicated_import", call)
        filter is Import.Filter.Invalid -> return Expansion.Error(filter.error.atom, call)
        legacy && options.except.hasDuplicate() -> return Expansion.Error("duplicated_import", call)
        // From 1.17 `filter_sigils(InfoCallback(functions))` crashes; the legs before aren't ported.
        selector == Import.Filter.Selector.SIGILS && !exports.hasInfo -> return Expansion.Unported(call)
        // `calculate_except` reads the exports, and so crashes on such a name, only without `except:` or a prior entry.
        selector == Import.Filter.Selector.SIGILS &&
            DIGITS_IN_SIGIL_NAMES.isSufficient(level) &&
            !SIGIL_FILTER_TOLERATES_ANY_NAME.isSufficient(level) &&
            listOf(priorFunctions to exports.functions, priorMacros to exports.macros).any { (prior, exported) ->
                (options.except == null || prior == null) && exported.any(::isUnclassifiedSigil)
            } -> return Expansion.Unported(call)
        legacy && options.only.orEmpty().any { it !in functions && it !in exports.macros } ->
            return Expansion.Error("invalid_import", call)
    }

    val imported = filter.imports(Import.Imports(functions.toSet(), exports.macros.toSet()))
    val exceptGiven = options.except != null
    val newFunctions = if (selector == Import.Filter.Selector.MACROS) {
        env.functions.filter { it.module != module }
    } else {
        calculateKey(module, env.functions, imported.functions, exceptGiven, removedAfter, level) {
            return Expansion.Error("special_form_conflict", call)
        }
    }
    val noMacros = !exports.hasInfo && !IMPORT_ONLY_MACROS_WITHOUT_INFO.isSufficient(level)
    val newMacros = when (selector) {
        Import.Filter.Selector.FUNCTIONS -> env.macros.filter { it.module != module }
        Import.Filter.Selector.MACROS if noMacros -> return Expansion.Error("no_macros", call)
        else ->
            calculateKey(module, env.macros, imported.macros, exceptGiven, removedAfter, level) {
                return Expansion.Error("special_form_conflict", call)
            }
    }
    val added = (newFunctions + newMacros).any { it.module == module }

    return expandRequire(call, module, opts, state, env.copy(functions = newFunctions, macros = newMacros), level) {
        value(added)
    }
}

/** An arity-2 `sigil_` name `is_sigil/1` has no clause for from 1.17 until 1.20.0-rc.5. */
private fun isUnclassifiedSigil(nameArity: NameArity): Boolean {
    val letters = nameArity.name.removePrefix("sigil_")

    return nameArity.arity == 2 &&
        letters != nameArity.name &&
        letters.isNotEmpty() &&
        !(letters.length == 1 && letters[0] in 'a'..'z') &&
        letters[0] !in 'A'..'Z'
}

/**
 * `calculate_key/6`, and before 1.17 the end of `calculate/6`: [module]'s entry of [new] less [removed] put first in
 * [old], or none when that is empty. [conflict] is called for a special form the level raises on.
 */
private inline fun calculateKey(
    module: String,
    old: List<Env.Imports>,
    new: Set<NameArity>,
    exceptGiven: Boolean,
    removed: Set<NameArity>,
    level: ElixirLanguageLevel,
    conflict: () -> Nothing,
): List<Env.Imports> {
    val others = old.filter { it.module != module }
    // `calculate_except` subtracts from an entry left empty, which the filter's prior reads as none.
    val leftEmpty = exceptGiven && old.any { it.module == module && it.nameArities.isEmpty() }
    var set = (if (leftEmpty) emptySet() else new).sortedWith(compareBy({ it.name }, { it.arity }))

    set = set - removed
    if (set.isEmpty()) return others

    if (IMPORT_DISCARDS_SPECIAL_FORMS.isSufficient(level)) {
        set = set.filterNot { specialForm(it.name, it.arity, level) }
    } else if (set.any { specialForm(it.name, it.arity, level) }) {
        conflict()
    }

    return listOf(Env.Imports(module, set)) + others
}

/** Whether [term] is, or holds, a list with a `|` tail. */
private fun hasTail(term: Term): Boolean =
    when (term) {
        is Term.List -> term.tail != null || term.elements.any(::hasTail)
        is Term.Pair -> hasTail(term.first) || hasTail(term.second)
        else -> false
    }

private fun List<NameArity>?.hasDuplicate(): Boolean = this != null && toSet().size != size

internal fun hasMetaKey(meta: Meta, name: String): Boolean = meta.keys.any { it is Meta.Key.Entry && it.name == name }

private val MODULE_INFO = setOf(NameArity("module_info", 0), NameArity("module_info", 1))
private val INTERNALS = MODULE_INFO + NameArity("behaviour_info", 1)
