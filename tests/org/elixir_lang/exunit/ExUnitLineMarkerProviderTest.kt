package org.elixir_lang.exunit

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import org.elixir_lang.junit.HeavyTestCase
import org.elixir_lang.psi.call.Call
import java.io.File

/** Which `defmodule` calls in a test file get a run marker: those that define a module. */
class ExUnitLineMarkerProviderTest : HeavyTestCase() {
    fun testANestedTestModuleHasAMarker() {
        assertNotNull(info("defmodule FooTest do\n  defmodule BarTest do\n  end\nend\n"))
    }

    /** A `defmodule` without a `do` defines no module, so it has no tests to run. */
    fun testADefmoduleWithoutADoHasNoMarker() {
        assertNull(info("defmodule FooTest do\n  defmodule BarTest, []\nend\n"))
    }

    /** The head of a `defmacro defmodule` is an argument `defmacro` quotes, not a `defmodule` call. */
    fun testTheHeadOfADefmacroDefmoduleHasNoMarker() {
        assertNull(info("defmodule FooTest do\n  defmacro defmodule(name, do: block) do\n    name\n  end\nend\n"))
    }

    /** The run marker of the last `defmodule` in `test/foo_test.exs` holding [text]. */
    private fun info(text: String): Any? {
        val directory = createTempDirectory()
        File(directory, "mix.exs").writeText(
            "defmodule App.MixProject do\n  use Mix.Project\n\n  def project, do: [app: :app, version: \"0.1.0\"]\nend\n"
        )
        File(directory, "test").mkdirs()
        File(directory, "test/foo_test.exs").writeText(text)
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        PsiTestUtil.addContentRoot(module, root)
        PsiTestUtil.addSourceRoot(module, root.findChild("test")!!, true)
        val file = PsiManager.getInstance(project).findFile(root.findFileByRelativePath("test/foo_test.exs")!!)!!
        val call = PsiTreeUtil.findChildrenOfType(file, Call::class.java).last { it.functionName() == "defmodule" }

        return ExUnitLineMarkerProvider().getInfo(PsiTreeUtil.getDeepestFirst(call))
    }
}
