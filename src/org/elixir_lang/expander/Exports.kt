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
         * exports, `behaviour_info/1` only for a behaviour.
         */
        val exported: List<NameArity>
            get() =
                if (hasInfo) {
                    functions + NOT_IN_INFO.filter { it.name != "behaviour_info" || behaviour != Behaviour.None }
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
        /**
         * The exports `__info__(:functions)` leaves out: `__info__/1`, which `elixir_erl` adds beside the module's
         * `def`s, and `module_info/0,1` and `behaviour_info/1`, which the Erlang compiler adds.
         */
        val NOT_IN_INFO: Set<NameArity> = setOf(
            NameArity("__info__", 1),
            NameArity("module_info", 0),
            NameArity("module_info", 1),
            NameArity("behaviour_info", 1),
        )
    }
}

/** A module's hard deprecations, which `elixir_dispatch:check_deprecated/6` reads once the module is loaded. */
sealed class Deprecations {
    /** @property reasons each `@deprecated` definition's reason */
    data class Known(val reasons: Map<NameArity, String>) : Deprecations()

    /** The module may deprecate definitions, but which, or why, can't be read. */
    data object Unreadable : Deprecations()
}
