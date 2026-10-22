package org.elixir_lang.expander

import org.elixir_lang.NameArity

/** What each module exports, as `elixir_aliases:ensure_loaded/3` and `elixir_import` read it. */
fun interface Exports {
    /** [module]'s exports; [module] is atom text, so an Elixir module carries the `Elixir.` prefix. */
    fun of(module: String): ModuleExports
}

sealed class ModuleExports {
    /** No such module: `ensure_loaded` raises `unloaded_module`. */
    data object Absent : ModuleExports()

    /** The module exists but its exports can't be read. */
    data object Unreadable : ModuleExports()

    /**
     * @property functions with [hasInfo], what `__info__(:functions)` gives; without, `module_info(exports)`
     *   unfiltered, `module_info/0,1` and `behaviour_info/1` included
     * @property macros what `__info__(:macros)` gives; empty without [hasInfo]
     * @property hasInfo whether the module exports `__info__/1`, as every Elixir module does
     * @property behaviour what `behaviour_info/1` answers, which [functions] leave out where [hasInfo]
     * @property deprecated what `__info__(:deprecated)` answers
     */
    data class Present(
        val functions: List<NameArity>,
        val macros: List<NameArity>,
        val hasInfo: Boolean,
        val behaviour: Behaviour = Behaviour.Unreadable,
        val deprecated: Deprecations = Deprecations.Unreadable,
    ) : ModuleExports() {
        /**
         * Every function a remote call reaches: [functions], and with [hasInfo] those of [NOT_IN_INFO] the module
         * exports, `behaviour_info/1` only for a behaviour, and each of [macros] as its [macroExport].
         */
        val exported: List<NameArity>
            get() =
                if (hasInfo) {
                    buildList {
                        addAll(functions)
                        addAll(NOT_IN_INFO.filter { it != BEHAVIOUR_INFO || behaviour != Behaviour.None })
                        macros.mapTo(this, ::macroExport)
                    }
                } else {
                    functions
                }
    }

    /** What a module's `behaviour_info/1` answers. */
    sealed class Behaviour {
        /** The module doesn't export `behaviour_info/1`: it is no behaviour. */
        data object None : Behaviour()

        /**
         * @property callbacks what `behaviour_info(:callbacks)` returns, where a macro callback is `MACRO-<name>` with
         *   one more than its arity
         */
        data class Callbacks(val callbacks: List<NameArity>) : Behaviour()

        /** The module may export `behaviour_info/1`, but what it answers can't be read. */
        data object Unreadable : Behaviour()
    }

    companion object {
        /** `module_info/0,1`, which the Erlang compiler adds to every module. */
        val MODULE_INFO: Set<NameArity> = setOf(NameArity("module_info", 0), NameArity("module_info", 1))

        /** `behaviour_info/1`, which the Erlang compiler adds to a behaviour. */
        val BEHAVIOUR_INFO = NameArity("behaviour_info", 1)

        /** What `elixir_import:remove_internals/1` removes: [MODULE_INFO] and a behaviour's [BEHAVIOUR_INFO]. */
        val INTERNALS: Set<NameArity> = MODULE_INFO + BEHAVIOUR_INFO

        /** `__info__/1`, which `elixir_erl` adds beside the module's `def`s. */
        val INFO = NameArity("__info__", 1)

        /** The exports `__info__(:functions)` leaves out: [INTERNALS] and [INFO]. */
        val NOT_IN_INFO: Set<NameArity> = INTERNALS + INFO

        private const val MACRO_PREFIX = "MACRO-"

        /** The function Elixir compiles [macro] to and exports: `MACRO-<name>`, taking the caller first. */
        fun macroExport(macro: NameArity): NameArity = NameArity(MACRO_PREFIX + macro.name, macro.arity + 1)

        /** The macro [export] is the [macroExport] of, or `null` where it is a function's. */
        fun macroOf(export: NameArity): NameArity? =
            if (export.name.startsWith(MACRO_PREFIX)) {
                NameArity(export.name.removePrefix(MACRO_PREFIX), export.arity - 1)
            } else {
                null
            }
    }
}

/** A module's hard deprecations, which `elixir_dispatch:check_deprecated/6` reads once the module is loaded. */
sealed class Deprecations {
    /** @property reasons each `@deprecated` definition's reason */
    data class Known(val reasons: Map<NameArity, String>) : Deprecations()

    /** The module may deprecate definitions, but which, or why, can't be read. */
    data object Unreadable : Deprecations()
}
