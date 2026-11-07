package org.elixir_lang.module

import com.intellij.facet.FacetManager
import com.intellij.facet.FacetModel
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.util.projectWizard.importSources.DetectedProjectRoot
import com.intellij.ide.util.projectWizard.importSources.ProjectStructureDetector
import com.intellij.ide.util.projectWizard.importSources.impl.ProjectFromSourcesBuilderImpl
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModifiableModuleModel
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModel
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ui.configuration.DefaultModulesProvider
import com.intellij.openapi.roots.ui.configuration.ModulesProvider
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.common.runAll
import com.intellij.util.containers.MultiMap
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.sdk.SdkFixtures
import org.elixir_lang.sdk.choice.FOLDER_OFFER
import org.elixir_lang.sdk.choice.FakeToolManager
import org.elixir_lang.sdk.choice.SdkChoiceStep
import org.elixir_lang.sdk.choice.pairedForNext
import org.elixir_lang.sdk.choice.pickAndNext
import org.elixir_lang.sdk.choice.settingsChanges
import org.elixir_lang.sdk.choice.takeTheOfferInStep
import org.elixir_lang.sdk.choice.waitFor
import org.elixir_lang.sdk.erlang_dependent.elixirInstall
import org.elixir_lang.sdk.erlang_dependent.install
import org.elixir_lang.tool_manager.ToolManagerSettings
import org.elixir_lang.tool_manager.elixirSdksAt
import java.io.File

/** "Create project from existing sources": the Elixir detector's step, and what committing the sources gives its modules. */
class ElixirProjectStructureDetectorStepTest : HeavyTestCase() {
    private lateinit var table: SdkFixtures.TableSnapshot
    private val toolManager = FakeToolManager()
    private val detector by lazy { ProjectStructureDetector.EP_NAME.findExtensionOrFail(ElixirProjectStructureDetector::class.java) }
    private lateinit var sources: File

    override fun setUp() {
        super.setUp()
        table = SdkFixtures.TableSnapshot()
        toolManager.install(testRootDisposable)
        sources = FileUtil.createTempDirectory("sources", null).also { File(it, "hello.ex").writeText("defmodule Hello do\nend\n") }
    }

    override fun tearDown() = runAll(
        { table.restore() },
        { super.tearDown() },
    )

    fun testThePickIsTheModuleSdkOfTheDetectorsModulesOnly() {
        val elixir = pairedForNext("Module Elixir", "Module Erlang", testRootDisposable)
        val builder = builder(project, DefaultModulesProvider.createForProject(project))
        step(builder).pickAndNext(elixir.name)

        val other = otherElixirModule()
        val projectSdk = ProjectRootManager.getInstance(project).projectSdk

        val detected = builder.commit(project, null, null).single()

        assertSame(elixir, ModuleRootManager.getInstance(detected).sdk)
        assertTrue("the other Elixir module still inherits", ModuleRootManager.getInstance(other).isSdkInherited)
        assertSame(projectSdk, ProjectRootManager.getInstance(project).projectSdk)
    }

    fun testInANewProjectTickingTheFirstBoxTurnsTheToolManagerOnOnceAfterTheModuleSdk() {
        val elixirHome = elixirInstall("1.18.4", "27")
        toolManager.pins(elixirHome, install("27", "27.3.4"))
        val builder = builder(null, ModulesProvider.EMPTY_MODULES_PROVIDER)
        step(builder).takeTheOfferInStep()
        val heard = settingsChanges(project, testRootDisposable) { detectedModules().map { ModuleRootManager.getInstance(it).sdk } }

        builder.commit(project, null, null)

        assertEquals(listOf(elixirSdksAt(elixirHome)), heard)
        assertTrue(ToolManagerSettings.getInstance(project).isEnabled(toolManager))
    }

    fun testOnImportModuleTheOfferIsForTheFolderChosen() {
        toolManager.pinsAnotherIn(sources.toPath())
        // Import Module's wizard works in the open project's folder, and hands the folder chosen to the builder.
        val open = FileUtil.createTempDirectory("open", null).path
        val step = step(builder(project, DefaultModulesProvider.createForProject(project), open))

        step.updateStep()

        step.component.waitFor(FOLDER_OFFER)
    }

    /** The import of [sources] in a wizard for [project] under [modulesProvider], working in [directory], with the detector's root found there. */
    private fun builder(project: Project?, modulesProvider: ModulesProvider, directory: String = sources.path): ProjectFromSourcesBuilderImpl {
        val context = WizardContext(project, Disposer.newDisposable(testRootDisposable))
        context.setProjectFileDirectory(directory)
        return ProjectFromSourcesBuilderImpl(context, modulesProvider).apply {
            baseProjectPath = sources.path
            setupProjectStructure(MultiMap<ProjectStructureDetector, DetectedProjectRoot>().apply {
                putValue(detector, object : DetectedProjectRoot(sources) {
                    override fun getRootTypeName() = "Elixir"
                })
            })
        }
    }

    private fun step(builder: ProjectFromSourcesBuilderImpl): SdkChoiceStep =
        detector.createWizardSteps(builder, builder.getProjectDescriptor(detector), null).single() as SdkChoiceStep

    /** An Elixir module named `other` already in the project, over its own directory, inheriting the project SDK. */
    private fun otherElixirModule(): Module {
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(FileUtil.createTempDirectory("other", null))!!
        return createModuleAt("other", project, ElixirModuleType.getInstance(), root.toNioPath()).also { module ->
            ModuleRootModificationUtil.updateModel(module) { it.addContentEntry(root); it.inheritSdk() }
        }
    }

    /** The modules other than the test's own. */
    private fun detectedModules(): List<Module> = ModuleManager.getInstance(project).modules.filter { it !== module }
}
