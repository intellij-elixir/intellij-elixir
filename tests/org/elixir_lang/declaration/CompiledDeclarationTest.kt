package org.elixir_lang.declaration

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiCompiledFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.ResolveState
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.psi.CallDefinition
import org.elixir_lang.beam.psi.callDefinition
import org.elixir_lang.beam.psi.impl.ModuleImpl
import org.elixir_lang.call.Visibility
import org.elixir_lang.psi.CallDefinitionClause
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.declaringCall
import org.elixir_lang.structure_view.element.Timed
import java.io.File

class CompiledDeclarationTest : PlatformTestCase() {
    override fun getTestDataPath(): String = EBIN.path

    fun testFunction() {
        val abs = definition("abs", Timed.Time.RUN)

        assertEquals(
            Declaration(
                "abs",
                ArityKnowledge.Exact(1),
                Capabilities(quotesArguments = false, compileTime = false, usableInGuards = false, Visibility.PUBLIC),
                Declared.Compiled(CompiledOrigin(beam, "Kernel", "abs", 1))
            ),
            abs.declaration()
        )
    }

    fun testGuardIsAGuard() {
        val isStruct = definition("is_struct", Timed.Time.COMPILE)

        assertEquals(
            Capabilities(quotesArguments = false, compileTime = true, usableInGuards = true, Visibility.PUBLIC),
            isStruct.capabilities
        )
    }

    fun testPrivate() {
        assertEquals(Visibility.PRIVATE, module().callDefinitions().first { !it.isExported }.capabilities.visibility)
    }

    fun testOriginResolvesBack() {
        val abs = definition("abs", Timed.Time.RUN)
        val isStruct = definition("is_struct", Timed.Time.COMPILE)

        assertSame(abs, (abs.declaration().declared as Declared.Compiled).origin.callDefinition(project))
        assertSame(isStruct, (isStruct.declaration().declared as Declared.Compiled).origin.callDefinition(project))
    }

    fun testOriginOfADecompiledClause() {
        val clause = absClause(decompiled(beam))

        assertSame(clause, sourceOrigin(clause).declaringCall(project))
    }

    fun testOriginOfADecompiledClauseAfterTheBeamChanges() {
        val copied = myFixture.copyFileToProject("Elixir.Kernel.beam", "changed/Elixir.Kernel.beam")
        val origin = sourceOrigin(absClause(decompiled(copied)))

        WriteCommandAction.runWriteCommandAction(project) {
            copied.setBinaryContent(File(EBIN, "elixir_interpolation.beam").readBytes())
        }

        assertNull(origin.declaringCall(project))
    }

    fun testOriginOfADecompiledClauseAfterTheBeamIsDeleted() {
        val copied = myFixture.copyFileToProject("Elixir.Kernel.beam", "gone/Elixir.Kernel.beam")
        val origin = sourceOrigin(absClause(decompiled(copied)))

        WriteCommandAction.runWriteCommandAction(project) { copied.delete(this) }

        assertNull(origin.declaringCall(project))
    }

    fun testOriginOfACopyOfADecompiledFile() {
        val clause = absClause(decompiled(beam).copy() as PsiFile)

        assertSame(clause, sourceOrigin(clause).declaringCall(project))
    }

    fun testOriginOfADeletedBeam() {
        val copied = myFixture.copyFileToProject("Elixir.Kernel.beam", "deleted/Elixir.Kernel.beam")
        val compiled = PsiManager.getInstance(project).findFile(copied) as PsiCompiledFile
        val module = compiled.children.single() as ModuleImpl<*>
        val origin = (module.callDefinitions().first().declaration().declared as Declared.Compiled).origin

        WriteCommandAction.runWriteCommandAction(project) { copied.delete(this) }

        assertNull(origin.callDefinition(project))
    }

    private val beam
        get() = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(EBIN, "Elixir.Kernel.beam"))!!

    private fun decompiled(file: VirtualFile): PsiFile =
        (PsiManager.getInstance(project).findFile(file) as PsiCompiledFile).decompiledPsiFile

    private fun absClause(file: PsiFile): Call =
        generateSequence(file.findElementAt(file.text.indexOf("def abs(number)"))) { it.parent }
            .filterIsInstance<Call>()
            .first { CallDefinitionClause.`is`(it) }

    private fun sourceOrigin(clause: Call): SourceOrigin =
        (CallDefinitionClause.declaration(clause, ResolveState.initial())!!.declared as Declared.Source).origin

    private fun module(): ModuleImpl<*> =
        (PsiManager.getInstance(project).findFile(beam) as PsiCompiledFile).children.single() as ModuleImpl<*>

    private fun definition(name: String, time: Timed.Time): CallDefinition =
        module().callDefinitions().single {
            it.nameArityInterval.name == name && it.nameArityInterval.arityInterval.minimum == 1 && it.time == time
        }

    companion object {
        private val EBIN = File("testData/org/elixir_lang/beam/parser/elixir-1.19.5-otp-28").absoluteFile
    }
}
