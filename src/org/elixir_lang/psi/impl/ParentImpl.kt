package org.elixir_lang.psi.impl

import com.ericsson.otp.erlang.OtpErlangBinary
import java.nio.charset.Charset

object ParentImpl {
    @JvmStatic
    fun elixirString(javaString: String): OtpErlangBinary =
        javaString.toByteArray(Charset.forName("UTF-8")).let(::OtpErlangBinary)
}
