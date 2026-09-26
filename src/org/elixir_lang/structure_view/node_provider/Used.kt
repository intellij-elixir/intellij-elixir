package org.elixir_lang.structure_view.node_provider

import com.intellij.icons.AllIcons
import com.intellij.ide.util.ActionShortcutProvider
import com.intellij.ide.util.FileStructureNodeProvider
import com.intellij.ide.util.treeView.smartTree.ActionPresentation
import com.intellij.ide.util.treeView.smartTree.ActionPresentationData
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.psi.ResolveState
import com.intellij.util.IncorrectOperationException
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.NameArity
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.impl.ElixirPsiImplUtil.ENTRANCE
import org.elixir_lang.psi.putInitialVisitedElement
import org.elixir_lang.structure_view.element.*
import org.elixir_lang.structure_view.element.modular.Module
import org.jetbrains.annotations.NonNls

class Used : FileStructureNodeProvider<TreeElement>, ActionShortcutProvider {
    override fun getActionIdForShortcut(): String = "FileStructurePopup"
    override fun getCheckBoxText(): String = "Show Used"

    /**
     * Returns a unique identifier for the action.
     *
     * @return the action identifier.
     */
    override fun getName(): String = ID

    /**
     * Returns the presentation for the action.
     *
     * @return the action presentation.
     * @see ActionPresentationData.ActionPresentationData
     */
    override fun getPresentation(): ActionPresentation =
        ActionPresentationData("Show Used", null, AllIcons.Hierarchy.Supertypes)

    override fun getShortcut(): Array<Shortcut> = throw IncorrectOperationException("see getActionIdForShortcut()")

    override fun provideNodes(node: TreeElement): Collection<TreeElement> =
        (node as? Module)?.children?.toList()?.let { childCollection ->
            provideNodesFromChildren(childCollection).let { filterOverridden(it, childCollection) }
        } ?: emptyList()

    companion object {
        @NonNls
        const val ID = "SHOW_USED"

        /**
         * What a `use` injects, less what the module redefines. `defoverridable` names a macro as well as a function,
         * so a redefinition hides an injected definition of the same name, arity and time.
         */
        private fun filterOverridden(
            nodesFromChildren: Collection<TreeElement>,
            children: Collection<TreeElement>
        ): Collection<TreeElement> {
            val childDefinitionByKey = definitionByKey(children)

            return nodesFromChildren
                .filterIsInstance<CallDefinition>()
                .filterNot { key(it) in childDefinitionByKey }
        }

        /** The call definitions among [children], by name, arity and time, as a redefinition must match all three. */
        fun definitionByKey(children: Collection<TreeElement>): Map<Pair<NameArity, Timed.Time>, CallDefinition> =
            children
                .filterIsInstance<CallDefinition>()
                .associateBy(::key)

        private fun key(definition: CallDefinition): Pair<NameArity, Timed.Time> =
            NameArity(definition.name(), definition.arity) to definition.time()

        /**
         * What [child] injects when it is a `use`: the calls resolution reaches through it, nested `use`s and an
         * `apply` in `__using__` included, built as a module's own children are.
         */
        @RequiresReadLock
        private fun provideNodesFromChild(child: TreeElement): Collection<TreeElement> {
            val use = child as? Use ?: return emptyList()
            val useCall = use.call()
            val injected = mutableListOf<Call>()
            // Entered from the file, as the structure view lists it, so the walk is not taken for one from the `use` itself.
            val walkState = ResolveState.initial().put(ENTRANCE, useCall.containingFile).putInitialVisitedElement(useCall)

            org.elixir_lang.psi.Use.treeWalkUpInjected(useCall, walkState) { call, _ ->
                injected += call
                true
            }

            return Module
                .childCallTreeElements(
                    org.elixir_lang.structure_view.element.modular.Use(use),
                    injected.toTypedArray(),
                    ResolveState.initial().put(ENTRANCE, useCall).putInitialVisitedElement(useCall)
                )
                .filterNot { it is Overridable }
        }

        @RequiresReadLock
        fun provideNodesFromChildren(children: Collection<TreeElement>): Collection<TreeElement> =
            children.flatMap { provideNodesFromChild(it) }
    }
}
