package org.elixir_lang.beam.term

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangString
import org.elixir_lang.junit.UnitTestCase

class InspectTest : UnitTestCase() {
    fun testEscapesInterpolationMarkers() {
        assertEquals("'abc\\#{value}'", "'abc#{value}'".elixirEscape())
    }

    fun testPreservesEscapedInterpolationMarkers() {
        assertEquals("'abc\\#{value}'", "'abc\\#{value}'".elixirEscape())
    }

    fun testEscapesInterpolationAfterEscapedNewline() {
        assertEquals("'line\\n\\#{value}'", "'line\n#{value}'".elixirEscape())
    }

    // Backslash in Erlang charlist / string literal must be escaped so that
    // the decompiled output is valid Elixir.  A single `\` in an OtpErlangString
    // previously rendered as `'\'` (unterminated), now must render as `'\\'`.
    fun testEscapesBackslashInCharlist() {
        // OtpErlangString("\\") is a string containing one backslash.
        // inspect() must render it as '\\' (4 chars: ' \ \ ')
        assertEquals("'\\\\'", inspect(OtpErlangString("\\")))
    }

    fun testEscapesSingleQuoteInCharlist() {
        // OtpErlangString("'") is a string containing one single quote.
        // inspect() must render it as '\'' (4 chars: ' \ ' ')
        assertEquals("'\\''", inspect(OtpErlangString("'")))
    }

    fun testEscapesBackslashInAtomName() {
        assertEquals(":\"\\\\\"", inspect(OtpErlangAtom("\\")))
    }

    // Atoms starting with "Elixir." but containing invalid alias characters
    // (e.g. :"Benchfella:tests" which is atom 'Elixir.Benchfella:tests' in Erlang)
    // must be rendered as the whole quoted atom, not bare aliases.
    fun testAtomWithColonInElixirPrefix() {
        assertEquals(":\"Elixir.Benchfella:tests\"", inspect(OtpErlangAtom("Elixir.Benchfella:tests")))
    }

    fun testValidElixirModuleAtom() {
        assertEquals("Benchfella", inspect(OtpErlangAtom("Elixir.Benchfella")))
    }
}
