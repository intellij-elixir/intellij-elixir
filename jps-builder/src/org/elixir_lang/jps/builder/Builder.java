package org.elixir_lang.jps.builder;

import com.intellij.execution.process.BaseOSProcessHandler;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.util.CommonProcessors;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.system.OS;
import org.elixir_lang.jps.builder.compiler_options.Extension;
import org.elixir_lang.jps.builder.execution.ExecutionException;
import org.elixir_lang.jps.builder.execution.GeneralCommandLine;
import org.elixir_lang.jps.builder.execution.ProcessAdapter;
import org.elixir_lang.jps.builder.execution.SourceRootDescriptor;
import org.elixir_lang.jps.builder.model.ErlangSdkNameMissing;
import org.elixir_lang.jps.builder.model.ModuleType;
import org.elixir_lang.jps.builder.model.SdkProperties;
import org.elixir_lang.jps.builder.sdk_type.MissingHomePath;
import org.elixir_lang.jps.builder.target.BuilderUtil;
import org.elixir_lang.jps.builder.target.Type;
import org.elixir_lang.jps.builder.tool_manager.Environment;
import org.elixir_lang.jps.shared.CompilerOptions;
import org.elixir_lang.jps.shared.cli.CliArgs;
import org.elixir_lang.jps.shared.cli.CliArguments;
import org.elixir_lang.jps.shared.cli.CliTool;
import org.elixir_lang.jps.shared.sdk.SdkPaths;
import org.elixir_lang.jps.shared.tool_manager.SdkEnvironment;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.builders.BuildOutputConsumer;
import org.jetbrains.jps.builders.DirtyFilesHolder;
import org.jetbrains.jps.builders.logging.ProjectBuilderLogger;
import org.jetbrains.jps.incremental.CompileContext;
import org.jetbrains.jps.incremental.ProjectBuildException;
import org.jetbrains.jps.incremental.TargetBuilder;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;
import org.jetbrains.jps.incremental.resources.ResourcesBuilder;
import org.jetbrains.jps.model.JpsProject;
import org.jetbrains.jps.model.java.JavaSourceRootType;
import org.jetbrains.jps.model.java.JpsJavaExtensionService;
import org.jetbrains.jps.model.library.JpsLibrary;
import org.jetbrains.jps.model.library.JpsLibraryCollection;
import org.jetbrains.jps.model.library.sdk.JpsSdk;
import org.jetbrains.jps.model.module.JpsModule;
import org.jetbrains.jps.model.module.JpsModuleSourceRoot;
import org.jetbrains.jps.model.module.JpsModuleType;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AccessDeniedException;
import java.util.*;

import static com.intellij.util.io.URLUtil.extractPath;

/**
 * <a href="https://github.com/ignatov/intellij-erlang/blob/master/jps-plugin/src/org/intellij/erlang/jps/builder/ErlangBuilder.java">...</a>
 * <a href="https://github.com/ignatov/intellij-erlang/tree/master/jps-plugin/src/org/intellij/erlang/jps/rebar">...</a>
 */
public class Builder extends TargetBuilder<SourceRootDescriptor, Target> {
    public static final String BUILDER_NAME = "Elixir Builder";
    public static final String ELIXIR_SOURCE_EXTENSION = "ex";
    public static final String ELIXIR_SCRIPT_EXTENSION = "exs";
    private static final String ELIXIR_TEST_SOURCE_EXTENSION = ELIXIR_SCRIPT_EXTENSION;

    private static final String ElIXIRC_NAME = "elixirc";
    private static final String MIX_NAME = "mix";
    private static final FileFilter ELIXIR_SOURCE_FILTER = file -> FileUtilRt.extensionEquals(file.getName(), ELIXIR_SOURCE_EXTENSION);
    private static final FileFilter ELIXIR_TEST_SOURCE_FILTER = file -> FileUtilRt.extensionEquals(file.getName(), ELIXIR_TEST_SOURCE_EXTENSION);
    // use JavaBuilderExtension?
    private static final Set<? extends JpsModuleType<?>> ourCompilableModuleTypes = Collections.singleton(ModuleType.INSTANCE);
    private static final String MIX_CONFIG_FILE_NAME = "mix." + ELIXIR_SCRIPT_EXTENSION;
    private final static Logger logger = Logger.getInstance(Builder.class);

