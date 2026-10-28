package org.elixir_lang.declaration

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.codeInsight.completion.OffsetsInFile
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiReferenceService
import com.intellij.psi.PsiReferenceService.Hints.NO_HINTS
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.code_insight.completion.insert_handler.QualifiedName
import org.elixir_lang.code_insight.completion.callableUnqualified
import org.elixir_lang.code_insight.completion.qualifiedLookupElements
import org.elixir_lang.code_insight.completion.unqualifiedLookupElements
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.operation.capture.NonNumeric
import org.elixir_lang.psi.qualification.Unqualified
import org.elixir_lang.reference.CaptureNameArity
import java.io.File

/** What completion offers at the start of each reference: its lookup elements, each with what it declares. */
class VisibleTest : PlatformTestCase() {
    fun testCallableDeclarationLookupsAreVisible() = assertLookupsAreVisible("psi/callable_declaration")

    fun testInputsLookupsAreVisible() = assertLookupsAreVisible("snapshot/inputs")

    fun testCallableDeclarationNamesWithoutAnAtomHaveNoDeclaration() = assertNoAtomNoDeclaration("psi/callable_declaration")

    fun testAQualifiedCallSeesWhatQualifiedCompletionOffers() {
        myFixture.configureByFiles("declaration/visible/qualified.ex", "declaration/visible/other.ex")
        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).single { it.text == "Other.public(1)" }
        val visible = sourceFor(Feature.COMPLETION).visible(call).map { it.lookupName to it.element }.toSet()

        myFixture.configureByFile("declaration/visible/completion.ex")
        val offered = myFixture.completeBasic().map { it.lookupString to it.psiElement!! }.toSet()

