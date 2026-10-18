package org.elixir_lang.declaration

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.util.io.FileUtil
import com.intellij.model.psi.PsiSymbolReferenceService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.model.psi.atom.AtomReference
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.reference.CallDefinitionClause
import org.elixir_lang.reference.Callable
import org.elixir_lang.reference.CaptureNameArity
import org.elixir_lang.reference.resolver.Callable as CallableResolver
import java.io.File

/**
 * A use's `resolve()` is one of its `multiResolve(false)` results, and so is every declaration the preference step
 * keeps at it, each delegation and each admitted target paired under it.
 */
class ResolveInMultiResolveTest : PlatformTestCase() {
    fun testACallOfADelegation() {
        delegation()
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: M.<caret>x(1)\nend\n")

        assertResolveInMultiResolve()
    }

    fun testACallOfAnImportedDelegation() {
        delegation()
        myFixture.configureByText("use.ex", "defmodule U do\n  import M\n\n  def run, do: <caret>x(1)\nend\n")

        assertResolveInMultiResolve()
    }

    fun testACaptureOfADelegation() {
        delegation()
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: &M.<caret>x/1\nend\n")

        assertResolveInMultiResolve()
    }

    fun testACallOfSeveralClauses() {
        myFixture.configureByText(
            "m.ex",
            "defmodule M do\n  def f(1), do: 1\n  def f(n), do: n\n\n  def run, do: <caret>f(2)\nend\n"
        )

        assertResolveInMultiResolve()
    }

    fun testASpecOfADelegation() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def x(a), do: a\nend\n")
        myFixture.configureByText("m.ex", "defmodule M do\n  @spec <caret>x(term) :: term\n  defdelegate x(a), to: T\nend\n")

        assertResolveInMultiResolve()
    }

    fun testAnMfaAtomOfADelegation() {
        delegation()
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: apply(M, :<caret>x, [1])\nend\n")

        assertResolveInMultiResolve()
    }

    /** A delegation in a dependency, outside the module the use and the target are in, as in an umbrella. */
    fun testACallOfADelegationInADependency() {
        val dependency = FileUtil.createTempDirectory("dependency", null)
        File(dependency, "m.ex").writeText("defmodule M do\n  defdelegate x(a), to: T\nend\n")
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dependency)!!
        val library = WriteAction.computeAndWait<Library, Throwable> {
            LibraryTablesRegistrar.getInstance().getLibraryTable(project).createLibrary("dependency").also { library ->
                library.modifiableModel.apply {
                    addRoot(root, OrderRootType.SOURCES)
                    commit()
                }
            }
        }
        ModuleRootModificationUtil.addDependency(myFixture.module, library)

        try {
            myFixture.addFileToProject("t.ex", "defmodule T do\n  def x(a), do: a\nend\n")
            myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: M.<caret>x(1)\nend\n")

            assertTrue(
                "the delegation in the dependency is a survivor",
                readAction { survivors().any { it.containingFile.virtualFile.parent == root } }
            )
            assertResolveInMultiResolve()
        } finally {
            ModuleRootModificationUtil.removeDependency(myFixture.module, library)
            WriteAction.runAndWait<Throwable> { LibraryTablesRegistrar.getInstance().getLibraryTable(project).removeLibrary(library) }
            FileUtil.delete(dependency)
        }
    }

    private fun delegation() {
        myFixture.addFileToProject("t.ex", "defmodule T do\n  def x(a), do: a\nend\n")
        myFixture.addFileToProject("m.ex", "defmodule M do\n  defdelegate x(a), to: T\nend\n")
    }

    private fun assertResolveInMultiResolve() {
        readAction {
            val reference = reference()
            val results = reference.multiResolve(false).map { it.element }
            val survivors = survivors()

            assertTrue("the step keeps something", survivors.isNotEmpty())

            for (survivor in survivors) {
                assertTrue("${survivor.text} in $results", survivor in results)
            }

            reference.resolve()?.let { resolved -> assertTrue("resolve() ${resolved.text} in $results", resolved in results) }
        }
    }

    /** Each [Preferred.found] the step keeps at the use, and what it delegates to. */
    private fun survivors(): List<PsiElement> {
        val (use, bound) = when (val reference = reference()) {
            is Callable -> Use.of(reference.element)!! to CallableResolver.answer(reference.element, false).bound
            is CaptureNameArity -> Use.capture(reference.nameElement, reference.arity)!! to false
            is CallDefinitionClause -> Use.specification(reference.element, reference.moduleAttribute)!! to false
            is AtomReference -> Use.mfa(reference.element)!! to false
            else -> error("no use for $reference")
        }

        return preferred(use, sourceFor(Feature.SYMBOL_REFERENCES).candidates(use, false), false, bound)
            .flatMap { listOf(it.found) + it.admittedDelegatedTo }
            .map { it.element }
    }

    @Suppress("UnstableApiUsage")
    private fun reference(): PsiPolyVariantReference =
        references().singleOrNull { it is Callable || it is CaptureNameArity || it is CallDefinitionClause }
            as PsiPolyVariantReference?
            ?: PsiSymbolReferenceService.getService()
                .getReferences(PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(myFixture.caretOffset), ElixirAtom::class.java)!!)
                .filterIsInstance<AtomReference>()
                .single()

    private fun references(): List<PsiReference> =
        when (val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)) {
            is PsiMultiReference -> reference.references.toList()
            null -> emptyList()
            else -> listOf(reference)
        }

    private fun <T> readAction(block: () -> T): T = ReadAction.computeBlocking<T, Throwable> { block() }
}
