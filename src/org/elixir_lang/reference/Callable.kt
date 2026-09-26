package org.elixir_lang.reference

import com.google.common.collect.Sets
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageViewLongNameLocation
import com.intellij.usageView.UsageViewShortNameLocation
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.IncorrectOperationException
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.annotator.Parameter
import org.elixir_lang.model.psi.variable.VariableReference
import org.elixir_lang.model.psi.variable.VariableSymbol
import org.elixir_lang.psi.*
import org.elixir_lang.psi.ElementDescriptionProvider.Companion.VARIABLE_USAGE_VIEW_TYPE_LOCATION_ELEMENT_DESCRIPTION
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.Named
import org.elixir_lang.psi.call.name.Function
import org.elixir_lang.psi.call.name.Module
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.DEFAULT_OPERATOR
import org.elixir_lang.psi.impl.declarations.UseScopeImpl
import org.elixir_lang.psi.impl.declarations.UseScopeImpl.selector
import org.elixir_lang.psi.impl.declarations.selfAndFollowingSiblingsSearchScope
import org.elixir_lang.psi.operation.Addition
import org.elixir_lang.psi.operation.InMatch
import org.elixir_lang.psi.operation.Match
import org.elixir_lang.psi.operation.Type
import org.elixir_lang.psi.qualification.Unqualified
import org.elixir_lang.psi.scope.variable.Variants
import org.elixir_lang.resolvesToMacro
import org.jetbrains.annotations.Contract

class Callable : PsiReferenceBase<Call>, PsiPolyVariantReference {

    /*
     * Constructors
     */

    constructor(call: Call) : super(call)

    private constructor(call: Call, rangeInCall: TextRange) : super(call, rangeInCall)

    @Throws(IncorrectOperationException::class)
    override fun handleElementRename(newElementName: String): PsiElement {
        return (myElement as NamedElement).setName(newElementName)
    }

    /*
     *
     * Instance Methods
     *
     */

    /*
     * Public Instance Methods
     */

    /**
     * Returns the array of String, [PsiElement] and/or [com.intellij.codeInsight.lookup.LookupElement]
     * instances representing all identifiers that are visible at the location of the reference. The contents
     * of the returned array is used to build the lookup list for basic code completion. (The list
     * of visible identifiers may not be filtered by the completion prefix string - the
     * filtering is performed later by IDEA core.)
     *
     * Qualified completion is handled by
     * [org.elixir_lang.code_insight.completion.provider.CallDefinitionClause.addCompletions]
     *
     * @return the array of available identifiers.
     */
    override fun getVariants(): Array<Any> =
        if (myElement is Unqualified) {
            val variableLookupElementList = if (myElement is UnqualifiedNoArgumentsCall<*>) {
                Variants.lookupElementList(myElement)
            } else {
                emptyList()
            }

            val callDefinitionClauseLookupElementList =
                org.elixir_lang.psi.scope.call_definition_clause.Variants.lookupElementList(myElement)

            (variableLookupElementList + callDefinitionClauseLookupElementList).toTypedArray()
        } else {
            emptyArray()
        }

    override fun isReferenceTo(element: PsiElement): Boolean =
        multiResolve(false).any {
            it.isValidResult && element.manager.areElementsEquivalent(it.element, element)
        }

    /**
     * Returns the results of resolving the reference.
     *
     * @param incompleteCode if true, the code in the context of which the reference is
     * being resolved is considered incomplete, and the method may return additional
     * invalid results.
     * @return the array of results for resolving the reference.
     */
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        resolveWithCaching(myElement.project, this, incompleteCode)

    /**
     * Returns the element which is the target of the reference.
     *
     * @return the target element, or null if it was not possible to resolve the reference to a valid target.
     */
    override fun resolve(): PsiElement? = multiResolve(false).singleOrNull()?.element

    /*
     * Protected Instance Methods
     */

    override fun calculateDefaultRangeInElement(): TextRange {
        val myElementRangeInDocument = myElement.textRange
        val myElementStartOffset = myElementRangeInDocument.startOffset

        return (myElement as? Named)?.let { named ->
            named.nameIdentifier?.let { nameIdentifier ->
                val nameIdentifierRangeInDocument = nameIdentifier.textRange
                TextRange(
                    nameIdentifierRangeInDocument.startOffset - myElementStartOffset,
                    nameIdentifierRangeInDocument.endOffset - myElementStartOffset
                )
            }
        } ?: TextRange(0, myElementRangeInDocument.endOffset - myElementStartOffset)
    }

