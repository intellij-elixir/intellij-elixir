package org.elixir_lang.expander

import com.intellij.openapi.util.TextRange
import org.elixir_lang.NameArity
import org.elixir_lang.expander.DefinitionTable.Kind
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta
import org.junit.Assert.assertEquals
import org.junit.Test

/** `Module.make_overridable/2`, `elixir_overridable:super/4` and `store_not_overridden/1` over a definition table. */
class OverridableTest {
    private val compiling = Compiling(node(0), ElixirLanguageLevel.of("1.20.4"))
    private val f = NameArity("f", 1)

    @Test
    fun `an overridable definition leaves the table`() {
        define("f", 1)
        define("g", 0)

        assertEquals(Overriding.RECORDED, makeOverridable(compiling, f))
        assertEntries("g/0 def line 1 clauses 1")
    }

    @Test
    fun `a definition that isn't there can't be made overridable`() {
        assertEquals(Overriding.NOT_DEFINED, makeOverridable(compiling, f))
    }

    @Test
    fun `each time it is made overridable counts one more`() {
        define("f", 1, line = 1)
        makeOverridable(compiling, f)
        assertEquals(1, compiling.overridable.getValue(f).count)

        define("f", 1, line = 5)
        assertEquals(Overriding.RECORDED, makeOverridable(compiling, f))

        val overridable = compiling.overridable.getValue(f)

        assertEquals(2, overridable.count)
        assertEquals(5, overridable.entry.line)
    }

    @Test
    fun `again as the other kind of definition is refused, after the definition is taken`() {
        define("f", 1)
        makeOverridable(compiling, f)
        define("f", 1, Kind.DEFMACRO)

        assertEquals(Overriding.BAD_KIND, makeOverridable(compiling, f))
        assertEquals(1, compiling.overridable.getValue(f).count)
        assertEntries()
    }

    @Test
    fun `private and public of one sort are the same kind`() {
        define("f", 1, Kind.DEFMACRO)
        makeOverridable(compiling, f)
        define("f", 1, Kind.DEFMACROP)

        assertEquals(Overriding.RECORDED, makeOverridable(compiling, f))
    }

    @Test
    fun `a function stores its hidden definition as defp`() {
        define("f", 1, line = 3, clauses = 2)
        makeOverridable(compiling, f)
        store(f)

        assertEntries("f (overridable 1)/1 defp line 3 clauses 2 unchecked")
    }

    @Test
    fun `a macro stores its hidden definition as defmacrop, named for the count`() {
        define("f", 1, Kind.DEFMACRO)
        makeOverridable(compiling, f)
        define("f", 1, Kind.DEFMACRO)
        makeOverridable(compiling, f)
        store(f)

        assertEntries("f (overridable 2)/1 defmacrop line 1 clauses 1 unchecked")
    }

    @Test
    fun `an overridable definition is stored once`() {
        define("f", 1)
        makeOverridable(compiling, f)
        store(f)
        store(f)

        assertEntries("f (overridable 1)/1 defp line 1 clauses 1 unchecked")
    }

    @Test
    fun `an overridable definition with defaults stores its defaults and not their arities`() {
        define("f", 2, defaults = 1)
        makeOverridable(compiling, NameArity("f", 2))
        store(NameArity("f", 2))

        assertEntries(
            "f/1 def line 1 clauses 1 default unchecked",
            "f (overridable 1)/2 defp line 1 clauses 1 defaults 1 unchecked",
        )
    }

    @Test
    fun `a definition not redefined is stored back as it was`() {
        define("f", 1, Kind.DEFP, line = 4, clauses = 2)
        makeOverridable(compiling, f)

        assertEquals(null, storeNotOverridden(compiling))
        assertEntries("f/1 defp line 4 clauses 2 unchecked")
    }

    @Test
    fun `a definition stored as super is not stored back`() {
        define("f", 1)
        makeOverridable(compiling, f)
        store(f)

        assertEquals(null, storeNotOverridden(compiling))
        assertEntries("f (overridable 1)/1 defp line 1 clauses 1 unchecked")
    }

    @Test
    fun `a definition redefined as the same sort is left`() {
        define("f", 1, line = 1)
        makeOverridable(compiling, f)
        define("f", 1, Kind.DEFP, line = 6)

        assertEquals(null, storeNotOverridden(compiling))
        assertEntries("f/1 defp line 6 clauses 1")
    }

    @Test
    fun `a definition redefined as the other sort is refused`() {
        define("f", 1)
        makeOverridable(compiling, f)
        val redefined = node(7)

        compiling.table.define("f", 1, Kind.DEFMACRO, redefined, 1, 0, ordered = true, checksClauses = true)

        val error = storeNotOverridden(compiling)

        assertEquals("bad_kind", error?.kind)
        assertEquals(redefined, error?.at)
    }

    private fun store(nameArity: NameArity) =
        storeOverridable(compiling, nameArity, compiling.overridable.getValue(nameArity), hidden = true)

    private fun define(
        name: String,
        arity: Int,
        kind: Kind = Kind.DEF,
        line: Int = 1,
        clauses: Int = 1,
        defaults: Int = 0,
    ) = compiling.table.define(name, arity, kind, node(line), clauses, defaults, ordered = true, checksClauses = true)

    private fun assertEntries(vararg expected: String) =
        assertEquals(
            expected.joinToString("\n"),
            compiling.table.entries.entries.joinToString("\n") { (key, entry) ->
                "${key.name}/${key.arity} ${entry.kind.name.lowercase()} line ${entry.line} clauses ${entry.clauses}" +
                    (if (entry.defaults > 0) " defaults ${entry.defaults}" else "") +
                    (if (entry.default) " default" else "") +
                    (if (entry.checksClauses) "" else " unchecked")
            },
        )

    private fun node(line: Int): ElixirAst {
        val position = Meta.Position(line, 3)

        return ElixirAst.Literal.Atom(
            Meta(TextRange(0, 0), position, position, listOf(Meta.Key.Location(position))),
            "def",
        )
    }
}
