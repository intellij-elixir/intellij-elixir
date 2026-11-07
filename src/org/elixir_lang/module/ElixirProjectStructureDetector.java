package org.elixir_lang.module;

import com.intellij.ide.util.importProject.ModuleDescriptor;
import com.intellij.ide.util.importProject.ProjectDescriptor;
import com.intellij.ide.util.projectWizard.ModuleWizardStep;
import com.intellij.ide.util.projectWizard.importSources.DetectedProjectRoot;
import com.intellij.ide.util.projectWizard.importSources.ProjectFromSourcesBuilder;
import com.intellij.ide.util.projectWizard.importSources.ProjectStructureDetector;
import com.intellij.ide.util.projectWizard.importSources.impl.ProjectFromSourcesBuilderImpl;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ui.configuration.ModulesProvider;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.util.containers.ContainerUtil;
import org.elixir_lang.sdk.choice.SdkChoiceKt;
import org.elixir_lang.sdk.choice.SdkChoicePick;
import org.elixir_lang.sdk.choice.SdkChoiceStep;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Created by zyuyou on 2015/5/27.
 *
 */
public class ElixirProjectStructureDetector extends ProjectStructureDetector {
  private static final Pattern ELIXIR_FILE = Pattern.compile(".*\\.exs*");

  @NotNull
  @Override
  public DirectoryProcessingResult detectRoots(@NotNull File dir, File @NotNull [] children, @NotNull File base, @NotNull List<DetectedProjectRoot> result) {
    // Stops at the first Elixir file.
    boolean hasElixirFile = !FileUtil.processFilesRecursively(
      base,
      file -> !(ELIXIR_FILE.matcher(file.getName()).matches() && !file.isDirectory())
    );
    if(hasElixirFile){
      result.add(new DetectedProjectRoot(dir) {
        @NotNull
        @Override
        public String getRootTypeName() {
          return "Elixir";
        }
      });
    }
    return DirectoryProcessingResult.SKIP_CHILDREN;
  }

  /**
   * This detector just for import non-organization project which not like mix-project
   * So that it's no need to detect directories such as libs, deps, tests etc
   * <p>
   * And mix-project will imported by the Builder.java
   * */
  @Override
  public void setupProjectStructure(@NotNull Collection<DetectedProjectRoot> roots, @NotNull ProjectDescriptor projectDescriptor, @NotNull ProjectFromSourcesBuilder builder) {
    if(!roots.isEmpty() && !builder.hasRootsFromOtherDetectors(this) && projectDescriptor.getModules().isEmpty()){
      projectDescriptor.setModules(ContainerUtil.map(
        roots,
        root -> new ModuleDescriptor(root.getDirectory(), ElixirModuleType.getInstance(), ContainerUtil.emptyList())
      ));
    }
  }

  /**
   * The chooser, asking for the Elixir SDK of this detector's modules, which an updater of the import writes once they
   * are made. Only {@link ProjectFromSourcesBuilderImpl} takes updaters, so another builder gets no step.
   */
  @Override
  public List<ModuleWizardStep> createWizardSteps(ProjectFromSourcesBuilder builder, ProjectDescriptor projectDescriptor, Icon stepIcon) {
    if (!(builder instanceof ProjectFromSourcesBuilderImpl sourcesBuilder)) {
      return Collections.emptyList();
    }

    AtomicReference<SdkChoicePick> picked = new AtomicReference<>();
    sourcesBuilder.addConfigurationUpdater((project, modelsProvider, modulesProvider) -> {
      SdkChoicePick pick = picked.get();
      if (pick != null) {
        SdkChoiceKt.writePick(project, modulesOf(projectDescriptor, modulesProvider), pick.getEntry().getSdk(), pick.getOptIns());
      }
    });

    return Collections.singletonList(new SdkChoiceStep(builder.getContext(), picked::set, builder::getBaseProjectPath));
  }

  /** The modules of {@code modulesProvider} with a content root of one of {@code projectDescriptor}'s modules. */
  private static @NotNull List<Module> modulesOf(@NotNull ProjectDescriptor projectDescriptor, @NotNull ModulesProvider modulesProvider) {
    Set<String> contentRootUrls = new HashSet<>();
    for (ModuleDescriptor moduleDescriptor : projectDescriptor.getModules()) {
      for (File contentRoot : moduleDescriptor.getContentRoots()) {
        contentRootUrls.add(VfsUtilCore.fileToUrl(contentRoot));
      }
    }

    return ContainerUtil.filter(
      modulesProvider.getModules(),
      module -> ContainerUtil.exists(ModuleRootManager.getInstance(module).getContentRootUrls(), contentRootUrls::contains)
    );
  }
}
