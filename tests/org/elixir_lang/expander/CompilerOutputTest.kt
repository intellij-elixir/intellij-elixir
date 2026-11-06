package org.elixir_lang.expander

import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.CompiledGuardTest.Companion.GUARDS
import org.elixir_lang.beam.CompiledGuardTest.Companion.KERNEL
import org.elixir_lang.expander.MirrorPassRows.ownedErrors
import org.elixir_lang.expander.MirrorPassRows.ownedStubs
import org.elixir_lang.psi.ElixirFile
import java.io.File

/**
 * A `.beam`'s decompiled source, through the mirror pass, holds none of the definitions the compiler added to the
 * `.beam` or refuses in source, and writes its guards as macros, so its expansion reports or raises nothing of theirs.
 */
class CompilerOutputTest : PlatformTestCase() {
    private val holder: ProjectModules get() = ProjectModules.getInstance(project)

    override fun setUp() {
        super.setUp()
        VfsRootAccess.allowRootAccess(testRootDisposable, GUARDS.path, KERNEL.path)
        allowAppBeamRoots(testRootDisposable)
    }

    fun testInfoOfAnElixirModule() {
        assertPassTakes(mirror(File(GUARDS, "Elixir.Guards.beam")), "def __info__/1")
    }

    fun testModuleInfoOfAnErlangModule() {
        assertPassTakes(mirror(File(KERNEL, "elixir_interpolation.beam")), "def module_info/0", "def module_info/1")
    }

    fun testBehaviourInfo() {
        assertPassTakes(mirror(appBeams.getValue("Elixir.GenServer")), "def behaviour_info/1")
    }

    fun testIsRecordOfErlang() {
        assertPassTakes(mirror(appBeams.getValue("erlang")), "def is_record/2")
    }

    fun testGuards() {
        assertPassTakes(
            mirror(File(GUARDS, "Elixir.Guards.beam")),
            "defguard defaulted/1",
            "defguard defaulted/2",
            "defguard documented/1",
            "defguard hidden/1",
        )
    }

    fun testGuardsWithDocsOff() {
        assertPassTakes(
            mirror(File(GUARDS, "Elixir.UndocumentedGuards.beam")),
            "defguard undocumented/1",
            "defguard undocumented_defaulted/1",
            "defguard undocumented_defaulted/2",
        )
    }

    fun testInfoInSourceStillRaises() {
        val source = myFixture.addFileToProject("info.ex", "defmodule Info do\n  def __info__(x), do: x\nend\n")

        val ended = expansion(holder, source).modules.single().ended

        assertEquals("$ended", "__info__", (ended as? ExpansionResult.Ended.Raised)?.error?.kind)
    }

    /**
     * [mirror] holds each of [stubs], and its expansion reports or raises nothing such a stub would.
     */
    private fun assertPassTakes(mirror: ElixirFile, vararg stubs: String) {
        val result = expansion(holder, mirror).modules.single()

        assertEquals(
            "${result.module} ended ${result.ended}",
            "mirror ${stubs.sorted()} reports []",
            "mirror ${ownedStubs(mirror).filter { it in stubs }.sorted()} reports ${ownedErrors(result)}",
        )
    }

    private fun mirror(beam: File): ElixirFile = mirrorOf(project, beam)!!
}
