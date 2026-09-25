package org.elixir_lang

import com.intellij.codeInsight.TargetElementEvaluatorEx2
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ThreeState
import org.elixir_lang.model.psi.module_attribute.ModuleAttributeSymbol
import org.elixir_lang.model.psi.variable.VariableSymbol
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.operation.Multiplication
import org.elixir_lang.psi.operation.capture.NonNumeric
import org.elixir_lang.psi.scope.ancestorTypeSpec
import org.elixir_lang.psi.scope.hasMapFieldOptionalityName
import org.elixir_lang.reference.Module
import org.elixir_lang.structure_view.element.Callback
import org.elixir_lang.structure_view.element.Type as TypeElement

internal class TargetElementEvaluator : TargetElementEvaluatorEx2() {
    override fun isAcceptableNamedParent(parent: PsiElement): Boolean = when {
        Callback.isHead(parent) || TypeElement.isHead(parent) || ModuleAttributeSymbol.isHead(parent) || VariableSymbol.isHead(parent) || (CallDefinitionClause.isHead(parent) && !Protocol.isHead(parent)) -> {
            // `@callback`/`@macrocallback` names and regular (non-protocol) call-definition clause heads are
            // owned by the Symbol model. Don't expose them as a legacy named element, which the platform
            // would otherwise offer as a redundant Find Usages / Go To target beside the symbol's
            // `name/arity` target.
            // Protocol function `def` heads are kept on the legacy path because
            // `DefinitionsScopedSearch` (Go To Implementation) resolves source via legacy flags.
            false
        }

        parent is UnqualifiedNoArgumentsCall<*> -> when (val grandParent = parent.parent) {
            is AtOperation -> false
            // Don't allow `name` in `&name/arity` to be named parent. The capture needs to be the parent.
            is Multiplication -> when (grandParent.parent) {
                is NonNumeric -> false
                else -> super.isAcceptableNamedParent(parent)
            }

            else -> super.isAcceptableNamedParent(parent)
        }
        // Don't allow `Mod.name` in `&Mod.name/arity` to be named parent.  The capture needs to be the parent.
        parent is QualifiedNoArgumentsCall<*> -> when (val grandParent = parent.parent) {
            is Multiplication -> when (grandParent.parent) {
                is NonNumeric -> false
                else -> super.isAcceptableNamedParent(parent)
            }

            else -> super.isAcceptableNamedParent(parent)
        }

        parent is Call -> {
            if (org.elixir_lang.psi.Module.`is`(parent)) {
                // `defmodule` declaration heads are owned by the Symbol model (ModuleSymbol declaration provider).
                // Prevent legacy named-element targeting, which produces Ctrl-hover underline but no actionable target.
                false
            } else if (parent.hasMapFieldOptionalityName() && parent.ancestorTypeSpec() != null) {
                false
            } else {
                super.isAcceptableNamedParent(parent)
            }
        }

        else -> super.isAcceptableNamedParent(parent)
    }

    override fun isAcceptableReferencedElement(
        element: PsiElement,
        referenceOrReferencedElement: PsiElement?
    ): ThreeState =
            when {
                referenceOrReferencedElement != null && (Callback.isHead(referenceOrReferencedElement) || TypeElement.isHead(referenceOrReferencedElement) || ModuleAttributeSymbol.isHead(referenceOrReferencedElement) || VariableSymbol.isHead(referenceOrReferencedElement) || CallDefinitionClause.isHead(
                    referenceOrReferencedElement
                )) -> {
                    // Names in call-definition clause heads (including `@callback`/`@macrocallback` and protocol
                    // function declarations) are owned by the Symbol model. Don't accept them as legacy
                    // *referenced* Find Usages / Go To targets - leave the Symbol as the sole target.
                    ThreeState.NO
                }

                referenceOrReferencedElement is Call && org.elixir_lang.psi.Module.`is`(referenceOrReferencedElement) -> {
                    // `defmodule` declarations are Symbol-owned. Legacy referenced-element acceptance
                    // can steal Ctrl-click handling from Symbol targets and result in no-op navigation.
                    ThreeState.NO
                }

                else -> super.isAcceptableReferencedElement(element, referenceOrReferencedElement)
            }

    override fun getTargetCandidates(reference: PsiReference): MutableCollection<PsiElement>? =
            when (reference) {
                // Module references resolve through Resolver.preferred() which already prefers source over decompiled,
                // but falls back to decompiled if no source exists (e.g. for a call only defined in .beam).
                is Module -> {
                    reference
                        .multiResolve(false)
                        .mapNotNull(ResolveResult::getElement)
                        .toMutableList()
                }
                // `defmodule` calls are declaration heads owned by ModuleSymbol. If a legacy PsiReference
                // is present on the call (for example, resolving `defmodule` to Kernel), suppress it so
                // Ctrl+Click stays on declaration-owned Symbol behavior.
                else -> {
                    val referenceElement = reference.element
                    // Suppress legacy references on `defmodule` heads (Symbol-owned): covers both the whole
                    // Call element and the `defmodule` keyword leaf (functionNameElement), which otherwise
                    // resolves to Kernel.defmodule in .beam and causes spurious navigation.
                    val enclosingModuleCall = org.elixir_lang.psi.Module.enclosing(referenceElement)
                    val isOnDefmoduleHead = enclosingModuleCall != null &&
                            (referenceElement == enclosingModuleCall ||
                                    PsiTreeUtil.isAncestor(
                                        enclosingModuleCall.functionNameElement(),
                                        referenceElement,
                                        false
                                    ))
                    if (isOnDefmoduleHead) {
                        mutableListOf()
                    } else {
                        val resolved = reference.resolve()
                        when {
                            resolved != null && (Callback.isHead(resolved) || TypeElement.isHead(resolved) || ModuleAttributeSymbol.isHead(resolved) || VariableSymbol.isHead(resolved) || CallDefinitionClause.isHead(resolved)) -> {
                                // Call-definition clause heads (including `@callback`/`@macrocallback` and protocol
                                // function declarations) are owned by the Symbol model; return empty list to prevent
                                // redundant legacy target beside the symbol's target - avoids the ambiguity popup.
                                mutableListOf()
                            }

                            else -> super.getTargetCandidates(reference)
                        }
                    }
                }
            }
}