        assertEquals(setOf("public", "macro", "delegated"), offered.map { it.first }.toSet())
        assertEquals(offered, visible)
    }

    fun testAnAliasAfterADotSeesWhatQualifiedCompletionOffers() {
        myFixture.configureByFile("declaration/visible/other.ex")
        myFixture.configureByFile("declaration/visible/completion.ex")

        assertSeesWhatIsOffered(setOf("public", "macro", "delegated")) { leaf ->
            CompletionPosition.AfterDot(leaf.parent.parent as QualifiableAlias)
        }
    }

    /** `pick`'s first clause has another arity than the capture's, and `padded` has no clause of its arity. */
    fun testAQualifiedCaptureSeesWhatCaptureCompletionOffers() {
        myFixture.configureByFile("declaration/visible/arities.ex")
        myFixture.configureByFile("declaration/visible/capture.ex")

        assertSeesWhatIsOffered(setOf("pad", "pick")) { leaf ->
            val reference = PsiTreeUtil.getParentOfType(leaf, NonNumeric::class.java)!!.reference as CaptureNameArity

            CompletionPosition.Capture(reference.nameElement, reference.arity)
        }
    }

    fun testAnMfaAtomSeesWhatAtomCompletionOffers() {
        myFixture.configureByFile("declaration/visible/mfa.ex")
        myFixture.configureByFile("declaration/visible/mfa_completion.ex")

        assertSeesWhatIsOffered(setOf("pub_one", "pub_two")) { leaf ->
            val atom = PsiTreeUtil.getParentOfType(leaf, ElixirAtom::class.java)!!

            CompletionPosition.Mfa(atom, Use.mfa(atom)!!.module)
        }
    }

    fun testImplicitImportsAreVisible() {
        withKernel {
            for (call in uses("snapshot/inputs").filter { it is Unqualified }) {
                val visible = sourceFor(Feature.COMPLETION).visible(call)
                val bySource = visible.groupingBy { it.element.containingFile.name }.eachCount()

                assertEquals("Kernel at ${call.text}", KERNEL, bySource["Elixir.Kernel.ex"])
                assertEquals("Kernel.SpecialForms at ${call.text}", SPECIAL_FORMS, bySource["Elixir.Kernel.SpecialForms.ex"])
            }
        }
    }

    fun testEveryLookupIsVisible() {
        withKernel {
            for (call in uses("snapshot/inputs").filter { it is Unqualified }) {
                assertEveryLookupIsVisible(call)
            }
        }
    }

    fun testAnOnlyImportGivesItsArities() {
        val visible = imported(
            """
            defmodule Pad do
              def pad(a, b \\ 1), do: {a, b}
            end

            defmodule PadUser do
              import Pad, only: [pad: 1]
              def go, do: pad(1)
            end
            """,
            "pad(1)",
            "pad",
        )

        assertEquals(ArityKnowledge.Range(1, 2), visible.declaration!!.arity)
        assertEquals(setOf(1), visible.importedArities)
    }

    fun testSeveralOnlyAritiesAreAllGiven() {
        val visible = imported(
            """
            defmodule Several do
              def f(a, b \\ 1, c \\ 2), do: {a, b, c}
            end

            defmodule SeveralUser do
              import Several, only: [f: 1, f: 3]
              def go, do: f(1)
            end
            """,
            "f(1)",
            "f",
        )

        assertEquals(setOf(1, 3), visible.importedArities)
    }

    fun testAnExceptImportGivesTheRest() {
        val visible = imported(
            """
            defmodule Queue do
              def snoc(q, x \\ nil), do: [x | q]
            end

            defmodule QueueUser do
              import Queue, except: [snoc: 2]
              def go, do: snoc([])
            end
            """,
            "snoc([])",
            "snoc",
        )

        assertEquals(setOf(1), visible.importedArities)
    }

    fun testAWholeImportGivesNoArities() {
        val visible = imported(
            """
            defmodule Pad do
              def pad(a, b \\ 1), do: {a, b}
            end

            defmodule PadUser do
              import Pad
              def go, do: pad(1)
            end
            """,
            "pad(1)",
            "pad",
        )

        assertNull(visible.importedArities)
    }

    fun testAnOpenIntervalGivesNoArities() {
        val visible = imported(
            """
            defmodule Spliced do
              def f(a \\ 1, unquote_splicing(xs)), do: a
            end

            defmodule SplicedUser do
              import Spliced, only: [f: 0]
              def go, do: f()
            end
            """,
            "f()",
            "f",
        )

        assertNull(visible.importedArities)
    }

    private fun assertLookupsAreVisible(inputDirectory: String) {
        for (call in uses(inputDirectory)) {
            assertEveryLookupIsVisible(call)
        }
    }

    /** Unqualified completion offers only the names Elixir can write unquoted; qualified completion offers each. */
    private fun assertEveryLookupIsVisible(call: Call) {
        val lookups = offered(call).map { it.lookupString to it.psiElement!! }.toSet()
        val visible = sourceFor(Feature.COMPLETION).visible(call)
            .filter { call !is Unqualified || callableUnqualified(it.lookupName) }
            .map { it.lookupName to it.element }
            .toSet()

        val at = "${call.functionName()} at ${location(call)}"

        assertEquals("lookups with no visible entry, $at", emptyList<String>(), render(lookups - visible))
        assertEquals("visible entries with no lookup, $at", emptyList<String>(), render(visible - lookups))
    }

    /**
     * What `visible` gives at the position [position] finds at the caret in completion's copy of the current file is
     * what completion offers there, which is [expected].
     */
    private fun assertSeesWhatIsOffered(expected: Set<String>, position: (PsiElement) -> CompletionPosition) {
        val file = myFixture.file
        val offset = myFixture.caretOffset
        val copy = file.copy() as PsiFile

        WriteAction.runAndWait<Throwable> {
            OffsetsInFile(file).replaceInCopy(copy, offset, offset, CompletionUtil.DUMMY_IDENTIFIER).ensureUpdatedAndGetNewOffsets()
        }

        val visible = sourceFor(Feature.COMPLETION).visible(position(copy.findElementAt(offset)!!))
            .map { it.lookupName to it.element }
            .toSet()
        val offered = myFixture.completeBasic().map { it.lookupString to it.psiElement!! }.toSet()

        assertEquals(expected, offered.map { it.first }.toSet())
        assertEquals(offered, visible)
    }

    private fun render(keys: Set<Pair<String, PsiElement>>): List<String> =
        keys.map { (name, element) -> "$name ${location(element)}" }.sorted()

    private fun location(element: PsiElement): String {
        val file = element.containingFile

        return "${file.name}:${StringUtil.offsetToLineNumber(file.text, element.textOffset) + 1}"
    }

    /** The one entry visible as [name] at the call written [call] in [text]. */
    private fun imported(text: String, call: String, name: String): Visible {
        myFixture.configureByText("imported.ex", text.trimIndent())
        val position = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).single { it.text == call }

        return sourceFor(Feature.COMPLETION).visible(position).single { it.lookupName == name }
    }

    private fun assertNoAtomNoDeclaration(inputDirectory: String) {
        val visible = uses(inputDirectory).flatMap { sourceFor(Feature.COMPLETION).visible(it) }

        assertTrue("an entry with no atom name", visible.any { it.name == null })
        for (entry in visible) {
            assertEquals(entry.toString(), entry.name == null, entry.declaration == null)
            assertEquals(entry.toString(), entry.name, entry.declaration?.name)
        }
    }

    /** `Callable.getVariants` offers call definitions only at an unqualified call. */
    private fun offered(call: Call): List<LookupElement> =
        when (call) {
            is Unqualified -> unqualifiedLookupElements(call)
            is Qualified -> qualifiedLookupElements(CompletionPosition.OfCall(call), QualifiedName.CALL)
            else -> emptyList()
        }

    private fun uses(inputDirectory: String): List<Call> {
        val root = myFixture.copyDirectoryToProject(inputDirectory, "")
        val calls = mutableListOf<Call>()
        VfsUtilCore.iterateChildrenRecursively(root, null) { virtualFile ->
            if (!virtualFile.isDirectory) {
                PsiManager.getInstance(project).findFile(virtualFile)!!.viewProvider.allFiles.forEach { file ->
                    file.accept(object : PsiRecursiveElementWalkingVisitor() {
                        override fun visitElement(element: PsiElement) {
                            super.visitElement(element)
                            PsiReferenceService.getService().getReferences(element, NO_HINTS)
                                .filterIsInstance<org.elixir_lang.reference.Callable>()
                                .forEach { calls += it.element }
                        }
                    })
                }
            }
            true
        }
        assertFalse(calls.isEmpty())

        return calls
    }

    private fun withKernel(block: () -> Unit) {
        val directory = File("testData/org/elixir_lang/beam/decompiler/Docs").absoluteFile
        VfsRootAccess.allowRootAccess(testRootDisposable, directory.path)
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)!!
        BeamLibraryFixture.addLibrary(project, myFixture.module, LIBRARY, listOf(root))

        try {
            block()
        } finally {
            WriteAction.runAndWait<Throwable> {
                val table = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
                table.getLibraryByName(LIBRARY)?.let { library ->
                    ModuleRootModificationUtil.updateModel(myFixture.module) { model ->
                        model.findLibraryOrderEntry(library)?.let(model::removeOrderEntry)
                    }
                    table.removeLibrary(library)
                }
            }
        }
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang"

    private companion object {
        const val LIBRARY = "visible_test_kernel"
        const val KERNEL = 171
        const val SPECIAL_FORMS = 33
    }
}
