package org.elixir_lang.model.psi

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.common.runAll
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.code_insight.psiUsagesAtCaret
import org.elixir_lang.code_insight.renameTargetAtCaret
import org.elixir_lang.code_insight.renameTargetsAtCaret
import java.io.File

@Suppress("UnstableApiUsage")
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

    /**
     * A delegation without `as:` to a compiled function renames its head and calls and gains `as:`, and the compiled
     * function keeps its name, at its project call too. Find Usages from the head still lists the compiled declaration
     * and that call.
     */
    fun testRenamingADelegationToACompiledFunctionAddsAs() {
        val text = "defmodule Facade do\n  defdelegate new(), to: :queue\n  def run, do: :queue.new()\n  def use, do: Facade.new()\nend\n"
        myFixture.configureByText("facade.ex", text)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("new()") + 1)

        val usages = myFixture.psiUsagesAtCaret(project)

        assertTrue("the compiled declaration: $usages", usages.any { it.file != myFixture.file })
        assertTrue(
            "the project call of the compiled function: $usages",
            usages.any { it.file == myFixture.file && text.substring(it.range.startOffset).startsWith("new()\n  def use") }
        )

        myFixture.renameTargetAtCaret("fresh")

        assertEquals(
            "defmodule Facade do\n  defdelegate fresh(), to: :queue, as: :new\n  def run, do: :queue.new()\n  def use, do: Facade.fresh()\nend\n",
            myFixture.file.text
        )
    }

    /** From a compiled function, a delegation without `as:` whose head has defaults is a use at the arity it passes on. */
    fun testFindUsagesFromACompiledFunctionListsADelegationWithDefaults() {
        myFixture.configureByText(
            "facade.ex",
            "defmodule Facade do\n  defdelegate eval_string(s, b \\\\ []), to: Code\n  def run, do: Code.eval_<caret>string(\"1\", [])\nend\n"
        )
        val text = myFixture.file.text

        val usages = myFixture.psiUsagesAtCaret(project)

        assertTrue(
            "the delegation's head: $usages",
            usages.any { it.file == myFixture.file && text.substring(it.range.startOffset).startsWith("eval_string(s, b") }
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
