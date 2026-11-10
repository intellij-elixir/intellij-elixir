package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.util.concurrency.AppExecutorUtil
import org.elixir_lang.PlatformTestCase
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

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

    fun testTwoReadersNameAFileOnce() {
        val gate = Gate()
        val named = ConcurrentLinkedQueue<VirtualFile>()
        val tracker = tracker(naming = { named += it; gate.hold(); namesOf(it) })
        val a = myFixture.addFileToProject("a.ex", "").virtualFile

        val first = read(tracker)
        val second: Future<Long>
        try {
            assertTrue("the first reader names a.ex", gate.entered.await(10, SECONDS))
            second = read(tracker)
            // Long enough for the second to name a.ex too, if it would.
            try {
                second.get(5, SECONDS)
            } catch (_: TimeoutException) {
            }
        } finally {
            gate.open.countDown()
        }
        first.get(10, SECONDS)
        second.get(10, SECONDS)
        count(tracker)

        assertEquals(listOf(a), named.toList())
    }

    fun testAReaderFindingAFileClaimedReturnsWithoutWaiting() {
        val gate = Gate()
        val tracker = tracker(naming = { gate.hold(); namesOf(it) })
        myFixture.addFileToProject("a.ex", "")

        assertASecondReaderGoesOn(tracker, gate, "names a.ex", "naming a.ex")
    }

    fun testAnArrivedDirectoryIsWalkedOnceAndTheSecondReaderDoesNotWait() {
        val x = myFixture.addFileToProject("d/x.ex", "").virtualFile
        val d = x.parent
        val e = myFixture.tempDirFixture.findOrCreateDir("e")
        val gate = Gate()
        val walks = AtomicInteger()
        val named = ConcurrentLinkedQueue<VirtualFile>()
        val tracker = tracker(
            naming = { named += it; namesOf(it) },
            walk = { directory, found ->
                walks.incrementAndGet()
                gate.hold()
                ModuleNamesTracker.walkFiles(directory, found)
            },
        )
        WriteAction.runAndWait<Throwable> { d.move(this, e) }

        assertASecondReaderGoesOn(tracker, gate, "walks d", "walking d")
        count(tracker)

        assertEquals("walks of d", 1, walks.get())
        assertEquals(listOf(x), named.toList())
    }

    fun testACancelLeavesTheRestWaitingAndTheNextReadNamesThem() {
        val indicator = EmptyProgressIndicator()
        val named = Collections.synchronizedList(ArrayList<VirtualFile>())
        val tracker = tracker(naming = {
            named += it
            if (named.size == 2) {
                indicator.cancel()
                ProgressManager.checkCanceled()
            }
            namesOf(it)
        })
        val atoms = listOf("Elixir.A", "Elixir.B", "Elixir.C")
        val before = atoms.associateWith { tracker.name(it).modificationCount }
        val files = listOf("a", "b", "c").map { myFixture.addFileToProject("$it.ex", "").virtualFile }

        try {
            ProgressManager.getInstance().runProcess({ count(tracker) }, indicator)
            fail("the read is cancelled")
        } catch (_: ProcessCanceledException) {
        }
        val firstRead = named.toList()
        assertEquals("named before the cancel", 2, firstRead.size)

        count(tracker)

        assertEquals("named by the next read", (files - firstRead.first()).toSet(), named.drop(2).toSet())
        assertEquals("named by the next read, once each", 2, named.size - 2)
        assertEquals(
            "each name moved once",
            atoms.associateWith { before.getValue(it) + 1 },
            atoms.associateWith { tracker.name(it).modificationCount },
        )
    }

    fun testACancelInDumbModeLeavesNoNameUnmoved() {
        val named = ConcurrentLinkedQueue<VirtualFile>()
        val tracker = tracker(naming = { named += it; namesOf(it) })
        val atoms = listOf("Elixir.A", "Elixir.B")
        val before = atoms.associateWith { tracker.name(it).modificationCount }

        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            listOf("a", "b").forEach { myFixture.addFileToProject("$it.ex", "") }
            // The second file's check, after the first is taken.
            assertCancelledIn(ModuleNamesTracker::class.java.name, "claiming", 2) { count(tracker) }
        }
        count(tracker)

        assertEquals("files left to the next read", 1, named.size)
        assertEquals(
            "names moved",
            atoms,
            atoms.filter { tracker.name(it).modificationCount != before.getValue(it) },
        )
    }

    fun testAnAskerPathHashingNegativeHoldsWhatIsHeldOnIt() {
        val directory = myFixture.tempDirFixture.findOrCreateDir("p").path
        // The fixture's directory changes the hash, so the name is found here.
        val name = generateSequence(0) { it + 1 }.map { "f$it.ex" }.first { "$directory/$it".hashCode() < 0 }
        val file = myFixture.addFileToProject("p/$name", "").virtualFile
        assertTrue("${file.path} hashes negative", file.path.hashCode() < 0)
        val askerPath = ModuleNamesTracker.getInstance(project).askerPath(file)

        assertTrue("askerPath's count is non-negative", askerPath.modificationCount >= 0)
        assertHolds(askerPath)
    }

    fun testAnAskerMovedToAPathOfTheSameHashMoves() {
        val file = myFixture.addFileToProject("Aa/x.ex", "").virtualFile
        val to = myFixture.tempDirFixture.findOrCreateDir("BB")
        assertEquals("the two paths hash alike", file.path.hashCode(), "${to.path}/x.ex".hashCode())
        val askerPath = ModuleNamesTracker.getInstance(project).askerPath(file)
        val before = askerPath.modificationCount

        WriteAction.runAndWait<Throwable> { file.move(this, to) }

        assertFalse("askerPath moved with the asker", askerPath.modificationCount == before)
    }

    fun testTwoNamesDifferingInCaseUnderAWslPathStayTwo() {
        val tracker = ModuleNamesTracker.getInstance(project)

        // A WSL distro's file system is case-sensitive even where the host's isn't.
        assertNotSame(tracker.path("//wsl.localhost/Ubuntu/p/lib/Foo.ex"), tracker.path("//wsl.localhost/Ubuntu/p/lib/foo.ex"))
    }

    fun testALightAskerHoldsWhatIsHeldOnIt() {
        assertHolds(ModuleNamesTracker.getInstance(project).askerPath(null))
    }

    fun testADirectoryMovedOrRenamedOutsideTheProjectIsNotWalked() {
        val walks = AtomicInteger()
        val tracker = tracker(naming = ::namesOf, walk = { directory, found -> walks.incrementAndGet(); ModuleNamesTracker.walkFiles(directory, found) })
        outside("elsewhere") { outside ->
            val (d, e) = WriteAction.computeAndWait<Pair<VirtualFile, VirtualFile>, Throwable> {
                outside.createChildDirectory(this, "d").also { it.createChildData(this, "x.ex") } to outside.createChildDirectory(this, "e")
            }
            count(tracker)
            WriteAction.runAndWait<Throwable> { d.move(this, e) }
            WriteAction.runAndWait<Throwable> { e.rename(this, "f") }
            count(tracker)

            assertEquals("walks", 0, walks.get())
        }
    }

    fun testADirectoryMovedOutOfTheProjectIsWalked() {
        val named = ConcurrentLinkedQueue<VirtualFile>()
        val tracker = tracker(naming = { named += it; namesOf(it) })
        val x = myFixture.addFileToProject("d/x.ex", "").virtualFile
        outside("elsewhere") { outside ->
            count(tracker)
            named.clear()
            WriteAction.runAndWait<Throwable> { x.parent.move(this, outside) }
            count(tracker)

            assertEquals(listOf(x), named.toList())
        }
    }

    fun testAFileWrittenOutsideTheProjectIsNotNamed() {
        val named = ConcurrentLinkedQueue<VirtualFile>()
        val tracker = tracker(naming = { named += it; namesOf(it) })
        outside("outside") { outside ->
            val a = WriteAction.computeAndWait<VirtualFile, Throwable> { outside.createChildData(this, "a.ex") }
            count(tracker)
            WriteAction.runAndWait<Throwable> { a.setBinaryContent("defmodule A do\nend\n".toByteArray()) }
            count(tracker)

            assertEquals(emptyList<VirtualFile>(), named.toList())
        }
    }

    /** Holds the thread calling [hold] until [open] is counted down, after counting down [entered]. */
    private class Gate {
        val entered = CountDownLatch(1)
        val open = CountDownLatch(1)

        fun hold() {
            entered.countDown()
            check(open.await(30, SECONDS)) { "the gate never opened" }
        }
    }

    /** While a first read of [tracker] is held at [gate], [doing], a second read returns: it goes on without the first's work. */
    private fun assertASecondReaderGoesOn(tracker: ModuleNamesTracker, gate: Gate, doing: String, stillDoing: String) {
        val first = read(tracker)
        try {
            assertTrue("the first reader $doing", gate.entered.await(10, SECONDS))
            read(tracker).get(10, SECONDS)
            assertFalse("the first reader is still $stillDoing", first.isDone)
        } finally {
            gate.open.countDown()
        }
        first.get(10, SECONDS)
    }

    /** A tracker naming each file by [naming] and walking each arrived directory by [walk], else as the tracker does. */
    private fun tracker(
        naming: (VirtualFile) -> Set<String>,
        walk: (VirtualFile, (VirtualFile) -> Unit) -> Unit = ModuleNamesTracker.Companion::walkFiles,
    ): ModuleNamesTracker = ModuleNamesTracker(project, naming, walk).also { Disposer.register(testRootDisposable, it) }

    /** Runs [body] on a directory named [name] beside the project's, deleted after. */
    private fun outside(name: String, body: (VirtualFile) -> Unit) {
        val directory = WriteAction.computeAndWait<VirtualFile, Throwable> {
            myFixture.tempDirFixture.getFile("")!!.parent.createChildDirectory(this, name)
        }
        try {
            body(directory)
        } finally {
            WriteAction.runAndWait<Throwable> { directory.delete(this) }
        }
    }

    /** A value held on [tracker] is the same at two reads. */
    private fun assertHolds(tracker: ModificationTracker) {
        val held = CachedValuesManager.getManager(project).createCachedValue { CachedValueProvider.Result.create(Any(), tracker) }

        assertSame(held.value, held.value)
    }

    /** `a.ex` defines `A`. */
    private fun namesOf(file: VirtualFile): Set<String> = setOf(file.nameWithoutExtension.uppercase())

    private fun read(tracker: ModuleNamesTracker): Future<Long> =
        AppExecutorUtil.getAppExecutorService().submit(Callable { count(tracker) })

    private fun count(tracker: ModuleNamesTracker): Long =
        ReadAction.computeBlocking<Long, Throwable> { tracker.modificationCount }

    private fun count(): Long = count(ModuleNamesTracker.getInstance(project))

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
