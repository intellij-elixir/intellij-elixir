package org.elixir_lang.facet

import com.intellij.facet.ProjectFacetManager
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.annotations.RequiresReadLock
import kotlinx.coroutines.launch
import org.elixir_lang.Facet
import org.elixir_lang.util.ElixirCoroutineService
import org.elixir_lang.util.awaitJpsProjectLoaded

/**
 * Turns the library an earlier version gave an Elixir facet's module in place of its SDK, named after the SDK, into the
 * name the facet stores, and removes it: IntelliJ IDEA showed it beside the module's own SDK, a second time. Runs when
 * the project opens, and again when an Elixir SDK is added or renamed, since a stand-in named like no SDK is kept.
 *
 * Waits for the JPS model: the workspace model is loaded from a cache and the `.iml` state applied over it afterwards,
 * which would both hide a stand-in from the check and discard its removal.
 */
internal class StandInMigration : ProjectActivity, DumbAware {
    override suspend fun execute(project: Project) {
        awaitJpsProjectLoaded(project)
        migrate(project)
    }

    companion object {
        /**
         * Runs the migration again after an Elixir SDK is added to the table or renamed: a stand-in kept for want of an
         * SDK of its name may now have one.
         */
        fun rerun(project: Project) {
            project.service<ElixirCoroutineService>().scope.launch {
                awaitJpsProjectLoaded(project)
                migrate(project)
            }
        }

        suspend fun migrate(project: Project) {
            if (readAction { project.isDisposed || standIns(project).isEmpty() }) return

            edtWriteAction {
                if (project.isDisposed) return@edtWriteAction

                for ((facet, names) in standIns(project)) {
                    facet.sdk = Facet.sdkNamed(names.first())

                    // Removing a module-level library's entry also removes the library from the module's table.
                    ModuleRootModificationUtil.updateModel(facet.module) { model ->
                        model.orderEntries.filterIsInstance<LibraryOrderEntry>()
                            .filter { it.libraryName in names }
                            .forEach(model::removeOrderEntry)
                    }
                }
            }
        }

        /**
         * The names of each Elixir facet's module's libraries, at any level, named like a registered Elixir SDK, in
         * order, for the facets whose modules have any.
         */
        @RequiresReadLock
        private fun standIns(project: Project): Map<Facet, List<String>> {
            val sdkNames = Facet.sdks().mapTo(HashSet()) { it.name }

            return ProjectFacetManager.getInstance(project).getFacets(Facet.ID)
                .associateWith { facet ->
                    ModuleRootManager.getInstance(facet.module).orderEntries.filterIsInstance<LibraryOrderEntry>()
                        .mapNotNull { it.libraryName }
                        .filter { it in sdkNames }
                }
                .filterValues { it.isNotEmpty() }
        }
    }
}
