package org.elixir_lang.iex.mix

import com.intellij.execution.Executor
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationWithSuppressedDefaultDebugAction
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunConfigurationWithSuppressedDefaultRunAction
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.options.SettingsEditorGroup
import com.intellij.openapi.project.Project
import org.elixir_lang.debugger.Modules
import org.elixir_lang.debugger.configuration.Debuggable
import org.elixir_lang.debugger.settings.stepping.ModuleFilter
import org.elixir_lang.iex.Mix
import org.elixir_lang.iex.mix.configuration.Editor
import org.elixir_lang.mix.ensureMostSpecificSdk
import org.elixir_lang.run.*
import org.jdom.Element

class Configuration(name: String, project: Project, configurationFactory: ConfigurationFactory) :
        org.elixir_lang.run.Configuration(name, project, configurationFactory),
        Debuggable<Configuration>,
        RunConfigurationWithSuppressedDefaultRunAction,
        RunConfigurationWithSuppressedDefaultDebugAction {
    override val cookie: String? = null
    override var inheritApplicationModuleFilters: Boolean = true
    override var moduleFilterList: MutableList<ModuleFilter> = mutableListOf()
    override val nodeName: String? = null

    override fun debuggedConfiguration(name: String, cookie: String): Configuration {
        val debugged = Configuration(this.name, project, factory!!)

        debugged.erlArgumentList.addAll(erlArgumentList)
        debugged.erlArgumentList.addAll(arrayOf("-name", name))
        debugged.erlArgumentList.addAll(arrayOf("-setcookie", cookie))
        debugged.erlArgumentList.addAll(Modules.erlArgumentList(mix = true))

        debugged.iexArgumentList.addAll(iexArgumentList)

        val mixArgumentList =
                when {
                    mixArgumentList.isEmpty() -> listOf("do", "intellij_elixir.debug,", "run")
                    mixArgumentList.firstOrNull() == "do" -> listOf("do", "intellij_elixir.debug,") + mixArgumentList.drop(1)
                    else -> listOf("do", "intellij_elixir.debug,") + mixArgumentList
                }
        debugged.mixArgumentList.addAll(mixArgumentList)

        debugged.workingDirectory = workingDirectory
        debugged.isPassParentEnvs = isPassParentEnvs
        debugged.envs = envs

        debugged.configurationModule.module = configurationModule.module

        return debugged
    }

    /** The IDE's Build step fails on a Mix module, which has no output path. */
    override fun isBuildBeforeLaunchAddedByDefault(): Boolean = false

    override fun getProgramParameters(): String? = mixArguments

    override fun setProgramParameters(value: String?) {
        mixArguments = value
    }

    var erlArgumentList: MutableList<String> = mutableListOf()

    var erlArguments: String?
        get() = erlArgumentList.toArguments()
        set(arguments) = erlArgumentList.fromArguments(arguments)

    var iexArguments: String?
        @JvmName("getIExArguments")
        get() = iexArgumentList.toArguments()
        @JvmName("setIExArguments")
        set(arguments) = iexArgumentList.fromArguments(arguments)

    var iexArgumentList: MutableList<String> = mutableListOf()

    private var mixArguments: String?
        get() = mixArgumentList.toArguments()
        set(arguments) = mixArgumentList.fromArguments(arguments)

    private var mixArgumentList: MutableList<String> = mutableListOf()

    override fun commandLine(): GeneralCommandLine {
        val workingDirectory = ensureWorkingDirectory()
        val module = ensureModule()
        val sdk = ensureMostSpecificSdk(module)
        val commandLine = Mix.commandLine(
                environment = envs,
                workingDirectory = workingDirectory,
                elixirSdk = sdk,
                erlArgumentList = erlArgumentList,
                iexArgumentList = iexArgumentList,
                project = project,
        )
        commandLine.addParameters(mixArgumentList)

        return commandLine
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
            SettingsEditorGroup<Configuration>().apply {
                this.addEditor("Configuration", Editor())
                this.addEditor("Interpreted Modules", org.elixir_lang.debugger.configuration.interpreted_modules.Editor<Configuration>())
            }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): State =
                State(environment, this)

    override fun readExternal(element: Element) {
        super.readExternal(element)
        element.readExternalArgumentList(ERL, erlArgumentList)
        element.readExternalArgumentList(IEX, iexArgumentList)
        element.readExternalArgumentList(MIX, mixArgumentList)
        workingDirectoryURL = element.readExternalWorkingDirectory()
        EnvironmentVariablesComponent.readExternal(element, envs)
        element.readExternalModule(this)
        element.readModuleFilters(moduleFilterList) { inheritApplicationModuleFilters ->
            this.inheritApplicationModuleFilters = inheritApplicationModuleFilters
        }
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        element.writeExternalArgumentList(ERL, erlArgumentList)
        element.writeExternalArgumentList(IEX, iexArgumentList)
        element.writeExternalArgumentList(MIX, mixArgumentList)
        element.writeExternalWorkingDirectory(workingDirectoryURL)
        EnvironmentVariablesComponent.writeExternal(element, envs)
        element.writeExternalModule(this)
        element.writeModuleFilters(moduleFilterList, inheritApplicationModuleFilters)
    }
}

private const val IEX = "iex"
