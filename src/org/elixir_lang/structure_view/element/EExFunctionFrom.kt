package org.elixir_lang.structure_view.element

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.psi.ResolveState
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.call.Visibility
import org.elixir_lang.psi.CallableDeclaration
import org.elixir_lang.navigation.item_presentation.NameArity
import org.elixir_lang.navigation.item_presentation.Parent
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.CallDefinitionClause.Companion.enclosingModular
import org.elixir_lang.structure_view.element.modular.Modular

/**
 * A function a macro call declares - an EEx `function_from_*` or a `Mix.Generator` embed - with the call as its head.
 * Its name, arity and visibility are what [CallableDeclaration] says the call declares.
 */
class EExFunctionFrom(val modular: Modular, val call: Call) : StructureViewTreeElement, Visible, NavigationItem {
    override fun navigate(requestFocus: Boolean) {
        if (canNavigate()) {
            call.navigate(requestFocus)
        }
    }

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

    private val declaration: CallableDeclaration.Declaration? by lazy {
        CallableDeclaration.definitions(call, ResolveState.initial()).firstOrNull()
    }

    private val declaredName: String by lazy { declaration?.name ?: "unknown_name" }

    private val arity: Int by lazy { declaration?.arityInterval?.minimum ?: 0 }

    /** The function's name and arity, `null` when the call does not spell its name as a literal atom. */
    @RequiresReadLock
    fun nameArity(): org.elixir_lang.NameArity? = declaration?.let { org.elixir_lang.NameArity(it.name, arity) }

    override fun visibility(): Visibility? = CallableDeclaration.capabilitiesOf(call, ResolveState.initial())?.visibility

    companion object {
        @RequiresReadLock
        fun fromCall(call: Call): EExFunctionFrom? =
            enclosingModular(call)?.let { modular ->
                EExFunctionFrom(modular, call)
            }
    }
}
