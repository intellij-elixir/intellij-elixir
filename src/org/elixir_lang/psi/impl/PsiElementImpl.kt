package org.elixir_lang.psi.impl

import com.intellij.openapi.editor.Document
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.findParentInFile
import com.intellij.psi.util.siblings
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.call.name.Function.ALIAS
import org.elixir_lang.psi.call.name.Function.CREATE
import org.elixir_lang.Module.nest
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.impl.call.maybeModularNameToModulars
import org.elixir_lang.psi.operation.Infix
import org.elixir_lang.psi.operation.Match
import org.elixir_lang.psi.scope.WhileIn.whileIn
import org.elixir_lang.util.AccumulatorContinue
import org.elixir_lang.util.foldWhile
import org.jetbrains.annotations.Contract

@RequiresReadLock
fun PsiElement.ancestorSequence() = generateSequence(this) { it.parent }

@RequiresReadLock
fun PsiElement.document(): Document? = containingFile.viewProvider.document

@RequiresReadLock
tailrec fun PsiElement.selfOrEnclosingMacroCall(): Call? =
    when (this) {
        is ElixirDoBlock ->
            parent.let { it as? Call }

        is ElixirAnonymousFunction -> {
            val generator = when (val grandParent = parent.let { it as? ElixirAccessExpression }?.parent) {
                // `defhelper = fn` in
                //  defhelper = quote @anno do
                //      defhelper = fn helper, vars, opts, bins, segs, trailing_slash? ->
                //        def unquote(:"#{helper}_path")(conn_or_endpoint, unquote(Macro.escape(opts)), unquote_splicing(vars)) do
                //          unquote(:"#{helper}_path")(conn_or_endpoint, unquote(Macro.escape(opts)), unquote_splicing(vars), [])
                //        end
                is Match -> grandParent

                is Arguments -> {
                    grandParent.parent.let { it as? ElixirMatchedParenthesesArguments }?.parent
                        .let { it as? Call }?.let { call ->
                            if (call.resolvedModuleName() == "Enum" &&
                                call.functionName() in arrayOf("each", "map", "reduce")
                            ) {
                                call
                            } else {
                                null
                            }
                        }
                }

                else -> null
            }

            generator?.parent?.selfOrEnclosingMacroCall()
        }

        is Arguments,
        is AtUnqualifiedNoParenthesesCall<*>,
        is ElixirAccessExpression,
        is ElixirAssociations,
        is ElixirAssociationsBase,
        is ElixirBlockItem,
        is ElixirBlockList,
        is ElixirContainerAssociationOperation,
        is ElixirList,
        is ElixirMapArguments,
        is ElixirMapConstructionArguments,
        is ElixirMapOperation,
        is ElixirMatchedParenthesesArguments,
        is ElixirNoParenthesesManyStrictNoParenthesesExpression,
        is ElixirParentheticalStab,
        is ElixirStab,
        is ElixirStabBody,
        is ElixirStabOperation,
        is ElixirTuple,
        is Infix,
        is QualifiedAlias,
        is QualifiedMultipleAliases ->
            parent.selfOrEnclosingMacroCall()

        is Call ->
            when {
                isCalling(KERNEL, ALIAS) -> this
                isCalling(org.elixir_lang.psi.call.name.Module.MODULE, CREATE, 3) -> this
                else -> null
            }

        is QuotableKeywordPair ->
            if (blockKeyword() != null) {
                parent.let { it as? QuotableKeywordList }?.parent.let { keywordListParent ->
                    when (keywordListParent) {
                        is ElixirNoParenthesesOneArgument -> keywordListParent
                        is ElixirParenthesesArguments -> {
                            keywordListParent.parent.let { it as? ElixirMatchedParenthesesArguments }
                        }

                        else -> null
                    }
                }?.parent.let { it as? Call }
            } else {
                null
            }

        else -> null
    }

/**
 * @return `null` if this element is at top-level
 */
@RequiresReadLock
@Contract(pure = true)
fun PsiElement.enclosingMacroCall(): Call? = parent.selfOrEnclosingMacroCall()

private val isModuleName = { c: PsiElement -> c is MaybeModuleName && c.isModuleName }

/**
 * Returns `true` if this element is inside a `defmodule` (or similar module definition).
 * Unlike [getModuleName], this does not assemble the full module name string - it is a
 * cheap boolean check suitable for hot paths like breakpoint availability.
 */
fun PsiElement.isInsideModule(): Boolean =
    findParentInFile(withSelf = true) { e -> e.children.any(isModuleName) } != null

@RequiresReadLock
fun PsiElement.getModuleName(): String? {
    // findParentInFile stops at PsiFile boundary, never touches PsiDirectory - avoids DiskQueryRelay VFS I/O
    return findParentInFile(withSelf = true) { e ->
        e.children.any(isModuleName)
    }?.let { moduleDefinition ->
        moduleDefinition.children.firstOrNull(isModuleName)?.let { nameElement ->
            val name = (moduleDefinition as? Call)?.let { Module.moduleName(SyntacticCall.of(it)) }

            if (name?.absolute == true) {
                name.name
            } else {
                val relative = name?.name ?: nameElement.text

                moduleDefinition.parent.getModuleName()?.let { parentModuleName -> nest(parentModuleName, relative) }
                    ?: relative
            }
        }
    }
}

