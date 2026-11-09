package org.elixir_lang.sdk.elixir

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.roots.ui.configuration.ProjectStructureConfigurable

/** What an Elixir SDK's page needs from IntelliJ IDEA's Project Structure, which the small IDEs don't have. */
interface SdkPageInDialog {
    /** The project whose Project Structure, its UI up, is over [sdkModel]; null under any other host of the page. */
    fun projectOf(sdkModel: SdkModel): Project?

    companion object {
        fun getInstance(): SdkPageInDialog =
            ApplicationManager.getApplication().getService(SdkPageInDialog::class.java)
    }
}

internal class NoProjectStructureSdkPage : SdkPageInDialog {
    override fun projectOf(sdkModel: SdkModel): Project? = null
}

internal class ProjectStructureSdkPage : SdkPageInDialog {
    /**
     * `isUiInitialized` tells Project Structure from `ProjectJdksConfigurable`, whose one-argument constructor creates the
     * same service and takes its model.
     */
    override fun projectOf(sdkModel: SdkModel): Project? {
        val manager = ProjectManager.getInstance()
        return (manager.openProjects.asSequence() + manager.defaultProject).firstOrNull { project ->
            project.serviceIfCreated<ProjectStructureConfigurable>()?.let { it.isUiInitialized && it.projectJdksModel === sdkModel } == true
        }
    }
}