    public Builder() {
        super(Arrays.asList(Type.PRODUCTION, Type.TEST));

        // disables java resource builder for elixir modules
        ResourcesBuilder.registerEnabler(module -> !(module.getModuleType() instanceof ModuleType));
    }

    /**
     * Build With elixirc.
     * if "isMake": compile all files of the module and dependent module.
     * else: just for compile the target affected file.
     */
    private static void doBuildWithElixirc(Target target, CompileContext context, JpsModule module, CompilerOptions compilerOptions, Set<String> absolutePathsToCompile) throws ProjectBuildException, MissingSdkProperties, ErlangSdkNameMissing, LibraryNotFound {

        // ensure compile output directory
        File outputDirectory = getBuildOutputDirectory(module, target.isTests(), context);
        runElixirc(target, context, compilerOptions, absolutePathsToCompile, outputDirectory);
    }

    private static void doBuildWithMix(Target target, CompileContext context, JpsModule module, CompilerOptions compilerOptions) throws ProjectBuildException {
        JpsSdk<SdkProperties> sdk = BuilderUtil.getSdk(context, module);

        for (String contentRootUrl : module.getContentRootsList().getUrls()) {
            String contentRootPath = extractPath(contentRootUrl);
            File contentRootDir = new File(contentRootPath);

            File mixConfigFile = new File(contentRootDir, MIX_CONFIG_FILE_NAME);

            if (!mixConfigFile.exists()) {
                continue;
            }

            String task = target.isTests() ? "test" : "compile";
            runMix(sdk, module, contentRootPath, task, compilerOptions, context);
        }
    }

    /*** doBuildWithElixirc releated private methods */

    @NotNull
    private static File getBuildOutputDirectory(@NotNull JpsModule module, boolean forTests, @NotNull CompileContext context) throws ProjectBuildException {

        JpsJavaExtensionService instance = JpsJavaExtensionService.getInstance();
        File outputDirectory = instance.getOutputDirectory(module, forTests);
        if (outputDirectory == null) {
            String errorMessage = "No output directory for module " + module.getName();
            context.processMessage(new CompilerMessage(ElIXIRC_NAME, BuildMessage.Kind.ERROR, errorMessage));
            throw new ProjectBuildException(errorMessage);
        }

        if (!outputDirectory.exists()) {
            FileUtil.createDirectory(outputDirectory);
        }
        return outputDirectory;
    }

    private static void runElixirc(Target target, CompileContext context, CompilerOptions compilerOptions, Set<String> absolutePaths, File outputDirectory) throws ProjectBuildException, MissingSdkProperties, ErlangSdkNameMissing, LibraryNotFound {
        GeneralCommandLine commandLine = getElixircCommandLine(target, context, compilerOptions, absolutePaths, outputDirectory);

        // elixirc runs in the output directory, which a module's own tool manager config may not cover.
        List<String> contentRootUrls = target.getModule().getContentRootsList().getUrls();
        File toolManagerDirectory = contentRootUrls.isEmpty() ? outputDirectory : new File(extractPath(contentRootUrls.get(0)));

        run(commandLine, toolManagerDirectory, null, context, ElIXIRC_NAME, compilerOptions);
    }

    private static GeneralCommandLine getElixircCommandLine(Target target, CompileContext context, CompilerOptions compilerOptions, Set<String> absolutePaths, File outputDirectory) throws ProjectBuildException, MissingSdkProperties, ErlangSdkNameMissing, LibraryNotFound {
        JpsModule module = target.getModule();
        JpsSdk<SdkProperties> sdk = BuilderUtil.getSdk(context, module);
        CliArgs args = cliArguments(CliTool.ELIXIRC, sdk, module);
        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.withWorkDirectory(outputDirectory);
        commandLine.setExePath(args.getExePath());
        commandLine.addParameters(args.getArguments());
        addCompileOptions(commandLine, compilerOptions);
        List<String> compileFilePaths = getCompileFilePaths(module, target, context, absolutePaths);
        commandLine.addParameters(compileFilePaths);
        return commandLine;
    }

    @NotNull
    private static List<String> getCompileFilePaths(@NotNull JpsModule module, @NotNull Target target, @NotNull CompileContext context, Set<String> absolutePaths) {
        // make
        if (context.getScope().isBuildIncrementally(target.getTargetType())) {
            return getCompileFilePathsDefault(module, target);
        }

        // force build files
        return new ArrayList<>(absolutePaths);
    }

