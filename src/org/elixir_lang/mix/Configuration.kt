package org.elixir_lang.mix

import com.intellij.execution.Executor
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.execution.configurations.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunConfigurationWithSuppressedDefaultRunAction
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.options.SettingsEditorGroup
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import org.elixir_lang.Mix
import org.elixir_lang.debugger.Modules
import org.elixir_lang.debugger.configuration.Debuggable
import org.elixir_lang.debugger.settings.stepping.ModuleFilter
import org.elixir_lang.run.*
import org.jdom.Element

/**
 * https://github.com/ignatov/intellij-erlang/blob/master/src/org/intellij/erlang/rebar/runner/RebarRunConfigurationBase.java
 */
open class Configuration(name: String, project: Project, configurationFactory: ConfigurationFactory) :
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

        debugged.elixirArgumentList.addAll(elixirArgumentList)

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

    fun setProgramParameters(value: MutableList<String>) {
        mixArgumentList = value
    }

    private var erlArgumentList: MutableList<String> = mutableListOf()

    var erlArguments: String?
        get() = erlArgumentList.toArguments()
        set(arguments) = erlArgumentList.fromArguments(arguments)

    private var elixirArgumentList: MutableList<String> = mutableListOf()

    var elixirArguments: String?
        get() = elixirArgumentList.toArguments()
        set(arguments) = elixirArgumentList.fromArguments(arguments)

    private var mixArguments: String?
        get() = mixArgumentList.toArguments()
        set(arguments) = mixArgumentList.fromArguments(arguments)

    private var mixArgumentList: MutableList<String> = mutableListOf()

    override fun commandLine(): GeneralCommandLine {
        val workingDirectory = ensureWorkingDirectory()
        val module = ensureModule()
        val sdk = ensureMostSpecificSdk(module)
        val commandLine = Mix.commandLine(
            envs,
            workingDirectory,
            sdk,
            erlArgumentList,
            elixirArgumentList,
            project = project,
        )
        commandLine.addParameters(mixArgumentList)

        return commandLine
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
            SettingsEditorGroup<Configuration>().apply {
                this.addEditor("Configuration", org.elixir_lang.mix.configuration.Editor())
                this.addEditor("Interpreted Modules", org.elixir_lang.debugger.configuration.interpreted_modules.Editor<Configuration>())
            }

    /**
     * getModules changed to getSearchScope in
     * https://github.com/JetBrains/intellij-community/commit/4daac1c8cdb0cb98bbb26df7d4900c766626a742#diff-5fa0d6d7f6e6058e338ccec4efdb4596,
     * which affects IntelliJ 2016.*
     *
     * @return scope where to search sources for this configuration
     */
    override fun getSearchScope(): GlobalSearchScope? {
        return GlobalSearchScope.projectScope(project)
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
            State(environment, this)

    override fun readExternal(element: Element) {
        super.readExternal(element)
        element.readExternalArgumentList(ERL, erlArgumentList)
        element.readExternalArgumentList(ELIXIR, elixirArgumentList)
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
        element.writeExternalArgumentList(ELIXIR, elixirArgumentList)
        element.writeExternalArgumentList(MIX, mixArgumentList)
        element.writeExternalWorkingDirectory(workingDirectoryURL)
        EnvironmentVariablesComponent.writeExternal(element, envs)
        element.writeExternalModule(this)
        element.writeModuleFilters(moduleFilterList, inheritApplicationModuleFilters)
    }
}
