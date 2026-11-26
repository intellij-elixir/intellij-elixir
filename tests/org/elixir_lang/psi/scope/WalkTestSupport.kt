package org.elixir_lang.psi.scope

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.injection.ElixirSigilInjector
import org.elixir_lang.settings.ElixirExperimentalSettings
import java.io.File

/** Fixtures shared by the tests of the call walk's cost. */
object WalkTestSupport {
    /** `Kernel` and `Kernel.SpecialForms` as decompiled source, as an SDK supplies them. */
    val DOCS_KERNEL = File("testData/org/elixir_lang/beam/decompiler/Docs")

    /** [block] with [directory] as a library of [module], removed afterwards. */
    fun <T> withLibrary(
        project: Project,
        module: Module,
        disposable: Disposable,
        name: String,
        directory: File,
        block: (VirtualFile) -> T
    ): T {
        val root = addLibrary(project, module, disposable, name, directory)

        return try {
            block(root)
        } finally {
            WriteAction.runAndWait<Throwable> {
                val table = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
                table.getLibraryByName(name)?.let { library ->
                    ModuleRootModificationUtil.updateModel(module) { model ->
                        model.findLibraryOrderEntry(library)?.let(model::removeOrderEntry)
                    }
                    table.removeLibrary(library)
                }
            }
        }
    }

    /** [directory] as the library [name] of [module], indexed and readable until [disposable] is disposed: its root. */
    fun addLibrary(project: Project, module: Module, disposable: Disposable, name: String, directory: File): VirtualFile {
        val absolute = directory.absoluteFile
        VfsRootAccess.allowRootAccess(disposable, absolute.path)
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(absolute) ?: error("no $absolute")
        BeamLibraryFixture.addLibrary(project, module, name, listOf(root))
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return root
    }

    /** [block] with `~H` sigils injected as HEEx, as the HEEx host fixtures set it up. */
    fun <T> withHeexInjection(project: Project, disposable: Disposable, block: () -> T): T {
        val settings = ElixirExperimentalSettings.instance
        val original = settings.state.enableHtmlInjection
        settings.state.enableHtmlInjection = true
        InjectedLanguageManager.getInstance(project).registerMultiHostInjector(ElixirSigilInjector(), disposable)

        return try {
            block()
        } finally {
            settings.state.enableHtmlInjection = original
        }
    }

    /** Every file injected into [file]'s hosts, and into theirs, breadth first. */
    fun injectedFiles(file: PsiFile): List<PsiFile> {
        val manager = InjectedLanguageManager.getInstance(file.project)
        val found = mutableListOf<PsiFile>()
        val pending = ArrayDeque(file.viewProvider.allFiles)

        while (pending.isNotEmpty()) {
            for (host in PsiTreeUtil.findChildrenOfType(pending.removeFirst(), PsiLanguageInjectionHost::class.java)) {
                manager.enumerateEx(host, host.containingFile, false) { injected, _ ->
                    found += injected.viewProvider.allFiles
                    pending += injected.viewProvider.allFiles
                }
            }
        }

        return found
    }

    /** `file:line:column` of [element]. */
    fun location(element: PsiElement?): String {
        val file = element?.containingFile ?: return element.toString()
        val document = PsiDocumentManager.getInstance(element.project).getDocument(file)
            ?: return "${file.name}@${element.textOffset}"
        val offset = element.textRange.startOffset
        val line = document.getLineNumber(offset)

        return "${file.name}:${line + 1}:${offset - document.getLineStartOffset(line) + 1}"
    }
}
