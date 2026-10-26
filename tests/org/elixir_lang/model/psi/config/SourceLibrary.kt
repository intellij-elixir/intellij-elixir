package org.elixir_lang.model.psi.config

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import java.io.File

/** Runs [block] while the module depends on a project library [name] whose sources are [directory]. */
internal fun CodeInsightTestFixture.withSourceLibrary(name: String, directory: File, block: () -> Unit) {
    val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory.absoluteFile)!!
    val library = WriteAction.computeAndWait<Library, Throwable> {
        LibraryTablesRegistrar.getInstance().getLibraryTable(project).createLibrary(name).also { library ->
            library.modifiableModel.apply {
                addRoot(root, OrderRootType.SOURCES)
                commit()
            }
        }
    }
    ModuleRootModificationUtil.addDependency(module, library)

    try {
        block()
    } finally {
        ModuleRootModificationUtil.removeDependency(module, library)
        WriteAction.runAndWait<Throwable> { LibraryTablesRegistrar.getInstance().getLibraryTable(project).removeLibrary(library) }
    }
}
