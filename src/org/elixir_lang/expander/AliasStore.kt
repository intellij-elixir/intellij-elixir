package org.elixir_lang.expander

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parents
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.Module
import org.elixir_lang.language_level.ElixirLanguageFeature
import org.elixir_lang.language_level.ElixirLanguageFeature.DBG_MACRO
import org.elixir_lang.language_level.ElixirLanguageFeature.IS_NON_STRUCT_MAP_GUARD
import org.elixir_lang.language_level.ElixirLanguageFeature.NIL_SAFE_GET_IN
import org.elixir_lang.language_level.ElixirLanguageFeature.NULLARY_RANGE
import org.elixir_lang.language_level.ElixirLanguageFeature.STEP_OPERATOR
import org.elixir_lang.language_level.ElixirLanguageFeature.TAP_AND_THEN_MACROS
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElementLowering
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Alias
import org.elixir_lang.psi.ElixirAnonymousFunction
import org.elixir_lang.psi.ElixirDoBlock
import org.elixir_lang.psi.ElixirStabBody
import org.elixir_lang.psi.ElixirStabOperation
import org.elixir_lang.psi.Module as DefModule
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.call.name.Function.IMPORT
import org.elixir_lang.psi.call.name.Function.REQUIRE
import org.elixir_lang.psi.call.name.Function.USE
import org.elixir_lang.psi.QuotableKeywordPair
import org.elixir_lang.psi.QuoteMacro
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.blockKeyword
import org.elixir_lang.psi.impl.call.keywordArgument
import org.elixir_lang.psi.impl.siblingExpressions
import org.elixir_lang.psi.qualification.Unqualified

/** What a call does to the aliases of the scope around it. */
enum class AliasStore {
    /**
     * The call stores an alias, as an `alias` or a `require` with `as:` does, or may: its module is read through the
     * aliases before it, or Elixir raises on it.
     */
    STORES,

    /**
     * The call removes the alias keyed by its module: an `alias` of a module to its own name, or before 1.16 a `require`
     * without `as:` or an `import`, where `expand_alias/5` stores the module under its own name.
     */
    REMOVES,

    /** The call stores the alias of its name's first segment: a `defmodule` or a `defprotocol`. */
    DEFINES,

    /** The call may store an alias, as a `use` may: what it stores is not read. */
    MAY_STORE,

    /** The call stores no alias in the scope around it. */
    NONE
}

/**
 * What [call], a statement in a scope, does to the aliases of that scope, as Elixir reads it from what the call resolves to
 * at [level]. It reads the statements written before [call] in the scopes that hold it, and no expander state.
 *
 * - An `alias`, `require` or `import` does what [directiveStore] says.
 * - A call to a macro of `Kernel.SpecialForms`, or of a module [Env.defaultRequires] holds, at an arity it is defined at
 *   does what [macroStore] says, a call with no qualifier reaching those of `Kernel.SpecialForms` and `Kernel`.
 * - Of those that store none or define, a `Kernel` macro called with no qualifier may store one after an
 *   `import Kernel` with `only:` or `except:` written before it in scope, which may bring another module's macro of
 *   its name in its place, and one that stores none also after a `use`. An `import` of another module alone doesn't, as
 *   the call would be ambiguous, and no `import` brings a special form.
 * - A call with no qualifier to anything else, a macro's name at an arity it isn't defined at included, stores none,
 *   unless a `use` or an `import` is written before it in scope, which may bring a macro of that name; then it may
 *   store one.
 * - A call to any other module is a function call and stores none, unless a `require`, `import` or `use` written before
 *   it names that module, when it may store one.
 * - A call these say stores none may store one where a call in its arguments that Elixir expands where it is written,
 *   as [argumentsStore] reads them, reaches a macro that stores one.
 */
@RequiresReadLock
fun aliasStore(call: Call, level: ElixirLanguageLevel): AliasStore =
    calleeStore(call, level).let { store ->
        if (store == AliasStore.NONE && argumentsStore(call, level)) AliasStore.MAY_STORE else store
    }

