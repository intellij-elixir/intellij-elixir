package org.elixir_lang.expander

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiFile
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.lowering.LoweringCounters
import org.elixir_lang.lowering.inspect
import java.io.File
import java.util.concurrent.Callable

/**
 * Another module's clauses, read on demand from the copy Elixir loads for the file that asks, and held apart from its
 * exports within their own bound.
 */
class ClauseCacheTest : PlatformTestCase() {
    private val holder: ProjectModules get() = ProjectModules.getInstance(project)

    fun testASourceModulesClausesAreReadFromItsFile() {
        val a = add("a.ex", "defmodule A do\nend\n")
        val b = add("b.ex", "defmodule B do\n  defmacro m, do: quote(do: :ok)\nend\n")
        val u = add("u.ex", "defmodule U do\nend\n")
        val first = clauses(a, "Elixir.B")

        assertEquals("SOURCE ${b.virtualFile.path}\n  defmacro m/0: 0 arguments quoted :ok", render(first))
        assertSame("asked again", first, clauses(a, "Elixir.B"))

        edit(project, u, "defmodule U do\n  def f, do: 1\nend\n")
        assertSame("an unrelated file edited", first, clauses(a, "Elixir.B"))

        edit(project, b, "defmodule B do\n  defmacro m, do: quote(do: :changed)\n  defp g(x), do: x\nend\n")
        assertEquals(
            "SOURCE ${b.virtualFile.path}\n  defmacro m/0: 0 arguments quoted :changed\n  defp g/1: 1 arguments",
            render(clauses(a, "Elixir.B")),
        )
    }

    /** The expansion that read a module's exports keeps its clauses, so reading them doesn't expand its file again. */
    fun testAModulesExportsAndClausesExpandItsFileOnce() {
        val a = add("a.ex", "defmodule A do\nend\n")
        add("b.ex", "defmodule B do\n  defmacro m, do: quote(do: :ok)\nend\n")

        LoweringCounters.reset()
        LoweringCounters.counting = true
        try {
            ReadAction.nonBlocking(Callable { holder.exports(a, "Elixir.B", legInputs) }).executeSynchronously()
            clauses(a, "Elixir.B")
        } finally {
            LoweringCounters.counting = false
        }

        assertEquals("b.ex expanded", 1L, LoweringCounters.tokenizations.sum())
    }

    fun testABeamsClausesAreItsDebugInfo() {
        val beam = File("testData/org/elixir_lang/expander/loaded_copy/Elixir.LoadedCopy.Dep.beam")
        val ebin = copiedLibrary(beam)
        val a = add("a.ex", "defmodule A do\nend\n")
        val path = ebin.findChild(beam.name)!!.path
        val first = clauses(a, "Elixir.LoadedCopy.Dep")

        assertEquals("DBGI $path\n  def f/0: 0 arguments", render(first))
        assertSame("asked again", first, clauses(a, "Elixir.LoadedCopy.Dep"))

        val rebuilt = File("testData/org/elixir_lang/expander/loaded_copy/changed/${beam.name}").readBytes()
        WriteAction.runAndWait<Throwable> { ebin.findChild(beam.name)!!.setBinaryContent(rebuilt) }

        assertEquals("DBGI $path\n  def g/0: 0 arguments", render(clauses(a, "Elixir.LoadedCopy.Dep")))
    }

    fun testAModuleWithSeveralCopiesHasNoClauses() {
        val a = add("a.ex", "defmodule A do\nend\n")
        add("b.ex", "defmodule B do\n  def f, do: 1\nend\n")
        add("c.ex", "defmodule B do\n  def f, do: 2\nend\n")

        assertNull(clauses(a, "Elixir.B"))
    }

    fun testAModuleNoFileDefinesHasNoClauses() {
        val a = add("a.ex", "defmodule A do\nend\n")

        assertNull(clauses(a, "Elixir.B"))
    }

    /** The asker's own file is compiling, so none of its modules is loaded yet. */
    fun testAModuleOfTheAskersFileHasNoClauses() {
        val a = add("a.ex", "defmodule B do\n  def f, do: 1\nend\n\ndefmodule A do\nend\n")

        assertNull(clauses(a, "Elixir.B"))
    }

    fun testAModuleThatStopsHasNoClauses() {
        val a = add("a.ex", "defmodule A do\nend\n")
        add("b.ex", "defmodule B do\n  def f, do: 1\n  require U\n  U.f()\nend\n")
        add("u1.ex", "defmodule U do\nend\n")
        add("u2.ex", "defmodule U do\nend\n")

        assertNull(clauses(a, "Elixir.B"))
    }

    /** A module defined again, where the later definition stops, loads with none of the earlier one's clauses. */
    fun testAModuleRedefinedSoItStopsHasNoClauses() {
        val a = add("a.ex", "defmodule A do\nend\n")
        add("b.ex", "defmodule B do\n  def f, do: 1\nend\n\ndefmodule B do\n  def g, do: 1\n  require U\n  U.f()\nend\n")
        add("u1.ex", "defmodule U do\nend\n")
        add("u2.ex", "defmodule U do\nend\n")

        assertNull(clauses(a, "Elixir.B"))
    }

