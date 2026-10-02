package org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.`fun`

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Scope
import org.elixir_lang.junit.UnitTestCase

/** An Erlang `fun` capture is written with the name spelt as Elixir spells a call of that kind. */
class FunctionTest : UnitTestCase() {
    /** Erlang: `fun erlang:'=:='/2` */
    fun testRemoteCaptureQuotesTheNameAsARemoteCall() =
        assertEquals("&:erlang.\"=:=\"/2", string(function(atom("erlang"), atom("=:="), integer(2))))

    /** Erlang: `fun 'after'/1` */
    fun testLocalCaptureOfAReservedWord() =
        assertEquals("&unquote(:after)/1", string(function(OtpErlangAtom("after"), OtpErlangLong(1))))

    private fun string(term: OtpErlangTuple): String = Function.toMacroString(term, Scope.EMPTY).string

    private fun function(vararg elements: OtpErlangObject) =
        OtpErlangTuple(arrayOf<OtpErlangObject>(OtpErlangAtom("function"), *elements))

    private fun atom(name: String) = OtpErlangTuple(arrayOf(OtpErlangAtom("atom"), OtpErlangLong(0), OtpErlangAtom(name)))

    private fun integer(value: Long) =
        OtpErlangTuple(arrayOf(OtpErlangAtom("integer"), OtpErlangLong(0), OtpErlangLong(value)))
}
