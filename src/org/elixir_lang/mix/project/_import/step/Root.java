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
import org.elixir_lang.mix.project._import.Builder;
import org.elixir_lang.mix.runner.MixTaskRunner;
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

        String projectFileDirectory = context.getProjectFileDirectory();
        //noinspection DialogTitleCapitalization
        myProjectRootComponent.addBrowseFolderListener(
                null,
                FileChooserDescriptorFactory.createSingleFolderDescriptor()
                        .withTitle("Select mix.exs of a mix project to import")
        );

        myProjectRootComponent.setText(projectFileDirectory); // provide project path
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
                // Ignore result - errors are logged by MixTaskRunner
                MixTaskRunner.updateHex(null, workingDirectory, sdk);
                MixTaskRunner.updateRebar(null, workingDirectory, sdk);
                MixTaskRunner.fetchDependencies(null, workingDirectory, sdk);
            }
        }

        Builder builder = getBuilder();
        builder.setIsImportingProject(getWizardContext().isCreatingNewProject());

        builder.setProjectRoot(projectRoot);
        return true;
    }
}
