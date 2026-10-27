package org.elixir_lang.model.psi.config

import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.expander.ImportMatch
import org.elixir_lang.psi.Sigil
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified

/** How a candidate read's call names its module, as written, and the [name] and [arity] it calls. */
data class ReadModule(val qualifier: Qualifier, val name: String, val arity: Int) {
    enum class Qualifier(val written: String?) {
        /** `Application`, `Elixir.Application` or `:"Elixir.Application"`; an `alias` can make the first name another module. */
        APPLICATION(Read.ELIXIR_APPLICATION),
        ERLANG_APPLICATION(Read.ERLANG_APPLICATION),

        /** Any other qualifier: another alias, a module attribute, a variable. */
        OTHER(null),
        UNQUALIFIED(null),
    }

    /** Whether a call is a read. */
    enum class Answer {
        READ,
        NOT_READ,

        /** The template's own Elixir names the call's module, which only expanding it could tell. */
        UNDECIDED,
    }

    /**
     * Whether [call], written as this says, calls a module with a read of [name] and [arity]: its qualifier names one
     * where the file's expansion reached it, or, unqualified, an import of one brings the read in. Where expansion did
     * not reach, only [Qualifier.written] counts. In a template [sigil] holds, each is named where [KeyAtom.place] says.
     */
    @RequiresReadLock
    fun isRead(call: Call, sigil: Sigil? = KeyAtom.injectedSigil(call)): Answer {
        val modules = Read.modules(name, arity)

        return when (qualifier) {
            Qualifier.UNQUALIFIED -> {
                val at = KeyAtom.place(call, sigil) ?: return Answer.UNDECIDED

                answer(
                    when (val match = KeyAtom.importOf(call, name, arity, at)) {
                        is ImportMatch.Function -> match.receiver in modules
                        is ImportMatch.Macro -> match.receiver in modules
                        else -> false
                    }
                )
            }
            Qualifier.APPLICATION, Qualifier.ERLANG_APPLICATION, Qualifier.OTHER -> {
                val named = (call as? Qualified)?.qualifier()?.let { written ->
                    KeyAtom.ofQualifier(written, KeyAtom.place(written, sigil) ?: return Answer.UNDECIDED)
                }

                answer((named ?: qualifier.written) in modules)
            }
        }
    }

    private fun answer(read: Boolean): Answer = if (read) Answer.READ else Answer.NOT_READ
}
