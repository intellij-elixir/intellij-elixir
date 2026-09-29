package org.elixir_lang.structure_view.element

import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import org.elixir_lang.call.Visibility
import org.elixir_lang.navigation.item_presentation.NameArity
import org.elixir_lang.psi.call.Call

class EExFunctionFromHead(private val eexFunctionFrom: EExFunctionFrom) : Element<Call>(eexFunctionFrom.call),
                                                                          Presentable, Visible {
    override fun getPresentation(): ItemPresentation =
        org.elixir_lang.navigation.item_presentation.CallDefinitionHead(
            eexFunctionFrom.presentation as NameArity,
            eexFunctionFrom.visibility(),
            navigationItem
        )

    override fun getChildren(): Array<TreeElement> = emptyArray()
    override fun visibility(): Visibility? = eexFunctionFrom.visibility()
}
