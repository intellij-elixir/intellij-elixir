package org.elixir_lang.sdk.elixir

import com.intellij.ide.actions.ShowSettingsUtilImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.ui.configuration.ProjectStructureConfigurable
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.MasterDetailsComponent
import com.intellij.openapi.ui.NamedConfigurable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.runAll
import com.intellij.testFramework.replaceService
import com.intellij.ui.JBSplitter
import com.intellij.ui.UiInterceptors
import com.intellij.ui.treeStructure.Tree
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.facet.SdksService
import org.elixir_lang.facet.sdks.SdksPage
import org.elixir_lang.sdk.ProcessOutput
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.choice.SdkRowsDialog
import org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.registeredErlang
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import org.elixir_lang.facet.sdks.Configurable as SdksConfigurable
import org.elixir_lang.facet.sdks.elixir.Configurable as ElixirSdksConfigurable

/**
 * The SDK list of the dialog showing an Elixir SDK's page: Project Structure's, as `MasterDetailsComponent` lays a list and a
 * page out, or Settings' Elixir SDKs page.
 */
class SdkPageInDialogTest : PlatformTestCase() {
    private val sdk: Sdk by lazy { SdkFixtures.elixirSdk("Lookup Elixir", SdkFixtures.fakeHome("lookup-elixir")) }

    private lateinit var table: SdkFixtures.TableSnapshot

