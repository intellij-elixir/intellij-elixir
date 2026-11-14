package org.elixir_lang.expander

import com.intellij.openapi.vfs.VirtualFile
import org.elixir_lang.lowering.LoweringCounters

/** The modules a file sees that code it doesn't run followed: its build's config read, and a module's file it ensures is compiled; and what moves them. */
class CodeFollowSeenHeavyTest : ConfigHeavyTestCase() {
    /** A followed module's clauses, gone from the clause cache, are read again with the read, giving the same answer. */
    fun testAFollowedModulesClausesEvictedAreReadAgain() {
        val root = project(CONFIG to REQUIRES_H, "support/h.ex" to H, "lib/p.ex" to reader("P"), "lib/q.ex" to reader("Q"))

        assertEquals(":from_h", v(root, "lib/p.ex"))
        holder.boundClauses(null)
        assertEquals(":from_h", v(root, "lib/q.ex"))
    }

    /** The dev and the test read of one config file each keep their followed modules' clauses, so asking in turn reads neither again. */
    fun testTheDevAndTestReadsOfOneConfigFileEachKeepTheirFollowedClauses() {
        val root = project(
            CONFIG to REQUIRES_H,
            "support/h.ex" to H,
            "lib/p.ex" to reader("P"),
            "lib/q.ex" to reader("Q"),
            "test/t_test.exs" to reader("T"),
        )

        assertEquals(":from_h", v(root, "lib/p.ex"))
        assertEquals(":from_h", v(root, "test/t_test.exs"))
        val (value, parsed) = parsing { v(root, "lib/q.ex") }

        assertEquals(":from_h", value)
        assertEquals("files parsed", 1L, parsed)
    }

    /** A config edit that newly follows a module moves a file that asked for it before. */
    fun testAConfigEditNewlyFollowingAModuleMovesAFileThatAskedForIt() {
        val root = project(CONFIG to "import Config\n", "support/h.ex" to H, "lib/p.ex" to reader("P"))

        assertEquals("unknown", v(root, "lib/p.ex"))
        edit(project, psi(root, CONFIG), REQUIRES_H)
        assertEquals(":from_h", v(root, "lib/p.ex"))
    }

    /** An edit to a file the read followed, through its document, moves a file that reads the module it defines. */
    fun testAnEditToAFollowedFileMovesItsReader() {
        val root = project(CONFIG to REQUIRES_H, "support/h.ex" to H, "lib/p.ex" to reader("P"))

        assertEquals(":from_h", v(root, "lib/p.ex"))
        edit(project, psi(root, "support/h.ex"), H.replace(":from_h", ":from_edit"))
        assertEquals(":from_edit", v(root, "lib/p.ex"))
    }

    /** A followed module's clauses, gone from the clause cache while the file that followed it stays held, are read again. */
    fun testAFollowedModulesClausesEvictedBesideItsHeldFileAreReadAgain() {
        val root = project("support/h.ex" to H, "lib/a.ex" to REQUIRER, "lib/b.ex" to ensurer("B"), "lib/c.ex" to ensurer("C"))

        assertEquals(":from_h", v(root, "lib/b.ex"))
        holder.boundClauses(null)
        assertEquals(":from_h", v(root, "lib/c.ex"))
    }

    /**
     * A module ensured from the file it ensures in turn, so that its file's run holds only for this one, and then called,
     * expands its file once.
     */
    fun testAModuleEnsuredWhileItEnsuresTheEnsurerAndCalledExpandsItsFileOnce() {
        val root = project(
            "lib/a.ex" to "defmodule A do\n  Code.ensure_compiled(B)\n  @v B.x()\n  def r, do: @v\nend\n",
            "lib/b.ex" to "defmodule B do\n  Code.ensure_compiled(A)\n  def x, do: :from_b\nend\n",
            "lib/c.ex" to "defmodule C do\n  Code.ensure_compiled(D)\n  @v D.x()\nend\n",
            "lib/d.ex" to "defmodule D do\n  Code.ensure_compiled(C)\n  def x, do: :from_d\nend\n",
        )
        // The same shape asked first reads the SDK's clauses that `Code.ensure_compiled/1` runs, so only the project's files are parsed below.
        assertEquals(":from_d", v(root, "lib/c.ex"))
        val (value, parsed) = parsing { v(root, "lib/a.ex") }

        assertEquals(":from_b", value)
        assertEquals("files parsed", 2L, parsed)
    }

    /** An edit to a file a module's file followed, through its document, moves a file that ensures the module is compiled. */
    fun testAnEditToAFileAnEnsuredModuleFollowedMovesItsEnsurer() {
        val root = project("support/h.ex" to H, "lib/a.ex" to REQUIRER, "lib/b.ex" to ensurer("B"))

        assertEquals(":from_h", v(root, "lib/b.ex"))
        edit(project, psi(root, "support/h.ex"), H.replace(":from_h", ":from_edit"))
        assertEquals(":from_edit", v(root, "lib/b.ex"))
    }

    /** What [body] gives, and how many files it parsed. */
    private fun <T> parsing(body: () -> T): Pair<T, Long> {
        LoweringCounters.reset()
        LoweringCounters.counting = true
        val value = try {
            body()
        } finally {
            LoweringCounters.counting = false
        }

        return value to LoweringCounters.tokenizations.sum()
    }

    /** `@v`'s final value in the module [path] defines. */
    private fun v(root: VirtualFile, path: String): String =
        rendered(ProjectModules.results(expansion(holder, psi(root, path))).single().attributes.final["v"])

    private companion object {
        const val REQUIRES_H = "import Config\nCode.require_file(\"../support/h.ex\", __DIR__)\n:ok\n"
        const val H = "defmodule H do\n  def v, do: :from_h\nend\n"
        const val REQUIRER = "defmodule A do\n  Code.require_file(\"../support/h.ex\", __DIR__)\nend\n"

        fun reader(module: String) = "defmodule $module do\n  @v H.v()\n  def r, do: @v\nend\n"

        fun ensurer(module: String) = "defmodule $module do\n  Code.ensure_compiled(A)\n  @v H.v()\n  def r, do: @v\nend\n"
    }
}
