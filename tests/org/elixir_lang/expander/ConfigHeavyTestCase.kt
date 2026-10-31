package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.junit.HeavyTestCase
import java.io.File
import java.util.concurrent.Callable

/** A Mix project on disk and the config tables its files read. */
abstract class ConfigHeavyTestCase : HeavyTestCase() {
    internal val holder: ProjectModules get() = ProjectModules.getInstance(project)

    override fun setUp() {
        super.setUp()
        LegSdk.setUp(module, testRootDisposable)
    }

    override fun tearDown() {
        try {
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

    /** The project [sources], with a `mix.exs` and a `lib/reader.ex` where they don't name one. */
    protected fun project(vararg sources: Pair<String, String>): VirtualFile {
        val directory = createTempDir("app")
        val defaults = listOf("mix.exs" to MIX_EXS, "lib/reader.ex" to READER)
        (defaults.filter { (path, _) -> sources.none { it.first == path } } + sources).forEach { (path, text) ->
            FileUtil.writeToFile(File(directory, path), text)
        }
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(module, root.findChild("lib")!!)
        root.findChild("test")?.let { PsiTestUtil.addSourceRoot(module, it, true) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return root
    }

    protected fun psi(root: VirtualFile, path: String): PsiFile =
        PsiManager.getInstance(project).findFile(root.findFileByRelativePath(path)!!)!!

    internal fun table(file: VirtualFile, env: String = "dev", inputs: ProjectModules.Inputs = legInputs): ConfigTable? =
        ReadAction.nonBlocking(Callable { holder.configTable(file, inputs, env) }).executeSynchronously()

    /** The config table `lib/reader.ex`'s build reads under [env]: its terms and the files read, newest first. */
    protected fun read(root: VirtualFile, env: String = "dev", reader: String = "lib/reader.ex"): String {
        val table = table(root.findFileByRelativePath(reader)!!, env) ?: return "no table"

        return when {
            table.unknown -> "unknown"
            table.declined -> "declined"
            else -> "${table.config} ${table.files.map { FileUtil.getRelativePath(root.path, it, '/') }}"
        }
    }

    protected companion object {
        const val MIX_EXS = "defmodule App.MixProject do\n  use Mix.Project\n\n  def project, do: [app: :app, version: \"0.1.0\"]\nend\n"
        const val READER = "defmodule Reader do\nend\n"
        const val CONFIG = "config/config.exs"
        const val DEV = "config/dev.exs"

        fun mixExs(options: String) =
            "defmodule App.MixProject do\n  use Mix.Project\n\n  def project, do: [app: :app, version: \"0.1.0\", $options]\nend\n"
    }
}
