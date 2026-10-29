package org.elixir_lang.reference.resolver

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.ElixirFileType
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.declaration.Feature
import org.elixir_lang.declaration.Use
import org.elixir_lang.declaration.sourceFor
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.call_definition_clause.LegacyWalkSource

/** `multiResolve` and the candidate list read one walk per `incompleteCode`, which an edit to the file refreshes. */
class SharedWalkTest : PlatformTestCase() {
    override fun setUp() {
        super.setUp()
        RecursionManager.assertOnRecursionPrevention(testRootDisposable)
    }

    fun testCandidatesAndMultiResolveShareOneWalkPerIncompleteCode() {
        myFixture.configureByText("shared.ex", SOURCE)
        val use = use(myFixture.file)
        val before = LegacyWalkSource.walkCount()

        candidates(use, false)
        multiResolve(use, false)
        candidates(use, false)
        assertEquals("one walk for incompleteCode = false", 1, LegacyWalkSource.walkCount() - before)

        multiResolve(use, true)
        candidates(use, true)
        assertEquals("one more walk for incompleteCode = true", 2, LegacyWalkSource.walkCount() - before)
    }

    fun testEditingANonPhysicalCopyWalksAgain() {
        myFixture.configureByText("shared.ex", SOURCE)
        val copy = myFixture.file.copy() as PsiFile
        assertFalse(copy.isPhysical)
        val use = use(copy)
        assertEquals(listOf(true), validity(use))
        val before = LegacyWalkSource.walkCount()

        WriteCommandAction.runWriteCommandAction(project) {
            val replacement = PsiFileFactory.getInstance(project)
                .createFileFromText("replacement.ex", ElixirFileType.INSTANCE, "def foo(x, y), do: x")
                .let { PsiTreeUtil.findChildOfType(it, Call::class.java)!! }
            definition(copy).replace(replacement)
        }

        assertEquals("foo/1 became foo/2", listOf(false), validity(use))
        assertEquals(1, LegacyWalkSource.walkCount() - before)
    }

    private fun candidates(use: Call, incompleteCode: Boolean) =
        sourceFor(Feature.SYMBOL_REFERENCES).candidates(Use.of(use)!!, incompleteCode)

    private fun multiResolve(use: Call, incompleteCode: Boolean) =
        (use.reference as PsiPolyVariantReference).multiResolve(incompleteCode)

    private fun validity(use: Call): List<Boolean> =
        multiResolve(use, false).filter { (it.element as? Call)?.functionName() == "def" }.map { it.isValidResult }

    private fun use(file: PsiFile): Call = PsiTreeUtil.findChildrenOfType(file, Call::class.java).single { it.text == "foo(1)" }

    private fun definition(file: PsiFile): Call =
        PsiTreeUtil.findChildrenOfType(file, Call::class.java).single { it.text.startsWith("def foo") }

    companion object {
        private val SOURCE = """
            defmodule M do
              def foo(x), do: x
              def usage, do: foo(1)
            end
        """.trimIndent()
    }
}
