package org.elixir_lang.mix.project._import.step;


import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.projectImport.ProjectImportWizardStep;
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironmentResolver;
import org.elixir_lang.mix.project._import.Builder;
import org.elixir_lang.mix.runner.MixTaskRunner;
import org.elixir_lang.sdk.choice.SdkChoiceStepKt;
import org.elixir_lang.tool_manager.ToolManagerProcessEnvironmentKt;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * <a href="https://github.com/ignatov/intellij-erlang/blob/master/src/org/intellij/erlang/rebar/importWizard/RebarProjectRootStep.java">...</a>
 */
public class Root extends ProjectImportWizardStep {
    private JCheckBox myGetDepsCheckbox;
    private JPanel myPanel;
    private TextFieldWithBrowseButton myProjectRootComponent;

    public Root(WizardContext context) {
        super(context);

        //noinspection DialogTitleCapitalization
        myProjectRootComponent.addBrowseFolderListener(
                null,
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
                        .withTitle("Select mix.exs of a mix project to import")
        );
    }

    /** The folder the wizard works in, unless a root was already entered. */
    @Override
    public void updateStep() {
        if (myProjectRootComponent.getText().isEmpty()) {
            myProjectRootComponent.setText(SdkChoiceStepKt.wizardFolder(getWizardContext(), getBuilder().getFileToImport()));
        }
    }

    /**
     * private methods
     */

    @Override
    @NotNull
    protected Builder getBuilder() {
        return (Builder) super.getBuilder();
    }

    @Override
    public JComponent getComponent() {
        return myPanel;
    }

    @Override
    @NotNull
    public JComponent getPreferredFocusedComponent() {
        return myProjectRootComponent.getTextField();
    }

    @Override
    public void updateDataModel() {
        String projectRoot = myProjectRootComponent.getText();
        if (!projectRoot.isEmpty()) {
            suggestProjectNameAndPath(null, projectRoot);
        }
    }

    @Override
    public boolean validate() {
        String projectRootPath = myProjectRootComponent.getText();
        if (StringUtil.isEmpty(projectRootPath)) {
            return false;
        }

        VirtualFile projectRoot = LocalFileSystem.getInstance().refreshAndFindFileByPath(projectRootPath);
        if (projectRoot == null) {
            return false;
        }

        if (myGetDepsCheckbox.isSelected() && !ApplicationManager.getApplication().isUnitTestMode()) {
            String workingDirectory = projectRoot.getCanonicalPath();

            assert workingDirectory != null;

            Sdk sdk = getBuilder().getElixirSdk();

            if (sdk != null) {
                // The project adopts the chooser's opt-ins only once the import commits.
                ToolManagerEnvironmentResolver environment = ToolManagerProcessEnvironmentKt.toolManagerEnvironment(
                        getWizardContext().getProject(),
                        getBuilder().getToolManagerOptIns()
                );

                // Errors are logged by MixTaskRunner
                MixTaskRunner.runImportTasks(environment, workingDirectory, sdk);
            }
        }

        Builder builder = getBuilder();
        builder.setIsImportingProject(getWizardContext().isCreatingNewProject());

        builder.setProjectRoot(projectRoot);
        return true;
    }
}
