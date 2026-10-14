package org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.v1.definitions.definition

import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.MacroNameArity
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.V1
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.v1.definitions.Definition
import java.io.File

class DefaultArgumentClauseTest : PlatformTestCase() {
    fun testTheDecompiledClauseIsNamedAfterTheClauseItCalls() {
        assertEquals(listOf("term"), inspect1().renderedClauses()!!.single().parameters)
    }

    fun testTheDebugInfoClauseKeepsTheNamesTheBeamStores() {
        assertEquals(listOf("x0"), inspect1().clauses!!.single().parameters)
    }

    fun testDefaultsOfSmallIntegersAreInTheHead() {
        val beam = File(
            "testData/org/elixir_lang/beam/chunk/debug_info/v1/elixir_erl/v1/definitions/definition/" +
                "small_integer_defaults/Elixir.SmallIntegerDefaults.beam"
        )
        val nameArities = listOf("one" to 0, "one" to 1, "two" to 0, "two" to 1, "two" to 2)
        val heads = BeamReader.read(beam.readBytes(), beam.path) { reader ->
            val definitions = (reader.debugInfo as V1).definitions!!

            nameArities.associateWith { (name, arity) ->
                DefaultArgumentClause.head(definitions[MacroNameArity("def", name, arity)]!!)
            }
        }

        assertEquals(
            mapOf(
                ("one" to 0) to listOf("a \\\\ 1"),
                ("one" to 1) to listOf("a \\\\ 1"),
                ("two" to 0) to listOf("a \\\\ 1", "b \\\\ 2"),
                ("two" to 1) to listOf("a \\\\ 1", "b \\\\ 2"),
                ("two" to 2) to listOf("a \\\\ 1", "b \\\\ 2"),
            ),
            heads,
        )
    }

    fun testTheSdkDefaultOfASmallIntegerIsInTheHead() {
        val beam = File(System.getenv("ELIXIR_LANG_ELIXIR_PATH"), "lib/elixir/ebin/Elixir.IO.ANSI.beam")
        val head = BeamReader.read(beam.readBytes(), beam.path) { reader ->
            DefaultArgumentClause.head((reader.debugInfo as V1).definitions!![MacroNameArity("def", "cursor_up", 0)]!!)
        }

        assertEquals(listOf("lines \\\\ 1"), head)
    }

    private fun inspect1(): Definition {
        val beam = File("testData/org/elixir_lang/beam/decompiler/Docs/Elixir.Kernel.beam")

        return BeamReader.read(beam.readBytes(), beam.path) { reader ->
            (reader.debugInfo as V1).definitions!![MacroNameArity("def", "inspect", 1)]
        }!!
    }
}
