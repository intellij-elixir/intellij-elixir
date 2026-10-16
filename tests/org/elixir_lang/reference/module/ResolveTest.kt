package org.elixir_lang.reference.module

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import org.elixir_lang.PlatformTestCase
import org.elixir_lang.psi.AtOperation
import org.elixir_lang.psi.ElixirAtom
import org.elixir_lang.psi.QualifiableAlias
import org.elixir_lang.psi.__MODULE__
import org.elixir_lang.psi.call.Call
import org.elixir_lang.reference.Module

/**
 * What a module alias's, an atom's, a `__MODULE__`'s and a LEEx assign's reference resolves to: the first valid of its
 * `multiResolve(false)`, else nothing. Unit tests of the overrides: Go To at each reads `multiResolve`.
 */
class ResolveTest : PlatformTestCase() {
    /** The `alias` the use was reached through stays among the results, but is not what the use names. */
    fun testAnAliasedModuleResolvesToItsDefmodule() {
        myFixture.addFileToProject("b.ex", "defmodule Foo.Bar do\nend\n")
        myFixture.configureByText("use.ex", "defmodule U do\n  alias Foo.Bar\n\n  def run, do: <caret>Bar\nend\n")

        assertResolves(
            reference(PsiTreeUtil.getParentOfType(elementAtCaret(), QualifiableAlias::class.java)!!),
            "b.ex:1: defmodule Foo.Bar do",
            listOf("b.ex:1: defmodule Foo.Bar do (true)", "use.ex:2: alias Foo.Bar (true)")
        )
    }

    fun testAModuleDefinedTwiceResolvesToTheFirst() {
        myFixture.addFileToProject("b1.ex", "defmodule Foo.Bar do\nend\n")
        myFixture.addFileToProject("b2.ex", "defmodule Foo.Bar do\nend\n")
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: Foo.<caret>Bar\nend\n")

        assertResolves(
            readAction { assertInstanceOf(myFixture.file.findReferenceAt(myFixture.caretOffset), Module::class.java) },
            "b1.ex:1: defmodule Foo.Bar do",
            listOf("b1.ex:1: defmodule Foo.Bar do (true)", "b2.ex:1: defmodule Foo.Bar do (true)")
        )
    }

    fun testAnAtomNamingAModuleDefinedTwiceResolvesToTheFirst() {
        myFixture.addFileToProject("b1.ex", "defmodule Foo.Bar do\nend\n")
        myFixture.addFileToProject("b2.ex", "defmodule Foo.Bar do\nend\n")
        myFixture.configureByText("use.ex", "defmodule U do\n  def run, do: <caret>:\"Elixir.Foo.Bar\"\nend\n")

        assertResolves(
            reference(PsiTreeUtil.getParentOfType(elementAtCaret(), ElixirAtom::class.java, false)!!),
            "b1.ex:1: defmodule Foo.Bar do",
            listOf("b1.ex:1: defmodule Foo.Bar do (true)", "b2.ex:1: defmodule Foo.Bar do (true)")
        )
    }

    fun test__MODULE__ResolvesToItsModule() {
        myFixture.configureByText("m.ex", "defmodule M do\n  def run, do: <caret>__MODULE__\nend\n")

        val call = readAction {
            PsiTreeUtil.findChildrenOfType(myFixture.file, Call::class.java).single { it.text == "__MODULE__" }
        }

        assertResolves(
            readAction { __MODULE__.reference(call) as PsiPolyVariantReference },
            "m.ex:1: defmodule M do",
            listOf("m.ex:1: defmodule M do (true)")
        )
    }

    fun testAnAssignSetInTwoFunctionsResolvesToTheFirst() {
        myFixture.addFileToProject(
            "lib/app_web/live/count_live.ex",
            """
            defmodule CountLive do
              def mount(_params, _session, socket) do
                assign(socket, :count, 0)
              end

              def handle_event("inc", _params, socket) do
                assign(socket, :count, 1)
              end
            end
            """.trimIndent()
        )
        val template = myFixture.addFileToProject("lib/app_web/live/count_live.html.leex", "<%= @count %>")
        myFixture.configureFromExistingVirtualFile(template.virtualFile)

        val atOperation = readAction {
            val elixirRoot = myFixture.file.viewProvider.allFiles.first { it.language.id == "Elixir" }

            PsiTreeUtil.findChildOfType(elixirRoot, AtOperation::class.java)!!
        }

        assertResolves(
            reference(atOperation),
            "count_live.ex:3: assign(socket, :count, 0)",
            listOf("count_live.ex:3: assign(socket, :count, 0) (true)", "count_live.ex:7: assign(socket, :count, 1) (true)")
        )
    }

    private fun elementAtCaret(): PsiElement = readAction { myFixture.file.findElementAt(myFixture.caretOffset)!! }

    private fun reference(element: PsiElement): PsiPolyVariantReference =
        readAction { element.references.filterIsInstance<PsiPolyVariantReference>().single() }

    private fun assertResolves(reference: PsiPolyVariantReference, resolved: String?, results: List<String>) {
        readAction {
            assertEquals(
                "multiResolve(false)",
                results,
                reference.multiResolve(false).map { "${describe(it.element!!)} (${it.isValidResult})" }
            )
            assertEquals("resolve()", resolved, reference.resolve()?.let(::describe))
        }
    }

    private fun describe(element: PsiElement): String {
        val file = element.containingFile
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val line = document.getLineNumber(element.textRange.startOffset)
        val text = document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))

        return "${file.name}:${line + 1}: ${text.trim()}"
    }

    private fun <T> readAction(block: () -> T): T = ReadAction.computeBlocking<T, Throwable> { block() }
}
