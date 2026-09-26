package org.elixir_lang.psi

import com.intellij.psi.ResolveState
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewNodeTextLocation
import com.intellij.usageView.UsageViewShortNameLocation
import com.intellij.usageView.UsageViewTypeLocation
import org.elixir_lang.annotator.Parameter
import org.elixir_lang.beam.psi.CallDefinition as BeamCallDefinition
import org.elixir_lang.beam.psi.Module as BeamModule
import org.elixir_lang.beam.psi.TypeDefinition as BeamTypeDefinition
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.ALIAS
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.impl.hasKeywordKey
import org.elixir_lang.reference.Callable
import org.elixir_lang.structure_view.element.*
import org.elixir_lang.structure_view.element.modular.Module
import org.elixir_lang.structure_view.element.structure.Structure

/**
 * Provides usage-view descriptions (type, short/long name, node text) for Elixir elements in a single
 * [getElementDescription] method: the same code detects the element's type and then groups all of its
 * descriptive text together, rather than each location being a separate method.
 */
internal class ElementDescriptionProvider : com.intellij.psi.ElementDescriptionProvider {
    override fun getElementDescription(element: PsiElement, location: ElementDescriptionLocation): String? =
        when (element) {
            is AtOperation -> getElementDescription(element, location)
            is Call -> getElementDescription(element, location)
            is ElixirAtom -> getElementDescription(element, location)
            is ElixirIdentifier -> getElementDescription(element, location)
            is ElixirKeywordKey -> getElementDescription(element, location)
            is ElixirVariable -> getElementDescription(element, location)
            is MaybeModuleName -> getElementDescription(element, location)
            is BeamModule -> getElementDescription(element, location)
            is BeamTypeDefinition -> getElementDescription(element, location)
            is BeamCallDefinition -> getElementDescription(element, location)
            else -> null
        }

    /*
     * Private Instance Methods
     */

    private fun getElementDescription(atOperation: AtOperation, location: ElementDescriptionLocation): String? =
        when (location) {
            UsageViewNodeTextLocation.INSTANCE -> atOperation.text
            UsageViewTypeLocation.INSTANCE -> "module attribute"
            else -> null
        }

    private fun getElementDescription(atom: ElixirAtom, location: ElementDescriptionLocation): String? =
        when (location) {
            UsageViewNodeTextLocation.INSTANCE, UsageViewShortNameLocation.INSTANCE -> atom.text
            UsageViewTypeLocation.INSTANCE -> "atom"
            else -> null
        }

    private fun getElementDescription(identifier: ElixirIdentifier, location: ElementDescriptionLocation): String? =
        when (Parameter.putParameterized(Parameter(identifier)).type) {
            Parameter.Type.FUNCTION_NAME -> when (location) {
                UsageViewShortNameLocation.INSTANCE -> identifier.text
                UsageViewTypeLocation.INSTANCE -> "function"
                else -> null
            }
            Parameter.Type.MACRO_NAME -> when (location) {
                UsageViewShortNameLocation.INSTANCE -> identifier.text
                UsageViewTypeLocation.INSTANCE -> "macro"
                else -> null
            }
            else -> null
        }

    private fun getElementDescription(
        keywordKey: ElixirKeywordKey,
        location: ElementDescriptionLocation
    ): String? {
        var elementDescription: String? = keywordKey
            .parent.let { it as? ElixirKeywordPair }
            ?.parent?.let { it as? ElixirKeywords }
            ?.parent?.let { it as? ElixirList }
            ?.parent?.let { it as ElixirAccessExpression }
            ?.parent?.let { it as? QuotableKeywordPair }
            ?.keywordKey?.let { outerKeywordKey ->
                if (outerKeywordKey.text == "bind_quoted" && location === UsageViewTypeLocation.INSTANCE) {
                    "quote bound variable"
                } else {
                    null
                }
            }

        if (elementDescription == null && location === UsageViewTypeLocation.INSTANCE) {
            elementDescription = "keyword key"
        }

        return elementDescription
    }

