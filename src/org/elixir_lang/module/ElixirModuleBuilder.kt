package org.elixir_lang.module

import com.intellij.compiler.CompilerWorkspaceConfiguration
import com.intellij.ide.util.projectWizard.*
import com.intellij.openapi.module.ModifiableModuleModel
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkTypeId
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.ui.configuration.ModulesProvider
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.elixir_lang.Icons
import org.elixir_lang.mix.Project
import org.elixir_lang.sdk.choice.TakesSdkPick
import org.elixir_lang.sdk.elixir.Type.Companion.instance
import org.elixir_lang.tool_manager.ToolManagerOptIns
import javax.swing.Icon
import javax.swing.JComponent

/**
 * Created by zyuyou on 2015/5/26.
 *
 */
class ElixirModuleBuilder : JavaModuleBuilder(), ModuleBuilderListener, TakesSdkPick {
    override var elixirSdk: Sdk? = null
    override var toolManagerOptIns: ToolManagerOptIns? = null

    @RequiresEdt
    override fun commit(project: com.intellij.openapi.project.Project, model: ModifiableModuleModel?, modulesProvider: ModulesProvider?): List<Module>? {
        val modules = super.commit(project, model, modulesProvider) ?: return null
        writePickTo(project, modules)
        return modules
    }

    @kotlin.jvm.Throws(ConfigurationException::class)
    override fun setupRootModel(rootModel: ModifiableRootModel) {
        addListener(this)
        super.setupRootModel(rootModel)
        val contentEntry = rootModel.contentEntries.single()
        val projectDirectory = contentEntry.file!!

        rootModel.removeContentEntry(contentEntry)
        Project.addFolders(rootModel, projectDirectory)
    }

    override fun getModuleType(): ModuleType<*> = ElixirModuleType.getInstance()
    override fun isSuitableSdkType(sdkType: SdkTypeId): Boolean = sdkType === instance
    override fun getNodeIcon(): Icon = Icons.LANGUAGE

    override fun moduleCreated(module: Module) {
        CompilerWorkspaceConfiguration.getInstance(module.project).CLEAR_OUTPUT_DIRECTORY = false
    }

    /** No SDK row: the module type's [org.elixir_lang.sdk.choice.SdkChoiceStep], shown before this step, asks for it. */
    override fun modifySettingsStep(settingsStep: SettingsStep): ModuleWizardStep = object : ModuleWizardStep() {
        override fun getComponent(): JComponent? = null

        override fun updateDataModel() {
            sourcePaths = emptyList()
        }
    }
}
