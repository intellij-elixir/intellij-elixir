package org.elixir_lang.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.UNQUOTE
import org.elixir_lang.psi.call.name.Function.UNQUOTE_SPLICING
import org.elixir_lang.psi.call.name.Module.KERNEL
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.call.CallImpl
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.childExpressions
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.operation.Match
import org.elixir_lang.psi.scope.Recording
import org.elixir_lang.psi.scope.WhileIn.whileIn

object Unquote {
    @RequiresReadLock
    fun treeWalkUp(unquoteCall: Call, resolveState: ResolveState, keepProcessing: (PsiElement, ResolveState) -> Boolean): Boolean {
        val unquoteCallResolveState = resolveState.putVisitedElement(unquoteCall)

        return unquoteCall
                .finalArguments()
                ?.singleOrNull()
                ?.let { it as? Call }
                ?.takeUnlessHasBeenVisited(unquoteCallResolveState)
                ?.reference
                ?.let { it as PsiPolyVariantReference }
                ?.let { reference -> treeWalkUp(reference, unquoteCallResolveState, keepProcessing) }
                ?: true
    }

    fun `is`(call: Call): Boolean = call.isCalling(KERNEL, UNQUOTE)

    /** The one argument [call] unquotes: `x` of `unquote(x)`, `unquote_splicing(x)` or `Left.unquote(x)(rest)`, but not `rest`. */
    @RequiresReadLock
    fun unquotedArgument(call: Call): PsiElement? =
            call.primaryArguments()?.singleOrNull()?.takeIf {
                `is`(call) ||
                        call.isCalling(KERNEL, UNQUOTE_SPLICING) ||
                        (call is Qualified && isQualified(call))
            }

    /**
     * Whether [entrance], written under [quote], is in the expression an `unquote` of [quote] unquotes, which runs where
     * the `quote` is: the receiver and the arguments of `Left.unquote(x)(y)` stay quoted. One inside a nested `quote`
     * belongs to that.
     */
    @RequiresReadLock
    fun isUnquotedIn(quote: Call, entrance: PsiElement): Boolean {
        val enclosing = generateSequence(entrance) { it.parent }.takeWhile { it != quote }.toList()

        return enclosing
                .drop(enclosing.indexOfLast { it is Call && QuoteMacro.`is`(it) } + 1)
                .any { call ->
                    call is Call && unquotedArgument(call)?.let { PsiTreeUtil.isAncestor(it, entrance, false) } == true
                }
    }

    @RequiresReadLock
    fun isQualified(qualified: Qualified): Boolean =
            isQualified(qualified, qualified.functionName())

    /**
     * Whether [qualified], named [name], is `Left.unquote(x)` in a `quote`: `unquote`'s own arguments are one, counting
     * a `do` block that follows them but not a piped value, which only joins the call `unquote` makes.
     */
    @RequiresReadLock
    fun isQualified(qualified: Qualified, name: String?): Boolean =
            name == UNQUOTE &&
                    (qualified.primaryArity() ?: 0) + (if (CallImpl.hasPrimaryDoBlock(qualified)) 1 else 0) == 1 &&
                    CallDefinitionClause.enclosingModularMacroCall(qualified)?.let { QuoteMacro.`is`(it) } == true

    /**
     * The arity of the call [call] makes: `Left.unquote(x)(args)` calls with `args`, and `Left.unquote(x)` with none,
     * plus a value piped into either.
     */
    @RequiresReadLock
    fun arity(call: Call): Int =
            if (call is Qualified && isQualified(call)) {
                (call.resolvedSecondaryArity() ?: 0) + if (CallImpl.isPipedInto(call)) 1 else 0
            } else {
                call.resolvedPrimaryArity() ?: 0
            }

    fun ancestorUnquote(descendent: PsiElement): Call? =
            descendent.parent?.parent?.parent?.let { it as? Call }?.takeIf { `is`(it) }

    private fun treeWalkUp(reference: PsiPolyVariantReference,
                           resolveState: ResolveState,
                           keepProcessing: (PsiElement, ResolveState) -> Boolean): Boolean =
            reference
                    .multiResolve(false)
                    .filter(ResolveResult::isValidResult)
                    .mapNotNull(ResolveResult::getElement)
                    .filterIsInstance<Call>()
                    .filter { !resolveState.hasBeenVisited(it) }
                    .let { resolveds -> treeWalkUpUnquoted(resolveds, resolveState, keepProcessing) }

