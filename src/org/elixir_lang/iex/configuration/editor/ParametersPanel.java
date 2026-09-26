package org.elixir_lang.iex.configuration.editor;

import com.intellij.execution.ui.CommonProgramParametersPanel;
import com.intellij.openapi.ui.LabeledComponent;
import com.intellij.ui.RawCommandLineEditor;
import com.intellij.util.ui.UIUtil;
import org.elixir_lang.iex.Configuration;
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
        setProgramParametersLabel("iex arguments:");
        addErlArgumentsComponent();
    }

    // See CommonJavaParametersPanel's addComponents
    private void addErlArgumentsComponent() {
        // `erl` is the executable's name.
        //noinspection DialogTitleCapitalization
        erlArgumentsComponent = LabeledComponent.create(new RawCommandLineEditor(), "erl arguments:");
        copyDialogCaption(erlArgumentsComponent);
        erlArgumentsComponent.setLabelLocation(BorderLayout.WEST);
        add(erlArgumentsComponent, 1);
    }
}
