package org.elixir_lang.mix

import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.facet.impl.FacetUtil
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModifiableModuleModel
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.ModuleListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import org.elixir_lang.Facet
import org.elixir_lang.jps.shared.ElixirModuleTypeId.ELIXIR_MODULE_TYPE_ID
import org.elixir_lang.mix.project.CANONICAL_FOLDER_MARKS
import org.elixir_lang.mix.project.FolderMark
import org.elixir_lang.mix.project.OtpApp
import org.elixir_lang.mix.sync.MixDepsSyncService
import org.elixir_lang.mix.sync.SyncRequest
import org.elixir_lang.sdk.ProcessOutput.isSmallIde
import org.elixir_lang.util.ElixirCoroutineService
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

                    // The scanned root is never skipped, whatever its name.
                    if (file != root && (isHidden(file) || isAssetsOrBuildOrConfigOrDepsOrTestsDirectory(root, file))) {
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
                    result[app] = if (isRoot) app.name else pathName(app, projectRoot)
                }
            }
        }

        return result
    }

    /** [app]'s name suffixed with its path from [projectRoot], or with its folder's name when that path is empty or unknown. */
    private fun pathName(app: OtpApp, projectRoot: VirtualFile?): String {
        val relativePath = projectRoot?.let { app.root.path.removePrefix(it.path).trimStart('/').replace('/', '-') }
        return "${app.name}-${if (relativePath.isNullOrEmpty()) app.root.name else relativePath}"
    }

    /**
     * The modules of [otpApps], in [handedModuleModel] or else the project's: a module rooted at the app under its name
     * or path name is reused, and each other app with no module file of its own is created and set up by
     * [rootModelModifier]. A handed model is left to its caller to commit, as Project Structure commits its own on OK;
     * the project's is committed here. Each module gets the Elixir facet where its type takes one, one new in a handed
     * model into that model, and a reused one that takes it gets its Mix folders marked too.
     * The apps' Mix dependency libraries are synced once their model commits.
     */
    @RequiresEdt
    fun createModulesForOtpApps(
        project: Project,
        otpApps: List<OtpApp>,
        handedModuleModel: ModifiableModuleModel?,
        rootModelModifier: (OtpApp, ModifiableRootModel) -> Unit,
        projectRoot: VirtualFile?
    ): List<Module> =
        if (otpApps.isNotEmpty()) {
            val moduleModel = handedModuleModel ?: ModuleManager.getInstance(project).getModifiableModel()
            val moduleNames = moduleNameForOtpApps(otpApps, projectRoot)
            val (modules, rootModels) = otpApps
                .map { createModuleForOtpApp(it, moduleModel, rootModelModifier, moduleNames.getValue(it), projectRoot) }
                .unzip()

            WriteAction.run<Throwable> {
                for (rootModel in rootModels.filterNotNull()) {
                    rootModel.commit()
                }

                if (handedModuleModel == null) {
                    moduleModel.commit()
                }

                // A new module's root model already marked its folders; a reused one is marked as it converts.
                for ((index, module) in modules.withIndex()) {
                    if (rootModels[index] == null) makeElixirModule(module, otpApps[index].root) else addElixirFacet(module)
                }
            }
            syncDepsOnceAdded(project, modules)

            modules
        } else {
            emptyList()
        }

    /**
     * Syncs the Mix dependency libraries once [modules] are in [project]: at once when they all are, and otherwise once
     * any of them is added, as the module model holding them commits. A model disposed instead, on Cancel, leaves the
     * wait to end at the next module added, or with the project.
     */
    fun syncDepsOnceAdded(project: Project, modules: List<Module>) {
        val moduleManager = ModuleManager.getInstance(project)
        if (modules.all { moduleManager.findModuleByName(it.name) === it }) {
            project.service<MixDepsSyncService>().enqueue(SyncRequest.All)
            return
        }

        val connection = project.messageBus.connect(project.service<ElixirCoroutineService>().scope)
        connection.subscribe(ModuleListener.TOPIC, object : ModuleListener {
            override fun modulesAdded(project: Project, added: List<Module>) {
                if (added.any(modules::contains)) {
                    connection.disconnect()
                    project.service<MixDepsSyncService>().enqueue(SyncRequest.All)
                } else if (modules.all(Module::isDisposed)) {
                    connection.disconnect()
                }
            }
        })
    }

    /**
     * Gives [module] the Elixir facet when its module type takes one and it has none, and then marks [root]'s Mix folders
     * in it; whether it did.
     */
    @RequiresWriteLock
    internal fun makeElixirModule(module: Module, root: VirtualFile): Boolean {
        if (!addElixirFacet(module)) return false
        ModuleRootModificationUtil.updateModel(module) { addFolders(it, root) }
        return true
    }

    /** Adds the Elixir facet to [module] when its module type takes one and it has none; whether it added it. */
    @RequiresWriteLock
    private fun addElixirFacet(module: Module): Boolean {
        val type = FacetType.findInstance(org.elixir_lang.facet.Type::class.java)

        val adds = type.isSuitableModuleType(ModuleType.get(module)) && FacetManager.getInstance(module).getFacetByType(Facet.ID) == null
        if (adds) FacetUtil.addFacet(module, type)
        return adds
    }

    /** The type of an OTP app's module: a small IDE has no `ELIXIR_MODULE` type, so its modules take the IDE's own. */
    fun otpAppModuleTypeId(): String =
        if (isSmallIde) ModuleTypeManager.getInstance().defaultModuleType.id else ELIXIR_MODULE_TYPE_ID

    /**
     * [otpApp]'s module. It takes the first of [moduleName], its [pathName], then that suffixed `-2`, `-3`, ... that is free or names a
     * module rooted at the app, which is then reused: a module of the name elsewhere, such as the one the platform names
     * after the opened folder, passes it on.
     */
    private fun createModuleForOtpApp(
        otpApp: OtpApp,
        moduleModel: ModifiableModuleModel,
        rootModelModifier: (OtpApp, ModifiableRootModel) -> Unit,
        moduleName: String,
        projectRoot: VirtualFile?
    ): Pair<Module, ModifiableRootModel?> {
        val pathName = pathName(otpApp, projectRoot)
        val names = sequenceOf(moduleName, pathName) + generateSequence(2) { it + 1 }.map { "$pathName-$it" }

        return names.firstNotNullOf { name ->
            val named = moduleModel.findModuleByName(name)
            when {
                named == null -> newModuleForOtpApp(otpApp, moduleModel, rootModelModifier, name, name == moduleName)
                otpApp.root in ModuleRootManager.getInstance(named).contentRoots -> named to null
                else -> null
            }
        }.also { (module, _) -> otpApp.module = module }
    }

    /** [otpApp]'s new module [name]. */
    private fun newModuleForOtpApp(
        otpApp: OtpApp,
        moduleModel: ModifiableModuleModel,
        rootModelModifier: (OtpApp, ModifiableRootModel) -> Unit,
        name: String,
        isOwnName: Boolean
    ): Pair<Module, ModifiableRootModel?> {
        val module = moduleModel.newModule("${otpApp.root.canonicalPath}${File.separator}/$name.iml", otpAppModuleTypeId())

        // The app's own module file is under its own name, so a path name's module has none.
        return module to if (otpApp.ideaModuleFile == null || !isOwnName) {
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

        for ((relativePath, folderMark) in CANONICAL_FOLDER_MARKS) {
            when (folderMark) {
                FolderMark.SOURCES -> addSourceDirToContent(content, root, relativePath, false)
                FolderMark.TEST_SOURCES -> addSourceDirToContent(content, root, relativePath, true)
                FolderMark.EXCLUDED -> excludeDirFromContent(content, root, relativePath)
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
            ?.isExcludeOutput = false
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

    private fun createImportedOtpApp(appRoot: VirtualFile): OtpApp? = findMixExs(appRoot)?.let { OtpApp(appRoot, it) }

    private fun findMixExs(directory: VirtualFile): VirtualFile? =
        try {
            directory.findChild(MIX_EXS)
        } catch (_: EOFException) {
            null
        }

    private fun isMixProject(directory: VirtualFile?): Boolean = directory?.let(::findMixExs) != null

    /** Whether [directory] is a Mix project's `deps`, which holds its fetched dependencies. */
    internal fun isDepsOfAMixProject(directory: VirtualFile): Boolean =
        directory.name == "deps" && isMixProject(directory.parent)

    /** A hidden directory holds tooling state, such as `.claude/worktrees/` checkouts, never an app of the scanned root. */
    private fun isHidden(directory: VirtualFile): Boolean = directory.name.startsWith(".")

    private fun isAssetsOrBuildOrConfigOrDepsOrTestsDirectory(root: VirtualFile, directory: VirtualFile): Boolean {
        val parent = directory.parent

        return when (directory.name) {
            "assets" -> true
            "config", "tests" -> parent == root
            // The deps and _build of root or of a Mix project below it hold fetched dependencies and their builds.
            "deps" -> parent == root || isDepsOfAMixProject(directory)
            "_build" -> parent == root || isMixProject(parent)
            else -> false
        }
    }
}
