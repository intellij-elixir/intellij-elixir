package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangList
import com.ericsson.otp.erlang.OtpErlangObject

/**
 * `Config`'s state for one read of a project's config, as `Config.__eval__!/3` keeps it in the process dictionary: the
 * env and target, and the [config] its `config/2,3` calls have merged.
 */
internal class ConfigRead(val env: String, val target: String) {
    var config: OtpErlangObject = OtpErlangList()
}
