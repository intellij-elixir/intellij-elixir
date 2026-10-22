package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangPid
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.expander.interpreter.Declined
import org.elixir_lang.expander.interpreter.compare
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A decline names the terms it declines on, but no more of them than a reader takes in. */
class DeclineTest {
    @Test
    fun aDeclineNamesOnlyTheTopOfALargeTerm() {
        val tree = (1..DOUBLINGS).fold<Int, OtpErlangObject>(OtpErlangList()) { t, _ -> OtpErlangList(arrayOf(t, t)) }
        val pair = { pid: Int -> OtpErlangTuple(arrayOf(tree, OtpErlangPid("nonode@nohost", pid, 0, 0))) }

        // Only the VM orders two pids, so the comparison declines once it reaches them.
        val reason = assertThrows(Declined::class.java) { compare(pair(1), pair(2), ElixirLanguageLevel.of("1.20.4")) }.reason

        assertTrue("${reason.length} chars: ${reason.take(80)}", reason.startsWith("compares {[[") && reason.length < 1_000)
    }

    companion object {
        /** A list built in this many steps that holds 2^this lists. */
        const val DOUBLINGS = 12
    }
}
