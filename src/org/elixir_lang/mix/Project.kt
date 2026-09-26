package org.elixir_lang.mix

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModifiableModuleModel
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.elixir_lang.jps.shared.ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID
import org.elixir_lang.mix.project.CANONICAL_FOLDER_MARKS
import org.elixir_lang.mix.project.FolderMark
import org.elixir_lang.mix.project.OtpApp
import org.elixir_lang.mix.sync.MixDepsSyncService
import org.elixir_lang.mix.sync.SyncRequest
import java.io.EOFException
import java.io.File

object Project {
    const val MIX_EXS = "mix.exs"
    // Qualified because an explicit import outranks a declaration in the same file: the bare name
    // resolves to the imported com.intellij.openapi.project.Project.
    private val LOG = Logger.getInstance(org.elixir_lang.mix.Project::class.java)

    fun addSourceDirToContent(
        content: ContentEntry,
        root: VirtualFile,
        sourceDir: String,
        test: Boolean
    ) {
        content.addSourceFolder("${root.url}/$sourceDir", test)
    }

    fun excludeDirFromContent(content: ContentEntry, root: VirtualFile, excludedDir: String) {
        content.addExcludeFolder("${root.url}/$excludedDir")
    }

    fun findOtpApps(root: VirtualFile, indicator: ProgressIndicator): List<OtpApp> {
        val importedOtpApps = mutableSetOf<OtpApp>()

        // synchronous and recursive
        root.refresh(false, true)

        VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Any>() {
            override fun visitFile(file: VirtualFile): Boolean {
                LOG.debug("visiting $file")
                indicator.checkCanceled()

                if (file.isDirectory) {
                    indicator.text2 = file.path

                    if (isAssetsOrBuildOrConfigOrDepsOrTestsDirectory(root.path, file.path)) {
                        return false
                    }
                }

                createImportedOtpApp(file)?.let { importedOtpApps.add(it) }

                return true
            }
        })

