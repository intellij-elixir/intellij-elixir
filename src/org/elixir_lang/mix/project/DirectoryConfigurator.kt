package org.elixir_lang.mix.project

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.DirectoryProjectConfigurator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.elixir_lang.mix.Project.createModulesForOtpApps
import org.elixir_lang.mix.Project.makeElixirModule
import org.elixir_lang.mix.Project.syncDepsOnceAdded

/**
 * Extends [DirectoryProjectConfigurator.AsyncDirectoryProjectConfigurator] because the scan for OTP
 * apps is too slow for the EDT and its result decides everything below it: only [configure] is
 * awaited by `PlatformProjectOpenProcessor.runDirectoryProjectConfigurators`.
 */
class DirectoryConfigurator : DirectoryProjectConfigurator.AsyncDirectoryProjectConfigurator() {
    companion object {
        private val LOG = Logger.getInstance(DirectoryConfigurator::class.java)
    }

    override suspend fun configure(
        project: Project,
        baseDir: VirtualFile,
        moduleRef: Ref<Module>,
        isProjectCreatedWithWizard: Boolean
    ) {
        LOG.debug("configuring $baseDir for project $project, created with wizard: $isProjectCreatedWithWizard")

        // The indicator coroutineToIndicator installs is backed by this coroutine's Job, so
        // cancelling the project open cancels the scan.
        val foundOtpApps = withContext(Dispatchers.IO) {
            coroutineToIndicator { indicator ->
                org.elixir_lang.mix.Project.findOtpApps(baseDir, indicator)
            }
        }

        val (rootApps, appsBelow) = foundOtpApps.partition { it.root == baseDir }
        for (otpApp in rootApps) {
            configureRootOtpApp(project, otpApp)
        }

        // isProjectCreatedWithWizard is also true for an attach's throwaway project, whose app modules nothing loads and
        // whose ask nobody sees.
        if (!isProjectCreatedWithWizard) {
            configureOtpAppsBelow(project, appsBelow, baseDir)
            if (foundOtpApps.isNotEmpty()) {
                askAfterOpen(project)
            }
        }
    }

    private suspend fun configureRootOtpApp(project: Project, otpApp: OtpApp) {
        val module = readAction { ModuleManager.getInstance(project).modules.firstOrNull() }

        if (module == null) {
            LOG.debug("no module to attach the Elixir facet to in project $project")
            return
        }

        // The facet check shares the write action with the mutations it guards, so a second
        // configurator pass cannot add the facet twice.
        val added = edtWriteAction { makeElixirModule(module, otpApp.root) }

        if (added) {
            syncDepsOnceAdded(project, listOf(module))
        }
    }

    /** Gives each of [otpApps] its own module, as IntelliJ IDEA's import makes them. */
    private suspend fun configureOtpAppsBelow(project: Project, otpApps: List<OtpApp>, baseDir: VirtualFile) {
        if (otpApps.isEmpty()) return

        withContext(Dispatchers.EDT) {
            createModulesForOtpApps(project, otpApps, null, { _, _ -> }, baseDir)
        }
    }
}
