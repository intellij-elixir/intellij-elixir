package org.elixir_lang.psi.scope

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveState
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.isAncestor
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.beam.psi.TypeDefinition as BeamTypeDefinition
import org.elixir_lang.psi.*
import org.elixir_lang.psi.Module
import org.elixir_lang.psi.ModuleAttribute.isTypeSpecName
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.impl.call.whileInStabBodyChildExpressions
import org.elixir_lang.psi.impl.identifierName
import org.elixir_lang.psi.impl.whileInChildExpressions
import org.elixir_lang.psi.operation.When
import org.elixir_lang.psi.scope.WhileIn.whileIn
import org.elixir_lang.psi.stub.index.ModularName
import org.elixir_lang.psi.stub.type.call.Stub.isModular

abstract class Type : PsiScopeProcessor {
    override fun execute(element: PsiElement, state: ResolveState): Boolean =
        when (TypeDescent.classify(element)) {
            TypeDescent.Bucket.AT_OPERATION -> execute(element as AtOperation, state)
            TypeDescent.Bucket.CALL -> execute(element as Call, state)
            TypeDescent.Bucket.STAB_PARENTHESES_SIGNATURE -> execute(element as ElixirStabParenthesesSignature, state)
            TypeDescent.Bucket.STAB_NO_PARENTHESES_SIGNATURE ->
                execute(element as ElixirStabNoParenthesesSignature, state)
            TypeDescent.Bucket.PARAMETER -> executeOnParameter(element, state)
            TypeDescent.Bucket.CHILDREN -> executeOnChildren(element, state)
            TypeDescent.Bucket.HALT -> false
            TypeDescent.Bucket.BEAM_MODULE -> execute(element as BeamModule, state)
            TypeDescent.Bucket.BEAM_TYPE_DEFINITION -> execute(element as BeamTypeDefinition, state)
            TypeDescent.Bucket.BEAM_CALL_DEFINITION, TypeDescent.Bucket.PASS, TypeDescent.Bucket.LEAF -> true
        }


    protected abstract fun executeOnType(definition: AtUnqualifiedNoParenthesesCall<*>, state: ResolveState): Boolean
    protected abstract fun executeOnParameter(parameter: PsiElement, state: ResolveState): Boolean
    protected abstract fun keepProcessing(): Boolean

    private fun execute(atNonNumericOperation: AtOperation, state: ResolveState): Boolean =
        execute(atNonNumericOperation.children.last(), state)

    private fun execute(call: Call, state: ResolveState): Boolean =
        when (call) {
            // A bare name met as a call is a type usage, as the module listing and a `quote` reach inside a `@spec`;
            // what declares a type variable is a `@type` head's argument or a stab signature's, walked on their own.
            is UnqualifiedNoArgumentsCall<*> -> true
            is AtUnqualifiedNoParenthesesCall<*> -> execute(call, state)
            else -> if (isModular(call) && call.isAncestor(state.get(ENTRANCE), false)) {
                val childCalls = org.elixir_lang.psi.CallDefinitionClause.modularChildCalls(call).toTypedArray()
                val childCallsKeepProcessing = whileIn(childCalls) {
                    execute(it, state)
                }

                // specialization
                if (childCallsKeepProcessing && Protocol.`is`(call)) {
                    // favor the decompiled, specific module for this protocol so that only this module's `t` will
                    // be used for Find Usages instead of for all protocols as happens with the source for
                    // `defprotocol`.
                    executeOnDecompiledProtocol(call) &&
                            executeOnDefprotocolCall(call, state)
                } else {
                    childCallsKeepProcessing
                }
            } else if (Use.`is`(call)) {
                Use.treeWalkUp(call, state, ::execute)
            } else {
                true
            }
        }

    private fun execute(moduleImpl: BeamModule, state: ResolveState): Boolean =
        if (moduleImpl.isAncestor(state.get(ENTRANCE), false)) {
            whileIn(moduleImpl.typeDefinitions()) {
                execute(it, state)
            }
        } else {
            true
        }

    protected abstract fun execute(typeDefinition: BeamTypeDefinition, state: ResolveState): Boolean

    private fun execute(
        atUnqualifiedNoParenthesesCall: AtUnqualifiedNoParenthesesCall<*>,
        state: ResolveState,
    ): Boolean {
        val identifierName = atUnqualifiedNoParenthesesCall.atIdentifier.identifierName()

        return if (isTypeSpecName(identifierName)) {
            executeOnType(atUnqualifiedNoParenthesesCall, state)
        } else {
            true
        }
    }

    private fun execute(stabParenthesesSignature: ElixirStabParenthesesSignature, state: ResolveState): Boolean =
        executeOnChildren(stabParenthesesSignature.parenthesesArguments, state)

