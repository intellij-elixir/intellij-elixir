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
import org.elixir_lang.NameArity
import org.elixir_lang.psi.call.Call
import org.elixir_lang.structure_view.element.*
import org.elixir_lang.structure_view.element.modular.Module
import org.elixir_lang.structure_view.element.modular.Module.Companion.addClausesToCallDefinition
import org.jetbrains.annotations.NonNls
import java.util.*

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
        private const val USING = "__using__"

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

        /** What [child] injects, when it is a `use` of a module in source. */
        private fun provideNodesFromChild(child: TreeElement): Collection<TreeElement> =
            (child as? Use)
                ?.let { use ->
                    org.elixir_lang.psi.Use.modulars(use.call())
                        .filterIsInstance<Call>()
                        .filter { org.elixir_lang.psi.Module.`is`(it) }
                        .firstNotNullOfOrNull { injectedBy(it, use) }
                }
                .orEmpty()

        /**
         * What [modular]'s `__using__/1` injects through [use], when it has one clause: that clause runs whatever
         * [use] passes, while which of several runs is not worked out.
         */
        private fun injectedBy(modular: Call, use: Use): List<TreeElement>? {
            val module = Module(modular)
            val macroByNameArity = HashMap<NameArity, CallDefinition>()

            for (definer in org.elixir_lang.psi.Using.definers(modular)) {
                val definerForm = org.elixir_lang.psi.CallableDeclaration.definerOf(definer) ?: continue
                val nameArityInterval = org.elixir_lang.psi.CallDefinitionClause
                    .nameArityInterval(definer, ResolveState.initial()) ?: continue

                addClausesToCallDefinition(
                    definer,
                    nameArityInterval.name,
                    nameArityInterval.arityInterval,
                    macroByNameArity,
                    module,
                    definerForm
                ) { _ -> }
            }

            val clause = macroByNameArity[NameArity(USING, 1)]?.clauseList()?.singleOrNull() ?: return null
            val quote = clause.children.lastOrNull() as? Quote ?: return null

            return quote.used(use).children.filterNot { it is Overridable }
        }

        fun provideNodesFromChildren(children: Collection<TreeElement>): Collection<TreeElement> =
            children.flatMap { provideNodesFromChild(it) }
    }
}
