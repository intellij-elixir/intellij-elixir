package org.elixir_lang.new_project_wizard

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.JavaUiBundle
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.observable.properties.ObservableMutableProperty
import com.intellij.openapi.observable.properties.ObservableProperty
import com.intellij.openapi.observable.util.not
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkTypeId
import com.intellij.openapi.roots.ui.configuration.JdkComboBox
import com.intellij.openapi.roots.ui.configuration.SdkListItem
import com.intellij.openapi.roots.ui.configuration.createSdkComboBox
import com.intellij.openapi.roots.ui.configuration.projectRoot.ProjectSdksModel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.layout.ValidationInfoBuilder
import kotlinx.coroutines.CancellationException
import org.elixir_lang.Elixir
import org.elixir_lang.Mix
import org.elixir_lang.jps.shared.ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID
import org.elixir_lang.module.ElixirModuleBuilder
import org.elixir_lang.sdk.SdkDetectionContext
import org.elixir_lang.sdk.SdkEnvironment
import org.elixir_lang.sdk.elixir.Type
import org.elixir_lang.sdk.erlang_dependent.ErlangSdkResolver
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
    override val mixNewAppProperty = propertyGraph.property("")
    override val mixNewModuleProperty = propertyGraph.property("")
    override val mixNewSupProperty = propertyGraph.property(false)
    override val mixNewUmbrellaProperty = propertyGraph.property(false)

    // Stored in newSdkCallback so setupProject can ensure the Erlang SDK is in
    // ProjectJdkTable at mix-command time, even if the workspace-model sync has
    // already wiped it out (IJ 2026+). setupProject runs on the EDT so no sync
    // can fire between addJdk and the resolver call.
    private var erlangSdkForSetup: Sdk? = null

    override var sdk by sdkProperty
    override var mixNewApp by mixNewAppProperty
    override var mixNewModule by mixNewModuleProperty
    override var mixNewSup by mixNewSupProperty
    override var mixNewUmbrella by mixNewUmbrellaProperty

    // Based on [IntelliJNewProjectWizardStep.setupUI](https://github.com/JetBrains/intellij-community/blob/dcb0ce2edd2c3b1dffb7e60103898acd5b913cfb/java/idea-ui/src/com/intellij/ide/projectWizard/generators/IntelliJNewProjectWizardStep.kt#L77-L124)
    override fun setupUI(builder: Panel) {
        with(builder) {
            row("Elixir SDK:") {
                elixirSdkComboBox(context, sdkProperty, pathProperty) { erlangSdkForSetup = it }
                    .columns(COLUMNS_MEDIUM)
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

            // Re-register both SDKs if the workspace-model sync has wiped them from
            // ProjectJdkTable. This sync fires ~5s after IDE start (delayLoadGlobalWorkspaceModel
            // in JpsGlobalModelSynchronizerImpl) and replaces in-memory SDKs with whatever was
            // on disk at that moment - before our SDKs were registered. setupProject runs on
            // the EDT so no sync can interleave between addJdk and the resolver call.
            ApplicationManager.getApplication().runWriteAction {
                val jdkTable = ProjectJdkTable.getInstance()
                if (jdkTable.findJdk(sdk.name) == null) {
                    jdkTable.addJdk(sdk)
                }
                erlangSdkForSetup?.let { erlangSdk ->
                    if (jdkTable.findJdk(erlangSdk.name) == null) {
                        jdkTable.addJdk(erlangSdk)
                    }
                }
            }
            erlangSdkForSetup = null  // Clear after use; Step is wizard-scoped so no leak, but signals intent

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

            val builder = ElixirModuleBuilder()

            // Always set moduleJdk rather than context.projectJdk so the Elixir plugin never claims the project SDK slot.
            // The project SDK belongs to the host language (Java, Python, etc.) and is not required for Elixir functionality.
            builder.moduleJdk = sdk

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

            builder.commit(project)
        } catch (e: ExecutionException) {
            Logger.getInstance(javaClass).warn("mix new failed", e)
            throw e
        } catch (ioException: IOException) {
            Logger.getInstance(javaClass).error(ioException)

            throw ioException
        }
    }
}

private val ELIXIR_SDK_TYPE_FILTER = { it: SdkTypeId -> it == Type.instance; }
private val ANY_SDK_TYPE_FILTER: ((SdkTypeId) -> Boolean) = { true }
private val ANY_SUGGESTED_SDK_FILTER: ((SdkListItem.SuggestedItem) -> Boolean) = { true }
private val APPLICATION_NAME_REGEX: Regex = Regex("[a-z]([a-z0-9_])*")