    companion object {
        val BIT_STRING_TYPES: Set<String> = Sets.newHashSet(
            "binary",
            "bits",
            "bitstring",
            "bytes",
            "float",
            "integer",
            "utf16",
            "utf32",
            "utf8"
        )

        @JvmField
        val IGNORED = "_"

        @Contract(pure = true)
        fun bitStringSegmentOptionElementDescription(
            call: Call,
            location: ElementDescriptionLocation
        ): String? {
            var elementDescription = call.name?.let { name ->
                when {
                    BIT_STRING_ENDIANNESS.contains(name) -> bitStringEndiannessElementDescription(location)
                    BIT_STRING_SIGNEDNESS.contains(name) -> bitStringSignednessElementDescription(location)
                    BIT_STRING_TYPES.contains(name) -> bitStringTypeElementDescription(location)
                    else -> null
                }
            }

            // getType is @NotNull, so must have fallback
            if (elementDescription == null && location === UsageViewTypeLocation.INSTANCE) {
                elementDescription = "bitstring segment option"
            }

            return elementDescription
        }

        private val BIT_STRING_ENDIANNESS = Sets.newHashSet(
            "big",
            "little",
            "native"
        )
        private val BIT_STRING_SIGNEDNESS = Sets.newHashSet(
            "signed",
            "unsigned"
        )

        @Contract(pure = true)
        private fun bitStringEndiannessElementDescription(location: ElementDescriptionLocation): String? =
            if (location === UsageViewTypeLocation.INSTANCE) {
                "bitstring endianness"
            } else {
                null
            }

        @Contract(pure = true)
        private fun bitStringSignednessElementDescription(location: ElementDescriptionLocation): String? =
            if (location === UsageViewTypeLocation.INSTANCE) {
                "bitstring signedness"
            } else {
                null
            }

        @Contract(pure = true)
        private fun bitStringTypeElementDescription(location: ElementDescriptionLocation): String? =
            if (location === UsageViewTypeLocation.INSTANCE) {
                "bitstring type"
            } else {
                null
            }

        /**
         * Callable for any of the following built-in definers
         *
         *
         *  * `def`
         *  * `defimpl`
         *  * `defmacro`
         *  * `defmacrop`
         *  * `defmodule`
         *  * `defp`
         *  * `defprotocol`
         *
         *
         * @param call definer call
         */
        fun definer(call: Call): Callable {
            val functionNameElement = call.functionNameElement()!!

            // Can't use `getStartOffsetInParent` because `functionNameElement` doesn't have to be a direct child of `call`
            // Can't use `getTextOffset` because that's the offset to the navigationElement, which is nameIdentifier
            val functionNameElementStartOffset = functionNameElement.textRange.startOffset
            val callStartOffset = call.textRange.startOffset
            val startOffset = functionNameElementStartOffset - callStartOffset

            return Callable(call, TextRange(startOffset, startOffset + functionNameElement.textLength))
        }

        fun ignoredElementDescription(location: ElementDescriptionLocation): String? =
            if (location === UsageViewTypeLocation.INSTANCE) {
                "ignored"
            } else {
                null
            }

        @Contract(pure = true)
        fun isBitStreamSegmentOption(element: PsiElement): Boolean =
            typeContext(element)?.let { type ->
                val typeParent = type.parent

                if (typeParent is ElixirBitString) {
                    val rightOperand = type.rightOperand()

                    if (PsiTreeUtil.isAncestor(rightOperand, element, false)) {
                        isBitStreamSegmentOptionDown(rightOperand!!, element)
                    } else {
                        false
                    }
                } else {
                    false
                }
            } ?: false

        @RequiresReadLock
        @JvmStatic
        @Contract(pure = true)
        fun isDefiner(call: Call): Boolean =
            // The forms whose `def*` names no call of its own; an EEx `function_from_*` or embed is a call to its macro.
            CallableDeclaration.headBindingFormOf(call) != null ||
                    Implementation.`is`(call) ||
                    org.elixir_lang.psi.Module.`is`(call) ||
                    Protocol.`is`(call)

        @JvmStatic
        @Contract(pure = true)
        fun isIgnored(element: PsiElement): Boolean =
            if (element is ElixirKeywordKey || element is UnqualifiedNoArgumentsCall<*>) {
                (element as PsiNamedElement).name == IGNORED
            } else {
                false
            }

        @Contract(pure = true)
        @JvmStatic
        fun isParameter(ancestor: PsiElement): Boolean =
            Parameter(ancestor).let { Parameter.putParameterized(it).type != null }

        @JvmStatic
        fun isParameterWithDefault(element: PsiElement): Boolean {
            val parent = element.parent

            return if (parent is InMatch) {
                val operator = parent.operator()
                val operatorText = operator.text

                if (operatorText == DEFAULT_OPERATOR) {
                    val defaulted = parent.leftOperand()

                    if (defaulted != null && defaulted.isEquivalentTo(element)) {
                        isParameter(parent)
                    } else {
                        false
                    }
                } else {
                    false
                }
            } else {
                false
            }
        }

        @Contract(pure = true)
        @JvmStatic
        fun isVariable(element: PsiElement): Boolean =
            /* The key of `quote bind_quoted: [name: value] do ... end` is the bound variable itself, so it answers
               before the walk; met as an ancestor, a quoted key `["#{name}": 1]` is looked through like a string. */
            element is ElixirKeywordKey || isVariableFrom(element)

        @Contract(pure = true)
        private tailrec fun isVariableFrom(ancestor: PsiElement): Boolean =
            when (VariableWalk.classify(ancestor)) {
                VariableWalk.Bucket.DECLARES -> true
                // a detached element has nothing above it to declare a variable
                VariableWalk.Bucket.TRANSPARENT -> isVariableFrom(ancestor.parent ?: return false)
                VariableWalk.Bucket.CALL -> isVariable(ancestor as Call)
                VariableWalk.Bucket.STOP, VariableWalk.Bucket.LEAF -> false
            }

        @RequiresReadLock
        fun parameterElementDescription(call: Call, location: ElementDescriptionLocation): String? =
            if (location === UsageViewLongNameLocation.INSTANCE || location === UsageViewShortNameLocation.INSTANCE) {
                call.name
            } else if (location === UsageViewTypeLocation.INSTANCE) {
                val parent = call.parent

                if (parent is ElixirNoParenthesesOneArgument) {
                    val grandParent = parent.getParent()

                    if (grandParent is Call) {
                        if (org.elixir_lang.psi.CallDefinitionClause.`is`(grandParent)) {
                            org.elixir_lang.psi.CallDefinitionClause.elementDescription(grandParent, location)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                } else {
                    null
                } ?: "parameter"
            } else {
                null
            }

        fun parameterWithDefaultElementDescription(location: ElementDescriptionLocation): String? =
            if (location === UsageViewTypeLocation.INSTANCE) {
                "parameter with default"
            } else {
                null
            }

        fun variableElementDescription(call: Call, location: ElementDescriptionLocation): String? =
            if (location === UsageViewLongNameLocation.INSTANCE || location === UsageViewShortNameLocation.INSTANCE) {
                call.name
            } else if (location === UsageViewTypeLocation.INSTANCE) {
                VARIABLE_USAGE_VIEW_TYPE_LOCATION_ELEMENT_DESCRIPTION
            } else {
                null
            }

        /**
         * Scoped by the walk above it, and by the scope of every declaration it belongs with: a declaration's
         * chain root, or the declarations a use reads and their chain roots. A use scope must hold every
         * reference, and the walk from a use starts at its own statement, after the declaration's. Finding what
         * it belongs with resolves, which needs the indices, so while they are being built the walk alone
         * answers; with them, the answer is kept until the PSI changes, as highlighting, search and rename each
         * ask for it.
         */
        @RequiresReadLock
        @JvmStatic
        fun variableUseScope(call: UnqualifiedNoArgumentsCall<*>): SearchScope =
            if (DumbService.isDumb(call.project)) {
                variableUseScope(call, call)
            } else {
                CachedValuesManager.getCachedValue(call) {
                    CachedValueProvider.Result.create(
                        variableUseScopeWithBelongings(call),
                        PsiModificationTracker.MODIFICATION_COUNT
                    )
                }
            }

        /** The walk's scope joined with each belonging's; a module scope joins the walk, never replaces it. */
        @RequiresReadLock
        private fun variableUseScopeWithBelongings(call: UnqualifiedNoArgumentsCall<*>): SearchScope {
            val belongsWith = if (VariableSymbol.isDeclaration(call)) {
                listOfNotNull(VariableSymbol.fromDeclaration(call)?.chainRootDeclaration()?.takeIf { it !== call })
            } else {
                declarationsRead(call)
            }

            return belongsWith.fold(variableUseScope(call, call) as SearchScope) { scope, declaration ->
                val theirs = UseScopeImpl.get(declaration)
                // the platform's union of two local scopes replaces overlapping elements with their common parent
                if (scope is LocalSearchScope && theirs is LocalSearchScope) {
                    scope.including(theirs)
                } else {
                    scope.union(theirs)
                }
            }
        }

        /** Both scopes' elements in text order, less any inside another; the platform's union climbs to parents. */
        private fun LocalSearchScope.including(other: LocalSearchScope): LocalSearchScope {
            val kept = ArrayList<PsiElement>(scope.size + other.scope.size)

            for (element in (scope.asList() + other.scope).sortedWith(BY_START_THEN_WIDEST)) {
                ProgressManager.checkCanceled()
                // sorted so, an element inside another follows it, so the last kept is the only one to test
                if (kept.lastOrNull()?.let { PsiTreeUtil.isAncestor(it, element, false) } != true) kept += element
            }

            return LocalSearchScope(kept.toTypedArray())
        }

        private val BY_START_THEN_WIDEST = compareBy<PsiElement>(
            { it.containingFile.virtualFile?.path },
            { it.textRange.startOffset },
            { -it.textRange.length }
        )

        /** The declarations [use] reads, resolved as usage membership resolves them, and their chain roots. */
        private fun declarationsRead(use: UnqualifiedNoArgumentsCall<*>): List<UnqualifiedNoArgumentsCall<*>> =
            VariableReference.resolveSymbols(use)
                .flatMap { listOfNotNull(it.declaration(), it.chainRootDeclaration()) }
                .distinct()

        /**
         * Searches downward from `ancestor`, only returning true if `element` is a type, unit, size or
         * modifier.
         *
         * @return `true` if `element` is a type, unit, size, or modifier.  i.e. something separated by
         * `-`. *   `false` if `element` is a variable used in `size(variable)`.
         */
        private fun isBitStreamSegmentOptionDown(ancestor: PsiElement, element: PsiElement): Boolean {
            var `is` = false

            if (ancestor.isEquivalentTo(element)) {
                `is` = true
            } else if (ancestor is Addition) {

                val leftOperand = ancestor.leftOperand()

                if (leftOperand != null) {
                    `is` = isBitStreamSegmentOptionDown(leftOperand, element)
                }

                if (!`is`) {
                    val rightOperand = ancestor.rightOperand()

                    if (rightOperand != null) {
                        `is` = isBitStreamSegmentOptionDown(rightOperand, element)
                    }
                }
            } else if (ancestor is UnqualifiedParenthesesCall<*>) {
                val ancestorCall = ancestor as Call
                val functionNameElement = ancestorCall.functionNameElement()

                if (functionNameElement != null && functionNameElement.isEquivalentTo(element)) {
                    `is` = true
                }
            }

            return `is`
        }

        @Contract(pure = true)
        private fun isVariable(call: Call): Boolean =
            when {
                call is UnqualifiedNoArgumentsCall<*> -> {
                    val name = call.getName()

                    // _ is an "ignored" not a variable
                    if (name == null || name != IGNORED) {
                        call.parent?.let { isVariableFrom(it) } ?: false
                    } else {
                        false
                    }
                }
                // module attribute, so original may be a unqualified no argument type name
                call is AtUnqualifiedNoParenthesesCall<*> -> false
                call.isCalling(Module.KERNEL, Function.DESTRUCTURE) -> true
                call.isCallingMacro(Module.KERNEL, Function.FOR) -> true
                call.isCalling(Module.KERNEL, Function.VAR_BANG) -> true
                /* A `def`'s arguments are values, so they cannot be declarations; a `defmacro`'s
                   are quoted and may well be bound - the three `Kernel` names above are the built-in
                   cases of exactly that. Which kind this is cannot be told from the syntax, so
                   resolve it. */
                call is UnqualifiedParenthesesCall<*> -> resolvesToMacro(call)
                else -> call.parent?.let { isVariableFrom(it) } ?: false
            }

        private fun variableUseScope(call: Call): LocalSearchScope =
            when (selector(call)) {
                UseScopeImpl.UseScopeSelector.PARENT -> scopeAbove(call)
                UseScopeImpl.UseScopeSelector.SELF -> LocalSearchScope(call)
                UseScopeImpl.UseScopeSelector.SELF_AND_FOLLOWING_SIBLINGS -> {
                    val selfAndFollowingSiblingList = ArrayList<PsiElement>()
                    var sibling: PsiElement? = call

                    while (sibling != null) {
                        selfAndFollowingSiblingList.add(sibling)

                        sibling = sibling.nextSibling
                    }

                    selfAndFollowingSiblingList.toTypedArray().let { LocalSearchScope(it) }
                }
            }

        private fun variableUseScope(match: Match): LocalSearchScope =
            // a detached match has nothing above it to scope the variable
            (match.parent ?: return LocalSearchScope.EMPTY).let { parent ->
                when (parent) {
                    // a template is one expression, so a tag's block continues in the tags after it
                    is ElixirEexTag -> parent.selfAndFollowingSiblingsSearchScope()
                    is ElixirStabBody -> {
                        val ancestor = PsiTreeUtil.getContextOfType(
                            parent,
                            ElixirAnonymousFunction::class.java,
                            ElixirBlockItem::class.java,
                            ElixirDoBlock::class.java,
                            ElixirParentheticalStab::class.java,
                            ElixirStabOperation::class.java
                        )

                        if (ancestor is ElixirParentheticalStab) {
                            variableUseScope(match, parent)
                        } else {
                            /* all non-ElixirParentheticalStab are block-like and so could have multiple statements after the match
                               where the match variable is used */
                            match.selfAndFollowingSiblingsSearchScope()
                        }
                    }

                    else -> scopeAbove(match)
                }
            }

        // a detached element has nothing above it to scope the variable
        private fun scopeAbove(element: PsiElement): LocalSearchScope =
            element.parent?.let { variableUseScope(element, it) } ?: LocalSearchScope.EMPTY

        /** The scope [ancestor] gives the variable below it, reached through [child], its direct child. */
        private tailrec fun variableUseScope(child: PsiElement, ancestor: PsiElement): LocalSearchScope =
            when (VariableUseScopeWalk.classify(ancestor)) {
                // a detached element has nothing above it to scope the variable
                VariableUseScopeWalk.Bucket.PARENT ->
                    variableUseScope(ancestor, ancestor.parent ?: return LocalSearchScope.EMPTY)
                VariableUseScopeWalk.Bucket.SELF -> LocalSearchScope(ancestor)
                VariableUseScopeWalk.Bucket.FOLLOWING -> child.selfAndFollowingSiblingsSearchScope()
                VariableUseScopeWalk.Bucket.MATCH -> variableUseScope(ancestor as Match)
                VariableUseScopeWalk.Bucket.CALL -> variableUseScope(ancestor as Call)
                VariableUseScopeWalk.Bucket.EMPTY, VariableUseScopeWalk.Bucket.LEAF -> LocalSearchScope.EMPTY
            }
    }
}

/* Needs to be wrapped in a ReadAction for Find Usage of call definition clauses when the potential usage is a Call as
   the Find Usage isn't executed in an overall ReadAction, which is checked by resolveWithCaching */
private fun resolveWithCaching(project: Project, callable: Callable, incompleteCode: Boolean): Array<ResolveResult> =
    ApplicationManager.getApplication().runReadAction(ResolveWithCachingComputable(project, callable, incompleteCode))

private class ResolveWithCachingComputable(
    val project: Project,
    val callable: Callable,
    val incompleteCode: Boolean
) : Computable<Array<ResolveResult>> {
    override fun compute(): Array<ResolveResult> {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        return ResolveCache
            .getInstance(project)
            .resolveWithCaching(
                callable,
                org.elixir_lang.reference.resolver.Callable,
                false,
                incompleteCode
            )
    }
}

private fun typeContext(element: PsiElement): Type? =
    ApplicationManager.getApplication().runReadAction(TypeContextComputable(element))

private class TypeContextComputable(val element: PsiElement) : Computable<Type?> {
    override fun compute(): Type? {
        ApplicationManager.getApplication().assertReadAccessAllowed()
        return PsiTreeUtil.getContextOfType(element, Type::class.java)
    }
}
