package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.expander.DefinitionTable.Entry
import org.elixir_lang.expander.DefinitionTable.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/** [DefinitionTable.define]: what `elixir_def:store_definition/9` stores for each definition. */
class DefinitionTableTest {
    @Test
    fun `one clause of each kind`() {
        val table = DefinitionTable()

        Kind.entries.forEachIndexed { index, kind -> table.define(kind.name.lowercase(), 0, kind, index + 1, 1, 0, true) }

        assertEntries(
            table,
            "def/0" to Entry(Kind.DEF, 1, 1, 0, default = false, ordered = true),
            "defp/0" to Entry(Kind.DEFP, 2, 1, 0, default = false, ordered = true),
            "defmacro/0" to Entry(Kind.DEFMACRO, 3, 1, 0, default = false, ordered = true),
            "defmacrop/0" to Entry(Kind.DEFMACROP, 4, 1, 0, default = false, ordered = true),
        )
    }

    @Test
    fun `each default arity is an entry of one clause`() {
        val table = DefinitionTable()

        table.define("f", 3, Kind.DEF, 1, 1, 2, true)

        assertEntries(
            table,
            "f/3" to Entry(Kind.DEF, 1, 1, 2, default = false, ordered = true),
            "f/1" to Entry(Kind.DEF, 1, 1, 0, default = true, ordered = true),
            "f/2" to Entry(Kind.DEF, 1, 1, 0, default = true, ordered = true),
        )
    }

    @Test
    fun `a bodiless head stores no clauses`() {
        val table = DefinitionTable()

        table.define("f", 2, Kind.DEF, 1, 0, 1, true)
        table.define("f", 2, Kind.DEF, 2, 1, 0, true)

        assertEntries(
            table,
            "f/2" to Entry(Kind.DEF, 1, 1, 1, default = false, ordered = true),
            "f/1" to Entry(Kind.DEF, 1, 1, 0, default = true, ordered = true),
        )
    }

    @Test
    fun `later clauses keep the first line and add their clauses`() {
        val table = DefinitionTable()

        table.define("f", 1, Kind.DEF, 1, 1, 0, true)
        table.define("f", 1, Kind.DEF, 3, 1, 0, true)

        assertEntries(table, "f/1" to Entry(Kind.DEF, 1, 2, 0, default = false, ordered = true))
    }

    @Test
    fun `an unordered clause makes its definition unordered`() {
        val table = DefinitionTable()

        table.define("f", 1, Kind.DEF, 1, 1, 0, true)
        table.define("f", 1, Kind.DEF, 3, 1, 0, false)
        table.define("g", 1, Kind.DEF, 5, 1, 0, true)

        assertEntries(
            table,
            "f/1" to Entry(Kind.DEF, 1, 2, 0, default = false, ordered = false),
            "g/1" to Entry(Kind.DEF, 5, 1, 0, default = false, ordered = true),
        )
    }

    @Test
    fun `an unnamed definition is listed apart`() {
        val table = DefinitionTable()

        table.define(null, 1, Kind.DEFP, 1, 1, 1, false)

        assertEntries(table)
        assertEquals(listOf(Kind.DEFP), table.unnamed)
    }

    @Test
    fun `kinds are each entry's kind`() {
        val table = DefinitionTable()

        table.define("f", 1, Kind.DEF, 1, 1, 1, true)
        table.define("m", 0, Kind.DEFMACROP, 2, 1, 0, true)

        assertEquals(
            mapOf(NameArity("f", 1) to Kind.DEF, NameArity("f", 0) to Kind.DEF, NameArity("m", 0) to Kind.DEFMACROP),
            table.kinds,
        )
    }

    private fun assertEntries(table: DefinitionTable, vararg expected: Pair<String, Entry>) =
        assertEquals(
            expected.joinToString("\n") { (key, entry) -> "$key $entry" },
            table.entries.entries.joinToString("\n") { (key, entry) -> "${key.name}/${key.arity} $entry" },
        )
}
