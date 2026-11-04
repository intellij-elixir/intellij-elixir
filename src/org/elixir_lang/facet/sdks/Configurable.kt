package org.elixir_lang.facet.sdks

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ex.ConfigurableCardPanel
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.ActionCallback
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.navigation.History
import com.intellij.ui.navigation.Place
import com.intellij.util.ui.JBUI
import org.elixir_lang.facet.SdksService
import org.elixir_lang.facet.sdk.Editor
import javax.swing.JComponent
import javax.swing.JPanel

private val LOG = logger<Configurable>()

fun Library.ModifiableModel.addRoots(sdk: Sdk) =
        sdk
                .rootProvider
                .getFiles(OrderRootType.CLASSES)
                .let { addRoots(it) }

abstract class Configurable: SearchableConfigurable, com.intellij.openapi.options.Configurable.NoScroll {
    internal val sdksService by lazy { SdksService.getInstance()!! }
    private val projectSdksModel by lazy { sdksService.getModel() }
    private lateinit var rootSplitter: Splitter
    private val editorByProjectJdkImpl: MutableMap<ProjectJdkImpl, Editor> = mutableMapOf()
    private lateinit var sdkPanel: ConfigurableCardPanel
    private lateinit var sdkList: List
    private lateinit var sdkListPanel: JPanel
    private var history : History? = History(object : Place.Navigator {
        override fun setHistory(history: History?) {
            this@Configurable.history = history
        }

        override fun navigateTo(place: Place?, requestFocus: Boolean): ActionCallback? = null

        override fun queryPlace(place: Place) {
        }
    })

    override fun apply() {
        editorByProjectJdkImpl.forEach { (_, editor) ->
            editor.apply()
        }
        sdksService.apply(projectSdksModel)
        removeLibraries()
    }

    /**
     * The application libraries of the SDKs removed on this page, which go when the removal is applied, but for one an
     * SDK added under the same name has taken back.
     */
    private val removedLibraries = mutableSetOf<Library>()

    private fun removeLibraries() {
        if (removedLibraries.isEmpty()) return
        val libraryTable = LibraryTablesRegistrar.getInstance().libraryTable
        val libraries = removedLibraries.intersect(libraryTable.libraries.toSet())
        removedLibraries.clear()
        if (libraries.isNotEmpty()) {
            ApplicationManager.getApplication().runWriteAction { libraries.forEach(libraryTable::removeLibrary) }
        }
    }

    override fun createComponent(): JComponent? {
        sdkList = List(this)

        val decorator = ToolbarDecorator
                .createDecorator(sdkList)
                .disableUpDownActions()
                .setAddAction { addSdk() }
                .setEditAction(null)
                .setRemoveAction { removeSdk() }

        sdkListPanel = decorator.createPanel()
        sdkList.refresh()
        addListeners()
        // TODO show normal SDK configuration
        sdkPanel = ConfigurableCardPanel()
        rootSplitter = OnePixelSplitter(false).apply {
            border = JBUI.Borders.empty()
            firstComponent = sdkListPanel
            secondComponent = sdkPanel
            isShowDividerControls = false
            setHonorComponentsMinimumSize(true)
        }

        return rootSplitter
    }

    override fun getHelpTopic(): String? = null

    override fun isModified(): Boolean =
            projectSdksModel.isModified || editorByProjectJdkImpl.any { entry -> entry.value.isModified }

    override fun reset() {
        // The shared model is populated once (SdksService.initModel) and kept in sync via the
        // SdkModel listeners; do NOT reset it here - re-cloning would invalidate the SDK objects
        // held by other open SDK views (and break removeSdk, whose findSdk matches by identity).
        sdkList.refresh()
    }

    abstract fun sdkType(): SdkType

    private fun addCreatedSdk(sdk: Sdk?) {
        sdk?.let {
            if (it.sdkType == sdkType()) {
                sdkList.apply {
                    refresh()
                    setSelectedValue(sdk, true)
                }
            }
        }
    }

    /**
     * The listener added to the shared, application-lifetime [ProjectSdksModel][com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel]
     * in [addListeners]; kept so [disposeUIResources] can remove it. Without removal, one listener
     * (pinning this Configurable and its Swing tree) accumulates on every Settings open.
     */
    private var sdkModelListener: SdkModel.Listener? = null

