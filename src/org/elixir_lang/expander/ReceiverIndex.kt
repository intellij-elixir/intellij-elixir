package org.elixir_lang.expander

import com.intellij.openapi.util.TextRange
import org.elixir_lang.lowering.ElixirAst
import org.elixir_lang.psi.Import.Term

/**
 * What each remote call's or remote capture's receiver a file's expansion reached expanded to, by the receiver's
 * origin. A receiver expanded more than once keeps its last: a qualifier that is a macro call reports after the calls
 * its output makes at its origin.
 */
internal class ReceiverIndex {
    sealed interface Receiver {
        class Module(val name: String) : Receiver

        /** Reached, and expanded to no atom. */
        data object Unknown : Receiver

        /** Not reached by the expansion. */
        data object Unreached : Receiver
    }

    private val byOrigin = HashMap<TextRange, Receiver>()

    fun expanded(qualifier: ElixirAst, value: Term) {
        byOrigin[qualifier.meta.origin] = if (value is Term.Atom) Receiver.Module(value.name) else Receiver.Unknown
    }

    fun at(range: TextRange): Receiver = byOrigin[range] ?: Receiver.Unreached
}
