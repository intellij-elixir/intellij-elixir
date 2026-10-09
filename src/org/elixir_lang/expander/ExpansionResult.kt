package org.elixir_lang.expander

import org.elixir_lang.lowering.ElixirAst

/**
 * What compiling one module gives: what it defines, each body's expansion in the order Elixir expands them, the errors
 * Elixir reported, and how the module ended.
 *
 * @property module the module's name as atom text, or, where it isn't an atom, as `inspect/1` shows it
 * @property units the module body, then each definition's body in the order Elixir expands them
 * @property errors the errors reported in this module and by the checks once its body ran, in Elixir's order; a nested
 *   module's are its own
 * @property warnings the warnings reported in this module, in Elixir's order; a nested module's are its own
 * @property opaque each unit's [Expansion.Opaque]
 * @property consulted the modules whose exports the module's expansion read
 * @property attributes what the module body did to its attributes
 */
internal data class ExpansionResult(
    val module: String,
    val table: DefinitionTable,
    val units: List<Unit>,
    val errors: List<Reported>,
    val warnings: List<Warning>,
    val ended: Ended,
    val opaque: List<Expansion.Opaque>,
    val consulted: Set<String>,
    val nested: List<ExpansionResult>,
    val attributes: AttributeLog,
) {
    /**
     * One body.
     *
     * @property node the module body, or the `def*` call that defined the body
     * @property ordered whether Elixir expands the body where the expander does: it and every body before it were
     *   defined at a statement of the module body
     * @property expansion the body's expansion, which a definition's ends at its first [Expansion] that isn't
     *   [Expansion.Expanded]
     */
    data class Unit(val owner: Owner, val node: ElixirAst, val ordered: Boolean, val expansion: Expansion)

    sealed interface Owner {
        data object ModuleBody : Owner

        /** @property name `null` where the name isn't known */
        data class Definition(val kind: DefinitionTable.Kind, val name: String?, val arity: Int) : Owner
    }

    sealed interface Ended {
        /** Whether compiling the module raised, which ends the compile of the file or module around it. */
        val raises: Boolean get() = this is Tainted || this is Raised || this is Crashed

        /** No error was reported. */
        data object Compiled : Ended

        /** An error was reported and nothing raised, so Elixir raises once the checks have run. */
        data object Tainted : Ended

        /** [error] raised. */
        data class Raised(val error: Expansion.Error) : Ended

        /** Elixir's own code raised [exception] after [error] was reported. */
        data class Crashed(val error: Expansion.Error, val exception: String) : Ended

        /** The first unit to reach a node that isn't ported, or a macro that isn't modelled, reached [at]. */
        data class Stopped(val at: ElixirAst) : Ended
    }
}

/**
 * What expanding a file gives.
 *
 * @property top the expansion of the file's forms, whose env is the one after the last form
 * @property modules each module the file defines at its top, in the order Elixir compiles them
 */
internal data class FileExpansion(val top: Expansion, val modules: List<ExpansionResult>)
