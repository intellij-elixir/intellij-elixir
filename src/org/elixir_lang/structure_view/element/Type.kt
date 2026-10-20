package org.elixir_lang.structure_view.element

import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.call.Visibility
import org.elixir_lang.navigation.item_presentation.Parent
import org.elixir_lang.psi.AtUnqualifiedNoParenthesesCall
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.ElixirPsiImplUtil
import org.elixir_lang.psi.operation.When
import org.elixir_lang.structure_view.element.modular.Modular

class Type(
    private val modular: Modular,
    moduleAttributeDefinition: AtUnqualifiedNoParenthesesCall<*>,
    private val kind: Kind
) : Element<AtUnqualifiedNoParenthesesCall<*>?>(moduleAttributeDefinition), Visible {

    /**
     * No children.
     *
     * @return empty array.
     */
    override fun getChildren(): Array<TreeElement> = emptyArray()

    /**
     * Returns the presentation of the tree element.
     *
     * @return the element presentation.
     */
    override fun getPresentation(): ItemPresentation {
        val parentPresentation = modular.presentation as Parent
        val location = parentPresentation.locatedPresentableText

        return org.elixir_lang.navigation.item_presentation.Type(
            location,
            type(navigationItem!!),
            kind.isOpaque,
            kind.visibility
        )
    }

    override fun visibility(): Visibility = kind.visibility

    /** The module attributes a type node can be built from. */
    enum class Kind {
        OPAQUE,
        TYPE,
        TYPEP;

        val isOpaque: Boolean get() = this == OPAQUE

        val visibility: Visibility
            get() = when (this) {
                OPAQUE, TYPE -> Visibility.PUBLIC
                TYPEP -> Visibility.PRIVATE
            }

        companion object {
            fun of(moduleAttributeName: String): Kind? =
                when (moduleAttributeName) {
                    "@opaque" -> OPAQUE
                    "@type" -> TYPE
                    "@typep" -> TYPEP
                    else -> null
                }

            fun of(call: Call): Kind? =
                (call as? AtUnqualifiedNoParenthesesCall<*>)?.let { of(ElixirPsiImplUtil.moduleAttributeName(it)) }
        }
    }

    companion object {
        fun elementDescription(location: ElementDescriptionLocation): String? =
            if (location === UsageViewTypeLocation.INSTANCE) {
                "type"
            } else {
                null
            }

        fun fromCall(modular: Modular, call: Call): Type? =
            Kind.of(call)?.let { kind ->
                Type(modular, call as AtUnqualifiedNoParenthesesCall<*>, kind)
            }

        @JvmStatic
        fun `is`(call: Call): Boolean = Kind.of(call) != null

        fun isHead(element: PsiElement): Boolean {
            val attribute = PsiTreeUtil.getParentOfType(element, AtUnqualifiedNoParenthesesCall::class.java, false)
                ?: return false
            if (!`is`(attribute)) return false
            val spec = specification(attribute) ?: return false
            val head = specificationType(spec) ?: return false

            return PsiTreeUtil.isAncestor(head, element, false) || PsiTreeUtil.isAncestor(element, head, false)
        }

        /** The head [call] writes its type in: `name(parameters)` in `@type name(parameters) :: ...`. */
        @RequiresReadLock
        fun head(call: Call): PsiElement? =
            (call as? AtUnqualifiedNoParenthesesCall<*>)
                ?.let(::specification)
                ?.let(CallDefinitionSpecification::specificationType)

        /** The element [call]'s head writes its name as: in `@type unquote(:name)() :: ...`, the atom. */
        @RequiresReadLock
        fun nameIdentifier(call: Call): PsiElement? = head(call)?.let(CallDefinitionHead::nameElement)

        fun specification(atUnqualifiedNoParenthesesCall: AtUnqualifiedNoParenthesesCall<*>): Call? =
            atUnqualifiedNoParenthesesCall.argument as? Call

        private fun specificationType(specification: Call): Call? =
            when (specification) {
                is org.elixir_lang.psi.operation.Type -> CallDefinitionSpecification.type(specification)
                is When -> CallDefinitionSpecification.type(specification)
                else -> null
            }

        fun type(atUnqualifiedNoParenthesesCall: AtUnqualifiedNoParenthesesCall<*>): Call? =
            atUnqualifiedNoParenthesesCall.argument as? Call
    }
}
