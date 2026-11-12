package org.elixir_lang.facet.sdk

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.UI
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.ex.ConfigurableCardPanel
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.common.runAll
import com.intellij.testFramework.registerOrReplaceServiceInstance
import com.intellij.ui.navigation.History
import com.intellij.ui.navigation.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.facet.FacetModelFixtures
import org.elixir_lang.facet.SdksService
import org.elixir_lang.facet.sdks.Configurable
import org.elixir_lang.facet.sdks.SdksPage
import org.elixir_lang.sdk.ProcessOutput
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.erlang_dependent.getErlangSdk
import org.elixir_lang.sdk.wsl.MockWslCompatService
import org.elixir_lang.sdk.wsl.WslCompatService
import java.io.File
import org.elixir_lang.facet.sdks.elixir.Configurable as ElixirSdksConfigurable
import org.elixir_lang.facet.sdks.erlang.Configurable as ErlangSdksConfigurable
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

    override fun tearDown() = runAll(
        { onEdt { pages.toList().forEach(SdksPage::cancel) } },
        { onEdt { built.forEach(Editor::disposeUIResources) } },
        { ProcessOutput.isSmallIdeOverride = null },
        { SdksService.getInstance()!!.resetForTests() },
        { super.tearDown() },
    )

    private val pages = mutableListOf<SdksPage>()

    /** Disposed at teardown: an Elixir SDK editor's model stays registered for pairing repairs until it is. */
    private val built = mutableListOf<Editor>()

    private fun editor(sdk: ProjectJdkImpl): Editor = Editor(model(), history(), sdk).also(built::add)

    fun testSelectingAnSdkBuildsItsEditorWithoutALock() {
        val sdk = onEdt { pairedElixirSdkCopy("Editor Built Unlocked") }

        val failure = withoutModelAccess { editor(sdk) }

        assertNull("building the editor must not need a lock; failed with ${describe(failure)}", failure)
    }

    fun testTheCardPanelResetsTheEditorWithoutAWriteAction() {
        val parent = FileUtil.createTempDirectory("editor-erlang-homes", null, true)
        val erlangHome = File(parent, "erlang")
        onEdt { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(parent)!!.children }
        val editor = onEdt {
            editor(pairedElixirSdkCopy("Editor Reset In Read Action", erlangHome.path))
        }
        // Created after the VFS listed its parent, so the VFS holds it as absent: a refreshing lookup of it fires an event.
        assertTrue(erlangHome.mkdir())
        val component = onEdt { withoutWriteActions { ConfigurableCardPanel.createConfigurableComponent(editor) } }

        assertNotNull(component)
    }

    fun testTheEditorTheCardPanelShowsIsUnmodified() {
        val editor = onEdt { editor(pairedElixirSdkCopy("Editor Shown Unmodified")) }

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
        val editor = onEdt { editor(sdk).also { ConfigurableCardPanel.createConfigurableComponent(it) } }
        val name = sdk.name
        onEdt { editor.doSetHomePath(homeB, sdkType) }
        assertTrue("browsing to a new home modifies the editor", onEdt { editor.isModified })

        onEdt { editor.apply() }

        assertEquals(FileUtil.toSystemIndependentName(homeB), FileUtil.toSystemIndependentName(sdk.homePath!!))
        val roots = sdk.rootProvider.getUrls(OrderRootType.CLASSES).map(VfsUtilCore::urlToPath)
        assertTrue("a CLASSES root lies under the new home: $roots", roots.any { FileUtil.isAncestor(homeB, it, false) })
        assertFalse("no CLASSES root lies under the old home: $roots", roots.any { FileUtil.isAncestor(homeA, it, false) })
        assertEquals("browsing renames nothing", name, sdk.name)
        val versionB = onEdt { sdkType.getVersionString(homeB) }
        assertFalse("the two homes' versions must differ", versionB == onEdt { sdkType.getVersionString(homeA) })
        assertEquals("the version is the new home's", versionB, sdk.versionString)
    }

    fun testRenamingAnErlangSdkReachesItsElixirSdksAndTheFacet() {
        val (erlang, elixir) = onEdt { pairedInTable("Elixir Of A Renamed Erlang") }
        FacetModelFixtures.nameFacet(module, elixir.name)

        renameOnAPage(ErlangSdksConfigurable(), erlang, "Renamed Erlang")

        assertEquals("the table's Erlang SDK", "Renamed Erlang", erlang.name)
        assertEquals("its Elixir SDK's pairing", "Renamed Erlang", SdkFixtures.storedErlangName(elixir))
        assertSame("the facet's Elixir SDK finds it", erlang, onEdt { FacetModelFixtures.facet(module).sdk?.getErlangSdk() })
    }

    fun testRenamingAnElixirSdkReachesTheFacet() {
        val (_, elixir) = onEdt { pairedInTable("Elixir Before Its Rename") }
        FacetModelFixtures.nameFacet(module, elixir.name)

        renameOnAPage(ElixirSdksConfigurable(), elixir, "Elixir After Its Rename")

        assertEquals("the table's Elixir SDK", "Elixir After Its Rename", elixir.name)
        assertEquals("the facet stores the new name", "Elixir After Its Rename", FacetModelFixtures.storedName(module))
        assertSame("the facet supplies it", elixir, onEdt { FacetModelFixtures.facet(module).sdk })
    }

    /** Opens [configurable] as a page, types [name] into [sdk]'s Name field and applies. */
    private fun renameOnAPage(configurable: Configurable, sdk: Sdk, name: String) = onEdt {
        val page = SdksPage(configurable, pages)
        page.rename(sdk, name)
        page.apply()
        SdkFixtures.settle()
    }

    fun testARefusedDuplicateNameLeavesThePageModifiedAndCancelRestoresIt() =
        assertARefusedNameIsCancelled("Duplicate Name") { taken -> taken }

    fun testARefusedEmptyNameLeavesThePageModifiedAndCancelRestoresIt() =
        assertARefusedNameIsCancelled("Empty Name") { "" }

    /**
     * Renames the Erlang SDK of a pair named [name] on one page to [refusedName] of another Erlang SDK's name and
     * applies, which is refused; then cancels that page and applies a second.
     */
    private fun assertARefusedNameIsCancelled(name: String, refusedName: (taken: String) -> String) = onEdt {
        val (erlang, elixir) = pairedInTable(name)
        val other = register(SdkFixtures.erlangSdk("$name Other Erlang", SdkFixtures.erlangHomeWithEbin("26", "26.2.5")))
        val erlangName = erlang.name
        val refused = SdksPage(ErlangSdksConfigurable(), pages)
        refused.rename(erlang, refusedName(other.name))

        assertThrows(ConfigurationException::class.java) { refused.apply() }

        assertTrue("the refused page keeps its edit", refused.isModified)
        assertEquals("the table's Erlang SDK", erlangName, erlang.name)
        assertEquals("the other Erlang SDK", "$name Other Erlang", other.name)
        assertEquals("the table's pairing", erlangName, SdkFixtures.storedErlangName(elixir))

        refused.cancel()
        SdkFixtures.settle()
        SdksPage(ErlangSdksConfigurable(), pages).apply()
        SdkFixtures.settle()

        assertEquals("the clone has the table's name", erlangName, SdksPage.copy(erlang).name)
        assertEquals("the table's Erlang SDK after the second Apply", erlangName, erlang.name)
        assertEquals("the table's pairing after the second Apply", erlangName, SdkFixtures.storedErlangName(elixir))
        assertEquals("the Elixir clone's pairing", erlangName, SdkFixtures.storedErlangName(SdksPage.copy(elixir)))
    }

    /** [SdkFixtures.pairedInTable], then the pages' model built afresh. */
    private fun pairedInTable(name: String): Pair<Sdk, Sdk> =
        SdkFixtures.pairedInTable(name, add = ::register).also { SdksService.getInstance()!!.resetForTests() }

    private fun pairedElixirSdkCopy(
        name: String,
        erlangHome: String = "/fake/erlang/${name.hashCode()}",
        elixirHome: String = "/fake/elixir/${name.hashCode()}",
    ): ProjectJdkImpl {
        val erlangSdk = register(SdkFixtures.erlangSdk("$name Erlang", erlangHome))
        val elixirSdk = register(SdkFixtures.elixirSdk(name, elixirHome))
        SdkFixtures.commitPairing(elixirSdk, erlangSdk)
        SdksService.getInstance()!!.resetForTests()

        return model().findSdk(name) as ProjectJdkImpl
    }

    /**
     * [block], with any write action it starts on this thread thrown: the card panel resets inside a read action, where
     * one throws in production, but the test EDT's write-intent lock lets it through.
     */
    @Suppress("JetBrainsInternalApiUsage")
    private fun <T> withoutWriteActions(block: () -> T): T {
        val allow = IdeEventQueue.getInstance().threadingSupport.prohibitWriteActionsInside()
        // The EDT is shared with every later test.
        try {
            return block()
        } finally {
            allow()
        }
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
