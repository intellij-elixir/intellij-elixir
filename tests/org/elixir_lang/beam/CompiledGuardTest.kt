package org.elixir_lang.beam

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiManager
import com.intellij.psi.ResolveState
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.psi.impl.ModuleImpl
import org.elixir_lang.declaration.Definer
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.ElixirFile
import org.elixir_lang.psi.Modular
import java.io.File

class CompiledGuardTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        VfsRootAccess.allowRootAccess(myFixture.testRootDisposable, GUARDS.path, KERNEL.path)
    }

    fun testDocumentedDefguard() {
        assertDefiner(Definer.DEFGUARD, GUARDS, "Elixir.Guards.beam", "documented", 1)
    }

    fun testDefguardUnderDocFalse() {
        assertDefiner(Definer.DEFGUARD, GUARDS, "Elixir.Guards.beam", "hidden", 1)
    }

    fun testDefguardUnderModuledocFalse() {
        assertDefiner(Definer.DEFGUARD, GUARDS, "Elixir.HiddenGuards.beam", "hidden_module", 1)
    }

    fun testDefguardWithDocsOff() {
        assertDefiner(Definer.DEFGUARD, GUARDS, "Elixir.UndocumentedGuards.beam", "undocumented", 1)
    }

    fun testArityADefaultCoversOfADefguardWithDocsOff() {
        assertDefiner(Definer.DEFGUARD, GUARDS, "Elixir.UndocumentedGuards.beam", "undocumented_defaulted", 1)
    }

    fun testMacroUnderDocGuard() {
        assertDefiner(Definer.DEFGUARD, KERNEL, "Elixir.Kernel.beam", "is_struct", 1)
    }

    fun testArityADefaultCoversOfAMacroUnderDocGuard() {
        assertDefiner(Definer.DEFGUARD, GUARDS, "Elixir.Guards.beam", "defaulted", 1)
    }

    fun testPlainDefmacro() {
        assertDefiner(Definer.DEFMACRO, GUARDS, "Elixir.Guards.beam", "plain", 1)
    }

    fun testFunctionUnderDocGuard() {
        assertDefiner(Definer.DEF, KERNEL, "Elixir.Kernel.beam", "abs", 1)
    }

    fun testErlangExport() {
        assertDefiner(Definer.DEF, KERNEL, "elixir_interpolation.beam", "extract", 6)
    }

    fun testDocumentedDefguardHead() {
        assertHead(Definer.DEFGUARD, GUARDS, "Elixir.Guards.beam", "documented", 1)
    }

    fun testDefguardUnderDocFalseHead() {
        assertHead(Definer.DEFGUARD, GUARDS, "Elixir.Guards.beam", "hidden", 1)
    }

    fun testDefguardUnderModuledocFalseHead() {
        assertHead(Definer.DEFGUARD, GUARDS, "Elixir.HiddenGuards.beam", "hidden_module", 1)
    }

    fun testDefguardWithDocsOffHead() {
        assertHead(Definer.DEFGUARD, GUARDS, "Elixir.UndocumentedGuards.beam", "undocumented", 1)
    }

    fun testDefaultedDefguardWithDocsOffHead() {
        assertHead(Definer.DEFGUARD, GUARDS, "Elixir.UndocumentedGuards.beam", "undocumented_defaulted", 2)
    }

    fun testArityADefaultCoversOfADefguardWithDocsOffHead() {
        assertHead(Definer.DEFGUARD, GUARDS, "Elixir.UndocumentedGuards.beam", "undocumented_defaulted", 1)
    }

    fun testMacroUnderDocGuardHead() {
        assertHead(Definer.DEFGUARD, KERNEL, "Elixir.Kernel.beam", "is_struct", 1)
    }

    fun testDefaultedMacroUnderDocGuardHead() {
        assertHead(Definer.DEFGUARD, GUARDS, "Elixir.Guards.beam", "defaulted", 2)
    }

    fun testArityADefaultCoversOfAMacroUnderDocGuardHead() {
        assertHead(Definer.DEFGUARD, GUARDS, "Elixir.Guards.beam", "defaulted", 1)
    }

    fun testPlainDefmacroHead() {
        assertHead(Definer.DEFMACRO, GUARDS, "Elixir.Guards.beam", "plain", 1)
    }

    fun testFunctionUnderDocGuardHead() {
        assertHead(Definer.DEF, KERNEL, "Elixir.Kernel.beam", "abs", 1)
    }

    /** Every clause of [name]/[arity] in [beam]'s decompiled source is written with [expected]. */
    private fun assertHead(expected: Definer, ebin: File, beam: String, name: String, arity: Int) {
        val mirror = compiled(ebin, beam).decompiledPsiFile as ElixirFile
        val definers = Modular.callDefinitionClauseCallSequence(mirror.modulars().single())
            .filter { call ->
                CallDefinitionClause.nameArityInterval(call, ResolveState.initial())
                    ?.let { it.name == name && arity in it.arityInterval.closed() } == true
            }
            .map { CallDefinitionClause.definer(it) }
            .toSet()

        assertEquals("$name/$arity's clauses", setOf(expected), definers)
    }

    private fun compiled(ebin: File, beam: String): PsiCompiledFile {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(ebin, beam))!!

        return PsiManager.getInstance(project).findFile(virtualFile) as PsiCompiledFile
    }

    private fun assertDefiner(expected: Definer, ebin: File, beam: String, name: String, arity: Int) {
        val compiled = compiled(ebin, beam)
        val definitions = (compiled.children.single() as ModuleImpl<*>).callDefinitions()
        val definition = definitions.singleOrNull {
            it.nameArityInterval.name == name && it.nameArityInterval.arityInterval.minimum == arity
        }

        assertNotNull("no $name/$arity in ${definitions.map { it.nameArityInterval }}", definition)
        assertEquals(expected.capabilities, definition!!.capabilities)
    }

    companion object {
        internal val GUARDS: File = File("testData/org/elixir_lang/beam/compiled_guard/ebin").absoluteFile
        internal val KERNEL: File = File("testData/org/elixir_lang/beam/parser/elixir-1.19.5-otp-28").absoluteFile
    }
}
