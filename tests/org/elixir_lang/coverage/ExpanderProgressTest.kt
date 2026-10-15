package org.elixir_lang.coverage

import com.intellij.openapi.util.TextRange
import org.elixir_lang.Module
import org.elixir_lang.coverage.MacroCensus.Side
import org.elixir_lang.junit.UnitTestCase
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.lowering.Meta

/**
 * Pins what the expander progress report says about a stop its fixture has no stable shape for, and that every row of
 * it stays one line of the columns it has.
 */
class ExpanderProgressTest : UnitTestCase() {
    fun testACallbackStopNamesItsModuleAndFunction() {
        assertEquals("Progress.Callback.__before_compile__/1", ExpanderProgress.shape(remote("Elixir.Progress.Callback")))
        assertEquals(":erlang.__before_compile__/1", ExpanderProgress.shape(remote("erlang")))
    }

    fun testAModuleNamedWithATabOrNewlineStaysInItsColumns() {
        val module = Module.indexName("a\tb\nc\\d")
        val report = ExpanderProgress(
            "1.20.4",
            "progress",
            0,
            listOf(
                ExpanderProgress.ModuleRow(
                    Side.APP,
                    "progress",
                    "lib/x.ex",
                    module,
                    1,
                    0,
                    ExpanderProgress.Kind.STOPPED,
                    ExpanderProgress.Stop("unported a\tb/1", 3),
                )
            ),
            emptyList(),
            emptyMap(),
        ).report()

        assertEquals(
            listOf("module\tapp\tprogress\tlib/x.ex\t:a\\tb\\nc\\\\d\t1\t0\tstopped\tunported a\\tb/1\t3"),
            report.lines().filter { it.startsWith("module\t") }
        )
        assertTrue(report.lines().filter { it.startsWith("module\t") }.all { it.split('\t').size == 10 })
    }

    private fun remote(receiver: String): ElixirAst.Call {
        val meta = Meta(TextRange(0, 0), Meta.Position(1, 1), Meta.Position(1, 1))
        val atom = { name: String -> ElixirAst.Literal.Atom(meta, name) }

        return ElixirAst.Call(
            meta,
            ElixirAst.Call(meta, atom("."), listOf(atom(receiver), atom("__before_compile__"))),
            listOf(ElixirAst.Call(meta, atom("__ENV__"), null)),
        )
    }
}
