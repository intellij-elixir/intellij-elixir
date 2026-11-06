package org.elixir_lang.sdk.choice

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbAwareAction
import org.elixir_lang.isElixirModule

/** Asks the chooser for the context's module when it is an Elixir module, else for every Elixir module. */
class ChooseElixirSdkAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = modules(e).any()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val modules = modules(e).toList().ifEmpty { return }
        SdkChoice.getInstance().ask(project, modules, e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT))
    }

    private fun modules(e: AnActionEvent): Sequence<Module> {
        val project = e.project ?: return emptySequence()
        e.getData(PlatformCoreDataKeys.MODULE)?.takeIf { it.isElixirModule() }?.let { return sequenceOf(it) }
        return ModuleManager.getInstance(project).modules.asSequence().filter { it.isElixirModule() }
    }
}
