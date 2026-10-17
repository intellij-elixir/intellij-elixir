package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.lowering.ElixirAst

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

        companion object {
            /** The kind `def`, `defp`, `defmacro` or `defmacrop`, as [keyword], defines. */
            fun of(keyword: String): Kind = valueOf(keyword.uppercase())
        }
    }

    /**
     * One definition.
     *
     * @property heads each head stored, in order; a compiled module's entry holds its first only
     * @property clauses the clauses stored, none for a bodiless head
     * @property defaults the most defaults any of its heads gave
     * @property default whether it was first stored for a default arity of another definition
     * @property ordered whether each of its clauses was defined at a statement of the module body
     * @property checksClauses whether its last head is checked for clauses: one with no `unquote` and no `context`
     */
    data class Entry(
        val kind: Kind,
        val heads: List<Head>,
        val clauses: Int,
        val defaults: Int,
        val default: Boolean,
        val ordered: Boolean,
        val checksClauses: Boolean,
    ) {
        /** Its first head. */
        val at: ElixirAst get() = heads.first().at

        /** The line of its first head. */
        val line: Int get() = lineOf(at.meta)
    }

    /**
     * One head of a definition.
     *
     * @property at the `def*` call
     * @property delegation what the head calls, when a `defdelegate` defined it
     * @property injected how a macro's output injected it, when one did
     */
    data class Head(val at: ElixirAst, val delegation: Delegation?, val injected: Injected?)

    /**
     * A head a macro's output injected.
     *
     * @property place where its `def*` call is written in the macro's file
     */
    class Injected(val place: Place)

    /** A `defdelegate`'s `to:` module and the function it calls there, as its `delegate_to` doc has them. */
    data class Delegation(val module: String, val function: String)

    /** Each named definition, in the order it was first stored. */
    val entries: Map<NameArity, Entry>
        field = LinkedHashMap()

    /**
     * A definition whose name isn't known.
     *
     * @property statement whether it was defined at a statement of the module body, so that only its name is unknown
     */
    data class Unnamed(val kind: Kind, val line: Int, val statement: Boolean)

    /** The definitions whose name isn't known, in the order they were defined. */
    val unnamed: List<Unnamed>
        field = mutableListOf()

    /** Whether every definition's name is known, so that a lookup by name finds each definition it may. */
    val everyNameKnown: Boolean get() = unnamed.isEmpty()

    /** Each named definition's kind. */
    val kinds: Map<NameArity, Kind> get() = entries.mapValues { it.value.kind }

    operator fun get(nameArity: NameArity): Entry? = entries[nameArity]

    /** The `{default, Name}` rows of the bag: each arity stored with defaults, and how many. */
    private val defaultRows = mutableMapOf<String, MutableList<Pair<Int, Int>>>()

    /**
     * `elixir_def:store_definition/10`: a head of [name] and [arity], or of no known name when [name] is `null`, with
     * [clauses] clauses and [defaults] defaults, then a clause for each default arity: the kind of the error the store
     * raises, if it raises, in which case nothing more is stored. For an unnamed head, [ordered] is whether it is a
     * statement of the module body. [delegation] is the head's, not its default arities'. [injected] is how a macro's
     * output injected the head and its default arities.
     */
    fun define(
        name: String?,
        arity: Int,
        kind: Kind,
        at: ElixirAst,
        clauses: Int,
        defaults: Int,
        ordered: Boolean,
        checksClauses: Boolean,
        delegation: Delegation? = null,
        injected: Injected? = null,
    ): String? {
        if (name == null) {
            unnamed += Unnamed(kind, lineOf(at.meta), ordered)

            return null
        }

        if (conflictsWithPreviousDefaults(name, arity, defaults)) return "defs_with_defaults"

        store(NameArity(name, arity), kind, listOf(Head(at, delegation, injected)), clauses, defaults, default = false, ordered, checksClauses)
            ?.let { return it }

        for (defaultArity in defaultArities(arity, defaults)) {
            store(NameArity(name, defaultArity), kind, listOf(Head(at, null, injected)), 1, 0, default = true, ordered, checksClauses = false)
                ?.let { return it }
        }

        return null
    }

    /**
     * `check_previous_defaults/7`: whether another arity of [name] stored with defaults has [arity] among its default
     * arities, or has an arity among this definition's.
     */
    private fun conflictsWithPreviousDefaults(name: String, arity: Int, defaults: Int): Boolean =
        defaultRows[name].orEmpty().any { (storedArity, storedDefaults) ->
            storedArity != arity &&
                storedDefaults != 0 &&
                (arity in defaultArities(storedArity, storedDefaults) || storedArity in defaultArities(arity, defaults))
        }

    /**
     * `elixir_def:store_definition/9`, as `elixir_overridable` stores a definition `Module.make_overridable/2` took: the
     * definition as it was, with no check against the defaults of another arity and no clause for its default arities,
     * which were never taken. The kind of the error the store raises, if it raises.
     */
    fun restore(name: String, arity: Int, kind: Kind, heads: List<Head>, clauses: Int, defaults: Int, ordered: Boolean): String? =
        store(NameArity(name, arity), kind, heads, clauses, defaults, default = false, ordered, checksClauses = false)

    /** [entry] as a compiled module holds [nameArity], with none of a definition's checks, which its compile passed. */
    fun put(nameArity: NameArity, entry: Entry) {
        entries[nameArity] = entry
    }

    /** `elixir_def:take_definition/2`: [nameArity]'s entry, taken out of the table with its `{default, Name}` row. */
    fun remove(nameArity: NameArity): Entry? =
        entries.remove(nameArity)?.also { removed ->
            defaultRows[nameArity.name]?.removeAll { it == nameArity.arity to removed.defaults }
        }

    /**
     * `elixir_def:store_definition/11`: every head is kept, the most defaults, and the last head's [checksClauses].
     * A store of another kind raises `changed_kind`, and a second with defaults `duplicate_defaults`.
     */
    private fun store(
        nameArity: NameArity,
        kind: Kind,
        heads: List<Head>,
        clauses: Int,
        defaults: Int,
        default: Boolean,
        ordered: Boolean,
        checksClauses: Boolean,
    ): String? {
        if (defaults > 0) defaultRows.getOrPut(nameArity.name) { mutableListOf() } += nameArity.arity to defaults

        val stored = entries[nameArity]

        entries[nameArity] =
            if (stored == null) {
                Entry(kind, heads, clauses, defaults, default, ordered, checksClauses)
            } else {
                if (stored.kind != kind) return "changed_kind"
                if (defaults > 0 && stored.defaults > 0) return "duplicate_defaults"

                stored.copy(
                    heads = stored.heads + heads,
                    clauses = stored.clauses + clauses,
                    defaults = maxOf(stored.defaults, defaults),
                    ordered = stored.ordered && ordered,
                    checksClauses = checksClauses,
                )
            }

        return null
    }

    companion object {
        /** The arities below [arity] a definition with [defaults] default arguments answers to. */
        fun defaultArities(arity: Int, defaults: Int): IntRange = arity - defaults until arity
    }
}
