package org.elixir_lang.mix.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFileFactory
import org.elixir_lang.ElixirScriptFileType
import org.elixir_lang.mix.Project
import org.elixir_lang.mix.ProjectKeywords
import org.elixir_lang.psi.ElixirFile
import java.io.IOException
import java.nio.file.Paths

private fun app(appMixFile: VirtualFile): String =
    try {
        app(appMixFile, text(appMixFile))
    } catch (_: IOException) {
        appFromPath(appMixFile)
    }

private fun app(appMixFile: VirtualFile, appMixFileText: String): String =
    computeReadAction { ProjectKeywords.app(elixirFile(appMixFileText)) } ?: appFromPath(appMixFile)

private fun appFromPath(appMixFile: VirtualFile): String = Paths.get(appMixFile.path).parent.fileName.toString()

fun <T> computeReadAction(computable: Computable<T>): T =
        ApplicationManager.getApplication().runReadAction(computable)

private fun elixirFile(text: String): ElixirFile {
    val defaultProject = ProjectManager.getInstance().defaultProject

    return PsiFileFactory
        .getInstance(defaultProject)
        .createFileFromText(Project.MIX_EXS, ElixirScriptFileType.INSTANCE, text) as ElixirFile
}

private fun text(virtualFile: VirtualFile): String = computeReadAction {
    VfsUtil.loadText(virtualFile)
}

class OtpApp(val root: VirtualFile, appMixFile: VirtualFile) {
    val deps = mutableSetOf<String>()
    var ideaModuleFile: VirtualFile? = null
    var module: Module? = null
    val name = app(appMixFile)

    override fun toString(): String = "$name ($root)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false

        val that = other as OtpApp

        return name == that.name && root == that.root
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + root.hashCode()
        return result
    }
}
