package org.elixir_lang.structure_view.element

import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.navigation.item_presentation.Parent
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.SyntacticCall
import org.elixir_lang.psi.impl.headAtomValue
import org.elixir_lang.psi.operation.Type
import org.elixir_lang.psi.operation.When
import org.elixir_lang.structure_view.element.modular.Modular
import org.jetbrains.annotations.Contract

/**
 * A call definition @spec
 */
class CallDefinitionSpecification(
        private val modular: Modular,
        moduleAttributeDefinition: AtUnqualifiedNoParenthesesCall<*>,
        private val callback: Boolean,
        private val time: Timed.Time
) : Element<AtUnqualifiedNoParenthesesCall<*>>(moduleAttributeDefinition) {
    /**
     * No children.
     *
     * @return empty array
     */
    override fun getChildren(): Array<TreeElement> = emptyArray()

    /**
     * Returns the presentation of the tree element.
     *
     * @return the element presentation.
     */
    override fun getPresentation(): ItemPresentation {
        val location = (modular.presentation as Parent).locatedPresentableText

        return org.elixir_lang.navigation.item_presentation.CallDefinitionSpecification(
                location,
                specification(navigationItem),
                callback,
                time
        )
    }

    companion object {
        fun elementDescription(
                @Suppress("UNUSED_PARAMETER") call: Call,
                location: ElementDescriptionLocation
        ): String? =
            if (location === UsageViewTypeLocation.INSTANCE) {
                "specification"
            } else {
                null
            }

        @Contract(pure = true)
        fun `is`(call: Call): Boolean = `is`(SyntacticCall.of(call))

        @Contract(pure = true)
        fun `is`(call: SyntacticCall): Boolean = call.moduleAttributeName() == "@spec"

        @RequiresReadLock
        @JvmStatic
        fun moduleAttributeNameArity(
                atUnqualifiedNoParenthesesCall: AtUnqualifiedNoParenthesesCall<*>
        ): org.elixir_lang.NameArity? =
                specification(atUnqualifiedNoParenthesesCall)?.let { specificationType(it) }?.let { typeNameArity(it) }

        @RequiresReadLock
        @JvmStatic
        fun moduleAttributeNameArity(call: Call): org.elixir_lang.NameArity? =
            (call as? AtUnqualifiedNoParenthesesCall<*>)?.let { moduleAttributeNameArity(it) }

        fun nameIdentifier(atUnqualifiedNoParenthesesCall: AtUnqualifiedNoParenthesesCall<*>): PsiElement? =
            specification(atUnqualifiedNoParenthesesCall)?.let { specificationType(it) }?.let { typeNameIdentifier(it) }

        fun nameIdentifier(call: Call): PsiElement? =
                (call as? AtUnqualifiedNoParenthesesCall<*>)?.let { nameIdentifier(it) }

        fun specification(atUnqualifiedNoParenthesesCall: AtUnqualifiedNoParenthesesCall<*>): Call? =
            atUnqualifiedNoParenthesesCall.argument as? Call

        fun specificationType(specification: Call): Call? =
                when (specification) {
                    is Type -> type(specification)
                    is When -> type(specification)
                    else -> null
                }

        fun type(typeOperation: Type): Call? = (typeOperation.leftOperand() as? Call)

        fun type(whenOperation: When): Call? =
                (whenOperation.leftOperand() as? Type)?.let { type(it) }

        /**
         * `null` when [type] has no function name, as for the `foo.()` and `bar not in baz` heads
         * the parser produces from a malformed `@spec`: [Call.functionName] is `@Nullable` and
         * every caller here already treats a missing name as "not a specification".
         */
        @RequiresReadLock
        @JvmStatic
        fun typeNameArity(type: Call): org.elixir_lang.NameArity? {
            val name = headAtomValue(type) ?: type.functionName() ?: return null
            val arity = type.resolvedFinalArity()

            return org.elixir_lang.NameArity(name, arity)
        }

        private fun typeNameIdentifier(type: Call): PsiElement? = type.functionNameElement()
    }
}