    @NotNull
    private static List<String> getCompileFilePathsDefault(@NotNull JpsModule module, @NotNull Target target) {
        CommonProcessors.CollectProcessor<File> exFilesCollector = new CommonProcessors.CollectProcessor<>() {
            @Override
            protected boolean accept(File file) {
                return !file.isDirectory() && FileUtilRt.extensionEquals(file.getName(), ELIXIR_SOURCE_EXTENSION);
            }
        };

        List<JpsModuleSourceRoot> sourceRoots = new ArrayList<>();
        ContainerUtil.addAll(sourceRoots, module.getSourceRoots(JavaSourceRootType.SOURCE));
        if (target.isTests()) {
            ContainerUtil.addAll(sourceRoots, module.getSourceRoots(JavaSourceRootType.TEST_SOURCE));
        }

        for (JpsModuleSourceRoot root : sourceRoots) {
            FileUtil.processFilesRecursively(root.getFile(), exFilesCollector);
        }

        Collection<File> fileCollection = exFilesCollector.getResults();

        return ContainerUtil.map(fileCollection, File::getAbsolutePath);
    }

    private static void addCompileOptions(@NotNull GeneralCommandLine commandLine, CompilerOptions compilerOptions) {
        if (!compilerOptions.attachDocsEnabled) {
            commandLine.addParameter("--no-docs");
        }

        if (!compilerOptions.attachDebugInfoEnabled) {
            commandLine.addParameter("--no-debug-info");
        }

        if (compilerOptions.warningsAsErrorsEnabled) {
            commandLine.addParameter("--warnings-as-errors");
        }

        if (compilerOptions.ignoreModuleConflictEnabled) {
            commandLine.addParameter("--ignore-module-conflict");
        }
    }

    private static CliArgs cliArguments(@NotNull CliTool tool, @NotNull JpsSdk<SdkProperties> sdk, @NotNull JpsModule module) throws MissingSdkProperties, ErlangSdkNameMissing, LibraryNotFound {
        String erlangHomePath = sdkToErlHomepath(sdk, module);
        String elixirHomePath = sdk.getHomePath();
        String elixirVersion = sdk.getVersionString();

        return CliArguments.args(elixirHomePath, elixirVersion, erlangHomePath, tool, java.util.Collections.emptyList(), java.util.Collections.emptyList(), OS.CURRENT, true);
    }

    private static String sdkToErlHomepath(@NotNull JpsSdk<SdkProperties> sdk, @NotNull JpsModule module) throws MissingSdkProperties, ErlangSdkNameMissing, LibraryNotFound {
        SdkProperties sdkProperties = ensureSdkProperties(sdk);

        return sdkPropertiesToErlHomepath(sdkProperties, module);
    }

    @NotNull
    private static String sdkPropertiesToErlHomepath(@NotNull SdkProperties sdkProperties, @NotNull JpsModule module) throws ErlangSdkNameMissing, LibraryNotFound {
        String erlangSdkName = sdkProperties.ensureErlangSdkName();

        return erlangSdkNameToErlHomepath(erlangSdkName, module);
    }

    @NotNull
    private static String erlangSdkNameToErlHomepath(@NotNull String erlangSdkName, @NotNull JpsModule module) throws LibraryNotFound {
        JpsLibraryCollection libraryCollection = module.getProject().getModel().getGlobal().getLibraryCollection();
        JpsLibrary erlangSdkLibrary = libraryCollection.findLibrary(erlangSdkName);
        if (erlangSdkLibrary == null) {
            throw new LibraryNotFound(erlangSdkName);
        }

        JpsSdk<?> erlangSdk = (JpsSdk<?>) erlangSdkLibrary.getProperties();

        return erlangSdk.getHomePath();
    }

    @NotNull
    private static SdkProperties ensureSdkProperties(@NotNull JpsSdk<SdkProperties> sdk) throws MissingSdkProperties {
        SdkProperties sdkProperties = sdk.getSdkProperties();

        if (sdkProperties == null) {
            throw new MissingSdkProperties();
        }

        return sdkProperties;
    }


