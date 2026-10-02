package org.elixir_lang.expander

import com.intellij.openapi.util.TextRange
import org.elixir_lang.NameArity
import org.elixir_lang.expander.DefinitionTable.Kind
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [DefinitionTable.define]: what `elixir_def:store_definition/9` stores for each definition, and the error it raises
 * where a definition conflicts with an earlier one.
 */
class DefinitionTableTest {
    @Test
    fun `one clause of each kind`() {
        val table = DefinitionTable()

        Kind.entries.forEachIndexed { index, kind -> define(table, kind.name.lowercase(), 0, kind, index + 1) }

        assertEntries(
            table,
            "def/0 def line 1 clauses 1",
            "defp/0 defp line 2 clauses 1",
            "defmacro/0 defmacro line 3 clauses 1",
            "defmacrop/0 defmacrop line 4 clauses 1",
        )
    }

    @Test
    fun `each default arity is an entry of one clause`() {
        val table = DefinitionTable()

        define(table, "f", 3, defaults = 2)

        assertEntries(
            table,
            "f/3 def line 1 clauses 1 defaults 2",
            "f/1 def line 1 clauses 1 default unchecked",
            "f/2 def line 1 clauses 1 default unchecked",
        )
    }

    @Test
    fun `a bodiless head stores no clauses`() {
        val table = DefinitionTable()

        define(table, "f", 2, clauses = 0, defaults = 1)
        define(table, "f", 2, line = 2)

        assertEntries(table, "f/2 def line 1 clauses 1 defaults 1", "f/1 def line 1 clauses 1 default unchecked")
    }

    @Test
    fun `later clauses keep the first node and add their clauses`() {
        val table = DefinitionTable()
        val first = node(1)

        table.define("f", 1, Kind.DEF, first, 1, 0, ordered = true, checksClauses = true)
        define(table, "f", 1, line = 3)

        assertEntries(table, "f/1 def line 1 clauses 2")
        assertEquals(first, table[NameArity("f", 1)]!!.at)
    }

    @Test
    fun `whether clauses are checked is the last clause's`() {
        val table = DefinitionTable()

        define(table, "f", 1, clauses = 0)
        define(table, "f", 1, clauses = 0, line = 2, checksClauses = false)
        define(table, "g", 1, clauses = 0, line = 3, checksClauses = false)
        define(table, "g", 1, clauses = 0, line = 4)

        assertEntries(table, "f/1 def line 1 clauses 0 unchecked", "g/1 def line 3 clauses 0")
    }

    @Test
    fun `an unordered clause makes its definition unordered`() {
        val table = DefinitionTable()

        define(table, "f", 1)
        define(table, "f", 1, line = 3, ordered = false)
        define(table, "g", 1, line = 5)

        assertEntries(table, "f/1 def line 1 clauses 2 unordered", "g/1 def line 5 clauses 1")
    }

    @Test
    fun `an unnamed definition is listed apart`() {
        val table = DefinitionTable()

        assertEquals(null, table.define(null, 1, Kind.DEFP, node(1), 1, 1, ordered = false, checksClauses = true))
        assertEntries(table)
        assertEquals(listOf(Kind.DEFP), table.unnamed)
    }

    @Test
    fun `kinds are each entry's kind`() {
        val table = DefinitionTable()

        define(table, "f", 1, defaults = 1)
        define(table, "m", 0, Kind.DEFMACROP, line = 2)

        assertEquals(
            mapOf(NameArity("f", 1) to Kind.DEF, NameArity("f", 0) to Kind.DEF, NameArity("m", 0) to Kind.DEFMACROP),
            table.kinds,
        )
    }

    // The definition-time checks

    @Test
    fun `a definition of another kind`() {
        val table = DefinitionTable()

        define(table, "f", 0)

        assertEquals("changed_kind", define(table, "f", 0, Kind.DEFP, line = 2))
        assertEntries(table, "f/0 def line 1 clauses 1")
    }

    @Test
    fun `defaults given twice`() {
        val table = DefinitionTable()

        define(table, "f", 1, defaults = 1)

        assertEquals("duplicate_defaults", define(table, "f", 1, line = 2, defaults = 1))
    }

    @Test
    fun `an arity in an earlier definition's defaults`() {
        val table = DefinitionTable()

        define(table, "f", 2, defaults = 1)

        assertEquals("defs_with_defaults", define(table, "f", 1, line = 2))
    }

    @Test
    fun `defaults covering an earlier definition's arity, which has defaults of its own`() {
        val table = DefinitionTable()

        define(table, "f", 2, defaults = 1)

        assertEquals("defs_with_defaults", define(table, "f", 4, line = 2, defaults = 2))
    }

    @Test
    fun `a default arity of another kind`() {
        val table = DefinitionTable()

        define(table, "f", 1)

        assertEquals("changed_kind", define(table, "f", 2, Kind.DEFP, line = 2, defaults = 1))
    }

    @Test
    fun `defaults are checked before the kind`() {
        val table = DefinitionTable()

        define(table, "f", 2, defaults = 1)

        assertEquals("defs_with_defaults", define(table, "f", 1, Kind.DEFP, line = 2))
    }

    @Test
    fun `defaults are checked before they are given twice`() {
        val table = DefinitionTable()

        define(table, "f", 3, defaults = 2)

        assertEquals("defs_with_defaults", define(table, "f", 2, line = 2, defaults = 1))
    }

    @Test
    fun `defaults after a definition of one of their arities`() {
        val table = DefinitionTable()

        define(table, "f", 1)

        assertEquals(null, define(table, "f", 2, line = 2, defaults = 1))
    }

    @Test
    fun `defaults covering an arity with no defaults of its own`() {
        val table = DefinitionTable()

        define(table, "f", 1)
        define(table, "g", 0, line = 2)

        assertEquals(null, define(table, "f", 3, line = 3, defaults = 1))
    }

    @Test
    fun `another clause of a definition with defaults`() {
        val table = DefinitionTable()

        define(table, "f", 2, defaults = 1)

        assertEquals(null, define(table, "f", 2, line = 2))
    }

    private fun define(
        table: DefinitionTable,
        name: String,
        arity: Int,
        kind: Kind = Kind.DEF,
        line: Int = 1,
        clauses: Int = 1,
        defaults: Int = 0,
        ordered: Boolean = true,
        checksClauses: Boolean = true,
    ): String? = table.define(name, arity, kind, node(line), clauses, defaults, ordered, checksClauses)

    private fun node(line: Int): ElixirAst {
        val position = Meta.Position(line, 3)

        return ElixirAst.Literal.Atom(
            Meta(TextRange(0, 0), position, position, listOf(Meta.Key.Location(position))),
            "def",
        )
    }

    private fun assertEntries(table: DefinitionTable, vararg expected: String) =
        assertEquals(
            expected.joinToString("\n"),
            table.entries.entries.joinToString("\n") { (key, entry) ->
                "${key.name}/${key.arity} ${entry.kind.name.lowercase()} line ${entry.line} clauses ${entry.clauses}" +
                    (if (entry.defaults > 0) " defaults ${entry.defaults}" else "") +
                    (if (entry.default) " default" else "") +
                    (if (entry.ordered) "" else " unordered") +
                    (if (entry.checksClauses) "" else " unchecked")
            },
        )
}
