package org.elixir_lang.junit

import com.intellij.facet.FacetManager
import com.intellij.facet.impl.FacetUtil
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderEntry
import com.intellij.openapi.roots.SourceFolder
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.IndexingTestUtil
import org.jetbrains.jps.model.java.JavaResourceRootProperties
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootProperties
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.java.JpsJavaExtensionService
import org.jetbrains.jps.model.module.JpsModuleSourceRootType

/**
 * The part of the module and project every later light test in a fork shares that is restored after each test: facets,
 * content entries with their source and exclude folders, order entries, and project libraries. Other shared state,
 * such as the project SDK or the module's compiler output, is each test's own to put back.
 */
internal data class SharedModuleState(
    val facets: Set<String>,
    val contentEntries: Map<String, ContentEntryState>,
    val orderEntries: List<String>,
    val projectLibraries: Set<String>,
) {
    data class ContentEntryState(val sourceFolders: Set<SourceFolderState>, val excludeFolders: Set<String>)

    /** The properties of a Java source or resource root; a root of any other type records none. */
    data class SourceFolderState(
        val url: String,
        val rootType: JpsModuleSourceRootType<*>,
        val packagePrefix: String,
        val relativeOutputPath: String,
        val forGeneratedSources: Boolean,
    ) {
        constructor(folder: SourceFolder) : this(
            folder.url,
            folder.rootType,
            (folder.jpsElement.properties as? JavaSourceRootProperties)?.packagePrefix ?: "",
            (folder.jpsElement.properties as? JavaResourceRootProperties)?.relativeOutputPath ?: "",
            when (val properties = folder.jpsElement.properties) {
                is JavaSourceRootProperties -> properties.isForGeneratedSources
                is JavaResourceRootProperties -> properties.isForGeneratedSources
                else -> false
            },
        )

        override fun toString(): String {
            val type = rootType.javaClass.simpleName + if (rootType.isForTests) " (tests)" else ""
            return "SourceFolderState(url=$url, rootType=$type, packagePrefix=$packagePrefix, " +
                "relativeOutputPath=$relativeOutputPath, forGeneratedSources=$forGeneratedSources)"
        }
    }

    /** One line for each way [after] differs from this. */
    fun differences(after: SharedModuleState): List<String> = buildList {
        (after.facets - facets).mapTo(this) { "facet added: $it" }
        (facets - after.facets).mapTo(this) { "facet removed: $it" }
        (after.contentEntries.keys - contentEntries.keys).mapTo(this) { "content entry added: $it" }
        (contentEntries.keys - after.contentEntries.keys).mapTo(this) { "content entry removed: $it" }
        for ((url, state) in contentEntries) {
            val afterState = after.contentEntries[url] ?: continue
            if (afterState != state) add("content entry changed: $url from $state to $afterState")
        }
        if (after.orderEntries != orderEntries) add("order entries changed from $orderEntries to ${after.orderEntries}")
        (after.projectLibraries - projectLibraries).mapTo(this) { "project library added: $it" }
        (projectLibraries - after.projectLibraries).mapTo(this) { "project library removed: $it" }
    }

    companion object {
        fun of(module: Module): SharedModuleState = ReadAction.computeBlocking<SharedModuleState, Throwable> {
            val rootManager = ModuleRootManager.getInstance(module)
            SharedModuleState(
                facets = FacetManager.getInstance(module).allFacets.mapTo(HashSet(), ::facetKey),
                contentEntries = rootManager.contentEntries.associate { entry ->
                    entry.url to ContentEntryState(
                        entry.sourceFolders.mapTo(HashSet(), ::SourceFolderState),
                        // `excludeFolderUrls` also has exclude policies' roots, which `removeExcludeFolder`
                        // cannot remove.
                        entry.excludeFolders.mapTo(HashSet()) { it.url },
                    )
                },
                orderEntries = rootManager.orderEntries.map(::orderEntryKey),
                projectLibraries = LibraryTablesRegistrar.getInstance().getLibraryTable(module.project).libraries
                    .mapNotNullTo(HashSet()) { it.name },
            )
        }

        /**
         * Removes from [module] and its project what a test added since [before], puts back the Java source and
         * resource folders it removed or changed on a kept content entry, and returns the state left. Anything else a
         * test removed is not put back: the check reports it.
         */
        fun restore(module: Module, before: SharedModuleState): SharedModuleState {
            val now = of(module)
            if (now == before) return now

            if (now.facets != before.facets) {
                val facetManager = FacetManager.getInstance(module)
                WriteAction.runAndWait<Throwable> {
                    facetManager.createModifiableModel().apply {
                        allFacets.filterNot { facetKey(it) in before.facets }.forEach(::removeFacet)
                        commit()
                    }
                }
            }

            if (now.contentEntries != before.contentEntries || now.orderEntries != before.orderEntries) {
                ModuleRootModificationUtil.updateModel(module) { model ->
                    for (entry in model.contentEntries) {
                        val kept = before.contentEntries[entry.url]
                        if (kept == null) {
                            model.removeContentEntry(entry)
                            continue
                        }
                        entry.sourceFolders.filterNot { SourceFolderState(it) in kept.sourceFolders }
                            .forEach(entry::removeSourceFolder)
                        val left = entry.sourceFolders.mapTo(HashSet(), ::SourceFolderState)
                        val java = JpsJavaExtensionService.getInstance()
                        for ((url, rootType, packagePrefix, outputPath, generated) in kept.sourceFolders - left) {
                            when (rootType) {
                                is JavaSourceRootType -> entry.addSourceFolder(
                                    url,
                                    rootType,
                                    java.createSourceRootProperties(packagePrefix, generated),
                                )
                                is JavaResourceRootType -> entry.addSourceFolder(
                                    url,
                                    rootType,
                                    java.createResourceRootProperties(outputPath, generated),
                                )
                            }
                        }
                        entry.excludeFolders.filterNot { it.url in kept.excludeFolders }
                            .forEach(entry::removeExcludeFolder)
                    }
                    // Removing a module library's entry removes the library.
                    model.orderEntries.filterNot { orderEntryKey(it) in before.orderEntries }
                        .forEach(model::removeOrderEntry)
                }
            }

            if (now.projectLibraries != before.projectLibraries) {
                val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(module.project)
                WriteAction.runAndWait<Throwable> {
                    libraryTable.libraries.filterNot { it.name in before.projectLibraries }
                        .forEach(libraryTable::removeLibrary)
                }
            }

            // The changes queue a rescan, which finishes before the next test's snapshot or the project closing.
            IndexingTestUtil.waitUntilIndexesAreReady(module.project)

            return of(module)
        }

        /**
         * A facet's type, name and saved configuration, so a stored setting a test leaves on a kept facet is a
         * difference.
         */
        private fun facetKey(facet: com.intellij.facet.Facet<*>): String {
            val configuration = FacetUtil.saveFacetConfiguration(facet.configuration)?.let(JDOMUtil::write)
            return "${facet.type.stringId}: ${facet.name} $configuration"
        }

        private fun orderEntryKey(entry: OrderEntry): String = when (entry) {
            is LibraryOrderEntry -> "library (${entry.libraryLevel}): ${entry.libraryName}"
            is ModuleOrderEntry -> "module: ${entry.moduleName}"
            is JdkOrderEntry -> "sdk: ${entry.jdkName}"
            else -> entry.presentableName
        }
    }
}
