package org.elixir_lang.structure_view.element

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.call.Visibility
import org.elixir_lang.navigation.item_presentation.NameArity
import org.elixir_lang.navigation.item_presentation.Parent
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.ElixirList
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.name.Function.DEF
import org.elixir_lang.psi.call.name.Function.DEFP
import org.elixir_lang.psi.impl.call.finalArguments
import org.elixir_lang.psi.impl.quotedAtomValue
import org.elixir_lang.psi.impl.stripAccessExpression
import org.elixir_lang.structure_view.element.CallDefinitionClause.Companion.enclosingModular
import org.elixir_lang.structure_view.element.modular.Modular

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

    private val declaredName: String by lazy {
        call.finalArguments()?.get(1)?.stripAccessExpression()?.let { it as? ElixirAtom }?.let(::quotedAtomValue)
            ?: "unknown_name"
    }

    private val arity: Int by lazy {
        call.finalArguments()?.let { arguments ->
            if (arguments.size >= 4) {
                // function_from_file(kind, name, file, args)
                // function_from_file(kind, name, file, args, options)
                // function_from_string(kind, name, template, args)
                // function_from_string(kind, name, template, args, options)
                arguments[3].stripAccessExpression().let { it as? ElixirList }?.children?.size
            } else {
                // function_from_file(kind, name, file) where args defaults to `[]`
                // function_from_string(kind, name, template) where args defaults to `[]`
                0
            }
        } ?: 0
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
