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
import org.elixir_lang.beam.type.VisibilityNameArity
import org.elixir_lang.expander.elixirString
import org.elixir_lang.expander.failure
import org.elixir_lang.expander.legBeams
import org.elixir_lang.expander.utf8
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.model.psi.type.TypeBuiltins
import org.elixir_lang.type.Visibility
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * The types each type stub set holds against `Code.Typespec.fetch_types/1` on the quoter node, for every `.beam` of the
 * leg's Elixir and OTP: the compiler's own reading of the same debug info, with the built-in types `erlang` stands for
 * added to its answer.
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
                ?.let { if (beam.nameWithoutExtension == "erlang") withUndeclaredBuiltins(it) else it }
            val actual = module.childrenStubs
                .filterIsInstance<TypeDefinitionStub<*>>()
                .map { VisibilityNameArity(it.visibility, it.name, it.arity) }
                .takeIf { module.typesRead() }
                ?.toSet()

            if (expected == actual) {
                if (beam.name.startsWith("Elixir.")) elixirCompared++
            } else {
                mismatches += "${beam.name}: expected ${expected?.toSortedSet()} but stubs are ${actual?.toSortedSet()}"
            }
        }

        assertEmpty("beams whose type stubs differ from what Code.Typespec.fetch_types reads", mismatches.take(20))
        assertTrue("no Elixir beam was compared", elixirCompared > 0)
    }

    /** Null where the beam's types can't be read. */
    private fun expected(answer: OtpErlangObject): Set<VisibilityNameArity>? =
        when (answer) {
            is OtpErlangAtom -> null
            else ->
                (answer as OtpErlangList)
                    .elements()
                    .map { type ->
                        val (kind, name, arity) = (type as OtpErlangTuple).elements()

                        VisibilityNameArity(visibility((kind as OtpErlangAtom).atomValue()), utf8(name), (arity as OtpErlangLong).intValue())
                    }
                    .filterNot { ReservedTypeName.isReserved(it.name) }
                    .toSet()
        }

    /** `erlang` stands for the built-in types: each pair its abstract code doesn't declare is a public type of it. */
    private fun withUndeclaredBuiltins(declared: Set<VisibilityNameArity>): Set<VisibilityNameArity> {
        val declaredPairs = declared.map { it.name to it.arity }.toSet()
        val undeclared =
            TypeBuiltins.BUILTIN_ARITY_BY_NAME
                .flatMap { (name, arities) -> arities.map { name to it } }
                .filterNot { (name, arity) -> ReservedTypeName.isReserved(name) || (name to arity) in declaredPairs }
                .map { (name, arity) -> VisibilityNameArity(Visibility.PUBLIC, name, arity) }

        return declared + undeclared
    }

    private fun visibility(kind: String): Visibility =
        Visibility.entries.singleOrNull { it.moduleAttribute == kind } ?: throw AssertionError("unexpected type kind $kind")

    private companion object {
        /**
         * Each `-nominal` form is as a `-type` one: public where the module exports it, else private. `[{path, :unread}]`
         * for a beam whose debug info holds no abstract code with a `-module` form, else
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

                exported = for {:attribute, _, :export_type, types} <- forms, type <- types, do: type

                # `fetch_types` reads `-nominal` only from Elixir 1.19, so the forms are read for it here.
                nominal =
                  for {:attribute, _, :nominal, {name, _, args}} <- forms do
                    {if({name, length(args)} in exported, do: :type, else: :typep), Atom.to_string(name), length(args)}
                  end

                with true <- Enum.any?(forms, &match?({:attribute, _, :module, _}, &1)),
                     {:ok, types} <- Code.Typespec.fetch_types(binary) do
                  fetched = for {kind, {name, _, args}} <- types, kind != :nominal, do: {kind, Atom.to_string(name), length(args)}

                  {path, fetched ++ nominal}
                else
                  _ -> {path, :unread}
                end
              end

            IntellijElixir.Quoter.Probe.send(__ENV__, result)
        """.trimIndent()
    }
}
