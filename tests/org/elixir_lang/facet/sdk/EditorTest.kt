package org.elixir_lang.facet.sdk

import com.intellij.openapi.application.ApplicationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.UI
import com.intellij.openapi.options.ex.ConfigurableCardPanel
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.ui.navigation.History
import com.intellij.ui.navigation.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.facet.SdksService
import org.elixir_lang.sdk.ProcessOutput
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.elixir_lang.sdk.erlang_dependent.SdkAdditionalData as ElixirSdkAdditionalData
import kotlin.time.Duration.Companion.seconds

/**
 * The Elixir SDKs settings page builds an [Editor] from a list-selection listener and shows it through
 * [ConfigurableCardPanel], which resets it inside a read action. 2026.2 dispatches Swing events holding no lock; the test
 * EDT's write-intent lock satisfies every lock assertion, so these check the contracts directly instead.
 */
class EditorTest : PlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        // The page is only offered in small IDEs; in IDEA the editor has no path editor for the javadoc roots it is asked for.
        ProcessOutput.isSmallIdeOverride = true
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            WslCompatService::class.java,
            MockWslCompatService(),
            testRootDisposable,
        )
        SdksService.getInstance()!!.resetForTests()
    }

    override fun tearDown() {
        try {
            ProcessOutput.isSmallIdeOverride = null
            SdksService.getInstance()!!.resetForTests()
        } finally {
            super.tearDown()
        }
    }

    fun testSelectingAnSdkBuildsItsEditorWithoutALock() {
        val sdk = onEdt { pairedElixirSdkCopy("Editor Built Unlocked") }

        val failure = withoutModelAccess { Editor(model(), history(), sdk) }

        assertNull("building the editor must not need a lock; failed with ${describe(failure)}", failure)
    }

    fun testTheCardPanelResetsTheEditorWithoutAWriteAction() {
        val parent = FileUtil.createTempDirectory("editor-erlang-homes", null, true)
        val erlangHome = File(parent, "erlang")
        onEdt { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(parent)!!.children }
        val editor = onEdt {
            Editor(model(), history(), pairedElixirSdkCopy("Editor Reset In Read Action", erlangHome.path))
        }
        // Created after the VFS listed its parent, so the VFS holds it as absent: a refreshing lookup of it fires an event.
        assertTrue(erlangHome.mkdir())
        val writeActions = countWriteActions()

        onEdt { ConfigurableCardPanel.createConfigurableComponent(editor) }

        assertEquals("the card panel resets the editor inside a read action, where a write action throws", 0, writeActions.get())
    }

    fun testTheEditorTheCardPanelShowsIsUnmodified() {
        val editor = onEdt { Editor(model(), history(), pairedElixirSdkCopy("Editor Shown Unmodified")) }

        onEdt { ConfigurableCardPanel.createConfigurableComponent(editor) }

        assertFalse("an editor just shown must match its SDK", onEdt { editor.isModified })
    }

    fun testBrowsingToANewErlangHomeKeepsIt() {
        val homeA = SdkFixtures.erlangHomeWithEbin("26", "26.2.5")
        val homeB = SdkFixtures.erlangHomeWithEbin("27", "27.3.4")
        val sdk = onEdt {
            register(SdkFixtures.erlangSdk("Browsed Erlang", homeA))
            SdksService.getInstance()!!.resetForTests()
            model().findSdk("Browsed Erlang") as ProjectJdkImpl
        }

        assertBrowsingKeepsTheNewHome(sdk, homeA, homeB)
    }

    fun testBrowsingToANewElixirHomeKeepsIt() {
        val homeA = SdkFixtures.elixirHome("1.18.4")
        val homeB = SdkFixtures.elixirHome("1.19.5")
        val sdk = onEdt { pairedElixirSdkCopy("Browsed Elixir", SdkFixtures.erlangHomeWithEbin("27", "27.3.4"), homeA) }

        assertBrowsingKeepsTheNewHome(sdk, homeA, homeB)
    }

    /** Browses [sdk]'s editor from [homeA] to [homeB] as the home chooser's callback does: on the EDT, no write action. */
    private fun assertBrowsingKeepsTheNewHome(sdk: ProjectJdkImpl, homeA: String, homeB: String) {
        val sdkType = sdk.sdkType as SdkType
        val editor = onEdt { Editor(model(), history(), sdk).also { ConfigurableCardPanel.createConfigurableComponent(it) } }
        try {
            val name = sdk.name
            onEdt { editor.doSetHomePath(homeB, sdkType) }
            assertTrue("browsing to a new home modifies the editor", onEdt { editor.isModified })

            onEdt { editor.apply() }

            assertEquals(FileUtil.toSystemIndependentName(homeB), FileUtil.toSystemIndependentName(sdk.homePath!!))
            val roots = sdk.rootProvider.getUrls(OrderRootType.CLASSES).map(VfsUtilCore::urlToPath)
            assertTrue("a CLASSES root lies under the new home: $roots", roots.any { FileUtil.isAncestor(homeB, it, false) })
            assertFalse("no CLASSES root lies under the old home: $roots", roots.any { FileUtil.isAncestor(homeA, it, false) })
            assertEquals("browsing renames nothing", name, sdk.name)
        } finally {
            onEdt { editor.disposeUIResources() }
        }
    }

    private fun pairedElixirSdkCopy(
        name: String,
        erlangHome: String = "/fake/erlang/${name.hashCode()}",
        elixirHome: String = "/fake/elixir/${name.hashCode()}",
    ): ProjectJdkImpl {
        val erlangSdk = register(SdkFixtures.erlangSdk("$name Erlang", erlangHome))
        val elixirSdk = register(SdkFixtures.elixirSdk(name, elixirHome))
        SdkFixtures.commit(elixirSdk, ElixirSdkAdditionalData(erlangSdk, elixirSdk))
        SdksService.getInstance()!!.resetForTests()

        return model().findSdk(name) as ProjectJdkImpl
    }

    private fun countWriteActions(): AtomicInteger =
        AtomicInteger().also { writeActions ->
            ApplicationManager.getApplication().addApplicationListener(
                object : ApplicationListener {
                    override fun beforeWriteActionStart(action: Any) {
                        writeActions.incrementAndGet()
                    }
                },
                testRootDisposable,
            )
        }

    private fun model() = SdksService.getInstance()!!.getModel()

    private fun history() = History(object : Place.Navigator {})

    private fun register(sdk: Sdk): Sdk = SdkFixtures.register(sdk, testRootDisposable)

    @Suppress("ObsoleteDispatchersEdt") // The model access `Dispatchers.UI` refuses is what the block needs.
    private fun <T> onEdt(block: () -> T): T =
        runBlocking { withTimeout(TIMEOUT) { withContext(Dispatchers.EDT) { block() } } }

    /** `Dispatchers.UI` refuses every lock, so [block] fails on the first model access it makes. */
    private fun withoutModelAccess(block: () -> Unit): Throwable? =
        runBlocking { withTimeout(TIMEOUT) { withContext(Dispatchers.UI) { runCatching(block).exceptionOrNull() } } }

    private fun describe(failure: Throwable?): String =
        generateSequence(failure) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }

    private companion object {
        val TIMEOUT = 30.seconds
    }
}