    private fun execute(stabNoParenthesesSignature: ElixirStabNoParenthesesSignature, state: ResolveState): Boolean =
        executeOnChildren(stabNoParenthesesSignature.noParenthesesArguments, state)

    private fun executeOnChildren(parent: PsiElement, state: ResolveState): Boolean =
        parent.whileInChildExpressions() {
            execute(it, state)
        }

    private fun executeOnDecompiledProtocol(call: Call): Boolean {
        val project = call.project

        return call.name?.let { name ->
            StubIndex.getInstance().processElements(
                ModularName.KEY,
                name,
                project,
                GlobalSearchScope.allScope(project),
                NamedElement::class.java
            ) { modular ->
                // use `BeamModule` to only use decompiled
                if (modular is BeamModule) {
                    // reset the resolve state as only `defmodule` that is an ancestor of entrance will be walked
                    execute(
                        modular,
                        ResolveState.initial().put(ENTRANCE, modular).putInitialVisitedElement(modular)
                    )
                } else {
                    true
                }
            }
        } ?: true
    }

    private fun executeOnDefprotocolCall(call: Call, state: ResolveState): Boolean =
        call.reference?.let { reference ->
            when (reference) {
                is PsiPolyVariantReference -> {
                    val resolveResults = reference.multiResolve(false)

                    whileIn(resolveResults) { resolveResult ->
                        resolveResult.element?.let { it as? Call }?.let {
                            executeOnDefprotocolDefinition(it, state)
                        } ?: true
                    }
                }

                // A single-target reference cannot name a `defprotocol` to walk; keep walking.
                else -> true
            }
        } ?: true

    private fun executeOnDefprotocolDefinition(descendant: PsiElement, state: ResolveState): Boolean =
        when (descendant) {
            is Call -> descendant.whileInStabBodyChildExpressions {
                executeOnDefprotocolDefinitionExpression(it, state)
            }

            else -> {
                true
            }
        }

    private fun executeOnDefprotocolDefinitionExpression(expression: PsiElement, state: ResolveState): Boolean =
        when (expression) {
            is Qualified ->
                if (expression.isCalling("Protocol", "__protocol__", 2)) {
                    expression.reference?.let { it as PsiPolyVariantReference }?.multiResolve(false)
                        ?.let { resolveResults ->
                            whileIn(resolveResults) { resolveResult ->
                                resolveResult.element?.let { __protocol__ ->
                                    Using.treeWalkUp(
                                        __protocol__,
                                        use = null,
                                        resolveState = state,
                                        keepProcessing = ::executeOn__protocol__DefinitionExpression
                                    )
                                } ?: true
                            }
                        } ?: true
                } else {
                    true
                }

            else -> true
        }

    private fun executeOn__protocol__DefinitionExpression(expression: PsiElement, state: ResolveState): Boolean =
        expression.let { it as? Call }?.let { call ->
            if (Module.`is`(call)) {
                // treat the module the same as an inline quote block as the `defprotocol` will replace itself with
                // the `defmodule`
                QuoteMacro.treeWalkUp(call, state, ::execute)
            } else {
                true
            }
        } ?: true
}

internal tailrec fun PsiElement.ancestorTypeSpec(): AtUnqualifiedNoParenthesesCall<*>? =
    when (TypeAscent.classify(this)) {
        TypeAscent.Bucket.SPEC ->
            (this as AtUnqualifiedNoParenthesesCall<*>).takeIf { isTypeSpecName(it.atIdentifier.identifierName()) }
        // a detached element has no spec above it
        TypeAscent.Bucket.PARENT -> (parent ?: return null).ancestorTypeSpec()
        TypeAscent.Bucket.NONE, TypeAscent.Bucket.LEAF -> null
    }

val OPTIONALITIES = arrayOf("optional", "required")

fun Call.isTypeSpecPseudoFunction(): Boolean =
    hasMapFieldOptionalityName() || hasListRepetitionName() || isNoTypeRestriction()

fun Call.hasMapFieldOptionalityName(): Boolean =
    parent is ElixirContainerAssociationOperation && functionName() in OPTIONALITIES && resolvedFinalArity() == 1

fun Call.hasListRepetitionName(): Boolean =
    parent is ElixirList && functionName() == "..."

fun Call.isNoTypeRestriction(): Boolean =
    functionName() == "var" && parent.isTypeRestriction()

fun PsiElement.isTypeRestriction(): Boolean =
    this is QuotableKeywordPair && parent.isTypeRestrictions()

fun PsiElement.isTypeRestrictions(): Boolean =
    this is QuotableKeywordList && parent.isTypeGuard()

fun PsiElement.isTypeGuard(): Boolean =
    this is When
