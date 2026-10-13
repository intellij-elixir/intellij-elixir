package org.elixir_lang.model.psi.protocol

import org.elixir_lang.declaration.Declaration
import org.elixir_lang.psi.ArityInterval
import org.elixir_lang.psi.arityInterval

/**
 * Whether a definition in a `defimpl` implements a protocol function. A `defimpl` sets `@behaviour` to its protocol
 * and the protocol's functions are `def` callbacks, so the definition must be a public run-time function, whether
 * written as a clause or a `defdelegate`, with the member's name at an arity it covers. A macro member is implemented
 * by nothing.
 */
object ProtocolImplementation {
    /** [member] is a definition of the protocol itself, source or compiled. */
    fun implementedBy(member: Declaration, implementer: Declaration): Boolean =
        member.arity.arityInterval()?.let {
            implementedBy(member.name, it, member.capabilities.quotesArguments, implementer)
        } == true

    fun implementedBy(member: ProtocolFunction, implementer: Declaration): Boolean =
        implementedBy(member.name, ArityInterval(member.arity, member.arity), member.macro, implementer)

    private fun implementedBy(name: String, arities: ArityInterval, macro: Boolean, implementer: Declaration): Boolean =
        !macro &&
            implementer.capabilities.remoteCallable &&
            implementer.name == name &&
            implementer.arity.arityInterval()?.overlaps(arities) == true
}
