package org.elixir_lang.distillery.configuration.editor;

import com.intellij.execution.ui.CommonProgramParametersPanel;
import com.intellij.execution.ui.MacroComboBoxWithBrowseButton;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.LabeledComponent;
import com.intellij.ui.CollectionComboBoxModel;
import com.intellij.ui.RawCommandLineEditor;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.util.ui.UIUtil;
import org.elixir_lang.distillery.Configuration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;

public class ParametersPanel extends CommonProgramParametersPanel {
    private LabeledComponent<MacroComboBoxWithBrowseButton> releaseCLIPathComponent;
    private LabeledComponent<RawCommandLineEditor> erlArgumentsComponent;
    private LabeledComponent<RawCommandLineEditor> extraArgumentsComponent;
    private LabeledComponent<ComboBox<CodeLoadingMode>> codeLoadingModeComponent;
    private LabeledComponent<MacroComboBoxWithBrowseButton> logDirectoryComponent;
    private LabeledComponent<ComboBox<Boolean>> replaceOSVarsComponent;
    private LabeledComponent<MacroComboBoxWithBrowseButton> sysConfigPathComponent;
    private LabeledComponent<MacroComboBoxWithBrowseButton> releaseConfigDirectoryComponent;
    private LabeledComponent<MacroComboBoxWithBrowseButton> pipeDirectoryComponent;
    private LabeledComponent<JCheckBox> wantsPTYComponent;
    private static final java.util.List<CodeLoadingMode> CODE_LOADING_MODE_LIST = Arrays.asList(null, CodeLoadingMode.EMBEDDED, CodeLoadingMode.INTERACTIVE);
    private static final java.util.List<Boolean> REPLACE_OS_VARS_LIST = Arrays.asList(null, false, true);

    // The replacement constructor, taking the project, first ships in 2026.1.1.
    @SuppressWarnings("deprecation")
    public ParametersPanel() {
        super();
    }

    @NotNull
    private String getErlArguments() {
        return erlArgumentsComponent.getComponent().getText();
    }

    private void setErlArguments(@Nullable String text) {
        erlArgumentsComponent.getComponent().setText(text);
    }

    @NotNull
    private String getReleaseCLIPath() {
        return releaseCLIPathComponent.getComponent().getText();
    }

    private void setReleaseCLIPath(@Nullable String releaseCLIPath) {
        releaseCLIPathComponent.getComponent().setText(releaseCLIPath);
    }

    @NotNull
    private String getExtraArguments() {
        return extraArgumentsComponent.getComponent().getText();
    }

    private void setExtraArguments(@Nullable String extraArguments) {
        extraArgumentsComponent.getComponent().setText(extraArguments);
    }

    @Nullable
    private CodeLoadingMode getCodeLoadingMode() {
        return (CodeLoadingMode) codeLoadingModeComponent.getComponent().getSelectedItem();
    }

    private void setCodeLoadingMode(@Nullable CodeLoadingMode codeLoadingMode) {
        codeLoadingModeComponent.getComponent().setSelectedItem(codeLoadingMode);
    }

    @NotNull
    private String getLogDirectory() {
        return logDirectoryComponent.getComponent().getText();
    }

    private void setLogDirectory(@Nullable String logDirectory) {
        logDirectoryComponent.getComponent().setText(logDirectory);
    }

    @Nullable
    private Boolean getReplaceOSVars() {
        return (Boolean) replaceOSVarsComponent.getComponent().getSelectedItem();
    }

    private void setReplaceOSVars(@Nullable Boolean replaceOSVars) {
        replaceOSVarsComponent.getComponent().setSelectedItem(replaceOSVars);
    }

    @NotNull
    private String getSysConfigPath() {
        return sysConfigPathComponent.getComponent().getText();
    }

    private void setSysConfigPath(@Nullable String sysConfigPath) {
        sysConfigPathComponent.getComponent().setText(sysConfigPath);
    }

    @NotNull
    private String getReleaseConfigDirectory() {
        return releaseConfigDirectoryComponent.getComponent().getText();
    }

    private void setReleaseConfigDirectory(@Nullable String releaseConfigDirectory) {
        releaseConfigDirectoryComponent.getComponent().setText(releaseConfigDirectory);
    }

