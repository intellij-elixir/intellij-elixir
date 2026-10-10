package org.elixir_lang.code_insight

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamLibraryFixture
import java.io.File

/**
 * A `defdelegate` in a library's source root is shown by its own head, not by the target it reaches.
 */
class LibraryDelegationParameterInfoTest : PlatformTestCase() {
    private var libraryRoot: File? = null

    fun testADelegationInALibraryShowsItsHead() {
        val root = FileUtil.createTempDirectory("delegation_library", null).also { libraryRoot = it }

        File(root, "b.ex").writeText(
            """
            defmodule A.Impl do
              def snoc(q, x), do: {q, x}
            end

            defmodule B do
              defdelegate snoc(q, x \\ nil), to: A.Impl
            end
            """.trimIndent()
        )

        val virtualRoot = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)!!

        BeamLibraryFixture.addLibrary(project, myFixture.module, "delegation-$name", listOf(virtualRoot), listOf(virtualRoot))
        myFixture.configureByText(
            "caller.ex",
            """
            defmodule Caller do
              def calls(q), do: B.snoc(q<caret>)
            end
            """.trimIndent()
        )

        assertEquals(listOf("q, x \\\\ nil"), myFixture.parameterInfoSignaturesAtCaret())
    }

    override fun tearDown() {
        try {
            BeamLibraryFixture.removeLibrary(project, myFixture.module, "delegation-$name")
            libraryRoot?.let(FileUtil::delete)
        } finally {
            super.tearDown()
        }
    }
}
