package org.elixir_lang.jps.builder.tool_manager;

import com.intellij.openapi.util.JDOMUtil;
import com.intellij.util.xmlb.XmlSerializer;
import org.elixir_lang.jps.builder.JpsBuildTestCase;
import org.elixir_lang.jps.builder.execution.GeneralCommandLine;
import org.elixir_lang.jps.shared.sdk.SdkPaths;
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironment;
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironmentResolver;
import org.elixir_lang.jps.shared.tool_manager.SdkEnvironment;
import org.elixir_lang.jps.shared.tool_manager.ToolManagerSettingsState;
import org.jdom.Element;
import org.jetbrains.jps.incremental.ProjectBuildException;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class EnvironmentTest extends JpsBuildTestCase {
    private static final File WORK_DIRECTORY = new File("/home/u/project");
    private static final String SDK_HOME = "/home/u/.asdf/installs/elixir/1.18.4";
    private static final SdkEnvironment SDK_ENVIRONMENT = SdkPaths.mixHomeEnvironment(SDK_HOME);

    public void testTheBuildReadsTheSettingsTheIdeSaves() throws Exception {
        Element component = JDOMUtil.load("""
                <component name="ElixirToolManagerSettings">
                  <option name="enabledManagers"><set><option value="mise" /></set></option>
                  <option name="environmentManagers"><set><option value="mise" /></set></option>
                </component>""");

        new SettingsSerializer().loadExtension(myProject, component);

        assertTrue(SettingsExtension.getState(myProject).appliesEnvironment("mise"));
    }

    public void testTheBuildReadsWhatTheSettingsSerialiseTo() {
        ToolManagerSettingsState saved = new ToolManagerSettingsState();
        saved.getEnabledManagers().add("mise");
        saved.getEnvironmentManagers().add("mise");

        new SettingsSerializer().loadExtension(myProject, XmlSerializer.serialize(saved));

        assertTrue(SettingsExtension.getState(myProject).appliesEnvironment("mise"));
    }

    public void testSettingsSavedBeforeTheEnvironmentOptionApplyNoEnvironment() throws Exception {
        Element component = JDOMUtil.load("""
                <component name="ElixirToolManagerSettings">
                  <option name="enabledManagers"><set><option value="mise" /></set></option>
                </component>""");

        new SettingsSerializer().loadExtension(myProject, component);

        assertFalse(SettingsExtension.getState(myProject).appliesEnvironment("mise"));
    }

    public void testAProjectWithoutSettingsAppliesNoEnvironment() {
        assertFalse(SettingsExtension.getState(myProject).appliesEnvironment("mise"));
    }

    public void testTheBuildGetsTheVariablesTheToolManagerResolvesInTheGivenDirectoryUnderItsOwn() throws ProjectBuildException {
        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.withWorkDirectory(new File("/home/u/project/_build/dev"));
        commandLine.getEnvironment().put("MIX_ENV", "test");
        AtomicReference<Path> askedAbout = new AtomicReference<>();
        AtomicReference<Map<String, String>> launchVariablesSeen = new AtomicReference<>();
        ToolManagerEnvironmentResolver resolver = (workDir, launchVariables) -> {
            askedAbout.set(workDir);
            launchVariablesSeen.set(launchVariables);
            return new ToolManagerEnvironment.Variables(Map.of("MIX_ENV", "dev", "RUSTUP_TOOLCHAIN", "1.94.1"));
        };

        Environment.apply(miseEnvironment(), Map.of("mise", resolver), commandLine, WORK_DIRECTORY, null);

        assertEquals(WORK_DIRECTORY.toPath(), askedAbout.get());
        assertEquals(Map.of("MIX_ENV", "test"), launchVariablesSeen.get());
        assertEquals("test", commandLine.getEnvironment().get("MIX_ENV"));
        assertEquals("1.94.1", commandLine.getEnvironment().get("RUSTUP_TOOLCHAIN"));
    }

    public void testTheToolManagersMixHomeOverridesTheSdks() throws ProjectBuildException {
        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.withWorkDirectory(WORK_DIRECTORY);
        AtomicReference<Map<String, String>> launchVariablesSeen = new AtomicReference<>();
        ToolManagerEnvironmentResolver resolver = (workDir, launchVariables) -> {
            launchVariablesSeen.set(launchVariables);
            return new ToolManagerEnvironment.Variables(Map.of("MIX_HOME", "/home/u/.asdf/installs/elixir/1.16.0/.mix"));
        };

        Environment.apply(miseEnvironment(), Map.of("mise", resolver), commandLine, WORK_DIRECTORY, SDK_ENVIRONMENT);

        assertEquals(Map.of(), launchVariablesSeen.get());
        assertEquals("/home/u/.asdf/installs/elixir/1.16.0/.mix", commandLine.getEnvironment().get("MIX_HOME"));
        assertEquals("/home/u/.asdf/installs/elixir/1.16.0/.mix/archives", commandLine.getEnvironment().get("MIX_ARCHIVES"));
    }

    public void testAToolManagerEnvironmentWithoutAMixHomeLeavesTheSdks() throws ProjectBuildException {
        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.withWorkDirectory(WORK_DIRECTORY);
        ToolManagerEnvironmentResolver resolver =
                (workDir, launchVariables) -> new ToolManagerEnvironment.Variables(Map.of("RUSTUP_TOOLCHAIN", "1.94.1"));

        Environment.apply(miseEnvironment(), Map.of("mise", resolver), commandLine, WORK_DIRECTORY, SDK_ENVIRONMENT);

        File mixHome = new File(SDK_HOME, ".mix");
        assertEquals(mixHome.getAbsolutePath(), commandLine.getEnvironment().get("MIX_HOME"));
        assertEquals(new File(mixHome, "archives").getAbsolutePath(), commandLine.getEnvironment().get("MIX_ARCHIVES"));
    }

    public void testTheSdksMixHomeIsSetWithoutAToolManagerEnvironment() throws ProjectBuildException {
        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.withWorkDirectory(WORK_DIRECTORY);

        Environment.apply(new ToolManagerSettingsState(), Map.of(), commandLine, WORK_DIRECTORY, SDK_ENVIRONMENT);

        assertEquals(new File(SDK_HOME, ".mix").getAbsolutePath(), commandLine.getEnvironment().get("MIX_HOME"));
    }

    public void testAToolManagerThatCannotProduceItsEnvironmentFailsTheBuild() {
        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.withWorkDirectory(WORK_DIRECTORY);
        ToolManagerEnvironmentResolver resolver =
                (workDir, launchVariables) -> new ToolManagerEnvironment.Error("mise config file '/p/mise.toml' is not trusted");

        try {
            Environment.apply(miseEnvironment(), Map.of("mise", resolver), commandLine, WORK_DIRECTORY, null);
            fail("the build should fail");
        } catch (ProjectBuildException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("mise config file '/p/mise.toml' is not trusted"));
        }
    }

    private static ToolManagerSettingsState miseEnvironment() {
        ToolManagerSettingsState settings = new ToolManagerSettingsState();
        settings.getEnabledManagers().add("mise");
        settings.getEnvironmentManagers().add("mise");

        return settings;
    }
}