/** What [call] does to the aliases of its scope, read from what it calls alone. */
@RequiresReadLock
private fun calleeStore(call: Call, level: ElixirLanguageLevel): AliasStore {
    val modules = macroModules(call, level)

    if (call is Qualified && modules.isEmpty()) {
        return if (isNamedBefore(call, level)) AliasStore.MAY_STORE else AliasStore.NONE
    }

    val (module, store) = reached(call, modules, level) ?: return unlisted(call, level)
    val byMacro = macroAliasStore(call, store, level) ?: return unlisted(call, level)

    return if (
        byMacro != AliasStore.MAY_STORE &&
        call is Unqualified &&
        module == KERNEL &&
        kernelShadowedBefore(call, level, byUse = byMacro != AliasStore.DEFINES)
    ) {
        AliasStore.MAY_STORE
    } else {
        byMacro
    }
}

/**
 * What [call], which reaches a macro [macroStore] says does [store], does by that alone: `null` for a `defmodule` or
 * `defprotocol` that isn't a defining clause.
 */
@RequiresReadLock
private fun macroAliasStore(call: Call, store: MacroStore, level: ElixirLanguageLevel): AliasStore? =
    when (store) {
        MacroStore.STORES -> directiveStore(call, level)
        MacroStore.DEFINES -> if (isDefinition(call)) AliasStore.DEFINES else null
        MacroStore.MAY_STORE -> AliasStore.MAY_STORE
        MacroStore.NONE -> AliasStore.NONE
    }

/**
 * Whether a call in [call]'s arguments, or in theirs, that Elixir expands where [call] is written reaches a macro that
 * stores an alias, as [macroAliasStore] reads it: none in a `quote`, nor in what [exposedCalls] leaves out. A macro an
 * `import` or `use` may bring isn't read there.
 */
@RequiresReadLock
private fun argumentsStore(call: Call, level: ElixirLanguageLevel): Boolean =
    !QuoteMacro.`is`(call) &&
        exposedCalls(call).any { argument ->
            when (reached(argument, level)?.let { (_, store) -> macroAliasStore(argument, store, level) }) {
                null, AliasStore.NONE -> argumentsStore(argument, level)
                else -> true
            }
        }

/**
 * The outermost calls under [element] that Elixir expands where [element] is written: none in a `do` block, a block
 * keyword such as `do:`, a clause or an `fn`, which each run in a scope of their own.
 */
private fun exposedCalls(element: PsiElement): Sequence<Call> =
    element.children.asSequence().flatMap { child ->
        when (child) {
            is Call -> sequenceOf(child)
            is ElixirDoBlock, is ElixirAnonymousFunction, is ElixirStabOperation -> emptySequence()
            is QuotableKeywordPair ->
                if (child.blockKeyword() != null) emptySequence() else exposedCalls(child)
            else -> exposedCalls(child)
        }
    }

/**
 * What the `alias`, `require` or `import` [call] does, read through [storedBy] from no aliases where that doesn't depend
 * on the aliases before it: a `require` or `import` without `as:`, and one of a module rooted at `Elixir.`, or an atom.
 * A `require` or `import` without `as:` of a module [storedBy] can't read, but which may name one as it expands, does
 * what [storedWithoutAs] says. Anywhere else, and where neither reads anything, it stores, the reading that hides no name.
 */
@RequiresReadLock
private fun directiveStore(call: Call, level: ElixirLanguageLevel): AliasStore {
    val directive = call.functionName()?.let(Directive::of) ?: return AliasStore.STORES
    val arguments = (ElementLowering.lower(call) as? ElixirAst.Call)?.arguments ?: return AliasStore.STORES
    val ref = arguments.firstOrNull() ?: return AliasStore.STORES
    val opts = arguments.getOrNull(1)
    val stored = when {
        directive != Directive.ALIAS && !hasAs(opts) ->
            storedBy(directive, arguments, AliasTable.EMPTY, level)
                ?: storedWithoutAs(directive, opts, level)?.takeIf { mayNameAModule(ref) }
        isRooted(ref) -> storedBy(directive, arguments, AliasTable.EMPTY, level)
        else -> return AliasStore.STORES
    }

    return when (stored) {
        is AliasStored.Stored -> if (stored.aliased) AliasStore.STORES else AliasStore.REMOVES
        AliasStored.Untouched -> AliasStore.NONE
        is AliasStored.Error, null -> AliasStore.STORES
    }
}

