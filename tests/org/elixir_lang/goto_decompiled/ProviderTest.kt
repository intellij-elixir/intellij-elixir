package org.elixir_lang.goto_decompiled

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamLibraryFixture
import java.io.File

/** Go To Related from a declaration lands on the decompiled definitions of the same function, whichever form declares it. */
class ProviderTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/beam/parser/elixir-1.19.5-otp-28"

    /** The `.beam` is in a library root, as a dependency's or the SDK's is, not in the project's own content. */
    fun testEachDeclaringFormIsRelatedToTheDecompiledFunction() {
        val ebin = FileUtil.createTempDirectory("goto_decompiled", "ebin")
        File(testDataPath, "Elixir.Kernel.beam").copyTo(File(ebin, "Elixir.Kernel.beam"))
        VfsRootAccess.allowRootAccess(myFixture.testRootDisposable, ebin.path)
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ebin)!!
        BeamLibraryFixture.addLibrary(project, myFixture.module, LIBRARY, listOf(root))

        for (declaration in listOf("def h<caret>d(list), do: list", "defdelegate h<caret>d(list), to: :erlang")) {
            myFixture.configureByText("kernel.ex", "defmodule Kernel do\n  $declaration\nend\n")

            val related = Provider().getItems(myFixture.file.findElementAt(myFixture.caretOffset)!!)
                .mapNotNull { it.element?.text?.lineSequence()?.first()?.trim() }

            assertTrue(
                "$declaration: expected the decompiled hd/1, got $related",
                related.isNotEmpty() && related.all { it.contains("hd(") }
            )
        }
    }

    private companion object {
        const val LIBRARY = "decompiled-kernel"
    }
}
