package org.elixir_lang.facet

import com.intellij.facet.FacetManager
import com.intellij.facet.FacetType
import com.intellij.facet.impl.FacetUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.common.runAll
import org.elixir_lang.Facet
import org.elixir_lang.sdk.ProcessOutput
import org.elixir_lang.sdk.SdkFixtures
import org.jdom.Element
import java.io.File
import java.util.concurrent.Callable
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.elixir_lang.sdk.elixir.Type as ElixirSdkType

/** Elixir SDKs and facets naming them, set up the way the small IDEs store them. */
internal class FacetModelFixtures {
    private val added = mutableListOf<Sdk>()

    /** An SDK's `ebin` and `lib` roots: [beam] in the first, and [source], declaring [moduleName], in the second. */
    class Roots(val ebin: VirtualFile, val lib: VirtualFile, val moduleName: String) {
        val beam: VirtualFile get() = ebin.findChild(BEAM.name)!!
        val source: VirtualFile get() = lib.findChild("${moduleName.lowercase()}.ex")!!
    }

    /**
     * Roots in a directory of their own outside the project, as an install's are: under the light project's
     * `temp://` content root a file is project source whatever the SDK is.
     */
    fun roots(moduleName: String, disposable: Disposable): Roots {
        val directory = FileUtil.createTempDirectory("facet_model", null, true).toPath()
        VfsRootAccess.allowRootAccess(disposable, directory.toString())
        val ebin = directory.resolve("ebin").createDirectories()
        BEAM.copyTo(ebin.resolve(BEAM.name).toFile())
        val lib = directory.resolve("lib").createDirectories()
        lib.resolve("${moduleName.lowercase()}.ex").writeText("defmodule $moduleName do\nend\n")
        val fileSystem = LocalFileSystem.getInstance()

        return Roots(
            fileSystem.refreshAndFindFileByNioFile(ebin)!!.also { it.refresh(false, true) },
            fileSystem.refreshAndFindFileByNioFile(lib)!!.also { it.refresh(false, true) },
            moduleName,
        )
    }

    /** [register] with [roots]. */
    fun register(name: String, roots: Roots, home: String? = null): Sdk =
        register(name, listOf(roots.ebin), listOf(roots.lib), home)

    /**
     * An Elixir SDK in the table with [classes] as its `CLASSES` roots and [sources] as its `SOURCES` roots; a [home]
     * made by `SdkFixtures.elixirHome` is read by the versions store first.
     */
    fun register(
        name: String,
        classes: List<VirtualFile> = emptyList(),
        sources: List<VirtualFile> = emptyList(),
        home: String? = null,
    ): Sdk {
        val sdk = if (home == null) ProjectJdkImpl(name, ElixirSdkType.instance) else SdkFixtures.elixirSdk(name, home)

        WriteAction.run<Throwable> {
            sdk.sdkModificator.apply {
                classes.forEach { addRoot(it, OrderRootType.CLASSES) }
                sources.forEach { addRoot(it, OrderRootType.SOURCES) }
                commitChanges()
            }
        }
        added += sdk
        SdkFixtures.register(sdk)
        SdksService.getInstance()!!.resetForTests()

        return sdk
    }

    /**
     * An SDK registered as [name] with roots of its own, and the module library an earlier version wrote in [module] in
     * place of its facet's SDK: named like it, carrying its `CLASSES` and `SOURCES` roots.
     */
    fun standIn(module: Module, name: String, disposable: Disposable): Sdk {
        val sdk = register(name, roots(name.replace(" ", ""), disposable))
        facet(module)
        ModuleRootModificationUtil.addModuleLibrary(
            module,
            name,
            sdk.rootProvider.getUrls(OrderRootType.CLASSES).toList(),
            sdk.rootProvider.getUrls(OrderRootType.SOURCES).toList(),
        )
        return sdk
    }

