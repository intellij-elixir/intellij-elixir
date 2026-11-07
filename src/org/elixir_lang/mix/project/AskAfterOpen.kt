package org.elixir_lang.mix.project

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import org.elixir_lang.isElixirModule
import org.elixir_lang.sdk.choice.SdkChoice
import org.elixir_lang.sdk.elixir.ElixirSdkLookup
import org.elixir_lang.sdk.elixir.sdk

/**
 * Once [project] is smart and no modal dialog is up, asks the chooser once for the Elixir modules that have no Elixir
 * SDK. Cancel writes nothing and leaves the editor banner to ask.
 */
internal fun askAfterOpen(project: Project) {
    DumbService.getInstance(project).smartInvokeLater({
        val modules = ReadAction.computeBlocking<List<Module>, Throwable> {
            ModuleManager.getInstance(project).modules.filter { it.isElixirModule() && ElixirSdkLookup.resolve(it).sdk == null }
        }
        if (modules.isNotEmpty()) {
            SdkChoice.getInstance().ask(project, modules)
        }
    }, ModalityState.nonModal())
}
