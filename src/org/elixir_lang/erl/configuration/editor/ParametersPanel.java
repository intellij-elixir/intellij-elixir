package org.elixir_lang.erl.configuration.editor;

import com.intellij.execution.ui.CommonProgramParametersPanel;

public class ParametersPanel extends CommonProgramParametersPanel {
    // The replacement constructor, taking the project, first ships in 2026.1.1.
    @SuppressWarnings("deprecation")
    public ParametersPanel() {
        super();
    }

    @Override
    protected void addComponents() {
        super.addComponents();
        setProgramParametersLabel("erl arguments:");
    }
}
