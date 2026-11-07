package org.elixir_lang.sdk.choice

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.roots.ui.configuration.DefaultModulesProvider

/**
 * Whether the wizard of [context] commits the chooser's pick itself at OK, so the chooser's tool manager boxes can hand
 * over opt-ins: New Project makes its project, and File > New > Module its module.
 */
fun takesOptIns(context: WizardContext): Boolean =
    context.isCreatingNewProject || context.modulesProvider is DefaultModulesProvider
