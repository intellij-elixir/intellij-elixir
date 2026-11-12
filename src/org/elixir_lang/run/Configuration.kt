package org.elixir_lang.run

import com.intellij.execution.CommonProgramRunConfigurationParameters
import com.intellij.execution.ExternalizablePath
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.ModuleBasedConfiguration
import com.intellij.execution.configurations.ParametersList
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.debugger.settings.stepping.ModuleFilter
import org.elixir_lang.mix.ensureMostSpecificSdk
import org.elixir_lang.run.configuration.Module
import org.elixir_lang.sdk.wsl.wslCompat
import org.elixir_lang.util.ReadActions
import org.jdom.Element
import com.intellij.openapi.util.io.FileUtil

fun ensureWorkingDirectory(project: Project) = project.basePath!!

fun ensureWorkingDirectory(project: Project, module: com.intellij.openapi.module.Module?): String {
    var workingDirectory: String? = null

    if (module != null) {
        workingDirectory = workingDirectory(module)
    }

    if (workingDirectory.isNullOrBlank()) {
        workingDirectory = ensureWorkingDirectory(project)
    }

    return workingDirectory
}

fun ensureWorkingDirectory(module: com.intellij.openapi.module.Module): String =
        ensureWorkingDirectory(module.project, module)

fun List<String>.toArguments(): String? {
    val joined = ParametersList.join(this)

    return joined.ifBlank {
        null
    }
}

fun MutableList<String>.fromArguments(arguments: String?) {
    this.clear()

    arguments
            ?.let { ParametersList.parse(it) }
            ?.let { this.addAll(it) }
}

fun Element.writeExternalArgumentList(command: String, argumentList: List<String>) {
    if (argumentList.isNotEmpty()) {
        val commandElement = Element(command)

        argumentList.forEach {
            val argumentElement = Element(ARGUMENT)
            argumentElement.addContent(it)
            commandElement.addContent(argumentElement)
        }

        addContent(commandElement)
    }
}

fun Element.readExternalArgumentList(command: String, argumentList: MutableList<String>) {
    argumentList.clear()

    val newArgumentList = getChild(command)?.let { commandElement ->
        commandElement.getChildren(ARGUMENT).map { argumentElement ->
            argumentElement.text
        }
    } ?: emptyList()

    argumentList.addAll(newArgumentList)
}

const val MODULE_FILTERS = "module-filters"
const val INHERIT_APPLICATION_MODULE_FILTERS = "inherit-application-module-filters"
const val MODULE_FILTER = "module-filter"
const val ENABLED = "enabled"
const val PATTERN = "pattern"

fun Element.writeModuleFilters(moduleFilterList: List<ModuleFilter>, inheritApplicationModuleFilters: Boolean) {
    val moduleFiltersElement = Element(MODULE_FILTERS)
    moduleFiltersElement.setAttribute(INHERIT_APPLICATION_MODULE_FILTERS, inheritApplicationModuleFilters.toString())

    moduleFilterList.forEach {
        moduleFiltersElement.writeModuleFilter(it)
    }

    addContent(moduleFiltersElement)
}

fun Element.writeModuleFilter(moduleFilter: ModuleFilter) {
    val moduleFilterElement = Element(MODULE_FILTER)
    moduleFilterElement.setAttribute(ENABLED, moduleFilter.enabled.toString())
    moduleFilterElement.setAttribute(PATTERN, moduleFilter.pattern)

    addContent(moduleFilterElement)
}

fun Element.readModuleFilters(
        moduleFilterList: MutableList<ModuleFilter>,
        inheritApplicationModuleFiltersSetter: (inheritApplicationModuleFilters: Boolean) -> Unit
) {
    moduleFilterList.clear()

    val moduleFiltersElement = getChild(MODULE_FILTERS)

    if (moduleFiltersElement != null) {
        inheritApplicationModuleFiltersSetter(
                moduleFiltersElement
                        .getAttributeValue(INHERIT_APPLICATION_MODULE_FILTERS)?.toBoolean()
                        ?: false
        )

        moduleFiltersElement
                .getChildren(MODULE_FILTER)
                .map { moduleFilterElement ->
                    val enabled = moduleFilterElement.getAttributeValue(ENABLED)!!.toBoolean()
                    val pattern = moduleFilterElement.getAttributeValue(PATTERN)!!

                    ModuleFilter(enabled, pattern)
                }
                .let {
                    moduleFilterList.addAll(it)
                }
    } else {
        inheritApplicationModuleFiltersSetter(false)
    }
}

/**
 * [ModuleBasedConfiguration.writeExternal] already serializes the module name as a `module` element, so this reuses
 * that element instead of adding a second one: the platform's own [com.intellij.execution.configurations.RunConfigurationModule.readExternal]
 * warns that the module was "serialized more than one time" when a scheme carries two.
 */
fun Element.writeExternalModule(configuration: Configuration) {
    val moduleName = configuration.configurationModule.moduleName

    if (moduleName.isNotBlank()) {
        ensureChild(MODULE).setAttribute(NAME, moduleName)
    }
}

