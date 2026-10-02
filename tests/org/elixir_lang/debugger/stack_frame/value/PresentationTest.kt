package org.elixir_lang.debugger.stack_frame.value

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangMap
import com.ericsson.otp.erlang.OtpErlangObject
import org.elixir_lang.beam.chunk.XValueRenderer
import org.elixir_lang.junit.UnitTestCase

/** The debugger writes a value's atoms the way Elixir's `inspect/1` does. */
class PresentationTest : UnitTestCase() {
    fun testElixirPrefixedAtomThatIsNotAnAlias() = assertEquals(":\"Elixir.foo\"", text(OtpErlangAtom("Elixir.foo")))

    fun testCapitalisedAtom() = assertEquals(":DOWN", text(OtpErlangAtom("DOWN")))

    fun testKeywordKeyThatNeedsQuoting() = assertEquals(
        "%{\"foo bar\": 1}",
        text(OtpErlangMap(arrayOf<OtpErlangObject>(OtpErlangAtom("foo bar")), arrayOf<OtpErlangObject>(OtpErlangLong(1)))),
    )

    private fun text(term: OtpErlangObject): kotlin.String =
        XValueRenderer().also { Presentation(term).renderValue(it) }.getText()
}
