package org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.attribute

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Attributes
import org.elixir_lang.beam.decompiler.Options
import org.elixir_lang.junit.UnitTestCase
import org.elixir_lang.otpErlangList

/** OTP 28's `-nominal` is a type attribute like `-type`: public where exported, else private. */
class NominalTypeTest : UnitTestCase() {
    fun testAnExportedNominalTypeIsAPublicType() {
        assertEquals(listOf("@type time :: non_neg_integer()"), rendered(exported = listOf("time")))
    }

    fun testANominalTypeThatIsNotExportedIsAPrivateType() {
        assertEquals(listOf("@typep time :: non_neg_integer()"), rendered(exported = emptyList()))
    }

    private fun rendered(exported: List<String>): List<String> {
        val forms = listOf(
            tuple("attribute", 1, "export_type", otpErlangList(exported.map { tuple(it, 0) })),
            tuple(
                "attribute",
                2,
                "nominal",
                tuple("time", tuple("type", 2, "non_neg_integer", otpErlangList()), otpErlangList()),
            ),
        )
        return Attributes.from(forms).types.map { it.toMacroString(Options()) }
    }

    private fun tuple(vararg elements: Any): OtpErlangTuple =
        OtpErlangTuple(
            elements.map { element ->
                when (element) {
                    is String -> OtpErlangAtom(element)
                    is Int -> OtpErlangLong(element.toLong())
                    is OtpErlangObject -> element
                    else -> throw IllegalArgumentException("$element")
                }
            }.toTypedArray(),
        )
}
