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
 * @property struct the struct `defstruct` recorded in the module body, or `null` where nothing recorded one
 * @property certain whether what compiling the module did is Elixir's: Elixir runs the `defmodule` where the expander
 *   did, it compiled, every statement of its body ran with Elixir's outcome, and no `@after_compile` callback runs code
 *   the expander doesn't follow
 * @property definitionsKnown whether what the module defines is known, as other files read it where the project
 *   compiles: it compiled, its `defmodule` and each around it are placed where Elixir runs them ([BodyValues.placed]),
 *   its tables hold what Elixir's do ([Compiling.tablesCertain]), and no `@after_compile` callback runs code the
 *   expander doesn't follow
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
    val struct: ModuleStruct?,
    val certain: Boolean,
    val definitionsKnown: Boolean,
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

        /**
         * Whether compiling the module ended its run and the run of the file or module around it: it raised, or it
         * reached a raise from certain inputs, where Elixir raised or raised before it.
         */
        val endsRun: Boolean get() = raises || this is Stopped && this.mayRaise

        /**
         * Whether compiling the module ended the run of the file or module around it, where the module is one of its
         * units and a [statement] of it or not: a raise ends that run wherever the module is, since the module raised
         * only where Elixir ran it as the expander did; a raise that may be Elixir's ends it at a statement, where
         * Elixir ran nothing after the module if it ran it as the expander did.
         */
        fun endsRunAt(statement: Boolean): Boolean = raises || statement && endsRun

        /** No error was reported. */
        data object Compiled : Ended

        /** An error was reported and nothing raised, so Elixir raises once the checks have run. */
        data object Tainted : Ended

        /** [error] raised. */
        data class Raised(val error: Expansion.Error) : Ended

        /** Elixir's own code raised [exception] after [error] was reported. */
        data class Crashed(val error: Expansion.Error, val exception: String) : Ended

        /**
         * The first unit to reach a node that isn't ported, or a macro that isn't modelled, reached [at]. The module
         * [mayRaise] where that unit, or a later one on the statement path, reached an error from certain inputs that
         * isn't the module's for certain, as Elixir may have raised at the stop before it: the module's run ends there
         * as a raise ends it.
         * The stop [skipped] what Elixir runs after it where it is in an expansion Elixir finishes before it runs any
         * of it, the body's or a `@before_compile` callback's output's, so what Elixir would have queued after it was
         * never queued, and where it left `@before_compile` callbacks unrun.
         */
        data class Stopped(val at: ElixirAst, val mayRaise: Boolean = false, val skipped: Boolean = false) : Ended
    }
}

/**
 * What expanding a file gives.
 *
 * @property top the expansion of the file's forms, whose env is the one after the last form
 * @property modules each module the file defines at its top, in the order Elixir compiles them
 * @property endsRun whether the file's run ended at an error from certain inputs: Elixir raised there or before it, so
 *   it compiled no module after it
 */
internal data class FileExpansion(val top: Expansion, val modules: List<ExpansionResult>, val endsRun: Boolean)
