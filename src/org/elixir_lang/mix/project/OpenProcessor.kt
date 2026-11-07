package org.elixir_lang.mix.project

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.projectImport.ProjectImportBuilder
import com.intellij.projectImport.ProjectOpenProcessorBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.elixir_lang.mix.project._import.Builder
import com.intellij.openapi.project.Project as IdeaProject
import org.elixir_lang.mix.Project as MixProject

private val LOG = logger<OpenProcessor>()
/**
 * Created by zyuyou on 15/7/1.
 */
class OpenProcessor : ProjectOpenProcessorBase<Builder>() {
    override val supportedExtensions = arrayOf(MixProject.MIX_EXS)

    override fun doGetBuilder(): Builder = ProjectImportBuilder.EXTENSIONS_POINT_NAME.findExtensionOrFail(Builder::class.java)

    override fun canOpenProject(file: VirtualFile): Boolean {
        return if (file.isDirectory) {
            file.children?.any { it.name in supportedExtensions } ?: false
        } else {
            file.name in supportedExtensions
        }
    }

    override suspend fun openProjectAsync(
        virtualFile: VirtualFile,
        projectToClose: IdeaProject?,
        forceOpenInNewFrame: Boolean
    ): IdeaProject? {
        val projectRoot = if (virtualFile.isDirectory) virtualFile else virtualFile.parent

        // Pre-scan for OTP apps in background BEFORE switching to EDT
        // This moves the slow VfsUtilCore.visitChildrenRecursively() off EDT
        LOG.debug("Pre-scanning project root: ${projectRoot.path}")
        val foundApps = withContext(Dispatchers.IO) {
            coroutineToIndicator {
                MixProject.findOtpApps(projectRoot, com.intellij.openapi.progress.ProgressManager.getInstance().progressIndicator
                    ?: com.intellij.openapi.progress.EmptyProgressIndicator())
            }
        }
        LOG.debug("Pre-scan found ${foundApps.size} OTP apps")

        // Store pre-scanned results in the builder
        builder.setPreScannedApps(projectRoot, foundApps)

        // The platform's ProjectOpenProcessorBase.openProjectAsync dispatches to the EDT itself on
        // 2026.1+ (it wraps its EDT-bound work in withContext(Dispatchers.EDT) and uses
        // withRawProgressReporter), so the old caller-side EDT hop for 2025.3's
        // runBlockingModalWithRawProgressReporter is no longer needed.
        return super.openProjectAsync(virtualFile, projectToClose, forceOpenInNewFrame)
    }

    override fun doQuickImport(file: VirtualFile, wizardContext: WizardContext): Boolean {
        LOG.debug("doQuickImport(file=${file.path})")

        val projectRoot = file.parent
        wizardContext.projectName = projectRoot.name
        builder.setProjectRoot(projectRoot)

        // Use pre-scanned results (no slow I/O on EDT)
        val foundApps = builder.list
        builder.setList(foundApps)

        // Mark as importing project so Builder.commit() enables Mix compiler
        // This matches the behavior of the Import Wizard
        builder.setIsImportingProject(true)

        // The builder is application-wide, and a cancelled import wizard leaves its SDK step's pick on it.
        builder.elixirSdk = null

        builder.openContext = wizardContext

        return true
    }
}
