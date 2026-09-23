package org.elixir_lang.code_insight.matrix

import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.LightProjectDescriptor
import java.io.File

/**
 * Every compiled backing's `ebin` as one module library, attached once for the whole matrix: a library added per
 * cell would reindex it thousands of times. The backings' modules have distinct names, so attaching them all at once
 * adds no candidate a cell does not ask for.
 */
object MatrixProjectDescriptor : LightProjectDescriptor() {
    /** One per compiled backing, laid out as Mix lays out a dependency's beams. */
    val ebinDirectories: List<File> =
        File(Fixtures.DIRECTORY, "_build/dev/lib").absoluteFile.let { directory ->
            directory.listFiles() ?: error("$directory is missing; run generate.exs in ${Fixtures.DIRECTORY}")
        }
            .map { File(it, "ebin") }
            .filter(File::isDirectory)
            .sortedBy { it.parentFile.name }

    override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
        VfsRootAccess.allowRootAccess(module.project, *ebinDirectories.map(File::getPath).toTypedArray())

        model.moduleLibraryTable.createLibrary("code-intelligence-matrix").modifiableModel.apply {
            ebinDirectories.forEach { addRoot(VfsUtilCore.pathToUrl(it.path), OrderRootType.CLASSES) }
            commit()
        }
    }
}
