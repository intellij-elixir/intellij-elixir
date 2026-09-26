package org.elixir_lang.elixir.configuration.editor;

import com.intellij.execution.ui.CommonProgramParametersPanel;
import com.intellij.openapi.ui.LabeledComponent;
import com.intellij.ui.RawCommandLineEditor;
import com.intellij.util.ui.UIUtil;
import org.elixir_lang.elixir.Configuration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class ParametersPanel extends CommonProgramParametersPanel {
    private LabeledComponent<RawCommandLineEditor> erlArgumentsComponent;

    // The replacement constructor, taking the project, first ships in 2026.1.1.
    @SuppressWarnings("deprecation")
    public ParametersPanel() {
        super();
    }

    private String getErlArguments() {
        return erlArgumentsComponent.getComponent().getText();
    }

    private void setErlArguments(@Nullable String text) {
        erlArgumentsComponent.getComponent().setText(text);
    }

    public void applyTo(@NotNull Configuration configuration) {
        super.applyTo(configuration);
        configuration.setErlArguments(getErlArguments());
    }

    public void reset(@NotNull Configuration configuration) {
        super.reset(configuration);
        setErlArguments(configuration.getErlArguments());
    }

    @Override
    public void setAnchor(@Nullable JComponent labelAnchor) {
        super.setAnchor(labelAnchor);
        erlArgumentsComponent.setAnchor(labelAnchor);
    }

    @Override
    protected void setupAnchor() {
        super.setupAnchor();
        myAnchor = UIUtil.mergeComponentsWithAnchor(this, erlArgumentsComponent);
    }

    @Override
    protected void addComponents() {
        super.addComponents();
        setProgramParametersLabel("elixir arguments:");
        addErlArgumentsComponent();
    }

    // See CommonJavaParametersPanel's addComponents
    private void addErlArgumentsComponent() {
        erlArgumentsComponent = createArgumentsComponent("erl");
        addArgumentsComponent(erlArgumentsComponent, 1);
    }

    private LabeledComponent<RawCommandLineEditor> createArgumentsComponent(@NotNull String command) {
        return LabeledComponent.create(new RawCommandLineEditor(), command + " arguments:");
    }

    private void addArgumentsComponent(@NotNull LabeledComponent<RawCommandLineEditor> argumentsComponent, int index) {
        copyDialogCaption(argumentsComponent);
        argumentsComponent.setLabelLocation(BorderLayout.WEST);
        add(argumentsComponent, index);
    }
}