    private fun addListeners() {
        val listener = object : SdkModel.Listener {
            override fun beforeSdkRemove(sdk: Sdk) {
                LOG.debug("beforeSdkRemove(sdk.name='${sdk.name}')")
                LibraryTablesRegistrar.getInstance().libraryTable.getLibraryByName(sdk.name)?.let(removedLibraries::add)
            }

            override fun sdkAdded(sdk: Sdk) {
                LOG.debug("sdkAdded(sdk.name='${sdk.name}')")
                LibraryTablesRegistrar.getInstance().libraryTable.let { libraryTable ->
                    ApplicationManager.getApplication().runWriteAction {
                        val library = libraryTable.getLibraryByName(sdk.name)?.also(removedLibraries::remove)
                            ?: libraryTable.createLibrary(sdk.name)
                        library.modifiableModel.apply {
                            replaceRoots(sdk)
                            commit()
                        }
                    }
                }

                sdkList.refresh()
            }

            override fun sdkChanged(sdk: Sdk, previousName: String) {
                LOG.debug("sdkChanged(sdk.name='${sdk.name}', previousName='$previousName')")
                if (sdk.name != previousName) {
                    LibraryTablesRegistrar.getInstance().libraryTable.getLibraryByName(previousName)?.let { library ->
                        ApplicationManager.getApplication().runWriteAction {
                            library.modifiableModel.apply {
                                name = sdk.name
                                replaceRoots(sdk)
                                commit()
                            }
                        }
                    }
                } else {
                    LibraryTablesRegistrar.getInstance().libraryTable.getLibraryByName(sdk.name)?.let { library ->
                        ApplicationManager.getApplication().runWriteAction {
                            library.modifiableModel.apply {
                                replaceRoots(sdk)
                                commit()
                            }
                        }
                    }
                }

                sdkList.refresh()
            }

            override fun sdkHomeSelected(sdk: Sdk, newSdkHome: String) {
                LOG.debug("sdkHomeSelected(sdk.name='${sdk.name}', newSdkHome='$newSdkHome')")
            }
        }
        projectSdksModel.addListener(listener)
        sdkModelListener = listener
        sdkList.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                updateSdkPanel(sdkList.selectedValue)
            }
        }
    }

    override fun disposeUIResources() {
        sdkModelListener?.let { projectSdksModel.removeListener(it) }
        sdkModelListener = null
        editorByProjectJdkImpl.clear()
        removedLibraries.clear()
        sdksService.discardUnapplied(projectSdksModel)
    }

    private fun addSdk() {
        projectSdksModel.doAdd(sdkListPanel, sdkType()) { sdk -> addCreatedSdk(sdk) }
    }

    private fun removeSdk() {
        val selected = sdkList.selectedValue ?: return
        removeSelectedSdk(selected)
    }

    /**
     * Removes [selected] (the editable model clone shown in the list) from the model, which takes it out of the table
     * when applied, after validating what is left, as IDEA's Project Structure does. Extracted for testing.
     */
    internal fun removeSelectedSdk(selected: ProjectJdkImpl) {
        projectSdksModel.removeSdk(selected)

        editorByProjectJdkImpl[selected]?.let {
            sdkPanel.getValue(sdkPanel.key, false).let { value ->
                sdkPanel.remove(value)
            }
        }
        editorByProjectJdkImpl.remove(selected)

        sdkList.refresh()
    }

    private fun updateSdkPanel(selectedValue: ProjectJdkImpl?) {
        val selectedEditor = selectedValue?.let { sdk ->
            editorByProjectJdkImpl.computeIfAbsent(sdk) { Editor(projectSdksModel, history!!, sdk) }
        }

        sdkPanel.select(selectedEditor, true)
    }
}

private fun Library.ModifiableModel.addRoots(roots: Array<VirtualFile>) =
        roots.forEach {
            addRoot(it, OrderRootType.CLASSES)
        }

private fun Library.ModifiableModel.clearRoots() {
    getUrls(OrderRootType.CLASSES).forEach {
        removeRoot(it, OrderRootType.CLASSES)
    }
}

private fun Library.ModifiableModel.replaceRoots(sdk: Sdk) {
    clearRoots()
    addRoots(sdk)
}
