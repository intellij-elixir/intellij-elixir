package org.elixir_lang.jps.builder.tool_manager;

import com.intellij.execution.process.BaseOSProcessHandler;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.util.Key;
import com.intellij.util.EnvironmentUtil;
import org.elixir_lang.jps.builder.execution.GeneralCommandLine;
import org.elixir_lang.jps.shared.tool_manager.SdkEnvironment;
import org.elixir_lang.jps.shared.tool_manager.ToolManagerCommandOutput;
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironment;
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironmentKt;
import org.elixir_lang.jps.shared.tool_manager.ToolManagerEnvironmentResolver;
import org.elixir_lang.jps.shared.tool_manager.ToolManagerSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;
import org.jetbrains.jps.incremental.ProjectBuildException;
import org.jetbrains.jps.model.JpsProject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Applies the environment of the tool managers the project opted into to the processes the build starts. */
public final class Environment {
    private static final long TIMEOUT_MS = 10_000;

    private static final Map<String, ToolManagerEnvironmentResolver> RESOLVERS =
            ToolManagerEnvironmentKt.builtInToolManagerEnvironments(Environment::run);

    private Environment() {
    }

    /**
     * Applies the environment the tool managers resolve in {@code directory}, keeping variables already set, then the
     * variables {@code sdkEnvironment} derives.
     */
    public static void apply(@NotNull JpsProject project,
                             @NotNull GeneralCommandLine commandLine,
                             @Nullable File directory,
                             @Nullable SdkEnvironment sdkEnvironment) throws ProjectBuildException {
        apply(SettingsExtension.getState(project), RESOLVERS, commandLine, directory, sdkEnvironment);
    }

    @VisibleForTesting
    static void apply(@NotNull ToolManagerSettingsState settings,
                      @NotNull Map<String, ToolManagerEnvironmentResolver> resolvers,
                      @NotNull GeneralCommandLine commandLine,
                      @Nullable File directory,
                      @Nullable SdkEnvironment sdkEnvironment) throws ProjectBuildException {
        Map<String, String> explicit = new HashMap<>(commandLine.getEnvironment());
        ToolManagerEnvironment environment = directory == null
                ? null
                : ToolManagerEnvironmentKt.resolveToolManagerEnvironment(settings, resolvers, directory.toPath(), explicit);

        if (environment instanceof ToolManagerEnvironment.Error) {
            throw new ProjectBuildException(((ToolManagerEnvironment.Error) environment).getMessage());
        }

        Map<String, String> variables = environment instanceof ToolManagerEnvironment.Variables
                ? ((ToolManagerEnvironment.Variables) environment).getVariables()
                : Map.of();
        ToolManagerEnvironmentKt.putToolManagerEnvironment(commandLine.getEnvironment(), variables, explicit, sdkEnvironment);
    }

    @NotNull
    private static ToolManagerCommandOutput run(@NotNull List<String> command,
                                                @NotNull Path workDir,
                                                @NotNull Map<String, String> environment) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(command).directory(workDir.toFile());
        // The same base environment the build's own GeneralCommandLine processes inherit.
        processBuilder.environment().clear();
        processBuilder.environment().putAll(EnvironmentUtil.getEnvironmentMap());
        processBuilder.environment().putAll(environment);
        Process process = processBuilder.start();
        BaseOSProcessHandler handler =
                new BaseOSProcessHandler(process, String.join(" ", command), StandardCharsets.UTF_8);
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                if (outputType == ProcessOutputTypes.STDOUT) {
                    stdout.append(event.getText());
                } else if (outputType == ProcessOutputTypes.STDERR) {
                    stderr.append(event.getText());
                }
            }
        });
        handler.startNotify();

        if (!handler.waitFor(TIMEOUT_MS)) {
            handler.destroyProcess();
            throw new IOException("timed out after " + TIMEOUT_MS / 1000 + " s");
        }

        return new ToolManagerCommandOutput(process.exitValue(), stdout.toString(), stderr.toString());
    }
}
