package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.AbstractProgressIndicatorBase
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.PsiFileEx
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import org.elixir_lang.NameArity
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.elixir_surface.LegManifest
import org.elixir_lang.language_level.ElixirLanguageLevel
import org.elixir_lang.psi.ElixirFile
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

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

    /** As Elixir makes each module available once its own `defmodule` compiles, while the rest of its file compiles. */
    fun testAModuleFinishedEarlierInARunningFileIsLoaded() {
        val a = add("a.ex", "defmodule A1 do\n  defmacro m, do: :ok\nend\n\ndefmodule A2 do\n  require B\nend\n")
        val b = add("b.ex", "defmodule B do\n  require A1\nend\n")
        val x = add("x.ex", "defmodule X do\nend\n")

        inBothOrders("A1 then A2, which requires B, which requires A1", a, b, x, legInputs) { ofA, ofB ->
            val rendered = render(a, ofA) + "\n" + render(b, ofB)

            assertTrue(rendered, render(a, ofA).contains("Elixir.A1 compiled"))
            assertTrue(rendered, render(a, ofA).contains("Elixir.A2 compiled"))
            assertTrue(rendered, render(b, ofB).contains("Elixir.B compiled"))
        }
    }

    /** B stops at `A1.m()`, as a macro from another source file isn't expanded, but no module reads as unloaded. */
    fun testAMacroFinishedEarlierInARunningFileIsLoaded() {
        val a = add("a.ex", "defmodule A1 do\n  defmacro m, do: :ok\nend\n\ndefmodule A2 do\n  require B\nend\n")
        val b = add("b.ex", "defmodule B do\n  require A1\n  A1.m()\nend\n")
        val x = add("x.ex", "defmodule X do\nend\n")

        inBothOrders("A1 then A2, which requires B, which calls A1's macro", a, b, x, legInputs) { ofA, ofB ->
            val rendered = render(a, ofA) + "\n" + render(b, ofB)

            assertFalse(rendered, rendered.contains("unloaded_module"))
        }
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

    fun testCycleAnswersAbsent() {
        val a = add("a.ex", "")
        val b = add("b.ex", "")
        val c = add("c.ex", "defmodule C do\n  require D\nend\n\ndefmodule D do\n  def f, do: :ok\nend\n")
        val x = add("x.ex", "defmodule X do\nend\n")

        for (version in listOf("1.20.4", "1.12.3")) {
            val inputs = ProjectModules.Inputs(ElixirLanguageLevel.of(version), legKernel)

            edit(project, a, "defmodule A do\n  require B\n  B.m()\n  defmacro n, do: :ok\nend\n")
            edit(project, b, "defmodule B do\n  require A\n  A.n()\n  defmacro m, do: :ok\nend\n")
            inBothOrders("$version, each requires the other", a, b, x, inputs) { ofA, ofB ->
                assertTrue(render(a, ofA).contains("Elixir.A raised unloaded_module `require B`"))
                assertTrue(render(b, ofB).contains("Elixir.B raised unloaded_module `require A`"))
            }
            val holder = ProjectModules(project)
            assertEquals(ModuleExports.Absent, exports(holder, a, "Elixir.B", inputs))
            assertFresh("$version, A after exports(B) from A", a, expansion(holder, a, inputs), inputs)
            assertFresh("$version, B after exports(B) from A", b, expansion(holder, b, inputs), inputs)

            edit(project, a, "defmodule A do\n  def g, do: :ok\n  def f, do: B.f()\nend\n")
            edit(project, b, "defmodule B do\n  def f, do: A.g()\nend\n")
            inBothOrders("$version, each calls the other unrequired", a, b, x, inputs) { ofA, ofB ->
                assertTrue(render(a, ofA).contains("Elixir.A compiled"))
                assertTrue(render(b, ofB).contains("Elixir.B compiled"))
            }

            val later = expansion(ProjectModules(project), c, inputs)
            assertFresh("$version, a call to a module defined later in the file", c, later, inputs)
            assertTrue(render(c, later).contains("Elixir.C raised unloaded_module `require D`"))
        }

        val level = ProjectModules.Inputs(ElixirLanguageLevel.of("1.12.3"), legKernel)
        edit(project, a, "defmodule A do\n  def f, do: B.f()\n  defmacro g, do: :ok\nend\n")
        edit(project, b, "defmodule B do\n  def f, do: A.g()\nend\n")
        inBothOrders("1.12.3, B calls A's macro unrequired", a, b, x, level) { _, ofB ->
            assertTrue(render(b, ofB).contains("Elixir.B stopped `A.g()`"))
        }
    }

    fun testOtherInputsRecompute() {
        val a = add("a.ex", "defmodule A do\n  def f, do: B.f()\n  defmacro g, do: :ok\nend\n")
        val b = add("b.ex", "defmodule B do\n  def f, do: A.g()\nend\n")
        val newer = ProjectModules.Inputs(ElixirLanguageLevel.of("1.20.4"), legKernel)
        val older = ProjectModules.Inputs(ElixirLanguageLevel.of("1.12.3"), legKernel)
        val holder = ProjectModules(project)
        expansion(holder, a, newer)
        expansion(holder, b, newer)

        val recomputed = expansion(holder, b, older)

        assertFresh("B at 1.12.3 after A and B at 1.20.4", b, recomputed, older)
        assertTrue(render(b, recomputed), render(b, recomputed).contains("Elixir.B stopped `A.g()`"))
    }

    fun testWarmAskLoadsNoTree() {
        val a = add("a.ex", "defmodule A do\nend\n").virtualFile
        val b = add("b.ex", "defmodule B do\n  def f, do: 1\nend\n").virtualFile
        val cold = exports(holder, psi(a), "Elixir.B")
        repeat(3) { System.gc() }
        assertFalse("b.ex's tree is let go", (psi(b) as PsiFileEx).isContentsLoaded)
        val loaded = mutableListOf<String>()
        val recording = Disposer.newDisposable()
        PsiManagerEx.getInstanceEx(project).setAssertOnFileLoadingFilter(
            {
                loaded += it.name
                false
            },
            recording,
        )

        try {
            assertEquals(cold, exports(holder, psi(a), "Elixir.B"))
        } finally {
            Disposer.dispose(recording)
        }

        assertEquals(emptyList<String>(), loaded)
    }

    fun testDumbAnswersUnreadableUncached() {
        val a = add("a.ex", A_CALLS_B)
        add("b.ex", "defmodule B do\n  defmacro m, do: :ok\nend\n")
        var dumb: FileExpansion? = null

        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertEquals(ModuleExports.Unreadable, exports(holder, a, "Elixir.B"))
            dumb = expansion(holder, a)
            assertTrue(render(a, dumb).contains("Elixir.A stopped `require B`"))
            assertNotSame("asked again while dumb", dumb, expansion(holder, a))
        }

        val smart = expansion(holder, a)
        assertNotSame("asked once smart", dumb, smart)
        assertFresh("asked once smart", a, smart)
    }

    /**
     * Below 1.13 every remote call reads its receiver's exports, so eight files that each call the others form one
     * group of re-entries. A value that read a file in progress is reused within the outermost call wherever it would
     * read the same, so the group costs a bounded multiple of one file whose calls go to absent modules, not one
     * expansion per path through the group.
     */
    fun testAGroupOfFilesThatCallEachOtherIsExpandedOncePerState() {
        val older = ProjectModules.Inputs(ElixirLanguageLevel.of("1.12.3"), legKernel)
        val n = 8
        val group = (1..n).map { i ->
            val calls = (1..n).filter { it != i }.joinToString(", ") { "M$it.f()" }
            add("m$i.ex", "defmodule M$i do\n  def f, do: [$calls]\nend\n")
        }
        val calls = (2..n).joinToString(", ") { "Absent$it.f()" }
        val alone = add("alone.ex", "defmodule Alone do\n  def f, do: [$calls]\nend\n")
        val x = add("x.ex", "defmodule X do\nend\n")
        // The first run also loads what later runs find cached, so it makes more checks.
        expandUnder(ProjectModules(project), alone, 0, older)
        val unit = expandUnder(ProjectModules(project), alone, 0, older)
        val holder = ProjectModules(project)

        val checks = expandUnder(holder, group.first(), 0, older)

        // Measured at 1.20.4's leg: 534 times with the reuse, 15,597 times without.
        assertTrue("$checks checks against $unit for one file", checks < 1000 * unit)
        assertFresh("M1", group.first(), expansion(holder, group.first(), older), older)

        for (i in 1..n) {
            assertEquals(
                "M$i from x.ex",
                exports(ProjectModules(project), x, "Elixir.M$i", older),
                exports(holder, x, "Elixir.M$i", older),
            )
        }
    }

    /**
     * C requires A1 while A0 is still compiling, so C raises. Once A1 has finished, A2's `require D` reaches C again,
     * which now compiles, so D stops at C's remote macro, as it does from a fresh run of D.
     */
    fun testAFileExpandedWhileAModuleWasUnfinishedIsExpandedAgainOnceItFinishes() {
        val older = ProjectModules.Inputs(ElixirLanguageLevel.of("1.12.3"), legKernel)
        val a = add(
            "a.ex",
            "defmodule A0 do\n  def g, do: C.f()\nend\n\ndefmodule A1 do\nend\n\ndefmodule A2 do\n  require D\nend\n",
        )
        add("c.ex", "defmodule C do\n  require A1\n  defmacro f, do: :ok\nend\n")
        add("d.ex", "defmodule D do\n  def h, do: C.f()\nend\n")

        val rendered = render(a, expansion(ProjectModules(project), a, older))

        assertTrue(rendered, rendered.contains("Elixir.A2 stopped `require D`"))
    }

    /**
     * A0 reaches C, which requires X from f.ex once f.ex has finished, and defines the macro `x`. A2's `require G` then
     * compiles f.ex, whose G calls `C.x()` while X is unfinished, so C raises at `require X` this time: below 1.13 G's
     * call reads C's exports, finds none, and is a function call, so G and A2 compile.
     */
    fun testAFileThatReadAnotherAsLoadedIsExpandedAgainWhileThatOneIsCompiling() {
        val older = ProjectModules.Inputs(ElixirLanguageLevel.of("1.12.3"), legKernel)
        val a = add("a.ex", "defmodule A0 do\n  def g, do: C.y()\nend\n\ndefmodule A2 do\n  require G\nend\n")
        add("c.ex", "defmodule C do\n  require X\n  def y, do: A2.h()\n  defmacro x, do: :ok\nend\n")
        add("f.ex", "defmodule G do\n  def g, do: C.x()\nend\n\ndefmodule X do\nend\n")

        val rendered = render(a, expansion(ProjectModules(project), a, older))

        assertTrue(rendered, rendered.contains("Elixir.A2 compiled"))
    }

    /**
     * A0's call reads C while A1 is unfinished, so C raises at `require A1`. Once A1 has finished, A2's `require C`
     * reads C again, which now compiles and defines `f`, as Elixir compiles all three.
     */
    fun testLaterModuleRereads() {
        val older = ProjectModules.Inputs(ElixirLanguageLevel.of("1.12.3"), legKernel)
        val a = add(
            "a.ex",
            "defmodule A0 do\n  def g, do: C.f()\nend\n\ndefmodule A1 do\nend\n\ndefmodule A2 do\n  require C\nend\n",
        )
        val c = add("c.ex", "defmodule C do\n  require A1\n  defmacro f, do: :ok\nend\n")
        val x = add("x.ex", "defmodule X do\nend\n")
        val holder = ProjectModules(project)

        val rendered = render(a, expansion(holder, a, older))

        assertFalse(rendered, rendered.contains("unloaded_module"))
        assertTrue(rendered, rendered.contains("Elixir.A2 compiled"))
        assertFresh("C after a.ex", c, expansion(holder, c, older), older)

        for (atom in listOf("Elixir.A0", "Elixir.A1", "Elixir.A2", "Elixir.C")) {
            assertEquals(
                "$atom from x.ex",
                exports(ProjectModules(project), x, atom, older),
                exports(holder, x, atom, older),
            )
        }
    }

    /**
     * A0's call reads C while A1 is unfinished: C passes `A1.m()` as a function call and stops at X's macro. A2 reads C
     * again once A1 has finished, and C ends at `A1.m()` without reading X. A0's answer still came from X: editing X's
     * macro into a function lets C compile, and A0 with it.
     */
    fun testASupersededAnswerKeepsWhatItRead() {
        val older = ProjectModules.Inputs(ElixirLanguageLevel.of("1.12.3"), legKernel)
        val a = add(
            "a.ex",
            "defmodule A0 do\n  def g, do: C.f()\nend\n\ndefmodule A1 do\n  defmacro m, do: :ok\nend\n\n" +
                "defmodule A2 do\n  require C\nend\n",
        )
        add("c.ex", "defmodule C do\n  A1.m()\n  require X\n  X.k()\n  def f, do: :ok\nend\n")
        val x = add("x.ex", "defmodule X do\n  defmacro k, do: :ok\nend\n")
        val holder = ProjectModules(project)
        val before = expansion(holder, a, older)
        assertFresh("X's macro", a, before, older)

        edit(project, x, "defmodule X do\n  def k, do: :ok\nend\n")
        val after = expansion(holder, a, older)

        assertFresh("X's macro edited into a function", a, after, older)
        assertFalse(render(a, after), render(a, before) == render(a, after))
    }

    fun testCancelledCachesNothing() {
        val a = add("a.ex", A_CALLS_B)
        add(
            "b.ex",
            "defmodule B do\n  def f(x), do: x + 1\n  def g(x), do: f(x) * 2\n  def h(x, y), do: {g(x), f(y), [x, y]}\n" +
                "  defmacro m, do: :ok\nend\n",
        )
        val x = add("x.ex", "defmodule X do\nend\n")
        // The first run also loads what later runs find cached, so it makes more checks.
        expandUnder(ProjectModules(project), a, cancelAt = 0)
        val checks = expandUnder(ProjectModules(project), a, cancelAt = 0)

        for (n in 1 until checks step maxOf(1, checks / 12)) {
            val holder = ProjectModules(project)

            try {
                expandUnder(holder, a, n)
                fail("no cancel at check $n of $checks")
            } catch (_: ProcessCanceledException) {
            }

            assertFresh("A asked after a cancel at check $n of $checks", a, expansion(holder, a))

            for (atom in listOf("Elixir.A", "Elixir.B")) {
                assertEquals(
                    "$atom from x.ex after a cancel at check $n of $checks",
                    exports(ProjectModules(project), x, atom),
                    exports(holder, x, atom),
                )
            }
        }
    }

    /**
     * Elixir compiles `a.ex` before `b.ex` here, so `A` is loaded when `b.ex` expands. Compiled together, a call in a
     * module body to a module not yet required races its load, as `code:ensure_loaded` doesn't wait for it.
     */
    fun testDeprecatedInAnotherFile() {
        val a = add("a.ex", DEPRECATED)
        val bodies = listOf(
            "defmodule B do\n  A.old()\nend\n",
            "defmodule B do\n  def f, do: A.old()\nend\n",
            "defmodule B do\n  require A\n  A.old_m()\nend\n",
            "defmodule B do\n  require A\n  def f, do: A.old_m()\nend\n",
            "defmodule B do\n  import A\n  old()\n  old_m()\nend\n",
        )
        val b = add("b.ex", bodies.first())
        val expected = bodies.map { DeprecationWarnings.compiled(listOf("a.ex" to DEPRECATED, "b.ex" to it)) }
        val actual = bodies.map {
            edit(project, b, it)
            DeprecationWarnings.of(expansion(holder, b))
        }

        assertTrue("no case warns", expected.any { it.isNotEmpty() })
        assertEquals(show(bodies, expected), show(bodies, actual))

        edit(project, b, bodies.first())
        DeprecationWarnings.of(expansion(holder, b))
        edit(project, a, DEPRECATED.replace("Use A.new/0", "Use A.newer/0"))
        assertEquals(
            listOf("A.old/0 is deprecated. Use A.newer/0 instead line 2"),
            DeprecationWarnings.of(expansion(holder, b)),
        )
    }

    /**
     * A definition that isn't a statement may take the attributes at any point, so whether a later one is deprecated
     * is unknown. Elixir warns of both calls; with one definition's reason unknown, none of the module's is read.
     */
    fun testAnUnknownDeprecationWarnsNothing() {
        add(
            "a.ex",
            "defmodule A do\n  @deprecated \"Use A.new/0 instead\"\n  case 1 do\n    _ -> def old, do: 1\n  end\n" +
                "  @deprecated \"Use A.new_m/0 instead\"\n  defmacro old_m, do: 1\n  def plain, do: 1\nend\n",
        )
        val b = add("b.ex", "defmodule B do\n  require A\n  A.old()\n  A.old_m()\nend\n")

        assertEquals(emptyList<String>(), DeprecationWarnings.of(expansion(holder, b)))
        assertEquals(Deprecations.Unreadable, (exports(holder, b, "Elixir.A") as ModuleExports.Present).deprecated)
    }

    /**
     * A new holder per order asks [a] then [b], and [b] then [a], and each answer equals a fresh run's, as does each module's
     * exports asked from [asker] afterwards.
     */
    private fun inBothOrders(
        label: String,
        a: PsiFile,
        b: PsiFile,
        asker: PsiFile,
        inputs: ProjectModules.Inputs,
        check: (ofA: FileExpansion, ofB: FileExpansion) -> Unit,
    ) {
        for ((first, second) in listOf(a to b, b to a)) {
            val holder = ProjectModules(project)
            val firstExpansion = expansion(holder, first, inputs)
            val secondExpansion = expansion(holder, second, inputs)
            assertFresh("$label, ${first.name} first", first, firstExpansion, inputs)
            assertFresh("$label, ${first.name} first, then ${second.name}", second, secondExpansion, inputs)

            for (atom in listOf("Elixir.A", "Elixir.B")) {
                assertEquals(
                    "$label, ${first.name} first, then $atom from ${asker.name}",
                    exports(ProjectModules(project), asker, atom, inputs),
                    exports(holder, asker, atom, inputs),
                )
            }

            if (first == a) check(firstExpansion, secondExpansion) else check(secondExpansion, firstExpansion)
        }
    }

    /** Expands [file], cancelled at this thread's [cancelAt]th check if that is above 0, and counts the checks. */
    private fun expandUnder(holder: ProjectModules, file: PsiFile, cancelAt: Int, inputs: ProjectModules.Inputs = legInputs): Int {
        val indicator = CancelAt(if (cancelAt > 0) cancelAt else Int.MAX_VALUE)

        whileCheckingIndicators {
            ProgressManager.getInstance().runProcess(
                { ReadAction.computeBlocking<FileExpansion, Throwable> { holder.expansion(file as ElixirFile, inputs) } },
                indicator,
            )
        }

        return indicator.checks
    }

    /**
     * Runs [block] while another thread runs under a cancelled indicator: only then does `ProgressManager.checkCanceled`
     * call each thread's indicator.
     */
    private fun whileCheckingIndicators(block: () -> Unit) {
        val running = CountDownLatch(1)
        val done = CountDownLatch(1)
        val other = thread {
            val cancelled = EmptyProgressIndicator()

            try {
                ProgressManager.getInstance().runProcess(
                    {
                        cancelled.cancel()
                        running.countDown()
                        done.await()
                    },
                    cancelled,
                )
            } catch (_: ProcessCanceledException) {
            }
        }
        running.await()

        try {
            block()
        } finally {
            done.countDown()
            other.join()
        }
    }

    /** Cancelled from its [n]th check on the thread that made it. */
    private class CancelAt(private val n: Int) : AbstractProgressIndicatorBase() {
        private val owner = Thread.currentThread()

        var checks = 0
            private set

        override fun isCanceled(): Boolean = checks >= n

        override fun checkCanceled() {
            if (Thread.currentThread() === owner) checks++

            super.checkCanceled()
        }
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

    /** [file]'s content replaced on disk, as a checkout or another editor writes it, with no document. */
    private fun rewrite(file: VirtualFile, text: String) {
        WriteAction.runAndWait<Throwable> { file.setBinaryContent(text.toByteArray()) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun psi(virtualFile: VirtualFile): PsiFile = PsiManager.getInstance(project).findFile(virtualFile)!!

    private fun assertFresh(
        message: String,
        file: PsiFile,
        expansion: FileExpansion,
        inputs: ProjectModules.Inputs = legInputs,
    ) = assertEquals(message, render(file, freshExpansion(project, file, inputs)), render(file, expansion))

    private fun show(bodies: List<String>, warnings: List<List<String>>) =
        bodies.indices.joinToString("\n\n") { "${bodies[it]}  => ${warnings[it].ifEmpty { listOf("none") }}" }

    private companion object {
        const val A_CALLS_B = "defmodule A do\n  require B\n  B.m()\nend\n"

        const val DEPRECATED =
            "defmodule A do\n  @deprecated \"Use A.new/0 instead\"\n  def old, do: 1\n" +
                "  @deprecated \"Use A.new_m/0 instead\"\n  defmacro old_m, do: 1\nend\n"
    }
}
