package org.elixir_lang.structure_view.element

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.util.OpenSourceUtil
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.call.Visibility
import org.elixir_lang.code_insight.definition
import org.elixir_lang.navigation.item_presentation.NameArity
import org.elixir_lang.navigation.item_presentation.Parent
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.arityInterval
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.DEF
import org.elixir_lang.psi.call.name.Function.DEFP
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.quotedAtomValue
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.psi.scope.call_definition_clause.Declarations
import org.elixir_lang.structure_view.element.CallDefinitionClause.Companion.enclosingModular
import org.elixir_lang.structure_view.element.modular.Modular

class EExFunctionFrom(val modular: Modular, val call: Call) : StructureViewTreeElement, Visible, NavigationItem {
    override fun navigate(requestFocus: Boolean) {
        OpenSourceUtil.navigate(requestFocus, call)
    }

    @Suppress("UnstableApiUsage")
    @RequiresReadLock
    @RequiresBackgroundThread
    override fun navigationRequest(): NavigationRequest? = call.navigationRequest()

    override fun canNavigate(): Boolean = true
    override fun canNavigateToSource(): Boolean = true

    override fun getName(): String = "$declaredName/$arity"

    override fun getValue(): Any = call

    override fun getPresentation(): ItemPresentation {
        val parent = modular.presentation as Parent
        val location = parent.locatedPresentableText

        return NameArity(
            location,
            false,
            Timed.Time.RUN,
            visibility(),
            false,
            false,
            declaredName,
            arity
        )
    }

    override fun getChildren(): Array<TreeElement> = arrayOf(EExFunctionFromHead(this))

    private val declaredName: String by lazy {
        call.finalArguments()?.get(1)?.stripAccessExpression()?.let { it as? ElixirAtom }?.let(::quotedAtomValue)
            ?: "unknown_name"
    }

    /** The arity that names the declared function, as it names any definition, and none where unknown. */
    private val arity: Int by lazy {
        call.finalArguments()?.let(Declarations::eexArity)?.arityInterval()?.definition()?.first ?: 0
    }

    override fun visibility(): Visibility? =
        call.finalArguments()?.get(0)?.stripAccessExpression()
            ?.let { it as? ElixirAtom }?.let(::quotedAtomValue)?.let { macro ->
            when (macro) {
                DEF -> Visibility.PUBLIC
                DEFP -> Visibility.PRIVATE
                else -> null
            }
        }


    companion object {
        @RequiresReadLock
        fun fromCall(call: Call): EExFunctionFrom? =
            enclosingModular(call)?.let { modular ->
                EExFunctionFrom(modular, call)
            }
    }
}