fun Row.elixirSdkComboBox(
    context: WizardContext,
    sdkProperty: ObservableMutableProperty<Sdk?>,
    locationProperty: ObservableProperty<String>? = null,
    onErlangSdkRegistered: (Sdk) -> Unit = {},
): Cell<JdkComboBox> {
    val sdksModel = ProjectSdksModel()

    Disposer.register(context.disposable) {
        SdkDetectionContext.clear()
        sdksModel.disposeUIResources()
    }

    fun locationDirectory(): String? = locationProperty?.get()?.takeUnless(String::isBlank)
    fun targetDescriptor() = locationDirectory()?.let { SdkEnvironment.eelDescriptor(it) }

    // After setupSdkPaths runs on a newly created Elixir SDK, verify it has a linked Erlang SDK.
    // If not (e.g. user cancelled the Erlang SDK selection dialog), remove it from the staged model
    // and clear the combo box selection so the wizard returns to "No SDK".
    // comboBox is assigned after this lambda but will be initialised by the time the callback fires.
    lateinit var comboBox: JdkComboBox
    val newSdkCallback: (Sdk) -> Unit = { sdk ->
        val erlangSdk = Elixir.elixirSdkToErlangSdk(sdk)
        if (erlangSdk == null) {
            sdksModel.removeSdk(sdk)
            comboBox.reloadModel()
            comboBox.selectedJdk = null
        } else {
            // The Erlang SDK was registered into ProjectJdkTable by registerErlangSdk /
            // SdkRegistrar. Add the Elixir SDK to sdksModel (not yet in JdkTable) so that
            // sdksModel.apply() on wizard commit can commit it cleanly.
            // ErlangSdkResolver checks both sdksModel and ProjectJdkTable, so the Erlang
            // SDK is found during validation without requiring a model reset.
            // Note: this does NOT prevent the workspace-model sync from wiping the SDKs
            // later - that is handled in setupProject via runWriteAction re-registration.
            sdksModel.addSdk(sdk)
            comboBox.reloadModel()
            comboBox.selectedJdk = sdk
            onErlangSdkRegistered(erlangSdk)
        }
    }

    comboBox = createSdkComboBox(
        context.project,
        sdksModel,
        sdkProperty,
        ELIXIR_MODULE_TYPE_ID,
        ELIXIR_SDK_TYPE_FILTER,
        { sdk -> SdkEnvironment.sdkVisibleFor(targetDescriptor(), sdk) },
        ANY_SUGGESTED_SDK_FILTER,
        ANY_SDK_TYPE_FILTER,
        newSdkCallback
    )

    // The wizard supplies no Project, so createSdkComboBox's internal reset() only loaded local
    // SDKs. Publish the location for SDK detection/creation and re-add SDKs registered for the
    // target environment, re-syncing whenever the user edits the location (e.g. to a WSL path).
    fun syncForLocation() {
        SdkDetectionContext.set(locationDirectory())
        SdkEnvironment.syncTargetSdks(sdksModel, targetDescriptor())
        comboBox.reloadModel()
    }
    syncForLocation()
    locationProperty?.afterChange(context.disposable) { syncForLocation() }

    return cell(comboBox)
        .validationOnApply { validateElixirSdk(sdkProperty, sdksModel) }
}

fun ValidationInfoBuilder.validateElixirSdk(
    sdkProperty: ObservableProperty<Sdk?>,
    sdkModel: ProjectSdksModel
): ValidationInfo? = validateAndGetSdkValidationMessage(sdkProperty, sdkModel)?.let { error(it) }

private fun validateAndGetSdkValidationMessage(
    sdkProperty: ObservableProperty<Sdk?>,
    sdkModel: ProjectSdksModel,
): @NlsContexts.DialogMessage String? {
    val sdk = sdkProperty.get() ?: return JavaUiBundle.message("title.no.jdk.specified")

    val internalErlangSdk = ErlangSdkResolver.getInstance().resolveErlangSdk(sdk, sdkModel)
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

    return try {
        sdkModel.apply(null, true)
        null
    } catch (e: ConfigurationException) {
        @Suppress("DEPRECATION")
        e.message ?: e.title
    }
}