    private val settingsPages = mutableListOf<SdksPage>()

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        SdksService.getInstance()!!.resetForTests()
    }

    override fun tearDown() {
        runAll(
            { settingsPages.toList().forEach(SdksPage::cancel) },
            { table.restore() },
            { SdksService.getInstance()!!.resetForTests() },
            { super.tearDown() },
        )
    }

    fun testAPageInNoListHasNone() {
        assertNull(ProjectStructureSdkPage().listOf(JPanel(), sdk))
    }

    /** No fallback to another list, such as Project Structure's while its SDK dialog shows the page. */
    fun testAPageBesideAListWithoutItsSdkHasNone() {
        val page = JPanel()
        listShowing(page, SdkFixtures.elixirSdk("Lookup other Elixir", SdkFixtures.fakeHome("lookup-other")))

        assertNull(ProjectStructureSdkPage().listOf(page, sdk))
    }

    fun testThePageRenamesItsSdkInTheListShowingIt() {
        val page = JPanel()
        val list = listShowing(page, sdk)

        val found = checkNotNull(ProjectStructureSdkPage().listOf(page, sdk))
        assertEquals(sdk.name, found.nameOf(sdk))
        found.rename(sdk, "Lookup renamed")

        assertEquals("Lookup renamed", list.editor.displayName)
        assertEquals("Lookup renamed", found.nameOf(sdk))
    }

    /** `ProjectJdksEditor` over Project Structure: both lists hold the copy, and only the dialog's shows the page. */
    fun testAnSdkDialogOverProjectStructureUsesItsOwnList() {
        val page = JPanel()
        val dialog = listShowing(page, sdk)
        val projectStructure = listShowing(dialog.component, sdk)

        val found = checkNotNull(ProjectStructureSdkPage().listOf(page, sdk))
        found.rename(sdk, "Lookup renamed in the dialog")
        found.select(sdk)
        SdkFixtures.settle()

        assertEquals("Lookup renamed in the dialog", dialog.editor.displayName)
        assertEquals("Project Structure's editor keeps its name", sdk.name, projectStructure.editor.displayName)
        assertSame(dialog.node, dialog.tree.selectionPath?.lastPathComponent)
        assertNull("Project Structure's list stays where it is", projectStructure.tree.selectionPath)
    }

    fun testThePageRenamesItsSdkInSettingsList() {
        val copy = settingsCopyOf(sdk)
        val editor = SdksPage(ElixirSdksConfigurable(), settingsPages).show(copy)

        val found = checkNotNull(SdkPageInDialog.getInstance().listOf(editor.createComponent(), copy))
        assertEquals(sdk.name, found.nameOf(copy))
        found.rename(copy, "Lookup renamed in Settings")

        assertEquals("the editor's Name field", "Lookup renamed in Settings", editor.name)
        assertEquals("Lookup renamed in Settings", found.nameOf(copy))
        assertEquals("the copy keeps its name until Apply", sdk.name, copy.name)
    }

    fun testSettingsListWithoutItsSdkHasNone() {
        val copy = settingsCopyOf(sdk)
        val editor = SdksPage(ElixirSdksConfigurable(), settingsPages).show(copy)
        val other = SdkFixtures.elixirSdk("Lookup unlisted Elixir", SdkFixtures.fakeHome("lookup-unlisted"))

        assertNull(SdkPageInDialog.getInstance().listOf(editor.createComponent(), other))
    }

    fun testProjectStructureModelPairsOnItsPage() {
        val structure = ProjectStructureConfigurable.getInstance(project)
        structure.createComponent()
        try {
            assertSame(project, SdkPageInDialog.getInstance().projectOf(structure.projectJdksModel))
            assertTrue(SdkPageInDialog.pairsOnItsPage(structure.projectJdksModel))
        } finally {
            structure.disposeUIResources()
        }
    }

    /** The SDK dialog's own model, which the chooser opens over an SDK it made or a broken row's SDK. */
    fun testSdkDialogModelPairsOnItsPage() {
        val made = SdkFixtures.register(SdkFixtures.elixirSdk("Lookup made", SdkFixtures.fakeHome("lookup-made")))
        val saved = SdkFixtures.register(SdkFixtures.elixirSdk("Lookup saved", SdkFixtures.fakeHome("lookup-saved")))
        val model = SdkDialogModel(project, made)

        assertSame(project, SdkPageInDialog.getInstance().projectOf(model))
        assertTrue(SdkPageInDialog.pairsOnItsPage(model))
        assertFalse("the SDK it was opened on as new", SdkPageInDialog.savedBefore(model, made))
        assertTrue(SdkPageInDialog.savedBefore(model, saved))
        assertTrue("opened on a saved SDK", SdkPageInDialog.savedBefore(SdkDialogModel(project, null), made))
    }

    /** An IDE with IntelliJ IDEA's SDK dialog but another product code, as Android Studio is, pairs in it all the same. */
    fun testSdkDialogModelPairsOnItsPageWhateverTheProduct() {
        ProcessOutput.isSmallIdeOverride = true
        try {
            val model = SdkDialogModel(project, null)

            assertSame(project, SdkPageInDialog.getInstance().projectOf(model))
            assertTrue(SdkPageInDialog.pairsOnItsPage(model))
        } finally {
            ProcessOutput.isSmallIdeOverride = null
        }
    }

    /** Its OK refuses the SDK it was opened on as new while that has no Erlang SDK, though the table holds it. */
    fun testTheSdkDialogRefusesTheSdkItMadeUntilPaired() {
        val made = madeUnpaired()
        for ((opened, refused) in listOf(made to true, null to false)) {
            val model = SdkDialogModel(project, opened).apply { reset(project) }
            try {
                val copy = model.projectSdks.getValue(made)
                val refusal = runCatching { (copy.sdkAdditionalData as SdkAdditionalData).checkValid(model) }.exceptionOrNull()
                assertEquals("opened on ${opened?.name}", refused, refusal is ConfigurationException)
            } finally {
                model.disposeUIResources()
            }
        }
    }

    /** Its page writes an edit into the copy as it is made, which leaves the model unmodified: OK saves it all the same. */
    fun testTheSdkDialogsOkSavesAnEditToItsCopy() {
        val made = madeUnpaired()
        val erlang = registeredErlang("Lookup Erlang", "27.3.4")
        withSdkDialog(made) { model, dialog ->
            val copy = model.projectSdks.getValue(made)
            SdkFixtures.commitPairing(copy, erlang)

            dialog.clickOk()

            assertTrue("the dialog closes as saved", dialog.isOK)
            assertSame(erlang, (made.sdkAdditionalData as SdkAdditionalData).getErlangSdk())
        }
    }

    /** An Erlang SDK its page's field adds to its model, which the dialog's list has no node for, is saved at OK with the pairing. */
    fun testTheSdkDialogsOkSavesAnErlangSdkItsFieldMade() {
        val made = madeUnpaired()
        withSdkDialog(made) { model, dialog ->
            val erlang = SdkFixtures.erlangSdk("Lookup field Erlang", SdkFixtures.erlangHomeWithEbin("27", "27.3.4"))
            model.addSdk(erlang)
            val copy = model.projectSdks.getValue(made)
            SdkFixtures.commitPairing(copy, erlang)

            dialog.clickOk()

            assertTrue("the dialog closes as saved", dialog.isOK)
            val saved = ProjectJdkTable.getInstance().findJdk(erlang.name)
            assertNotNull("the field's Erlang SDK is in the table", saved)
            assertSame(saved, (made.sdkAdditionalData as SdkAdditionalData).getErlangSdk())
        }
    }

    /** Opened on an SDK not yet in the table, as new, the SDK dialog shows its page, and its Cancel leaves the table without it. */
    fun testTheSdkDialogOpenedOnANewSdkShowsItsPageAndItsCancelLeavesNoSdk() {
        val made = unpaired()
        val before = ProjectJdkTable.getInstance().allJdks.map { it.name }
        var shown: String? = null
        UiInterceptors.registerPossible(
            testRootDisposable,
            object : UiInterceptors.UiInterceptor<ProjectStructureSdkPage.SdkDialog>(ProjectStructureSdkPage.SdkDialog::class.java) {
                override fun doIntercept(component: ProjectStructureSdkPage.SdkDialog) {
                    SdkFixtures.settle()
                    shown = component.shown?.name
                    component.close(DialogWrapper.CANCEL_EXIT_CODE)
                }
            },
        )

        val saved = ProjectStructureSdkPage().editInSdkDialog(project, made, JPanel(), made = true)

        assertEquals("the dialog shows the new SDK's page", made.name, shown)
        assertFalse(saved)
        assertEquals(before, ProjectJdkTable.getInstance().allJdks.map { it.name })
    }

    /**
     * On a small IDE, the Elixir SDKs page the chooser opens on its new SDK counts it as the page's add: OK refuses it until
     * it has an Erlang SDK, and Cancel takes it out of the shared model.
     */
    fun testTheElixirSdksPageOpenedOnANewSdkCountsItAsItsAdd() = inASmallIde {
        val shared = SdksService.getInstance()!!.getModel()
        val made = unpaired()
        shared.addSdk(made)
        val copy = SdksPage.copy(made)
        val configurable = ElixirSdksConfigurable()
        val page = SdksPage(configurable, settingsPages)

        configurable.open(copy, made = true)

        assertSame(copy, page.selected)
        assertTrue(page.isModified)
        assertThrows(ConfigurationException::class.java) { page.apply() }
        page.cancel()
        assertNull(shared.projectSdks[made])
    }

    /** Opened on a saved SDK, the page selects it and leaves it, unmodified, in the shared model. */
    fun testTheElixirSdksPageOpenedOnASavedSdkSelectsIt() {
        val copy = settingsCopyOf(sdk)
        val configurable = ElixirSdksConfigurable()
        val page = SdksPage(configurable, settingsPages)

        configurable.open(copy, made = false)

        assertSame(copy, page.selected)
        assertFalse(page.isModified)
        page.cancel()
        assertSame(copy, SdksPage.copy(sdk))
    }

    /**
     * On a small IDE, an edit to a saved SDK whose OK was refused and then cancelled is gone when the chooser opens its page
     * again, with no event run between, as none runs at `nonModal` under the modal chooser; a later OK saves none of it.
     */
    fun testTheElixirSdksPageReopenedAfterACancelShowsTheSavedSdk() = inASmallIde {
        val saved = madeUnpaired()
        val erlang = registeredErlang("Lookup Erlang", "27.3.4")
        SdksService.getInstance()!!.resetForTests()
        val page = SdkPageInDialog.getInstance()

        settingsScripted { settings ->
            settings.rename(saved, "Lookup renamed")
            assertThrows(ConfigurationException::class.java) { settings.apply() }
            settings.cancel()
            false
        }
        assertFalse(page.editInSdkDialog(project, saved, JPanel(), made = false))

        settingsScripted { settings ->
            val copy = SdksPage.copy(saved)
            assertEquals("the reopened page shows the saved name", "Lookup made", settings.nameShown(copy))
            settings.pickErlang(saved, erlang)
            settings.apply()
            settings.cancel()
            true
        }
        assertTrue(page.editInSdkDialog(project, saved, JPanel(), made = false))
        assertEquals("the later OK saves no cancelled edit", "Lookup made", saved.name)
        assertSame(erlang, (saved.sdkAdditionalData as SdkAdditionalData).getErlangSdk())
    }

    /**
     * On a small IDE, an edit to a saved SDK whose Apply was refused in Settings, opened by the chooser's Manage SDKs..., and
     * then cancelled is gone when the chooser's Edit SDK... opens its page, with no event run between.
     */
    fun testTheElixirSdksPageOpenedAfterASettingsCancelShowsTheSavedSdk() = inASmallIde {
        val saved = madeUnpaired()
        SdksService.getInstance()!!.resetForTests()
        val manageSdks = SdksPage(ElixirSdksConfigurable(), settingsPages)
        manageSdks.rename(saved, "Lookup renamed")
        assertThrows(ConfigurationException::class.java) { manageSdks.apply() }
        manageSdks.cancel()

        settingsScripted { settings ->
            assertEquals("the page shows the saved name", "Lookup made", settings.nameShown(SdksPage.copy(saved)))
            settings.cancel()
            false
        }
        assertFalse(SdkPageInDialog.getInstance().editInSdkDialog(project, saved, JPanel(), made = false))
    }

    fun testChooserModelPairsOnItsPage() {
        val rows = SdkRowsDialog(project, null, emptyList(), null)
        try {
            assertTrue(SdkPageInDialog.pairsOnItsPage(rows.model))
            assertNull(SdkPageInDialog.getInstance().projectOf(rows.model))
        } finally {
            Disposer.dispose(rows.disposable)
        }
    }

    fun testSdksServiceModelPairsOnItsPageOnASmallIde() = inASmallIde {
        assertTrue(SdkPageInDialog.pairsOnItsPage(SdksService.getInstance()!!.getModel()))
    }

    fun testOtherModelDoesNotPair() {
        assertFalse(SdkPageInDialog.pairsOnItsPage(ProjectSdksModel()))
        inASmallIde { assertFalse(SdkPageInDialog.pairsOnItsPage(ProjectSdksModel())) }
    }

    /** "Lookup made", with no Erlang SDK, not in the table. */
    private fun unpaired(): Sdk =
        SdkFixtures.elixirSdk("Lookup made", elixirInstall("1.18.4", "27")).also { SdkFixtures.commit(it, SdkAdditionalData(it)) }

    /** "Lookup made", in the table with no Erlang SDK, as the chooser's new SDK is when the SDK dialog opens on it. */
    private fun madeUnpaired(): Sdk =
        SdkFixtures.register(SdkFixtures.elixirSdk("Lookup made", elixirInstall("1.18.4", "27")))
            .also { SdkFixtures.commit(it, SdkAdditionalData(it)) }

    /** The SDK dialog opened on [made] as new, over its own model, once it has selected its SDK, as it does when it shows. */
    private fun withSdkDialog(made: Sdk, body: (SdkDialogModel, ProjectStructureSdkPage.SdkDialog) -> Unit) {
        val model = SdkDialogModel(project, made)
        val dialog = ProjectStructureSdkPage.SdkDialog(made, JPanel(), model)
        try {
            SdkFixtures.settle()
            body(model, dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    /** [test] on a small IDE, which has no Project Structure. */
    private fun inASmallIde(test: () -> Unit) {
        ProcessOutput.isSmallIdeOverride = true
        val host = Disposer.newDisposable()
        try {
            ApplicationManager.getApplication().replaceService(SdkPageInDialog::class.java, NoProjectStructureSdkPage(), host)
            test()
        } finally {
            Disposer.dispose(host)
            ProcessOutput.isSmallIdeOverride = null
        }
    }

    /**
     * Settings, until this test ends, as [user] drives the one page it opens, once that page has run its initialization.
     */
    private fun settingsScripted(user: (SdksPage) -> Boolean) {
        val settings = object : ShowSettingsUtilImpl() {
            override fun editConfigurable(parent: Component?, configurable: Configurable, advancedInitialization: Runnable?): Boolean {
                val page = SdksPage(configurable as SdksConfigurable, settingsPages)
                advancedInitialization?.run()
                return user(page)
            }
        }
        ApplicationManager.getApplication().replaceService(ShowSettingsUtil::class.java, settings, testRootDisposable)
    }

    /** [saved]'s copy in the model Settings' SDK pages share. */
    private fun settingsCopyOf(saved: Sdk): ProjectJdkImpl {
        SdkFixtures.register(saved)
        SdksService.getInstance()!!.resetForTests()
        return SdksPage.copy(saved)
    }

    /** A list of one SDK, [listed], in a splitter's first half, with [page] in its second. */
    private fun listShowing(page: JComponent, listed: Sdk): ListOfOne {
        val editor = Editor(listed)
        val node = MasterDetailsComponent.MyNode(editor)
        val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode().apply { add(node) }))
        val splitter = JBSplitter().apply {
            firstComponent = JPanel().apply { add(tree) }
            secondComponent = JPanel().apply { add(page) }
        }
        return ListOfOne(splitter, tree, node, editor)
    }

    private class ListOfOne(val component: JComponent, val tree: Tree, val node: MasterDetailsComponent.MyNode, val editor: Editor)

    /** An SDK's editor in a list, holding its Name as `JdkConfigurable` does. */
    private class Editor(private val sdk: Sdk) : NamedConfigurable<Sdk>() {
        private var name: String = sdk.name

        override fun getDisplayName(): String = name

        override fun setDisplayName(name: String) {
            this.name = name
        }

        override fun getEditableObject(): Sdk = sdk

        override fun getBannerSlogan(): String = name

        override fun createOptionsPanel(): JComponent = JPanel()

        override fun isModified(): Boolean = false

        override fun apply() = Unit
    }
}
