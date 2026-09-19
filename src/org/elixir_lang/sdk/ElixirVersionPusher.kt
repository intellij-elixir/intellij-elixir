package org.elixir_lang.sdk

import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.impl.FilePropertyPusher
import com.intellij.openapi.roots.impl.PushedFilePropertiesUpdater
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.FileAttribute
import com.intellij.psi.FilePropertyKey
import com.intellij.psi.FilePropertyKeyImpl
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.elixir_lang.ElixirFileType
import org.elixir_lang.isElixirModule
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.sdk.elixir.ElixirSdkLookup
import org.elixir_lang.sdk.elixir.sdk

/**
 * Pushes the Elixir version of each Elixir module's SDK onto the module's directories, where it is persisted, so a file
 * is parsed and indexed at that version from the start of a session - before [SdkVersionsStore], which is not
 * persisted, has read anything.
 *
 * All but [initExtra] run under a read lock, some inside a VFS write action, so they must not do I/O.
 */
@Suppress("UnstableApiUsage") // The platform's only way to make an index input follow a module's SDK.
internal class ElixirVersionPusher : FilePropertyPusher<String> {
    override fun getFilePropertyKey(): FilePropertyKey<String> = KEY

    override fun pushDirectoriesOnly(): Boolean = true

    override fun getDefaultValue(): String = ElixirLanguageLevel.FALLBACK.elixirVersion

    override fun getImmediateValue(module: Module): String? = versionOf(module)

    /**
     * Asked before the module: a home the store has not read yet keeps the version already on the directory. Answering
     * the default instead would reindex everything under it now, and again once the home is read.
     */
    override fun getImmediateValue(project: Project, file: VirtualFile?): String? {
        val directory = file ?: return null
        val module = ProjectFileIndex.getInstance(project).getModuleForFile(directory, false) ?: return null

        return if (versionOf(module) == null) KEY.getPersistentValue(directory) else null
    }

    @RequiresReadLock
    override fun acceptsDirectory(file: VirtualFile, project: Project): Boolean =
        ProjectFileIndex.getInstance(project).getModuleForFile(file, false)?.isElixirModule() == true

    override fun persistAttribute(project: Project, fileOrDir: VirtualFile, value: String) {
        if (KEY.setPersistentValue(fileOrDir, value)) {
            PushedFilePropertiesUpdater.getInstance(project).filePropertiesChanged(fileOrDir, ::isParsedAsElixir)
        }
    }

    /**
     * Before the project's first scan, so what it pushes is the version the SDK reports rather than the default. A
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

    companion object {
        val KEY: FilePropertyKey<String> = FilePropertyKeyImpl.createPersistentStringKey("elixir.version", attribute())

        /**
         * A `FileAttribute` id may be registered once per JVM, and loading the plugin again without a restart runs this
         * again, so the attribute is kept where it outlives the plugin's class loader.
         */
        internal fun attribute(): FileAttribute =
            System.getProperties().computeIfAbsent(ATTRIBUTE_ID) { FileAttribute(ATTRIBUTE_ID, 1, true) } as FileAttribute

        private const val ATTRIBUTE_ID = "elixir_version"

        /**
         * `.exs` and `.leex` are covered by subclasses of the types checked here. A `.beam`'s stubs are built from the
         * binary, not parsed.
         */
        internal fun isParsedAsElixir(file: VirtualFile): Boolean = when (file.fileType) {
            is ElixirFileType, is org.elixir_lang.eex.file.Type, is org.elixir_lang.heex.file.Type -> true
            else -> false
        }

        /** `null` until the store has read the SDK's home, which is not the same as having no SDK. */
        @RequiresReadLock
        internal fun versionOf(module: Module): String? {
            val sdk = ElixirSdkLookup.resolve(module).sdk ?: return ElixirLanguageLevel.FALLBACK.elixirVersion

            return SdkVersionsStore.getInstance().elixirVersions(sdk.homePath)?.elixirVersion
        }
    }
}
