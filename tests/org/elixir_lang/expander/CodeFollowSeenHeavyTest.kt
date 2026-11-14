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

        fun reader(module: String) = "defmodule $module do\n  @v H.v()\n  def r, do: @v\nend\n"
    }
}
