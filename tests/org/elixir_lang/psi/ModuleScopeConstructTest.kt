package org.elixir_lang.psi

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangTuple
import com.ericsson.otp.erlang.OtpInputStream
import org.elixir_lang.beam.BeamBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Which of Elixir's own `do`-block forms run their block in the module body is a fact of Elixir, so every macro the
 * SDK documents for `Kernel` and `Kernel.SpecialForms` is classified here, and the forms classified as running their
 * block in place are exactly [CallDefinitionClause.BLOCK_IN_PLACE_FORMS]. A macro a new Elixir adds fails until it is
 * classified.
 */
class ModuleScopeConstructTest {
    private enum class Block { IN_PLACE, DEFINES, MODULE, QUOTES, NONE }

    @Test
    fun `every Kernel and SpecialForms macro is classified and the in-place forms are the ones module scope looks through`() {
        val documented = documentedMacros("Elixir.Kernel") + documentedMacros("Elixir.Kernel.SpecialForms")

        assertEquals("Classify in BLOCK_BY_MACRO:", "", (documented - BLOCK_BY_MACRO.keys).sorted().joinToString(" "))
        assertEquals(
            BLOCK_BY_MACRO.filterValues { it == Block.IN_PLACE }.keys.sorted(),
            CallDefinitionClause.BLOCK_IN_PLACE_FORMS.sorted()
        )
    }

    /** The names of the macros [module]'s `Docs` chunk documents. */
    private fun documentedMacros(module: String): Set<String> {
        val root = System.getenv("ELIXIR_LANG_ELIXIR_PATH")
        assertNotNull("ELIXIR_LANG_ELIXIR_PATH not set for the test JVM", root)
        val bytes = File(root, "lib/elixir/ebin/$module.beam").readBytes()
        val docs = BeamBytes.chunks(bytes).single { it.id == "Docs" }
        val docsV1 = OtpInputStream(bytes.copyOfRange(docs.data, docs.data + docs.size)).read_any() as OtpErlangTuple

        return (docsV1.elementAt(6) as OtpErlangList)
            .map { ((it as OtpErlangTuple).elementAt(0) as OtpErlangTuple) }
            .filter { (it.elementAt(0) as OtpErlangAtom).atomValue() == "macro" }
            .map { (it.elementAt(1) as OtpErlangAtom).atomValue() }
            .toSet()
    }

    private companion object {
        val BLOCK_BY_MACRO: Map<String, Block> =
            listOf("case", "cond", "for", "if", "receive", "try", "unless", "with").associateWith { Block.IN_PLACE } +
                listOf("def", "defp", "defmacro", "defmacrop").associateWith { Block.DEFINES } +
                listOf("defimpl", "defmodule", "defprotocol").associateWith { Block.MODULE } +
                mapOf("quote" to Block.QUOTES) +
                listOf(
                    // Kernel
                    "!", "&&", "..", "..//", "<>", "@", "alias!", "and", "binding", "dbg", "defdelegate", "defexception",
                    "defguard", "defguardp", "defoverridable", "defstruct", "destructure", "get_and_update_in",
                    "get_in", "in", "is_exception", "is_nil", "is_non_struct_map", "is_struct", "match?", "or",
                    "pop_in", "put_in", "raise", "reraise", "sigil_C", "sigil_D", "sigil_N", "sigil_R", "sigil_S",
                    "sigil_T", "sigil_U", "sigil_W", "sigil_c", "sigil_r", "sigil_s", "sigil_w", "tap", "then",
                    "to_char_list", "to_charlist", "to_string", "update_in", "use", "var!", "|>", "||",
                    // Kernel.SpecialForms
                    "%", "%{}", "&", ".", "::", "<<>>", "=", "^", "__CALLER__", "__DIR__", "__ENV__", "__MODULE__",
                    "__STACKTRACE__", "__aliases__", "__block__", "__cursor__", "alias", "fn", "import", "require",
                    "super", "unquote", "unquote_splicing", "{}",
                ).associateWith { Block.NONE }
    }
}
