package org.elixir_lang.structure_view.element

import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.call.Visibility
import org.elixir_lang.navigation.item_presentation.NameArity
import org.elixir_lang.navigation.item_presentation.Parent
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.operation.Type
import org.elixir_lang.psi.operation.When
import org.elixir_lang.structure_view.element.CallDefinitionClause.Companion.enclosingModular
import org.elixir_lang.structure_view.element.Timed.Time
import org.elixir_lang.structure_view.element.modular.Modular
import org.jetbrains.annotations.Contract

class Callback(private val modular: Modular, navigationItem: Call, private val kind: Kind) :
    Element<AtUnqualifiedNoParenthesesCall<*>>(navigationItem as AtUnqualifiedNoParenthesesCall<*>), Timed {
    /** The module attributes a callback node can be built from. */
    enum class Kind {
        CALLBACK,
        MACROCALLBACK;

        val time: Time
            get() = when (this) {
                CALLBACK -> Time.RUN
                MACROCALLBACK -> Time.COMPILE
            }

        companion object {
            fun of(moduleAttributeName: String): Kind? =
                when (moduleAttributeName) {
                    "@callback" -> CALLBACK
                    "@macrocallback" -> MACROCALLBACK
                    else -> null
                }

            fun of(call: Call): Kind? = of(SyntacticCall.of(call))

            fun of(call: SyntacticCall): Kind? = call.moduleAttributeName()?.let { of(it) }
        }
    }

    /**
     * A callback's [.getPresentation] is a [NameArity] like a [CallDefinition], so like a call
     * definition, it's children are the specifications and clauses, since the callback has no clauses, the only child
     * is the specification.
     *
     * @return the list of children.
     */
    override fun getChildren(): Array<TreeElement> =
        arrayOf(
            CallDefinitionSpecification(
                modular,
                navigationItem,
                true,
                time()
            )
        )

    /**
     * Returns the presentation of the tree element.
     *
     * @return the element presentation.
     */
    override fun getPresentation(): ItemPresentation {
        val location = (modular.presentation as Parent).locatedPresentableText

        var name = "?"
        var arity = -1

        headCall(navigationItem)?.let { headCall ->
            CallDefinitionHead.nameArityInterval(headCall, ResolveState.initial())?.let { nameArityInterval ->
                name = nameArityInterval.name
                arity = nameArityInterval.arityInterval.singleOrNull() ?: arity
            }
        }

        return NameArity(
            location,
            true,
            time(),
            Visibility.PUBLIC,
            false,
            false,
            name,
            arity
        )
    }

    override fun time(): Time = kind.time

    companion object {
        @JvmStatic
        fun elementDescription(
            @Suppress("UNUSED_PARAMETER") call: Call,
            location: ElementDescriptionLocation
        ): String? =
            if (location === UsageViewTypeLocation.INSTANCE) {
                "callback"
            } else {
                null
            }

        @Contract(pure = true)
        fun headCall(atUnqualifiedNoParenthesesCall: AtUnqualifiedNoParenthesesCall<*>): Call? =
            atUnqualifiedNoParenthesesCall.argument?.let { specificationHeadCall(it) }

        @RequiresReadLock
        fun `is`(call: Call): Boolean = `is`(SyntacticCall.of(call))

        @RequiresReadLock
        fun `is`(call: SyntacticCall): Boolean {
            ThreadingAssertions.assertReadAccess()

            return Kind.of(call) != null
        }

        fun fromCall(modular: Modular, call: Call): Callback? =
            Kind.of(call)?.let { kind -> Callback(modular, call, kind) }

        /**
         * `true` if [element] is (within) the name/head of an enclosing `@callback`/`@macrocallback`,
         * e.g. `foo` or `foo()` in `@callback foo() :: ...`.
         *
         * Used so the Symbol model (the `Callback` symbol) owns Find Usages / navigation for callback
         * names instead of the legacy `PsiReference`-based find-usages, which otherwise contributes a
         * redundant second target.
         */
        @RequiresReadLock
        @Contract(pure = true)
        fun isHead(element: PsiElement): Boolean {
            val attribute = PsiTreeUtil.getParentOfType(element, AtUnqualifiedNoParenthesesCall::class.java, false)
                ?: return false
            if (!`is`(attribute)) return false
            val head = headCall(attribute) ?: return false
            return PsiTreeUtil.isAncestor(head, element, false) || PsiTreeUtil.isAncestor(element, head, false)
        }

        @RequiresReadLock
        fun fromCall(call: Call): Callback? =
            Kind.of(call)?.let { kind ->
                enclosingModular(call)?.let { modular -> Callback(modular, call, kind) }
            }

        @Contract(pure = true)
        fun nameIdentifier(call: Call): PsiElement? =
            (call as? AtUnqualifiedNoParenthesesCall<*>)?.let { nameIdentifier(it) }

        @Contract(pure = true)
        fun nameIdentifier(atUnqualifiedNoParenthesesCall: AtUnqualifiedNoParenthesesCall<*>): PsiElement? =
            headCall(atUnqualifiedNoParenthesesCall)?.let { headCall ->
                CallDefinitionHead.nameIdentifier(headCall)
            }

        private fun parameterizedTypeHeadCall(whenOperation: When): Call? =
            (whenOperation.leftOperand() as? Type)?.let { type ->
                typeHeadCall(type)
            }

        private fun specificationHeadCall(specification: PsiElement): Call? =
            when (specification) {
                is Type -> typeHeadCall(specification)
                is When -> parameterizedTypeHeadCall(specification)
                else -> null
            }

        private fun typeHeadCall(typeOperation: Type): Call? = typeOperation.leftOperand() as? Call
    }
}