    @NotNull
    private String getPipeDirectory() {
        return pipeDirectoryComponent.getComponent().getText();
    }

    private void setPipeDirectory(@Nullable String pipeDirectory) {
        pipeDirectoryComponent.getComponent().setText(pipeDirectory);
    }

    private boolean getWantsPTY() {
        return wantsPTYComponent.getComponent().isSelected();
    }

    private void setWantsPTY(boolean wantsPTY) {
        wantsPTYComponent.getComponent().setSelected(wantsPTY);
    }

    public void applyTo(@NotNull Configuration configuration) {
        super.applyTo(configuration);
        configuration.setReleaseCLIPath(getReleaseCLIPath());
        configuration.setErlArguments(getErlArguments());
        configuration.setExtraArguments(getExtraArguments());
        configuration.setCodeLoadingMode(getCodeLoadingMode());
        configuration.setLogDirectory(getLogDirectory());
        configuration.setReplaceOSVars(getReplaceOSVars());
        configuration.setSysConfigPath(getSysConfigPath());
        configuration.setReleaseConfigDirectory(getReleaseConfigDirectory());
        configuration.setPipeDirectory(getPipeDirectory());
        configuration.setWantsPTY(getWantsPTY());
    }

    public void reset(@NotNull Configuration configuration) {
        super.reset(configuration);
        setReleaseCLIPath(configuration.getReleaseCLIPath());
        setErlArguments(configuration.getErlArguments());
        setExtraArguments(configuration.getExtraArguments());
        setCodeLoadingMode(configuration.getCodeLoadingMode());
        setLogDirectory(configuration.getLogDirectory());
        setReplaceOSVars(configuration.getReplaceOSVars());
        setSysConfigPath(configuration.getSysConfigPath());
        setReleaseConfigDirectory(configuration.getReleaseConfigDirectory());
        setPipeDirectory(configuration.getPipeDirectory());
        setWantsPTY(configuration.getWantsPTY());
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
        addReleaseCLIPathComponent();
        setProgramParametersLabel("Release CLI arguments:");
        addErlArgumentsComponent();
        addExtraArgumentsComponent();
        addCodeLoadingModeComponent();
        addLogDirectoryComponent();
        addReplaceOSVarsComponent();
        addSysConfigPathComponent();
        addReleaseConfigDirectoryComponent();
        addPipeDirectoryComponent();
        addWantsPtyComponent();
    }

    private void addReleaseCLIPathComponent() {
        FileChooserDescriptor fileChooserDescriptor = FileChooserDescriptorFactory.singleFile();
        fileChooserDescriptor.setTitle("Select Release CLI");
        MacroComboBoxWithBrowseButton releaseCLIPathComboBox = new MacroComboBoxWithBrowseButton(fileChooserDescriptor, getProject());
        releaseCLIPathComponent = LabeledComponent.create(releaseCLIPathComboBox, "Release CLI path");
        releaseCLIPathComponent.setLabelLocation(BorderLayout.WEST);
        add(releaseCLIPathComponent, 0);
    }

    // See CommonJavaParametersPanel's addComponents
    private void addErlArgumentsComponent() {
        erlArgumentsComponent = createArgumentsComponent("erl");
        addArgumentsComponent(erlArgumentsComponent, 2);
    }


    private void addExtraArgumentsComponent() {
       extraArgumentsComponent = createArgumentsComponent("elixir -extra");
       addArgumentsComponent(extraArgumentsComponent, 3);
    }

    private void addCodeLoadingModeComponent() {
        ComboBoxModel<CodeLoadingMode> codeLoadingModeComboBoxModel = new CollectionComboBoxModel<>(CODE_LOADING_MODE_LIST);
        ComboBox<CodeLoadingMode> codeLoadingModeComboBox = new ComboBox<>(codeLoadingModeComboBoxModel);
        codeLoadingModeComboBox.setRenderer(
                new SimpleListCellRenderer<>() {
                    @Override
                    public void customize(@NotNull JList list,
                                          @Nullable CodeLoadingMode value,
                                          int index,
                                          boolean selected,
                                          boolean hasFocus) {
                        if (value == null) {
                            setText("Use default");
                        } else {
                            setText(value.toString());
                        }
                    }
                }
        );

        codeLoadingModeComponent = LabeledComponent.create(codeLoadingModeComboBox, "Code loading mode");
        codeLoadingModeComponent.setLabelLocation(BorderLayout.WEST);
        add(codeLoadingModeComponent, 4);
    }

