package org.elixir_lang.notification.setup_sdk

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.VfsTestUtil
import com.intellij.ui.HyperlinkLabel
import com.intellij.util.ui.UIUtil
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import org.elixir_lang.facet.SmallIdeFacetHeavyTestCase
import org.elixir_lang.jps.shared.ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.sdk.choice.RecordedChooser
import java.util.concurrent.Callable

private const val CHOOSE = "Choose Elixir SDK..."

/**
 * Clicks the banner's one link, [CHOOSE], on an Elixir file under [root] in [module], which has no Elixir SDK and no
 * tool-manager install.
 */
private fun clickBannersLink(project: Project, module: Module, root: VirtualFile) {
    PsiTestUtil.addContentRoot(module, root)
    val file = VfsTestUtil.createFile(root, "banner.ex", "")
    val panel = ReadAction.nonBlocking(Callable { Provider().collectNotificationData(project, file) }).executeSynchronously()
        ?.apply(FileEditorManager.getInstance(project).openFile(file, false).first())
    assertNotNull("the banner shows", panel)
    val link = UIUtil.findComponentsOfType(panel!!, HyperlinkLabel::class.java).single()
    assertEquals(CHOOSE, link.text)
    link.doClick()
}

/** IntelliJ IDEA: the module panel and the project panel ask the chooser, as the notification's action does. */
class ProviderTest : HeavyTestCase() {
    private lateinit var recorded: RecordedChooser

    override fun setUp() {
        super.setUp()
        recorded = RecordedChooser(testRootDisposable)
    }

    fun testTheModulePanelAsksTheChooserForTheFilesModule() {
        val elixirModule = doCreateRealModuleIn("elixir", project, ModuleTypeManager.getInstance().findByID(ELIXIR_MODULE_TYPE_ID))

        clickBannersLink(project, elixirModule, tempDir.createVirtualDir())

        recorded.assertChooserAskedFor(elixirModule)
    }

    fun testTheProjectPanelAsksTheChooserForTheFilesModule() {
        clickBannersLink(project, module, tempDir.createVirtualDir())

        recorded.assertChooserAskedFor(module)
    }

    fun testTheNotificationsActionAsksTheChooserForItsModule() {
        val notification = Notification("Elixir", "Mix settings", "", NotificationType.ERROR)
        val event = TestActionEvent.createTestEvent(SimpleDataContext.getProjectContext(project))

        Action(project, module).actionPerformed(event, notification)

        recorded.assertChooserAskedFor(module)
    }
}

/** A small IDE: the facet panel asks the chooser. */
class ProviderSmallIdeTest : SmallIdeFacetHeavyTestCase() {
    private lateinit var recorded: RecordedChooser

    override fun setUp() {
        super.setUp()
        recorded = RecordedChooser(testRootDisposable)
    }

    fun testTheFacetPanelAsksTheChooserForTheFilesModule() {
        clickBannersLink(project, module, tempDir.createVirtualDir())

        recorded.assertChooserAskedFor(module)
    }
}
