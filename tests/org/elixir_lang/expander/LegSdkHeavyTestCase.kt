package org.elixir_lang.expander

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.model.psi.config.disableMixDepsCheck
import java.io.File

/** A project under the leg's SDK, with Mix's dependency check off, and the [ProjectModules] that expands its files. */
abstract class LegSdkHeavyTestCase : HeavyTestCase() {
    internal val holder: ProjectModules get() = ProjectModules.getInstance(project)

    override fun setUp() {
        super.setUp()
        disableMixDepsCheck(testRootDisposable)
        LegSdk.setUp(module, testRootDisposable)
    }

    override fun tearDown() {
        try {
            LegSdk.tearDown(module)
        } finally {
            super.tearDown()
        }
    }

    /** [directory] as a content root of the module, its `lib` a source root and its `test` a test source root. */
    protected fun rooted(directory: File): VirtualFile {
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        PsiTestUtil.addContentRoot(module, root)
        root.findChild("lib")?.let { PsiTestUtil.addSourceRoot(module, it) }
        root.findChild("test")?.let { PsiTestUtil.addSourceRoot(module, it, true) }

        return root
    }

    /** The PSI of [path]'s file under [root]. */
    protected fun psi(root: VirtualFile, path: String): PsiFile =
        PsiManager.getInstance(project).findFile(root.findFileByRelativePath(path)!!)!!

    protected companion object {
        const val MIX_EXS = "defmodule App.MixProject do\n  use Mix.Project\n\n  def project, do: [app: :app, version: \"0.1.0\"]\nend\n"

        fun mixExs(options: String) =
            "defmodule App.MixProject do\n  use Mix.Project\n\n  def project, do: [app: :app, version: \"0.1.0\", $options]\nend\n"
    }
}
