package org.elixir_lang.documentation

import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.CallDefinitionClause.enclosingModularMacroCall
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.psi.ElixirUnmatchedAtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.CanonicallyNamed
import org.elixir_lang.psi.impl.ElixirUnmatchedUnqualifiedNoParenthesesCallImpl
import org.elixir_lang.psi.impl.call.macroChildCallList
import org.elixir_lang.psi.impl.identifierName
import org.elixir_lang.psi.impl.siblingExpressions
import org.elixir_lang.psi.stub.type.call.Stub
import org.elixir_lang.structure_view.element.CallDefinitionHead
import com.intellij.util.concurrency.annotations.RequiresReadLock

object SourceFileDocsHelper {
    fun fetchDocs(element: PsiElement): FetchedDocs? = when (element) {
        is AtUnqualifiedNoParenthesesCall<*> -> fetchDocs(element)
        is Call -> fetchDocs(element)
        else -> documentedCallDefinitionClause(element)?.let(::fetchDocs)
    }

    /**
     * The `def`/`defp` clause [element] names, when [element] is that clause's own name identifier.
     *
     * Resolution hands out the name identifier rather than the clause where a `PsiNamedElement` is
     * wanted - `HeexComponentResolver.declarationTarget` does, so Rename and Go To Declaration land
     * on the name - and the resulting element reaches Quick Docs unchanged.
     *
     * The nearest [Call] ancestor of a name identifier is the head's argument-list wrapper
     * (`button(assigns)`), so the walk continues to the first call definition clause. Requiring the
     * clause's name identifier to be [element] is what keeps the clause's `@doc` off everything else
     * inside it.
     */
    private fun documentedCallDefinitionClause(element: PsiElement): Call? =
        generateSequence(element.parent) { it.parent }
            .filterIsInstance<Call>()
            .firstOrNull { CallableDeclaration.headBindingFormOf(it) == CallableDeclaration.Form.CLAUSE }
            ?.takeIf { CallDefinitionClause.nameIdentifier(it) == element }

    private fun fetchDocs(moduleAttribute: AtUnqualifiedNoParenthesesCall<*>): FetchedDocs? =
        when (moduleAttribute.atIdentifier.identifierName()) {
            "type", "typep", "opaque" -> fetchTypeDocs(moduleAttribute)
            "callback", "macrocallback" -> fetchCallbackDocs(moduleAttribute)
            else -> null
        }

    private fun fetchTypeDocs(moduleAttribute: AtUnqualifiedNoParenthesesCall<*>): FetchedDocs.TypeDocumentation? {
        val typeDoc = moduleAttribute
            .siblingExpressions(forward = false, withSelf = false)
            .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
            .firstOrNull { previousModuleAttribute ->
                previousModuleAttribute.atIdentifier.identifierName() == "typedoc"
            }
            ?.moduleAttributeValue()
            ?.documentationMarkdownText()

        return if (!typeDoc.isNullOrEmpty()) {
            enclosingModularMacroCall(moduleAttribute)?.let { modular ->
                val module = (modular as? CanonicallyNamed)?.canonicalName().orEmpty()

                FetchedDocs.TypeDocumentation(module, moduleAttribute.text, typeDoc)
            }
        } else {
            null
        }
    }

    private fun fetchCallbackDocs(moduleAttribute: AtUnqualifiedNoParenthesesCall<*>): FetchedDocs.CallbackDocumentation? {
        val typeDoc = moduleAttribute
            .siblingExpressions(forward = false, withSelf = false)
            .filterIsInstance<AtUnqualifiedNoParenthesesCall<*>>()
            .firstOrNull { previousModuleAttribute ->
                previousModuleAttribute.atIdentifier.identifierName() == "doc"
            }
            ?.moduleAttributeValue()
            ?.documentationMarkdownText()

        return if (!typeDoc.isNullOrEmpty()) {
            enclosingModularMacroCall(moduleAttribute)?.let { modular ->
                val module = (modular as? CanonicallyNamed)?.canonicalName().orEmpty()

                FetchedDocs.CallbackDocumentation(module, moduleAttribute.text, typeDoc)
            }
        } else {
            null
        }
    }

    private fun fetchDocs(call: Call): FetchedDocs? = when {
        Stub.isModular(call) -> {
            val moduleDoc = (call as? ElixirUnmatchedUnqualifiedNoParenthesesCallImpl)
                ?.doBlock
                ?.stab
                ?.stabBody
                ?.unmatchedExpressionList
                ?.asSequence()
                ?.filterIsInstance<ElixirUnmatchedAtUnqualifiedNoParenthesesCall>()
                ?.filter { it.atIdentifier.lastChild?.text == "moduledoc" }
                ?.mapNotNull { moduleAttribute ->
                    moduleAttribute.moduleAttributeValue()?.documentationMarkdownText()
                }
                ?.joinToString("")

            if (!moduleDoc.isNullOrEmpty()) {
                FetchedDocs.ModuleDocumentation(call.canonicalName().orEmpty(), moduleDoc)
            } else {
                null
            }
        }
        CallableDeclaration.headBindingFormOf(call) == CallableDeclaration.Form.CLAUSE -> {
            val state = ResolveState.initial()

            CallDefinitionClause.nameArityInterval(call, state)?.let { nameArityRange ->
                enclosingModularMacroCall(call)?.let { modular ->
                    val module = (modular as? CanonicallyNamed)?.canonicalName().orEmpty()

                    modular
                        .macroChildCallList()
                        .mapNotNull { sibling ->
                            if (CallableDeclaration.headBindingFormOf(sibling) == CallableDeclaration.Form.CLAUSE) {
                                CallDefinitionClause
                                    .head(sibling)
                                    ?.let { siblingHead ->
                                        CallDefinitionHead
                                            .nameArityInterval(siblingHead, state)
                                            ?.let { siblingNameArityInterval ->
                                                if ((siblingNameArityInterval.name == nameArityRange.name) &&
                                                    siblingNameArityInterval.arityInterval.overlaps(nameArityRange.arityInterval)
                                                ) {
                                                    FetchedDocs
                                                        .FunctionOrMacroDocumentation
                                                        .fromCallDefinitionClauseCall(module, sibling, siblingHead)
                                                } else {
                                                    null
                                                }
                                            }
                                    }
                            } else {
                                null
                            }
                        }
                        .takeIf(List<*>::isNotEmpty)
                        ?.reduce { acc, documentation ->
                            acc.merge(documentation)
                        }
                }
            }
        }
        CallableDeclaration.headBindingFormOf(call) == CallableDeclaration.Form.DELEGATION -> delegationDocs(call)
        else -> null
    }

    /**
     * The `@doc` written on a `defdelegate` itself, or `null` when it has none.
     *
     * A delegation's own `@doc` is the more specific answer, so it replaces the target's. Null rather
     * than an empty document is what lets the caller fall through to the target.
     */
    @RequiresReadLock
    private fun delegationDocs(call: Call): FetchedDocs? =
        CallableDeclaration
            .delegationHead(call)
            ?.let { head ->
                enclosingModularMacroCall(call)?.let { modular ->
                    val module = (modular as? CanonicallyNamed)?.canonicalName().orEmpty()

                    FetchedDocs.FunctionOrMacroDocumentation
                        .fromCallDefinitionClauseCall(module, call, head)
                        .takeIf { it.doc != null }
                }
            }
}
