package org.elixir_lang.facet

import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.facet.impl.FacetUtil
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.testFramework.common.runAll
import org.elixir_lang.Facet
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkVersionWatchService
import org.elixir_lang.sdk.SdkVersionsStore
import org.elixir_lang.sdk.elixir.ModuleSdkStatus
import org.elixir_lang.sdk.elixir.summaryHtml
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType
import java.awt.Component
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.JLabel
import com.intellij.util.concurrency.annotations.RequiresEdt

/**
 * Tests the Settings -> Elixir per-module SDK panel ([org.elixir_lang.facet.Configurable]): the SDK
 * chooser is populated, the status line under it renders the shared [ModuleSdkStatus] text, and
 * applying a selection writes the module's Facet SDK.
 */
class ModuleSdkConfigurableTest : PlatformTestCase() {

    override fun setUp() {
        super.setUp()
        SdksService.getInstance()!!.resetForTests()
        ensureElixirFacet()
    }

    override fun tearDown() {
        runAll(
            {
                WriteAction.run<Throwable> {
                    FacetManager.getInstance(module).getFacetByType(Facet.ID)?.let { it.sdk = null }
                }
            },
            { SdksService.getInstance()!!.resetForTests() },
            { SdkVersionsStore.getInstance().clearForTests() },
            { super.tearDown() },
        )
    }

    private fun ensureElixirFacet() {
        val facetManager = FacetManager.getInstance(module)
        if (facetManager.getFacetByType(Facet.ID) == null) {
            FacetUtil.addFacet(module, FacetType.findInstance(Type::class.java))
        }
    }

    private fun registerElixirSdk(name: String): Sdk {
        val sdk = SdkFixtures.register(ProjectJdkImpl(name, ElixirSdkType.instance), testRootDisposable)
        SdksService.getInstance()!!.resetForTests()
        return sdk
    }

    private fun setFacetSdk(sdk: Sdk?) {
        WriteAction.run<Throwable> {
            FacetManager.getInstance(module).getFacetByType(Facet.ID)!!.sdk = sdk
        }
    }

    /** Concrete per-module configurable mirroring [org.elixir_lang.facet.configurable.Project]. */
    private fun moduleConfigurable(): Configurable = object : Configurable(module) {
        override fun initSdk(): Sdk? = Facet.sdk(module)
        override fun applySdk(sdk: Sdk?) = setFacetSdk(sdk)
    }

    private fun descendants(root: Component): List<Component> {
        val out = mutableListOf<Component>()
        fun visit(c: Component) {
            out.add(c)
            if (c is Container) c.components.forEach(::visit)
        }
        visit(root)
        return out
    }

    fun testChooserIsPopulated() {
        registerElixirSdk("Elixir Module Test A")

        val component = moduleConfigurable().createComponent()

        val combo = descendants(component).filterIsInstance<JComboBox<*>>().single()
        val names = (0 until combo.itemCount).mapNotNull { (combo.getItemAt(it) as? Sdk)?.name }
        assertTrue("chooser should contain the registered SDK; got $names", "Elixir Module Test A" in names)
    }

    fun testStatusLabelRendersSharedText() {
        val sdk = registerElixirSdk("Elixir Module Test B")
        setFacetSdk(sdk)

        val configurable = moduleConfigurable()
        val component = configurable.createComponent()
        configurable.reset()   // selects the module's Facet SDK

        val expected = "<html>${ModuleSdkStatus.of(sdk).summaryHtml()}</html>"
        // Awaited, not read once: the status is classified off the EDT.
        SdkFixtures.waitUntil(
            "status line should render the shared status text; expected: $expected",
        ) {
            descendants(component).filterIsInstance<JLabel>().any { it.text == expected }
        }
    }

    @RequiresEdt
    fun testPickingAnSdkNoOpenProjectUsesReadsItsInstallation() {
        val homePath = SdkFixtures.elixirHome("1.20.5")
        // Installed here rather than relied on: registering an SDK reads it only while the watch service is
        // listening, and whether it already is depends on what else ran in this JVM first.
        SdkVersionWatchService.install(testRootDisposable)
        val sdk = SdkFixtures.register(
            SdkFixtures.elixirSdk("Elixir Module Test Unread", homePath),
            testRootDisposable,
        )
        SdksService.getInstance()!!.resetForTests()

        val component = moduleConfigurable().createComponent()
        val combo = descendants(component).filterIsInstance<JComboBox<*>>().single()

        // Registering reads the home; that read is discarded to model an SDK loaded from `jdk.table.xml` that nothing
        // has read, so only picking it can satisfy the assertion below.
        assertNotNull(
            "precondition: registering an SDK reads it",
            SdkVersionsStore.getInstance().elixirVersions(homePath),
        )
        SdkVersionsStore.getInstance().clearForTests()

        combo.selectedItem = (0 until combo.itemCount)
            .mapNotNull { combo.getItemAt(it) as? Sdk }
            .single { it.name == sdk.name }

        SdkFixtures.waitUntil("picking an SDK should read the installation it points at, not report it invalid") {
            SdkVersionsStore.getInstance().elixirVersions(homePath)?.elixirVersion == "1.20.5"
        }
    }

    fun testDisposeUIResourcesDetachesSdkChooser() {
        val configurable = moduleConfigurable()
        val component = configurable.createComponent()
        val combo = descendants(component).filterIsInstance<JComboBox<*>>().single()

        // Sanity: while the panel is live, the chooser tracks additions to the shared model.
        val before = combo.itemCount
        SdksService.getInstance()!!.getModel().addSdk(ProjectJdkImpl("Elixir Detach A", ElixirSdkType.instance))
        assertEquals(before + 1, combo.itemCount)

        configurable.disposeUIResources()

        // After disposal the chooser's Model must be detached from the shared, app-lifetime
        // ProjectSdksModel - otherwise one listener (pinning the combo) accumulates per module
        // per Settings open.
        SdksService.getInstance()!!.getModel().addSdk(ProjectJdkImpl("Elixir Detach B", ElixirSdkType.instance))
        assertEquals(
            "chooser must not receive updates after disposeUIResources",
            before + 1,
            combo.itemCount,
        )
    }

    /**
     * Unless `disposeUIResources` drops `rootPanel`, `createComponent` returns the cached panel, whose status updates
     * launch on the cancelled scope and never land.
     */
    fun testAPanelRebuiltAfterDisposalStillUpdatesItsStatusLine() {
        val sdk = registerElixirSdk("Elixir Module Test Rebuilt")
        setFacetSdk(sdk)
        val configurable = moduleConfigurable()
        configurable.createComponent()
        configurable.disposeUIResources()

        val rebuilt = configurable.createComponent()
        configurable.reset()

        val expected = "<html>${ModuleSdkStatus.of(sdk).summaryHtml()}</html>"
        SdkFixtures.waitUntil(
            "a panel rebuilt after disposal must still render its status; expected: $expected",
        ) {
            descendants(rebuilt).filterIsInstance<JLabel>().any { it.text == expected }
        }
    }

    fun testApplyWritesFacetSdk() {
        registerElixirSdk("Elixir Module Test C")

        val configurable = moduleConfigurable()
        val component = configurable.createComponent()

        val combo = descendants(component).filterIsInstance<JComboBox<*>>().single()
        combo.selectedItem = SdksService.getInstance()!!.getModel().findSdk("Elixir Module Test C")
        configurable.apply()

        assertEquals("Elixir Module Test C", Facet.sdk(module)?.name)
    }
}
