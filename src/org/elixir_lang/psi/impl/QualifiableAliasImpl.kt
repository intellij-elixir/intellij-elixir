package org.elixir_lang.psi.impl

import com.intellij.psi.*
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.isAncestor
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.*
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.call.maybeModularNameToModulars
import org.elixir_lang.psi.operation.Normalized.operatorIndex
import org.elixir_lang.psi.operation.Operation
import org.elixir_lang.psi.stub.type.call.Stub.isModular
import org.elixir_lang.reference.Module
import org.jetbrains.annotations.Contract

@RequiresReadLock
fun QualifiableAlias.computeReference(): PsiPolyVariantReference? =
    if (isDefmoduleDeclarationName(this)) {
        null
    } else when (val parent = this.parent) {
        is QualifiableAlias ->
            // If the `parent` goes beyond this element then this element is the outermost Qualifiable alias that is still
            // ends in this element, so it represents the fully-qualified name.
            if (textRange.endOffset < parent.textRange.endOffset) {
                // The range in the element though should only be the final to match the guidance in
                // `com.intellij.psi.PsiReference#getRangeInElement`, which appears necessary to make completion to
                // work
                Module(this)
            } else {
                // If the parent ends at the same offset, then the parent should supply the reference
                null
            }

        else ->

            if (this.fullyQualifiedName() !in arrayOf(
                    // `BitString` is used for `defimpl ..., for: BitString` to define protocols on bitstrings
                    // (`<<...>>`)
                    "BitString",
                    // There is no one module that defines the `Elixir` module.  It is only defined implicitly as the common
                    // namespace to all Aliases.
                    "Elixir"
                )
            ) {
                Module(this)
            } else {
                null
            }
    }

/**
 * Whether [alias] is (part of) the declared name of an enclosing `defmodule`.
 * Declaration names are anchored by `ModuleSymbolDeclarationProvider` and must not carry references -
 * an (even unresolving) reference over a declaration anchor shadows the declaration in the platform's
 * declaration-or-reference arbitration.
 */
@RequiresReadLock
internal fun isDefmoduleDeclarationName(alias: QualifiableAlias): Boolean {
    val moduleCall = org.elixir_lang.psi.Module.enclosing(alias) ?: return false
    val firstPrimaryArgument = moduleCall.primaryArguments()?.firstOrNull() ?: return false

    return PsiTreeUtil.isAncestor(firstPrimaryArgument, alias, false)
}

fun QualifiableAlias.cachedReference(): PsiPolyVariantReference? =
    CachedValuesManager.getCachedValue(this) {
        CachedValueProvider.Result.create(computeReference(), this)
    }

@Contract(pure = true)
fun QualifiableAlias.isOutermostQualifiableAlias(): Boolean {
    val parent = parent
    var outermost = false

    /* prevents individual Aliases or tail qualified aliases of qualified chain from having reference separate
           reference from overall chain */
    if (parent !is QualifiableAlias) {
        val grandParent = parent.parent

        // prevents first Alias of a qualified chain from having a separate reference from overall chain
        if (grandParent !is QualifiableAlias) {
            outermost = true
        }
    }

    return outermost
}

fun QualifiableAlias.maybeModularNameToModulars(maxScope: PsiElement): Set<PsiNamedElement> =
    if (!recursiveKernelImport(maxScope)) {
        /* need to construct reference directly as qualified aliases don't return a reference except for the
           outermost */
        reference?.toModulars()
    } else {
        null
    } ?: emptySet()

@Contract(pure = true)
private fun QualifiableAlias.recursiveKernelImport(maxScope: PsiElement): Boolean =
    maxScope is ElixirFile && maxScope.name == "kernel.ex" && name == KERNEL

private fun PsiReference.toModulars(): Set<PsiNamedElement> =
    when (this) {
        is PsiPolyVariantReference -> {
            multiResolve(false).flatMap { resolveResult ->
                resolveResult
                    .takeIf(ResolveResult::isValidResult)
                    ?.element
                    ?.let { resolved -> toModulars(resolved) }
                    ?: emptySet()
            }.toSet()
        }

        else -> {
            resolve()
                ?.let { resolved -> toModulars(resolved) }
                ?: emptySet()
        }
    }

private fun PsiReference.toModulars(resolved: PsiElement): Set<PsiNamedElement> =
    if (resolved is Call && isModular(resolved)) {
        (resolved as? PsiNamedElement)?.let { setOf(it) } ?: emptySet()
    } else if (resolved is org.elixir_lang.beam.psi.Module) {
        setOf(resolved)
    } else if (resolved.isEquivalentTo(element)) {
        // resolved to self, but not a modular, so stop looking
        emptySet()
    } else {
        resolved.reference?.toModulars() ?: emptySet()
    }

