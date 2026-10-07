package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.language_level.ElixirLanguageFeature.KERNEL_TYPESPEC_REQUIRED_BY_DEFAULT
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * Elixir's expansion environment `E`, less `file`, `line`, `lexical_tracker`, `tracers` and the variables. Every name is
 * the text of the atom it quotes to; modules and aliases carry the `Elixir.` prefix. Each list is in the order `E` holds
 * it.
 */
data class Env(
    val aliases: List<Alias>,
    /** An ordset, as `E` keeps it. */
    val requires: List<String>,
    val functions: List<Imports>,
    val macros: List<Imports>,
    val macroAliases: List<MacroAlias>,
    val context: Context,
    val contextModules: List<String>,
    /** `null` where `E`'s `module` is `nil`: outside any module. */
    val module: String?,
    /** `null` where `E`'s `function` is `nil`: outside any function. */
    val function: NameArity?,
) {
    /** `{alias, module}`: [alias] names [module]. */
    data class Alias(val alias: String, val module: String)

    /** `{module, [{name, arity}]}`: the names imported from [module]. */
    data class Imports(val module: String, val nameArities: List<NameArity>)

    /** `{alias, {counter, module}}`: an alias defined inside the macro expansion that took [counter]. */
    data class MacroAlias(val alias: String, val counter: Counter, val module: String)

    /** The counter a macro expansion takes (`elixir_module:next_counter/1`). */
    sealed class Counter {
        /** `{module, n}`: the [n]th counter taken in [module] while it is being defined. */
        data class InModule(val module: String, val n: Long) : Counter()

        /** `erlang:unique_integer()`: taken outside a module being defined. */
        data class Unique(val n: Long) : Counter()
    }

    enum class Context { NONE, MATCH, GUARD }

    companion object {
        /**
         * The env at the start of an empty module body, before `defmodule` sets [module] and [contextModules]: the
         * default `requires` for [level], and [kernel]'s functions and macros imported.
         */
        fun empty(level: ElixirLanguageLevel, kernel: KernelImports): Env =
            Env(
                aliases = emptyList(),
                requires = if (KERNEL_TYPESPEC_REQUIRED_BY_DEFAULT.isSufficient(level)) {
                    listOf(APPLICATION, KERNEL, KERNEL_TYPESPEC)
                } else {
                    listOf(APPLICATION, KERNEL)
                },
                functions = listOf(Imports(KERNEL, kernel.functions)),
                macros = listOf(Imports(KERNEL, kernel.macros)),
                macroAliases = emptyList(),
                context = Context.NONE,
                contextModules = emptyList(),
                module = null,
                function = null,
            )

        private const val APPLICATION = "Elixir.Application"
        private const val KERNEL = "Elixir.Kernel"
    }
}
