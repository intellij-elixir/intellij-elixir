package org.elixir_lang.new_project_wizard

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.JavaUiBundle
import com.intellij.ide.highlighter.ModuleFileType
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.observable.properties.ObservableMutableProperty
import com.intellij.openapi.observable.properties.ObservableProperty
import com.intellij.openapi.observable.util.not
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.toNioPathOrNull
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.dsl.builder.*
import com.intellij.util.concurrency.annotations.RequiresEdt
import kotlinx.coroutines.CancellationException
import org.elixir_lang.Mix
import org.elixir_lang.module.ElixirModuleBuilder
import org.elixir_lang.sdk.SdkDetectionContext
import org.elixir_lang.sdk.SdkEnvironment
import org.elixir_lang.sdk.choice.SdkChoicePanel
import org.elixir_lang.sdk.choice.SdkChoiceState
import org.elixir_lang.sdk.choice.takesOptIns
import org.elixir_lang.sdk.erlang_dependent.getErlangSdk
import org.elixir_lang.tool_manager.ToolManagerOptIns
import java.io.IOException
import java.nio.file.Paths
import com.intellij.openapi.application.ReadAction
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.elixir_lang.sdk.wsl.wslCompat

// Based on [NewPythonProjectStep](https://github.com/JetBrains/intellij-community/blob/7bb876b50c1601c8563c444d5f133dd19247e814/python/src/com/jetbrains/python/newProject/NewProjectWizardPythonData.kt#L74)
class Step(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent),
                                           NewProjectWizardBaseData by parent.baseData!!,
                                           Data {
    override val sdkProperty = propertyGraph.property<Sdk?>(null)
    private val optInsProperty = propertyGraph.property<ToolManagerOptIns?>(null)
    override val mixNewAppProperty = propertyGraph.property("")
    override val mixNewModuleProperty = propertyGraph.property("")
    override val mixNewSupProperty = propertyGraph.property(false)
    override val mixNewUmbrellaProperty = propertyGraph.property(false)

    override var sdk by sdkProperty
    override var mixNewApp by mixNewAppProperty
    override var mixNewModule by mixNewModuleProperty
    override var mixNewSup by mixNewSupProperty
    override var mixNewUmbrella by mixNewUmbrellaProperty

    // Based on [IntelliJNewProjectWizardStep.setupUI](https://github.com/JetBrains/intellij-community/blob/dcb0ce2edd2c3b1dffb7e60103898acd5b913cfb/java/idea-ui/src/com/intellij/ide/projectWizard/generators/IntelliJNewProjectWizardStep.kt#L77-L124)
    override fun setupUI(builder: Panel) {
        with(builder) {
            row("Elixir SDK:") {
                elixirSdkChooser(context, sdkProperty, optInsProperty, pathProperty).align(AlignX.FILL)
            }
            @Suppress("DialogTitleCapitalization")
            collapsibleGroup("mix new Options") {
                row("--app") {
                    textField()
                        .bindText(mixNewAppProperty)
                        .align(AlignX.FILL)
                        .validationOnApply {
                            if (mixNewApp.isBlank()) {
                                val name = this@Step.name

                                if (!name.matches(APPLICATION_NAME_REGEX)) {
                                    error(
                                        "Application name must start with a lowercase ASCII letter, followed by " +
                                                "lowercase ASCII letters, numbers, or underscores, got: \"${name}\"" +
                                                ". The application name is inferred from the path, if you'd like to" +
                                                " explicitly name the application set --app"
                                    )
                                } else {
                                    null
                                }
                            } else {
                                if (!mixNewApp.matches(APPLICATION_NAME_REGEX)) {
                                    error(
                                        "Application name must start with a lowercase ASCII letter, followed by " +
                                                "lowercase ASCII letters, numbers, or underscores."
                                    )
                                } else {
                                    null
                                }
                            }
                        }
                }.bottomGap(BottomGap.SMALL)
                row("--module") {
                    textField()
                        .bindText(mixNewModuleProperty)
                        .align(AlignX.FILL)
                }.bottomGap(BottomGap.SMALL)
                row {
                    checkBox("--sup")
                        .bindSelected(mixNewSupProperty)
                        .enabledIf(!mixNewUmbrellaProperty)
                        .comment("Not available for umbrella projects: an umbrella root has no application to supervise.")
                }.bottomGap(BottomGap.SMALL)
                row {
                    checkBox("--umbrella")
                        .bindSelected(mixNewUmbrellaProperty)
                }
            }
        }
    }

    override fun setupProject(project: Project) {
        try {
            val sdk = this.sdk!!
            val workingDirectory = Paths.get(".").toAbsolutePath().normalize().toString()
            val projectDirectory = context.projectDirectory.toString()

            // Mix.commandLine() -> argsOrThrow() -> requireErlangSdkOrNotifyAndThrow()
            // -> findErlangSdkByHomePath() asserts a read lock; acquire one here.
            val commandLine = ReadAction.nonBlocking(Callable {
                Mix.commandLine(emptyMap(), workingDirectory, sdk, project = project)
            }).executeSynchronously()
            commandLine.addParameters(
                MixNewOptions.parameters(projectDirectory, mixNewApp, mixNewModule, mixNewSup, mixNewUmbrella)
            )

            // delete the caller's created empty directory so that `mix new` can create it.
            if (!context.projectDirectory.toFile().deleteRecursively()) {
                throw IOException("Could not delete ${context.projectDirectory}")
            }

            val processOutput = ProgressManager
                .getInstance()
                .run(object : Task.WithResult<ProcessOutput, ExecutionException>(
                    project,
                    "mix new $projectDirectory",
                    true
                ) {
                    @Throws(ExecutionException::class)
                    override fun compute(indicator: ProgressIndicator): ProcessOutput {
                        indicator.isIndeterminate = true

                        return ExecUtil.execAndGetOutput(commandLine, TimeUnit.SECONDS.toMillis(30).toInt())
                    }
                })

            if (processOutput.isCancelled) {
                throw CancellationException()
            } else if (processOutput.isTimeout) {
                throw TimeoutException()
            } else if (processOutput.exitCode != 0) {
                val stderrWithoutColorCodes = processOutput.stderr.replace(Regex("\u001B\\[[;\\d]*m"), "")

                @Suppress("DialogTitleCapitalization")
                NotificationGroupManager
                    .getInstance()
                    .getNotificationGroup("Elixir")
                    .createNotification("mix new failed", stderrWithoutColorCodes, NotificationType.ERROR)
                    // project will fail to initialize and not have a window, so don't use `project`
                    .notify(null)

                throw IOException("mix new failed: $stderrWithoutColorCodes")
            }

            // mix new wrote the whole project from an external process, so none of it is in VFS.
            // Refresh before configuring the module: the folder-mark scans that run later use
            // findChild, which answers from the cached child list rather than going to disk, and
            // would otherwise never see this project's mix.exs.
            LocalFileSystem
                .getInstance()
                .refreshAndFindFileByNioFile(context.projectDirectory)
                ?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }

            super.setupProject(project)

            moduleBuilder(sdk).commit(project)
        } catch (e: ExecutionException) {
            Logger.getInstance(javaClass).warn("mix new failed", e)
            throw e
        } catch (ioException: IOException) {
            Logger.getInstance(javaClass).error(ioException)

            throw ioException
        }
    }

    /**
     * The builder of the module `mix new` made, which writes [sdk] as its Elixir SDK. It is named and placed from this
     * step's name and location, as `IntelliJNewProjectWizardStep.configureModuleBuilder` does: left unset, New Module
     * would write the project's own module file.
     */
    internal fun moduleBuilder(sdk: Sdk): ElixirModuleBuilder {
        val projectDirectory = context.projectDirectory.toString()
        val builder = ElixirModuleBuilder()
        builder.name = name
        builder.moduleFilePath = FileUtil.toSystemDependentName(Paths.get(projectDirectory, name + ModuleFileType.DOT_DEFAULT_EXTENSION).toString())
        builder.contentEntryPath = FileUtil.toSystemDependentName(projectDirectory)
        builder.elixirSdk = sdk
        builder.toolManagerOptIns = optInsProperty.get()

        // Set the source paths even when there are none: JavaModuleBuilder.getSourcePaths()
        // creates and returns <contentRoot>/src when they were never set at all, which would
        // leave a stray empty src/ in an umbrella root.
        builder.sourcePaths = MixNewOptions
            .sourcePath(projectDirectory, mixNewUmbrella)
            ?.let { listOf(com.intellij.openapi.util.Pair(it, "")) }
            .orEmpty()

        MixNewOptions
            .compilerOutputPath(projectDirectory, name, mixNewApp, mixNewUmbrella)
            ?.let(builder::setCompilerOutputPath)

        return builder
    }
}

