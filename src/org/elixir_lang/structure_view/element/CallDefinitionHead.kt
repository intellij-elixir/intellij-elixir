package org.elixir_lang.structure_view.element

import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.NameArityInterval
import org.elixir_lang.call.Visibility
import org.elixir_lang.navigation.item_presentation.NameArity
import org.elixir_lang.psi.*
import org.elixir_lang.psi.impl.isInterpolatedWithin
import org.elixir_lang.psi.scope.call_definition_clause.DeclaringForm
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.call.name.Function.UNQUOTE
import org.elixir_lang.psi.impl.PsiNamedElementImpl.unquoteName
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.headAtomQuotable
import org.elixir_lang.psi.impl.headAtomValue
import org.elixir_lang.psi.impl.stripAccessExpressionAndParentheses
import org.elixir_lang.psi.operation.Normalized.operatorIndex
import org.elixir_lang.psi.operation.When

class CallDefinitionHead(val callDefinition: CallDefinition, private val visibility: Visibility, call: Call) :
    Element<Call>(call), Presentable, Visible {
    /**
     * Heads have no children since they have no body.
     *
     * @return empty array
     */
    override fun getChildren(): Array<TreeElement> = emptyArray()

    /**
     * Returns the presentation of the tree element.
     *
     * @return the element presentation.
     */
    override fun getPresentation(): ItemPresentation =
        org.elixir_lang.navigation.item_presentation.CallDefinitionHead(
            callDefinition.presentation as NameArity,
            visibility,
            navigationItem
        )

    /**
     * The visibility of the element.
     *
     * @return [Visibility.PUBLIC].
     */
    override fun visibility(): Visibility = visibility

    companion object {
        @RequiresReadLock
        fun enclosingDelegationCall(call: Call): Call? =
            enclosingDelegationCall(SyntacticCall.of(call))?.let(SyntacticCall::call)

        @RequiresReadLock
        fun enclosingDelegationCall(call: SyntacticCall): SyntacticCall? = DeclaringForm.delegationOf(call)

        fun `is`(call: Call): Boolean = `is`(SyntacticCall.of(call))

        fun `is`(call: SyntacticCall): Boolean = call.shape == SyntacticCall.Shape.UNQUALIFIED_PARENTHESES

        fun nameIdentifier(head: PsiElement): PsiElement? =
            if (head is ElixirMatchedAtOperation) {
                head.operator()
            } else {
                val stripped = strip(head)

                if (stripped is Call) {
                    stripped.functionNameElement()
                } else {
                    stripped
                }
            }

        /** The element a rename of [head]'s name rewrites: in `unquote(:name)(...)`, the atom. */
        @RequiresReadLock
        fun nameElement(head: PsiElement): PsiElement? = headAtomQuotable(head) ?: nameIdentifier(head)

        /**
         * The elements a caret is on [head]'s name at: in `unquote(:name)(...)`, the atom and `unquote`, which together
         * write it, though a rename rewrites only the atom.
         */
        @RequiresReadLock
        fun nameElements(head: PsiElement): List<PsiElement> = listOfNotNull(nameElement(head), nameIdentifier(head)).distinct()

        /**
         * Whether [element] is on [head]'s name, or holds it, but is not code interpolated in it, which runs. An atom, as
         * an EEx function's name is written, is its own head.
         */
        @RequiresReadLock
        fun isOnName(head: PsiElement, element: PsiElement): Boolean =
            nameElements(head).any { name ->
                PsiTreeUtil.isAncestor(element, name, false) ||
                    PsiTreeUtil.isAncestor(name, element, false) && !element.isInterpolatedWithin(name)
            }

        @RequiresReadLock
        fun nameArityInterval(head: PsiElement, state: ResolveState): NameArityInterval? =
            if (head is ElixirMatchedAtOperation) {
                val name = head.operator().text.trim { it <= ' ' }
                val arityInterval = ArityInterval(1, 1)

                NameArityInterval(name, arityInterval)
            } else {
                (strip(head) as? Call)?.let { stripped ->
                    val functionName = stripped.functionName()

                    if (functionName != null) {
                        val name = headAtomValue(stripped) ?: unquoteName(stripped, functionName)
                        val arityInterval = if (functionName == UNQUOTE) {
                            ArityInterval.fromArguments(parameters(stripped))
                        } else {
                            stripped.resolvedFinalArityInterval()
                        }

                        NameArityInterval(name, arityInterval).adjusted(state)
                    } else {
                        null
                    }
                }
            }

        /**
         * The arguments of [stripped] that are the head's parameters. For an `unquote(:name)` head the primary list
         * names the function, so a bare `unquote(:name)` has none.
         */
        @RequiresReadLock
        fun parameters(stripped: Call): Array<PsiElement>? =
            if (stripped.functionName() == UNQUOTE) {
                stripped.secondaryArguments()?.map { it!! }?.toTypedArray()
            } else {
                stripped.finalArguments()
            }

        /**
         * Head without parentheses around the guard or guarded head
         *
         * @param head `((((name(arg, ...))) when ...))`
         * @return `name(arg, ...)`
         */
        fun strip(head: PsiElement): PsiElement =
            stripGuard(head.stripAccessExpressionAndParentheses()).stripAccessExpressionAndParentheses()

        /**
         * The head without the guard clause
         *
         * @param head `name(arg, ...) when ...`
         * @return `name(arg, ...)`.  `head` if no guard clause.
         */
        tailrec fun stripGuard(head: PsiElement): PsiElement =
            if (head is When) {
                val children = head.children

                val operatorIndex = operatorIndex(children)
                var onlyNonErrorIndex = -1

                for (i in 0 until operatorIndex) {
                    if (children[i] !is PsiErrorElement) {
                        if (onlyNonErrorIndex == -1) {
                            // first
                            onlyNonErrorIndex = i
                        } else {
                            // more than one
                            onlyNonErrorIndex = -1

                            break
                        }
                    }
                }

                if (onlyNonErrorIndex != -1) {
                    stripGuard(children[onlyNonErrorIndex])
                } else {
                    head
                }
            } else {
                head
            }
    }
}
