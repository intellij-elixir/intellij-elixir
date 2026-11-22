package org.elixir_lang.sdk.elixir

import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.ui.MasterDetailsComponent
import com.intellij.openapi.ui.NamedConfigurable
import com.intellij.ui.JBSplitter
import com.intellij.ui.treeStructure.Tree
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.SdkFixtures
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** Project Structure's SDK list, found from an Elixir SDK's page, as `MasterDetailsComponent` lays a list and a page out. */
class SdkPageInDialogTest : PlatformTestCase() {
    private val sdk: Sdk by lazy { SdkFixtures.elixirSdk("Lookup Elixir", SdkFixtures.fakeHome("lookup-elixir")) }

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
