package org.elixir_lang.declaration

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import org.elixir_lang.settings.ElixirExperimentalSettings
import org.elixir_lang.settings.ElixirExperimentalSettingsListener

/** Drops what this project holds from the old path when the Expander checkbox changes, and has its editors analysed again. */
internal class ExpanderPathListener(private val project: Project) : ElixirExperimentalSettingsListener {
    override fun settingsChanged(oldState: ElixirExperimentalSettings.State, newState: ElixirExperimentalSettings.State) {
        if (oldState.enableExpander == newState.enableExpander) return

        // `dropPsiCaches` needs the EDT or a write action.
        ApplicationManager.getApplication().invokeLater(
            {
                PsiManager.getInstance(project).dropPsiCaches()
                DaemonCodeAnalyzer.getInstance(project).restart("the Expander setting changed")
            },
            ModalityState.nonModal(),
            project.disposed,
        )
    }
}
