package org.elixir_lang.structure_view.element

import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.navigation.item_presentation.Delegation
import org.elixir_lang.navigation.item_presentation.Parent
import org.elixir_lang.psi.ElixirAccessExpression
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function
import org.elixir_lang.psi.call.name.Module
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.call.finalArity
import org.elixir_lang.psi.impl.call.keywordArgument
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.structure_view.element.CallDefinitionClause.Companion.enclosingModular
import org.elixir_lang.structure_view.element.modular.Modular

class Delegation(private val modular: Modular, call: Call) : Element<Call?>(call) {
    private val childList: MutableList<TreeElement> = ArrayList()

    /**
     * If `true`, when delegated, the first argument passed to the delegated function will be relocated to the end
     * of the arguments when dispatched to the target.
     *
     * @return defaults to `false` and when keyword argument is not parsable as boolean.
     */
    @RequiresReadLock
    fun appendFirst(): Boolean =
        navigationItem!!.keywordArgument("append_first")?.let { keywordValue ->
            keywordValue.text == "true"
        } ?: false

    /**
     * The value of the `:as` keyword argument
     *
     * @return text of the `:as` keyword value
     */
    fun `as`(): String? = keywordArgumentText("as")

    @RequiresReadLock
    fun callDefinitionHeadCallList(): List<Call> = callDefinitionHeadCallList(navigationItem!!)

    fun definition(callDefinition: CallDefinition) = childList.add(callDefinition)

    /**
     * The calls defined by this delegation
     *
     * @return the list of [CallDefinition] elements;
     */
    override fun getChildren(): Array<TreeElement> = childList.toTypedArray()

    /**
     * Returns the presentation of the tree element.
     *
     * @return the element presentation.
     */
    override fun getPresentation(): ItemPresentation {
        val parent = modular.presentation as Parent
        val location = parent.locatedPresentableText

        return Delegation(
            location,
            to(),
            `as`(),
            appendFirst()
        )
    }

    /**
     * The value of the `:to` keyword argument
     *
     * @return text of the `:to` keyword value
     */
    fun to(): String? = keywordArgumentText("to")

    /*
     * Private Instance Methods
     */
    /**
     * The text of the `keywordValueText` keyword argument.
     *
     * @param keywordValueText the text of the keyword value
     * @return the `PsiElement.getText()` of the keyword value if the keyword argument exists of the
     * [Element.navigationItem]; `null` if the keyword argument does not exist.
     */
    private fun keywordArgumentText(keywordValueText: String): String? =
        navigationItem!!.keywordArgument(keywordValueText)?.text

    companion object {
        @RequiresReadLock
        @JvmStatic
        fun callDefinitionHeadCallList(defdelegateCall: Call): List<Call> {
            var callDefinitionHeadCallList: List<Call>? = null
            val finalArguments = defdelegateCall.finalArguments()!!
            val firstFinalArgument = finalArguments[0]
            if (firstFinalArgument is ElixirAccessExpression) {
                val accessExpressionChild = firstFinalArgument.stripAccessExpression()

                callDefinitionHeadCallList = if (accessExpressionChild is ElixirList) {
                    accessExpressionChild.children.filterIsInstance<Call>().filter { CallDefinitionHead.`is`(it) }
                } else {
                    null
                }
            } else if (firstFinalArgument is Call) {
                callDefinitionHeadCallList = filterCallDefinitionHeadCallList(firstFinalArgument)
            }
            if (callDefinitionHeadCallList == null) {
                callDefinitionHeadCallList = emptyList()
            }
            return callDefinitionHeadCallList
        }

        fun filterCallDefinitionHeadCallList(vararg calls: Call): List<Call> =
            calls.filter { CallDefinitionHead.`is`(it) }

        fun elementDescription(location: ElementDescriptionLocation): String? =
            if (location === UsageViewTypeLocation.INSTANCE) {
                "delegation"
            } else {
                null
            }

        @JvmStatic
        // finalArity, not resolvedFinalArity via isCalling(..., 2) - callDefinitionHeadCallList() reads
        // finalArguments() and indexes it.
        fun `is`(call: Call): Boolean =
            call.isCalling(Module.KERNEL, Function.DEFDELEGATE) && call.finalArity() == 2

        @RequiresReadLock
        fun fromCall(call: Call): org.elixir_lang.structure_view.element.Delegation? =
            enclosingModular(call)?.let { modular ->
                Delegation(modular, call)
            }

        @RequiresReadLock
        fun nameIdentifier(call: Call): PsiElement? =
            org.elixir_lang.psi.CallableDeclaration.delegationHead(call)?.let { CallDefinitionHead.nameIdentifier(it) }
    }
}
