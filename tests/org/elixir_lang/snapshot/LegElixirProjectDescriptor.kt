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
        addLegElixir(module, model)
    }
}

/** Adds [LegElixirProjectDescriptor]'s library. */
fun addLegElixir(module: Module, model: ModifiableRootModel) {
    val applications = File(environment("ELIXIR_LANG_ELIXIR_PATH"), "lib").listFiles(File::isDirectory).orEmpty()

    addLibrary(
        module,
        model,
        "Elixir ${environment("ELIXIR_VERSION")}",
        applications.sortedBy { it.name }.flatMap { application ->
            listOf(File(application, "ebin") to OrderRootType.CLASSES, File(application, "lib") to OrderRootType.SOURCES)
        },
    )
}

/** Adds a project library named [name] holding [roots] to [module]'s project, and [model]'s module depends on it. */
fun addLibrary(module: Module, model: ModifiableRootModel, name: String, roots: List<Pair<File, OrderRootType>>) {
    val tableModel = LibraryTablesRegistrar.getInstance().getLibraryTable(module.project).modifiableModel
    val library = tableModel.createLibrary(name)

    library.modifiableModel.let { libraryModel ->
        // By URL: the roots are outside the roots a test may read until the library lists them.
        for ((root, type) in roots) {
            libraryModel.addRoot(VfsUtilCore.pathToUrl(root.path), type)
        }

        libraryModel.commit()
    }

    tableModel.commit()
    model.addLibraryEntry(library)
}