    fun testDumbHasNoClauses() {
        val a = add("a.ex", "defmodule A do\nend\n")
        add("b.ex", "defmodule B do\n  def f, do: 1\nend\n")

        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertNull(clauses(a, "Elixir.B"))
        }
        assertEquals("SOURCE /src/b.ex\n  def f/0: 0 arguments", render(clauses(a, "Elixir.B")))
    }

    fun testTheLeastRecentlyUsedIsEvictedFirst() {
        val cache = ClauseCache<String, String>(10) { it.length.toLong() }
        cache["a"] = "aaaa"
        cache["b"] = "bbbb"
        cache["a"]
        cache["c"] = "cccc"

        assertEquals(listOf("aaaa", null, "cccc"), listOf(cache["a"], cache["b"], cache["c"]))
    }

    /** Clauses the bound doesn't hold are read again from their file, and answer as before. */
    fun testAnEvictedEntryIsReadAgain() {
        val a = add("a.ex", "defmodule A do\nend\n")
        val b = add("b.ex", "defmodule B do\n  defmacro m, do: quote(do: :ok)\nend\n")

        holder.boundClauses(1)
        try {
            val first = clauses(a, "Elixir.B")
            val again = clauses(a, "Elixir.B")

            assertNotSame("read again", first, again)
            assertEquals("SOURCE ${b.virtualFile.path}\n  defmacro m/0: 0 arguments quoted :ok", render(first))
            assertEquals(render(first), render(again))
        } finally {
            holder.boundClauses(null)
        }
    }

    fun testAnEntryHeavierThanTheBoundIsNotHeld() {
        val cache = ClauseCache<String, String>(10) { it.length.toLong() }
        cache["a"] = "aaaa"
        cache["b"] = "bbbbbbbbbbb"

        assertEquals(listOf("aaaa", null), listOf(cache["a"], cache["b"]))
    }

    fun testAReplacedEntryWeighsOnlyItsNewValue() {
        val cache = ClauseCache<String, String>(10) { it.length.toLong() }
        cache["a"] = "aaaa"
        cache["b"] = "bbbb"
        cache["a"] = "aa"
        cache["c"] = "cccc"

        assertEquals(listOf("aa", "bbbb", "cccc"), listOf(cache["a"], cache["b"], cache["c"]))
    }

    /** An entry is weighed by what it retains: each clause, and each node its arguments, guards, body and quotes hold. */
    fun testClausesWeighWhatTheyRetain() {
        val a = add("a.ex", "defmodule A do\nend\n")
        add("b.ex", "defmodule B do\n  def f(1) when true, do: 2\n  defmacro m, do: quote(do: 3)\nend\n")

        // `f`: its clause, `1`, `true` and `2`. `m`: its clause; `quote(do: 3)`, its callee, `[do: 3]`, `{:do, 3}`, `:do`
        // and `3`; and the escaped `3`.
        assertEquals(ModuleClauses.BYTES_PER_NODE * (4 + 8), clauses(a, "Elixir.B")!!.weight())
    }

    /** A binary's bytes weigh too, once for each array, as a clause retains them. */
    fun testABinaryWeighsItsBytes() {
        val a = add("a.ex", "defmodule A do\nend\n")
        val long = "x".repeat(1000)
        add(
            "b.ex",
            "defmodule B do\n  def f, do: \"$long\"\nend\n\n" +
                "defmodule C do\n  def f, do: \"x\"\nend\n\n" +
                "defmodule D do\n  defmacro m, do: quote(do: \"$long\")\nend\n\n" +
                "defmodule E do\n  defmacro m, do: quote(do: \"x\")\nend\n",
        )

        assertEquals(999L, clauses(a, "Elixir.B")!!.weight() - clauses(a, "Elixir.C")!!.weight())
        // The escaped block holds the quoted binary's own array.
        assertEquals(999L, clauses(a, "Elixir.D")!!.weight() - clauses(a, "Elixir.E")!!.weight())
    }

    private fun clauses(asker: PsiFile, atom: String): ModuleClauses? =
        ReadAction.nonBlocking(Callable { holder.clauses(asker, atom, legInputs) }).executeSynchronously()

    /** [clauses]' source and file, then each definition's kind, name and arity, and each clause's arity and quote. */
    private fun render(clauses: ModuleClauses?): String {
        clauses ?: return "none"

        return "${clauses.source} ${clauses.file}\n" + clauses.definitions.entries.joinToString("\n") { (nameArity, definition) ->
            val described = "  ${definition.kind.name.lowercase()} ${nameArity.name}/${nameArity.arity}"

            definition.clauses?.joinToString("; ", "$described: ") { clause ->
                "${clause.args.size} arguments" + clause.body.let(clauses.quotes::of)?.let { " quoted ${inspect(it.toOtp())}" }.orEmpty()
            } ?: "$described unknown"
        }
    }

    /** A library holding a copy of [beam], whose `ebin` the test may rewrite. */
    private fun copiedLibrary(beam: File): VirtualFile {
        val directory = FileUtil.createTempDirectory("clause_cache_ebin", null, true)
        beam.copyTo(File(directory, beam.name))
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.path)
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
        BeamLibraryFixture.addLibrary(project, module, "clause_cache_ebin", listOf(root), emptyList())
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        return root
    }

    private fun add(path: String, text: String): PsiFile = myFixture.addFileToProject(path, text)
}
