package org.elixir_lang.mix.project._import

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModifiableModuleModel
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ui.configuration.DefaultModulesProvider
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.VfsTestUtil
import com.intellij.testFramework.common.runAll
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.mix.sync.MixSyncTestHelpers
import org.elixir_lang.mix.project.OtpApp
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.SdkFixtures.fakeHome
import org.elixir_lang.sdk.choice.FakeToolManager
import org.elixir_lang.sdk.choice.mixImportStep
import org.elixir_lang.sdk.choice.settingsChanges
import org.elixir_lang.sdk.choice.takeTheOfferInStep
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install
import org.elixir_lang.tool_manager.ToolManagerOptIns
import org.elixir_lang.tool_manager.ToolManagerSettings
import org.elixir_lang.tool_manager.elixirSdksAt

/** What committing a Mix import gives the apps it imports, with the SDK and opt-ins its step handed `Builder`. */
class BuilderCommitTest : HeavyTestCase() {
    private lateinit var table: SdkFixtures.TableSnapshot
    private val toolManager = FakeToolManager()
    /** The import's `Builder`, which `Provider` hands its steps. */
    private val builder = Builder()
    private lateinit var umbrella: VirtualFile
    private val reused = mutableSetOf<String>()
    private var moduleNames = emptyMap<String, String>()

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        toolManager.install(testRootDisposable)
        umbrella = tempDir.createVirtualDir("umbrella")
    }

    override fun tearDown() = runAll(
        { MixSyncTestHelpers.awaitMixDepsSyncs(project) },
        { MixSyncTestHelpers.removeAllLibraries(project) },
        { table.restore() },
        { super.tearDown() },
    )

    fun testEachAppGetsThePick() = assertEachAppGetsThePick(app("alpha"), app("beta"))

    fun testAHandedModelWithNewAppsIsLeftForItsCallerAndEachAppGetsThePick() =
        assertAHandedModelIsLeftForItsCallerThenEachAppGetsThePick(app("alpha"), app("beta"))

    fun testAReusedAppAmongNewOnesGetsThePick() = assertEachAppGetsThePick(app("alpha", reused = true), app("beta"))

    fun testWhenEveryAppIsReusedEachGetsThePick() =
        assertEachAppGetsThePick(app("alpha", reused = true), app("beta", reused = true))

    fun testOnlyReusedAppsInAHandedModelAreLeftForItsCaller() =
        assertAHandedModelIsLeftForItsCallerThenEachAppGetsThePick(app("alpha", reused = true), app("beta", reused = true))

    fun testTheAppsDependenciesBecomeTheirLibraries() {
        importing(appUsingPhoenix("alpha"))
        builder.elixirSdk = elixirSdk()

        commit()

        assertPhoenixIsALibraryOf("alpha")
    }

    fun testOnceAHandedModelCommitsTheAppsDependenciesBecomeTheirLibraries() {
        importing(appUsingPhoenix("alpha"))
        builder.elixirSdk = elixirSdk()
        val model = ModuleManager.getInstance(project).getModifiableModel()
        commit(model)

        WriteAction.run<Throwable> { model.commit() }

        assertPhoenixIsALibraryOf("alpha")
    }

    fun testTheOptInsAreWrittenOnceAfterThePick() {
        val sdk = elixirSdk()
        importing(app("alpha"))
        builder.elixirSdk = sdk
        builder.toolManagerOptIns = ToolManagerOptIns(toolManager.name, false)
        val heard = settingsChanges(project, testRootDisposable) { moduleSdk("alpha") }

        commit()

        assertEquals(listOf(sdk), heard)
        assertTrue(ToolManagerSettings.getInstance(project).isEnabled(toolManager))
    }

    fun testInANewProjectTickingTheStepsFirstBoxTurnsTheToolManagerOn() {
        val elixirHome = elixirInstall("1.18.4", "27")
        toolManager.pins(elixirHome, install("27", "27.3.4"))
        importing(app("alpha"))
        val context = WizardContext(null, Disposer.newDisposable(testRootDisposable)).apply {
            setProjectFileDirectory(umbrella.path)
            projectBuilder = builder
        }
        mixImportStep(context, builder).takeTheOfferInStep()

        commit()

        assertEquals(elixirSdksAt(elixirHome), listOfNotNull(moduleSdk("alpha")))
        assertTrue(ToolManagerSettings.getInstance(project).isEnabled(toolManager))
    }

    fun testWithNoOptInsTheToolManagerSettingsAreLeftAlone() {
        importing(app("alpha"))
        builder.elixirSdk = elixirSdk()
        val heard = settingsChanges(project, testRootDisposable) {}

        commit()

        assertEquals(0, heard.size)
        assertFalse(ToolManagerSettings.getInstance(project).isEnabled(toolManager))
    }

    fun testWithOptInsAndNoPickNoOptInIsWritten() {
        importing(app("alpha"))
        builder.toolManagerOptIns = ToolManagerOptIns(toolManager.name, true)
        val heard = settingsChanges(project, testRootDisposable) {}

        commit()

        assertEquals(0, heard.size)
        assertFalse(ToolManagerSettings.getInstance(project).isEnabled(toolManager))
    }

    /** Committing an import of [names] with no module model gives each app, as `commit` returns it, the pick. */
    private fun assertEachAppGetsThePick(vararg names: String) {
        val sdk = elixirSdk()
        importing(*names)
        builder.elixirSdk = sdk

        val modules = commit()

        assertEquals(names.map { sdk }, modules.map { ModuleRootManager.getInstance(it).sdk })
    }

    /** Committing an import of [names] into a handed model leaves it uncommitted, and once it commits each app has the pick. */
    private fun assertAHandedModelIsLeftForItsCallerThenEachAppGetsThePick(vararg names: String) {
        val sdk = elixirSdk()
        importing(*names)
        builder.elixirSdk = sdk
        val model = ModuleManager.getInstance(project).getModifiableModel()

        commit(model)

        assertTrue("the handed model is left uncommitted", model.isChanged)
        assertNull(ModuleManager.getInstance(project).findModuleByName(moduleName(names.first())))
        WriteAction.run<Throwable> { model.commit() }
        assertEquals(names.map { sdk }, names.map { moduleSdk(it) })
    }

    private fun elixirSdk(): Sdk = SdkFixtures.register(SdkFixtures.elixirSdk("Import Elixir", fakeHome("elixir/import")), testRootDisposable)

    /** The app [name] under the umbrella's `apps/`, declaring `phoenix`, which it has fetched and built. */
    private fun appUsingPhoenix(name: String): String {
        mixAppUsingPhoenix(project, VfsTestUtil.createDir(umbrella, "apps/$name"), name)
        return name
    }

    private fun assertPhoenixIsALibraryOf(name: String) = assertPhoenixIsALibraryOf(
        project,
        ModuleManager.getInstance(project).findModuleByName(moduleName(name))!!,
        umbrella.findFileByRelativePath("apps/$name")!!,
    )

    /** The app [name] under the umbrella's `apps/`, whose module file the import finds and loads when [reused]. */
    private fun app(name: String, reused: Boolean = false): String {
        val root = VfsTestUtil.createDir(umbrella, "apps/$name")
        mixExs(root, name)
        if (reused) this.reused += name
        return name
    }

    /** The apps [names] found under the umbrella and all selected, each [app] made reused with its module file. */
    private fun importing(vararg names: String) {
        val apps = names.map { name ->
            val root = umbrella.findFileByRelativePath("apps/$name")!!
            OtpApp(root, root.findChild("mix.exs")!!)
        }
        moduleNames = org.elixir_lang.mix.Project.moduleNameForOtpApps(apps, umbrella).mapKeys { it.key.name }
        for (app in apps.filter { it.name in reused }) {
            app.ideaModuleFile = VfsTestUtil.createFile(
                app.root,
                "${moduleName(app.name)}.iml",
                $$"""<?xml version="1.0" encoding="UTF-8"?>
                  |<module type="ELIXIR_MODULE" version="4">
                  |  <component name="NewModuleRootManager"><content url="file://$MODULE_DIR$" /></component>
                  |</module>
                  |""".trimMargin(),
            )
        }
        builder.setPreScannedApps(umbrella, apps)
    }

    /** The name the import gives app [name]'s module. */
    private fun moduleName(name: String) = moduleNames.getValue(name)

    private fun commit(model: ModifiableModuleModel? = null): List<Module> =
        builder.commit(project, model, DefaultModulesProvider.createForProject(project), null)

    private fun moduleSdk(name: String): Sdk? =
        ModuleManager.getInstance(project).findModuleByName(moduleName(name))?.let { ModuleRootManager.getInstance(it).sdk }
}
