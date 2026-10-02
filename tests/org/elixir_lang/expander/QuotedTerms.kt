package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangAtom
import com.ericsson.otp.erlang.OtpErlangBinary
import com.ericsson.otp.erlang.OtpErlangDouble
import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangLong
import com.ericsson.otp.erlang.OtpErlangObject
import com.ericsson.otp.erlang.OtpErlangString
import com.ericsson.otp.erlang.OtpErlangTuple
import org.elixir_lang.lowering.inspect

/** [Quoted] values as `inspect` prints them, and decoded from the terms JInterface gives. */
internal object QuotedTerms {
    fun inspect(quoted: Quoted): String = inspect(otp(quoted))

    /**
     * [term] as a [Quoted], with each atom renamed through [atoms] and each binary through [binaries]. A list of small
     * integers arrives as an [OtpErlangString], and is decoded as the list it is.
     */
    fun of(
        term: OtpErlangObject,
        atoms: (String) -> String = { it },
        binaries: (String) -> String = { it },
    ): Quoted =
        when (term) {
            is OtpErlangAtom -> Quoted.Atom(atoms(term.atomValue()))
            is OtpErlangLong -> Quoted.Integer(term.bigIntegerValue())
            is OtpErlangDouble -> Quoted.Float(term.doubleValue())
            is OtpErlangBinary -> Quoted.Binary(binaries(String(term.binaryValue(), Charsets.UTF_8)).toByteArray())
            is OtpErlangString ->
                Quoted.List(term.stringValue().codePoints().toArray().map { Quoted.Integer(it.toBigInteger()) })
            is OtpErlangList -> Quoted.List(term.elements().map { of(it, atoms, binaries) })
            is OtpErlangTuple -> Quoted.Tuple(term.elements().map { of(it, atoms, binaries) })
            else -> Quoted.Unknown
        }

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
