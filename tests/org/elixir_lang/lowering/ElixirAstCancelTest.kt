package org.elixir_lang.lowering

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.expander.CancelAt
import org.elixir_lang.expander.whileCheckingIndicators

/** Reading a term into [ElixirAst] is cancelled as it walks the term; cancellation needs the application. */
class ElixirAstCancelTest : PlatformTestCase() {
    /** Reading a list that holds itself twice at every depth is cancelled as it reads it. */
    fun testFromOtpIsCancelledAsItReads() = assertCancelled { ElixirAst.fromOtp(tree(), TextRange(0, 0)) }

    /** Reading a call whose metadata holds such a list is cancelled as it reads the metadata. */
    fun testFromOtpIsCancelledAsItReadsMetadata() {
        val call = tuple(OtpErlangAtom("f"), list(tuple(OtpErlangAtom("k"), tree())), OtpErlangAtom("nil"))

        assertCancelled { ElixirAst.fromOtp(call, TextRange(0, 0)) }
    }

    /** A list built in [DOUBLINGS] steps that holds 2^[DOUBLINGS] lists, one list in many places. */
    private fun tree() = (1..DOUBLINGS).fold<Int, OtpErlangObject>(list()) { t, _ -> list(t, t) }

    /** [read] is cancelled before it has made half as many checks as the [tree] has lists. */
    private fun assertCancelled(read: () -> Unit) {
        val indicator = CancelAt((1 shl DOUBLINGS) / 2)

        try {
            whileCheckingIndicators { ProgressManager.getInstance().runProcess(read, indicator) }
            fail("no cancel at check ${(1 shl DOUBLINGS) / 2}, of ${indicator.checks}")
        } catch (_: ProcessCanceledException) {
        }
    }

    private fun list(vararg elements: OtpErlangObject) = OtpErlangList(elements)

    private fun tuple(vararg elements: OtpErlangObject) = OtpErlangTuple(elements)

    companion object {
        const val DOUBLINGS = 12
    }
}
