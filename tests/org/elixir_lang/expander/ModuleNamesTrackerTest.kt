package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.PsiManagerEx
import org.elixir_lang.PlatformTestCase

/** [ModuleNamesTracker] moves when a file's module names change, and only then. */
class ModuleNamesTrackerTest : PlatformTestCase() {
    fun testTypingInABodyDoesNotMove() {
        val file = myFixture.addFileToProject("a.ex", "defmodule A do\n  def f, do: 1\nend\n")
        val before = count()

        val body = "defmodule A do\n  def f, do: 1".length

        " + 2 * (3 - 4) |> Enum.map(fn x -> x * 2 end) |> g()".forEachIndexed { i, c -> type(file, body + i, "$c") }

        assertEquals(before, count())
    }

    fun testRenamingAModuleMoves() {
        val file = myFixture.addFileToProject("a.ex", "defmodule A do\nend\n")
        val before = count()

        type(file, "defmodule A".length, "B")

        assertTrue(count() > before)
    }

    fun testANestedModuleMoves() {
        val file = myFixture.addFileToProject("a.ex", "defmodule A do\nend\n")
        val before = count()

        type(file, "defmodule A do\n".length, "  defmodule N do\n  end\n")

        assertTrue(count() > before)
    }

    fun testAModuleInAFileWithNoneMoves() {
        val file = myFixture.addFileToProject("a.ex", "x = 1\n")
        val before = count()

        type(file, "x = 1\n".length, "defmodule M do\nend\n")

        assertTrue(count() > before)
    }

    fun testAProtocolAndAnImplementationMove() {
        val file = myFixture.addFileToProject("a.ex", "x = 1\n")
        val before = count()

        type(file, "x = 1\n".length, "defprotocol P do\nend\n")
        val afterProtocol = count()
        type(file, file.textLength, "defimpl P, for: Atom do\nend\n")

        assertTrue(afterProtocol > before)
        assertTrue(count() > afterProtocol)
    }

    fun testTypingAgainAfterAMoveDoesNotMove() {
        val file = myFixture.addFileToProject("a.ex", "defmodule A do\n  def f, do: 1\nend\n")

        type(file, "defmodule A".length, "B")
        val moved = count()
        type(file, "defmodule AB do\n  def f, do: 1".length, " + 1")

        assertEquals(moved, count())
    }

    fun testSavingAnEditDoesNotMove() {
        val file = myFixture.addFileToProject("a.ex", "defmodule A do\n  def f, do: 1\nend\n")
        type(file, "defmodule A do\n  def f, do: 1".length, " + 1")
        val before = count()

        saveAll()

        assertEquals(before, count())
    }

    fun testAModuleWrittenOnDiskToAFileWithNoPsiMoves() {
        val file = myFixture.addFileToProject("a.ex", "defmodule A do\nend\n").virtualFile
        repeat(3) { System.gc() }
        assertNull("a.ex has no PSI", PsiManagerEx.getInstanceEx(project).fileManager.findCachedViewProvider(file))
        val before = count()

        WriteAction.runAndWait<Throwable> {
            file.setBinaryContent("defmodule A do\nend\n\ndefmodule B do\nend\n".toByteArray())
        }

        assertTrue(count() > before)
    }

    fun testAFileDeletedAfterAnEditIsNotRead() {
        val file = myFixture.addFileToProject("a.ex", "defmodule A do\nend\n")
        type(file, "defmodule A".length, "B")

        WriteAction.runAndWait<Throwable> { file.virtualFile.delete(this) }

        count()
    }

    fun testDeletedFileReleased() {
        val file = myFixture.addFileToProject("a.ex", "defmodule A do\nend\n")
        val nested = myFixture.addFileToProject("dir/b.ex", "defmodule B do\nend\n")
        val tracker = ModuleNamesTracker.getInstance(project)
        type(file, "defmodule A".length, "B")
        type(nested, "defmodule B".length, "C")
        count()
        assertEquals(setOf(file.virtualFile, nested.virtualFile), tracker.named)

        WriteAction.runAndWait<Throwable> { file.virtualFile.delete(this) }
        assertEquals("a deleted file", setOf(nested.virtualFile), tracker.named)

        WriteAction.runAndWait<Throwable> { nested.virtualFile.parent.delete(this) }
        assertEquals("a file in a deleted directory", emptySet<Any>(), tracker.named)
    }

    private fun count(): Long =
        ReadAction.computeBlocking<Long, Throwable> { ModuleNamesTracker.getInstance(project).modificationCount }

    private fun saveAll() {
        WriteAction.runAndWait<Throwable> { FileDocumentManager.getInstance().saveAllDocuments() }
    }

    private fun type(file: PsiFile, offset: Int, text: String) {
        val manager = PsiDocumentManager.getInstance(project)
        val document = manager.getDocument(file)!!

        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(offset, text)
            manager.commitDocument(document)
        }
    }
}
