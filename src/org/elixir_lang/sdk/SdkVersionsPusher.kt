package org.elixir_lang.sdk

import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.impl.FilePropertyPusher
import com.intellij.openapi.roots.impl.PushedFilePropertiesUpdater
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FilePropertyKey
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.ElixirFileType
import org.elixir_lang.isElixirModule
import org.elixir_lang.language_level.ElixirLanguageLevel

/**
 * Pushes the Elixir and Erlang/OTP versions of each Elixir module's SDK onto the module's directories, where they are
 * persisted, so a file is parsed and indexed at them from the start of a session - before [SdkVersionsStore], which is
 * not persisted, has read anything. Both are one value, written by [PushedVersions], since the platform reads one value
 * per pusher: for a directory with none of its own, and for a file's stub.
 *
 * All but [initExtra] run under a read lock, some inside a VFS write action, so they must not do I/O.
 */
@Suppress("UnstableApiUsage") // The platform's only way to make an index input follow a module's SDK.
internal class SdkVersionsPusher : FilePropertyPusher<String> {
    override fun getFilePropertyKey(): FilePropertyKey<String> = PushedVersions.KEY

    override fun pushDirectoriesOnly(): Boolean = true

    override fun getDefaultValue(): String = PushedVersions.encode(ElixirLanguageLevel.FALLBACK)

    override fun getImmediateValue(module: Module): String? = PushedVersions.versionsOf(module)

    /**
     * Asked before the module, and alone for a directory created later, so a directory answers its own module's
     * versions rather than inheriting another module's. While a home is unread, a directory keeps the versions it
     * holds: answering the default instead would reindex everything under it now, and again once the home is read. One
     * with none inherits from a parent in content in the same module, and otherwise gets the Elixir version with its
     * build's OTP major, since a paired Erlang SDK that was removed is never read, or the default while the Elixir
     * home is unread too.
     */
    override fun getImmediateValue(project: Project, file: VirtualFile?): String? {
        val directory = file ?: return null
        val fileIndex = ProjectFileIndex.getInstance(project)
        val module = fileIndex.getModuleForFile(directory, false) ?: return null
        PushedVersions.versionsOf(module)?.let { return it }
        PushedVersions.KEY.getPersistentValue(directory)?.let { return it }

        val parent = directory.parent
        if (parent != null && fileIndex.isInContent(parent) && fileIndex.getModuleForFile(parent, false) == module) {
            return null
        }

        return PushedVersions.unpairedVersionsOf(module) ?: defaultValue
    }

    @RequiresReadLock
    override fun acceptsDirectory(file: VirtualFile, project: Project): Boolean =
        ProjectFileIndex.getInstance(project).getModuleForFile(file, false)?.isElixirModule() == true

    override fun persistAttribute(project: Project, fileOrDir: VirtualFile, value: String) {
        if (PushedVersions.KEY.setPersistentValue(fileOrDir, value)) {
            PushedFilePropertiesUpdater.getInstance(project).filePropertiesChanged(fileOrDir, ::isParsedAsElixir)
        }
    }

    /**
     * Before the project's first scan, so what it pushes is the versions the SDK reports rather than the default. A
     * home an earlier project already read publishes nothing now, so the versions are compared here too.
     */
    override fun initExtra(project: Project) {
        SdkVersionsFiller.fillUsedByBlocking(project)
        project.service<ElixirLanguageLevelPushes>().request()
    }

    /** Called inside the roots change's write action, so the push is left to the project's scope. */
    override fun afterRootsChanged(project: Project) {
        project.service<ElixirLanguageLevelPushes>().request()
    }
}

/**
 * `.exs` and `.leex` are covered by subclasses of the types checked here. A `.beam`'s stubs are built from the binary.
 */
internal fun isParsedAsElixir(file: VirtualFile): Boolean = when (file.fileType) {
    is ElixirFileType, is org.elixir_lang.eex.file.Type, is org.elixir_lang.heex.file.Type -> true
    else -> false
}
