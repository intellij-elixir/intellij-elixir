package org.elixir_lang.sdk.elixir

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.roots.ui.configuration.ProjectStructureConfigurable
import com.intellij.openapi.ui.MasterDetailsComponent
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import java.awt.Component
import javax.swing.JComponent
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeNode

/** What an Elixir SDK's page needs from IntelliJ IDEA's Project Structure, which the small IDEs don't have. */
interface SdkPageInDialog {
    /** The project whose Project Structure, its UI up, is over [sdkModel]; null under any other host of the page. */
    fun projectOf(sdkModel: SdkModel): Project?

    /**
     * The SDK list of the dialog showing [page], [sdk]'s page: Project Structure's, that of an SDK dialog over it, or Settings'
     * SDKs page. Null when no list holds the page.
     *
     * A dialog shows its list in a splitter's first half and the selected page in the second, as `MasterDetailsComponent` does.
     */
    fun listOf(page: JComponent, sdk: Sdk): SdkList? =
        generateSequence<Component>(page) { it.parent }
            .filterIsInstance<Splitter>()
            .firstNotNullOfOrNull { splitter -> splitter.firstComponent?.let { sdkListIn(it, sdk) } }

    companion object {
        fun getInstance(): SdkPageInDialog =
            ApplicationManager.getApplication().getService(SdkPageInDialog::class.java)
    }
}

/** A dialog's SDK list, whose editors hold each SDK's Name until the dialog applies. */
interface SdkList {
    /** The name [sdk]'s editor holds. */
    fun nameOf(sdk: Sdk): String?

    /** Renames [sdk]'s editor, its Name field and its row, as the user's typing would. */
    fun rename(sdk: Sdk, name: String)

    /** Moves the list to [sdk]. */
    fun select(sdk: Sdk)
}

/** A list of SDKs that isn't a tree of `MasterDetailsComponent` nodes. */
interface ListsSdks {
    /** This list, when it lists [sdk]. */
    fun sdkListFor(sdk: Sdk): SdkList?
}

private fun sdkListIn(half: JComponent, sdk: Sdk): SdkList? =
    UIUtil.uiTraverser(half).firstNotNullOfOrNull { component ->
        when (component) {
            is Tree -> component.takeIf { node(it, sdk) != null }?.let(::TreeSdkList)
            is ListsSdks -> component.sdkListFor(sdk)
            else -> null
        }
    }

private fun node(tree: Tree, sdk: Sdk): MasterDetailsComponent.MyNode? =
    MasterDetailsComponent.findNodeByObject(tree.model.root as TreeNode, sdk)

private class TreeSdkList(private val tree: Tree) : SdkList {
    override fun nameOf(sdk: Sdk): String? = node(tree, sdk)?.configurable?.displayName

    override fun rename(sdk: Sdk, name: String) {
        val node = node(tree, sdk) ?: return
        val configurable = node.configurable ?: return
        configurable.displayName = name
        configurable.updateName()
        (tree.model as? DefaultTreeModel)?.nodeChanged(node)
    }

    override fun select(sdk: Sdk) {
        val node = node(tree, sdk) ?: return
        TreeUtil.selectInTree(node, true, tree, true)
    }
}

internal class NoProjectStructureSdkPage : SdkPageInDialog {
    override fun projectOf(sdkModel: SdkModel): Project? = null
}

internal class ProjectStructureSdkPage : SdkPageInDialog {
    /**
     * `isUiInitialized` tells Project Structure from `ProjectJdksConfigurable`, whose one-argument constructor creates the
     * same service and takes its model.
     */
    override fun projectOf(sdkModel: SdkModel): Project? {
        val manager = ProjectManager.getInstance()
        return (manager.openProjects.asSequence() + manager.defaultProject).firstOrNull { project ->
            project.serviceIfCreated<ProjectStructureConfigurable>()?.let { it.isUiInitialized && it.projectJdksModel === sdkModel } == true
        }
    }
}
