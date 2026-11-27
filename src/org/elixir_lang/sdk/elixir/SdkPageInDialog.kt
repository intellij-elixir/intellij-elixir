package org.elixir_lang.sdk.elixir

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.ui.ProjectJdksEditor
import com.intellij.openapi.roots.ui.configuration.ProjectJdksConfigurable
import com.intellij.openapi.roots.ui.configuration.ProjectStructureConfigurable
import com.intellij.openapi.roots.ui.configuration.projectRoot.JdkConfigurable
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.ui.MasterDetailsComponent
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.navigation.History
import com.intellij.ui.navigation.Place
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import org.elixir_lang.facet.SdksService
import org.elixir_lang.sdk.ProcessOutput.isSmallIde
import org.elixir_lang.sdk.choice.takenInFor
import org.jetbrains.annotations.VisibleForTesting
import java.awt.Component
import javax.swing.JComponent
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeNode
import org.elixir_lang.facet.sdks.elixir.Configurable as ElixirSdksConfigurable

/** What an Elixir SDK's page needs from IntelliJ IDEA's Project Structure, which the small IDEs don't have. */
interface SdkPageInDialog {
    /** The project whose Project Structure, its UI up, is over [sdkModel]; null under any other host of the page. */
    fun projectOf(sdkModel: SdkModel): Project?

    /**
     * Opens [sdk]'s page in the SDK dialog over [parent], counting it as new there when [made]; whether its OK saved it.
     * A [made] SDK is taken in for the dialog and, when its OK doesn't save it, taken out again. False, opening nothing,
     * where there is no such dialog.
     */
    fun editInSdkDialog(project: Project, sdk: Sdk, parent: Component, made: Boolean): Boolean

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

        /**
         * Whether a new Elixir SDK's page over [sdkModel] pairs it with an Erlang SDK, and refuses it until it has one: in
         * Project Structure and the SDK dialog, on a small IDE in Settings, whose pages share [SdksService]'s model, and in
         * the chooser, which opens the new SDK's page.
         */
        fun pairsOnItsPage(sdkModel: SdkModel): Boolean =
            sdkModel is AddsPairedOnTheirPage || getInstance().projectOf(sdkModel) != null ||
                isSmallIde && SdksService.getInstance()?.holds(sdkModel) == true

        /**
         * Whether [sdk], the table SDK behind one of [sdkModel]'s copies, was saved before the dialog over [sdkModel] opened:
         * the table holds it, and it isn't the one the SDK dialog was opened on as new.
         */
        fun savedBefore(sdkModel: SdkModel, sdk: Sdk): Boolean =
            (sdkModel as? SdkDialogModel)?.made !== sdk && service<SdksService>().inTable(sdk)
    }
}

/** A model whose adds are paired later, on the new SDK's page. */
interface AddsPairedOnTheirPage

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

    /** Settings' Elixir SDKs page at [sdk], over the model the SDK pages share, which takes [sdk] in first when [made]. */
    override fun editInSdkDialog(project: Project, sdk: Sdk, parent: Component, made: Boolean): Boolean {
        val service = SdksService.getInstance() ?: return false
        val shared = service.getModel()
        if (made) shared.addSdk(sdk)
        val copy = shared.projectSdks[sdk] ?: return false
        val page = ElixirSdksConfigurable()
        return ShowSettingsUtil.getInstance().editConfigurable(parent, page) { page.open(copy, made) } && service.inTable(sdk)
    }
}

/** The SDK dialog's own model, over [project], and [made], the table SDK it was opened on as new. */
internal class SdkDialogModel(val project: Project, val made: Sdk?) : ProjectSdksModel() {
    // The dialog's OK always applies: the page's edits to a copy never make the model read modified.
    override fun isModified(): Boolean = true
}

/** IntelliJ IDEA's SDK pages. */
internal class ProjectStructureSdkPage : SdkPageInDialog {
    /**
     * The SDK dialog's project, else Project Structure's: `isUiInitialized` tells Project Structure from
     * `ProjectJdksConfigurable`, whose one-argument constructor creates the same service and takes its model.
     */
    override fun projectOf(sdkModel: SdkModel): Project? {
        if (sdkModel is SdkDialogModel) return sdkModel.project
        val manager = ProjectManager.getInstance()
        return (manager.openProjects.asSequence() + manager.defaultProject).firstOrNull { project ->
            project.serviceIfCreated<ProjectStructureConfigurable>()?.let { it.isUiInitialized && it.projectJdksModel === sdkModel } == true
        }
    }

    /** The dialog's model resets from the table, so a [made] SDK goes into it first, unpaired. */
    override fun editInSdkDialog(project: Project, sdk: Sdk, parent: Component, made: Boolean): Boolean =
        takenInFor(sdk, made) {
            // Not `showAndGet`, which refuses the modeless peer a headless test shows a dialog in.
            SdkDialog(sdk, parent, SdkDialogModel(project, sdk.takeIf { made })).run { show(); isOK }
        }

    /**
     * `ProjectJdksEditor` at [sdk], over [model], a model of its own. Its OK applies [model] and, when that is refused, shows
     * why and stays open.
     */
    @VisibleForTesting
    internal class SdkDialog private constructor(
        sdk: Sdk,
        parent: Component,
        private val list: ProjectJdksConfigurable,
        model: SdkDialogModel,
    ) : ProjectJdksEditor(sdk, parent, list) {
        constructor(sdk: Sdk, parent: Component, model: SdkDialogModel) :
            this(sdk, parent, ProjectJdksConfigurable(model.project, model), model)

        init {
            // The list's OK takes out every SDK of the model it has no node for, as one its delete removed, and only its own
            // Add gives one: an SDK a page's field makes, such as an Erlang SDK from a Detected item, gets one here.
            model.addListener(object : SdkModel.Listener {
                override fun sdkAdded(sdk: Sdk) {
                    if (node(list.tree, sdk) != null) return
                    val tree = list.tree.model as DefaultTreeModel
                    val root = tree.root as MasterDetailsComponent.MyNode
                    val page = JdkConfigurable(sdk, model, list.tree::repaint, History(object : Place.Navigator {}), model.project)
                    tree.insertNodeInto(MasterDetailsComponent.MyNode(page), root, root.childCount)
                }
            }, disposable)
        }

        @VisibleForTesting
        internal fun clickOk() = doOKAction()

        /** The SDK whose page the dialog shows, its model's copy. */
        @get:VisibleForTesting
        internal val shown: Sdk? get() = list.selectedJdk
    }
}