object QualifiableAliasImpl {
    @Contract(pure = true)
    @JvmStatic
    fun fullyQualifiedName(qualifiableAlias: QualifiableAlias): String =
        prependQualifiers(qualifiableAlias.parent, qualifiableAlias, selfQualifiedName(qualifiableAlias))

    /**
     * The name of [qualifiableAlias] with its own qualifier expanded.
     *
     * [com.intellij.psi.PsiNamedElement.getName] for a [QualifiedAlias] is the raw source text.
     * That only equals the resolvable module name when the qualifier chain consists purely of
     * Aliases: a call qualifier such as `__MODULE__` in `__MODULE__.Endpoint` must be resolved to
     * the enclosing module so the name becomes e.g. `MyModule.Endpoint` - echoing the text would
     * produce `__MODULE__.Endpoint`, which matches nothing in the module name index.
     */
    private fun selfQualifiedName(qualifiableAlias: QualifiableAlias): String {
        if (qualifiableAlias !is QualifiedAlias) {
            return qualifiableAlias.name ?: "?"
        }

        val children = qualifiableAlias.children
        val operatorIndex = operatorIndex(children)
        val qualifier = org.elixir_lang.psi.operation.infix.Normalized.leftOperand(children, operatorIndex)
        val relativeName = org.elixir_lang.psi.operation.infix.Normalized.rightOperand(children, operatorIndex)
            ?.let { it as? PsiNamedElement }
            ?.name

        return if (qualifier != null && relativeName != null) {
            "${qualifierName(qualifier, qualifiableAlias)}.$relativeName"
        } else {
            qualifiableAlias.name ?: "?"
        }
    }

    /**
     * The module name contributed by [qualifier], the left operand of a qualified alias or
     * qualified call: the dotted name for Alias chains (recursing through [selfQualifiedName] so
     * call qualifiers nested in longer chains like `__MODULE__.Foo.Bar` expand too), or the name
     * of the modular a call qualifier such as `__MODULE__` resolves to.
     */
    private fun qualifierName(qualifier: PsiElement, context: PsiElement): String =
        when (val strippedQualifier = qualifier.stripAccessExpression()) {
            is QualifiedAlias -> selfQualifiedName(strippedQualifier)
            is QualifiableAlias -> strippedQualifier.name ?: "?"
            is Call -> {
                val modularSet = strippedQualifier.maybeModularNameToModulars()

                // A qualifier that resolves to no module, or to more than one, is a placeholder segment
                modularSet.singleOrNull()?.name ?: "?"
            }

            // Anything else names no module either
            else -> "?"
        }

    private fun prependQualifiers(ancestor: PsiElement, previousAncestor: PsiElement, accumulator: String): String =
        when (ancestor) {
            // being inside arguments to a call end qualifiers
            is Arguments,
                // Typing a qualified call before the function name is written
                // `Alias.(arg1)` when the full line is `Alias.f(arg1)
            is DotCall<*>,
                // function call with no parentheses like `raise ArgumentError, ...`
            is ElixirUnqualifiedNoParenthesesManyArgumentsCall,
            is Operation,
            is QuotableKeywordPair,
                // containers
            is ElixirAssociationsBase, is ElixirContainerAssociationOperation, is ElixirList, is ElixirStructOperation, is ElixirTuple,
            is ElixirEexTag,
                // Top of file
            is ElixirFile,
                // Top of expression inside of interpolation
            is ElixirInterpolation,
                // Typing an alias on a new line in the body of function
            is ElixirStabBody,
                // https://github.com/intellij-elixir/intellij-elixir/issues/2839
                //
                // params do
                //   requires :keys, type: List[String], default: []
                // end
                // `List[String]` in above or bracket like `Alias.function[key]`
            is BracketOperation,
                // like `[String]` in above
            is ElixirBracketArguments -> accumulator

            is ElixirAccessExpression, is ElixirMultipleAliases ->
                prependQualifiers(ancestor.parent, ancestor, accumulator)

            is QualifiedAlias, is Qualified, is QualifiedMultipleAliases -> {
                val children = ancestor.children
                val operatorIndex = operatorIndex(children)
                val qualifier = org.elixir_lang.psi.operation.infix.Normalized.leftOperand(children, operatorIndex)

                if (qualifier != null) {
                    if (qualifier.isAncestor(previousAncestor)) {
                        // ancestor was qualifier, so it is only the qualifier's name
                        accumulator
                    } else {
                        "${qualifierName(qualifier, ancestor)}.${accumulator}"
                    }
                } else {
                    // A qualified alias still missing its qualifier while being typed
                    "?.${accumulator}"
                }
            }

            // Any other container ends qualification, as every container named above does
            else -> accumulator
        }
}