    private fun treeWalkUpUnquoted(unquotedList: List<Call>,
                                   resolveState: ResolveState,
                                   keepProcessing: (PsiElement, ResolveState) -> Boolean): Boolean =
            whileIn(unquotedList) { unquoted ->
                val unquotedResolveState = resolveState.putVisitedElement(unquoted)

                if (CallDefinitionClause.`is`(unquoted)) {
                    Using.treeWalkUp(unquoted, null, unquotedResolveState, keepProcessing)
                } else {
                    // The walk's answer is dropped, so a consumer's stop signal does not end the loop here
                    treeWalkUpUnquotedVariable(
                        unquoted,
                        unquoted,
                        Recording.enter(
                            unquotedResolveState, "UNQUOTE_VALUE", unquoted, stops = true, absorbs = true
                        ),
                        keepProcessing
                    )

                    true
                }
            }

    /**
     * [declaration] is the variable the walk started from, so [Destructure] can pair it with the value at its own
     * position once a [Match] is reached; [unquoted] is the current hop, whose own position is lost as the walk climbs.
     */
    private tailrec fun treeWalkUpUnquotedVariable(unquoted: PsiElement,
                                                   declaration: PsiElement,
                                                   resolveState: ResolveState,
                                                   keepProcessing: (PsiElement, ResolveState) -> Boolean): Boolean {
        // a detached element binds nothing above
        val parent = unquoted.parent ?: return true

        return when (UnquotedVariableWalk.classify(parent)) {
            UnquotedVariableWalk.Bucket.MATCH -> {
                val match = parent as Match

                // empty for `... = variable`, or for two sides that do not line up: neither binds anything further up
                whileIn(Destructure.valuesAt(match.leftOperand(), match.rightOperand(), declaration)) { value ->
                    treeWalkUpValue(value, resolveState, keepProcessing)
                }
            }
            UnquotedVariableWalk.Bucket.RECURSE ->
                treeWalkUpUnquotedVariable(parent, declaration, resolveState, keepProcessing)
            UnquotedVariableWalk.Bucket.STOP,
            UnquotedVariableWalk.Bucket.UNFOLLOWED,
            UnquotedVariableWalk.Bucket.LEAF -> true
        }
    }

    private fun treeWalkUpValue(value: PsiElement,
                                resolveState: ResolveState,
                                keepProcessing: (PsiElement, ResolveState) -> Boolean): Boolean =
            when (value) {
                is Call -> treeWalkUpValue(value, resolveState, keepProcessing)
                // the compiler expands a list literal's elements, so a list of fragments defines each of them
                is ElixirList -> treeWalkUpFragments(value, resolveState, keepProcessing)
                // Only a two-element tuple is a quoted literal. `{:__block__, [], [fragment]}` splices too, but that
                // needs the node's shape read rather than its size, and is not modelled.
                is ElixirTuple ->
                    value.takeIf { it.childExpressions().count() == 2 }
                            ?.let { tuple -> treeWalkUpFragments(tuple, resolveState, keepProcessing) }
                            ?: true
                else -> true
            }

    /** Each element of [container] read as a fragment. Marked visited first: a fragment can answer this container. */
    private fun treeWalkUpFragments(container: PsiElement,
                                    resolveState: ResolveState,
                                    keepProcessing: (PsiElement, ResolveState) -> Boolean): Boolean =
            container
                    .takeUnlessHasBeenVisited(resolveState)
                    ?.let { entered ->
                        val enteredResolveState = resolveState.putVisitedElement(entered)

                        whileIn(entered.childExpressions().toList()) { fragment ->
                            treeWalkUpValue(fragment.stripAccessExpression(), enteredResolveState, keepProcessing)
                        }
                    }
                    ?: true

    private fun treeWalkUpValue(value: Call,
                                resolveState: ResolveState,
                                keepProcessing: (PsiElement, ResolveState) -> Boolean): Boolean =
            when {
                QuoteMacro.`is`(value) -> QuoteMacro.treeWalkUp(value, resolveState, keepProcessing)
                Case.`is`(value) -> Case.treeWalkUp(value, resolveState, keepProcessing)
                else -> {
                    value.reference?.let { it as PsiPolyVariantReference }?.let { reference ->
                        treeWalkUp(reference, resolveState.putVisitedElement(value), keepProcessing)
                    } ?: true
                }
            }
}