/**
 * Whether [ref] may name a module as it expands: an alias, a call or a variable. Elixir raises on any other ref of a
 * `require` or `import` (`expected_compile_time_module`).
 */
private fun mayNameAModule(ref: ElixirAst): Boolean = ref is ElixirAst.Alias || ref is ElixirAst.Call

/** Whether [ref] names its module whatever the aliases: an atom, or an alias whose head is `Elixir`. */
private fun isRooted(ref: ElixirAst): Boolean =
    ref is ElixirAst.Literal.Atom || (ref is ElixirAst.Alias && ref.atomNames()?.first() == "Elixir")

/**
 * The modules of `Kernel.SpecialForms` and those [Env.defaultRequires] holds at [level] whose macros [call] may reach:
 * none for a call to any other module.
 */
@RequiresReadLock
private fun macroModules(call: Call, level: ElixirLanguageLevel): List<String> =
    if (call is Qualified) {
        listOfNotNull(Module.atom(call.resolvedModuleName())?.takeIf { it in Env.defaultRequires(level) })
    } else {
        UNQUALIFIED_MACRO_MODULES
    }

/** The module of [modules] whose macro [call] reaches, and what [macroStore] says it does; `null` where it reaches none. */
@RequiresReadLock
private fun reached(call: Call, modules: List<String>, level: ElixirLanguageLevel): Pair<String, MacroStore>? {
    val name = call.functionName() ?: return null
    val arity = call.resolvedFinalArity()

    return modules.firstNotNullOfOrNull { module -> macroStore(module, name, arity, level)?.let { module to it } }
}

/** [reached] over the modules [macroModules] gives for [call]. */
@RequiresReadLock
private fun reached(call: Call, level: ElixirLanguageLevel): Pair<String, MacroStore>? =
    // The name first: reading a qualified call's module quotes its qualifier.
    if (call.functionName() in MACRO_NAMES) reached(call, macroModules(call, level), level) else null

/** Whether [call], which reaches a macro that [MacroStore.DEFINES], is a defining clause: [AliasStore.DEFINES]. */
@RequiresReadLock
private fun isDefinition(call: Call): Boolean = DefModule.isDefiningClause(SyntacticCall.of(call))

/**
 * The directive, `import`, `require` or `use`, whose macro [call] reaches, as [aliasStore] reads it; `null` for any
 * other call.
 */
@RequiresReadLock
private fun directiveReached(call: Call, level: ElixirLanguageLevel): String? =
    call.functionName()?.takeIf { it in DIRECTIVES && reached(call, level) != null }

/** The directives [directiveReached] names. */
private val DIRECTIVES = setOf(IMPORT, REQUIRE, USE)

/** The directives that may bring a macro a call with no qualifier reaches. */
private val IMPORTING = setOf(IMPORT, USE)

/** What [call], which reaches no macro [macroStore] holds, stores. */
@RequiresReadLock
private fun unlisted(call: Call, level: ElixirLanguageLevel): AliasStore =
    if (call is Unqualified && earlierInScope(call).any { directiveReached(it, level) in IMPORTING }) {
        AliasStore.MAY_STORE
    } else {
        AliasStore.NONE
    }

/**
 * Whether an `import Kernel` with `only:` or `except:` is written before [call] in scope, or, where [byUse], a `use`,
 * which may import `Kernel` without one of its macros and another module's in its place.
 */
@RequiresReadLock
private fun kernelShadowedBefore(call: Call, level: ElixirLanguageLevel, byUse: Boolean): Boolean =
    earlierInScope(call).any { directive ->
        when (directiveReached(directive, level)) {
            USE -> byUse
            IMPORT ->
                Alias.firstArgument(directive)?.text?.let(Module::partial) == "Kernel" &&
                    (directive.keywordArgument("only") != null || directive.keywordArgument("except") != null)
            else -> false
        }
    }

