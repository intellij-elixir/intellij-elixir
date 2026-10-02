package org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.v1.definitions.definition

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamReader
import org.elixir_lang.beam.chunk.debug_info.Term
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.V1
import org.elixir_lang.beam.chunk.debug_info.v1.elixir_erl.v1.definitions.Definition
import org.elixir_lang.junit.logs.expectErrors
import java.io.File

/** The chunk viewer's signature of a clause whose definition has no macro kind it knows. */
class ClauseSignatureTest : PlatformTestCase() {
    fun testNameThatDoesNotParseBareIsUnquoted() {
        val definition = definitionWithoutMacro("after")

        assertEquals("unquote(:after)(x)", definition.clauses!!.single().signature)
    }

    private fun definitionWithoutMacro(name: String): Definition {
        lateinit var definition: Definition

        expectErrors(Term::class.java, Regex("macro is not an atom")) {
            definition = Definition(
                debugInfo(),
                tuple(OtpErlangAtom(name), OtpErlangLong(1)),
                OtpErlangLong(0),
                OtpErlangList(),
                OtpErlangList(
                    arrayOf<OtpErlangObject>(
                        tuple(OtpErlangList(), OtpErlangList(arrayOf<OtpErlangObject>(variable("x"))), OtpErlangList(), OtpErlangAtom("ok"))
                    )
                ),
            )
        }

        return definition
    }

    private fun debugInfo(): V1 {
        val beam = File("testData/org/elixir_lang/beam/decompiler/Docs/Elixir.Kernel.beam")

        return BeamReader.read(beam.readBytes(), beam.path) { reader -> reader.debugInfo as V1 }!!
    }

    private fun variable(name: String) = tuple(OtpErlangAtom(name), OtpErlangList(), OtpErlangAtom("nil"))

    private fun tuple(vararg elements: OtpErlangObject) = OtpErlangTuple(elements)
}
