package org.elixir_lang.expander

import com.ericsson.otp.erlang.OtpErlangList

/** What a loaded module's `__info__(:attributes)` gives: the attributes its `.beam` persists. */
sealed class ModuleAttributes {
    /** @property keyword each persisted attribute with its values, in the `.beam`'s order */
    data class Known(val keyword: OtpErlangList) : ModuleAttributes()

    /** Which attributes the module persists can't be read: it has no `.beam` the expander reads, or its chunk can't be. */
    data object Unreadable : ModuleAttributes()
}
