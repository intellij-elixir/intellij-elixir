package org.elixir_lang.beam.chunk.debug_info

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.decompiler.ReservedTypeName
import org.elixir_lang.beam.psi.BeamFileImpl
import org.elixir_lang.beam.psi.stubs.ModuleStub
import org.elixir_lang.beam.psi.stubs.TypeDefinitionStub
import org.elixir_lang.expander.elixirString
import org.elixir_lang.expander.failure
import org.elixir_lang.expander.legBeams
import org.elixir_lang.expander.utf8
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.type.Visibility
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * The types each type stub set holds against `Code.Typespec.fetch_types/1` on the quoter node, for every `.beam` of the
 * leg's Elixir and OTP: the compiler's own reading of the same debug info.
 */
class TypeDefinitionsOracleTest : PlatformTestCase() {
    fun testEachBeamsTypeStubsAreTheTypesElixirFetchesFromIt() {
        val beams = legBeams().sortedBy { it.nameWithoutExtension }
        val compiled = Quoter.compile(fetchTypes(beams), 300.seconds)
        assertEquals("compile: ${compiled.failure}", OtpErlangAtom("ok"), compiled.status)

        val fetched = (compiled.messages.single() as OtpErlangList).elements().associate { entry ->
            val (path, answer) = (entry as OtpErlangTuple).elements().let { it[0] to it[1] }

            utf8(path) to answer
        }
        val mismatches = mutableListOf<String>()
        var elixirCompared = 0

        for (beam in beams) {
            val module = BeamFileImpl.buildFileStub(beam.readBytes(), beam.path)!!.childrenStubs.single() as ModuleStub<*>
            val expected = expected(fetched.getValue(beam.invariantSeparatorsPath))
            val actual = module.childrenStubs
                .filterIsInstance<TypeDefinitionStub<*>>()
                .map { Triple(it.visibility, it.name, it.arity) }
                .takeIf { module.typesRead() }
                ?.toSet()

            // `erlang` fakes the built-in types as its own: its answer is not the debug info's.
            if (beam.nameWithoutExtension == "erlang") continue

            if (expected == actual) {
                if (beam.name.startsWith("Elixir.")) elixirCompared++
            } else {
                mismatches += "${beam.name}: expected ${expected?.map(Any::toString)?.sorted()} but stubs are ${actual?.map(Any::toString)?.sorted()}"
            }
        }

        assertEmpty("beams whose type stubs differ from what Code.Typespec.fetch_types reads", mismatches.take(20))
        assertTrue("no Elixir beam was compared", elixirCompared > 0)
    }

    /** Null where the beam's types can't be read. */
    private fun expected(answer: OtpErlangObject): Set<Triple<Visibility, String, Int>>? =
        when (answer) {
            is OtpErlangAtom -> null
            else ->
                (answer as OtpErlangList)
                    .elements()
                    .map { type ->
                        val (kind, name, arity) = (type as OtpErlangTuple).elements()

                        Triple(visibility((kind as OtpErlangAtom).atomValue()), utf8(name), (arity as OtpErlangLong).intValue())
                    }
                    .filterNot { (_, name, _) -> ReservedTypeName.isReserved(name) }
                    .toSet()
        }

    private fun visibility(kind: String): Visibility =
        Visibility.entries.singleOrNull { it.moduleAttribute == kind } ?: throw AssertionError("unexpected type kind $kind")

    private companion object {
        /**
         * `[{path, :unread}]` for a beam whose debug info holds no abstract code with a `-module` form, else
         * `[{path, types}]`, each type `{kind, name, arity}`, as `Code.Typespec.fetch_types/1` answers.
         */
        fun fetchTypes(beams: List<File>): String = """
            paths = [${beams.joinToString(", ") { elixirString(it.invariantSeparatorsPath) }}]

            result =
              for path <- paths do
                binary = File.read!(path)

                forms =
                  with {:ok, {module, [debug_info: {:debug_info_v1, backend, data}]}} <-
                         :beam_lib.chunks(binary, [:debug_info]),
                       {:ok, forms} <- backend.debug_info(:erlang_v1, module, data, []) do
                    forms
                  else
                    _ -> []
                  end

                with true <- Enum.any?(forms, &match?({:attribute, _, :module, _}, &1)),
                     {:ok, types} <- Code.Typespec.fetch_types(binary) do
                  {path, for({kind, {name, _, args}} <- types, do: {kind, Atom.to_string(name), length(args)})}
                else
                  _ -> {path, :unread}
                end
              end

            IntellijElixir.Quoter.Probe.send(__ENV__, result)
        """.trimIndent()
    }
}
