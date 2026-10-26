package org.elixir_lang.model.psi.config

import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.expander.ImportMatch
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

    /**
     * Whether [call], written as this says, calls a module with a read of [name] and [arity]: its qualifier names one
     * where the file's expansion reached it, or, unqualified, an import of one brings the read in. Where expansion did
     * not reach, only [Qualifier.written] counts.
     */
    @RequiresReadLock
    fun isRead(call: Call): Boolean {
        val modules = Read.modules(name, arity)

        return when (qualifier) {
            Qualifier.UNQUALIFIED ->
                when (val match = KeyAtom.importOf(call, name, arity)) {
                    is ImportMatch.Function -> match.receiver in modules
                    is ImportMatch.Macro -> match.receiver in modules
                    else -> false
                }
            Qualifier.APPLICATION, Qualifier.ERLANG_APPLICATION, Qualifier.OTHER ->
                ((call as? Qualified)?.qualifier()?.let(KeyAtom::ofQualifier) ?: qualifier.written) in modules
        }
    }
}
