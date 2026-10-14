package org.elixir_lang.snapshot

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.LightProjectDescriptor
import org.elixir_lang.elixir_surface.LegManifest.environment
import java.io.File

/**
 * A light project whose module depends on the leg's Elixir as one project library: each application's `ebin` as
 * classes and its `lib` as sources. It is a project of its own, so the shared light module is never changed.
 */
object LegElixirProjectDescriptor : LightProjectDescriptor() {
    override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
        val applications = File(environment("ELIXIR_LANG_ELIXIR_PATH"), "lib").listFiles(File::isDirectory).orEmpty()
        val tableModel = LibraryTablesRegistrar.getInstance().getLibraryTable(module.project).modifiableModel
        val library = tableModel.createLibrary("Elixir ${environment("ELIXIR_VERSION")}")

        library.modifiableModel.let { libraryModel ->
            // By URL: the roots are outside the roots a test may read until the library lists them.
            for (application in applications.sortedBy { it.name }) {
                libraryModel.addRoot(VfsUtilCore.pathToUrl(File(application, "ebin").path), OrderRootType.CLASSES)
                libraryModel.addRoot(VfsUtilCore.pathToUrl(File(application, "lib").path), OrderRootType.SOURCES)
            }

            libraryModel.commit()
        }

        tableModel.commit()
        model.addLibraryEntry(library)
    }
}
