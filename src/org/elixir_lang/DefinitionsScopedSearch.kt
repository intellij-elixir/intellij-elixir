package org.elixir_lang

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.util.Processor
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.declaration.Declaration
import org.elixir_lang.model.psi.protocol.ProtocolImplementation
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.DefinitionDeclaration
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.declaringModuleCall
import org.elixir_lang.psi.impl.maybeModularNameToModulars
import org.elixir_lang.psi.outerMostQualifiableAlias

internal class DefinitionsScopedSearch :
    QueryExecutorBase<PsiElement, DefinitionsScopedSearch.SearchParameters>(/* requireReadAction = */ true) {
    override fun processQuery(
        queryParameters: DefinitionsScopedSearch.SearchParameters,
        consumer: Processor<in PsiElement>
    ) {
        when (val element = queryParameters.element) {
            is Call -> processQuery(element, consumer)
            is QualifiableAlias -> processQuery(element, consumer)
        }
    }

    private fun processQuery(qualifiableAlias: QualifiableAlias, consumer: Processor<in PsiElement>) {
        ProgressManager.checkCanceled()
        val outerMostQualifiableAlias = qualifiableAlias.outerMostQualifiableAlias()
        // A declaration's name carries no reference, so it cannot resolve to its own declaration.
        val declaringModuleCall = declaringModuleCall(outerMostQualifiableAlias)

        if (declaringModuleCall != null) {
            processQuery(declaringModuleCall, consumer)
        } else {
            outerMostQualifiableAlias.maybeModularNameToModulars(qualifiableAlias.containingFile)
                .map { modular ->
                    processQuery(modular, consumer)
                }
        }
    }

    private fun processQuery(psiElement: PsiElement, consumer: Processor<in PsiElement>) {
        when (psiElement) {
            is Call -> processQuery(psiElement, consumer)
            is BeamModule -> processQuery(psiElement, consumer)
            is BeamCallDefinition -> processQuery(psiElement, consumer)
        }
    }

    private fun processQuery(callArgument: Call, consumer: Processor<in PsiElement>) {
        // "Go To Implementation" (Ctrl+Alt+B) resolves its source through the element-name path, which
        // lands on the *name-call head* of the clause (e.g. `perform()` inside `def perform()`), not on
        // the enclosing `CallDefinitionClause`. Since protocol-function names are owned by the Symbol
        // model, they carry no self-resolving reference to fall back on, so normalize the head call up to
        // its enclosing def clause before dispatching.
        val call =
            if (!Protocol.`is`(callArgument) && !CallDefinitionClause.`is`(callArgument) && Protocol.isHead(callArgument)) {
                generateSequence(callArgument.parent) { it.parent }
                    .filterIsInstance<Call>()
                    .firstOrNull { CallDefinitionClause.`is`(it) }
                    ?: callArgument
            } else {
                callArgument
            }

        if (Protocol.`is`(call)) {
            Protocol.processImplementations(call, consumer)
        } else if (CallDefinitionClause.`is`(call)) {
            enclosingModularMacroCall(call)?.let { modularCall ->
                DefinitionDeclaration.of(call, ResolveState.initial())?.let { member ->
                    if (Protocol.`is`(modularCall)) {
                        processImplementers(modularCall, member, consumer)
                    }
                }
            }
        }
    }

    private fun processQuery(moduleImpl: BeamModule, consumer: Processor<in PsiElement>) {
        if (Protocol.`is`(moduleImpl)) {
            Protocol.processImplementations(moduleImpl, consumer)
        }
    }

    private fun processQuery(callDefinitionImpl: BeamCallDefinition, consumer: Processor<in PsiElement>) {
        val moduleImpl = callDefinitionImpl.parent

        if (Protocol.`is`(moduleImpl)) {
            processImplementers(moduleImpl, callDefinitionImpl.declaration(), consumer)
        }
    }

    /** The definitions of every `defimpl` of [protocol] that implement [member]. */
    private fun processImplementers(protocol: PsiElement, member: Declaration, consumer: Processor<in PsiElement>) {
        val process = Processor<PsiElement> { defimpl ->
            ProgressManager.checkCanceled()

            var continueProcessing = true

            when (defimpl) {
                is Call ->
                    for (defimplChild in CallDefinitionClause.modularChildCalls(defimpl)) {
                        ProgressManager.checkCanceled()

                        if (DefinitionDeclaration.of(defimplChild, ResolveState.initial())
                                ?.let { ProtocolImplementation.implementedBy(member, it) } == true &&
                            !consumer.process(defimplChild)
                        ) {
                            continueProcessing = false
                            break
                        }
                    }

                is BeamModule ->
                    for (callDefinition in defimpl.callDefinitions()) {
                        ProgressManager.checkCanceled()

                        if (ProtocolImplementation.implementedBy(member, callDefinition.declaration()) &&
                            !consumer.process(callDefinition)
                        ) {
                            continueProcessing = false
                            break
                        }
                    }
            }

            continueProcessing
        }

        when (protocol) {
            is Call -> Protocol.processImplementations(protocol, process)
            is BeamModule -> Protocol.processImplementations(protocol, process)
        }
    }
}