    private void addLogDirectoryComponent() {
        FileChooserDescriptor fileChooserDescriptor = FileChooserDescriptorFactory.singleDir();
        fileChooserDescriptor.setTitle("Select Log Directory");
        MacroComboBoxWithBrowseButton logDirectoryComboBox = new MacroComboBoxWithBrowseButton(fileChooserDescriptor, getProject());
        logDirectoryComponent = LabeledComponent.create(logDirectoryComboBox, "Log directory");
        logDirectoryComponent.setLabelLocation(BorderLayout.WEST);
        add(logDirectoryComponent, 5);
    }

    private void addReplaceOSVarsComponent() {
        ComboBoxModel<Boolean> replaceOsVarsComboBoxModel = new CollectionComboBoxModel<>(REPLACE_OS_VARS_LIST);
        ComboBox<Boolean> replaceOsVarsComboBox = new ComboBox<>(replaceOsVarsComboBoxModel);
        replaceOsVarsComboBox.setRenderer(
                new SimpleListCellRenderer<>() {
                    @Override
                    public void customize(@NotNull JList list,
                                          @Nullable Boolean value,
                                          int index,
                                          boolean selected,
                                          boolean hasFocus) {
                        if (value == null) {
                            setText("Use default");
                        } else {
                            setText(value.toString());
                        }
                    }
                }
        );

        replaceOSVarsComponent = LabeledComponent.create(replaceOsVarsComboBox, "Replace OS vars");
        replaceOSVarsComponent.setLabelLocation(BorderLayout.WEST);
        add(replaceOSVarsComponent, 6);
    }

    private void addSysConfigPathComponent() {
        FileChooserDescriptor fileChooserDescriptor = FileChooserDescriptorFactory.singleFile();
        //noinspection DialogTitleCapitalization
        fileChooserDescriptor.setTitle("Select sys.config File");
        MacroComboBoxWithBrowseButton sysConfigPathComboBox = new MacroComboBoxWithBrowseButton(fileChooserDescriptor, getProject());
        sysConfigPathComponent = LabeledComponent.create(sysConfigPathComboBox, "Config file (sys.config)");
        sysConfigPathComponent.setLabelLocation(BorderLayout.WEST);
        add(sysConfigPathComponent, 7);
    }

    private void addReleaseConfigDirectoryComponent() {
        FileChooserDescriptor fileChooserDescriptor = FileChooserDescriptorFactory.singleDir();
        fileChooserDescriptor.setTitle("Select Release Config Directory");
        MacroComboBoxWithBrowseButton releaseConfigDirectoryComboBox = new MacroComboBoxWithBrowseButton(fileChooserDescriptor, getProject());
        releaseConfigDirectoryComponent = LabeledComponent.create(releaseConfigDirectoryComboBox, "Release config directory");
        releaseConfigDirectoryComponent.setLabelLocation(BorderLayout.WEST);
        add(releaseConfigDirectoryComponent, 8);
    }

    private void addPipeDirectoryComponent() {
        FileChooserDescriptor fileChooserDescriptor = FileChooserDescriptorFactory.singleDir();
        fileChooserDescriptor.setTitle("Select Pipe Directory");
        MacroComboBoxWithBrowseButton pipeDirectoryComboBox = new MacroComboBoxWithBrowseButton(fileChooserDescriptor, getProject());
        pipeDirectoryComponent = LabeledComponent.create(pipeDirectoryComboBox, "Pipe directory");
        pipeDirectoryComponent.setLabelLocation(BorderLayout.WEST);
        add(pipeDirectoryComponent, 9);
    }

    private void addWantsPtyComponent() {
        wantsPTYComponent = LabeledComponent.create(new JCheckBox(), "Use pseudo-terminal (PTY)");
        wantsPTYComponent.setLabelLocation(BorderLayout.WEST);
        add(wantsPTYComponent, 10);
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
