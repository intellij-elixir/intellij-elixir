package org.elixir_lang.expander

import org.elixir_lang.NameArity
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.ReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [CompiledDefaults] read from each `.beam` of the leg's Elixir apps, and from one compiled without debug info. */
class CompiledDefaultsTest {
    /** Wherever a module has both, its debug info gives each definition `Docs` lists the defaults `Docs` gives. */
    @Test
    fun `debug info gives what Docs gives`() {
        val read = appBeams.keys.sorted().flatMap { module ->
            reading(appBeams.getValue(module)) { reader ->
                val docs = CompiledDefaults.fromDocs(reader)
                val debugInfo = CompiledDefaults.fromDebugInfo(reader)

                listOfNotNull(
                    if (docs != null && debugInfo != null) Triple(module, docs, debugInfo.filterKeys { it in docs }) else null,
                )
            }
        }

        assertTrue("Elixir.Enum is compared", read.any { it.first == "Elixir.Enum" })
        assertEquals(
            read.flatMap { (module, docs, _) -> shown(module, docs) },
            read.flatMap { (module, _, debugInfo) -> shown(module, debugInfo) },
        )
    }

    /** `Enum.max/1` is `max/2` or `max/3` with its defaults, depending on the release. */
    @Test
    fun `Enum max's defaults cover arity 1`() {
        val defaults = reading(appBeams.getValue("Elixir.Enum")) { CompiledDefaults.of(it).orEmpty() }

        assertTrue(
            "$defaults",
            defaults.any { (nameArity, count) -> nameArity.name == "max" && nameArity.arity > 1 && nameArity.arity - count <= 1 },
        )
    }

    @Test
    fun `a module compiled without debug info reads Docs`() {
        val beam = File("testData/org/elixir_lang/expander/compiled_module/docs_only/Elixir.DocsOnly.beam")

        assertEquals(mapOf(NameArity("f", 2) to 1), reading(beam) { CompiledDefaults.of(it).orEmpty() })
    }

    private fun <T : Any> reading(beam: File, block: (BeamReader) -> T): T =
        (BeamReader.readResult(beam.readBytes(), beam.path, block) as ReadResult.Present).value

    /** [defaults]' entries that cover an arity, as `module name/arity: count` lines, sorted. */
    private fun shown(module: String, defaults: Map<NameArity, Int>): List<String> =
        defaults.filterValues { it > 0 }.map { (nameArity, count) -> "$module ${nameArity.name}/${nameArity.arity}: $count" }.sorted()
}
