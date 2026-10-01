package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.lowering.inspect

/** [Quoted] values as `inspect` prints them. */
internal object QuotedTerms {
    fun inspect(quoted: Quoted): String = inspect(otp(quoted))

    private fun otp(quoted: Quoted): OtpErlangObject =
        when (quoted) {
            is Quoted.Atom -> OtpErlangAtom(quoted.name)
            is Quoted.Integer -> OtpErlangLong(quoted.value)
            is Quoted.Float -> OtpErlangDouble(quoted.value)
            is Quoted.Binary -> OtpErlangBinary(quoted.bytes)
            is Quoted.List -> OtpErlangList(quoted.elements.map(::otp).toTypedArray())
            is Quoted.Tuple -> OtpErlangTuple(quoted.elements.map(::otp).toTypedArray())
            Quoted.Unknown -> OtpErlangAtom("<unknown>")
        }
}
