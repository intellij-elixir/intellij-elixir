package org.elixir_lang.expander

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.junit.HeavyTestCase
import java.io.File

/** A file's answers follow its path when it is renamed or moved, though its text and the modules it reads are unchanged. */
class ProjectModulesPathHeavyTest : HeavyTestCase() {
    private val holder: ProjectModules get() = ProjectModules.getInstance(project)

    /** The file names its functions by the tail of its path and of its directory. */
    fun testARenamedOrMovedFilesAnswersFollowIt() {
        val directory = createTempDir("paths")
        FileUtil.writeToFile(
            File(directory, "a/named.ex"),
            "defmodule Named do\n" +
                "  file = :erlang.byte_size(__ENV__.file)\n" +
                "  dir = :erlang.byte_size(__DIR__)\n" +
                "  def unquote(:erlang.binary_to_atom(:erlang.binary_part(__ENV__.file, file - 11, 11), :utf8))(), do: 1\n" +
                "  def unquote(:erlang.binary_to_atom(:erlang.binary_part(__DIR__, dir - 2, 2), :utf8))(), do: 1\n" +
                "end\n",
        )
        FileUtil.createDirectory(File(directory, "bb"))
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        val module = createModule("paths")
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(module, root)
        val file = root.findFileByRelativePath("a/named.ex")!!
        val psi = psi(file)

        assertEquals(answer(file), defines(holder, psi))

        WriteAction.runAndWait<Throwable> { file.rename(this, "renamed.ex") }
        assertEquals(answer(file), defines(holder, psi))

        WriteAction.runAndWait<Throwable> { file.move(this, root.findChild("bb")!!) }
        assertEquals(answer(file), defines(holder, psi))
    }

    /** The two functions a file at its path names: the last 11 bytes of the path, and the last 2 of its directory's. */
    private fun answer(file: VirtualFile): String =
        "compiled " + listOf(file.path.takeLast(11), file.parent.path.takeLast(2)).map { "$it/0" }.sorted()

    private fun psi(file: VirtualFile): PsiFile = PsiManager.getInstance(project).findFile(file)!!
}
