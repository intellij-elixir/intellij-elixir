package org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.function

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.SyntaxTraverser
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Attributes
import org.elixir_lang.beam.chunk.debug_info.v1.erl_abstract_code.abstract_code_compiler_options.abstract_code.Function
import org.elixir_lang.beam.decompiler.Options
import org.elixir_lang.onSmallStack

class ClauseTest : PlatformTestCase() {
    fun testInlineFallbackIsParsable() {
        val fallback = "..."

        val file = myFixture.configureByText(
            "hex_pb_package.ex",
            """
            defmodule :hex_pb_package do
              def get_msg_defs(), do: $fallback
            end
            """.trimIndent()
        )

        val hasPsiErrors = SyntaxTraverser.psiTraverser(file)
            .traverse()
            .filter(PsiErrorElement::class.java)
            .isNotEmpty

        assertFalse("Expected no PsiErrorElement for inline fallback", hasPsiErrors)
    }

    fun testABodyTooDeepToRenderFallsBackToItsHead() {
        val rendered = onSmallStack { deepFunction(100_000).toMacroString(Options(decompileBodies = true)) }

        assertEquals("def deep(), do: ...", rendered)
    }

    fun testAShallowBodyIsRendered() {
        val rendered = onSmallStack { deepFunction(3).toMacroString(Options(decompileBodies = true)) }

        assertEquals("def deep(), do: {{{1}}}", rendered)
    }

    /** `deep() -> {{...{1}...}}.`, nested [depth] tuples deep. */
    private fun deepFunction(depth: Int): Function {
        var body: OtpErlangObject = tuple(OtpErlangAtom("integer"), line(), OtpErlangLong(1))
        repeat(depth) { body = tuple(OtpErlangAtom("tuple"), line(), OtpErlangList(body)) }
        val clause = tuple(OtpErlangAtom("clause"), line(), OtpErlangList(), OtpErlangList(), OtpErlangList(body))
        val function = tuple(OtpErlangAtom("function"), line(), OtpErlangAtom("deep"), OtpErlangLong(0), OtpErlangList(clause))

        return Function(function, Attributes(emptyList()))
    }

    private fun line(): OtpErlangLong = OtpErlangLong(1)

    private fun tuple(vararg elements: OtpErlangObject): OtpErlangTuple = OtpErlangTuple(elements)
}