/**
 * Whether a `require`, `import` or `use` written before [call] in scope names its module, or the `as:` of a `require`
 * does. Aliases are not expanded, so a module is named when the last segment of its name is.
 */
@RequiresReadLock
private fun isNamedBefore(call: Qualified, level: ElixirLanguageLevel): Boolean {
    val written = Module.split(call.moduleName()).last()

    return earlierInScope(call)
        .filter { directiveReached(it, level) != null }
        .any { directive ->
            listOfNotNull(Alias.firstArgument(directive), Alias.asValue(directive))
                .any { Module.split(it.text).last() == written }
        }
}

/**
 * The calls written before [element] in the scopes that hold it, below [until] or else its file, the nearest first:
 * each one that is a call, and the [exposedCalls] of a statement that isn't, such as a list, whose elements Elixir
 * expands where it is written. Another branch of an `if` or `case`, or a function's head, is no statement.
 */
private fun earlierInScope(element: PsiElement, until: PsiElement? = null): Sequence<Call> =
    scopes(element, true, until)
        .flatMap { it.siblingExpressions(forward = false, withSelf = false) }
        .flatMap { earlier ->
            when {
                earlier is Call -> sequenceOf(earlier)
                earlier.parent is PsiFile || earlier.parent is ElixirStabBody -> exposedCalls(earlier)
                else -> emptySequence()
            }
        }

/**
 * The calls, below [until] or else its file, whose aliases reach [element]: [enclosingDefinitions], then the calls
 * [earlierInScope].
 */
@RequiresReadLock
internal fun aliasingForms(element: PsiElement, level: ElixirLanguageLevel, until: PsiElement? = null): Sequence<Call> =
    enclosingDefinitions(element, level, until) + earlierInScope(element, until)

/**
 * The calls holding [element], below [until] or else its file, that store an alias where they are written, as [aliasStore]
 * says at [level]: the `defmodule` and `defprotocol` calls, the nearest first. An `alias` or `require` holding [element]
 * stores nothing [element] reads.
 */
@RequiresReadLock
private fun enclosingDefinitions(element: PsiElement, level: ElixirLanguageLevel, until: PsiElement?): Sequence<Call> =
    scopes(element, false, until)
        .filterIsInstance<Call>()
        // `aliasStore`'s `DEFINES`, without the walks over earlier statements it makes for other calls.
        .filter { call -> reached(call, level)?.second == MacroStore.DEFINES && isDefinition(call) }

/** The elements holding [element], and it [withSelf], below [until] or else its file. */
private fun scopes(element: PsiElement, withSelf: Boolean, until: PsiElement?): Sequence<PsiElement> =
    element.parents(withSelf).takeWhile { it !is PsiFile && it !== until }

/** What a call to a macro does to the aliases of the scope around it. */
internal enum class MacroStore {
    /** It goes through the store rule, [storeAlias], as `alias`, `require` and `import` do. */
    STORES,

    /** It stores the alias of its name's first segment, as `defmodule/2` and `defprotocol/2` do. */
    DEFINES,

    /** It may store any alias, as `use` may. */
    MAY_STORE,

    /**
     * It stores none around it: a form that opens a scope of its own, `defimpl`, whose module's name is computed, and
     * any macro that only reads or builds code.
     */
    NONE,
}

internal const val SPECIAL_FORMS = "Elixir.Kernel.SpecialForms"

/** The modules a call with no qualifier reaches a macro of without an `import`. */
private val UNQUALIFIED_MACRO_MODULES = listOf(SPECIAL_FORMS, KERNEL)

/**
 * What a call of [module]'s macro [name]/[arity] does, the one place a macro is classified as storing an alias or not;
 * `null` where [module] defines no such macro. A special form takes the arities [specialFormArity] gives at [level], and
 * a macro of a module [Env.defaultRequires] may hold those [MACROS] gives, from the level that adds it where [ADDED]
 * names one.
 */
internal fun macroStore(module: String, name: String, arity: Int, level: ElixirLanguageLevel): MacroStore? =
    if (module == SPECIAL_FORMS) {
        SPECIAL_FORM_STORES[name]?.takeIf { specialForm(name, arity, level) }
    } else {
        MACROS[module]?.get(name)
            ?.takeIf { arity in it.arities && ADDED[module]?.get(name to arity)?.isSufficient(level) != false }
            ?.store
    }

