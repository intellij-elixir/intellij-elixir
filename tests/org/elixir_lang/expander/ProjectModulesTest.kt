package org.elixir_lang.expander

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.PsiFileEx
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.testFramework.IndexingTestUtil
import org.elixir_lang.NameArity
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.elixir_surface.LegManifest
import java.io.File

/**
 * A file's expansion reads the modules other files define through [ProjectModules], and each answer, cached or not,
 * is the one a fresh run gives after any change to what it read.
 */
class ProjectModulesTest : PlatformTestCase() {
    private val holder: ProjectModules get() = ProjectModules.getInstance(project)

    fun testSourceDependency() {
        val a = add("a.ex", A_CALLS_B)
        val b = add("b.ex", "defmodule B do\n  defmacro m, do: :ok\nend\n")
        val u = add("u.ex", "defmodule U do\nend\n")

        val first = expansion(holder, a)
        assertFresh("(a) first ask", a, first)
        assertEquals(listOf(NameArity("m", 0)), (exports(holder, a, "Elixir.B") as ModuleExports.Present).macros)
        assertSame("(a) second ask", first, expansion(holder, a))

        edit(project, b, "defmodule B do\n  defmacro m, do: :changed\nend\n")
        val bodyEdited = expansion(holder, a)
        assertNotSame("(b) B's macro body edited", first, bodyEdited)
        assertFresh("(b) B's macro body edited", a, bodyEdited)

        edit(project, u, "defmodule U do\n  def f, do: 1\nend\n")
        assertSame("(c) an unrelated file edited", bodyEdited, expansion(holder, a))

        edit(project, b, "defmodule B do\n  defmacro m, do: :changed\n  defmacro m3, do: :ok\nend\n")
        val macroAdded = expansion(holder, a)
        assertFresh("(d) a macro added to B", a, macroAdded)
        assertEquals(
            listOf(NameArity("m", 0), NameArity("m3", 0)),
            (exports(holder, a, "Elixir.B") as ModuleExports.Present).macros,
        )

        edit(project, b, "defmodule B do\n  defmacro m2, do: :ok\nend\n")
        expansion(holder, b)
        val renamed = expansion(holder, a)
        assertNotSame("(e) B asked first after m is renamed m2", macroAdded, renamed)
        assertFresh("(e) B asked first after m is renamed m2", a, renamed)
    }

    fun testStructRead() {
        addLegElixir()
        val a = add("a.ex", "defmodule A do\n  %B{y: 1}\nend\n")
        val b = add("b.ex", "defmodule B do\n  defstruct [:x]\nend\n")

        val before = render(a, expansion(holder, a))
        edit(project, b, "defmodule B do\n  defstruct [:x, :y]\nend\n")
        val after = expansion(holder, a)

        assertFresh("B's struct gains :y", a, after)
        assertFalse("A's answer moves", before == render(a, after))
    }

    fun testTwoModulesDown() {
        val a = add("a.ex", A_CALLS_B)
        add("b.ex", "defmodule B do\n  require C\n  defmacro m, do: :ok\nend\n")
        val c = add("c.ex", "defmodule C do\nend\n")
        add("d.ex", "defmodule D do\n  defmacro m, do: :ok\nend\n")

        val before = render(a, expansion(holder, a))
        edit(project, c, "defmodule C do\n  require D\n  D.m()\nend\n")
        val after = expansion(holder, a)

        assertFresh("C stops at D.m()", a, after)
        assertFalse("A's answer moves", before == render(a, after))
    }

    fun testAbsentThenDefined() {
        val created = add("created.ex", "defmodule Created do\n  require D\nend\n")
        val outside = add("outside.ex", "defmodule Outside do\n  require E\nend\n")
        val edited = add("edited.ex", "defmodule Edited do\n  require F\nend\n")
        val host = add("host.ex", "defmodule Host do\nend\n")
        val createdBefore = render(created, expansion(holder, created))
        expansion(holder, outside)

        add("d.ex", "defmodule D do\nend\n")
        val createdAfter = expansion(holder, created)
        assertFresh("(a) D defined in a new file under content", created, createdAfter)
        assertFalse("(a) the answer moves", createdBefore == render(created, createdAfter))

        val elsewhere = FileUtil.createTempDirectory("project_modules", null, true)
        VfsRootAccess.allowRootAccess(testRootDisposable, elsewhere.path)
        val elsewhereRoot = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(elsewhere)!!
        WriteAction.runAndWait<Throwable> {
            elsewhereRoot.createChildData(this, "e.ex").setBinaryContent("defmodule E do\nend\n".toByteArray())
        }
        assertFresh("(c) E defined in a new file outside content", outside, expansion(holder, outside))

        val editedBefore = render(edited, expansion(holder, edited))
        edit(project, host, "defmodule Host do\nend\n\ndefmodule F do\nend\n")
        val editedAfter = expansion(holder, edited)
        assertFresh("(d) F defined by an edit to an existing file", edited, editedAfter)
        assertFalse("(d) the answer moves", editedBefore == render(edited, editedAfter))
    }

