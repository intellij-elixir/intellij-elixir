package org.elixir_lang.expander

import org.elixir_lang.NameArity

/** What a module defines, as `Module.definitions_in/2` lists it. */
internal class DefinitionTable {
    /** The four kinds Elixir's definition table holds. */
    enum class Kind {
        DEF,
        DEFP,
        DEFMACRO,
        DEFMACROP;

        val macro: Boolean get() = this == DEFMACRO || this == DEFMACROP
        val public: Boolean get() = this == DEF || this == DEFMACRO
    }

    /**
     * One definition.
     *
     * @property line the line of its first clause
     * @property clauses the clauses stored, none for a bodiless head
     * @property defaults the most defaults any of its heads gave
     * @property default whether it was first stored for a default arity of another definition
     * @property ordered whether each of its clauses was defined at a statement of the module body
     */
    data class Entry(
        val kind: Kind,
        val line: Int,
        val clauses: Int,
        val defaults: Int,
        val default: Boolean,
        val ordered: Boolean,
    )

    /** Each named definition, in the order it was first stored. */
    val entries: Map<NameArity, Entry>
        field = LinkedHashMap()

    /** The kinds of the definitions whose name isn't known, in the order they were defined. */
    val unnamed: List<Kind>
        field = mutableListOf()

    /** Each named definition's kind. */
    val kinds: Map<NameArity, Kind> get() = entries.mapValues { it.value.kind }

    operator fun get(nameArity: NameArity): Entry? = entries[nameArity]

    /**
     * `elixir_def:store_definition/10`: a head of [name] and [arity], or of no known name when [name] is `null`, with
     * [clauses] clauses and [defaults] defaults, then a clause for each default arity.
     */
    fun define(name: String?, arity: Int, kind: Kind, line: Int, clauses: Int, defaults: Int, ordered: Boolean) {
        if (name == null) {
            unnamed += kind

            return
        }

        store(NameArity(name, arity), kind, line, clauses, defaults, default = false, ordered)

        for (defaultArity in arity - defaults until arity) {
            store(NameArity(name, defaultArity), kind, line, 1, 0, default = true, ordered)
        }
    }

    /** `elixir_def:store_definition/9`: the first clause's line is kept, and the most defaults. */
    private fun store(
        nameArity: NameArity,
        kind: Kind,
        line: Int,
        clauses: Int,
        defaults: Int,
        default: Boolean,
        ordered: Boolean,
    ) {
        entries[nameArity] =
            entries[nameArity]?.let { stored ->
                stored.copy(
                    kind = kind,
                    clauses = stored.clauses + clauses,
                    defaults = maxOf(stored.defaults, defaults),
                    ordered = stored.ordered && ordered,
                )
            } ?: Entry(kind, line, clauses, defaults, default, ordered)
    }
}
