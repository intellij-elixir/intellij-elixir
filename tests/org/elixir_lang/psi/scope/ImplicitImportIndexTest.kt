package org.elixir_lang.psi.scope

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.declaration.ExpanderSettingTestCase
import org.elixir_lang.psi.Modular
import org.elixir_lang.psi.call.Call
import org.elixir_lang.psi.scope.WalkTestSupport.location
import org.elixir_lang.psi.stub.type.call.Stub.isModular

/**
 * What the implicit `import Kernel` reads: every clause `Kernel` itself declares, by name, and nothing it only
 * imports.
 */
class ImplicitImportIndexTest : ExpanderSettingTestCase() {
    fun testSyntheticKernel() {
        myFixture.copyDirectoryToProject("psi/scope/implicit_import_index", "")
        val file = PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir("kernel.ex"))!!

        assertIndexIsTheClauseSequence(kernel(file))
    }

    fun testDocsKernel() =
        WalkTestSupport.withLibrary(project, myFixture.module, testRootDisposable, LIBRARY, WalkTestSupport.DOCS_KERNEL) { root ->
            assertIndexIsTheClauseSequence(kernel(PsiManager.getInstance(project).findFile(root.findChild("Elixir.Kernel.ex")!!)!!))
        }

    fun testKernelsPrivateClauseIsNotImported() {
        myFixture.copyDirectoryToProject("psi/scope/implicit_import_index", "")
        myFixture.configureFromTempProjectFile("kernel.ex")

        assertEquals(listOf("kernel.ex:4:3"), valid("kernel_target(1)").map(::location))
        assertEmpty("kernel_private(1) resolved through the implicit import", valid("kernel_private(1)"))
    }

    fun testKernelsPrivateClauseIsNotOffered() {
        myFixture.copyDirectoryToProject("psi/scope/implicit_import_index", "")
        myFixture.configureByText(
            "caller.ex",
            """
            defmodule Caller do
              def go, do: kernel_<caret>
            end
            """.trimIndent()
        )
        val offered = myFixture.completeBasic()?.map { it.lookupString }

        assertNotNull("completing `kernel_` offered no list", offered)
        assertContainsElements(offered!!, "kernel_target")
        assertDoesntContain(offered, "kernel_private")
    }

    /** `Kernel`'s own `import` must not reach every module through the implicit import. */
    fun testKernelsImportIsNotReexported() {
        myFixture.addFileToProject(
            "kernel_fixture.ex",
            """
            defmodule Helper do
              def helper_fun, do: :ok
            end

            defmodule Kernel do
              import Helper

              def target, do: :ok
            end
            """.trimIndent()
        )
        myFixture.configureByText(
            "caller.ex",
            """
            defmodule Caller do
              def go, do: helper_fun()
              def control, do: target()
            end
            """.trimIndent()
        )

        assertEmpty("helper_fun() resolved through Kernel's import", valid("helper_fun()"))
        assertEquals(listOf("kernel_fixture.ex:8:3"), valid("target()").map(::location))
    }

    /** A clause under a module-level `if` is `Kernel`'s own, so the implicit import brings it in. */
    fun testKernelsConditionalClauseIsImported() {
        myFixture.addFileToProject(
            "kernel_fixture.ex",
            """
            defmodule Kernel do
              if true do
                def conditional_fun, do: :ok
              end
            end
            """.trimIndent()
        )
        myFixture.configureByText(
            "caller.ex",
            """
            defmodule Caller do
              def go, do: conditional_fun()
            end
            """.trimIndent()
        )

        assertEquals(listOf("kernel_fixture.ex:3:5"), valid("conditional_fun()").map(::location))
    }

    /** A clause is looked up by the atom a call quotes to, which turns MICRO SIGN into GREEK SMALL LETTER MU. */
    fun testMicroSignIsLookedUpByItsAtom() {
        myFixture.addFileToProject(
            "kernel_fixture.ex",
            """
            defmodule Kernel do
              def src_$MICRO(a), do: a
            end
            """.trimIndent()
        )
        myFixture.configureByText(
            "caller.ex",
            """
            defmodule Caller do
              def go, do: src_$MICRO(1)
            end
            """.trimIndent()
        )

        assertEquals(listOf("kernel_fixture.ex:2:3"), valid("src_$MICRO(1)").map(::location))
    }

    /** `def unquote(name)` declares no atom, so no lookup by name finds it, even by the empty prefix. */
    fun testUnquoteNamedClauseIsNotLookedUpByName() {
        val file = myFixture.addFileToProject(
            "kernel_fixture.ex",
            """
            defmodule Kernel do
              def unquote(name)(x), do: x
            end
            """.trimIndent()
        )
        val index = ImplicitImportIndex.of(kernel(file))
        assertEquals(1, index.clauses.size)
        assertEmpty(index.startingWith("unquote"))
        assertEmpty(index.startingWith(""))
    }

    private fun valid(text: String): List<PsiElement?> {
        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).single { it.text == text }

        return (call.reference as PsiPolyVariantReference).multiResolve(false).filter { it.isValidResult }.map { it.element }
    }

    private fun kernel(file: PsiFile): Call =
        PsiTreeUtil.findChildrenOfType(file, Call::class.java).first { isModular(it) && it.text.startsWith("defmodule Kernel ") }

    private fun assertIndexIsTheClauseSequence(kernel: Call) {
        val sequence = Modular.callDefinitionClauseCallSequence(kernel).toList()
        val byName = ImplicitImportIndex.of(kernel).byName()

        for ((name, clauses) in byName) {
            assertEquals("$name's clauses in sequence order", clauses.sortedBy(sequence::indexOf), clauses)
            assertEquals("$name's clauses starting with $name", clauses, ImplicitImportIndex.of(kernel).startingWith(name).filter { it in clauses })
        }

        val indexed = byName.values.flatten().distinct().sortedBy(sequence::indexOf)
        assertEquals("every clause, in sequence order", indexed, ImplicitImportIndex.of(kernel).startingWith(""))
        assertEquals(
            "the index's clauses against callDefinitionClauseCallSequence(Kernel); dropped: " +
                (sequence - indexed.toSet()).joinToString { "${location(it)} ${it.text.lineSequence().first()}" },
            sequence.map(::location),
            indexed.map(::location)
        )
    }

    override fun getTestDataPath(): String = "testData/org/elixir_lang"

    private companion object {
        const val LIBRARY = "implicit_import_index_kernel"
        const val MICRO = "\u00b5"
    }
}
