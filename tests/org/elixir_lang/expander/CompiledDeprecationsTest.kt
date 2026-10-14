package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.NameArity
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.ReadResult
import java.io.File

/**
 * What [CompiledDeprecations] reads from each `.beam` in every app of the leg's Elixir and OTP, against what
 * `__info__(:deprecated)` gives once Elixir loads the module, or none for a module without `__info__/1`.
 */
class CompiledDeprecationsTest : ProbeTestCase() {
    fun testEveryModuleDeprecatesWhatElixirLoads() {
        val modules = appBeams.keys.sorted()
        val probed = harness.probeLoaded(
            modules,
            """
            if function_exported?(m, :__info__, 1) do
              try do
                m.__info__(:deprecated)
              rescue
                FunctionClauseError -> :raised
              end
            else
              []
            end
            """.trimIndent(),
        )
        val compared = modules.filter { probed[it] != null && it != BOOTSTRAP }

        assertEquals(
            "modules compared (the node loads no ${probed.filterValues { it == null }.keys})",
            compared.map { "$it: ${shown(probed.getValue(it)!!)}" },
            compared.map { "$it: ${shown(read(it))}" },
        )
        assertTrue("every Elixir app's and OTP's modules are compared: ${compared.size}", "Elixir.Logger" in compared)
        assertEquals(
            "Enum.chunk/2",
            "Use Enum.chunk_every/2 instead",
            (read("Elixir.Enum") as Deprecations.Known).reasons[NameArity("chunk", 2)],
        )
    }

    /** `elixir_bootstrap` writes its own `__info__/1`, which has no `deprecated` clause, so Elixir's call raises. */
    fun testAHandWrittenInfoWithNoDeprecatedClauseIsUnreadable() {
        val probed = harness.probeLoaded(
            listOf(BOOTSTRAP),
            "try do\n  m.__info__(:deprecated)\nrescue\n  FunctionClauseError -> :raised\nend",
        )

        assertEquals("Elixir's call", OtpErlangAtom("raised"), probed.getValue(BOOTSTRAP))
        assertEquals(Deprecations.Unreadable, read(BOOTSTRAP))
    }

    /** Each way `'__info__'/1`'s `deprecated` clause returns its list is met and read. */
    fun testEachShapeIsRead() {
        val shapes = appBeams.keys.mapNotNull { module ->
            (reading(module) { CompiledDeprecations.read(it) } as? CompiledDeprecations.Read.Found)?.shape
        }.toSet()

        assertEquals(CompiledDeprecations.Shape.entries.toSet(), shapes)
    }

    /**
     * `__info__(:deprecated)` lists a function once for each of its clauses' reasons, sorted, as
     * `[{{:old, 1}, "a"}, {{:old, 1}, "b"}]`, and a call in a module body reports the first, which
     * `elixir_dispatch:check_deprecated/6` finds with `lists:keyfind/3`.
     */
    fun testTheFirstOfAFunctionsReasonsIsRead() {
        val beam = File("testData/org/elixir_lang/expander/clause_reasons/Elixir.ClauseReasons.beam")

        assertEquals(
            Deprecations.Known(mapOf(NameArity("old", 1) to "a")),
            (BeamReader.readResult(beam.readBytes(), beam.path) { CompiledDeprecations.of(it) } as ReadResult.Present).value,
        )
    }

    private fun read(module: String): Deprecations = reading(module) { CompiledDeprecations.of(it) }

    private fun <T : Any> reading(module: String, block: (BeamReader) -> T): T {
        val beam = appBeams.getValue(module)

        return (BeamReader.readResult(beam.readBytes(), beam.path, block) as ReadResult.Present).value
    }

    /** [deprecations] as `name/arity: reason` lines, sorted. */
    private fun shown(deprecations: Deprecations): String =
        when (deprecations) {
            is Deprecations.Known -> deprecations.reasons.map { (nameArity, reason) -> "${nameArity.name}/${nameArity.arity}: $reason" }
                .sorted()
                .toString()
            Deprecations.Unreadable -> "unreadable"
        }

    /** What `__info__(:deprecated)` gave, as [shown] shows a read. */
    private fun shown(probed: OtpErlangObject): String =
        when (probed) {
            is OtpErlangList -> probed.elements().map {
                val (nameArity, reason) = (it as OtpErlangTuple).elements()
                val (name, arity) = (nameArity as OtpErlangTuple).elements()

                "${(name as OtpErlangAtom).atomValue()}/${(arity as OtpErlangLong).intValue()}: " +
                    String((reason as OtpErlangBinary).binaryValue(), Charsets.UTF_8)
            }.sorted().toString()
            else -> "$probed"
        }

    private companion object {
        const val BOOTSTRAP = "elixir_bootstrap"
    }
}
