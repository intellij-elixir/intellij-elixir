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
import java.awt.Component
import javax.swing.JComponent
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeNode

/** What an Elixir SDK's page needs from IntelliJ IDEA's Project Structure, which the small IDEs don't have. */
interface SdkPageInDialog {
    /** The project whose Project Structure, its UI up, is over [sdkModel]; null under any other host of the page. */
    fun projectOf(sdkModel: SdkModel): Project?

    /** Moves the SDK list of [project]'s Project Structure to [sdk], a copy in its model. */
    fun selectInList(project: Project, sdk: Sdk)

    /**
     * The SDK list of the dialog showing [page], [sdk]'s page: Project Structure's, or that of an SDK dialog over it. Null
     * when no list holds the page.
     */
    fun listOf(page: JComponent, sdk: Sdk): SdkList? = null

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
}

internal class NoProjectStructureSdkPage : SdkPageInDialog {
    override fun projectOf(sdkModel: SdkModel): Project? = null

    override fun selectInList(project: Project, sdk: Sdk) = Unit
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

    override fun selectInList(project: Project, sdk: Sdk) {
        ProjectStructureConfigurable.getInstance(project).jdkConfig.selectNodeInTree(sdk)
    }

    /** A `MasterDetailsComponent` shows its tree in its splitter's first half and the selected page in the second. */
    override fun listOf(page: JComponent, sdk: Sdk): SdkList? =
        generateSequence<Component>(page) { it.parent }
            .filterIsInstance<Splitter>()
            .mapNotNull { splitter -> splitter.firstComponent?.let { UIUtil.findComponentOfType(it, Tree::class.java) } }
            .firstOrNull { tree -> node(tree, sdk) != null }
            ?.let(::TreeSdkList)

    private class TreeSdkList(private val tree: Tree) : SdkList {
        override fun nameOf(sdk: Sdk): String? = node(tree, sdk)?.configurable?.displayName

        override fun rename(sdk: Sdk, name: String) {
            val node = node(tree, sdk) ?: return
            val configurable = node.configurable ?: return
            configurable.displayName = name
            configurable.updateName()
            (tree.model as? DefaultTreeModel)?.nodeChanged(node)
        }
    }

    private companion object {
        fun node(tree: Tree, sdk: Sdk): MasterDetailsComponent.MyNode? =
            MasterDetailsComponent.findNodeByObject(tree.model.root as TreeNode, sdk)
    }
}