        return importedOtpApps.sortedWith(Comparator { o1, o2 ->
            val nameCompareResult = String.CASE_INSENSITIVE_ORDER.compare(o1.name, o2.name)

            if (nameCompareResult == 0) {
                String.CASE_INSENSITIVE_ORDER.compare(o1.root.path, o2.root.path)
            } else {
                nameCompareResult
            }
        })
    }

    /**
     * Computes unique IntelliJ module names for a list of OTP apps, disambiguating when
     * multiple apps share the same name (e.g., umbrella root and child app both named "emqx").
     *
     * Apps with unique names keep their original name. For collisions, the app at [projectRoot]
     * keeps its name and others get a suffix derived from their relative path.
     */
    fun moduleNameForOtpApps(otpApps: List<OtpApp>, projectRoot: VirtualFile? = null): Map<OtpApp, String> {
        val result = mutableMapOf<OtpApp, String>()
        val byName = otpApps.groupBy { it.name.lowercase() }

        for ((_, apps) in byName) {
            if (apps.size == 1) {
                result[apps.first()] = apps.first().name
            } else {
                for (app in apps) {
                    val isRoot = projectRoot != null && app.root.path == projectRoot.path
                    if (isRoot) {
                        result[app] = app.name
                    } else if (projectRoot != null) {
                        val relativePath = app.root.path
                            .removePrefix(projectRoot.path)
                            .trimStart('/')
                            .replace('/', '-')
                        result[app] = "${app.name}-${relativePath}"
                    } else {
                        result[app] = "${app.name}-${app.root.name}"
                    }
                }
            }
        }

        return result
    }

    @RequiresEdt
    fun createModulesForOtpApps(
        project: Project,
        otpApps: List<OtpApp>,
        modifiableModuleModelFactory: () -> ModifiableModuleModel,
        rootModelModifier: (OtpApp, ModifiableRootModel) -> Unit = { _, _ -> },
        projectRoot: VirtualFile? = null
    ): List<Module> =
        if (otpApps.isNotEmpty()) {
            val moduleModel = modifiableModuleModelFactory()
            val moduleNames = moduleNameForOtpApps(otpApps, projectRoot)
            val createdRootModels = otpApps.mapNotNull { createModuleForOtpApp(it, moduleModel, rootModelModifier, moduleNames[it] ?: it.name) }

            if (createdRootModels.isNotEmpty()) {
                // Use WriteAction.run since this is called from EDT via importToProject
                // runBlockingCancellable is forbidden on EDT as it doesn't pump the event queue
                WriteAction.run<Throwable> {
                    for (rootModel in createdRootModels) {
                        rootModel.commit()
                    }

                    moduleModel.commit()
                }
                project.service<MixDepsSyncService>().enqueue(SyncRequest.All)
            }

            createdRootModels.map { it.module }
        } else {
            emptyList()
        }

    private fun createModuleForOtpApp(
        otpApp: OtpApp,
        moduleModel: ModifiableModuleModel,
        rootModelModifier: (OtpApp, ModifiableRootModel) -> Unit,
        moduleName: String
    ): ModifiableRootModel? {
        val ideaModuleDir = otpApp.root
        val ideaModuleFile = "${ideaModuleDir.canonicalPath}${File.separator}/${moduleName}.iml"
        val module = moduleModel.newModule(ideaModuleFile, ELIXIR_MODULE_TYPE_ID)
        otpApp.module = module

        return if (otpApp.ideaModuleFile == null) {
            val rootModel = ModuleRootManager.getInstance(module).modifiableModel

            addFolders(rootModel, otpApp.root)
            rootModelModifier(otpApp, rootModel)

            rootModel
        } else {
            null
        }
    }

    fun addFolders(modifiableRootModel: ModifiableRootModel, root: VirtualFile) {
        clearExcludeOutput(modifiableRootModel)

        val content = modifiableRootModel.addContentEntry(root)

        for (canonicalFolder in CANONICAL_FOLDER_MARKS) {
            when (canonicalFolder.folderMark) {
                FolderMark.SOURCES -> addSourceDirToContent(content, root, canonicalFolder.relativePath, false)
                FolderMark.TEST_SOURCES -> addSourceDirToContent(content, root, canonicalFolder.relativePath, true)
                FolderMark.EXCLUDED -> excludeDirFromContent(content, root, canonicalFolder.relativePath)
            }
        }
    }

    /**
     * Turns off "exclude compiler output" for an Elixir module.
     *
     * Elixir compiles to `_build`, never to IntelliJ's compiler output, so excluding that output
     * buys nothing - and the plugin ships a project converter whose only job is to strip the
     * resulting `<exclude-output/>` from `ELIXIR_MODULE`s. Any module keeping the flag is therefore
     * offered for "conversion" the next time its project is opened.
     *
     * Setting it explicitly matters even though `false` is the value we want. `JavaSettingsSerializer`
     * writes `<exclude-output/>` for a module with **no** Java settings at all, and
     * `JpsJavaModuleExtensionBridge.isExcludeOutput()` defaults to `true` for the same reason, so
     * leaving the flag untouched produces the tag rather than omitting it. `JavaModuleBuilder`
     * additionally sets it to `true` outright.
     *
     * Called from [addFolders], which every path that configures an Elixir module root goes
     * through: the New Project wizard and the New Module wizard via
     * [org.elixir_lang.module.ElixirModuleBuilder], and the import wizard and project-open
     * processor via [createModulesForOtpApps].
     *
     * `CompilerModuleExtension` ships with the Java plugin, so it is absent in a small IDE such as
     * RubyMine - where there is no `<exclude-output/>` to suppress in the first place.
     */
    private fun clearExcludeOutput(modifiableRootModel: ModifiableRootModel) {
        modifiableRootModel
            .getModuleExtension(CompilerModuleExtension::class.java)
            ?.setExcludeOutput(false)
    }

    /**
     * Refreshes the umbrella sub-app directories under [root] so their `mix.exs` is visible to
     * [VirtualFile.findChild].
     *
     * `mix new` writes a sub-app from an external process, so VFS can hold a child list for it
     * containing only the entries the IDE itself touched - no `mix.exs` - and `findChild` answers
     * from that list without going to disk. [findOtpApps] avoids this by refreshing its own root
     * before scanning; the folder-mark scans need the same.
     *
     * Refreshes `apps/` and its immediate sub-directories rather than recursing, so the cost stays
     * bounded on umbrellas whose children carry `deps/` and `_build/`. A sub-app that appears after
     * `apps/` was last refreshed is therefore picked up on the following scan.
     *
     * @param async when false this blocks until the refresh completes, so it must not be called
     *   under a read or write lock - a synchronous refresh is not permitted inside one.
     */
    fun refreshUmbrellaSubApps(root: VirtualFile, async: Boolean) {
        val appsDir = root.findChild("apps") ?: return
        val targets = (listOf(appsDir) + appsDir.children.filter(VirtualFile::isDirectory)).toTypedArray()

        VfsUtil.markDirtyAndRefresh(async, false, true, *targets)
    }

    private fun createImportedOtpApp(appRoot: VirtualFile): OtpApp? =
        try {
            appRoot.findChild(MIX_EXS)
        } catch (_: EOFException) {
            null
        }?.let {
            OtpApp(appRoot, it)
        }

    private fun isAssetsOrBuildOrConfigOrDepsOrTestsDirectory(projectRootPath: String, path: String): Boolean {
        return (path.endsWith("/assets")
                || "$projectRootPath/_build" == path
                || "$projectRootPath/config" == path
                || "$projectRootPath/deps" == path
                || "$projectRootPath/tests" == path)
    }
}
