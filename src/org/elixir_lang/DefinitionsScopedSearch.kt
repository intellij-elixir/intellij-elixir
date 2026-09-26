package org.elixir_lang

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.util.Processor
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.Protocol
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.call.Call
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
        qualifiableAlias.outerMostQualifiableAlias().maybeModularNameToModulars(qualifiableAlias.containingFile)
            .map { modular ->
                processQuery(modular, consumer)
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
                CallDefinitionClause.nameArityInterval(call, ResolveState.initial())?.let { protocolNameArityInterval ->
                    if (Protocol.`is`(modularCall)) {
                        Protocol.processImplementations(modularCall) { defimpl ->
                            ProgressManager.checkCanceled()

                            var continueProcessing = true

                            when (defimpl) {
                                is Call -> {
                                    for (defimplChild in org.elixir_lang.psi.CallDefinitionClause.modularChildCalls(defimpl)) {
                                        ProgressManager.checkCanceled()

                                        if (CallDefinitionClause.`is`(defimplChild)) {
                                            CallDefinitionClause.nameArityInterval(defimplChild, ResolveState.initial())
                                                ?.let { implNameArityInterval ->
                                                    if (implNameArityInterval.name == protocolNameArityInterval.name &&
                                                        implNameArityInterval.arityInterval.overlaps(protocolNameArityInterval.arityInterval)
                                                    ) {
                                                        if (!consumer.process(defimplChild)) {
                                                            continueProcessing = false
                                                        }
                                                    }
                                                }
                                        }

                                        if (!continueProcessing) {
                                            break
                                        }
                                    }
                                }

                                is BeamModule -> {
                                    for (callDefinition in defimpl.callDefinitions()) {
                                        ProgressManager.checkCanceled()

                                        val implNameArityInterval = callDefinition.nameArityInterval

                                        if (implNameArityInterval.name == protocolNameArityInterval.name &&
                                            implNameArityInterval.arityInterval.overlaps(protocolNameArityInterval.arityInterval)
                                        ) {
                                            if (!consumer.process(callDefinition)) {
                                                continueProcessing = false
                                            }
                                        }

                                        if (!continueProcessing) {
                                            break
                                        }
                                    }
                                }
                            }

                            continueProcessing
                        }
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
            val name = callDefinitionImpl.name
            val arity = callDefinitionImpl.exportedArity(ResolveState.initial())

            Protocol.processImplementations(moduleImpl) { defimpl ->
                ProgressManager.checkCanceled()

                var continueProcessing = true

                when (defimpl) {
                    is Call -> {
                        for (defimplChild in org.elixir_lang.psi.CallDefinitionClause.modularChildCalls(defimpl)) {
                            ProgressManager.checkCanceled()

                            if (CallDefinitionClause.`is`(defimplChild)) {
                                CallDefinitionClause.nameArityInterval(defimplChild, ResolveState.initial())
                                    ?.let { implNameArityInterval ->
                                        if (implNameArityInterval.name == name &&
                                            implNameArityInterval.arityInterval.contains(arity)
                                        ) {
                                            if (!consumer.process(defimplChild)) {
                                                continueProcessing = false
                                            }
                                        }
                                    }
                            }

                            if (!continueProcessing) {
                                break
                            }
                        }
                    }
                    is BeamModule ->
                        for (callDefinition in defimpl.callDefinitions()) {
                            ProgressManager.checkCanceled()

                            val implNameArityInterval = callDefinition.nameArityInterval

                            if (implNameArityInterval.name == name &&
                                implNameArityInterval.arityInterval.contains(arity)
                            ) {
                                if (!consumer.process(callDefinition)) {
                                    continueProcessing = false
                                }
                            }

                            if (!continueProcessing) {
                                break
                            }
                        }
                }

                continueProcessing
            }
        }
    }
}