private val APPLICATION_NAME_REGEX: Regex = Regex("[a-z]([a-z0-9_])*")

/**
 * The chooser's panel over the table's Elixir SDKs that run where [locationProperty]'s directory is. The pick is
 * [sdkProperty], and the tool manager opt-ins handed over with it are [optInsProperty]. Create is refused until there is
 * a pick, and for one with no Erlang SDK, which `mix new` needs.
 */
@RequiresEdt
fun Row.elixirSdkChooser(
    context: WizardContext,
    sdkProperty: ObservableMutableProperty<Sdk?>,
    optInsProperty: ObservableMutableProperty<ToolManagerOptIns?>,
    locationProperty: ObservableProperty<String>,
): Cell<SdkChoicePanel> {
    fun locationDirectory(): String? = locationProperty.get().takeUnless(String::isBlank)
    fun target() = locationDirectory()?.let { SdkEnvironment.eelDescriptor(it) }

    fun folders() = listOfNotNull(locationDirectory()?.toNioPathOrNull())

    val state = SdkChoiceState(context.project ?: ProjectManager.getInstance().defaultProject, target(), null, folders(), takesOptIns(context))
    Disposer.register(context.disposable, state)
    Disposer.register(context.disposable) { SdkDetectionContext.clear() }
    val panel = SdkChoicePanel(state, context.disposable) {
        sdkProperty.set(it?.entry?.sdk)
        optInsProperty.set(it?.optIns)
    }

    // Add...'s home chooser opens where the project goes.
    SdkDetectionContext.set(locationDirectory())
    locationProperty.afterChange(context.disposable) {
        SdkDetectionContext.set(locationDirectory())
        panel.retarget(target(), folders())
    }

    return cell(panel).validationOnApply { validationMessage(sdkProperty.get())?.let { error(it) } }
}

private fun validationMessage(sdk: Sdk?): @NlsContexts.DialogMessage String? {
    sdk ?: return JavaUiBundle.message("title.no.jdk.specified")

    val internalErlangSdk = sdk.getErlangSdk()
        ?: return "Internal Erlang SDK is not configured. Set it before picking this Elixir SDK."

    val internalErlangHome = internalErlangSdk.homePath
    if (internalErlangHome != null && !wslCompat.isReachable(internalErlangHome)) {
        return "Internal Erlang SDK (${internalErlangSdk.name}) home directory ($internalErlangHome) is in a WSL" +
                " distribution that is not installed."
    }
    if (internalErlangHome?.let { wslCompat.findFileByPath(it) } == null) {
        return "Internal Erlang SDK (${internalErlangSdk.name}) home directory" +
                " (${internalErlangSdk.homePath}) does not exist. Did you uninstall it?"
    }

    return null
}
