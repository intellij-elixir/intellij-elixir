package org.elixir_lang.declaration

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiReferenceService
import com.intellij.psi.PsiReferenceService.Hints.NO_HINTS
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.beam.BeamLibraryFixture
import org.elixir_lang.code_insight.completion.callDefinitionClauseLookupElements
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.call.qualification.Qualified
import org.elixir_lang.psi.impl.qualifierModulars
import org.elixir_lang.psi.qualification.Unqualified
import org.elixir_lang.psi.scope.call_definition_clause.Variants
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

    /**
     * The decompiled heads `def unquote(unquote(:name))(...)`, offered under their whole head, are with the source's
     * `def unquote(...)` heads the only lookups not visible.
     */
    fun testImplicitImportsLookupsAreVisibleButTheirUnquoteHeads() {
        withKernel {
            for (call in uses("snapshot/inputs").filter { it is Unqualified }) {
                val (lookups, visible) = keys(call)
                val notVisible = (lookups - visible).map { it.first }

                assertEquals(emptySet<Pair<String, PsiElement>>(), visible - lookups)
                assertEquals(emptyList<String>(), notVisible.filterNot { it == "unquote" || it.startsWith(DECOMPILED_UNQUOTE) })
            }
        }
    }

    private fun assertLookupsAreVisible(inputDirectory: String) {
        for (call in uses(inputDirectory)) {
            val lookups = offered(call).map { it.lookupString to it.psiElement!! }.toSet()
            val entries = sourceFor(Feature.COMPLETION).visible(call)
            val visible = entries.map { it.lookupName to it.element }.toSet()
            // A visible entry with no name is a spelling to show, with nothing to insert.
            val named = entries.filter { it.name != null }.map { it.lookupName to it.element }.toSet()

            assertEquals("visible without a lookup at ${call.text}", emptySet<Pair<String, PsiElement>>(), named - lookups)
            assertEquals(
                "lookups without a visible entry at ${call.text} other than `def unquote(...)` heads",
                emptyList<String>(),
                (lookups - visible).map { it.first }.filter { it != "unquote" }
            )
        }
    }

    private fun assertNoAtomNoDeclaration(inputDirectory: String) {
        val visible = uses(inputDirectory).flatMap { sourceFor(Feature.COMPLETION).visible(it) }

        assertTrue("an entry with no atom name", visible.any { it.name == null })
        for (entry in visible) {
            assertEquals(entry.toString(), entry.name == null, entry.declaration == null)
            assertEquals(entry.toString(), entry.name, entry.declaration?.name)
        }
    }

    private fun keys(call: Call): Pair<Set<Pair<String, PsiElement>>, Set<Pair<String, PsiElement>>> =
        offered(call).map { it.lookupString to it.psiElement!! }.toSet() to
            sourceFor(Feature.COMPLETION).visible(call).map { it.lookupName to it.element }.toSet()

    /** `Callable.getVariants` offers the walk's call definitions only at an unqualified call. */
    private fun offered(call: Call): List<LookupElement> =
        when (call) {
            is Unqualified -> Variants.lookupElementList(call)
            is Qualified -> call.qualifier().qualifierModulars()?.let { callDefinitionClauseLookupElements(it) }.orEmpty()
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
        const val DECOMPILED_UNQUOTE = "unquote(unquote("
    }
}
