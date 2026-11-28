package org.elixir_lang.sdk.erlang_dependent

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.testFramework.replaceService
import org.elixir_lang.sdk.elixir.SdkList
import org.elixir_lang.sdk.elixir.SdkPageInDialog
import java.awt.Component
import javax.swing.JComponent

/**
 * A Project Structure on [onProject] over every model, as no bare `ProjectSdksModel` otherwise is, or none when null, whose
 * SDK list holds each SDK editor's Name, which reaches the copy only when the editor applies, and records the SDKs the list
 * is moved to.
 */
internal class ProjectStructureFake(private val onProject: Project?) : SdkPageInDialog, SdkList {
    val selectedInList = mutableListOf<Sdk>()
    private val names = mutableMapOf<Sdk, String>()

    /** Each name the page wrote, in order. */
    val renames = mutableListOf<String>()

    override fun projectOf(sdkModel: SdkModel): Project? = onProject

    override fun editInSdkDialog(project: Project, sdk: Sdk, parent: Component, made: Boolean): Boolean = false

    override fun listOf(page: JComponent, sdk: Sdk): SdkList = this

    override fun nameOf(sdk: Sdk): String = names[sdk] ?: sdk.name

    override fun rename(sdk: Sdk, name: String) {
        names[sdk] = name
        renames += name
    }

    override fun select(sdk: Sdk) {
        selectedInList += sdk
    }

    /** [name] typed into [sdk]'s Name field. */
    fun type(sdk: Sdk, name: String) {
        names[sdk] = name
    }

    companion object {
        /** The fake, in place of the application's [SdkPageInDialog] until [parentDisposable] is disposed. */
        fun install(project: Project?, parentDisposable: Disposable): ProjectStructureFake =
            ProjectStructureFake(project).also {
                ApplicationManager.getApplication().replaceService(SdkPageInDialog::class.java, it, parentDisposable)
            }
    }
}