    private fun getElementDescription(variable: ElixirVariable, location: ElementDescriptionLocation): String? =
        if (location === UsageViewLongNameLocation.INSTANCE || location === UsageViewShortNameLocation.INSTANCE) {
            variable.name
        } else if (location === UsageViewTypeLocation.INSTANCE) {
            VARIABLE_USAGE_VIEW_TYPE_LOCATION_ELEMENT_DESCRIPTION
        } else {
            null
        }

    private fun getElementDescription(
        maybeModuleName: MaybeModuleName,
        location: ElementDescriptionLocation
    ): String? {
        var elementDescription: String? = if (maybeModuleName is QualifiableAlias) {
            if (location === UsageViewShortNameLocation.INSTANCE) {
                val fullyQualifiedName = maybeModuleName.fullyQualifiedName()

                if (isAliasCallAs(maybeModuleName)) {
                    "as: $fullyQualifiedName"
                } else {
                    fullyQualifiedName
                }
            } else {
                null
            }
        } else {
            null
        }

        if (location === UsageViewTypeLocation.INSTANCE) {
            elementDescription = if (isAliasCallArgument(maybeModuleName)) {
                "alias"
            } else {
                "module"
            }
        }

        return elementDescription
    }

    private fun getElementDescription(moduleImpl: BeamModule, location: ElementDescriptionLocation): String? =
        when (location) {
            UsageViewNodeTextLocation.INSTANCE -> "defmodule ${moduleImpl.name}"
            UsageViewLongNameLocation.INSTANCE, UsageViewShortNameLocation.INSTANCE -> moduleImpl.name
            UsageViewTypeLocation.INSTANCE -> "module"
            else -> null
        }

    private fun getElementDescription(
        typeDefinitionImpl: BeamTypeDefinition,
        location: ElementDescriptionLocation
    ): String? =
        when (location) {
            UsageViewNodeTextLocation.INSTANCE -> {
                val moduleAttribute = typeDefinitionImpl.visibility.moduleAttribute
                val name = typeDefinitionImpl.name
                val parameters = (0 until typeDefinitionImpl.arity).joinToString(", ") { i -> "p${i}" }

                "@$moduleAttribute $name(${parameters}) :: ..."
            }
            UsageViewLongNameLocation.INSTANCE, UsageViewShortNameLocation.INSTANCE -> typeDefinitionImpl.name
            UsageViewTypeLocation.INSTANCE -> "type"
            else -> null
        }

    private fun getElementDescription(
        callDefinitionImpl: BeamCallDefinition,
        location: ElementDescriptionLocation
    ): String? =
        when (location) {
            UsageViewNodeTextLocation.INSTANCE -> {
                val capabilities = CallableDeclaration.capabilitiesOf(callDefinitionImpl, ResolveState.initial()) ?: return null
                val macro = CallableDeclaration.Definer.writing(capabilities)?.keyword ?: return null
                val name = callDefinitionImpl.exportedName()
                val parameterCount = callDefinitionImpl.nameArityInterval.arityInterval.closed().last
                val parameters = (0 until parameterCount).joinToString(", ") { i -> "p${i}" }

                "$macro $name(${parameters}), do: ..."
            }
            UsageViewLongNameLocation.INSTANCE, UsageViewShortNameLocation.INSTANCE ->
                callDefinitionImpl.nameArityInterval.toString()
            UsageViewTypeLocation.INSTANCE ->
                if (CallableDeclaration.isCompileTime(callDefinitionImpl)) "macro" else "function"
            else -> null
        }

    private fun getElementDescription(call: Call, location: ElementDescriptionLocation): String? =
        when (val form = CallableDeclaration.formOf(call, ResolveState.initial())) {
            null -> getNonDeclarationDescription(call, location)
            else -> getDeclarationDescription(call, form, location)
        }

