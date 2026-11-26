package org.elixir_lang.beam

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.psi.impl.ModuleImpl
import org.elixir_lang.expander.legBeams
import java.io.File

/**
 * `ModuleImpl.setMirror` matches compiled stubs to decompiled call definitions by name and arity, so a stub
 * with no counterpart is skipped; pairing them by position instead threw `InvalidMirrorException` and left
 * the whole module without a mirror. [setDefinitionLimitForTests] puts both cases in existing fixtures:
 * above it private functions are never decompiled and their stubs cannot match, below it every stub must.
 */
class StubMirrorTest : PlatformTestCase() {
    override fun getTestDataPath(): String = "testData/org/elixir_lang/beam/decompiler"

    override fun setUp() {
        super.setUp()
        setDefinitionLimitForTests(DEFINITION_LIMIT, testRootDisposable)
    }

    fun testOtpPubKeyMirrorsDespiteUnmatchedStubs() {
        assertMirrorsDespiteUnmatchedStubs("OTP-PUB-KEY")
    }

    fun testGlMirrorsDespiteUnmatchedStubs() {
        assertMirrorsDespiteUnmatchedStubs("gl")
    }

    /**
     * Nothing else asserts that matching still matches: the golden fixtures compare decompiler text and
     * `assertParseable` only looks for parse errors, so this could break entirely and leave a green suite.
     */
    fun testQueueMirrorsEveryStub() {
        val callDefinitions = mirroredModule("queue").callDefinitions()
        assertTrue("queue.beam has no call definitions", callDefinitions.isNotEmpty())

        val unmatched = callDefinitions.filter { it.mirror == null }

        assertEmpty(
            "queue.beam is under the definition limit, so all ${callDefinitions.size} stubs should " +
                "have a mirror. Unmatched: " + unmatched.take(10).joinToString { it.exportedName() },
            unmatched
        )
    }

    /** An Elixir beam's mirror renders each type from the specs its debug info holds, not from the docs chunk. */
    fun testAnElixirModulesMirrorShowsItsPrivateTypesWithTheirBodies() {
        val text = decompiled("Elixir.Module")

        assertTrue("# Private Types in:\n${text.take(2000)}", text.contains("  # Private Types\n"))
        assertTrue("@typep def_kind in:\n${text.take(2000)}", Regex("  @typep def_kind :: \\S").containsMatchIn(text))
    }

    fun testAnElixirBeamsStubsPairWithItsMirror() = assertMirrored("Elixir.Module")

    fun testAnElixirBeamWithManyTypesPairsWithItsMirror() = assertMirrored("Elixir.Ecto.Changeset")

    fun testAnElixirBeamOfOtp28PairsWithItsMirror() = assertMirrored("OTP28/Elixir.Oban")

    fun testErlangsStubsPairWithItsMirror() = assertMirrored("Docs/erlang")

    /** Every Elixir beam of the leg decompiles to parseable Elixir that its stubs pair with, as `ModuleImpl.getMirror` checks. */
    fun testEveryElixirBeamOfTheLegDecompilesAndPairs() {
        val beams = legBeams().filter { it.name.startsWith("Elixir.") }
        assertNotEmpty(beams)
        beams.map(File::getParentFile).distinct().forEach { VfsRootAccess.allowRootAccess(testRootDisposable, it.absolutePath) }

        val problems = beams.mapNotNull { beam ->
            val file = PsiManager.getInstance(project).findFile(LocalFileSystem.getInstance().findFileByIoFile(beam)!!)
            val decompiled = (file as? PsiCompiledFile)?.decompiledPsiFile
            val error = decompiled?.let { PsiTreeUtil.findChildOfType(it, PsiErrorElement::class.java) }
            val module = file?.children?.singleOrNull() as? ModuleImpl<*>

            when {
                decompiled == null -> "${beam.name}: no decompiled file"
                error != null -> "${beam.name}: ${error.errorDescription}"
                module?.mirror == null -> "${beam.name}: no mirror"
                else -> null
            }
        }

        assertEmpty(problems.take(20).joinToString("\n"), problems)
    }

    /** Building the mirror is the assertion: `ModuleImpl.getMirror` reports a rejected one as `LOGGER.error`. */
    private fun assertMirrored(name: String) {
        mirroredModule(name)
    }

    private fun decompiled(name: String): String {
        val beam = File(testDataPath, "$name.beam")
        VfsRootAccess.allowRootAccess(testRootDisposable, beam.parentFile.absolutePath)

        return Decompiler().decompile(LocalFileSystem.getInstance().findFileByIoFile(beam)!!).toString()
    }

    private fun assertMirrorsDespiteUnmatchedStubs(name: String) {
        val callDefinitions = mirroredModule(name).callDefinitions()
        val unmatched = callDefinitions.filter { it.mirror == null }
        val distinctMirrors = callDefinitions.mapNotNull { it.mirror }.distinct()

        // Raising DEFINITION_LIMIT past this fixture decompiles its private functions too, closing
        // the gap and leaving the case below asserting nothing. Repoint it at a larger fixture.
        assertTrue(
            "$name.beam decompiled to ${distinctMirrors.size} call definitions for ${callDefinitions.size} " +
                "stubs, so it no longer exercises a stub/mirror mismatch. Was DEFINITION_LIMIT " +
                "raised past ${callDefinitions.size}?",
            distinctMirrors.size != callDefinitions.size && unmatched.isNotEmpty()
        )

        // An unmatched exported function is the case ModuleImpl.setMirror warns about, and would make this
        // a test about that warning instead.
        val unmatchedExported = unmatched.filter { it.isExported }
        assertEmpty(
            "Unmatched exported functions in $name: " + unmatchedExported.take(10).joinToString { it.exportedName() },
            unmatchedExported
        )

        assertTrue(
            "$name: all ${callDefinitions.size} stubs were unmatched, expected some to be mirrored",
            unmatched.size < callDefinitions.size
        )
    }

    /**
     * `BeamFileImpl.getMirror` turns an `InvalidMirrorException` into `LOGGER.error`, which fails the test
     * as an unexpected log - so building the mirror is itself the assertion that neither
     * `setMirror` call site rejected the decompiled source.
     */
    private fun mirroredModule(name: String): ModuleImpl<*> {
        val beam = File(testDataPath, "$name.beam")
        VfsRootAccess.allowRootAccess(testRootDisposable, beam.parentFile.absolutePath)

        val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(beam)
        assertNotNull("No VirtualFile for $name.beam", virtualFile)

        val compiledFile = PsiManager.getInstance(project).findFile(virtualFile!!)
        assertInstanceOf(compiledFile, PsiCompiledFile::class.java)
        assertNotNull("Decompiled PSI file is null for $name", (compiledFile as PsiCompiledFile).decompiledPsiFile)

        val module = compiledFile.children.singleOrNull() as? ModuleImpl<*>
        assertNotNull("$name.beam has no ModuleImpl child", module)
        assertNotNull("$name's module element has no mirror", module!!.mirror)

        return module
    }

    companion object {
        private const val DEFINITION_LIMIT = 500
    }
}
