package org.elixir_lang.expander

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.declaration.EXPANDER
import org.elixir_lang.declaration.SOURCE_PROPERTY
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.expanderSwitchOn
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.junit.logs.GuardedLoggedErrorProcessor
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.call.Call
import java.io.File

/**
 * The Mix project at [dir], whose `mix deps.get` and `mix compile` have run: its `lib/` and `test/` as content and each
 * dep as a library of its ebin and sources.
 */
internal class PhoenixCorpus(private val project: Project, disposable: Disposable, val dir: File) {
    val loweringErrors = sortedMapOf<String, Int>()

    init {
        VfsRootAccess.allowRootAccess(disposable, dir.path, LegManifest.environment("ELIXIR_LANG_ELIXIR_PATH"))
    }

    /** A file asked about, with its uses, and the uses the costlier measures ask: all of an app file's, every tenth of a dep file's. */
    class Asked(val set: String, val name: String, val psi: ElixirFile, val uses: List<Use>) {
        val sampled: List<Use> = if (set == APP) uses else uses.filterIndexed { i, _ -> i % 10 == 0 }
    }

    /**
     * The app's `lib/` and `test/` as content and each dep as a library of [module]. The leg's Elixir is not a library
     * too, since that would give each of its modules a second copy.
     */
    fun setUp(module: Module): String {
        for ((name, test) in listOf("lib" to false, "test" to true)) {
            val root = vf(File(dir, name))
            PsiTestUtil.addContentRoot(module, root)
            PsiTestUtil.addSourceRoot(module, root, test)
        }
        val deps = File(dir, "deps").listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }
        for (dep in deps) {
            val ebin = File(dir, "_build/dev/lib/${dep.name}/ebin").takeIf { it.isDirectory }
            val sources = listOf("lib", "src").map { File(dep, it) }.filter { it.isDirectory }
            library(module, "dep-${dep.name}", listOfNotNull(ebin), sources)
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return "content: lib (sources), test (test sources); ${deps.size} dep libraries, each CLASSES " +
            "_build/dev/lib/<dep>/ebin and SOURCES deps/<dep>/{lib,src}"
    }

    val appFiles: List<File> get() = exFiles(File(dir, "lib")) + exFiles(File(dir, "test"))

    val depFiles: List<File> get() = File(dir, "deps").listFiles()!!.sortedBy { it.name }.flatMap { exFiles(File(it, "lib")) }

    /** 50 dep files spread evenly over [depFiles] in path order. */
    val depSample: List<File> get() = depFiles.let { all -> List(50) { all[it * all.size / 50] } }

    /** The app's files and the dep sample, with their uses. */
    fun asked(): List<Asked> = asked(APP, appFiles) + asked(DEP, depSample)

    private fun asked(set: String, files: List<File>): List<Asked> =
        files.mapNotNull { file ->
            val psi = psi(file) ?: return@mapNotNull null
            val uses = read { PsiTreeUtil.findChildrenOfType(psi, Call::class.java).mapNotNull { Use.of(it) } }

            Asked(set, file.relativeTo(dir).path, psi, uses)
        }

    fun psi(file: File): ElixirFile? = PsiManager.getInstance(project).findFile(vf(file)) as? ElixirFile

    /** Runs [body] with the lowering's logged errors counted rather than failing the test. */
    fun <T> quiet(body: () -> T): T {
        val processor = object : GuardedLoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> =
                if (category.contains("org.elixir_lang.lowering")) {
                    synchronized(loweringErrors) { loweringErrors.merge(message.lines().first().take(200), 1, Int::plus) }
                    Action.NONE
                } else {
                    super.processError(category, message, details, t)
                }
        }

        return LoggedErrorProcessor.executeWith(processor).use { body() }
    }

    fun <T> read(body: () -> T): T = quiet { ReadAction.computeBlocking<T, Throwable> { body() } }

    private fun vf(file: File): VirtualFile =
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!.also { VfsUtil.markDirtyAndRefresh(false, true, true, it) }

    private fun library(module: Module, name: String, classes: List<File>, sources: List<File>) {
        WriteAction.runAndWait<Throwable> {
            val table = LibraryTablesRegistrar.getInstance().getLibraryTable(project).modifiableModel
            val library = table.createLibrary(name)
            library.modifiableModel.let { model ->
                classes.forEach { model.addRoot(vf(it), OrderRootType.CLASSES) }
                sources.forEach { model.addRoot(vf(it), OrderRootType.SOURCES) }
                model.commit()
            }
            table.commit()
            ModuleRootModificationUtil.addDependency(module, library)
        }
    }

    private fun exFiles(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && (it.extension == "ex" || it.extension == "exs") }.sortedBy { it.path }.toList()

    companion object {
        const val PROPERTY = "org.elixir_lang.expander.phoenix"
        const val APP = "app"
        const val DEP = "dep"

        /** Why a test over the corpus can't run, or `null` when both it and the expander switch are set. */
        fun skipReason(): String? =
            when {
                !expanderSwitchOn() -> "$SOURCE_PROPERTY=$EXPANDER is not set"
                System.getProperty(PROPERTY) == null -> "$PROPERTY=<Phoenix app directory> is not set"
                else -> null
            }
    }
}