/** What a call of a macro does, and the arities its module defines it at on some supported level. */
private class Macro(val store: MacroStore, vararg arities: Int) {
    val arities: Set<Int> = arities.toSet()
}

/** The special forms, by name. */
private val SPECIAL_FORM_STORES: Map<String, MacroStore> =
    listOf("alias", "require", "import").associateWith { MacroStore.STORES } +
        listOf(
            "%", "%{}", "&", ".", "::", "<<>>", "=", "^", "{}", "__aliases__", "__block__", "__CALLER__", "__cursor__",
            "__DIR__", "__ENV__", "__MODULE__", "__STACKTRACE__", "case", "cond", "fn", "for", "quote",
            "receive", "super", "try", "unquote", "unquote_splicing", "with",
        ).associateWith { MacroStore.NONE }

/** The macros of each module [Env.defaultRequires] may hold on a supported level, by module and name. */
private val MACROS: Map<String, Map<String, Macro>> =
    mapOf(
        KERNEL to
            mapOf(
                "defmodule" to Macro(MacroStore.DEFINES, 2),
                "defprotocol" to Macro(MacroStore.DEFINES, 2),
                "use" to Macro(MacroStore.MAY_STORE, 1, 2),
            ) +
            none(
                0 to listOf("binding", "dbg", ".."),
                1 to listOf(
                    "!", "@", "alias!", "binding", "dbg", "def", "defexception", "defguard", "defguardp", "defmacro",
                    "defmacrop", "defoverridable", "defp", "defstruct", "get_in", "is_exception", "is_nil",
                    "is_non_struct_map", "is_struct", "pop_in", "raise", "to_char_list", "to_charlist", "to_string",
                    "var!",
                ),
                2 to listOf(
                    "&&", "..", "<>", "|>", "||", "and", "dbg", "def", "defdelegate", "defimpl", "defmacro", "defmacrop",
                    "defp", "destructure", "get_and_update_in", "if", "in", "is_exception", "is_struct", "match?", "or",
                    "put_in", "raise", "reraise", "sigil_c", "sigil_C", "sigil_D", "sigil_N", "sigil_r", "sigil_R",
                    "sigil_s", "sigil_S", "sigil_T", "sigil_U", "sigil_w", "sigil_W", "tap", "then", "unless",
                    "update_in", "var!",
                ),
                3 to listOf("..//", "defimpl", "reraise"),
            ),
        APPLICATION to none(1 to listOf("__using__"), 2 to listOf("compile_env", "compile_env!"), 3 to listOf("compile_env")),
    )

/** The [MACROS] a supported level may lack, by module, then name and arity, with the feature that adds each. */
private val ADDED: Map<String, Map<Pair<String, Int>, ElixirLanguageFeature>> =
    mapOf(
        KERNEL to
            mapOf(
                (".." to 0) to NULLARY_RANGE,
                ("..//" to 3) to STEP_OPERATOR,
                ("dbg" to 0) to DBG_MACRO,
                ("dbg" to 1) to DBG_MACRO,
                ("dbg" to 2) to DBG_MACRO,
                ("get_in" to 1) to NIL_SAFE_GET_IN,
                ("is_non_struct_map" to 1) to IS_NON_STRUCT_MAP_GUARD,
                ("tap" to 2) to TAP_AND_THEN_MACROS,
                ("then" to 2) to TAP_AND_THEN_MACROS,
            ),
    )

/** The names [macroStore] classifies at some module and arity. */
private val MACRO_NAMES: Set<String> = SPECIAL_FORM_STORES.keys + MACROS.values.flatMap { it.keys }

/** The macros that store no alias, from each arity to the names defined at it. */
private fun none(vararg byArity: Pair<Int, List<String>>): Map<String, Macro> =
    byArity
        .flatMap { (arity, names) -> names.map { it to arity } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, arities) -> Macro(MacroStore.NONE, *arities.toIntArray()) }
