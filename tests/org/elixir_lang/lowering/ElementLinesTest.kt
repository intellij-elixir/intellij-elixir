package org.elixir_lang.lowering

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.io.FileUtil
import org.elixir_lang.intellij_elixir.Quoter
import org.elixir_lang.parser_definition.ParsingTestCase
import org.elixir_lang.psi.Quotable
import java.io.File

/** [ElementLowering]'s lines against the quoter's, after newlines an older tokenizer leaves uncounted. */
class ElementLinesTest : ParsingTestCase() {
    fun testUncountedNewlines() {
        val text = FileUtil.loadFile(File("testData/org/elixir_lang/lowering/UncountedNewlines.ex"), Charsets.UTF_8.name(), true).trim()
        val reply = Quoter.quote(text)!!
        assertEquals(reply.toString(), OtpErlangAtom("ok"), reply.elementAt(0))
        val expected = ((reply.elementAt(1) as OtpErlangTuple).elementAt(2) as OtpErlangList).elements().toList()
        val file = createPsiFile("UncountedNewlines", text)

        val lowered = ReadAction.computeBlocking<List<Any>, Throwable> {
            file.children.filterIsInstance<Quotable>().map(ElementLowering::quote)
        }

        assertEquals(expected, lowered)
    }
}