    /** Every declaring form, so a new one fails to compile here until it is described. */
    private fun getDeclarationDescription(
        call: Call,
        form: CallableDeclaration.Form,
        location: ElementDescriptionLocation
    ): String? =
        when (form) {
            CallableDeclaration.Form.CLAUSE -> CallDefinitionClause.elementDescription(call, location)
            CallableDeclaration.Form.CALLBACK -> Callback.elementDescription(call, location)
            CallableDeclaration.Form.DELEGATION -> Delegation.elementDescription(location)
            CallableDeclaration.Form.EXCEPTION ->
                org.elixir_lang.structure_view.element.Exception.elementDescription(call, location)
            CallableDeclaration.Form.EEX_FUNCTION_FROM, CallableDeclaration.Form.GENERATOR_EMBED ->
                if (location === UsageViewTypeLocation.INSTANCE) "function" else null
        }

    private fun getNonDeclarationDescription(call: Call, location: ElementDescriptionLocation): String? =
        when {
            CallDefinitionSpecification.`is`(call) -> CallDefinitionSpecification.elementDescription(call, location)
            Implementation.`is`(call) -> Implementation.elementDescription(location)
            Import.`is`(call) -> Import.elementDescription(call, location)
            org.elixir_lang.psi.Module.`is`(call) -> Module.elementDescription(call, location)
            Overridable.`is`(call) -> Overridable.elementDescription(location)
            Protocol.`is`(call) -> Module.elementDescription(call, location)
            QuoteMacro.`is`(call) -> org.elixir_lang.structure_view.element.Quote.elementDescription(call, location)
            Structure.`is`(call) -> Structure.elementDescription(call, location)
            Type.`is`(call) -> Type.elementDescription(location)
            Use.`is`(call) -> Use.elementDescription(call, location)
            call is AtUnqualifiedNoParenthesesCall<*> -> getElementDescription(call, location)
            Callable.isBitStreamSegmentOption(call) -> Callable.bitStringSegmentOptionElementDescription(call, location)
            Callable.isIgnored(call) -> Callable.ignoredElementDescription(location)
            Callable.isParameter(call) -> Callable.parameterElementDescription(call, location)
            Callable.isParameterWithDefault(call) -> Callable.parameterWithDefaultElementDescription(location)
            Callable.isVariable(call) -> Callable.variableElementDescription(call, location)
            else ->
                if (location === UsageViewTypeLocation.INSTANCE) {
                    "call"
                } else {
                    null
                }
        }

    private fun getElementDescription(
        atUnqualifiedNoParenthesesCall: AtUnqualifiedNoParenthesesCall<*>,
        location: ElementDescriptionLocation
    ): String? =
        if (location === UsageViewLongNameLocation.INSTANCE || location === UsageViewShortNameLocation.INSTANCE) {
            atUnqualifiedNoParenthesesCall.name
        } else if (location === UsageViewTypeLocation.INSTANCE) {
            "module attribute"
        } else {
            null
        }

    private fun isAliasCallArgument(element: Call): Boolean = element.isCalling(KERNEL, ALIAS)

    private tailrec fun isAliasCallArgument(element: PsiElement): Boolean =
        when (element) {
            is Call ->
                isAliasCallArgument(element)
            is Arguments,
            is ElixirAccessExpression,
            is ElixirMultipleAliases,
            is QualifiableAlias,
            is QualifiedMultipleAliases ->
                isAliasCallArgument(element.parent)
            is QuotableKeywordPair ->
                isAliasCallAs(element)
            else ->
                false
        }

    private fun isAliasCallAs(element: QuotableKeywordPair): Boolean =
        if (element.hasKeywordKey("as")) {
            val parent = element.parent

            if (parent is QuotableKeywordList) {
                val grandParent = parent.parent

                isAliasCallArgument(grandParent)
            } else {
                false
            }
        } else {
            false
        }

    private tailrec fun isAliasCallAs(element: PsiElement): Boolean =
        when (element) {
            is ElixirAccessExpression, is QualifiableAlias -> isAliasCallAs(element.parent)
            is QuotableKeywordPair -> isAliasCallAs(element)
            else -> false
        }

    companion object {
        const val VARIABLE_USAGE_VIEW_TYPE_LOCATION_ELEMENT_DESCRIPTION = "variable"
    }
}
