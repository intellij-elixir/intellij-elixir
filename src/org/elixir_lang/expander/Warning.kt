package org.elixir_lang.expander

import org.elixir_lang.lowering.ElixirAst

/** A warning Elixir reports as it expands a module. It changes nothing about the expansion. */
internal sealed interface Warning {
    /** The call that is warned of. */
    val at: ElixirAst

    /**
     * A call of a function or macro its module marks `@deprecated`: `elixir_dispatch:check_deprecated/6`'s warning.
     *
     * @property reason the attribute's text
     */
    data class Deprecated(
        override val at: ElixirAst,
        val module: String,
        val name: String,
        val arity: Int,
        val reason: String,
    ) : Warning

    /** A call of `Application.get_env/fetch_env/fetch_env!` in a module body, where `compile_env` is meant. */
    data class CompileEnv(override val at: ElixirAst, val name: String, val arity: Int) : Warning
}