    fun testAModuleDefinedOnDiskInAFileWithNoTree() {
        val edited = add("edited.ex", "defmodule Edited do\n  require F\n  require G\nend\n")
        val unloaded = add("unloaded.ex", "defmodule Unloaded do\nend\n").virtualFile
        val held = add("held.ex", "defmodule Held do\nend\n").virtualFile
        val before = render(edited, expansion(holder, edited))
        repeat(3) { System.gc() }
        assertNull("unloaded.ex has no PSI", PsiManagerEx.getInstanceEx(project).fileManager.findCachedViewProvider(unloaded))
        val heldFile = psi(held)
        assertFalse("held.ex's tree is let go", (heldFile as PsiFileEx).isContentsLoaded)

        rewrite(unloaded, "defmodule Unloaded do\nend\n\ndefmodule F do\nend\n")
        val afterUnloaded = expansion(holder, edited)
        assertFresh("F defined on disk in a file with no PSI", edited, afterUnloaded)
        assertFalse("F defined on disk in a file with no PSI: the answer moves", before == render(edited, afterUnloaded))

        rewrite(held, "defmodule Held do\nend\n\ndefmodule G do\nend\n")
        val afterHeld = expansion(holder, edited)
        assertFresh("G defined on disk in a file whose PSI has no tree", edited, afterHeld)
        assertFalse(
            "G defined on disk in a file whose PSI has no tree: the answer moves",
            render(edited, afterUnloaded) == render(edited, afterHeld),
        )
        assertTrue(heldFile.isValid)
    }

    fun testTypingInABodyRecomputesNothing() {
        val asker = add("asker.ex", "defmodule Asker do\n  require Missing\n  require B\nend\n")
        add("b.ex", "defmodule B do\nend\n")
        val typed = add("typed.ex", "defmodule Typed do\n  def f, do: 1\nend\n")
        val held = expansion(holder, asker)
        val body = "defmodule Typed do\n  def f, do: 1".length
        val manager = PsiDocumentManager.getInstance(project)

        " + 2 * (3 - 4) |> Enum.map(fn x -> x * 2 end) |> g()".forEachIndexed { i, character ->
            WriteCommandAction.runWriteCommandAction(project) {
                manager.getDocument(typed)!!.insertString(body + i, "$character")
                manager.commitAllDocuments()
            }

            assertSame("after ${i + 1} characters", held, expansion(holder, asker))
        }
    }

    fun testSeveralCopiesUnreadable() {
        val a = add("a.ex", "defmodule A do\n  require B\nend\n")
        add("b1.ex", "defmodule B do\n  def f, do: 1\nend\n")
        val b2 = add("b2.ex", "defmodule B do\n  def f, do: 1\nend\n")

        assertEquals(ModuleExports.Unreadable, exports(holder, a, "Elixir.B"))
        val several = expansion(holder, a)
        assertFresh("two copies", a, several)

        edit(project, b2, "defmodule B2 do\n  def f, do: 1\nend\n")

        assertTrue(exports(holder, a, "Elixir.B") is ModuleExports.Present)
        assertFresh("one copy left", a, expansion(holder, a))
    }

    fun testLibraryReadsBeam() {
        val directory = FileUtil.createTempDirectory("project_modules_ebin", null, true)
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.path)
        val beam = File("testData/org/elixir_lang/expander/loaded_copy/Elixir.LoadedCopy.Dep.beam")
        beam.copyTo(File(directory, beam.name))
        val ebin = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
        BeamLibraryFixture.addLibrary(project, module, "project_modules_ebin", listOf(ebin))
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val a = add("a.ex", "defmodule A do\n  require LoadedCopy.Dep\nend\n")

        assertEquals(
            ModuleExports.Present(listOf(NameArity("f", 0)), emptyList(), true, ModuleExports.Behaviour.None),
            exports(holder, a, "Elixir.LoadedCopy.Dep"),
        )
        val before = expansion(holder, a)
        assertFresh("the .beam read", a, before)

        val rebuilt = File("testData/org/elixir_lang/expander/loaded_copy/changed/${beam.name}").readBytes()
        WriteAction.runAndWait<Throwable> { ebin.findChild(beam.name)!!.setBinaryContent(rebuilt) }

        assertEquals(
            ModuleExports.Present(listOf(NameArity("g", 0)), emptyList(), true, ModuleExports.Behaviour.None),
            exports(holder, a, "Elixir.LoadedCopy.Dep"),
        )
        val after = expansion(holder, a)
        assertNotSame("the .beam rebuilt", before, after)
        assertFresh("the .beam rebuilt", a, after)
    }

    /** The leg's Elixir `.beam`s as a library, which a `defstruct` dispatches to. */
    private fun addLegElixir() {
        val directory = LegManifest.ebin()
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.path)
        val ebin = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
        BeamLibraryFixture.addLibrary(project, module, "leg_elixir", listOf(ebin))
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun add(path: String, text: String): PsiFile = myFixture.addFileToProject(path, text)

    private fun assertFresh(message: String, file: PsiFile, expansion: FileExpansion) =
        assertEquals(message, render(file, freshExpansion(project, file)), render(file, expansion))

    /** [file]'s content replaced on disk, as a checkout or another editor writes it, with no document. */
    private fun rewrite(file: VirtualFile, text: String) {
        WriteAction.runAndWait<Throwable> { file.setBinaryContent(text.toByteArray()) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun psi(virtualFile: VirtualFile): PsiFile = PsiManager.getInstance(project).findFile(virtualFile)!!

    private companion object {
        const val A_CALLS_B = "defmodule A do\n  require B\n  B.m()\nend\n"
    }
}