    fun remove(sdk: Sdk) {
        WriteAction.run<Throwable> { ProjectJdkTable.getInstance().removeJdk(sdk) }
        SdksService.getInstance()!!.resetForTests()
    }

    /** Removes every SDK [register] added that is still in the table. */
    fun removeAll() {
        WriteAction.run<Throwable> {
            val table = ProjectJdkTable.getInstance()
            added.filter { table.allJdks.contains(it) }.forEach { table.removeJdk(it) }
        }
        added.clear()
        SdksService.getInstance()!!.resetForTests()
    }

    /** Runs as a small IDE until [disposable] is disposed, then [leaveSmallIde]. */
    fun onSmallIde(project: Project, disposable: Disposable) {
        ProcessOutput.isSmallIdeOverride = true
        Disposer.register(disposable) { leaveSmallIde(project) }
    }

    /** [removeAll], then lets the deferred roots change run while still a small IDE, then stops being one. */
    fun leaveSmallIde(project: Project) {
        runAll(
            { removeAll() },
            { settle(project) },
            { ProcessOutput.isSmallIdeOverride = null },
        )
    }

    companion object {
        private val BEAM =
            File("testData/org/elixir_lang/mockSdk-1.0.4/lib/elixir/ebin/Elixir.Kernel.beam").absoluteFile

        /** Lets the deferred roots change run, and the indexing it starts finish. */
        fun settle(project: Project) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }

        fun isInLibraryClasses(project: Project, file: VirtualFile): Boolean =
            ReadAction.nonBlocking(Callable { ProjectFileIndex.getInstance(project).isInLibraryClasses(file) })
                .executeSynchronously()

        fun isInLibrarySource(project: Project, file: VirtualFile): Boolean =
            ReadAction.nonBlocking(Callable { ProjectFileIndex.getInstance(project).isInLibrarySource(file) })
                .executeSynchronously()

        /** Adds [root] to [sdk]'s `CLASSES` roots, as Settings commits one. */
        fun addClassesRoot(sdk: Sdk, root: VirtualFile) {
            WriteAction.runAndWait<Throwable> {
                sdk.sdkModificator.apply {
                    addRoot(root, OrderRootType.CLASSES)
                    commitChanges()
                }
            }
        }

        fun facet(module: Module): Facet =
            FacetManager.getInstance(module).getFacetByType(Facet.ID)
                ?: WriteAction.computeAndWait<Facet, Throwable> {
                    FacetUtil.addFacet(module, FacetType.findInstance(Type::class.java))
                }

        /** Stores [name] in [module]'s facet as a saved `.iml` would hold it, with no library. */
        fun nameFacet(module: Module, name: String) {
            val facet = facet(module)
            val saved = Element("configuration").setAttribute("sdkName", name)
            FacetUtil.loadFacetConfiguration(facet.configuration, saved)
            WriteAction.runAndWait<Throwable> { FacetManager.getInstance(module).facetConfigurationChanged(facet) }
        }

        /** The name [module]'s facet would save, or null when it saves none. */
        fun storedName(module: Module): String? =
            FacetManager.getInstance(module).getFacetByType(Facet.ID)
                ?.let { FacetUtil.saveFacetConfiguration(it.configuration) }
                ?.getAttributeValue("sdkName")

        /** A module library named [name] in [module], with no roots. */
        fun moduleLibrary(module: Module, name: String) {
            ModuleRootModificationUtil.addModuleLibrary(module, name, emptyList(), emptyList())
        }

        /** The names of the libraries [module]'s order entries refer to. */
        fun libraryNames(module: Module): List<String?> =
            ReadAction.computeBlocking<List<String?>, Throwable> {
                ModuleRootManager.getInstance(module).orderEntries.filterIsInstance<LibraryOrderEntry>()
                    .map { it.libraryName }
            }

        fun assign(module: Module, sdk: Sdk) {
            val facet = facet(module)
            WriteAction.runAndWait<Throwable> { facet.sdk = sdk }
        }
    }
}
