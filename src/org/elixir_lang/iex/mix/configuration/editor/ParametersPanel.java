package org.elixir_lang.iex.mix.configuration.editor;

import com.intellij.execution.ui.CommonProgramParametersPanel;
import com.intellij.openapi.ui.LabeledComponent;
import com.intellij.ui.RawCommandLineEditor;
import com.intellij.util.ui.UIUtil;
import org.elixir_lang.iex.mix.Configuration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class ParametersPanel extends CommonProgramParametersPanel {
    private LabeledComponent<RawCommandLineEditor> iexArgumentsComponent;
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

    private String getIExArguments() {
        return iexArgumentsComponent.getComponent().getText();
    }

    private void setIExArguments(@Nullable String text) {
        iexArgumentsComponent.getComponent().setText(text);
    }

    public void applyTo(@NotNull Configuration configuration) {
        super.applyTo(configuration);
        configuration.setErlArguments(getErlArguments());
        configuration.setIExArguments(getIExArguments());
    }

    public void reset(@NotNull Configuration configuration) {
        super.reset(configuration);
        setErlArguments(configuration.getErlArguments());
        setIExArguments(configuration.getIExArguments());
    }

    @Override
    public void setAnchor(@Nullable JComponent labelAnchor) {
        super.setAnchor(labelAnchor);
        erlArgumentsComponent.setAnchor(labelAnchor);
        iexArgumentsComponent.setAnchor(labelAnchor);
    }

    @Override
    protected void setupAnchor() {
        super.setupAnchor();
        myAnchor = UIUtil.mergeComponentsWithAnchor(this, erlArgumentsComponent, iexArgumentsComponent);
    }

    @Override
    protected void addComponents() {
        super.addComponents();
        setProgramParametersLabel("mix arguments:");
        addIExArgumentsComponent();
        addErlArgumentsComponent();
    }

    // See CommonJavaParametersPanel's addComponents
    private void addIExArgumentsComponent() {
        iexArgumentsComponent = createArgumentsComponent("iex");
        // after myProgramParametersComponent, which can't be addressed directly because it is private in CommonProgramParameters Panel
        addArgumentsComponent(iexArgumentsComponent, 1);
    }

    // See CommonJavaParametersPanel's addComponents
    private void addErlArgumentsComponent() {
        erlArgumentsComponent = createArgumentsComponent("erl");
        addArgumentsComponent(erlArgumentsComponent, 2);
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
