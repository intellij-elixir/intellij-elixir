package org.elixir_lang.beam

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiManager
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.psi.impl.ModuleImpl
import org.elixir_lang.declaration.Definer
import java.io.File

class CompiledGuardTest : PlatformTestCase() {
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

    private fun assertDefiner(expected: Definer, ebin: File, beam: String, name: String, arity: Int) {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(ebin, beam))!!
        val compiled = PsiManager.getInstance(project).findFile(virtualFile) as PsiCompiledFile
        val definitions = (compiled.children.single() as ModuleImpl<*>).callDefinitions()
        val definition = definitions.singleOrNull {
            it.nameArityInterval.name == name && it.nameArityInterval.arityInterval.minimum == arity
        }

        assertNotNull("no $name/$arity in ${definitions.map { it.nameArityInterval }}", definition)
        assertEquals(expected.capabilities, definition!!.capabilities)
    }

    companion object {
        private val GUARDS = File("testData/org/elixir_lang/beam/compiled_guard/ebin").absoluteFile
        private val KERNEL = File("testData/org/elixir_lang/beam/parser/elixir-1.19.5-otp-28").absoluteFile
    }
}