    private static GeneralCommandLine mixCommandLine(@NotNull JpsSdk<SdkProperties> sdk, @NotNull JpsModule module, @Nullable String workingDirectory, @NotNull String task, @NotNull CompilerOptions compilerOptions) throws FileNotFoundException, ErlangSdkNameMissing, LibraryNotFound, AccessDeniedException, MissingHomePath, MissingSdkProperties {
        CliArgs args = cliArguments(CliTool.MIX, sdk, module);
        GeneralCommandLine commandLine = new GeneralCommandLine();
        commandLine.withWorkDirectory(workingDirectory);
        commandLine.setExePath(args.getExePath());
        commandLine.addParameters(args.getArguments());
        commandLine.addParameter(task);
        addCompileOptions(commandLine, compilerOptions);

        return commandLine;
    }

    private static void runMix(@NotNull JpsSdk<SdkProperties> sdk, @NotNull JpsModule module, @Nullable String contentRootPath, @NotNull String task, @NotNull CompilerOptions compilerOptions, @NotNull CompileContext context) throws ProjectBuildException {
        GeneralCommandLine commandLine;

        try {
            commandLine = mixCommandLine(sdk, module, contentRootPath, task, compilerOptions);
        } catch (AccessDeniedException | ErlangSdkNameMissing | FileNotFoundException | LibraryNotFound |
                 MissingHomePath | MissingSdkProperties exception) {
            throw new ProjectBuildException("Couldn't construct command line for mix: " + exception.getMessage(), exception);
        }

        run(commandLine, commandLine.getWorkDirectory(), SdkPaths.mixHomeEnvironment(sdk.getHomePath()), context, MIX_NAME, compilerOptions);
    }

    private static void run(@NotNull GeneralCommandLine commandLine, @Nullable File toolManagerDirectory, @Nullable SdkEnvironment sdkEnvironment, @NotNull CompileContext context, @NotNull String builderName, @NotNull CompilerOptions compilerOptions) throws ProjectBuildException {
        Environment.apply(context.getProjectDescriptor().getProject(), commandLine, toolManagerDirectory, sdkEnvironment);

        Process process;

        try {
            process = commandLine.createProcess();
        } catch (ExecutionException executionException) {
            throw new ProjectBuildException("Failed to run " + builderName, executionException);
        }

        BaseOSProcessHandler handler = new BaseOSProcessHandler(process, commandLine.getCommandLineString(), Charset.defaultCharset());
        ProcessAdapter adapter = new ProcessAdapter(context, builderName, commandLine.getWorkDirectory().getPath(), compilerOptions);
        handler.addProcessListener(adapter);
        handler.startNotify();
        handler.waitFor();
    }

    @Override
    public void build(@NotNull Target target, @NotNull DirtyFilesHolder<SourceRootDescriptor, Target> holder, @NotNull BuildOutputConsumer outputConsumer, @NotNull CompileContext context) throws ProjectBuildException, IOException {
        logger.info(target.getPresentableName());

        final Set<String> absolutePathsToCompile = com.intellij.util.containers.CollectionFactory.createFilePathSet();

        holder.processDirtyFiles((target1, file, root) -> {
            boolean isAcceptFile = target1.isTests() ? ELIXIR_TEST_SOURCE_FILTER.accept(file) : ELIXIR_SOURCE_FILTER.accept(file);
            if (isAcceptFile && ourCompilableModuleTypes.contains(target1.getModule().getModuleType())) {
                absolutePathsToCompile.add(file.getAbsolutePath());
            }
            return true;
        });

        if (absolutePathsToCompile.isEmpty() && !holder.hasRemovedFiles()) {
            return;
        }

        JpsModule module = target.getModule();
        JpsProject project = module.getProject();
        CompilerOptions compilerOptions = Extension.getOrCreateExtension(project).getOptions();

        ProjectBuilderLogger logger = context.getLoggingManager().getProjectBuilderLogger();
        logger.logCompiledPaths(absolutePathsToCompile, Builder.BUILDER_NAME, "Compiling files:");

        if (compilerOptions.useMixCompiler) {
            doBuildWithMix(target, context, module, compilerOptions);
        } else {
            // elixirc can not compile tests now.
            if (!target.isTests()) {
                try {
                    doBuildWithElixirc(target, context, module, compilerOptions, absolutePathsToCompile);
                } catch (MissingSdkProperties | ErlangSdkNameMissing | LibraryNotFound e) {
                    throw new ProjectBuildException(e);
                }
            }
        }
    }

    @NotNull
    @Override
    public String getPresentableName() {
        return BUILDER_NAME;
    }

}