fun <R> PsiElement.foldChildrenWhile(
    initial: R,
    operation: (PsiElement, acc: R) -> AccumulatorContinue<R>
): AccumulatorContinue<R> =
    when (this) {
        is ElixirAccessExpression ->
            operation(firstChild, initial)

        is ElixirList, is ElixirStabBody -> {
            var child: PsiElement? = firstChild
            var final = AccumulatorContinue(initial, true)

            while (child != null) {
                final = if (child is ElixirAccessExpression) {
                    child.foldChildrenWhile(initial, operation)
                } else {
                    operation(child, final.accumulator)
                }

                if (!final.`continue`) {
                    break
                } else {
                    child = child.nextSibling
                }
            }

            final
        }

        else ->
            AccumulatorContinue(initial, true)
    }

@RequiresReadLock
fun PsiElement.macroChildCallList(): MutableList<Call> {
    val callList: MutableList<Call>

    if (this is ElixirAccessExpression) {
        callList = this@macroChildCallList.getFirstChild().macroChildCallList()
    } else if (this is ElixirList || this is ElixirStabBody) {
        callList = ArrayList()

        var child: PsiElement? = firstChild
        while (child != null) {
            ProgressManager.checkCanceled()
            if (child is Call) {
                callList.add(child)
            } else if (child is ElixirAccessExpression) {
                callList.addAll(child.macroChildCallList())
            }
            child = child.nextSibling
        }
    } else {
        callList = mutableListOf()
    }

    return callList
}

/**
 * @return [Call] for the `defmodule`, `defimpl`, or `defprotocol` that defines
 * `maybeAlias` after it is resolved through any `alias`es or `use`.
 */
@RequiresReadLock
@Contract(pure = true)
fun PsiElement.maybeModularNameToModulars(
    maxScope: PsiElement,
    useCall: Call?,
    incompleteCode: Boolean
): Set<PsiNamedElement> = when (val strippedMaybeModuleName = stripAccessExpression()) {
    is ElixirAtom -> strippedMaybeModuleName.maybeModularNameToModulars(incompleteCode)
    is QualifiableAlias -> strippedMaybeModuleName.maybeModularNameToModulars(maxScope)
    is Call -> strippedMaybeModuleName.maybeModularNameToModulars(useCall)
    else -> emptySet()
}

fun PsiElement.moduleWithDependentsScope(): GlobalSearchScope =
    containingFile
        .virtualFile
        ?.let { virtualFile ->
            ModuleUtilCore
                .findModuleForFile(virtualFile, project)
                // module can be null for scratch files
                ?.let { GlobalSearchScope.moduleWithDependentsScope(it) }
        }
        ?: GlobalSearchScope.allScope(project)

fun PsiElement.prevSiblingSequence() = generateSequence(this) { it.prevSibling }

fun PsiElement.whileInChildExpressions(
    forward: Boolean = true,
    keepProcessing: (element: PsiElement) -> Boolean
): Boolean =
    childExpressions(forward)
        .let { whileIn(it, keepProcessing) }

fun <R> PsiElement.childExpressionsFoldWhile(
    forward: Boolean,
    initial: R,
    folder: (
        element: PsiElement, accumulator: R
    ) -> AccumulatorContinue<R>
): AccumulatorContinue<R> =
    childExpressions(forward).foldWhile(initial, folder)

fun PsiElement.childExpressions(forward: Boolean = true): Sequence<PsiElement> {
    val seed = if (forward) {
        firstChild
    } else {
        lastChild
    }

    return seed?.siblingExpressions(forward, withSelf = true) ?: emptySequence()
}

fun PsiElement.siblingExpressions(forward: Boolean = true, withSelf: Boolean = true): Sequence<PsiElement> =
    siblings(forward, withSelf).filter(PsiElement::isExpression)

fun PsiElement.isExpression(): Boolean =
    when (this) {
        is ElixirEndOfExpression, is PsiComment, is PsiWhiteSpace -> false
        else -> node is CompositeElement
    }

@Contract(pure = true)
fun PsiElement.siblingExpression(function: (PsiElement) -> PsiElement?): PsiElement? {
    var expression: PsiElement? = this

    do {
        expression = function(expression ?: return null)
    } while (expression is ElixirEndOfExpression ||
        expression is LeafPsiElement ||
        expression is PsiComment ||
        expression is PsiWhiteSpace
    )

    return expression
}

@Contract(pure = true)
fun PsiElement.stripAccessExpression(): PsiElement = (this as? ElixirAccessExpression)?.stripOnlyChildParent() ?: this
fun Array<PsiElement>.stripAccessExpressions(): List<PsiElement> = map(PsiElement::stripAccessExpression)

@Contract(pure = true)
fun PsiElement.stripOnlyChildParent(): PsiElement = children.singleOrNull() ?: this
