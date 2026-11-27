package org.elixir_lang.sdk.choice

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.testFramework.replaceService
import org.elixir_lang.sdk.elixir.SdkPageInDialog
import java.awt.Component

/**
 * The SDK dialog, scripted: each open is recorded, and [save], when given, is what its OK does to the SDK it opened on;
 * with none, it is cancelled. A made SDK is taken into the table for it and out again at its Cancel, as on IntelliJ IDEA,
 * unless [takesIn] is false, as on a small IDE, whose page keeps it in its own model until its OK, here [save].
 */
internal class ScriptedSdkDialog(private val takesIn: Boolean = true, private val save: ((Sdk) -> Unit)?) : SdkPageInDialog {
    /** An open: the SDK by name, and whether as new. */
    data class Opened(val name: String, val made: Boolean)

    val opened = mutableListOf<Opened>()

    override fun projectOf(sdkModel: SdkModel): Project? = null

    override fun editInSdkDialog(project: Project, sdk: Sdk, parent: Component, made: Boolean): Boolean {
        opened += Opened(sdk.name, made)
        return takenInFor(sdk, made && takesIn) {
            save?.invoke(sdk)
            save != null
        }
    }

    /** In place of the application's [SdkPageInDialog] until [disposable] is disposed. */
    fun install(disposable: Disposable): ScriptedSdkDialog = also {
        ApplicationManager.getApplication().replaceService(SdkPageInDialog::class.java, it, disposable)
    }
}
