package org.elixir_lang.expander

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.junit.HeavyTestCase
import java.io.File

/** Each file's expansion looks modules up from its own IDE module and source root, whichever file asked first. */
class ProjectModulesScopeHeavyTest : HeavyTestCase() {
    private val holder: ProjectModules get() = ProjectModules.getInstance(project)

    /** `D` defined in a second IDE module, which the asker's module doesn't depend on, stays absent for the asker. */
    fun testSecondIdeModule() {
        val firstRoot = root(createModule("first"), "first", "lib/a.ex" to "defmodule A do\n  require D\nend\n")
        val secondRoot = root(createModule("second"), "second", "lib/s.ex" to "defmodule S do\n  require D\nend\n")
        val a = psi(firstRoot, "lib/a.ex")
        val s = psi(secondRoot, "lib/s.ex")
        val before = render(a, expansion(holder, a))
        expansion(holder, s)

        WriteAction.runAndWait<Throwable> {
            secondRoot.findChild("lib")!!.createChildData(this, "d.ex").setBinaryContent("defmodule D do\nend\n".toByteArray())
        }

        assertEquals(before, render(a, expansion(holder, a)))
        assertEquals(render(a, freshExpansion(project, a)), render(a, expansion(holder, a)))
        assertEquals(render(s, freshExpansion(project, s)), render(s, expansion(holder, s)))
        assertTrue(exports(holder, s, "Elixir.D") is ModuleExports.Present)
        assertEquals(ModuleExports.Absent, exports(holder, a, "Elixir.D"))
    }

    /**
     * `B`, in `X`'s main sources, requires `D`, which only `X`'s tests define. A test file of `X` and a file of `Y`,
     * which depends on `X`, both require `B`: `B` reads `D` from its own scope, whichever of them is asked first.
     */
    fun testAskOrder() {
        for (tFirst in listOf(true, false)) {
            val suffix = if (tFirst) "t_first" else "y_first"
            val x = createModule("x_$suffix")
            val xRoot = root(
                x,
                "x_$suffix",
                "lib/b.ex" to "defmodule B do\n  require D\nend\n",
                "test/d.ex" to "defmodule D do\nend\n",
                "test/t.ex" to "defmodule T do\n  require B\nend\n",
            )
            PsiTestUtil.addSourceRoot(x, xRoot.findChild("test")!!, true)
            val y = createModule("y_$suffix")
            val yRoot = root(y, "y_$suffix", "lib/y.ex" to "defmodule Y do\n  require B\nend\n")
            ModuleRootModificationUtil.addDependency(y, x)
            val t = psi(xRoot, "test/t.ex")
            val yFile = psi(yRoot, "lib/y.ex")

            for (file in if (tFirst) listOf(t, yFile) else listOf(yFile, t)) expansion(holder, file)

            for (file in listOf(t, yFile)) {
                assertEquals(
                    "${file.name}, ${if (tFirst) "t.ex" else "y.ex"} asked first",
                    render(file, freshExpansion(project, file)),
                    render(file, expansion(holder, file)),
                )
            }
            assertTrue(exports(holder, t, "Elixir.D") is ModuleExports.Present)
            assertEquals(ModuleExports.Absent, exports(holder, psi(xRoot, "lib/b.ex"), "Elixir.D"))
        }
    }

    private fun root(module: com.intellij.openapi.module.Module, name: String, vararg sources: Pair<String, String>): VirtualFile {
        val directory = createTempDir(name)
        sources.forEach { (path, text) -> FileUtil.writeToFile(File(directory, path), text) }
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(module, root.findChild("lib")!!)

        return root
    }

    private fun psi(root: VirtualFile, path: String): PsiFile =
        PsiManager.getInstance(project).findFile(root.findFileByRelativePath(path)!!)!!
}
