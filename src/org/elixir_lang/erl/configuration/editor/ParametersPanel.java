package org.elixir_lang.erl.configuration.editor;

import com.intellij.execution.ui.CommonProgramParametersPanel;
import org.jetbrains.annotations.NotNull;

public class ParametersPanel extends CommonProgramParametersPanel {
    // The replacement constructor, taking the project, first ships in 2026.1.1.
    @SuppressWarnings("deprecation")
    public ParametersPanel() {
        super();
    }

    private String getErlArguments() {
        return getProgramParametersComponent().getComponent().getText();
    }

    private void setErlArguments(@NotNull String text) {
        getProgramParametersComponent().getComponent().setText(text);
    }

    @Override
    protected void addComponents() {
        super.addComponents();
        setProgramParametersLabel("erl arguments:");
    }
}
