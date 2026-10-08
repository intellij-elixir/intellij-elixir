package org.elixir_lang.model.psi

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.common.runAll
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.code_insight.renameTargetsAtCaret
import java.io.File

class CompiledDeclarationRenameTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/documentation/erlang_atom_qualifier_hover"

    fun testRenamingACompiledFunctionIsRefused() {
        assertRenameRefused(
            """
            defmodule Caller do
              def run, do: :queue.n<caret>ew()
            end
            """
        )
    }

    fun testRenamingACompiledModuleIsRefused() {
        assertRenameRefused(
            """
            defmodule Caller do
              def run, do: Co<caret>de.ensure_loaded(Caller)
            end
            """
        )
    }

    fun testRenamingACompiledTypeIsRefused() {
        assertRenameRefused(
            """
            defmodule Caller do
              @spec run() :: :queue.qu<caret>eue()
              def run, do: :queue.new()
            end
            """
        )
    }

    private fun assertRenameRefused(text: String) {
        myFixture.configureByText("caller.ex", text.trimIndent())
        val targets = myFixture.renameTargetsAtCaret()

        assertNotEmpty(targets)

        for (target in targets) {
            val failure = runCatching { myFixture.renameTarget(target, "renamed") }.exceptionOrNull()

            assertTrue(
                "Renaming $target, declared in compiled code, should be refused, but " +
                    (failure?.let { "failed with $it" } ?: "renamed it"),
                generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains("declared in compiled code") }
            )
        }
    }

    override fun tearDown() {
        runAll(
            { BeamLibraryFixture.removeLibrary(project, myFixture.module, LIBRARY_NAME) },
            { super.tearDown() },
        )
    }

    override fun setUp() {
        super.setUp()
        val beamDirs = listOf(
            testDataPath,
            "testData/org/elixir_lang/code_insight/completion/contributor/call_definition_clause/beam_function/ebin",
        ).map { File(it).absolutePath }
        beamDirs.forEach { VfsRootAccess.allowRootAccess(myFixture.testRootDisposable, it) }
        val beamDirVfs = beamDirs.map { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(it))!! }
        BeamLibraryFixture.addLibrary(project, myFixture.module, LIBRARY_NAME, beamDirVfs)
    }

    private companion object {
        const val LIBRARY_NAME = "compiled_declaration_rename_test_lib"
    }
}
