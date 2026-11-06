package org.elixir_lang.expander

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.IndexingTestUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.beam.CompiledGuardTest.Companion.GUARDS
import org.elixir_lang.beam.CompiledGuardTest.Companion.KERNEL
import org.elixir_lang.expander.MirrorPassRows.loweredGuards
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
        VfsRootAccess.allowRootAccess(testRootDisposable, GUARDS.path, KERNEL.path, OWN_BEHAVIOUR_INFO.path)
        allowAppBeamRoots(testRootDisposable)
    }

    fun testInfoOfAnElixirModule() {
        assertPassTakes(mirror(File(GUARDS, "Elixir.Guards.beam")), "def __info__/1")
    }

    fun testModuleInfoOfAnErlangModule() {
        assertPassTakes(mirror(File(KERNEL, "elixir_interpolation.beam")), "def module_info/0", "def module_info/1")
    }

    /** `GenServer`'s definitions are read and don't list its `behaviour_info/1`, the one the compiler added for its callbacks. */
    fun testBehaviourInfo() {
        val mirror = mirror(appBeams.getValue("Elixir.GenServer"))

        assertEquals("written lists behaviour_info/1", false, MirrorPassRows.written(mirror)?.contains(ModuleExports.BEHAVIOUR_INFO))
        assertPassTakes(mirror, "def behaviour_info/1")
    }

    /** A module with no `@callback` may write `behaviour_info/1` itself, and the pass keeps it, so its own call reaches it. */
    fun testOwnBehaviourInfoIsKept() {
        val mirror = mirror(File(OWN_BEHAVIOUR_INFO, "Elixir.OwnBehaviourInfo.beam"))
        val result = expansion(holder, mirror).modules.single()
        val output = compilerOutput(MirrorPassRows.lowered(mirror)) { MirrorPassRows.written(mirror) }

        assertEquals(
            "${result.module} ended ${result.ended}",
            "output [def behaviour_info/1] reports [] ended null",
            "output ${MirrorPassRows.defined(output).filter { it == "def behaviour_info/1" }.distinct()} " +
                "reports ${result.errors.map { it.kind }} ended ${MirrorPassRows.endedError(result)?.second?.kind}",
        )
    }

    /** That `behaviour_info/1` is in the module's `__info__(:functions)`, so a module importing it can call it. */
    fun testOwnBehaviourInfoIsImported() {
        val ebin = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(OWN_BEHAVIOUR_INFO)!!
        BeamLibraryFixture.addLibrary(project, module, "own_behaviour_info", listOf(ebin))
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val source = myFixture.addFileToProject(
            "importer.ex",
            "defmodule Importer do\n  import OwnBehaviourInfo\n\n  def f, do: behaviour_info(:callbacks)\nend\n",
        )
        val result = expansion(holder, source).modules.single()

        assertEquals(
            "${result.module} ended ${result.ended}",
            "reports [] ended null",
            "reports ${result.errors.map { it.kind }} ended ${MirrorPassRows.endedError(result)?.second?.kind}",
        )
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
     * [mirror] holds each of [stubs], the pass's output for it none of them nor any other the pass owns but each guard
     * of [stubs] as its macro, and its expansion reports or raises nothing such a stub would.
     */
    private fun assertPassTakes(mirror: ElixirFile, vararg stubs: String) {
        val result = expansion(holder, mirror).modules.single()
        val lowered = stubs.filter { it.startsWith("defguard") }.map { it.replaceFirst("defguard", "defmacro") }.sorted()
        val forms = MirrorPassRows.lowered(mirror)
        val written = MirrorPassRows.written(mirror)
        val output = compilerOutput(forms) { written }

        assertEquals(
            "${result.module} ended ${result.ended}",
            "mirror ${stubs.sorted()} output [] lowered $lowered reports []",
            "mirror ${ownedStubs(forms, written).filter { it in stubs }.sorted()} output ${ownedStubs(output, written)} " +
                "lowered ${loweredGuards(forms, output).filter { it in lowered }} reports ${ownedErrors(result)}",
        )
    }

    private fun mirror(beam: File): ElixirFile = mirrorOf(project, beam)!!

    companion object {
        /** `testData/org/elixir_lang/beam/compiler_output/build.exs`'s `.beam`. */
        private val OWN_BEHAVIOUR_INFO = File("testData/org/elixir_lang/beam/compiler_output/ebin").absoluteFile
    }
}
