package org.elixir_lang.coverage

import com.intellij.openapi.util.io.FileUtil
import org.elixir_lang.coverage.MacroCensus.Side
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.elixir_surface.LegManifest.environment
import org.elixir_lang.junit.UnitTestCase
import java.io.File

/**
 * Pins the macro census over a small project with a path dependency: which macros the compiler dispatches, what the
 * expander's summaries leave Opaque, and the library, side and column each is counted under.
 */
class MacroCensusTest : UnitTestCase() {
    fun testReportMatchesTheCommittedReport() {
        val elixirVersion = environment("ELIXIR_VERSION")

        LegManifest.assertMatchesFile(
            ROOT,
            elixirVersion,
            "fixture.report",
            census.report(),
            LegManifest.regenerateCommand(MacroCensusTest::class, elixirVersion, environment("ERLANG_VERSION"))
        )
    }

    fun testMixProjectFilesAreNotCounted() {
        assertEquals(emptyList<MacroCensus.Event>(), census.events.filter { it.macro == "Mix.Project.__using__/1" })
        assertEquals(emptyList<MacroCensus.Event>(), census.events.filter { it.file == "mix.exs" })
    }

    fun testOpaqueIsDecidedBySummaries() {
        val expected = mapOf(
            "CensusDep.__using__/1" to true,
            "CensusDep.wrap/1" to true,
            "CensusFixture.twice/1" to true,
            "Kernel.use/1" to false,
            "Kernel.def/2" to false,
            "Kernel.defmacro/2" to false,
            "Kernel.defmacrop/2" to false,
        )

        assertEquals(expected, census.events.associate { it.macro to it.isOpaque }.filterKeys(expected::containsKey))
    }

    fun testLibraryIsTheReceiversApplication() {
        assertEquals("census_dep", only("CensusDep.wrap/1").library)
        assertEquals("census_dep", only("CensusDep.__using__/1").library)
        assertEquals("census_fixture", only("CensusFixture.twice/1").library)
    }

    fun testDependencyDispatchesAreCountedUnderTheDependency() {
        val inDependency = census.events.filter { it.macro == "Kernel.defmacro/2" }

        assertEquals(listOf("lib/census_dep.ex" to 2, "lib/census_dep.ex" to 8), inDependency.map { it.file to it.line }.sortedBy { it.second })
        assertEquals(listOf(Side.DEP, Side.DEP), inDependency.map { it.side })
        assertEquals(listOf("census_dep", "census_dep"), inDependency.map { it.app })
    }

    fun testEventsAreSplitByTheirColumn() {
        if (!census.splitsByColumn) {
            assertTrue(census.report().startsWith("# Elixir ${environment("ELIXIR_VERSION")}\tcensus_fixture\tno column split\n"))

            return
        }

        assertEquals("7:33", only("CensusDep.wrap/1").position)
        assertEquals("8:23", only("CensusFixture.twice/1").position)
        assertNull(only("CensusDep.__using__/1").column)
        assertNull(census.events.single { it.macro == "Kernel.if/2" }.column)
    }

    /** `defguard` in a dependency builds its `Kernel.Utils.defguard/2` call with no line. */
    fun testAnEventWithoutALineIsRead() {
        val event = MacroCensus.event("remote_macro\tElixir.Kernel.Utils\tdefguard\t2\tlib/mint/http.ex\tnone\tnone\tmint\telixir\tfalse\ttrue")

        assertNull(event.line)
        assertNull(event.column)
        assertEquals("Kernel.Utils.defguard/2", event.macro)
    }

    fun testAnErlangReceiverIsNamedAsElixirInspectsIt() {
        val event = MacroCensus.event("imported_macro\telixir_bootstrap\tdef\t2\tlib/a.ex\t1\t3\ta\telixir\ttrue\ttrue")

        assertEquals(":elixir_bootstrap.def/2", event.macro)
    }

    fun testProjectIsLeftUntouched() {
        val before = listing()

        MacroCensus.run(PROJECT)

        assertEquals(before, listing())
        assertEquals(
            listOf(
                "census_dep/lib/census_dep.ex",
                "census_dep/mix.exs",
                "census_fixture/lib/census_fixture.ex",
                "census_fixture/mix.exs",
                "census_umbrella/apps/census_child/lib/census_child.ex",
                "census_umbrella/apps/census_child/mix.exs",
                "census_umbrella/mix.exs"
            ),
            before.keys.toList()
        )
    }

    /** Each child of an umbrella compiles in its own directory, so its macros would be counted as a dependency's. */
    fun testAnUmbrellaRootIsRefused() {
        val failure = runCatching { MacroCensus.run(File("$FIXTURES/census_umbrella")) }.exceptionOrNull()

        assertTrue("$failure", failure is IllegalStateException)
        assertTrue(failure!!.message, failure.message!!.startsWith("census_umbrella is an umbrella project"))
    }

    fun testAnAmbientBuildPathIsNotFollowed() {
        val ambient = FileUtil.createTempDirectory("ambient-build", null)

        try {
            MacroCensus.run(PROJECT, parentEnvironment = System.getenv() + ("MIX_BUILD_PATH" to ambient.path))

            assertEquals(emptyList<String>(), ambient.list()!!.toList())
        } finally {
            FileUtil.delete(ambient)
        }
    }

    private val census: MacroCensus get() = cached

    private fun only(macro: String): MacroCensus.Event = census.events.single { it.macro == macro }

    private val MacroCensus.Event.position: String get() = "$line:$column"

    private fun listing(): Map<String, String> =
        File(FIXTURES).walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(File(FIXTURES)).invariantSeparatorsPath to it.readText() }
            .toSortedMap()

    private companion object {
        const val ROOT = "testData/org/elixir_lang/coverage/census"
        const val FIXTURES = "testData/org/elixir_lang/coverage/fixture"
        val PROJECT = File("$FIXTURES/census_fixture")

        val cached: MacroCensus by lazy { MacroCensus.run(PROJECT) }
    }
}
