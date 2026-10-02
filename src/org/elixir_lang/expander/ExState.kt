package org.elixir_lang.expander

import org.elixir_lang.expander.ExState.Prematch.OutsideMatch.Mode
import org.elixir_lang.language_level.ElixirLanguageFeature.UNDEFINED_VARIABLE_RAISES
import org.elixir_lang.language_level.ElixirLanguageLevel

/** `{name, context}`, the name as atom text: the context is `nil` for a variable written in source. */
data class Variable(val name: String, val context: Context) {
    /** What `elixir_utils:var_context/2` gives: the variable's own context, or the counter of the macro that made it. */
    sealed class Context {
        data class Atom(val text: String) : Context()

        data class Counter(val counter: Env.Counter) : Context()
    }

    companion object {
        val NIL: Context = Context.Atom("nil")
    }
}

/**
 * The part of Elixir's expansion state `#elixir_ex{}` that the ported clauses read and write. On 1.11–1.12 Elixir kept
 * it in `E`; it is kept here on every version.
 *
 * @property read the variables that can be read, each at its version
 * @property version the version the next new variable takes
 * @property stacktrace whether `__STACKTRACE__` can be read: inside the clauses of a `catch` or `rescue`
 * @property caller whether `__CALLER__` can be read: in a macro's body
 */
data class ExState(
    val read: Map<Variable, Int>,
    val write: Write,
    val prematch: Prematch,
    val version: Int,
    val stacktrace: Boolean = false,
    val caller: Boolean = false,
) {
    /** The write half of `vars`: where bindings go that the expression being expanded can't read yet. */
    sealed interface Write {
        /** `false`: outside an argument scope, a binding is only read. */
        data object NotWriting : Write

        data class Writing(val vars: Map<Variable, Int>) : Write
    }

    sealed interface Prematch {
        /** Outside a pattern: [mode] says what an undefined variable is. */
        data class OutsideMatch(val mode: Mode) : Prematch {
            enum class Mode {
                /** It warns and is expanded as a local call of no arguments. */
                Warn,

                /** `undefined_var`. */
                Raise,

                /** Under `^`: `undefined_var_pin`. */
                Pin,
            }
        }

        /**
         * Inside a pattern.
         *
         * @property read the variables that could be read before the match's right side, which `^` reads
         * @property version the next version when the match began: a variable at this version or later was already
         *   bound by this match
         * @property cycles from 1.18, each variable bound by one side of a match inside a pattern, mapped to the
         *   variables its sibling sides bind
         * @property skip from 1.18, the groups of variables bound together by one side, which never form a cycle
         *   among themselves
         */
        data class InMatch(
            val read: Map<Variable, Int>,
            val version: Int,
            val cycles: Map<Variable, Map<Variable, Dependency>>,
            val skip: List<Set<Variable>>,
        ) : Prematch

        /**
         * A bitstring size inside [match]'s pattern, from 1.14. A variable bound by the pattern before the bitstring,
         * one in [original] but not in [match]'s read, can't be read.
         *
         * @property original the variables that could be read where the bitstring began
         */
        data class Bitsize(val match: InMatch, val original: Map<Variable, Int>) : Prematch

        /** Whether a sibling binds the variable on one side of the match, or on more than one. */
        enum class Dependency { ONE_SIDE, REPEATED }
    }

    /** `elixir_env:prepare_write/1`. */
    fun prepareWrite(): ExState = copy(write = Write.Writing(read))

    /** `elixir_env:reset_read/2`: [start]'s read half, keeping this state's writes. */
    fun resetRead(start: ExState): ExState = copy(read = start.read)

    /** `elixir_env:close_write/2`: the writes become readable, and merge into [upper]'s write half when it has one. */
    fun closeWrite(upper: ExState): ExState {
        val writes = (write as Write.Writing).vars

        return copy(
            read = writes,
            write = when (val upperWrite = upper.write) {
                Write.NotWriting -> Write.NotWriting
                is Write.Writing -> Write.Writing(mergeVars(upperWrite.vars, writes))
            }
        )
    }

    companion object {
        /** The state a module body starts from, under the default `on_undefined_variable`. */
        fun empty(level: ElixirLanguageLevel): ExState {
            val mode = if (UNDEFINED_VARIABLE_RAISES.isSufficient(level)) Mode.Raise else Mode.Warn

            return ExState(emptyMap(), Write.NotWriting, Prematch.OutsideMatch(mode), 0)
        }

        /** `elixir_env:merge_vars/2`: the union, keeping the higher version of a variable in both. */
        fun mergeVars(into: Map<Variable, Int>, from: Map<Variable, Int>): Map<Variable, Int> =
            from.entries.fold(into) { acc, (variable, version) ->
                if ((acc[variable] ?: Int.MIN_VALUE) >= version) acc else acc + (variable to version)
            }
    }
}
