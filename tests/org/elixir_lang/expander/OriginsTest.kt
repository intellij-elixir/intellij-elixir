package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.util.concurrency.AppExecutorUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.expander.interpreter.COPY_NODES
import org.elixir_lang.lowering.Aligned
import org.elixir_lang.lowering.ElixirAst
import java.util.concurrent.TimeUnit

class OriginsTest : PlatformTestCase() {
    /** A term and a source of 40 levels, each level both elements of its pair, hold 40 pairs of parts along 2^40 paths. */
    fun testATermFitsASourceReadingEachPairOfSharedPartsOnce() {
        val origins = Origins()
        val fragment = call(OtpErlangAtom("unquote"), OtpErlangAtom("a"))
        val node = ElixirAst.fromOtp(OtpErlangAtom("foo"), TextRange(1, 2))!!

        origins.record(node, call(OtpErlangAtom("foo"), shared(fragment)))

        val indicator = EmptyProgressIndicator()
        val cancellation = AppExecutorUtil.getAppScheduledExecutorService().schedule(indicator::cancel, 10, TimeUnit.SECONDS)
        var found: Aligned? = null

        try {
            ProgressManager.getInstance().runProcess(
                { found = origins.look(call(OtpErlangAtom("foo"), shared(OtpErlangAtom("b"))), null) },
                indicator,
            )
        } finally {
            cancellation.cancel(false)
        }

        assertFalse("look did not return within 10 seconds", indicator.isCanceled)
        assertTrue("found $found", found is Aligned.Spine)
    }

    /** A value's source nodes are copied per place, so each place counts: `Enum.duplicate/2` of a body holds it that often. */
    fun testASourceNodeCountsTowardsTheLimitAtEachPlace() {
        val origins = Origins()
        val term = call(OtpErlangAtom("foo"), OtpErlangAtom("a"))

        origins.record(ElixirAst.fromOtp(term, TextRange(1, 2))!!, term)

        val many = OtpErlangList(Array<OtpErlangObject>(COPY_NODES / 2) { term })

        assertNull(ElixirAst.fromOtp(many, TextRange(0, 1), values = true, known = { null }, COPY_NODES, origins, true, null))
    }

    private fun shared(leaf: OtpErlangObject): OtpErlangObject =
        (1..40).fold(leaf) { part, _ -> OtpErlangTuple(arrayOf(part, part)) }

    private fun call(head: OtpErlangAtom, argument: OtpErlangObject): OtpErlangTuple =
        OtpErlangTuple(arrayOf(head, OtpErlangList(), OtpErlangList(arrayOf(argument))))
}