/**
 * Only overrides what [ModuleBasedConfiguration.readExternal] already restored when the module can actually be
 * resolved: a configuration read before its module is loaded keeps the platform's pointer, which resolves later,
 * rather than being reset to no module at all.
 */
fun Element.readExternalModule(configuration: Configuration) {
    getChild(MODULE)
            ?.getAttributeValue(NAME)
            ?.let { moduleName -> configuration.configurationModule.findModule(moduleName) }
            ?.let { module -> configuration.configurationModule.module = module }
}

fun Element.writeExternalWorkingDirectory(workingDirectoryURL: String?) {
    writeExternalURL(WORKING_DIRECTORY, workingDirectoryURL)
}

fun Element.readExternalWorkingDirectory(): String? = readExternalURL(WORKING_DIRECTORY)

fun Element.writeExternalURL(childName: String, url: String?) {
    if (!url.isNullOrBlank()) {
        val childElement = ensureChild(childName)
        childElement.setAttribute(URL, url)
    }
}

fun Element.readExternalURL(childName: String) = getChild(childName)?.getAttributeValue(URL)

fun Element.ensureChild(name: String): Element = getChild(name) ?: addChild(name)

private fun Element.addChild(name: String): Element {
    val newChild = Element(name)
    addContent(newChild)

    return newChild
}

const val ELIXIR = "elixir"
const val ERL = "erl"
const val MIX = "mix"
private const val MODULE = "module"
private const val NAME = "name"
private const val PASS_PARENT_ENVS = "pass-parent-envs"
private const val URL = "url"
private const val WORKING_DIRECTORY = "working-directory"

abstract class Configuration(name: String, project: Project, configurationFactory: ConfigurationFactory) :
        ModuleBasedConfiguration<Module, Element>(name, Module(project), configurationFactory),
        CommonProgramRunConfigurationParameters {
    override fun getEnvs(): Map<String, String> = mutableEnvs

    override fun setEnvs(envs: Map<String, String>) {
        mutableEnvs.clear()
        mutableEnvs.putAll(envs)
    }

    abstract fun commandLine(): GeneralCommandLine

    fun ensureWorkingDirectoryAndSdk(): Pair<String, Sdk> =
        ReadActions.compute {
            ensureWorkingDirectory() to ensureMostSpecificSdk(ensureModule())
        }

    fun ensureModule(): com.intellij.openapi.module.Module =
            configurationModule.module ?: ensureModule(ensureWorkingDirectory(), project)

    override fun getWorkingDirectory(): String? = ExternalizablePath.localPathValue(workingDirectoryURL)

    fun ensureWorkingDirectory(): String {
        var workingDirectory = this.workingDirectory

        if (workingDirectory.isNullOrBlank()) {
            workingDirectory = ensureWorkingDirectory(project, configurationModule.module)
        }

        return workingDirectory
    }

    override fun setWorkingDirectory(localPath: String?) {
        workingDirectoryURL = ExternalizablePath.urlValue(localPath)
    }

    override fun isPassParentEnvs(): Boolean = _passParentEnvs

    override fun setPassParentEnvs(passParentEnvs: Boolean) {
        _passParentEnvs = passParentEnvs
    }

    /**
     * [com.intellij.execution.configuration.EnvironmentVariablesComponent.readExternal] restores only the variables
     * map, so the flag deciding whether the parent environment is inherited is read here. Subclasses get it through
     * their `super.readExternal` call.
     */
    override fun readExternal(element: Element) {
        super.readExternal(element)
        _passParentEnvs = element.getAttributeValue(PASS_PARENT_ENVS)?.toBoolean() ?: false
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)

        if (_passParentEnvs) {
            element.setAttribute(PASS_PARENT_ENVS, true.toString())
        }
    }

    override fun getValidModules(): Collection<com.intellij.openapi.module.Module> =
            project.let { ModuleManager.getInstance(it) }.modules.asList()

    @RequiresReadLock
    fun sdkPaths(): List<String> = ensureModule().sdkPaths()

    protected val mutableEnvs = mutableMapOf<String, String>()
    private var _passParentEnvs: Boolean = false
    protected var workingDirectoryURL: String? = null
}

private const val ARGUMENT = "argument"

private fun ensureModule(workingDirectory: String, project: Project): com.intellij.openapi.module.Module {
    val virtualFile = wslCompat.findFileByPath(FileUtil.toSystemIndependentName(workingDirectory), refresh = true)
            ?: error("Working directory ($workingDirectory) could not be mapped to a VirtualFile")

    return ModuleUtilCore.findModuleForFile(virtualFile, project)
            ?: error("Working directory ($workingDirectory) VirtualFile could not be mapped to a Module")
}

private fun workingDirectory(module: com.intellij.openapi.module.Module): String? =
        ModuleRootManager.getInstance(module).contentRoots.firstOrNull()?.path

@RequiresReadLock
private fun com.intellij.openapi.module.Module.sdkPaths(): List<String> = ensureMostSpecificSdk(this).paths()

@RequiresReadLock
private fun Sdk.paths(): List<String> =
        rootProvider.getFiles(OrderRootType.CLASSES)
            .mapNotNull { it.canonicalPath }
            .map { wslCompat.maybeParseWindowsUncPath(it) }
