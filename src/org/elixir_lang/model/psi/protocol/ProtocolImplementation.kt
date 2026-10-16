package org.elixir_lang.model.psi.protocol

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.beam.psi.CallDefinition
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.declaration.ArityKnowledge
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.declaration.SameFunction
import org.elixir_lang.model.psi.function.FunctionSymbol
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.DefinitionDeclaration
import org.elixir_lang.psi.Implementation
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.psi.call.Call

/**
 * Whether a definition in a `defimpl` implements a protocol function. A `defimpl` sets `@behaviour` to its protocol
 * and the protocol's functions are `def` callbacks, so the definition must be a public run-time function, whether
 * written as a clause or a `defdelegate`, with the member's name at an arity it covers. A macro member is implemented
 * by nothing.
 */
object ProtocolImplementation {
    /** [member] is a definition of the protocol itself, source or compiled. */
    fun implementedBy(member: Declaration, implementer: Declaration): Boolean =
        implementedBy(FunctionSymbol.isMacro(member.capabilities), implementer, implementer.sameFunction(member))

    fun implementedBy(member: ProtocolFunction, implementer: Declaration): Boolean =
        implementedBy(member.macro, implementer, implementer.sameFunction(member.name, ArityKnowledge.Exact(member.arity)))

    /**
     * The source protocol functions [implementer], a definition in a `defimpl`, implements: those of the protocol the
     * `defimpl` names. Empty when it implements none, the protocol does not resolve or it is compiled.
     */
    @RequiresReadLock
    fun membersImplementedBy(implementer: Call): List<ProtocolFunction> =
        implementedMembers(implementer).filterIsInstance<ImplementedMember.Source>().map { it.member }

    /** Where each protocol member [implementer], a definition in a `defimpl`, implements is defined, once each. */
    @RequiresReadLock
    fun memberElementsImplementedBy(implementer: Call): List<PsiElement> =
        implementedMembers(implementer).map { it.element }.distinct()

    /**
     * Whether [implementer], a definition in a `defimpl`, implements the member of the protocol the `defimpl` names
     * that is [name]`/`[arity], source or compiled. A compiled protocol has no [ProtocolFunction], so this is what
     * says a clause implements nothing for either.
     */
    @RequiresReadLock
    fun implementsMember(implementer: Call, name: String, arity: Int, macro: Boolean): Boolean =
        implementedMembers(implementer).any {
            when (it) {
                is ImplementedMember.Source ->
                    it.member.name == name && it.member.arity == arity && it.member.macro == macro

                is ImplementedMember.Compiled ->
                    it.member.name == name &&
                        it.member.arity.arityInterval()?.contains(arity) == true &&
                        FunctionSymbol.isMacro(it.member.capabilities) == macro
            }
        }

    /**
     * A protocol member a definition in a `defimpl` implements, and where the member is defined: the `def` clause of a
     * source protocol, or the compiled protocol's call definition. A source clause with a default argument gives one
     * member per arity it covers, all at its one [element].
     */
    sealed interface ImplementedMember {
        val element: PsiElement

        class Source(val member: ProtocolFunction, override val element: Call) : ImplementedMember

        class Compiled(val member: Declaration, override val element: CallDefinition) : ImplementedMember
    }

    /** The members of its protocol that [implementer], a definition in a `defimpl`, implements. */
    @RequiresReadLock
    fun implementedMembers(implementer: Call): List<ImplementedMember> {
        val declaration = DefinitionDeclaration.of(implementer, ResolveState.initial()) ?: return emptyList()
        val defimpl = CallDefinitionClause.enclosingModularMacroCall(implementer)?.takeIf { Implementation.`is`(it) }
            ?: return emptyList()
        val protocols = mutableListOf<PsiElement>()
        Implementation.processProtocols(defimpl) { protocol -> protocols.add(protocol) }

        return protocols.flatMap { protocol ->
            ProgressManager.checkCanceled()

            when (protocol) {
                is Call ->
                    CallDefinitionClause.modularChildCalls(protocol)
                        .filter { CallDefinitionClause.`is`(it) }
                        .flatMap { clause ->
                            ProtocolFunction.fromClause(clause)
                                .filter { implementedBy(it, declaration) }
                                .map { ImplementedMember.Source(it, clause) }
                        }

                is BeamModule ->
                    protocol.callDefinitions()
                        .filter { implementedBy(it.declaration(), declaration) }
                        .map { ImplementedMember.Compiled(it.declaration(), it) }

                else -> emptyList()
            }
        }
    }

    /** An arity not yet knowable implements nothing. */
    private fun implementedBy(macro: Boolean, implementer: Declaration, sameFunction: SameFunction): Boolean =
        !macro && implementer.capabilities.remoteCallable && sameFunction == SameFunction.YES
}
